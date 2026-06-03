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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future migrations will be added here as the MVP persistence model grows.
    }

    private companion object {
        const val DATABASE_NAME = "android_host_llm.db"
        const val DATABASE_VERSION = 1
    }
}
