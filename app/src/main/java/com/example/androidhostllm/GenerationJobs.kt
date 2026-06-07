package com.example.androidhostllm

import java.util.UUID

const val DEFAULT_GENERATION_JOB_TIMEOUT_MS = 180_000L

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
    val updatedAtMs: Long,
    val startedAtMs: Long?,
    val completedAtMs: Long?,
    val errorCode: String?,
    val errorMessage: String?,
    val error: String?,
    val partialOutput: String,
) {
    val isActive: Boolean
        get() = status in ACTIVE_GENERATION_STATUSES
}

data class GenerationJobSummary(
    val activeCount: Int,
    val activeJob: GenerationJobRecord?,
    val expiredStaleCount: Int,
)

class GenerationJobStore(
    private val activeTimeoutMs: Long = DEFAULT_GENERATION_JOB_TIMEOUT_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val jobs = linkedMapOf<String, GenerationJobRecord>()

    @Synchronized
    fun create(chatId: String, userId: String, userMessageId: String): GenerationJobRecord {
        val now = clock()
        val job = GenerationJobRecord(
            id = UUID.randomUUID().toString(),
            chatId = chatId,
            userId = userId,
            userMessageId = userMessageId,
            assistantMessageId = null,
            status = GenerationStatus.QUEUED,
            createdAtMs = now,
            updatedAtMs = now,
            startedAtMs = null,
            completedAtMs = null,
            errorCode = null,
            errorMessage = null,
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
                updatedAtMs = clock(),
                startedAtMs = it.startedAtMs ?: clock(),
            )
        }
    }

    @Synchronized
    fun appendPartial(id: String, text: String): GenerationJobRecord? {
        if (text.isBlank()) return jobs[id]
        return update(id) { it.copy(updatedAtMs = clock(), partialOutput = it.partialOutput + text) }
    }

    @Synchronized
    fun complete(id: String, assistantMessageId: String?, output: String): GenerationJobRecord? {
        val now = clock()
        return update(id) {
            it.copy(
                assistantMessageId = assistantMessageId,
                status = GenerationStatus.COMPLETED,
                updatedAtMs = now,
                completedAtMs = now,
                partialOutput = output,
                errorCode = null,
                errorMessage = null,
                error = null,
            )
        }
    }

    @Synchronized
    fun fail(id: String, error: Throwable): GenerationJobRecord? {
        val status = if (error is GenerationTimeoutException) GenerationStatus.TIMED_OUT else GenerationStatus.FAILED
        val message = error.message ?: status.name
        val code = if (status == GenerationStatus.TIMED_OUT) "generation_timed_out" else "generation_failed"
        val now = clock()
        return update(id) {
            it.copy(
                status = status,
                updatedAtMs = now,
                completedAtMs = now,
                errorCode = code,
                errorMessage = message,
                error = message,
            )
        }
    }

    @Synchronized
    fun cancel(id: String, message: String = "Cancelled by user"): GenerationJobRecord? {
        val now = clock()
        return update(id) {
            it.copy(
                status = GenerationStatus.CANCELLED,
                updatedAtMs = now,
                completedAtMs = now,
                errorCode = "generation_cancelled",
                errorMessage = message,
                error = message,
            )
        }
    }

    @Synchronized
    fun finishIfStillActive(id: String, status: GenerationStatus, errorCode: String, errorMessage: String): GenerationJobRecord? {
        require(status in setOf(GenerationStatus.CANCELLED, GenerationStatus.FAILED, GenerationStatus.TIMED_OUT))
        val current = jobs[id] ?: return null
        if (!current.isActive) return current
        val now = clock()
        val next = current.copy(
            status = status,
            updatedAtMs = now,
            completedAtMs = now,
            errorCode = errorCode,
            errorMessage = errorMessage,
            error = errorMessage,
        )
        jobs[id] = next
        return next
    }

    @Synchronized
    fun get(id: String): GenerationJobRecord? {
        expireStaleActiveLocked(clock())
        return jobs[id]
    }

    @Synchronized
    fun activeForChat(chatId: String): GenerationJobRecord? {
        expireStaleActiveLocked(clock())
        return jobs.values.lastOrNull {
            it.chatId == chatId && it.isActive
        }
    }

    @Synchronized
    fun activeAny(): GenerationJobRecord? {
        expireStaleActiveLocked(clock())
        return jobs.values.lastOrNull { it.isActive }
    }

    @Synchronized
    fun recentForChat(chatId: String): List<GenerationJobRecord> {
        expireStaleActiveLocked(clock())
        return jobs.values.filter { it.chatId == chatId }.takeLast(50).reversed()
    }

    @Synchronized
    fun recentAll(limit: Int = 100): List<GenerationJobRecord> {
        expireStaleActiveLocked(clock())
        return jobs.values.toList().takeLast(limit.coerceIn(1, 300)).reversed()
    }

    @Synchronized
    fun activeSummary(): GenerationJobSummary {
        val expired = expireStaleActiveLocked(clock())
        val activeJobs = jobs.values.filter { it.isActive }
        return GenerationJobSummary(
            activeCount = activeJobs.size,
            activeJob = activeJobs.lastOrNull(),
            expiredStaleCount = expired,
        )
    }

    @Synchronized
    fun cancelAllActive(message: String = "Cancelled by admin"): List<GenerationJobRecord> {
        expireStaleActiveLocked(clock())
        val now = clock()
        val cancelled = mutableListOf<GenerationJobRecord>()
        jobs.keys.toList().forEach { id ->
            val current = jobs[id] ?: return@forEach
            if (!current.isActive) return@forEach
            val next = current.copy(
                status = GenerationStatus.CANCELLED,
                updatedAtMs = now,
                completedAtMs = now,
                errorCode = "generation_cancelled",
                errorMessage = message,
                error = message,
            )
            jobs[id] = next
            cancelled += next
        }
        return cancelled
    }

    @Synchronized
    fun cleanupStaleActive(): Int = expireStaleActiveLocked(clock())

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

    private fun expireStaleActiveLocked(now: Long): Int {
        var expired = 0
        jobs.keys.toList().forEach { id ->
            val job = jobs[id] ?: return@forEach
            if (!job.isActive) return@forEach
            val activeStartedAt = job.startedAtMs ?: job.createdAtMs
            if (now - activeStartedAt <= activeTimeoutMs) return@forEach
            jobs[id] = job.copy(
                status = GenerationStatus.TIMED_OUT,
                updatedAtMs = now,
                completedAtMs = now,
                errorCode = "generation_timed_out",
                errorMessage = "Generation job exceeded active timeout and was automatically expired.",
                error = "Generation job exceeded active timeout and was automatically expired.",
            )
            expired += 1
        }
        return expired
    }
}

val ACTIVE_GENERATION_STATUSES = setOf(
    GenerationStatus.QUEUED,
    GenerationStatus.RUNNING,
    GenerationStatus.STREAMING,
)
