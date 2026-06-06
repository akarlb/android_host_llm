package com.example.androidhostllm

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class AppDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE users (
                id TEXT PRIMARY KEY,
                username TEXT NOT NULL,
                username_normalized TEXT UNIQUE NOT NULL,
                password_hash TEXT NOT NULL,
                password_salt TEXT NOT NULL,
                role TEXT NOT NULL,
                created_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE sessions (
                id TEXT PRIMARY KEY,
                token_hash TEXT NOT NULL,
                user_id TEXT NOT NULL,
                created_at_ms INTEGER NOT NULL,
                last_seen_at_ms INTEGER NOT NULL,
                expires_at_ms INTEGER NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX sessions_token_hash_idx ON sessions(token_hash)")
        db.execSQL("CREATE INDEX sessions_user_id_idx ON sessions(user_id)")
        createChatTables(db)
        createFileTables(db)
        createChatFileContextStateTable(db)
        createChatFileAttachmentTable(db)
        createSkillTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createChatTables(db)
        }
        if (oldVersion < 3) {
            createFileTables(db)
        }
        if (oldVersion < 4) {
            createChatFileContextStateTable(db)
        }
        if (oldVersion < 5) {
            createChatFileAttachmentTable(db)
        }
        if (oldVersion < 6) {
            createSkillTables(db)
            addMessageThinkingColumns(db)
        }
        if (oldVersion < 7) {
            createSkillTables(db)
            addToolLogTraceColumns(db)
        }
    }

    private fun createChatTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chats (
                id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL,
                title TEXT NOT NULL,
                profile TEXT NOT NULL,
                created_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                archived INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS messages (
                id TEXT PRIMARY KEY,
                chat_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at_ms INTEGER NOT NULL,
                thinking_text TEXT NULL,
                raw_content TEXT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS chats_user_archived_updated_idx ON chats(user_id, archived, updated_at_ms)")
        db.execSQL("CREATE INDEX IF NOT EXISTS messages_chat_created_idx ON messages(chat_id, created_at_ms)")
    }

    private fun createFileTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS uploaded_files (
                id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL,
                original_filename TEXT NOT NULL,
                safe_filename TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                size_bytes INTEGER NOT NULL,
                storage_path TEXT NOT NULL,
                created_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS file_chunks (
                id TEXT PRIMARY KEY,
                file_id TEXT NOT NULL,
                chunk_index INTEGER NOT NULL,
                heading_path TEXT NULL,
                content TEXT NOT NULL,
                char_count INTEGER NOT NULL,
                created_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS uploaded_files_user_created_idx ON uploaded_files(user_id, created_at_ms)")
        db.execSQL("CREATE INDEX IF NOT EXISTS file_chunks_file_index_idx ON file_chunks(file_id, chunk_index)")
    }

    private fun createChatFileContextStateTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chat_file_context_state (
                chat_id TEXT NOT NULL,
                file_id TEXT NOT NULL,
                last_included_chunk_index INTEGER NOT NULL DEFAULT -1,
                updated_at_ms INTEGER NOT NULL,
                PRIMARY KEY(chat_id, file_id)
            )
            """.trimIndent()
        )
    }

    private fun createChatFileAttachmentTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chat_file_attachments (
                chat_id TEXT NOT NULL,
                file_id TEXT NOT NULL,
                user_id TEXT NOT NULL,
                created_at_ms INTEGER NOT NULL,
                PRIMARY KEY(chat_id, file_id)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS chat_file_attachments_user_chat_idx ON chat_file_attachments(user_id, chat_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS chat_file_attachments_file_idx ON chat_file_attachments(file_id)")
    }


    private fun createSkillTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS skills (
                id TEXT PRIMARY KEY,
                slug TEXT NOT NULL UNIQUE,
                display_name TEXT NOT NULL,
                description TEXT NOT NULL,
                system_prompt TEXT NOT NULL,
                response_mode TEXT NULL,
                thinking_default INTEGER NOT NULL DEFAULT 0,
                show_thinking_default INTEGER NOT NULL DEFAULT 0,
                tool_use_mode TEXT NOT NULL DEFAULT 'NONE',
                allowed_tools_json TEXT NOT NULL DEFAULT '[]',
                output_schema_json TEXT NULL,
                strict_output INTEGER NOT NULL DEFAULT 0,
                built_in INTEGER NOT NULL DEFAULT 0,
                enabled INTEGER NOT NULL DEFAULT 1,
                created_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chat_skill_state (
                chat_id TEXT PRIMARY KEY,
                skill_id TEXT NOT NULL,
                thinking_enabled INTEGER NOT NULL DEFAULT 0,
                show_thinking INTEGER NOT NULL DEFAULT 0,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS tool_call_log (
                id TEXT PRIMARY KEY,
                request_id TEXT NULL,
                chat_id TEXT NOT NULL,
                message_id TEXT NULL,
                tool_name TEXT NOT NULL,
                request_json TEXT NOT NULL,
                result_json TEXT NULL,
                status TEXT NOT NULL,
                skill_slug TEXT NULL,
                skill_version INTEGER NULL,
                raw_model_output TEXT NULL,
                parsed_tool_name TEXT NULL,
                sanitized_args_json TEXT NULL,
                sanitized_result_preview TEXT NULL,
                duration_ms INTEGER NULL,
                error_code TEXT NULL,
                error_message TEXT NULL,
                created_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS tool_call_log_chat_created_idx ON tool_call_log(chat_id, created_at_ms)")
    }

    private fun addMessageThinkingColumns(db: SQLiteDatabase) {
        runCatching { db.execSQL("ALTER TABLE messages ADD COLUMN thinking_text TEXT NULL") }
        runCatching { db.execSQL("ALTER TABLE messages ADD COLUMN raw_content TEXT NULL") }
    }

    private fun addToolLogTraceColumns(db: SQLiteDatabase) {
        runCatching { db.execSQL("ALTER TABLE tool_call_log ADD COLUMN request_id TEXT NULL") }
        runCatching { db.execSQL("ALTER TABLE tool_call_log ADD COLUMN skill_slug TEXT NULL") }
        runCatching { db.execSQL("ALTER TABLE tool_call_log ADD COLUMN skill_version INTEGER NULL") }
        runCatching { db.execSQL("ALTER TABLE tool_call_log ADD COLUMN raw_model_output TEXT NULL") }
        runCatching { db.execSQL("ALTER TABLE tool_call_log ADD COLUMN parsed_tool_name TEXT NULL") }
        runCatching { db.execSQL("ALTER TABLE tool_call_log ADD COLUMN sanitized_args_json TEXT NULL") }
        runCatching { db.execSQL("ALTER TABLE tool_call_log ADD COLUMN sanitized_result_preview TEXT NULL") }
        runCatching { db.execSQL("ALTER TABLE tool_call_log ADD COLUMN duration_ms INTEGER NULL") }
        runCatching { db.execSQL("ALTER TABLE tool_call_log ADD COLUMN error_code TEXT NULL") }
    }

    companion object {
        const val DATABASE_NAME = "android_host_llm.db"
        const val DATABASE_VERSION = 7
        const val SCHEMA_VERSION = DATABASE_VERSION
    }
}
