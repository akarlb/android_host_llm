package com.example.androidhostllm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationJobStoreTest {
    @Test
    fun activeAnyReturnsActiveJobBeforeTimeout() {
        var now = 1_000L
        val store = GenerationJobStore(activeTimeoutMs = 10_000L, clock = { now })
        val job = store.create("chat-1", "user-1", "message-1")
        store.markRunning(job.id, streaming = false)

        now += 5_000L

        val active = store.activeAny()
        assertNotNull(active)
        assertEquals(job.id, active?.id)
        assertEquals(GenerationStatus.RUNNING, active?.status)
    }

    @Test
    fun activeAnyExpiresStaleActiveJobAfterTimeout() {
        var now = 1_000L
        val store = GenerationJobStore(activeTimeoutMs = 10_000L, clock = { now })
        val job = store.create("chat-1", "user-1", "message-1")
        store.markRunning(job.id, streaming = true)

        now += 10_001L

        assertNull(store.activeAny())
        val expired = store.get(job.id)
        assertEquals(GenerationStatus.TIMED_OUT, expired?.status)
        assertEquals("generation_timed_out", expired?.errorCode)
        assertEquals("Generation job exceeded active timeout and was automatically expired.", expired?.errorMessage)
        assertFalse(expired?.isActive ?: true)
    }

    @Test
    fun cancelAllActiveCancelsActiveJobsOnly() {
        var now = 1_000L
        val store = GenerationJobStore(activeTimeoutMs = 10_000L, clock = { now })
        val active = store.create("chat-1", "user-1", "message-1")
        store.markRunning(active.id, streaming = false)
        val completed = store.create("chat-2", "user-2", "message-2")
        store.complete(completed.id, "assistant-1", "done")

        now += 1_000L
        val cancelled = store.cancelAllActive()

        assertEquals(1, cancelled.size)
        assertEquals(active.id, cancelled.first().id)
        assertEquals(GenerationStatus.CANCELLED, store.get(active.id)?.status)
        assertEquals(GenerationStatus.COMPLETED, store.get(completed.id)?.status)
        assertNull(store.activeAny())
    }

    @Test
    fun finishIfStillActiveDoesNotOverwriteCompletedJob() {
        val store = GenerationJobStore(activeTimeoutMs = 10_000L, clock = { 1_000L })
        val job = store.create("chat-1", "user-1", "message-1")
        store.complete(job.id, "assistant-1", "done")

        val result = store.finishIfStillActive(
            job.id,
            GenerationStatus.FAILED,
            "streaming_response_failed",
            "Stream failed",
        )

        assertEquals(GenerationStatus.COMPLETED, result?.status)
        assertEquals(GenerationStatus.COMPLETED, store.get(job.id)?.status)
        assertTrue(store.recentAll().none { it.isActive })
    }
}
