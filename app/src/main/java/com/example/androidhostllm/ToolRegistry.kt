package com.example.androidhostllm

import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale

data class ToolDefinition(
    val name: String,
    val displayName: String,
    val description: String,
    val inputSchema: JSONObject,
    val outputSchema: JSONObject,
    val enabled: Boolean = true,
    val dangerLevel: String = "SAFE",
    val allowedForSkills: List<String> = emptyList(),
)

data class ParsedToolCall(val name: String, val arguments: JSONObject, val raw: JSONObject)
data class ToolExecutionResult(val status: ToolCallStatus, val result: JSONObject?, val error: String?)

class ToolRegistry(
    private val chatRepository: ChatRepository,
    private val fileRepository: FileRepository,
) {
    private val tools = listOf(
        ToolDefinition("get_current_datetime", "Get current date/time", "Returns the device current date and time.", objectSchema(), objectSchema(), allowedForSkills = listOf("default")),
        ToolDefinition("list_chat_files", "List chat files", "Lists Markdown files attached to the current chat.", objectSchema(), objectSchema(), allowedForSkills = listOf("default", "coding", "markdown-qa")),
        ToolDefinition("count_markdown_chunks", "Count Markdown chunks", "Returns chunk and character counts for an attached Markdown file.", objectSchema(required = listOf("fileId"), properties = mapOf("fileId" to JSONObject().put("type", "string"))), objectSchema(), allowedForSkills = listOf("markdown-qa")),
        ToolDefinition("search_attached_markdown", "Search attached Markdown", "Searches attached Markdown chunks with simple keyword overlap.", objectSchema(properties = mapOf("query" to JSONObject().put("type", "string"), "limit" to JSONObject().put("type", "integer"))), objectSchema(), allowedForSkills = listOf("default", "coding", "markdown-qa")),
    )
    private val byName = tools.associateBy { it.name }

    fun listSafeMetadata(): List<ToolDefinition> = tools.filter { it.enabled }
    fun listAdminMetadata(): List<ToolDefinition> = tools

    fun toolInstructions(skill: SkillRecord): String {
        if (skill.toolUseMode == ToolUseMode.NONE) return ""
        val available = skill.allowedTools.mapNotNull { byName[it] }.filter { it.enabled }
        if (available.isEmpty()) return ""
        return buildString {
            appendLine("Available tools for this skill:")
            available.forEach { tool -> appendLine("- ${tool.name}: ${tool.description}") }
            appendLine("Tool-call protocol:")
            appendLine("- If you need a tool, output only this JSON object: {\"tool_call\":{\"name\":\"tool_name\",\"arguments\":{}}}")
            appendLine("- Do not include prose around a tool call.")
            appendLine("- Request only one tool call at a time.")
            appendLine("- Use only the tools listed above.")
            appendLine("- If no tool is needed, answer normally.")
        }.trim()
    }

    fun parseToolCall(text: String): ParsedToolCall? {
        val trimmed = text.trim()
        if (trimmed.length > MAX_TOOL_CALL_CHARS) return null
        val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
        if (json.length() != 1 || !json.has("tool_call")) return null
        val call = json.optJSONObject("tool_call") ?: return null
        val name = call.optString("name").trim()
        if (name.isBlank()) return null
        val args = call.optJSONObject("arguments") ?: JSONObject()
        return ParsedToolCall(name, args, json)
    }

    fun execute(userId: String, chatId: String, skill: SkillRecord, call: ParsedToolCall): ToolExecutionResult {
        val tool = byName[call.name] ?: return ToolExecutionResult(ToolCallStatus.REJECTED, null, "Tool is unavailable")
        if (!tool.enabled || call.name !in skill.allowedTools || (tool.allowedForSkills.isNotEmpty() && skill.slug !in tool.allowedForSkills)) {
            return ToolExecutionResult(ToolCallStatus.REJECTED, null, "Tool is not allowed for this skill")
        }
        if (!validateArguments(tool, call.arguments)) return ToolExecutionResult(ToolCallStatus.REJECTED, null, "Tool arguments are invalid")
        return runCatching {
            when (call.name) {
                "get_current_datetime" -> currentDateTime()
                "list_chat_files" -> listChatFiles(userId, chatId)
                "count_markdown_chunks" -> countMarkdownChunks(userId, chatId, call.arguments)
                "search_attached_markdown" -> searchAttachedMarkdown(userId, chatId, call.arguments)
                else -> throw IllegalArgumentException("Tool is unavailable")
            }
        }.fold(
            onSuccess = { ToolExecutionResult(ToolCallStatus.SUCCESS, it, null) },
            onFailure = { ToolExecutionResult(ToolCallStatus.FAILED, null, "Tool execution failed") },
        )
    }

    private fun validateArguments(tool: ToolDefinition, args: JSONObject): Boolean {
        if (args.toString().length > MAX_ARGUMENT_CHARS) return false
        val props = tool.inputSchema.optJSONObject("properties") ?: JSONObject()
        val allowed = mutableSetOf<String>()
        props.keys().forEach { allowed += it }
        args.keys().forEach { if (it !in allowed) return false }
        val required = tool.inputSchema.optJSONArray("required") ?: JSONArray()
        for (i in 0 until required.length()) if (!args.has(required.getString(i))) return false
        return true
    }

    private fun currentDateTime(): JSONObject {
        val zone = ZoneId.systemDefault()
        return JSONObject().put("iso", OffsetDateTime.now(zone).toString()).put("timezone", zone.id)
    }

    private fun listChatFiles(userId: String, chatId: String): JSONObject {
        val files = JSONArray()
        chatRepository.listAttachedFiles(userId, chatId).orEmpty().forEach { file ->
            files.put(JSONObject().put("id", file.id).put("filename", file.safeFilename).put("sizeBytes", file.sizeBytes).put("chunkCount", file.chunkCount))
        }
        return JSONObject().put("files", files)
    }

    private fun countMarkdownChunks(userId: String, chatId: String, args: JSONObject): JSONObject {
        val fileId = args.optString("fileId")
        val file = chatRepository.listAttachedFiles(userId, chatId).orEmpty().firstOrNull { it.id == fileId } ?: throw IllegalArgumentException("File not attached")
        val chunks = fileRepository.listChunks(userId, file.id).orEmpty()
        return JSONObject().put("fileId", file.id).put("chunkCount", chunks.size).put("totalChars", chunks.sumOf { it.charCount })
    }

    private fun searchAttachedMarkdown(userId: String, chatId: String, args: JSONObject): JSONObject {
        val query = args.optString("query").trim()
        val limit = args.optInt("limit", 5).coerceIn(1, 10)
        val terms = keywords(query)
        val matches = chatRepository.listAttachedFiles(userId, chatId).orEmpty().flatMap { file ->
            fileRepository.listChunks(userId, file.id).orEmpty().map { chunk ->
                val score = keywords(chunk.headingPath.orEmpty() + " " + chunk.content).count { it in terms }
                Triple(file, chunk, score)
            }
        }.filter { it.third > 0 || terms.isEmpty() }.sortedWith(compareByDescending<Triple<UploadedFileRecord, FileChunkRecord, Int>> { it.third }.thenBy { it.second.chunkIndex }).take(limit)
        val array = JSONArray()
        matches.forEach { (file, chunk, _) ->
            array.put(JSONObject()
                .put("fileId", file.id)
                .put("filename", file.safeFilename)
                .put("chunkIndex", chunk.chunkIndex)
                .put("heading", chunk.headingPath ?: JSONObject.NULL)
                .put("snippet", snippet(chunk.content, terms)))
        }
        return JSONObject().put("matches", array)
    }

    private fun snippet(content: String, terms: Set<String>): String {
        val lower = content.lowercase(Locale.US)
        val index = terms.asSequence().map { lower.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
        val start = (index - 180).coerceAtLeast(0)
        return content.substring(start, (start + 600).coerceAtMost(content.length)).replace(Regex("\\s+"), " ").trim()
    }

    private fun keywords(text: String): Set<String> = text.lowercase(Locale.US).split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 3 && it !in setOf("the", "and", "for", "with", "this", "that", "from") }.toSet()

    companion object {
        private const val MAX_TOOL_CALL_CHARS = 8_000
        private const val MAX_ARGUMENT_CHARS = 2_000
        private fun objectSchema(required: List<String> = emptyList(), properties: Map<String, JSONObject> = emptyMap()): JSONObject {
            val props = JSONObject(); properties.forEach { (key, value) -> props.put(key, value) }
            return JSONObject().put("type", "object").put("required", JSONArray(required)).put("properties", props)
        }
    }
}
