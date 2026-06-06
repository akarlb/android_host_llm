package com.example.androidhostllm

enum class SecurityMode {
    LOCAL_DEV,
    TRUSTED_LAN;

    companion object {
        fun fromServerMode(value: String): SecurityMode {
            return when (value.trim().uppercase()) {
                "TRUSTED_LAN", "LAN" -> TRUSTED_LAN
                else -> LOCAL_DEV
            }
        }
    }
}
