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
            SELECT id, chat_id, role, content, created_at_ms
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
    fun addMessage(chatId: String, role: String, content: String, createdAtMs: Long = System.currentTimeMillis()): MessageRecord {
        val message = MessageRecord(
            id = UUID.randomUUID().toString(),
            chatId = chatId,
            role = role,
            content = content,
            createdAtMs = createdAtMs,
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
    fun touchChat(chatId: String, updatedAtMs: Long = System.currentTimeMillis()) {
        database.writableDatabase.update(
            "chats",
            ContentValues().apply { put("updated_at_ms", updatedAtMs) },
            "id = ?",
            arrayOf(chatId)
        )
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
        )
    }
}
