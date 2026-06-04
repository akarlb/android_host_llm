package com.example.androidhostllm

import java.util.Locale

data class PromptPlan(
    val finalPrompt: String,
    val contextMetadata: PromptContextMetadata,
    val continuationMode: Boolean,
    val includedFileIds: List<String>,
    val includedChunks: Int,
    val includedChars: Int,
    val omittedChunks: Int,
    val truncated: Boolean,
)

data class PromptContextMetadata(
    val fileIds: List<String>,
    val includedChunks: Int,
    val includedChars: Int,
    val omittedChunks: Int,
    val truncated: Boolean,
    val continuationMode: Boolean,
    val friendlyMessage: String?,
)

class PromptBudgetManager {
    fun isContinuationRequest(content: String): Boolean {
        val normalized = content.lowercase(Locale.US)
            .trim()
            .trim('.', '!', '?', ':', ';', ',', '"', '\'')
            .replace(Regex("\\s+"), " ")
        return normalized in CONTINUATION_REQUESTS
    }

    fun calculateFileBudget(
        messages: List<MessageRecord>,
        currentUserMessageId: String,
        selectedFileIds: List<String>,
        responseMode: ResponseMode,
        continuationMode: Boolean,
        retry: Boolean = false,
    ): Int {
        if (selectedFileIds.isEmpty()) return 0
        val skeleton = renderPrompt(
            messages = messages,
            contextBlock = "",
            currentUserMessageId = currentUserMessageId,
            continuationMode = continuationMode,
            retry = retry,
        )
        val styleReserve = responseMode.name.length + 400
        val available = MAX_FINAL_PROMPT_CHARS - skeleton.length - styleReserve
        val cap = when {
            retry -> MAX_FILE_CONTEXT_CHARS_HARD / 2
            continuationMode -> 3_200
            else -> MAX_FILE_CONTEXT_CHARS_HARD
        }
        return available.coerceIn(MIN_FILE_CONTEXT_CHARS, cap)
    }

    fun buildPlan(
        messages: List<MessageRecord>,
        currentUserMessageId: String,
        context: FileContextBuildResult?,
        continuationMode: Boolean,
        retry: Boolean = false,
    ): PromptPlan {
        val contextBlock = context?.promptBlock.orEmpty()
        var finalPrompt = renderPrompt(messages, contextBlock, currentUserMessageId, continuationMode, retry)
        var truncated = context?.truncated ?: false
        if (estimateTokens(finalPrompt) > SAFE_INPUT_TOKENS || finalPrompt.length > MAX_FINAL_PROMPT_CHARS) {
            finalPrompt = renderPrompt(
                messages = messages,
                contextBlock = contextBlock.take(if (retry) 2_000 else 4_000),
                currentUserMessageId = currentUserMessageId,
                continuationMode = continuationMode,
                retry = true,
            )
            truncated = true
        }
        if (finalPrompt.length > MAX_FINAL_PROMPT_CHARS) {
            finalPrompt = finalPrompt.take(MAX_FINAL_PROMPT_CHARS - 16) + "\nassistant:"
            truncated = true
        }
        val metadata = PromptContextMetadata(
            fileIds = context?.fileIds.orEmpty(),
            includedChunks = context?.includedChunks ?: 0,
            includedChars = context?.includedChars ?: 0,
            omittedChunks = context?.omittedChunks ?: 0,
            truncated = truncated,
            continuationMode = continuationMode,
            friendlyMessage = context?.message ?: when {
                continuationMode && context != null -> "Continuing with the next available part of the selected file context."
                truncated && context != null -> "Using selected file context. Large files were shortened automatically."
                else -> null
            },
        )
        return PromptPlan(
            finalPrompt = finalPrompt,
            contextMetadata = metadata,
            continuationMode = continuationMode,
            includedFileIds = metadata.fileIds,
            includedChunks = metadata.includedChunks,
            includedChars = metadata.includedChars,
            omittedChunks = metadata.omittedChunks,
            truncated = metadata.truncated,
        )
    }

    fun isTokenOverflow(error: Throwable): Boolean {
        val message = error.message.orEmpty().lowercase(Locale.US)
        return TOKEN_OVERFLOW_PATTERNS.any { it in message }
    }

    fun friendlyError(error: Throwable): Pair<String, String> {
        return if (isTokenOverflow(error)) {
            "CONTEXT_TOO_LARGE" to "The selected context was too large, so I shortened it. Please ask about a more specific section if the answer is incomplete."
        } else {
            "GENERATION_ERROR" to "Generation failed. Please try again with a shorter request."
        }
    }

    private fun renderPrompt(
        messages: List<MessageRecord>,
        contextBlock: String,
        currentUserMessageId: String,
        continuationMode: Boolean,
        retry: Boolean,
    ): String {
        val current = messages.firstOrNull { it.id == currentUserMessageId }
        val previous = messages.filter { it.id != currentUserMessageId }
        val recent = previous.takeLast(if (continuationMode || retry) 4 else 8)
        return buildString {
            if (continuationMode) {
                appendLine("The user asked to continue. Continue from the selected file context below. Do not repeat the previous answer.")
                appendLine("If the available context has been shortened, continue with the next available relevant section.")
            } else {
                appendLine("Continue this chat. Use prior turns only as compact context.")
            }
            if (contextBlock.isNotBlank()) {
                appendLine()
                appendLine("[Selected Markdown Context]")
                appendLine(contextBlock)
                appendLine("[/Selected Markdown Context]")
            }
            if (recent.isNotEmpty()) {
                appendLine()
                appendLine("Recent conversation summary:")
                recent.forEach { message ->
                    val limit = when {
                        message.role == "assistant" && (continuationMode || retry) -> 400
                        message.role == "assistant" -> MAX_PREVIOUS_ASSISTANT_CHARS
                        message.role == "user" -> MAX_PREVIOUS_USER_CHARS
                        else -> 600
                    }
                    append(message.role)
                    append(": ")
                    appendLine(message.content.compact(limit))
                }
            }
            appendLine()
            appendLine("User request:")
            appendLine(current?.content?.compact(if (retry) 1_200 else 2_000).orEmpty())
            append("assistant:")
        }
    }

    private fun String.compact(limit: Int): String {
        val clean = replace(Regex("\\s+"), " ").trim()
        return if (clean.length <= limit) clean else clean.take(limit).trimEnd() + "..."
    }

    private fun estimateTokens(text: String): Int = (text.length / APPROX_CHARS_PER_TOKEN).toInt()

    private companion object {
        const val MAX_MODEL_INPUT_TOKENS = 4096
        const val RESERVED_OUTPUT_TOKENS = 768
        const val SAFE_INPUT_TOKENS = 3000
        const val APPROX_CHARS_PER_TOKEN = 3.0
        const val MAX_FINAL_PROMPT_CHARS = 9000
        const val MAX_FILE_CONTEXT_CHARS_HARD = 6000
        const val MAX_PREVIOUS_ASSISTANT_CHARS = 1200
        const val MAX_PREVIOUS_USER_CHARS = 800
        const val MIN_FILE_CONTEXT_CHARS = 1200
        val CONTINUATION_REQUESTS = setOf(
            "continue",
            "go on",
            "keep going",
            "more",
            "next",
            "next part",
            "continue the answer",
            "continue from where you stopped",
            "carry on",
        )
        val TOKEN_OVERFLOW_PATTERNS = setOf(
            "input token ids are too long",
            "maximum number of tokens allowed",
            "token ids are too long",
            "context length",
            "prompt too long",
            "too many tokens",
            "token limit",
        )
    }
}
