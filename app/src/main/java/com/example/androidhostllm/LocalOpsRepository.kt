package com.example.androidhostllm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class LocalOpsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val database = AppDatabase(appContext)
    private val uploadRoot = File(appContext.filesDir, "uploaded_markdown")

    @Synchronized
    fun backupExport(tools: List<ToolDefinition>, safeSettings: JSONObject): JSONObject {
        return JSONObject()
            .put("schemaVersion", AppDatabase.SCHEMA_VERSION)
            .put("exportedAtMs", System.currentTimeMillis())
            .put("users", safeUsersJson())
            .put("chats", chatsJson())
            .put("messages", messagesJson())
            .put("uploadedFiles", uploadedFilesJson(includeContent = true))
            .put("chatFileAttachments", tableJson("SELECT chat_id, file_id, user_id, created_at_ms FROM chat_file_attachments") { cursor ->
                JSONObject().put("chatId", cursor.getString(0)).put("fileId", cursor.getString(1)).put("userId", cursor.getString(2)).put("createdAtMs", cursor.getLong(3))
            })
            .put("chatFileContextState", tableJson("SELECT chat_id, file_id, last_included_chunk_index, updated_at_ms FROM chat_file_context_state") { cursor ->
                JSONObject().put("chatId", cursor.getString(0)).put("fileId", cursor.getString(1)).put("lastIncludedChunkIndex", cursor.getInt(2)).put("updatedAtMs", cursor.getLong(3))
            })
            .put("skills", skillsJson())
            .put("chatSkillState", tableJson("SELECT chat_id, skill_id, thinking_enabled, show_thinking, updated_at_ms FROM chat_skill_state") { cursor ->
                JSONObject().put("chatId", cursor.getString(0)).put("skillId", cursor.getString(1)).put("thinkingEnabled", cursor.getInt(2) != 0).put("showThinking", cursor.getInt(3) != 0).put("updatedAtMs", cursor.getLong(4))
            })
            .put("tools", JSONArray().also { array -> tools.forEach { array.put(toolExportJson(it)) } })
            .put("safeAppSettings", safeSettings)
            .put("excluded", JSONArray(listOf("password_hash", "password_salt", "sessions", "token_hash", "hugging_face_token", "raw_storage_path")))
    }

    @Synchronized
    fun diagnostics(health: JSONObject, modelLoaded: Boolean, serverMode: String, recentErrors: JSONArray): JSONObject {
        val scan = storageScan()
        return JSONObject()
            .put("schemaVersion", AppDatabase.SCHEMA_VERSION)
            .put("exportedAtMs", System.currentTimeMillis())
            .put("app", JSONObject().put("packageName", appContext.packageName).put("databaseName", AppDatabase.DATABASE_NAME))
            .put("mode", JSONObject().put("serverMode", serverMode))
            .put("health", health)
            .put("modelLoaded", modelLoaded)
            .put("counts", countsJson())
            .put("storageScan", scan)
            .put("recentErrors", recentErrors)
            .put("routeMatrixVersion", "docs/security/route_auth_matrix.md")
            .put("excluded", JSONArray(listOf("secrets", "password hashes", "session tokens", "full user content", "raw storage paths")))
    }

    @Synchronized
    fun storageScan(): JSONObject {
        val dbDiskPaths = mutableSetOf<String>()
        database.readableDatabase.rawQuery("SELECT storage_path FROM uploaded_files", emptyArray()).use { cursor ->
            while (cursor.moveToNext()) dbDiskPaths += cursor.getString(0)
        }
        val diskFiles = uploadRoot.walkTopDown().filter { it.isFile }.toList()
        val orphanDiskFiles = diskFiles.filter { it.path !in dbDiskPaths }
        return JSONObject()
            .put("schemaVersion", AppDatabase.SCHEMA_VERSION)
            .put("scannedAtMs", System.currentTimeMillis())
            .put("counts", countsJson())
            .put("orphanChunks", count("SELECT COUNT(*) FROM file_chunks c LEFT JOIN uploaded_files f ON f.id = c.file_id WHERE f.id IS NULL"))
            .put("orphanAttachments", count("SELECT COUNT(*) FROM chat_file_attachments a LEFT JOIN chats c ON c.id = a.chat_id LEFT JOIN uploaded_files f ON f.id = a.file_id WHERE c.id IS NULL OR f.id IS NULL"))
            .put("orphanContextStates", count("SELECT COUNT(*) FROM chat_file_context_state s LEFT JOIN chats c ON c.id = s.chat_id LEFT JOIN uploaded_files f ON f.id = s.file_id WHERE c.id IS NULL OR f.id IS NULL"))
            .put("orphanToolLogs", count("SELECT COUNT(*) FROM tool_call_log l LEFT JOIN chats c ON c.id = l.chat_id WHERE c.id IS NULL"))
            .put("missingStoredFiles", countMissingStoredFiles())
            .put("orphanDiskFiles", orphanDiskFiles.size)
            .put("orphanDiskBytes", orphanDiskFiles.sumOf { it.length() })
            .put("cleanupConfirmation", CLEANUP_CONFIRMATION)
    }

    @Synchronized
    fun cleanupOrphans(confirm: String): JSONObject? {
        if (confirm != CLEANUP_CONFIRMATION) return null
        val db = database.writableDatabase
        var chunks = 0
        var attachments = 0
        var contextStates = 0
        var toolLogs = 0
        db.beginTransaction()
        try {
            chunks = db.delete("file_chunks", "file_id NOT IN (SELECT id FROM uploaded_files)", emptyArray())
            attachments = db.delete("chat_file_attachments", "chat_id NOT IN (SELECT id FROM chats) OR file_id NOT IN (SELECT id FROM uploaded_files)", emptyArray())
            contextStates = db.delete("chat_file_context_state", "chat_id NOT IN (SELECT id FROM chats) OR file_id NOT IN (SELECT id FROM uploaded_files)", emptyArray())
            toolLogs = db.delete("tool_call_log", "chat_id NOT IN (SELECT id FROM chats)", emptyArray())
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        val dbDiskPaths = mutableSetOf<String>()
        database.readableDatabase.rawQuery("SELECT storage_path FROM uploaded_files", emptyArray()).use { cursor ->
            while (cursor.moveToNext()) dbDiskPaths += cursor.getString(0)
        }
        var diskFiles = 0
        var diskBytes = 0L
        uploadRoot.walkTopDown().filter { it.isFile && it.path !in dbDiskPaths }.forEach { file ->
            diskBytes += file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) diskFiles += 1
        }
        return JSONObject()
            .put("status", "ok")
            .put("deleted", JSONObject().put("chunks", chunks).put("attachments", attachments).put("contextStates", contextStates).put("toolLogs", toolLogs).put("diskFiles", diskFiles).put("diskBytes", diskBytes))
            .put("scan", storageScan())
    }

    private fun safeUsersJson() = tableJson("SELECT id, username, role, created_at_ms, updated_at_ms FROM users ORDER BY created_at_ms ASC") { cursor ->
        JSONObject().put("id", cursor.getString(0)).put("username", cursor.getString(1)).put("role", cursor.getString(2)).put("createdAtMs", cursor.getLong(3)).put("updatedAtMs", cursor.getLong(4))
    }

    private fun chatsJson() = tableJson("SELECT id, user_id, title, profile, created_at_ms, updated_at_ms, archived FROM chats ORDER BY updated_at_ms ASC") { cursor ->
        JSONObject().put("id", cursor.getString(0)).put("userId", cursor.getString(1)).put("title", cursor.getString(2)).put("profile", cursor.getString(3)).put("createdAtMs", cursor.getLong(4)).put("updatedAtMs", cursor.getLong(5)).put("archived", cursor.getInt(6) != 0)
    }

    private fun messagesJson() = tableJson("SELECT id, chat_id, role, content, created_at_ms, thinking_text FROM messages ORDER BY created_at_ms ASC") { cursor ->
        JSONObject().put("id", cursor.getString(0)).put("chatId", cursor.getString(1)).put("role", cursor.getString(2)).put("content", cursor.getString(3)).put("createdAtMs", cursor.getLong(4)).put("thinking", if (cursor.isNull(5)) JSONObject.NULL else cursor.getString(5))
    }

    private fun uploadedFilesJson(includeContent: Boolean) = tableJson("SELECT id, user_id, original_filename, safe_filename, mime_type, size_bytes, storage_path, created_at_ms FROM uploaded_files ORDER BY created_at_ms ASC") { cursor ->
        val storagePath = cursor.getString(6)
        val file = File(storagePath)
        JSONObject()
            .put("id", cursor.getString(0))
            .put("userId", cursor.getString(1))
            .put("originalFilename", cursor.getString(2))
            .put("safeFilename", cursor.getString(3))
            .put("mimeType", cursor.getString(4))
            .put("sizeBytes", cursor.getLong(5))
            .put("createdAtMs", cursor.getLong(7))
            .put("content", if (includeContent && file.exists() && file.length() <= MAX_EXPORT_FILE_BYTES) runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: JSONObject.NULL else JSONObject.NULL)
            .put("chunks", chunksJson(cursor.getString(0)))
    }

    private fun chunksJson(fileId: String) = tableJson("SELECT id, chunk_index, heading_path, content, char_count, created_at_ms FROM file_chunks WHERE file_id = ? ORDER BY chunk_index ASC", arrayOf(fileId)) { cursor ->
        JSONObject().put("id", cursor.getString(0)).put("chunkIndex", cursor.getInt(1)).put("headingPath", if (cursor.isNull(2)) JSONObject.NULL else cursor.getString(2)).put("content", cursor.getString(3)).put("charCount", cursor.getInt(4)).put("createdAtMs", cursor.getLong(5))
    }

    private fun skillsJson() = tableJson("SELECT id, slug, display_name, description, system_prompt, response_mode, thinking_default, show_thinking_default, tool_use_mode, allowed_tools_json, output_schema_json, strict_output, built_in, enabled, created_at_ms, updated_at_ms FROM skills ORDER BY built_in DESC, display_name ASC") { cursor ->
        val outputSchemaText = if (cursor.isNull(10)) null else cursor.getString(10)
        JSONObject()
            .put("id", cursor.getString(0)).put("slug", cursor.getString(1)).put("displayName", cursor.getString(2))
            .put("description", cursor.getString(3)).put("systemPrompt", cursor.getString(4))
            .put("responseMode", if (cursor.isNull(5)) JSONObject.NULL else cursor.getString(5))
            .put("thinkingDefault", cursor.getInt(6) != 0).put("showThinkingDefault", cursor.getInt(7) != 0)
            .put("toolUseMode", cursor.getString(8)).put("allowedTools", JSONArray(cursor.getString(9)))
            .put("outputSchema", outputSchemaText?.let { runCatching { JSONObject(it) }.getOrNull() ?: it } ?: JSONObject.NULL)
            .put("strictOutput", cursor.getInt(11) != 0).put("builtIn", cursor.getInt(12) != 0).put("enabled", cursor.getInt(13) != 0)
            .put("createdAtMs", cursor.getLong(14)).put("updatedAtMs", cursor.getLong(15))
    }

    private fun toolExportJson(tool: ToolDefinition): JSONObject {
        return JSONObject().put("name", tool.name).put("displayName", tool.displayName).put("description", tool.description).put("enabled", tool.enabled).put("dangerLevel", tool.dangerLevel).put("allowedForSkills", JSONArray(tool.allowedForSkills)).put("inputSchema", tool.inputSchema).put("outputSchema", tool.outputSchema)
    }

    private fun countsJson(): JSONObject {
        return JSONObject()
            .put("users", count("SELECT COUNT(*) FROM users"))
            .put("activeChats", count("SELECT COUNT(*) FROM chats WHERE archived = 0"))
            .put("archivedChats", count("SELECT COUNT(*) FROM chats WHERE archived = 1"))
            .put("messages", count("SELECT COUNT(*) FROM messages"))
            .put("uploadedFiles", count("SELECT COUNT(*) FROM uploaded_files"))
            .put("fileChunks", count("SELECT COUNT(*) FROM file_chunks"))
            .put("chatFileAttachments", count("SELECT COUNT(*) FROM chat_file_attachments"))
            .put("toolLogs", count("SELECT COUNT(*) FROM tool_call_log"))
            .put("storageBytes", countLong("SELECT COALESCE(SUM(size_bytes), 0) FROM uploaded_files"))
    }

    private fun countMissingStoredFiles(): Int {
        var missing = 0
        database.readableDatabase.rawQuery("SELECT storage_path FROM uploaded_files", emptyArray()).use { cursor ->
            while (cursor.moveToNext()) if (!File(cursor.getString(0)).exists()) missing += 1
        }
        return missing
    }

    private fun count(sql: String): Int = countLong(sql).toInt()

    private fun countLong(sql: String): Long {
        database.readableDatabase.rawQuery(sql, emptyArray()).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    private fun tableJson(sql: String, args: Array<String> = emptyArray(), mapper: (android.database.Cursor) -> JSONObject): JSONArray {
        val array = JSONArray()
        database.readableDatabase.rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext()) array.put(mapper(cursor))
        }
        return array
    }

    companion object {
        const val CLEANUP_CONFIRMATION = "cleanup-orphans"
        private const val MAX_EXPORT_FILE_BYTES = 2L * 1024L * 1024L
    }
}
