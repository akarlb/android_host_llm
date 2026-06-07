package com.example.androidhostllm

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStreamingSourceValidationTest {
    @Test
    fun appStreamingRouteUsesDirectStreamingWithFallback() {
        val source = File("src/main/java/com/example/androidhostllm/LocalHttpServer.kt").readText()
        val streamingFunction = source.substringAfter("private fun streamingAppMessageResponse(")
            .substringBefore("private fun buildAppPromptPlan(")

        assertTrue(streamingFunction.contains("canUseDirectStreaming(promptPlan.skill, promptPlan.skillState, userMessage.content)"))
        assertTrue(streamingFunction.contains("liteRtLmManager.generateStreaming("))
        assertTrue(streamingFunction.contains("generationJobs.appendPartial(generationId, chunk)"))
        assertTrue(streamingFunction.contains("generateAppReply("))
        assertTrue(source.contains("ToolUseMode.OPTIONAL -> !looksLikeToolRequest(userContent)"))
    }
}
