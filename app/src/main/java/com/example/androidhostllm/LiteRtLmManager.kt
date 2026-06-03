package com.example.androidhostllm

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

class LiteRtLmManager(private val appContext: Context) {
    private val mutex = Mutex()
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    @Volatile private var backendStatus: String = "Not loaded"
    @Volatile private var responseMode: ResponseMode = ResponseMode.CODING_CONCISE
    @Volatile private var conversationMode: ConversationMode = ConversationMode.PERSISTENT
    @Volatile private var generationTimeoutSeconds: Int = DEFAULT_GENERATION_TIMEOUT_SECONDS

    @Volatile private var speculativeDecodingRequested: Boolean = true
    @Volatile private var speculativeDecodingEnabled: Boolean = false
    @Volatile private var speculativeDecodingAvailable: Boolean = true
    @Volatile private var speculativeDecodingError: String? = null

    @Volatile private var lastLoadDurationMs: Long? = null
    @Volatile private var lastGenerationStartedAtMs: Long? = null
    @Volatile private var lastFirstChunkLatencyMs: Long? = null
    @Volatile private var lastGenerationDurationMs: Long? = null
    @Volatile private var lastOutputChars: Int = 0
    @Volatile private var lastApproxCharsPerSecond: Double = 0.0
    @Volatile private var lastChunkCount: Int = 0
    @Volatile private var lastStreamingUsed: Boolean = false
    @Volatile private var totalRequests: Long = 0
    @Volatile private var totalErrors: Long = 0
    @Volatile private var activeGeneration: Boolean = false
    @Volatile private var lastErrorShortMessage: String? = null
    @Volatile private var currentGenerationJob: Job? = null
    private val performanceHistory = ArrayDeque<PerformanceHistoryEntry>()

    suspend fun loadModel(modelPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val loadStarted = System.currentTimeMillis()
            lastLoadDurationMs = null
            runCatching {
                val modelFile = File(modelPath)
                require(modelFile.exists()) { "Model file does not exist: $modelPath. Put model.litertlm at the path displayed in the app, or edit the model path before loading." }
                require(modelFile.canRead()) { "Model file is not readable: $modelPath. Check that the file was pushed to the displayed app-specific path and is readable by the app." }

                closeLocked()
                try {
                    initialize(
                        modelPath = modelPath,
                        backend = Backend.GPU(),
                        status = "Loaded with GPU",
                        enableSpeculativeDecoding = speculativeDecodingRequested,
                    )
                } catch (gpuError: Throwable) {
                    closeLocked()
                    try {
                        initialize(
                            modelPath = modelPath,
                            backend = Backend.CPU(),
                            status = "GPU failed, loaded with CPU",
                            enableSpeculativeDecoding = false,
                        )
                    } catch (cpuError: Throwable) {
                        closeLocked(resetStatus = false)
                        backendStatus = "Failed to load"
                        cpuError.addSuppressed(gpuError)
                        throw cpuError
                    }
                }
            }.also {
                lastLoadDurationMs = System.currentTimeMillis() - loadStarted
            }
        }
    }

    suspend fun generate(
        prompt: String,
        conversationModeOverride: ConversationMode? = null,
        responseModeOverride: ResponseMode? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val generationStarted = beginGeneration(streaming = false, job = coroutineContext[Job])
            val effectiveConversationMode = conversationModeOverride ?: conversationMode
            val effectiveResponseMode = responseModeOverride ?: responseMode
            runCatching {
                withTimeout(generationTimeoutSeconds * 1000L) {
                    val activeConversation = createConversationForRequestLocked(effectiveConversationMode)
                    try {
                        val output = activeConversation.sendMessage(prompt).toString()
                        finishGeneration(
                            startedAt = generationStarted,
                            outputChars = output.length,
                            firstChunkAt = System.currentTimeMillis(),
                            chunkCount = if (output.isNotEmpty()) 1 else 0,
                            effectiveConversationMode = effectiveConversationMode,
                            effectiveResponseMode = effectiveResponseMode,
                        )
                        output
                    } finally {
                        closeRequestConversationIfNeeded(activeConversation, effectiveConversationMode)
                    }
                }
            }.recoverCatching { error ->
                if (error is TimeoutCancellationException) {
                    throw GenerationTimeoutException(generationTimeoutSeconds)
                }
                throw error
            }.onFailure {
                finishGeneration(
                    startedAt = generationStarted,
                    outputChars = 0,
                    firstChunkAt = null,
                    chunkCount = 0,
                    effectiveConversationMode = effectiveConversationMode,
                    effectiveResponseMode = effectiveResponseMode,
                    error = it,
                )
                recordGenerationError(it)
            }
        }
    }

    suspend fun generateStreaming(
        prompt: String,
        onChunk: suspend (String) -> Unit,
        conversationModeOverride: ConversationMode? = null,
        responseModeOverride: ResponseMode? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val generationStarted = beginGeneration(streaming = true, job = coroutineContext[Job])
            val effectiveConversationMode = conversationModeOverride ?: conversationMode
            val effectiveResponseMode = responseModeOverride ?: responseMode
            runCatching {
                withTimeout(generationTimeoutSeconds * 1000L) {
                    val activeConversation = createConversationForRequestLocked(effectiveConversationMode)
                    try {
                        val output = StringBuilder()
                        var firstChunkAt: Long? = null
                        var chunkCount = 0
                        var previousText = ""
                        activeConversation.sendMessageAsync(prompt).collect { message ->
                            val currentText = message.toString()
                            val deltaText = if (currentText.startsWith(previousText)) {
                                currentText.removePrefix(previousText)
                            } else {
                                currentText
                            }
                            if (currentText.length >= previousText.length || !currentText.startsWith(previousText)) {
                                previousText = currentText
                            }
                            if (deltaText.isNotEmpty()) {
                                if (firstChunkAt == null) {
                                    val chunkArrivedAt = System.currentTimeMillis()
                                    firstChunkAt = chunkArrivedAt
                                    lastFirstChunkLatencyMs = chunkArrivedAt - generationStarted
                                }
                                chunkCount += 1
                                output.append(deltaText)
                                onChunk(deltaText)
                            }
                        }
                        val finalOutput = output.toString()
                        finishGeneration(
                            startedAt = generationStarted,
                            outputChars = finalOutput.length,
                            firstChunkAt = firstChunkAt,
                            chunkCount = chunkCount,
                            effectiveConversationMode = effectiveConversationMode,
                            effectiveResponseMode = effectiveResponseMode,
                        )
                        finalOutput
                    } finally {
                        closeRequestConversationIfNeeded(activeConversation, effectiveConversationMode)
                    }
                }
            }.recoverCatching { error ->
                if (error is TimeoutCancellationException) {
                    throw GenerationTimeoutException(generationTimeoutSeconds)
                }
                throw error
            }.onFailure {
                finishGeneration(
                    startedAt = generationStarted,
                    outputChars = 0,
                    firstChunkAt = null,
                    chunkCount = 0,
                    effectiveConversationMode = effectiveConversationMode,
                    effectiveResponseMode = effectiveResponseMode,
                    error = it,
                )
                recordGenerationError(it)
            }
        }
    }

    fun isLoaded(): Boolean = engine != null

    fun backendStatus(): String = backendStatus

    fun setResponseMode(value: ResponseMode) {
        responseMode = value
    }

    fun responseMode(): ResponseMode = responseMode

    fun setConversationMode(value: ConversationMode) {
        if (conversationMode == value) return

        conversationMode = value

        if (activeGeneration) {
            return
        }

        runCatching {
            when (value) {
                ConversationMode.PERSISTENT -> {
                    val activeEngine = engine
                    if (activeEngine != null && conversation == null) {
                        conversation = activeEngine.createConversation()
                    }
                }
                ConversationMode.FRESH_PER_REQUEST -> {
                    conversation?.close()
                    conversation = null
                }
            }
        }.onFailure {
            lastErrorShortMessage = shortMessage(it)
        }
    }

    fun conversationMode(): ConversationMode = conversationMode

    fun setSpeculativeDecodingRequested(value: Boolean) {
        speculativeDecodingRequested = value
        if (!value) {
            speculativeDecodingEnabled = false
            speculativeDecodingError = null
        }
    }

    fun speculativeDecodingRequested(): Boolean = speculativeDecodingRequested

    fun speculativeDecodingEnabled(): Boolean = speculativeDecodingEnabled

    fun setGenerationTimeoutSeconds(value: Int) {
        generationTimeoutSeconds = value.coerceIn(MIN_GENERATION_TIMEOUT_SECONDS, MAX_GENERATION_TIMEOUT_SECONDS)
    }

    fun generationTimeoutSeconds(): Int = generationTimeoutSeconds

    fun cancelCurrentGeneration(): Result<Unit> {
        val job = currentGenerationJob
        return if (job == null || !activeGeneration) {
            Result.failure(IllegalStateException("No active generation to cancel"))
        } else {
            job.cancel()
            Result.success(Unit)
        }
    }

    suspend fun resetConversation(): Result<Unit> = withContext(Dispatchers.IO) {
        if (activeGeneration) {
            return@withContext Result.failure(IllegalStateException("Cannot reset while generation is active"))
        }
        mutex.withLock {
            runCatching {
                if (activeGeneration) error("Cannot reset while generation is active")
                val activeEngine = engine ?: error("Model is not loaded")
                conversation?.close()
                conversation = when (conversationMode) {
                    ConversationMode.PERSISTENT -> activeEngine.createConversation()
                    ConversationMode.FRESH_PER_REQUEST -> null
                }
            }.onFailure {
                lastErrorShortMessage = shortMessage(it)
            }
        }
    }

    fun applyResponseModeHint(prompt: String, mode: ResponseMode = responseMode): String {
        val instruction = when (mode) {
            ResponseMode.CODING_CONCISE -> "You are assisting with coding. Be direct. Prefer concise, actionable answers. Avoid long explanations unless asked. When giving code, prioritize the exact patch or command."
            ResponseMode.BALANCED -> "Answer clearly and directly."
            ResponseMode.DETAILED -> "Answer thoroughly when useful."
        }
        return "$instruction\n\n$prompt"
    }

    fun performanceSnapshot(): PerformanceSnapshot {
        return PerformanceSnapshot(
            backendStatus = backendStatus,
            modelLoaded = isLoaded(),
            speculativeDecodingRequested = speculativeDecodingRequested,
            speculativeDecodingEnabled = speculativeDecodingEnabled,
            speculativeDecodingAvailable = speculativeDecodingAvailable,
            speculativeDecodingError = speculativeDecodingError,
            lastLoadDurationMs = lastLoadDurationMs,
            lastGenerationStartedAtMs = lastGenerationStartedAtMs,
            lastFirstChunkLatencyMs = lastFirstChunkLatencyMs,
            lastGenerationDurationMs = lastGenerationDurationMs,
            lastOutputChars = lastOutputChars,
            lastApproxCharsPerSecond = lastApproxCharsPerSecond,
            lastChunkCount = lastChunkCount,
            lastStreamingUsed = lastStreamingUsed,
            totalRequests = totalRequests,
            totalErrors = totalErrors,
            activeGeneration = activeGeneration,
            lastErrorShortMessage = lastErrorShortMessage,
        )
    }

    fun performanceJson(): JSONObject = performanceSnapshot().toJson()

    fun performanceHistoryJson(): JSONObject {
        val data = synchronized(performanceHistory) {
            JSONArray().also { array ->
                performanceHistory.forEach { array.put(it.toJson()) }
            }
        }
        return JSONObject()
            .put("count", data.length())
            .put("data", data)
    }

    fun performanceSummary(): String {
        val snapshot = performanceSnapshot()
        return buildString {
            appendLine("Backend used: ${snapshot.backendStatus}")
            appendLine("MTP: ${mtpStatus()}")
            appendLine("Last load time: ${formatMs(snapshot.lastLoadDurationMs)}")
            appendLine("Last first chunk latency: ${formatMs(snapshot.lastFirstChunkLatencyMs)}")
            appendLine("Last generation duration: ${formatMs(snapshot.lastGenerationDurationMs)}")
            appendLine("Last chars/sec: ${String.format(Locale.US, "%.1f", snapshot.lastApproxCharsPerSecond)}")
            appendLine("Last chunk count: ${snapshot.lastChunkCount}")
            appendLine("Total requests / errors: ${snapshot.totalRequests} / ${snapshot.totalErrors}")
            appendLine("Active generation: ${if (snapshot.activeGeneration) "yes" else "no"}")
        }
    }

    fun mtpStatus(): String = when {
        speculativeDecodingEnabled -> "enabled"
        !speculativeDecodingAvailable -> "unavailable"
        else -> "disabled"
    }

    fun close() {
        closeLocked()
    }

    private fun initialize(modelPath: String, backend: Backend, status: String, enableSpeculativeDecoding: Boolean) {
        setSpeculativeDecoding(enableSpeculativeDecoding)
        val newEngine = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = backend,
                cacheDir = appContext.cacheDir.absolutePath,
            )
        )
        newEngine.initialize()
        engine = newEngine
        conversation = if (conversationMode == ConversationMode.PERSISTENT) {
            newEngine.createConversation()
        } else {
            null
        }
        backendStatus = status
    }

    @OptIn(ExperimentalApi::class)
    private fun setSpeculativeDecoding(enabled: Boolean) {
        if (!enabled) {
            runCatching { ExperimentalFlags.enableSpeculativeDecoding = false }
            speculativeDecodingEnabled = false
            return
        }
        runCatching {
            ExperimentalFlags.enableSpeculativeDecoding = true
        }.fold(
            onSuccess = {
                speculativeDecodingAvailable = true
                speculativeDecodingEnabled = true
                speculativeDecodingError = null
            },
            onFailure = {
                speculativeDecodingAvailable = false
                speculativeDecodingEnabled = false
                speculativeDecodingError = shortMessage(it)
            },
        )
    }

    private fun beginGeneration(streaming: Boolean, job: Job?): Long {
        val now = System.currentTimeMillis()
        totalRequests += 1
        activeGeneration = true
        currentGenerationJob = job
        lastGenerationStartedAtMs = now
        lastFirstChunkLatencyMs = null
        lastGenerationDurationMs = null
        lastOutputChars = 0
        lastApproxCharsPerSecond = 0.0
        lastChunkCount = 0
        lastStreamingUsed = streaming
        lastErrorShortMessage = null
        return now
    }

    private fun finishGeneration(
        startedAt: Long,
        outputChars: Int,
        firstChunkAt: Long?,
        chunkCount: Int,
        effectiveConversationMode: ConversationMode,
        effectiveResponseMode: ResponseMode,
        error: Throwable? = null,
    ) {
        val duration = System.currentTimeMillis() - startedAt
        lastGenerationDurationMs = duration
        lastFirstChunkLatencyMs = firstChunkAt?.let { it - startedAt }
        lastOutputChars = outputChars
        lastApproxCharsPerSecond = if (duration > 0) outputChars * 1000.0 / duration else 0.0
        lastChunkCount = chunkCount
        addPerformanceHistory(
            PerformanceHistoryEntry(
                timestampMs = System.currentTimeMillis(),
                streamingUsed = lastStreamingUsed,
                firstChunkLatencyMs = lastFirstChunkLatencyMs,
                generationDurationMs = lastGenerationDurationMs,
                outputChars = lastOutputChars,
                approxCharsPerSecond = lastApproxCharsPerSecond,
                chunkCount = lastChunkCount,
                backendStatus = backendStatus,
                speculativeDecodingEnabled = speculativeDecodingEnabled,
                conversationMode = effectiveConversationMode,
                responseMode = effectiveResponseMode,
                error = error?.let { shortMessage(it) },
            )
        )
        activeGeneration = false
        currentGenerationJob = null
    }

    private fun recordGenerationError(error: Throwable) {
        totalErrors += 1
        lastErrorShortMessage = shortMessage(error)
        activeGeneration = false
        currentGenerationJob = null
    }

    private fun createConversationForRequestLocked(effectiveConversationMode: ConversationMode): Conversation {
        val activeEngine = engine ?: error("Model is not loaded")

        return when (effectiveConversationMode) {
            ConversationMode.PERSISTENT -> {
                conversation ?: activeEngine.createConversation().also {
                    conversation = it
                }
            }
            ConversationMode.FRESH_PER_REQUEST -> {
                conversation?.close()
                conversation = null
                activeEngine.createConversation()
            }
        }
    }

    private fun closeRequestConversationIfNeeded(requestConversation: Conversation, effectiveConversationMode: ConversationMode) {
        if (effectiveConversationMode == ConversationMode.FRESH_PER_REQUEST) {
            runCatching { requestConversation.close() }

            if (conversation === requestConversation) {
                conversation = null
            }
        }
    }

    private fun addPerformanceHistory(entry: PerformanceHistoryEntry) {
        synchronized(performanceHistory) {
            performanceHistory.addLast(entry)
            while (performanceHistory.size > PERFORMANCE_HISTORY_LIMIT) {
                performanceHistory.removeFirst()
            }
        }
    }

    private fun closeLocked(resetStatus: Boolean = true) {
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
        setSpeculativeDecoding(false)
        activeGeneration = false
        currentGenerationJob = null
        if (resetStatus) backendStatus = "Not loaded"
    }

    private fun formatMs(value: Long?): String = value?.let { "${it}ms" } ?: "n/a"

    private fun shortMessage(error: Throwable): String {
        return (error.message ?: error::class.java.simpleName).take(160)
    }

    private companion object {
        const val DEFAULT_GENERATION_TIMEOUT_SECONDS = 180
        const val MIN_GENERATION_TIMEOUT_SECONDS = 10
        const val MAX_GENERATION_TIMEOUT_SECONDS = 600
        const val PERFORMANCE_HISTORY_LIMIT = 20
    }
}

data class PerformanceSnapshot(
    val backendStatus: String,
    val modelLoaded: Boolean,
    val speculativeDecodingRequested: Boolean,
    val speculativeDecodingEnabled: Boolean,
    val speculativeDecodingAvailable: Boolean,
    val speculativeDecodingError: String?,
    val lastLoadDurationMs: Long?,
    val lastGenerationStartedAtMs: Long?,
    val lastFirstChunkLatencyMs: Long?,
    val lastGenerationDurationMs: Long?,
    val lastOutputChars: Int,
    val lastApproxCharsPerSecond: Double,
    val lastChunkCount: Int,
    val lastStreamingUsed: Boolean,
    val totalRequests: Long,
    val totalErrors: Long,
    val activeGeneration: Boolean,
    val lastErrorShortMessage: String?,
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("backendStatus", backendStatus)
            .put("modelLoaded", modelLoaded)
            .put("speculativeDecodingRequested", speculativeDecodingRequested)
            .put("speculativeDecodingEnabled", speculativeDecodingEnabled)
            .put("speculativeDecodingAvailable", speculativeDecodingAvailable)
            .put("speculativeDecodingError", speculativeDecodingError ?: JSONObject.NULL)
            .put("lastLoadDurationMs", lastLoadDurationMs ?: JSONObject.NULL)
            .put("lastGenerationStartedAtMs", lastGenerationStartedAtMs ?: JSONObject.NULL)
            .put("lastFirstChunkLatencyMs", lastFirstChunkLatencyMs ?: JSONObject.NULL)
            .put("lastGenerationDurationMs", lastGenerationDurationMs ?: JSONObject.NULL)
            .put("lastOutputChars", lastOutputChars)
            .put("lastApproxCharsPerSecond", String.format(Locale.US, "%.1f", lastApproxCharsPerSecond).toDouble())
            .put("lastChunkCount", lastChunkCount)
            .put("lastStreamingUsed", lastStreamingUsed)
            .put("totalRequests", totalRequests)
            .put("totalErrors", totalErrors)
            .put("activeGeneration", activeGeneration)
            .put("lastErrorShortMessage", lastErrorShortMessage ?: JSONObject.NULL)
    }
}

data class PerformanceHistoryEntry(
    val timestampMs: Long,
    val streamingUsed: Boolean,
    val firstChunkLatencyMs: Long?,
    val generationDurationMs: Long?,
    val outputChars: Int,
    val approxCharsPerSecond: Double,
    val chunkCount: Int,
    val backendStatus: String,
    val speculativeDecodingEnabled: Boolean,
    val conversationMode: ConversationMode,
    val responseMode: ResponseMode,
    val error: String?,
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("timestampMs", timestampMs)
            .put("streamingUsed", streamingUsed)
            .put("firstChunkLatencyMs", firstChunkLatencyMs ?: JSONObject.NULL)
            .put("generationDurationMs", generationDurationMs ?: JSONObject.NULL)
            .put("outputChars", outputChars)
            .put("approxCharsPerSecond", String.format(Locale.US, "%.1f", approxCharsPerSecond).toDouble())
            .put("chunkCount", chunkCount)
            .put("backendStatus", backendStatus)
            .put("speculativeDecodingEnabled", speculativeDecodingEnabled)
            .put("conversationMode", conversationMode.name)
            .put("responseMode", responseMode.name)
            .put("error", error ?: JSONObject.NULL)
    }
}

class GenerationTimeoutException(timeoutSeconds: Int) : Exception("Generation timed out after $timeoutSeconds seconds")

enum class ResponseMode(val displayName: String) {
    CODING_CONCISE("Coding concise"),
    BALANCED("Balanced"),
    DETAILED("Detailed");

    companion object {
        fun fromDisplayName(displayName: String): ResponseMode {
            return entries.firstOrNull { it.displayName == displayName } ?: CODING_CONCISE
        }
    }
}

enum class ConversationMode(val displayName: String) {
    PERSISTENT("Persistent conversation"),
    FRESH_PER_REQUEST("Fresh conversation per request");

    companion object {
        fun fromDisplayName(displayName: String): ConversationMode {
            return entries.firstOrNull { it.displayName == displayName } ?: PERSISTENT
        }
    }
}
