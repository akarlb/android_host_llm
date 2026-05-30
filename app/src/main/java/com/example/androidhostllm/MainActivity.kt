package com.example.androidhostllm

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
    private val downloadManager = ModelDownloadManager()
    private lateinit var serverAuth: ServerAuth
    private lateinit var appPreferences: AppPreferences
    private var selectedPreset = ModelPreset.GEMMA_4_E2B
    private lateinit var modelDirectoryInfo: ModelDirectoryInfo
    private var sessionHfToken: String = ""

    private lateinit var readinessText: TextView
    private lateinit var presetSpinner: Spinner
    private lateinit var modelUrlText: TextView
    private lateinit var hfTokenField: EditText
    private lateinit var allowMobileDataCheck: CheckBox
    private lateinit var modelPathField: EditText
    private lateinit var modelStatusText: TextView
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var loadButton: Button
    private lateinit var downloadButton: Button
    private lateinit var cancelDownloadButton: Button
    private lateinit var deleteButton: Button
    private lateinit var lanModeButton: RadioButton
    private lateinit var localhostModeButton: RadioButton
    private lateinit var portField: EditText
    private lateinit var serverStatusText: TextView
    private lateinit var urlsText: TextView
    private lateinit var apiKeyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServerRegistry.initialize(applicationContext)
        liteRtLmManager = ServerRegistry.liteRtLmManager
        serverAuth = ServerAuth(applicationContext)
        appPreferences = AppPreferences(applicationContext)
        modelDirectoryInfo = NetworkUtils.modelDirectory(applicationContext)
        setContentView(createContentView())
        updateSelectedPreset(ModelPreset.GEMMA_4_E2B)
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    override fun onDestroy() {
        downloadManager.cancel()
        activityScope.cancel()
        // The foreground service owns the HTTP server lifecycle once started.
        super.onDestroy()
    }

    private fun createContentView(): View {
        val padding = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(TextView(this).apply { text = "LiteRT-LM Phone Server"; textSize = 24f })

        root.addView(sectionTitle("1. Permissions / readiness"))
        readinessText = bodyText("")
        root.addView(readinessText)
        root.addView(Button(this).apply {
            text = "Enable Notifications / Persistent Server"
            setOnClickListener { requestNotificationsIfNeeded() }
        })
        root.addView(Button(this).apply {
            text = "Open Battery Settings"
            setOnClickListener { openBatterySettings() }
        })
        root.addView(bodyText("For long sessions, keep the app open or disable battery optimization manually."))

        root.addView(sectionTitle("2. Model"))
        presetSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, ModelPreset.ALL)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    updateSelectedPreset(ModelPreset.ALL[position])
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        root.addView(presetSpinner)
        modelUrlText = bodyText("")
        root.addView(modelUrlText)
        hfTokenField = EditText(this).apply {
            hint = "Optional Hugging Face token (persisted on this device)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setText(appPreferences.savedHuggingFaceToken().orEmpty())
        }
        root.addView(hfTokenField)
        root.addView(bodyText("Hugging Face tokens are persisted in app preferences, used only for Hugging Face downloads, and never exposed by the local API."))
        root.addView(Button(this).apply { text = "Save Hugging Face Token"; setOnClickListener { saveHuggingFaceTokenFromField() } })
        root.addView(Button(this).apply { text = "Clear Hugging Face Token"; setOnClickListener { clearHuggingFaceToken() } })
        allowMobileDataCheck = CheckBox(this).apply { text = "Allow mobile data for large model download" }
        root.addView(allowMobileDataCheck)
        root.addView(TextView(this).apply { text = "Model path (editable; downloaded preset path is the default)" })
        modelPathField = EditText(this).apply {
            setSingleLine(true)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    appPreferences.saveModelPath(s?.toString().orEmpty())
                }
            })
        }
        root.addView(modelPathField)
        modelStatusText = bodyText("")
        root.addView(modelStatusText)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(progressBar)
        progressText = bodyText("Idle")
        root.addView(progressText)
        downloadButton = Button(this).apply { text = "Download Gemma 4 E2B"; setOnClickListener { confirmAndDownload(false) } }
        root.addView(downloadButton)
        root.addView(Button(this).apply { text = "Re-download / Replace"; setOnClickListener { confirmAndDownload(true) } })
        cancelDownloadButton = Button(this).apply { text = "Cancel Download"; setOnClickListener { downloadManager.cancel() } }
        root.addView(cancelDownloadButton)
        deleteButton = Button(this).apply { text = "Delete Model"; setOnClickListener { deleteModel() } }
        root.addView(deleteButton)
        loadButton = Button(this).apply { text = "Load Model"; setOnClickListener { loadModel() } }
        root.addView(loadButton)

        root.addView(sectionTitle("3. Server"))
        val bindModeGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        lanModeButton = RadioButton(this).apply { text = "LAN / Wi-Fi"; isChecked = true }
        localhostModeButton = RadioButton(this).apply { text = "Localhost only" }
        bindModeGroup.addView(lanModeButton)
        bindModeGroup.addView(localhostModeButton)
        root.addView(bindModeGroup)
        portField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText("8080")
            hint = "Port"
        }
        root.addView(portField)
        apiKeyText = bodyText("")
        root.addView(apiKeyText)
        root.addView(Button(this).apply { text = "Regenerate Local Server API Key"; setOnClickListener { serverAuth.regenerateApiKey(); refreshUi() } })
        root.addView(Button(this).apply { text = "Start Server"; setOnClickListener { startServer() } })
        root.addView(Button(this).apply { text = "Stop Server"; setOnClickListener { stopServer() } })
        serverStatusText = bodyText("")
        root.addView(serverStatusText)
        urlsText = bodyText("")
        root.addView(urlsText)
        root.addView(Button(this).apply { text = "Copy Primary Chat URL"; setOnClickListener { copyPrimaryChatUrl() } })

        return ScrollView(this).apply { addView(root) }
    }

    private fun sectionTitle(textValue: String) = TextView(this).apply { text = "\n$textValue"; textSize = 20f }
    private fun bodyText(textValue: String) = TextView(this).apply { text = textValue; textSize = 15f }

    private fun updateSelectedPreset(preset: ModelPreset) {
        selectedPreset = preset
        val target = preset.targetFile(modelDirectoryInfo.directory)
        if (::modelPathField.isInitialized) modelPathField.setText(appPreferences.savedModelPath() ?: target.absolutePath)
        if (::modelUrlText.isInitialized) modelUrlText.text = "${preset.displayName}\nRepo: ${preset.repo}\nURL: ${preset.url}\nExpected size: ${NetworkUtils.formatBytes(preset.expectedBytes)}"
        if (::downloadButton.isInitialized) downloadButton.text = "Download Gemma 4 E2B"
        refreshUi()
    }

    private fun refreshUi() {
        if (!::readinessText.isInitialized) return
        val notificationStatus = if (Build.VERSION.SDK_INT < 33) "not required before Android 13" else if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) "granted" else "not granted"
        val freeBytes = NetworkUtils.availableBytes(modelDirectoryInfo.directory)
        val ips = NetworkUtils.lanIpv4Candidates()
        readinessText.text = buildString {
            appendLine("Internet: declared as normal permission")
            appendLine("Notifications: $notificationStatus")
            appendLine("App model directory: ${modelDirectoryInfo.directory.absolutePath}")
            if (modelDirectoryInfo.usesInternalFallback) appendLine("Warning: external files directory unavailable; using internal filesDir/models, which may be harder to push manually and is deleted with app data.")
            appendLine("Free storage: ${NetworkUtils.formatBytes(freeBytes)}")
            appendLine("Active network: ${NetworkUtils.activeNetworkType(this@MainActivity).label}")
            appendLine("Device LAN IPv4: ${ips.ifEmpty { listOf("No LAN IPv4 detected; check Wi-Fi.") }.joinToString()}")
        }
        val modelFile = File(modelPathField.text.toString())
        val partFile = File(modelFile.absolutePath + ".part")
        modelStatusText.text = buildString {
            appendLine("Local model path: ${modelFile.absolutePath}")
            appendLine(if (modelFile.exists()) "Model exists: ${NetworkUtils.formatBytes(modelFile.length())}" else "Model not found")
            if (partFile.exists()) appendLine("Partial download exists: ${NetworkUtils.formatBytes(partFile.length())}; Download will try to resume.")
            appendLine("Loaded: ${liteRtLmManager.isLoaded()} (${liteRtLmManager.backendStatus()})")
        }
        val state = ServerRegistry.state
        serverStatusText.text = "Server running: ${state.running}; bind: ${state.bindHost}:${state.port}; mode: ${state.mode}; model loaded: ${liteRtLmManager.isLoaded()}"
        apiKeyText.text = "Local server API key (for LAN / Wi-Fi): ${serverAuth.getOrCreateApiKey()}"
        urlsText.text = buildUrlText(currentPort())
    }

    private fun confirmAndDownload(forceReplace: Boolean) {
        val networkType = NetworkUtils.activeNetworkType(this)
        if (networkType == ActiveNetworkType.NONE) {
            progressText.text = "Network unavailable; connect to Wi-Fi before downloading."
            return
        }
        if (networkType == ActiveNetworkType.MOBILE && !allowMobileDataCheck.isChecked) {
            progressText.text = "Active network is mobile data. Check 'Allow mobile data for large model download' to explicitly proceed, or switch to Wi-Fi."
            return
        }
        startDownload(forceReplace)
    }

    private fun startDownload(forceReplace: Boolean) {
        val target = File(modelPathField.text.toString().trim())
        val free = NetworkUtils.availableBytes(modelDirectoryInfo.directory)
        if (free < selectedPreset.minimumFreeBytes) {
            progressText.text = "Not enough free storage. Need at least ${NetworkUtils.formatBytes(selectedPreset.minimumFreeBytes)} for ${selectedPreset.displayName}; available ${NetworkUtils.formatBytes(free)}."
            return
        }
        if (target.exists() && !forceReplace) {
            progressText.text = "Model exists (${NetworkUtils.formatBytes(target.length())}). Use Load, Re-download / Replace, or Delete."
            return
        }
        sessionHfToken = hfTokenField.text.toString().trim()
        persistHuggingFaceToken(sessionHfToken)
        appPreferences.saveModelPath(target.absolutePath)
        downloadButton.isEnabled = false
        progressBar.progress = 0
        activityScope.launch {
            val result = downloadManager.download(selectedPreset, target, sessionHfToken.ifBlank { null }) { progress ->
                activityScope.launch { showDownloadProgress(progress) }
            }
            result.onFailure { progressText.text = it.message ?: "Download failed" }
            downloadButton.isEnabled = true
            refreshUi()
        }
    }

    private fun showDownloadProgress(progress: DownloadProgress) {
        progressBar.progress = progress.percent ?: 0
        progressText.text = buildString {
            append(progress.status.name.lowercase())
            if (progress.totalBytes != null) append(": ${NetworkUtils.formatBytes(progress.downloadedBytes)} / ${NetworkUtils.formatBytes(progress.totalBytes)}")
            progress.percent?.let { append(" ($it%)") }
            if (progress.speedBytesPerSecond > 0) append(" • ${NetworkUtils.formatBytes(progress.speedBytesPerSecond)}/s")
            if (progress.message.isNotBlank()) append(" • ${progress.message}")
        }
    }

    private fun saveHuggingFaceTokenFromField() {
        persistHuggingFaceToken(hfTokenField.text.toString().trim())
        Toast.makeText(this, "Hugging Face token saved on this device.", Toast.LENGTH_SHORT).show()
    }

    private fun clearHuggingFaceToken() {
        appPreferences.clearHuggingFaceToken()
        hfTokenField.setText("")
        Toast.makeText(this, "Hugging Face token cleared.", Toast.LENGTH_SHORT).show()
    }

    private fun persistHuggingFaceToken(token: String) {
        if (token.isBlank()) {
            appPreferences.clearHuggingFaceToken()
        } else {
            appPreferences.saveHuggingFaceToken(token)
        }
    }

    private fun deleteModel() {
        val modelFile = File(modelPathField.text.toString().trim())
        val partFile = File(modelFile.absolutePath + ".part")
        val deleted = (if (modelFile.exists()) modelFile.delete() else true) && (if (partFile.exists()) partFile.delete() else true)
        progressText.text = if (deleted) "Deleted model and partial download if present." else "Could not delete one or more files."
        refreshUi()
    }

    private fun loadModel() {
        val modelPath = modelPathField.text.toString().trim()
        appPreferences.saveModelPath(modelPath)
        if (modelPath.isBlank()) {
            progressText.text = "Error: model path is required"
            return
        }
        val parentDirectory = File(modelPath).parentFile
        if (parentDirectory != null && !parentDirectory.exists() && !parentDirectory.mkdirs()) {
            progressText.text = "Error: could not create model directory: ${parentDirectory.absolutePath}"
            return
        }
        loadButton.isEnabled = false
        progressText.text = "Loading $modelPath"
        activityScope.launch {
            val result = liteRtLmManager.loadModel(modelPath)
            result.fold(
                onSuccess = { progressText.text = "Loaded: $modelPath (${liteRtLmManager.backendStatus()})" },
                onFailure = { progressText.text = "Error: ${it.message}" }
            )
            loadButton.isEnabled = true
            refreshUi()
        }
    }

    private fun startServer() {
        val port = currentPort()
        val lanMode = lanModeButton.isChecked
        val bindHost = if (lanMode) "0.0.0.0" else "127.0.0.1"
        val mode = if (lanMode) "lan" else "localhost"
        val displayUrl = if (lanMode) {
            NetworkUtils.lanIpv4Candidates().firstOrNull()?.let { "http://$it:$port" } ?: "http://0.0.0.0:$port"
        } else "http://127.0.0.1:$port"
        val requireApiKey = lanMode
        val apiKey = serverAuth.getOrCreateApiKey()
        activityScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val intent = LocalServerService.startIntent(this@MainActivity, bindHost, port, requireApiKey, apiKey, mode, displayUrl)
                    ContextCompat.startForegroundService(this@MainActivity, intent)
                }
            }.fold(
                onSuccess = { progressText.text = "Server starting at $displayUrl" },
                onFailure = { progressText.text = "Server error: ${it.message}" }
            )
            refreshUi()
        }
    }

    private fun stopServer() {
        startService(LocalServerService.stopIntent(this))
        refreshUi()
    }

    private fun buildUrlText(port: Int): String {
        val ips = NetworkUtils.lanIpv4Candidates()
        return buildString {
            appendLine("Localhost URL: http://127.0.0.1:$port")
            appendLine("Localhost chat: http://127.0.0.1:$port/v1/chat/completions")
            if (ips.isEmpty()) {
                appendLine("LAN URL: No LAN IPv4 detected; check Wi-Fi.")
            } else {
                ips.forEachIndexed { index, ip ->
                    val marker = if (index == 0) "primary" else "candidate"
                    appendLine("LAN URL ($marker): http://$ip:$port")
                    appendLine("LAN chat ($marker): http://$ip:$port/v1/chat/completions")
                }
            }
        }
    }

    private fun copyPrimaryChatUrl() {
        val port = currentPort()
        val host = if (lanModeButton.isChecked) NetworkUtils.lanIpv4Candidates().firstOrNull() ?: "127.0.0.1" else "127.0.0.1"
        val url = "http://$host:$port/v1/chat/completions"
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("LiteRT-LM chat URL", url))
        Toast.makeText(this, "Copied $url", Toast.LENGTH_SHORT).show()
    }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        } else {
            Toast.makeText(this, "Notification permission already granted or not required.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openBatterySettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        startActivity(intent)
    }

    private fun currentPort(): Int = portField.text.toString().toIntOrNull()?.coerceIn(1, 65535) ?: 8080
}
