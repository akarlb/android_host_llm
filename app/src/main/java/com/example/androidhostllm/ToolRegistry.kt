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
data class ToolCallParseResult(
    val call: ParsedToolCall?,
    val status: ToolCallStatus,
    val errorCode: String?,
    val errorMessage: String?,
    val repairable: Boolean,
)
data class ToolExecutionResult(val status: ToolCallStatus, val result: JSONObject?, val errorCode: String?, val error: String?)

class ToolRegistry(
    private val chatRepository: ChatRepository,
    private val fileRepository: FileRepository,
) {
    private val tools = listOf(
        ToolDefinition("get_current_datetime", "Get current date/time", "Returns the device current date and time.", objectSchema(), objectSchema(), allowedForSkills = listOf("default")),
        ToolDefinition("list_chat_files", "List chat files", "Lists Markdown files attached to the current chat.", objectSchema(), objectSchema(), allowedForSkills = listOf("default", "coding", "markdown-qa")),
        ToolDefinition("count_markdown_chunks", "Count Markdown chunks", "Returns chunk and character counts for an attached Markdown file.", objectSchema(required = listOf("fileId"), properties = mapOf("fileId" to JSONObject().put("type", "string").put("maxLength", 120))), objectSchema(), allowedForSkills = listOf("markdown-qa")),
        ToolDefinition("search_attached_markdown", "Search attached Markdown", "Searches attached Markdown chunks with simple keyword overlap.", objectSchema(properties = mapOf("query" to JSONObject().put("type", "string").put("maxLength", 500), "limit" to JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 10))), objectSchema(), allowedForSkills = listOf("default", "coding", "markdown-qa")),
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

    fun parseToolCall(text: String): ParsedToolCall? = parseToolCallDetailed(text).call

    fun parseToolCallDetailed(text: String): ToolCallParseResult {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return parseFailure("empty_tool_call", "Tool call is empty", repairable = true)
        if (trimmed.length > MAX_TOOL_CALL_CHARS) return parseFailure("tool_call_too_large", "Tool call payload is too large", repairable = false)
        val json = candidateJsonObjectForToolCall(trimmed)
            ?: return parseFailure("tool_call_not_json", "No single JSON object found", repairable = looksLikeToolCall(trimmed))
        if (json.length() != 1 || !json.has("tool_call")) return parseFailure("invalid_tool_call_root", "Expected only a tool_call object", repairable = true)
        val call = json.optJSONObject("tool_call") ?: return parseFailure("invalid_tool_call_shape", "tool_call must be an object", repairable = true)
        val allowedCallFields = setOf("name", "arguments")
        call.keys().forEach { if (it !in allowedCallFields) return parseFailure("unexpected_tool_call_field", "Unexpected tool_call field: $it", repairable = false) }
        val name = call.optString("name").trim()
        if (name.isBlank()) return parseFailure("missing_tool_name", "Tool name is required", repairable = true)
        val tool = byName[name] ?: return ToolCallParseResult(null, ToolCallStatus.UNKNOWN_TOOL, "unknown_tool", "Unknown tool: $name", repairable = false)
        val args = if (call.has("arguments")) {
            call.optJSONObject("arguments") ?: return parseFailure("invalid_arguments_shape", "Tool arguments must be an object", repairable = true)
        } else {
            JSONObject()
        }
        val validation = validateArguments(tool, args)
        if (!validation.valid) return ToolCallParseResult(null, ToolCallStatus.INVALID_ARGUMENTS, "invalid_arguments", validation.error ?: "Tool arguments are invalid", repairable = true)
        return ToolCallParseResult(ParsedToolCall(name, args, json), ToolCallStatus.SUCCESS, null, null, repairable = false)
    }

    fun execute(userId: String, chatId: String, skill: SkillRecord, call: ParsedToolCall): ToolExecutionResult {
        val tool = byName[call.name] ?: return ToolExecutionResult(ToolCallStatus.UNKNOWN_TOOL, null, "unknown_tool", "Tool is unavailable")
        if (chatRepository.getChat(userId, chatId) == null) return ToolExecutionResult(ToolCallStatus.PERMISSION_DENIED, null, "chat_not_owned", "Chat is unavailable")
        if (!tool.enabled || call.name !in skill.allowedTools || (tool.allowedForSkills.isNotEmpty() && skill.slug !in tool.allowedForSkills)) {
            return ToolExecutionResult(ToolCallStatus.PERMISSION_DENIED, null, "tool_not_allowed", "Tool is not allowed for this skill")
        }
        if (tool.dangerLevel != "SAFE") return ToolExecutionResult(ToolCallStatus.PERMISSION_DENIED, null, "danger_level_denied", "Tool danger level is not allowed")
        val validation = validateArguments(tool, call.arguments)
        if (!validation.valid) return ToolExecutionResult(ToolCallStatus.INVALID_ARGUMENTS, null, "invalid_arguments", validation.error ?: "Tool arguments are invalid")
        return runCatching {
            when (call.name) {
                "get_current_datetime" -> currentDateTime()
                "list_chat_files" -> listChatFiles(userId, chatId)
                "count_markdown_chunks" -> countMarkdownChunks(userId, chatId, call.arguments)
                "search_attached_markdown" -> searchAttachedMarkdown(userId, chatId, call.arguments)
                else -> throw IllegalArgumentException("Tool is unavailable")
            }
        }.fold(
            onSuccess = { ToolExecutionResult(ToolCallStatus.SUCCESS, it, null, null) },
            onFailure = { ToolExecutionResult(ToolCallStatus.EXECUTION_FAILED, null, "execution_failed", "Tool execution failed") },
        )
    }

    private fun validateArguments(tool: ToolDefinition, args: JSONObject): ValidationResult {
        if (args.toString().length > MAX_ARGUMENT_CHARS) return ValidationResult.fail("Arguments payload is too large")
        return JsonSchemaValidator.validateObject(tool.inputSchema, args)
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

        private fun parseFailure(code: String, message: String, repairable: Boolean): ToolCallParseResult {
            return ToolCallParseResult(null, ToolCallStatus.PARSE_FAILED, code, message, repairable)
        }

        internal fun candidateJsonObjectForToolCall(text: String): JSONObject? {
            fencedBlocks(text)
                .filter { it.info.lowercase(Locale.US) == "json" }
                .firstNotNullOfOrNull { block -> firstJsonObjectIn(block.content) }
                ?.let { return it }

            fencedBlocks(text)
                .firstNotNullOfOrNull { block -> firstJsonObjectIn(block.content) }
                ?.let { return it }

            return firstJsonObjectIn(text)
        }

        private data class FencedBlock(val info: String, val content: String)

        private fun fencedBlocks(text: String): Sequence<FencedBlock> = sequence {
            var searchFrom = 0
            while (searchFrom < text.length) {
                val fenceStart = text.indexOf("```", searchFrom)
                if (fenceStart < 0) break
                val contentStart = text.indexOf('\n', fenceStart + 3)
                if (contentStart < 0) break
                val fenceEnd = text.indexOf("```", contentStart + 1)
                if (fenceEnd < 0) break

                val info = text.substring(fenceStart + 3, contentStart).trim()
                val content = text.substring(contentStart + 1, fenceEnd).trim()
                yield(FencedBlock(info, content))
                searchFrom = fenceEnd + 3
            }
        }

        private fun firstJsonObjectIn(text: String): JSONObject? {
            return extractTopLevelJsonObjectStrings(text).firstNotNullOfOrNull { candidate ->
                runCatching { JSONObject(candidate) }.getOrNull()
            }
        }

        private fun extractTopLevelJsonObjectStrings(text: String): List<String> {
            val ranges = mutableListOf<IntRange>()
            var depth = 0
            var start = -1
            var inString = false
            var escaped = false
            text.forEachIndexed { index, char ->
                if (escaped) {
                    escaped = false
                    return@forEachIndexed
                }
                if (char == '\\' && inString) {
                    escaped = true
                    return@forEachIndexed
                }
                if (char == '"') inString = !inString
                if (inString) return@forEachIndexed
                when (char) {
                    '{' -> {
                        if (depth == 0) start = index
                        depth += 1
                    }
                    '}' -> {
                        if (depth > 0) depth -= 1
                        if (depth == 0 && start >= 0) {
                            ranges += start..index
                            start = -1
                        }
                    }
                }
            }
            return ranges.map { text.substring(it) }
        }

        private fun looksLikeToolCall(text: String): Boolean {
            val lower = text.lowercase(Locale.US)
            return "tool_call" in lower || "\"name\"" in lower && "\"arguments\"" in lower || "```json" in lower
        }
    }
}
