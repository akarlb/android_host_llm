package com.example.androidhostllm

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : Activity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var liteRtLmManager: LiteRtLmManager
    private var localHttpServer: LocalHttpServer? = null
    private var defaultPathUsesInternalStorage = false

    private lateinit var modelPathField: EditText
    private lateinit var statusText: TextView
    private lateinit var loadButton: Button
    private lateinit var startServerButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        liteRtLmManager = LiteRtLmManager(applicationContext)
        setContentView(createContentView(defaultModelPath()))
        if (defaultPathUsesInternalStorage) {
            statusText.text = "Warning: external files directory was unavailable; using internal app files directory."
        }
    }

    override fun onDestroy() {
        localHttpServer?.stopServer()
        liteRtLmManager.close()
        activityScope.cancel()
        super.onDestroy()
    }

    private fun createContentView(initialModelPath: String): LinearLayout {
        val padding = (16 * resources.displayMetrics.density).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            addView(TextView(context).apply {
                text = "LiteRT-LM Local Server"
                textSize = 24f
            })

            addView(TextView(context).apply {
                text = "Model path (copy this exact path when pushing the model)"
            })

            modelPathField = EditText(context).apply {
                setSingleLine(true)
                setText(initialModelPath)
            }
            addView(modelPathField)

            statusText = TextView(context).apply {
                text = "Not loaded. Put model.litertlm at the path shown above, or edit the path."
                textSize = 16f
            }
            addView(statusText)

            loadButton = Button(context).apply {
                text = "Load Model"
                setOnClickListener { loadModel() }
            }
            addView(loadButton)

            startServerButton = Button(context).apply {
                text = "Start Server"
                setOnClickListener { startServer() }
            }
            addView(startServerButton)

            addView(TextView(context).apply {
                text = "Server URL: http://127.0.0.1:8080"
                textSize = 16f
            })
        }
    }

    private fun loadModel() {
        val modelPath = modelPathField.text.toString().trim()
        if (modelPath.isBlank()) {
            statusText.text = "Error: model path is required"
            return
        }

        val parentDirectory = File(modelPath).parentFile
        if (parentDirectory != null && !parentDirectory.exists() && !parentDirectory.mkdirs()) {
            statusText.text = "Error: could not create model directory: ${parentDirectory.absolutePath}"
            return
        }

        loadButton.isEnabled = false
        statusText.text = "Loading $modelPath"
        activityScope.launch {
            val result = liteRtLmManager.loadModel(modelPath)
            result.fold(
                onSuccess = { statusText.text = "Loaded: $modelPath" },
                onFailure = { statusText.text = "Error: ${it.message}" }
            )
            loadButton.isEnabled = true
        }
    }

    private fun startServer() {
        activityScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (localHttpServer == null) {
                        localHttpServer = LocalHttpServer(liteRtLmManager).also { it.startServer() }
                    }
                }
            }.fold(
                onSuccess = { statusText.text = "Server running at http://127.0.0.1:8080" },
                onFailure = { statusText.text = "Server error: ${it.message}" }
            )
        }
    }

    private fun defaultModelPath(): String {
        val externalFilesDir = applicationContext.getExternalFilesDir(null)
        val modelDirectory = externalFilesDir ?: filesDir.also { defaultPathUsesInternalStorage = true }
        modelDirectory.mkdirs()
        return File(modelDirectory, MODEL_FILE_NAME).absolutePath
    }

    private companion object {
        const val MODEL_FILE_NAME = "model.litertlm"
    }
}
