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

    fun savedResponseMode(): ResponseMode {
        val saved = prefs.getString(KEY_RESPONSE_MODE, null)
        return runCatching { ResponseMode.valueOf(saved ?: ResponseMode.CODING_CONCISE.name) }.getOrDefault(
            when (prefs.getString(KEY_RESPONSE_LENGTH, null)) {
                "SHORT" -> ResponseMode.CODING_CONCISE
                "LONG" -> ResponseMode.DETAILED
                else -> ResponseMode.CODING_CONCISE
            }
        )
    }

    fun saveResponseMode(value: ResponseMode) {
        prefs.edit().putString(KEY_RESPONSE_MODE, value.name).apply()
    }

    fun savedConversationMode(): ConversationMode {
        val saved = prefs.getString(KEY_CONVERSATION_MODE, ConversationMode.PERSISTENT.name)
        return runCatching { ConversationMode.valueOf(saved ?: ConversationMode.PERSISTENT.name) }.getOrDefault(ConversationMode.PERSISTENT)
    }

    fun saveConversationMode(value: ConversationMode) {
        prefs.edit().putString(KEY_CONVERSATION_MODE, value.name).apply()
    }

    fun savedSpeculativeDecodingRequested(): Boolean = prefs.getBoolean(KEY_SPECULATIVE_DECODING_REQUESTED, true)

    fun saveSpeculativeDecodingRequested(value: Boolean) {
        prefs.edit().putBoolean(KEY_SPECULATIVE_DECODING_REQUESTED, value).apply()
    }

    fun savedGenerationTimeoutSeconds(): Int = prefs.getInt(KEY_GENERATION_TIMEOUT_SECONDS, 180).coerceIn(10, 600)

    fun saveGenerationTimeoutSeconds(value: Int) {
        prefs.edit().putInt(KEY_GENERATION_TIMEOUT_SECONDS, value.coerceIn(10, 600)).apply()
    }

    private companion object {
        const val KEY_MODEL_PATH = "model_path"
        const val KEY_HF_TOKEN = "hf_token"
        const val KEY_RESPONSE_LENGTH = "response_length"
        const val KEY_RESPONSE_MODE = "response_mode"
        const val KEY_CONVERSATION_MODE = "conversation_mode"
        const val KEY_SPECULATIVE_DECODING_REQUESTED = "speculative_decoding_requested"
        const val KEY_GENERATION_TIMEOUT_SECONDS = "generation_timeout_seconds"
    }
}
