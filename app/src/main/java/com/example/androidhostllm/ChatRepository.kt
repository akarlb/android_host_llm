package com.example.androidhostllm

import android.content.ContentValues
import android.content.Context
import java.util.UUID

class ChatRepository(context: Context) {
    private val database = AppDatabase(context.applicationContext)

    @Synchronized
    fun listChats(userId: String): List<ChatRecord> {
        database.readableDatabase.rawQuery(
            """
            SELECT id, user_id, title, profile, created_at_ms, updated_at_ms
            FROM chats
            WHERE user_id = ? AND archived = 0
            ORDER BY updated_at_ms DESC
            """.trimIndent(),
            arrayOf(userId)
        ).use { cursor ->
            val chats = mutableListOf<ChatRecord>()
            while (cursor.moveToNext()) {
                chats += cursor.toChatRecord()
            }
            return chats
        }
    }

    @Synchronized
    fun createChat(userId: String, title: String?, profile: ChatProfile): ChatRecord {
        val now = System.currentTimeMillis()
        val cleanTitle = title?.trim()?.takeIf { it.isNotBlank() } ?: "New chat"
        val chat = ChatRecord(
            id = UUID.randomUUID().toString(),
            userId = userId,
            title = cleanTitle,
            profile = profile,
            createdAtMs = now,
            updatedAtMs = now,
        )
        database.writableDatabase.insertOrThrow(
            "chats",
            null,
            ContentValues().apply {
                put("id", chat.id)
                put("user_id", chat.userId)
                put("title", chat.title)
                put("profile", chat.profile.name)
                put("created_at_ms", chat.createdAtMs)
                put("updated_at_ms", chat.updatedAtMs)
                put("archived", 0)
            }
        )
        return chat
    }

    @Synchronized
    fun getChat(userId: String, chatId: String): ChatRecord? {
        database.readableDatabase.rawQuery(
            """
            SELECT id, user_id, title, profile, created_at_ms, updated_at_ms
            FROM chats
            WHERE id = ? AND user_id = ? AND archived = 0
            LIMIT 1
            """.trimIndent(),
            arrayOf(chatId, userId)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursor.toChatRecord()
        }
    }

    @Synchronized
    fun listMessages(userId: String, chatId: String): List<MessageRecord>? {
        getChat(userId, chatId) ?: return null
        database.readableDatabase.rawQuery(
            """
            SELECT id, chat_id, role, content, created_at_ms, thinking_text, raw_content
            FROM messages
            WHERE chat_id = ?
            ORDER BY created_at_ms ASC
            """.trimIndent(),
            arrayOf(chatId)
        ).use { cursor ->
            val messages = mutableListOf<MessageRecord>()
            while (cursor.moveToNext()) {
                messages += cursor.toMessageRecord()
            }
            return messages
        }
    }

    @Synchronized
    fun addMessage(chatId: String, role: String, content: String, createdAtMs: Long = System.currentTimeMillis(), thinking: String? = null, rawContent: String? = null): MessageRecord {
        val message = MessageRecord(
            id = UUID.randomUUID().toString(),
            chatId = chatId,
            role = role,
            content = content,
            createdAtMs = createdAtMs,
            thinking = thinking,
            rawContent = rawContent,
        )
        database.writableDatabase.insertOrThrow(
            "messages",
            null,
            ContentValues().apply {
                put("id", message.id)
                put("chat_id", message.chatId)
                put("role", message.role)
                put("content", message.content)
                put("created_at_ms", message.createdAtMs)
                put("thinking_text", message.thinking)
                put("raw_content", message.rawContent)
            }
        )
        touchChat(chatId, createdAtMs)
        return message
    }

    @Synchronized
    fun archiveChat(userId: String, chatId: String): Boolean {
        val now = System.currentTimeMillis()
        return database.writableDatabase.update(
            "chats",
            ContentValues().apply {
                put("archived", 1)
                put("updated_at_ms", now)
            },
            "id = ? AND user_id = ? AND archived = 0",
            arrayOf(chatId, userId)
        ) > 0
    }

    @Synchronized
    fun renameChat(userId: String, chatId: String, title: String): ChatRecord? {
        val cleanTitle = title.trim().takeIf { it.isNotBlank() }?.take(120) ?: return null
        val now = System.currentTimeMillis()
        val updated = database.writableDatabase.update(
            "chats",
            ContentValues().apply {
                put("title", cleanTitle)
                put("updated_at_ms", now)
            },
            "id = ? AND user_id = ? AND archived = 0",
            arrayOf(chatId, userId)
        ) > 0
        return if (updated) getChat(userId, chatId) else null
    }

    @Synchronized
    fun touchChat(chatId: String, updatedAtMs: Long = System.currentTimeMillis()) {
        database.writableDatabase.update(
            "chats",
            ContentValues().apply { put("updated_at_ms", updatedAtMs) },
            "id = ?",
            arrayOf(chatId)
        )
    }

    @Synchronized
    fun getContextState(chatId: String, fileId: String): ChatFileContextState? {
        database.readableDatabase.rawQuery(
            """
            SELECT chat_id, file_id, last_included_chunk_index, updated_at_ms
            FROM chat_file_context_state
            WHERE chat_id = ? AND file_id = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(chatId, fileId)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return ChatFileContextState(
                chatId = cursor.getString(0),
                fileId = cursor.getString(1),
                lastIncludedChunkIndex = cursor.getInt(2),
                updatedAtMs = cursor.getLong(3),
            )
        }
    }

    @Synchronized
    fun updateContextState(chatId: String, fileId: String, lastIncludedChunkIndex: Int) {
        val now = System.currentTimeMillis()
        database.writableDatabase.insertWithOnConflict(
            "chat_file_context_state",
            null,
            ContentValues().apply {
                put("chat_id", chatId)
                put("file_id", fileId)
                put("last_included_chunk_index", lastIncludedChunkIndex)
                put("updated_at_ms", now)
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    @Synchronized
    fun resetContextState(chatId: String, fileId: String) {
        database.writableDatabase.delete(
            "chat_file_context_state",
            "chat_id = ? AND file_id = ?",
            arrayOf(chatId, fileId)
        )
    }

    @Synchronized
    fun listAttachedFiles(userId: String, chatId: String): List<UploadedFileRecord>? {
        getChat(userId, chatId) ?: return null
        database.readableDatabase.rawQuery(
            """
            SELECT f.id, f.user_id, f.original_filename, f.safe_filename, f.mime_type, f.size_bytes,
                   f.storage_path, f.created_at_ms, COUNT(c.id) AS chunk_count
            FROM chat_file_attachments a
            JOIN uploaded_files f ON f.id = a.file_id AND f.user_id = a.user_id
            LEFT JOIN file_chunks c ON c.file_id = f.id
            WHERE a.user_id = ? AND a.chat_id = ?
            GROUP BY f.id
            ORDER BY a.created_at_ms ASC
            """.trimIndent(),
            arrayOf(userId, chatId)
        ).use { cursor ->
            val files = mutableListOf<UploadedFileRecord>()
            while (cursor.moveToNext()) files += cursor.toUploadedFileRecord()
            return files
        }
    }

    @Synchronized
    fun listAttachedFileIds(userId: String, chatId: String): List<String>? {
        return listAttachedFiles(userId, chatId)?.map { it.id }
    }

    @Synchronized
    fun attachFile(userId: String, chatId: String, fileId: String): Boolean {
        getChat(userId, chatId) ?: return false
        if (!userOwnsFile(userId, fileId)) return false
        val now = System.currentTimeMillis()
        database.writableDatabase.insertWithOnConflict(
            "chat_file_attachments",
            null,
            ContentValues().apply {
                put("chat_id", chatId)
                put("file_id", fileId)
                put("user_id", userId)
                put("created_at_ms", now)
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
        )
        return true
    }

    @Synchronized
    fun detachFile(userId: String, chatId: String, fileId: String): Boolean {
        getChat(userId, chatId) ?: return false
        database.writableDatabase.delete(
            "chat_file_attachments",
            "chat_id = ? AND file_id = ? AND user_id = ?",
            arrayOf(chatId, fileId, userId)
        )
        resetContextState(chatId, fileId)
        return true
    }

    @Synchronized
    fun totalChatCount(): Int {
        database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM chats WHERE archived = 0",
            emptyArray<String>()
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun android.database.Cursor.toChatRecord(): ChatRecord {
        return ChatRecord(
            id = getString(0),
            userId = getString(1),
            title = getString(2),
            profile = runCatching { ChatProfile.valueOf(getString(3)) }.getOrDefault(ChatProfile.CONVERSATION),
            createdAtMs = getLong(4),
            updatedAtMs = getLong(5),
        )
    }

    private fun android.database.Cursor.toMessageRecord(): MessageRecord {
        return MessageRecord(
            id = getString(0),
            chatId = getString(1),
            role = getString(2),
            content = getString(3),
            createdAtMs = getLong(4),
            thinking = if (isNull(5)) null else getString(5),
            rawContent = if (isNull(6)) null else getString(6),
        )
    }

    private fun android.database.Cursor.toUploadedFileRecord(): UploadedFileRecord {
        return UploadedFileRecord(
            id = getString(0),
            userId = getString(1),
            originalFilename = getString(2),
            safeFilename = getString(3),
            mimeType = getString(4),
            sizeBytes = getLong(5),
            storagePath = getString(6),
            createdAtMs = getLong(7),
            chunkCount = getInt(8),
        )
    }

    private fun userOwnsFile(userId: String, fileId: String): Boolean {
        database.readableDatabase.rawQuery(
            "SELECT 1 FROM uploaded_files WHERE id = ? AND user_id = ? LIMIT 1",
            arrayOf(fileId, userId)
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }
}
