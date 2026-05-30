package com.example.androidhostllm

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class LiteRtLmManager(private val appContext: Context) {
    private val mutex = Mutex()
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    suspend fun loadModel(modelPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val modelFile = File(modelPath)
                require(modelFile.exists()) { "Model file does not exist: $modelPath. Put model.litertlm at the path displayed in the app, or edit the model path before loading." }
                require(modelFile.canRead()) { "Model file is not readable: $modelPath. Check that the file was pushed to the displayed app-specific path and is readable by the app." }

                closeLocked()
                try {
                    initialize(modelPath, Backend.GPU())
                } catch (gpuError: Throwable) {
                    closeLocked()
                    try {
                        initialize(modelPath, Backend.CPU())
                    } catch (cpuError: Throwable) {
                        cpuError.addSuppressed(gpuError)
                        throw cpuError
                    }
                }
            }
        }
    }

    suspend fun generate(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val activeConversation = conversation ?: error("Model is not loaded")
                activeConversation.sendMessage(prompt).text
            }
        }
    }

    fun isLoaded(): Boolean = engine != null && conversation != null

    fun close() {
        closeLocked()
    }

    private fun initialize(modelPath: String, backend: Backend) {
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
    }

    private fun closeLocked() {
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
    }
}
