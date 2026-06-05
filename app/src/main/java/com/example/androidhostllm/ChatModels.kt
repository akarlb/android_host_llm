package com.example.androidhostllm

data class ChatRecord(
    val id: String,
    val userId: String,
    val title: String,
    val profile: ChatProfile,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

data class MessageRecord(
    val id: String,
    val chatId: String,
    val role: String,
    val content: String,
    val createdAtMs: Long,
    val thinking: String? = null,
    val rawContent: String? = null,
)

data class ChatFileContextState(
    val chatId: String,
    val fileId: String,
    val lastIncludedChunkIndex: Int,
    val updatedAtMs: Long,
)

enum class ChatProfile {
    CODING,
    CONVERSATION,
    CUSTOM,
}
