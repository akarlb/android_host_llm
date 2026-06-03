package com.example.androidhostllm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.content.Intent
import android.os.Build
import android.os.IBinder

class LocalServerService : Service() {
    override fun onCreate() {
        super.onCreate()
        ServerRegistry.initialize(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startServer(intent)
            ACTION_STOP -> stopServerAndSelf()
            else -> updateForegroundNotification()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun startServer(intent: Intent) {
        val bindHost = intent.getStringExtra(EXTRA_BIND_HOST) ?: "127.0.0.1"
        val port = intent.getIntExtra(EXTRA_PORT, 8080)
        val requireApiKey = intent.getBooleanExtra(EXTRA_REQUIRE_API_KEY, false)
        val apiKey = intent.getStringExtra(EXTRA_API_KEY)
        val mode = intent.getStringExtra(EXTRA_MODE) ?: "localhost"
        val displayUrl = intent.getStringExtra(EXTRA_DISPLAY_URL) ?: "http://127.0.0.1:$port"
        val preferences = AppPreferences(applicationContext)
        ServerRegistry.liteRtLmManager.setResponseMode(preferences.savedResponseMode())
        ServerRegistry.liteRtLmManager.setConversationMode(preferences.savedConversationMode())
        ServerRegistry.liteRtLmManager.setSpeculativeDecodingRequested(preferences.savedSpeculativeDecodingRequested())
        ServerRegistry.liteRtLmManager.setGenerationTimeoutSeconds(preferences.savedGenerationTimeoutSeconds())

        ServerRegistry.server?.stopServer()
        val server = LocalHttpServer(
            liteRtLmManager = ServerRegistry.liteRtLmManager,
            appPreferences = preferences,
            authRepository = AuthRepository(applicationContext),
            bindHost = bindHost,
            port = port,
            requireApiKey = requireApiKey,
            apiKey = apiKey,
            serverMode = mode,
        )
        server.startServer()
        ServerRegistry.server = server
        ServerRegistry.state = ServerState(true, bindHost, port, mode, displayUrl)
        startForegroundCompat(buildNotification())
    }

    private fun stopServerAndSelf() {
        ServerRegistry.server?.stopServer()
        ServerRegistry.server = null
        ServerRegistry.state = ServerRegistry.state.copy(running = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateForegroundNotification() {
        if (ServerRegistry.state.running) {
            startForegroundCompat(buildNotification())
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, LocalServerService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            2,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val state = ServerRegistry.state
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("LiteRT-LM server running")
            .setContentText("${state.mode}: ${state.displayUrl} • model loaded: ${ServerRegistry.liteRtLmManager.isLoaded()}")
            .setStyle(Notification.BigTextStyle().bigText("Mode: ${state.mode}\nURL: ${state.displayUrl}\nModel loaded: ${ServerRegistry.liteRtLmManager.isLoaded()}"))
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "LiteRT-LM server", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "local_server"
        private const val NOTIFICATION_ID = 42
        const val ACTION_START = "com.example.androidhostllm.START_SERVER"
        const val ACTION_STOP = "com.example.androidhostllm.STOP_SERVER"
        const val EXTRA_BIND_HOST = "bindHost"
        const val EXTRA_PORT = "port"
        const val EXTRA_REQUIRE_API_KEY = "requireApiKey"
        const val EXTRA_API_KEY = "apiKey"
        const val EXTRA_MODE = "mode"
        const val EXTRA_DISPLAY_URL = "displayUrl"

        fun startIntent(context: Context, bindHost: String, port: Int, requireApiKey: Boolean, apiKey: String?, mode: String, displayUrl: String): Intent {
            return Intent(context, LocalServerService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_BIND_HOST, bindHost)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_REQUIRE_API_KEY, requireApiKey)
                .putExtra(EXTRA_API_KEY, apiKey)
                .putExtra(EXTRA_MODE, mode)
                .putExtra(EXTRA_DISPLAY_URL, displayUrl)
        }

        fun stopIntent(context: Context): Intent = Intent(context, LocalServerService::class.java).setAction(ACTION_STOP)
    }
}
