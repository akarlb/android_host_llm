package com.example.androidhostllm

import android.content.Context
import android.util.Base64
import java.security.SecureRandom

class ServerAuth(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("server_auth", Context.MODE_PRIVATE)

    fun getOrCreateApiKey(): String {
        val existing = prefs.getString(KEY_API_KEY, null)
        if (!existing.isNullOrBlank()) return existing
        return regenerateApiKey()
    }

    fun regenerateApiKey(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val key = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        prefs.edit().putString(KEY_API_KEY, key).apply()
        return key
    }

    private companion object {
        const val KEY_API_KEY = "api_key"
    }
}
