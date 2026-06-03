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
        return runCatching { ResponseMode.valueOf(saved ?: ResponseMode.FAST_PATCH.name) }.getOrDefault(
            when (prefs.getString(KEY_RESPONSE_LENGTH, null)) {
                "SHORT" -> ResponseMode.CODING_CONCISE
                "LONG" -> ResponseMode.DETAILED
                else -> ResponseMode.FAST_PATCH
            }
        )
    }

    fun saveResponseMode(value: ResponseMode) {
        prefs.edit().putString(KEY_RESPONSE_MODE, value.name).apply()
    }

    fun savedConversationMode(): ConversationMode {
        val saved = prefs.getString(KEY_CONVERSATION_MODE, ConversationMode.FRESH_PER_REQUEST.name)
        return runCatching { ConversationMode.valueOf(saved ?: ConversationMode.FRESH_PER_REQUEST.name) }.getOrDefault(ConversationMode.FRESH_PER_REQUEST)
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

    fun savedSessionProfile(): SessionProfile {
        val saved = prefs.getString(KEY_SESSION_PROFILE, SessionProfile.CODING.name)
        return runCatching { SessionProfile.valueOf(saved ?: SessionProfile.CODING.name) }.getOrDefault(SessionProfile.CODING)
    }

    fun saveSessionProfile(value: SessionProfile) {
        prefs.edit().putString(KEY_SESSION_PROFILE, value.name).apply()
    }

    fun savedResetPolicy(): ResetPolicy {
        val saved = prefs.getString(KEY_RESET_POLICY, ResetPolicy.MANUAL_ONLY.name)
        return runCatching { ResetPolicy.valueOf(saved ?: ResetPolicy.MANUAL_ONLY.name) }.getOrDefault(ResetPolicy.MANUAL_ONLY)
    }

    fun saveResetPolicy(value: ResetPolicy) {
        prefs.edit().putString(KEY_RESET_POLICY, value.name).apply()
    }

    fun savedGenerationSettings(): GenerationSettings {
        val custom = GenerationSettings(
            sessionProfile = SessionProfile.CUSTOM,
            responseMode = savedResponseMode(),
            conversationMode = savedConversationMode(),
            resetPolicy = savedResetPolicy(),
            timeoutSeconds = savedGenerationTimeoutSeconds(),
            speculativeDecodingRequested = savedSpeculativeDecodingRequested(),
        )
        val profile = savedSessionProfile()
        return if (profile == SessionProfile.CUSTOM) {
            custom
        } else {
            SessionProfilePresets.settingsFor(profile)
        }
    }

    fun saveGenerationSettings(settings: GenerationSettings) {
        prefs.edit()
            .putString(KEY_SESSION_PROFILE, settings.sessionProfile.name)
            .putString(KEY_RESPONSE_MODE, settings.responseMode.name)
            .putString(KEY_CONVERSATION_MODE, settings.conversationMode.name)
            .putString(KEY_RESET_POLICY, settings.resetPolicy.name)
            .putInt(KEY_GENERATION_TIMEOUT_SECONDS, settings.timeoutSeconds.coerceIn(10, 600))
            .putBoolean(KEY_SPECULATIVE_DECODING_REQUESTED, settings.speculativeDecodingRequested)
            .apply()
    }

    private companion object {
        const val KEY_MODEL_PATH = "model_path"
        const val KEY_HF_TOKEN = "hf_token"
        const val KEY_RESPONSE_LENGTH = "response_length"
        const val KEY_RESPONSE_MODE = "response_mode"
        const val KEY_CONVERSATION_MODE = "conversation_mode"
        const val KEY_SESSION_PROFILE = "session_profile"
        const val KEY_RESET_POLICY = "reset_policy"
        const val KEY_SPECULATIVE_DECODING_REQUESTED = "speculative_decoding_requested"
        const val KEY_GENERATION_TIMEOUT_SECONDS = "generation_timeout_seconds"
    }
}
