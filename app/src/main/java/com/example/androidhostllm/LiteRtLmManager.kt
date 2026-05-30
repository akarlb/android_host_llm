package com.example.androidhostllm

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale

class LiteRtLmManager(private val appContext: Context) {
    private val mutex = Mutex()
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    @Volatile private var backendStatus: String = "Not loaded"
    @Volatile private var responseLength: ResponseLength = ResponseLength.MEDIUM

    private val enableSpeculativeDecodingForGpu: Boolean = true
    @Volatile private var speculativeDecodingEnabled: Boolean = false

    @Volatile private var lastLoadStartedAt: Long? = null
    @Volatile private var lastLoadDurationMs: Long? = null
    @Volatile private var lastGenerationStartedAt: Long? = null
    @Volatile private var lastFirstChunkLatencyMs: Long? = null
    @Volatile private var lastGenerationDurationMs: Long? = null
    @Volatile private var lastOutputChars: Int = 0
    @Volatile private var lastApproxCharsPerSecond: Double = 0.0
    @Volatile private var lastStreamingUsed: Boolean = false

    suspend fun loadModel(modelPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val loadStarted = System.currentTimeMillis()
            lastLoadStartedAt = loadStarted
            lastLoadDurationMs = null
            runCatching {
                val modelFile = File(modelPath)
                require(modelFile.exists()) { "Model file does not exist: $modelPath. Put model.litertlm at the path displayed in the app, or edit the model path before loading." }
                require(modelFile.canRead()) { "Model file is not readable: $modelPath. Check that the file was pushed to the displayed app-specific path and is readable by the app." }

                closeLocked()
                try {
                    initialize(modelPath, Backend.GPU(), "Loaded with GPU", enableSpeculativeDecoding = enableSpeculativeDecodingForGpu)
                } catch (gpuError: Throwable) {
                    closeLocked()
                    try {
                        initialize(modelPath, Backend.CPU(), "GPU failed, loaded with CPU", enableSpeculativeDecoding = false)
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

    suspend fun generate(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val generationStarted = beginGeneration(streaming = false)
            runCatching {
                val activeConversation = conversation ?: error("Model is not loaded")
                val output = activeConversation.sendMessage(prompt).toString()
                finishGeneration(generationStarted, output.length, firstChunkAt = System.currentTimeMillis())
                output
            }.onFailure {
                finishGeneration(generationStarted, outputChars = 0, firstChunkAt = null)
            }
        }
    }

    suspend fun generateStreaming(
        prompt: String,
        onChunk: suspend (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val generationStarted = beginGeneration(streaming = true)
            runCatching {
                val activeConversation = conversation ?: error("Model is not loaded")
                val output = StringBuilder()
                var firstChunkAt: Long? = null
                activeConversation.sendMessageAsync(prompt).collect { message ->
                    val chunkText = message.toString()
                    if (chunkText.isNotEmpty()) {
                        if (firstChunkAt == null) {
                            val chunkArrivedAt = System.currentTimeMillis()
                            firstChunkAt = chunkArrivedAt
                            lastFirstChunkLatencyMs = chunkArrivedAt - generationStarted
                        }
                        output.append(chunkText)
                        onChunk(chunkText)
                    }
                }
                val finalOutput = output.toString()
                finishGeneration(generationStarted, finalOutput.length, firstChunkAt)
                finalOutput
            }.onFailure {
                finishGeneration(generationStarted, outputChars = 0, firstChunkAt = null)
            }
        }
    }

    fun isLoaded(): Boolean = engine != null && conversation != null

    fun backendStatus(): String = backendStatus

    fun setResponseLength(value: ResponseLength) {
        responseLength = value
    }

    fun responseLength(): ResponseLength = responseLength

    fun applyResponseLengthHint(prompt: String): String {
        val instruction = when (responseLength) {
            ResponseLength.SHORT -> "Answer concisely in 3–5 sentences unless the user explicitly asks for more detail."
            ResponseLength.MEDIUM -> "Answer clearly and directly."
            ResponseLength.LONG -> null
        }
        return if (instruction == null) prompt else "$instruction\n\n$prompt"
    }

    fun performanceSnapshot(): JSONObject {
        return JSONObject()
            .put("backendStatus", backendStatus)
            .put("modelLoaded", isLoaded())
            .put("speculativeDecodingEnabled", speculativeDecodingEnabled)
            .put("lastLoadStartedAt", lastLoadStartedAt ?: JSONObject.NULL)
            .put("lastLoadDurationMs", lastLoadDurationMs ?: JSONObject.NULL)
            .put("lastGenerationStartedAt", lastGenerationStartedAt ?: JSONObject.NULL)
            .put("lastFirstChunkLatencyMs", lastFirstChunkLatencyMs ?: JSONObject.NULL)
            .put("lastGenerationDurationMs", lastGenerationDurationMs ?: JSONObject.NULL)
            .put("lastOutputChars", lastOutputChars)
            .put("lastApproxCharsPerSecond", String.format(Locale.US, "%.1f", lastApproxCharsPerSecond).toDouble())
            .put("lastStreamingUsed", lastStreamingUsed)
    }

    fun performanceSummary(): String {
        val snapshot = performanceSnapshot()
        val speculativeText = if (snapshot.optBoolean("speculativeDecodingEnabled")) "enabled" else "unavailable / disabled"
        return buildString {
            appendLine("Backend: ${snapshot.optString("backendStatus")}")
            appendLine("MTP/speculative decoding: $speculativeText")
            appendLine("Last generation: ${formatMs(snapshot.optLongOrNull("lastGenerationDurationMs"))}")
            appendLine("First chunk latency: ${formatMs(snapshot.optLongOrNull("lastFirstChunkLatencyMs"))}")
            appendLine("Approx chars/sec: ${String.format(Locale.US, "%.1f", snapshot.optDouble("lastApproxCharsPerSecond", 0.0))}")
        }
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
        val newConversation = newEngine.createConversation()
        engine = newEngine
        conversation = newConversation
        backendStatus = status
    }

    @OptIn(ExperimentalApi::class)
    private fun setSpeculativeDecoding(enabled: Boolean) {
        ExperimentalFlags.enableSpeculativeDecoding = enabled
        speculativeDecodingEnabled = enabled
    }

    private fun beginGeneration(streaming: Boolean): Long {
        val now = System.currentTimeMillis()
        lastGenerationStartedAt = now
        lastFirstChunkLatencyMs = null
        lastGenerationDurationMs = null
        lastOutputChars = 0
        lastApproxCharsPerSecond = 0.0
        lastStreamingUsed = streaming
        return now
    }

    private fun finishGeneration(startedAt: Long, outputChars: Int, firstChunkAt: Long?) {
        val duration = System.currentTimeMillis() - startedAt
        lastGenerationDurationMs = duration
        lastFirstChunkLatencyMs = firstChunkAt?.let { it - startedAt }
        lastOutputChars = outputChars
        lastApproxCharsPerSecond = if (duration > 0) outputChars * 1000.0 / duration else 0.0
    }

    private fun closeLocked(resetStatus: Boolean = true) {
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
        setSpeculativeDecoding(false)
        if (resetStatus) backendStatus = "Not loaded"
    }

    private fun JSONObject.optLongOrNull(name: String): Long? {
        return if (isNull(name)) null else optLong(name)
    }

    private fun formatMs(value: Long?): String = value?.let { "${it}ms" } ?: "n/a"
}

enum class ResponseLength(val displayName: String) {
    SHORT("Short"),
    MEDIUM("Medium"),
    LONG("Long");

    companion object {
        fun fromDisplayName(displayName: String): ResponseLength {
            return entries.firstOrNull { it.displayName == displayName } ?: MEDIUM
        }
    }
}
