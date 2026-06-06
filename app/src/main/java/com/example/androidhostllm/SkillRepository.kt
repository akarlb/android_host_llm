package com.example.androidhostllm

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class SkillRepository(context: Context) {
    private val database = AppDatabase(context.applicationContext)

    init {
        seedBuiltInSkills()
    }

    @Synchronized
    fun listEnabledSkills(): List<SkillRecord> = listSkills(enabledOnly = true)

    @Synchronized
    fun listSkills(enabledOnly: Boolean = false): List<SkillRecord> {
        val where = if (enabledOnly) "WHERE enabled = 1" else ""
        database.readableDatabase.rawQuery(
            """
            SELECT id, slug, display_name, description, system_prompt, response_mode, thinking_default,
                   show_thinking_default, tool_use_mode, allowed_tools_json, output_schema_json, strict_output,
                   built_in, enabled, created_at_ms, updated_at_ms
            FROM skills $where
            ORDER BY built_in DESC, display_name ASC
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->
            val skills = mutableListOf<SkillRecord>()
            while (cursor.moveToNext()) skills += cursor.toSkillRecord()
            return skills
        }
    }

    @Synchronized
    fun getSkillBySlug(slug: String, enabledOnly: Boolean = true): SkillRecord? {
        database.readableDatabase.rawQuery(
            """
            SELECT id, slug, display_name, description, system_prompt, response_mode, thinking_default,
                   show_thinking_default, tool_use_mode, allowed_tools_json, output_schema_json, strict_output,
                   built_in, enabled, created_at_ms, updated_at_ms
            FROM skills
            WHERE slug = ? ${if (enabledOnly) "AND enabled = 1" else ""}
            LIMIT 1
            """.trimIndent(),
            arrayOf(slug)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursor.toSkillRecord()
        }
    }

    @Synchronized
    fun getChatSkillState(chatId: String): ChatSkillStateRecord {
        val existing = findChatSkillState(chatId)
        if (existing != null) return existing
        val default = getSkillBySlug("default") ?: seedAndGetDefault()
        return setChatSkillState(chatId, default.slug, default.thinkingDefault, default.showThinkingDefault)!!
    }

    @Synchronized
    fun setChatSkillState(
        chatId: String,
        skillSlug: String,
        thinkingEnabled: Boolean? = null,
        showThinking: Boolean? = null,
    ): ChatSkillStateRecord? {
        val skill = getSkillBySlug(skillSlug) ?: return null
        val previous = findChatSkillState(chatId)
        val now = System.currentTimeMillis()
        val nextThinking = thinkingEnabled ?: if (previous?.skillSlug == skill.slug) previous.thinkingEnabled else skill.thinkingDefault
        val nextShow = showThinking ?: previous?.showThinking ?: skill.showThinkingDefault
        database.writableDatabase.insertWithOnConflict(
            "chat_skill_state",
            null,
            ContentValues().apply {
                put("chat_id", chatId)
                put("skill_id", skill.id)
                put("thinking_enabled", if (nextThinking) 1 else 0)
                put("show_thinking", if (nextShow) 1 else 0)
                put("updated_at_ms", now)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
        return ChatSkillStateRecord(chatId, skill.id, skill.slug, nextThinking, nextShow, now)
    }

    @Synchronized
    fun upsertCustomSkill(json: JSONObject, existingSlug: String? = null): SkillRecord? {
        val slug = (existingSlug ?: json.optString("slug")).trim().lowercase().takeIf { it.matches(Regex("[a-z0-9][a-z0-9-]{0,63}")) } ?: return null
        val existing = getSkillBySlug(slug, enabledOnly = false)
        if (existing?.builtIn == true) {
            val now = System.currentTimeMillis()
            database.writableDatabase.update(
                "skills",
                ContentValues().apply {
                    if (json.has("enabled")) put("enabled", if (json.optBoolean("enabled")) 1 else 0)
                    put("updated_at_ms", now)
                },
                "slug = ?",
                arrayOf(slug)
            )
            return getSkillBySlug(slug, enabledOnly = false)
        }
        val now = System.currentTimeMillis()
        val skill = SkillRecord(
            id = existing?.id ?: UUID.randomUUID().toString(),
            slug = slug,
            displayName = json.optString("displayName", existing?.displayName ?: slug).trim().ifBlank { slug },
            description = json.optString("description", existing?.description ?: "Custom skill.").trim(),
            systemPrompt = json.optString("systemPrompt", existing?.systemPrompt ?: "You are a helpful assistant.").trim(),
            responseMode = json.optString("responseMode", existing?.responseMode ?: "").takeIf { it.isNotBlank() },
            thinkingDefault = json.optBoolean("thinkingDefault", existing?.thinkingDefault ?: false),
            showThinkingDefault = json.optBoolean("showThinkingDefault", existing?.showThinkingDefault ?: false),
            toolUseMode = runCatching { ToolUseMode.valueOf(json.optString("toolUseMode", existing?.toolUseMode?.name ?: "NONE")) }.getOrDefault(ToolUseMode.NONE),
            allowedTools = json.optJSONArray("allowedTools")?.toStringList() ?: existing?.allowedTools.orEmpty(),
            outputSchemaJson = json.optJSONObject("outputSchema")?.toString() ?: json.optString("outputSchemaJson", existing?.outputSchemaJson ?: "").takeIf { it.isNotBlank() },
            strictOutput = json.optBoolean("strictOutput", existing?.strictOutput ?: false),
            builtIn = existing?.builtIn ?: false,
            enabled = json.optBoolean("enabled", existing?.enabled ?: true),
            createdAtMs = existing?.createdAtMs ?: now,
            updatedAtMs = now,
        )
        upsertSkill(skill)
        return getSkillBySlug(slug, enabledOnly = false)
    }

    @Synchronized
    fun disableOrDeleteSkill(slug: String): Boolean {
        val skill = getSkillBySlug(slug, enabledOnly = false) ?: return false
        return if (skill.builtIn) {
            database.writableDatabase.update("skills", ContentValues().apply { put("enabled", 0); put("updated_at_ms", System.currentTimeMillis()) }, "slug = ?", arrayOf(slug)) > 0
        } else {
            database.writableDatabase.delete("skills", "slug = ?", arrayOf(slug)) > 0
        }
    }

    @Synchronized
    fun exportCustomSkills(): JSONArray {
        val array = JSONArray()
        listSkills(enabledOnly = false).filterNot { it.builtIn }.forEach { skill ->
            array.put(skillExportJson(skill))
        }
        return array
    }

    @Synchronized
    fun importCustomSkills(array: JSONArray): Pair<Int, String?> {
        var imported = 0
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: return imported to "Item $index is not an object"
            val slug = item.optString("slug").trim().lowercase()
            val existing = getSkillBySlug(slug, enabledOnly = false)
            if (existing?.builtIn == true) return imported to "Cannot overwrite built-in skill: $slug"
            if (upsertCustomSkill(item) == null) return imported to "Invalid skill at item $index"
            imported += 1
        }
        return imported to null
    }

    @Synchronized
    fun insertToolLog(chatId: String, messageId: String?, toolName: String, requestJson: JSONObject, resultJson: JSONObject?, status: ToolCallStatus, error: String? = null): ToolCallLogRecord {
        val log = ToolCallLogRecord(UUID.randomUUID().toString(), chatId, messageId, toolName, requestJson.toString(), resultJson?.toString(), status, error, System.currentTimeMillis())
        database.writableDatabase.insertOrThrow("tool_call_log", null, ContentValues().apply {
            put("id", log.id); put("chat_id", log.chatId); put("message_id", log.messageId); put("tool_name", log.toolName)
            put("request_json", log.requestJson); put("result_json", log.resultJson); put("status", log.status.name); put("error_message", log.errorMessage); put("created_at_ms", log.createdAtMs)
        })
        return log
    }

    @Synchronized
    fun listToolLogs(chatId: String): List<ToolCallLogRecord> {
        database.readableDatabase.rawQuery(
            "SELECT id, chat_id, message_id, tool_name, request_json, result_json, status, error_message, created_at_ms FROM tool_call_log WHERE chat_id = ? ORDER BY created_at_ms DESC LIMIT 100",
            arrayOf(chatId)
        ).use { cursor ->
            val logs = mutableListOf<ToolCallLogRecord>()
            while (cursor.moveToNext()) logs += ToolCallLogRecord(
                cursor.getString(0), cursor.getString(1), if (cursor.isNull(2)) null else cursor.getString(2), cursor.getString(3), cursor.getString(4), if (cursor.isNull(5)) null else cursor.getString(5),
                runCatching { ToolCallStatus.valueOf(cursor.getString(6)) }.getOrDefault(ToolCallStatus.FAILED), if (cursor.isNull(7)) null else cursor.getString(7), cursor.getLong(8)
            )
            return logs
        }
    }

    @Synchronized
    fun listRecentToolLogs(limit: Int = 100): List<ToolCallLogRecord> {
        database.readableDatabase.rawQuery(
            """
            SELECT id, chat_id, message_id, tool_name, request_json, result_json, status, error_message, created_at_ms
            FROM tool_call_log
            ORDER BY created_at_ms DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.coerceIn(1, 200).toString())
        ).use { cursor ->
            val logs = mutableListOf<ToolCallLogRecord>()
            while (cursor.moveToNext()) logs += ToolCallLogRecord(
                cursor.getString(0),
                cursor.getString(1),
                if (cursor.isNull(2)) null else cursor.getString(2),
                cursor.getString(3),
                cursor.getString(4),
                if (cursor.isNull(5)) null else cursor.getString(5),
                runCatching { ToolCallStatus.valueOf(cursor.getString(6)) }.getOrDefault(ToolCallStatus.FAILED),
                if (cursor.isNull(7)) null else cursor.getString(7),
                cursor.getLong(8)
            )
            return logs
        }
    }

    private fun skillExportJson(skill: SkillRecord): JSONObject {
        return JSONObject()
            .put("slug", skill.slug)
            .put("displayName", skill.displayName)
            .put("description", skill.description)
            .put("systemPrompt", skill.systemPrompt)
            .put("responseMode", skill.responseMode ?: JSONObject.NULL)
            .put("thinkingDefault", skill.thinkingDefault)
            .put("showThinkingDefault", skill.showThinkingDefault)
            .put("toolUseMode", skill.toolUseMode.name)
            .put("allowedTools", JSONArray(skill.allowedTools))
            .put("outputSchema", skill.outputSchemaJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject.NULL)
            .put("strictOutput", skill.strictOutput)
            .put("enabled", skill.enabled)
    }

    private fun findChatSkillState(chatId: String): ChatSkillStateRecord? {
        return database.readableDatabase.rawQuery(
            """
            SELECT s.chat_id, s.skill_id, k.slug, s.thinking_enabled, s.show_thinking, s.updated_at_ms
            FROM chat_skill_state s JOIN skills k ON k.id = s.skill_id
            WHERE s.chat_id = ? AND k.enabled = 1
            LIMIT 1
            """.trimIndent(),
            arrayOf(chatId)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toChatSkillStateRecord() else null }
    }

    private fun seedAndGetDefault(): SkillRecord {
        seedBuiltInSkills()
        return getSkillBySlug("default")!!
    }

    private fun seedBuiltInSkills() {
        builtIns().forEach { upsertSkill(it) }
    }

    private fun upsertSkill(skill: SkillRecord) {
        database.writableDatabase.insertWithOnConflict("skills", null, ContentValues().apply {
            put("id", skill.id); put("slug", skill.slug); put("display_name", skill.displayName); put("description", skill.description); put("system_prompt", skill.systemPrompt)
            put("response_mode", skill.responseMode); put("thinking_default", if (skill.thinkingDefault) 1 else 0); put("show_thinking_default", if (skill.showThinkingDefault) 1 else 0)
            put("tool_use_mode", skill.toolUseMode.name); put("allowed_tools_json", JSONArray(skill.allowedTools).toString()); put("output_schema_json", skill.outputSchemaJson)
            put("strict_output", if (skill.strictOutput) 1 else 0); put("built_in", if (skill.builtIn) 1 else 0); put("enabled", if (skill.enabled) 1 else 0)
            put("created_at_ms", skill.createdAtMs); put("updated_at_ms", skill.updatedAtMs)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun builtIns(): List<SkillRecord> {
        val now = System.currentTimeMillis()
        val gdprSchema = JSONObject().put("type", "object").put("required", JSONArray(listOf("confirm_deletion", "reason"))).put("properties", JSONObject().put("confirm_deletion", JSONObject().put("type", "boolean")).put("reason", JSONObject().put("type", "string"))).toString()
        fun skill(slug: String, name: String, desc: String, prompt: String, mode: ToolUseMode, tools: List<String>, think: Boolean = false, strict: Boolean = false, schema: String? = null) =
            SkillRecord("builtin-$slug", slug, name, desc, prompt.trim(), null, think, false, mode, tools, schema, strict, true, true, now, now)
        return listOf(
            skill("default", "Default", "General local assistant.", """
                You are a helpful local assistant running on the user's Android device. Be clear, concise, and practical. Use attached files when relevant. If tools are available and useful, request exactly one tool call using the required tool-call JSON format. Otherwise answer directly.
            """, ToolUseMode.OPTIONAL, listOf("get_current_datetime", "list_chat_files", "search_attached_markdown")),
            skill("coding", "Coding", "Fast coding help and patch-oriented answers.", """
                You are assisting with coding. Prioritize direct fixes, commands, patches, and concise explanations. Avoid long background unless needed. If attached files are relevant, use them. If tools are useful, request exactly one tool call using the required tool-call JSON format.
            """, ToolUseMode.OPTIONAL, listOf("list_chat_files", "search_attached_markdown")),
            skill("gdpr-pii-audit", "GDPR PII Audit", "Audits snippets for student/staff/parent PII and deletion justification under GDPR Article 17.", """
                You are a precise German Data Protection Officer (DSB) enforcing the General Data Protection Regulation (GDPR). Your sole task is to audit a provided text snippet to verify if it contains personally identifiable information (PII) regarding students, staff, or parents, such as names, specific identifiers, or combinations of data that make an individual identifiable, justifying its flagged status for deletion under Article 17 GDPR, the right to erasure.

                You must output only a valid minified JSON object.

                Do not include markdown code block formatting.

                Do not include conversational filler or pre/post-text.

                If the text contains identifiable names or specific student, staff, or parent identifiers, set confirm_deletion to true.

                If it contains purely aggregated, anonymized, or non-personal information, set confirm_deletion to false.

                Expected JSON schema:
                {"confirm_deletion": boolean, "reason": "A clear, concise, human-readable reason in English detailing what PII was found or why it is absent."}
            """, ToolUseMode.NONE, emptyList(), think = true, strict = true, schema = gdprSchema),
            skill("markdown-qa", "Markdown Q&A", "Answers questions using attached Markdown files.", """
                You answer questions using the selected Markdown files attached to the current chat. If the answer depends on file content, use attached file context and available file-search tools. If context is truncated, be transparent but calm. Do not expose internal token limits.
            """, ToolUseMode.OPTIONAL, listOf("list_chat_files", "search_attached_markdown", "count_markdown_chunks")),
        )
    }

    private fun Cursor.toSkillRecord() = SkillRecord(
        getString(0), getString(1), getString(2), getString(3), getString(4), if (isNull(5)) null else getString(5), getInt(6) != 0, getInt(7) != 0,
        runCatching { ToolUseMode.valueOf(getString(8)) }.getOrDefault(ToolUseMode.NONE), JSONArray(getString(9)).toStringList(), if (isNull(10)) null else getString(10), getInt(11) != 0, getInt(12) != 0, getInt(13) != 0, getLong(14), getLong(15)
    )

    private fun Cursor.toChatSkillStateRecord() = ChatSkillStateRecord(getString(0), getString(1), getString(2), getInt(3) != 0, getInt(4) != 0, getLong(5))
}

fun JSONArray.toStringList(): List<String> {
    val values = mutableListOf<String>()
    for (i in 0 until length()) optString(i).trim().takeIf { it.isNotBlank() }?.let { values += it }
    return values.distinct()
}
