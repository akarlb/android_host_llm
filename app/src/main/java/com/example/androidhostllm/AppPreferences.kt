package com.example.androidhostllm

import android.content.Context

class AppPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("app_preferences", Context.MODE_PRIVATE)

    fun savedModelPath(): String? = prefs.getString(KEY_MODEL_PATH, null)?.takeIf { it.isNotBlank() }

    fun saveModelPath(path: String) {
        prefs.edit().putString(KEY_MODEL_PATH, path).apply()
    }

    fun savedHuggingFaceToken(): String? = prefs.getString(KEY_HF_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun saveHuggingFaceToken(token: String) {
        prefs.edit().putString(KEY_HF_TOKEN, token).apply()
    }

    fun clearHuggingFaceToken() {
        prefs.edit().remove(KEY_HF_TOKEN).apply()
    }

    private companion object {
        const val KEY_MODEL_PATH = "model_path"
        const val KEY_HF_TOKEN = "hf_token"
    }
}
