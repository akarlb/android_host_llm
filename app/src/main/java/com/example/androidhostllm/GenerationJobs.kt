package com.example.androidhostllm

import java.util.UUID

enum class GenerationStatus {
    QUEUED,
    RUNNING,
    STREAMING,
    COMPLETED,
    CANCELLED,
    FAILED,
    TIMED_OUT,
}

data class GenerationJobRecord(
    val id: String,
    val chatId: String,
    val userId: String,
    val userMessageId: String,
    val assistantMessageId: String?,
    val status: GenerationStatus,
    val createdAtMs: Long,
    val startedAtMs: Long?,
    val completedAtMs: Long?,
    val error: String?,
    val partialOutput: String,
)

class GenerationJobStore {
    private val jobs = linkedMapOf<String, GenerationJobRecord>()

    @Synchronized
    fun create(chatId: String, userId: String, userMessageId: String): GenerationJobRecord {
        val now = System.currentTimeMillis()
        val job = GenerationJobRecord(
            id = UUID.randomUUID().toString(),
            chatId = chatId,
            userId = userId,
            userMessageId = userMessageId,
            assistantMessageId = null,
            status = GenerationStatus.QUEUED,
            createdAtMs = now,
            startedAtMs = null,
            completedAtMs = null,
            error = null,
            partialOutput = "",
        )
        jobs[job.id] = job
        trim()
        return job
    }

    @Synchronized
    fun markRunning(id: String, streaming: Boolean): GenerationJobRecord? {
        return update(id) {
            it.copy(
                status = if (streaming) GenerationStatus.STREAMING else GenerationStatus.RUNNING,
                startedAtMs = it.startedAtMs ?: System.currentTimeMillis(),
            )
        }
    }

    @Synchronized
    fun appendPartial(id: String, text: String): GenerationJobRecord? {
        if (text.isBlank()) return jobs[id]
        return update(id) { it.copy(partialOutput = it.partialOutput + text) }
    }

    @Synchronized
    fun complete(id: String, assistantMessageId: String?, output: String): GenerationJobRecord? {
        return update(id) {
            it.copy(
                assistantMessageId = assistantMessageId,
                status = GenerationStatus.COMPLETED,
                completedAtMs = System.currentTimeMillis(),
                partialOutput = output,
                error = null,
            )
        }
    }

    @Synchronized
    fun fail(id: String, error: Throwable): GenerationJobRecord? {
        val status = if (error is GenerationTimeoutException) GenerationStatus.TIMED_OUT else GenerationStatus.FAILED
        return update(id) {
            it.copy(
                status = status,
                completedAtMs = System.currentTimeMillis(),
                error = error.message ?: status.name,
            )
        }
    }

    @Synchronized
    fun cancel(id: String): GenerationJobRecord? {
        return update(id) {
            it.copy(
                status = GenerationStatus.CANCELLED,
                completedAtMs = System.currentTimeMillis(),
                error = "Cancelled by user",
            )
        }
    }

    @Synchronized
    fun get(id: String): GenerationJobRecord? = jobs[id]

    @Synchronized
    fun activeForChat(chatId: String): GenerationJobRecord? {
        return jobs.values.lastOrNull {
            it.chatId == chatId && it.status in setOf(GenerationStatus.QUEUED, GenerationStatus.RUNNING, GenerationStatus.STREAMING)
        }
    }

    @Synchronized
    fun activeAny(): GenerationJobRecord? {
        return jobs.values.lastOrNull {
            it.status in setOf(GenerationStatus.QUEUED, GenerationStatus.RUNNING, GenerationStatus.STREAMING)
        }
    }

    @Synchronized
    fun recentForChat(chatId: String): List<GenerationJobRecord> {
        return jobs.values.filter { it.chatId == chatId }.takeLast(50).reversed()
    }

    private fun update(id: String, block: (GenerationJobRecord) -> GenerationJobRecord): GenerationJobRecord? {
        val current = jobs[id] ?: return null
        val next = block(current)
        jobs[id] = next
        return next
    }

    private fun trim() {
        while (jobs.size > 300) {
            val first = jobs.keys.firstOrNull() ?: return
            jobs.remove(first)
        }
    }
}
