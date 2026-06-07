package com.example.androidhostllm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ToolRegistryJsonParserTest {
    @Test
    fun extractsFencedJsonBlock() {
        val parsed = ToolRegistry.candidateJsonObjectForToolCall(
            """
            ```json
            {"a":1}
            ```
            """.trimIndent()
        )

        assertNotNull(parsed)
        assertEquals(1, parsed?.getInt("a"))
    }

    @Test
    fun extractsPlainFencedJsonBlock() {
        val parsed = ToolRegistry.candidateJsonObjectForToolCall(
            """
            ```
            {"a":1}
            ```
            """.trimIndent()
        )

        assertNotNull(parsed)
        assertEquals(1, parsed?.getInt("a"))
    }

    @Test
    fun extractsNestedObject() {
        val parsed = ToolRegistry.candidateJsonObjectForToolCall("""{"tool":{"name":"x","args":{"a":1}}}""")

        assertNotNull(parsed)
        assertEquals(1, parsed?.getJSONObject("tool")?.getJSONObject("args")?.getInt("a"))
    }

    @Test
    fun ignoresBracesInsideString() {
        val parsed = ToolRegistry.candidateJsonObjectForToolCall("""{"text":"hello {not real brace}"}""")

        assertNotNull(parsed)
        assertEquals("hello {not real brace}", parsed?.getString("text"))
    }

    @Test
    fun handlesEscapedQuotesInsideString() {
        val parsed = ToolRegistry.candidateJsonObjectForToolCall("""{"text":"hello \"quoted\" text"}""")

        assertNotNull(parsed)
        assertEquals("hello \"quoted\" text", parsed?.getString("text"))
    }

    @Test
    fun extractsJsonWithProseAroundIt() {
        val parsed = ToolRegistry.candidateJsonObjectForToolCall("""Before {"a":1} after.""")

        assertNotNull(parsed)
        assertEquals(1, parsed?.getInt("a"))
    }

    @Test
    fun malformedJsonReturnsNull() {
        val parsed = ToolRegistry.candidateJsonObjectForToolCall(
            """
            ```json
            {"a":}
            ```
            """.trimIndent()
        )

        assertNull(parsed)
    }
}
