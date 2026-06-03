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
    @Volatile private var sessionProfile: SessionProfile = SessionProfile.CODING
    @Volatile private var responseMode: ResponseMode = ResponseMode.FAST_PATCH
    @Volatile private var conversationMode: ConversationMode = ConversationMode.FRESH_PER_REQUEST
    @Volatile private var resetPolicy: ResetPolicy = ResetPolicy.MANUAL_ONLY
    @Volatile private var generationTimeoutSeconds: Int = DEFAULT_GENERATION_TIMEOUT_SECONDS
    @Volatile private var completedPersistentRequestsSinceReset: Int = 0

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
        settingsOverride: GenerationSettings? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val settings = effectiveSettings(settingsOverride, conversationModeOverride, responseModeOverride)
            val generationStarted = beginGeneration(streaming = false, job = coroutineContext[Job])
            runCatching {
                withTimeout(settings.timeoutSeconds * 1000L) {
                    val activeConversation = createConversationForRequestLocked(settings)
                    try {
                        val output = activeConversation.sendMessage(prompt).toString()
                        finishGeneration(
                            startedAt = generationStarted,
                            outputChars = output.length,
                            firstChunkAt = System.currentTimeMillis(),
                            chunkCount = if (output.isNotEmpty()) 1 else 0,
                            settings = settings,
                        )
                        output
                    } finally {
                        closeRequestConversationIfNeeded(activeConversation, settings.conversationMode)
                    }
                }
            }.recoverCatching { error ->
                if (error is TimeoutCancellationException) {
                    throw GenerationTimeoutException(settings.timeoutSeconds)
                }
                throw error
            }.onFailure {
                finishGeneration(
                    startedAt = generationStarted,
                    outputChars = 0,
                    firstChunkAt = null,
                    chunkCount = 0,
                    settings = settings,
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
        settingsOverride: GenerationSettings? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val settings = effectiveSettings(settingsOverride, conversationModeOverride, responseModeOverride)
            val generationStarted = beginGeneration(streaming = true, job = coroutineContext[Job])
            runCatching {
                withTimeout(settings.timeoutSeconds * 1000L) {
                    val activeConversation = createConversationForRequestLocked(settings)
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
                            settings = settings,
                        )
                        finalOutput
                    } finally {
                        closeRequestConversationIfNeeded(activeConversation, settings.conversationMode)
                    }
                }
            }.recoverCatching { error ->
                if (error is TimeoutCancellationException) {
                    throw GenerationTimeoutException(settings.timeoutSeconds)
                }
                throw error
            }.onFailure {
                finishGeneration(
                    startedAt = generationStarted,
                    outputChars = 0,
                    firstChunkAt = null,
                    chunkCount = 0,
                    settings = settings,
                    error = it,
                )
                recordGenerationError(it)
            }
        }
    }

    fun isLoaded(): Boolean = engine != null

    fun conversationActive(): Boolean = conversation != null

    fun activeGeneration(): Boolean = activeGeneration

    fun backendStatus(): String = backendStatus

    fun setSessionProfile(value: SessionProfile) {
        sessionProfile = value
    }

    fun sessionProfile(): SessionProfile = sessionProfile

    suspend fun applyGenerationSettings(settings: GenerationSettings): Result<Unit> {
        val conversationResult = setConversationMode(settings.conversationMode)
        if (conversationResult.isFailure) return conversationResult
        setSessionProfile(settings.sessionProfile)
        setResponseMode(settings.responseMode)
        setResetPolicy(settings.resetPolicy)
        setGenerationTimeoutSeconds(settings.timeoutSeconds)
        setSpeculativeDecodingRequested(settings.speculativeDecodingRequested)
        return Result.success(Unit)
    }

    fun applyGenerationSettingsPreference(settings: GenerationSettings) {
        setSessionProfile(settings.sessionProfile)
        setResponseMode(settings.responseMode)
        setResetPolicy(settings.resetPolicy)
        setGenerationTimeoutSeconds(settings.timeoutSeconds)
        setSpeculativeDecodingRequested(settings.speculativeDecodingRequested)
        setConversationModePreference(settings.conversationMode)
    }

    fun setResponseMode(value: ResponseMode) {
        responseMode = value
    }

    fun responseMode(): ResponseMode = responseMode

    suspend fun setConversationMode(value: ConversationMode): Result<Unit> = withContext(Dispatchers.IO) {
        if (activeGeneration) {
            return@withContext Result.failure(IllegalStateException("Cannot change conversation mode while generation is active"))
        }
        mutex.withLock {
            runCatching {
                if (activeGeneration) error("Cannot change conversation mode while generation is active")
                conversationMode = value
                completedPersistentRequestsSinceReset = 0
                when (value) {
                    ConversationMode.PERSISTENT -> {
                        val activeEngine = engine
                        if (activeEngine != null && conversation == null) {
                            conversation = activeEngine.createConversation()
                        }
                    }
                    ConversationMode.FRESH_PER_REQUEST -> {
                        closeConversationLocked()
                    }
                }
            }.onFailure {
                lastErrorShortMessage = shortMessage(it)
            }
        }
    }

    fun setConversationModePreference(value: ConversationMode) {
        if (engine == null) {
            conversationMode = value
        }
    }

    fun conversationMode(): ConversationMode = conversationMode

    fun setResetPolicy(value: ResetPolicy) {
        resetPolicy = value
    }

    fun resetPolicy(): ResetPolicy = resetPolicy

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

    suspend fun resetConversation(): Result<String> = withContext(Dispatchers.IO) {
        if (activeGeneration) {
            return@withContext Result.failure(IllegalStateException("Cannot reset while generation is active"))
        }
        mutex.withLock {
            runCatching {
                if (activeGeneration) error("Cannot reset while generation is active")
                val activeEngine = engine ?: error("Model is not loaded")
                when (conversationMode) {
                    ConversationMode.PERSISTENT -> {
                        closeConversationLocked()
                        conversation = activeEngine.createConversation()
                        completedPersistentRequestsSinceReset = 0
                        "Conversation reset"
                    }
                    ConversationMode.FRESH_PER_REQUEST -> {
                        closeConversationLocked()
                        completedPersistentRequestsSinceReset = 0
                        "Fresh-per-request conversation state cleared"
                    }
                }
            }.onFailure {
                lastErrorShortMessage = shortMessage(it)
            }
        }
    }

    fun applyResponseModeHint(prompt: String, mode: ResponseMode = responseMode): String {
        val instruction = when (mode) {
            ResponseMode.FAST_PATCH -> "You are assisting with coding. Prioritize speed and directness. Give the exact fix, command, or patch first. Avoid background explanation unless essential. Prefer at most 5 bullets. If code is needed, provide only the minimal relevant snippet or diff. Do not restate the problem."
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
            conversationMode = conversationMode,
            conversationActive = conversationActive(),
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
            appendLine("Profile: ${sessionProfile.displayName}")
            appendLine("Conversation mode: ${snapshot.conversationMode.displayName}")
            appendLine("Response mode: ${responseMode.displayName}")
            appendLine("Reset policy: ${resetPolicy.displayName}")
            appendLine("Conversation active: ${if (snapshot.conversationActive) "yes" else "no"}")
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
        settings: GenerationSettings,
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
                conversationMode = settings.conversationMode,
                responseMode = settings.responseMode,
                resetPolicy = settings.resetPolicy,
                sessionProfile = settings.sessionProfile,
                error = error?.let { shortMessage(it) },
            )
        )
        if (settings.conversationMode == ConversationMode.PERSISTENT) {
            completedPersistentRequestsSinceReset += 1
        }
        activeGeneration = false
        currentGenerationJob = null
    }

    private fun recordGenerationError(error: Throwable) {
        totalErrors += 1
        lastErrorShortMessage = shortMessage(error)
        activeGeneration = false
        currentGenerationJob = null
    }

    private fun effectiveSettings(
        settingsOverride: GenerationSettings?,
        conversationModeOverride: ConversationMode?,
        responseModeOverride: ResponseMode?,
    ): GenerationSettings {
        val base = settingsOverride ?: GenerationSettings(
            sessionProfile = sessionProfile,
            responseMode = responseMode,
            conversationMode = conversationMode,
            resetPolicy = resetPolicy,
            timeoutSeconds = generationTimeoutSeconds,
            speculativeDecodingRequested = speculativeDecodingRequested,
        )
        return base.copy(
            conversationMode = conversationModeOverride ?: base.conversationMode,
            responseMode = responseModeOverride ?: base.responseMode,
        )
    }

    private fun createConversationForRequestLocked(settings: GenerationSettings): Conversation {
        return when (settings.conversationMode) {
            ConversationMode.PERSISTENT -> {
                val activeEngine = engine ?: error("Model is not loaded")
                resetPersistentConversationForPolicyLocked(activeEngine, settings.resetPolicy)
                conversation ?: activeEngine.createConversation().also { conversation = it }
            }
            ConversationMode.FRESH_PER_REQUEST -> {
                closeConversationLocked()
                (engine ?: error("Model is not loaded")).createConversation()
            }
        }
    }

    private fun resetPersistentConversationForPolicyLocked(activeEngine: Engine, policy: ResetPolicy) {
        val shouldReset = when (policy) {
            ResetPolicy.MANUAL_ONLY -> false
            ResetPolicy.BEFORE_EACH_REQUEST -> true
            ResetPolicy.EVERY_5_REQUESTS -> completedPersistentRequestsSinceReset >= 5
        }
        if (shouldReset) {
            closeConversationLocked()
            conversation = activeEngine.createConversation()
            completedPersistentRequestsSinceReset = 0
        }
    }

    private fun closeRequestConversationIfNeeded(requestConversation: Conversation, effectiveConversationMode: ConversationMode) {
        if (effectiveConversationMode == ConversationMode.FRESH_PER_REQUEST) {
            if (conversation === requestConversation) {
                conversation = null
            }
            requestConversation.close()
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
        closeConversationLocked()
        engine?.close()
        engine = null
        setSpeculativeDecoding(false)
        activeGeneration = false
        currentGenerationJob = null
        if (resetStatus) backendStatus = "Not loaded"
    }

    private fun closeConversationLocked() {
        val activeConversation = conversation
        conversation = null
        activeConversation?.close()
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
    val conversationMode: ConversationMode,
    val conversationActive: Boolean,
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
            .put("conversationMode", conversationMode.name)
            .put("conversationActive", conversationActive)
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
    val resetPolicy: ResetPolicy,
    val sessionProfile: SessionProfile,
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
            .put("resetPolicy", resetPolicy.name)
            .put("sessionProfile", sessionProfile.name)
            .put("error", error ?: JSONObject.NULL)
    }
}

data class GenerationSettings(
    val sessionProfile: SessionProfile,
    val responseMode: ResponseMode,
    val conversationMode: ConversationMode,
    val resetPolicy: ResetPolicy,
    val timeoutSeconds: Int,
    val speculativeDecodingRequested: Boolean,
)

enum class SessionProfile(val displayName: String, val summary: String) {
    CODING("Coding", "Coding: fast patch answers, fresh request sessions, short direct output."),
    CONVERSATION("Conversation", "Conversation: persistent chat memory, balanced natural replies."),
    CUSTOM("Custom", "Custom: manually controlled advanced generation settings.");

    companion object {
        fun fromDisplayName(displayName: String): SessionProfile {
            return entries.firstOrNull { it.displayName == displayName } ?: CODING
        }
    }
}

enum class ResetPolicy(val displayName: String) {
    MANUAL_ONLY("Manual only"),
    BEFORE_EACH_REQUEST("Before each request"),
    EVERY_5_REQUESTS("Every 5 requests");

    companion object {
        fun fromDisplayName(displayName: String): ResetPolicy {
            return entries.firstOrNull { it.displayName == displayName } ?: MANUAL_ONLY
        }
    }
}

object SessionProfilePresets {
    fun settingsFor(profile: SessionProfile, custom: GenerationSettings? = null): GenerationSettings {
        return when (profile) {
            SessionProfile.CODING -> GenerationSettings(
                sessionProfile = SessionProfile.CODING,
                responseMode = ResponseMode.FAST_PATCH,
                conversationMode = ConversationMode.FRESH_PER_REQUEST,
                resetPolicy = ResetPolicy.MANUAL_ONLY,
                timeoutSeconds = 180,
                speculativeDecodingRequested = true,
            )
            SessionProfile.CONVERSATION -> GenerationSettings(
                sessionProfile = SessionProfile.CONVERSATION,
                responseMode = ResponseMode.BALANCED,
                conversationMode = ConversationMode.PERSISTENT,
                resetPolicy = ResetPolicy.MANUAL_ONLY,
                timeoutSeconds = 240,
                speculativeDecodingRequested = true,
            )
            SessionProfile.CUSTOM -> custom ?: settingsFor(SessionProfile.CODING).copy(sessionProfile = SessionProfile.CUSTOM)
        }
    }

    fun matchingProfile(settings: GenerationSettings): SessionProfile {
        val coding = settingsFor(SessionProfile.CODING)
        val conversation = settingsFor(SessionProfile.CONVERSATION)
        return when {
            settings.matchesProfile(coding) -> SessionProfile.CODING
            settings.matchesProfile(conversation) -> SessionProfile.CONVERSATION
            else -> SessionProfile.CUSTOM
        }
    }

    private fun GenerationSettings.matchesProfile(other: GenerationSettings): Boolean {
        return responseMode == other.responseMode &&
            conversationMode == other.conversationMode &&
            resetPolicy == other.resetPolicy &&
            timeoutSeconds == other.timeoutSeconds &&
            speculativeDecodingRequested == other.speculativeDecodingRequested
    }
}

class GenerationTimeoutException(timeoutSeconds: Int) : Exception("Generation timed out after $timeoutSeconds seconds")

enum class ResponseMode(val displayName: String) {
    FAST_PATCH("Fast patch"),
    CODING_CONCISE("Coding concise"),
    BALANCED("Balanced"),
    DETAILED("Detailed");

    companion object {
        fun fromDisplayName(displayName: String): ResponseMode {
            return entries.firstOrNull { it.displayName == displayName } ?: FAST_PATCH
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
