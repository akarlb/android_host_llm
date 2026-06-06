package com.example.androidhostllm

data class SkillRecord(
    val id: String,
    val slug: String,
    val displayName: String,
    val description: String,
    val systemPrompt: String,
    val responseMode: String?,
    val thinkingDefault: Boolean,
    val showThinkingDefault: Boolean,
    val toolUseMode: ToolUseMode,
    val allowedTools: List<String>,
    val outputSchemaJson: String?,
    val strictOutput: Boolean,
    val builtIn: Boolean,
    val enabled: Boolean,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

data class ChatSkillStateRecord(
    val chatId: String,
    val skillId: String,
    val skillSlug: String,
    val thinkingEnabled: Boolean,
    val showThinking: Boolean,
    val updatedAtMs: Long,
)

data class ToolCallLogRecord(
    val id: String,
    val requestId: String?,
    val chatId: String,
    val messageId: String?,
    val toolName: String,
    val requestJson: String,
    val resultJson: String?,
    val status: ToolCallStatus,
    val skillSlug: String?,
    val skillVersion: Long?,
    val rawModelOutput: String?,
    val parsedToolName: String?,
    val sanitizedArgsJson: String?,
    val sanitizedResultPreview: String?,
    val durationMs: Long?,
    val errorCode: String?,
    val errorMessage: String?,
    val createdAtMs: Long,
)

enum class ToolUseMode { NONE, OPTIONAL, REQUIRED }
enum class ToolCallStatus {
    PARSE_FAILED,
    REPAIR_FAILED,
    UNKNOWN_TOOL,
    PERMISSION_DENIED,
    INVALID_ARGUMENTS,
    EXECUTION_FAILED,
    TIMEOUT,
    SUCCESS,
    REJECTED,
}
