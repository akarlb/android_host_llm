package com.example.androidhostllm

import android.content.Context

object ServerRegistry {
    lateinit var liteRtLmManager: LiteRtLmManager
        private set
    private var initialized = false

    @Volatile
    var server: LocalHttpServer? = null

    @Volatile
    var state: ServerState = ServerState()

    fun initialize(context: Context) {
        if (!initialized) {
            liteRtLmManager = LiteRtLmManager(context.applicationContext)
            initialized = true
        }
    }
}

data class ServerState(
    val running: Boolean = false,
    val bindHost: String = "127.0.0.1",
    val port: Int = 8080,
    val mode: String = "localhost",
    val displayUrl: String = "http://127.0.0.1:8080",
)
