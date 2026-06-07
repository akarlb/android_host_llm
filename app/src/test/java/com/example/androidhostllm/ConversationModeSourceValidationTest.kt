package com.example.androidhostllm

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationModeSourceValidationTest {
    @Test
    fun normalChatConsumesSavedConversationModeWithOneShotRecoveryFreshMode() {
        val managerSource = File("src/main/java/com/example/androidhostllm/LiteRtLmManager.kt").readText()
        val serverSource = File("src/main/java/com/example/androidhostllm/LocalHttpServer.kt").readText()

        assertTrue(managerSource.contains("forceFreshNextGeneration = true"))
        assertTrue(managerSource.contains("fun consumeRecoveryConversationMode(preferred: ConversationMode): ConversationMode"))
        assertTrue(managerSource.contains("return if (forceFreshNextGeneration)"))
        assertTrue(serverSource.contains("liteRtLmManager.consumeRecoveryConversationMode(appPreferences.savedConversationMode())"))
        assertTrue(!serverSource.contains("val conversationMode = ConversationMode.FRESH_PER_REQUEST"))
    }
}
