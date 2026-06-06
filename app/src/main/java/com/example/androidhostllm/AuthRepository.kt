package com.example.androidhostllm

import android.content.ContentValues
import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import kotlin.math.min

class AuthRepository(context: Context) {
    private val database = AppDatabase(context.applicationContext)
    private val secureRandom = SecureRandom()
    private val failedLogins = mutableMapOf<String, FailedLoginState>()

    @Synchronized
    fun register(username: String?, password: String?): AuthResult {
        val cleanUsername = username?.trim().orEmpty()
        val cleanPassword = password.orEmpty()
        val normalized = normalizeUsername(cleanUsername)
        if (normalized.isBlank() || cleanPassword.isBlank()) return AuthResult.InvalidFields
        if (findUserByNormalized(normalized) != null) return AuthResult.DuplicateUsername

        val now = System.currentTimeMillis()
        val role = if (countUsers() == 0) UserRole.ADMIN else UserRole.USER
        val passwordHash = PasswordHasher.hashPassword(cleanPassword)
        val user = AuthUser(
            id = UUID.randomUUID().toString(),
            username = cleanUsername,
            role = role,
        )
        database.writableDatabase.insertOrThrow(
            "users",
            null,
            ContentValues().apply {
                put("id", user.id)
                put("username", user.username)
                put("username_normalized", normalized)
                put("password_hash", passwordHash.hash)
                put("password_salt", passwordHash.salt)
                put("role", role.name)
                put("created_at_ms", now)
                put("updated_at_ms", now)
            }
        )
        return AuthResult.Success(createSession(user, now))
    }

    @Synchronized
    fun login(username: String?, password: String?): AuthResult {
        val normalized = normalizeUsername(username?.trim().orEmpty())
        val cleanPassword = password.orEmpty()
        if (normalized.isBlank() || cleanPassword.isBlank()) return AuthResult.InvalidFields
        throttleFor(normalized, System.currentTimeMillis())?.let { return it }

        val row = findUserAuthRow(normalized) ?: run {
            recordFailedLogin(normalized, System.currentTimeMillis())
            return AuthResult.InvalidCredentials
        }
        if (!PasswordHasher.verifyPassword(cleanPassword, row.passwordHash, row.passwordSalt)) {
            recordFailedLogin(normalized, System.currentTimeMillis())
            return AuthResult.InvalidCredentials
        }
        failedLogins.remove(normalized)
        return AuthResult.Success(createSession(row.user, System.currentTimeMillis()))
    }

    @Synchronized
    fun logout(token: String): Boolean {
        val tokenHash = hashSessionToken(token)
        return database.writableDatabase.delete("sessions", "token_hash = ?", arrayOf(tokenHash)) > 0
    }

    @Synchronized
    fun currentUser(token: String?): AuthUser? {
        if (token.isNullOrBlank()) return null
        val tokenHash = hashSessionToken(token)
        val now = System.currentTimeMillis()
        database.readableDatabase.rawQuery(
            """
            SELECT u.id, u.username, u.role, s.created_at_ms, s.last_seen_at_ms, s.expires_at_ms
            FROM sessions s
            JOIN users u ON u.id = s.user_id
            WHERE s.token_hash = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(tokenHash)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val createdAtMs = cursor.getLong(3)
            val lastSeenAtMs = cursor.getLong(4)
            val expiresAtMs = if (cursor.isNull(5)) createdAtMs + SESSION_ABSOLUTE_TIMEOUT_MS else cursor.getLong(5)
            if (expiresAtMs <= now || lastSeenAtMs + SESSION_IDLE_TIMEOUT_MS <= now) {
                database.writableDatabase.delete("sessions", "token_hash = ?", arrayOf(tokenHash))
                return null
            }
            database.writableDatabase.update(
                "sessions",
                ContentValues().apply { put("last_seen_at_ms", now) },
                "token_hash = ?",
                arrayOf(tokenHash)
            )
            return AuthUser(
                id = cursor.getString(0),
                username = cursor.getString(1),
                role = UserRole.valueOf(cursor.getString(2)),
            )
        }
    }

    @Synchronized
    fun logoutAllForToken(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        val tokenHash = hashSessionToken(token)
        val userId = database.readableDatabase.rawQuery(
            "SELECT user_id FROM sessions WHERE token_hash = ? LIMIT 1",
            arrayOf(tokenHash)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: return false
        return database.writableDatabase.delete("sessions", "user_id = ?", arrayOf(userId)) > 0
    }

    fun requireUser(token: String?): AuthUser? = currentUser(token)

    fun requireAdmin(token: String?): AuthUser? = currentUser(token)?.takeIf { it.role == UserRole.ADMIN }

    @Synchronized
    fun listAdminUserOverviews(): List<AdminUserOverview> {
        database.readableDatabase.rawQuery(
            """
            SELECT u.id, u.username, u.role, u.created_at_ms,
                   COUNT(DISTINCT c.id) AS chat_count,
                   COUNT(DISTINCT f.id) AS file_count
            FROM users u
            LEFT JOIN chats c ON c.user_id = u.id AND c.archived = 0
            LEFT JOIN uploaded_files f ON f.user_id = u.id
            GROUP BY u.id
            ORDER BY u.created_at_ms ASC
            """.trimIndent(),
            emptyArray<String>()
        ).use { cursor ->
            val users = mutableListOf<AdminUserOverview>()
            while (cursor.moveToNext()) {
                users += AdminUserOverview(
                    id = cursor.getString(0),
                    username = cursor.getString(1),
                    role = UserRole.valueOf(cursor.getString(2)),
                    createdAtMs = cursor.getLong(3),
                    chatCount = cursor.getInt(4),
                    fileCount = cursor.getInt(5),
                )
            }
            return users
        }
    }

    private fun createSession(user: AuthUser, now: Long): AuthSession {
        val token = generateSessionToken()
        val session = AuthSession(
            id = UUID.randomUUID().toString(),
            token = token,
            user = user,
            expiresAtMs = now + SESSION_ABSOLUTE_TIMEOUT_MS,
        )
        database.writableDatabase.insertOrThrow(
            "sessions",
            null,
            ContentValues().apply {
                put("id", session.id)
                put("token_hash", hashSessionToken(token))
                put("user_id", user.id)
                put("created_at_ms", now)
                put("last_seen_at_ms", now)
                put("expires_at_ms", session.expiresAtMs)
            }
        )
        return session
    }

    private fun throttleFor(normalized: String, now: Long): AuthResult.Throttled? {
        val state = failedLogins[normalized] ?: return null
        if (now - state.windowStartMs > FAILED_LOGIN_WINDOW_MS) {
            failedLogins.remove(normalized)
            return null
        }
        if (state.blockedUntilMs > now) {
            return AuthResult.Throttled(((state.blockedUntilMs - now) / 1000L).coerceAtLeast(1L))
        }
        return null
    }

    private fun recordFailedLogin(normalized: String, now: Long) {
        val existing = failedLogins[normalized]
        val state = if (existing == null || now - existing.windowStartMs > FAILED_LOGIN_WINDOW_MS) {
            FailedLoginState(failures = 1, windowStartMs = now, blockedUntilMs = 0L)
        } else {
            val failures = existing.failures + 1
            val blockedUntil = if (failures >= FAILED_LOGIN_LIMIT) {
                now + min(MAX_FAILED_LOGIN_BACKOFF_MS, FAILED_LOGIN_BACKOFF_MS * (failures - FAILED_LOGIN_LIMIT + 1))
            } else {
                0L
            }
            existing.copy(failures = failures, blockedUntilMs = blockedUntil)
        }
        failedLogins[normalized] = state
    }

    private fun findUserByNormalized(normalized: String): AuthUser? {
        database.readableDatabase.rawQuery(
            "SELECT id, username, role FROM users WHERE username_normalized = ? LIMIT 1",
            arrayOf(normalized)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return AuthUser(
                id = cursor.getString(0),
                username = cursor.getString(1),
                role = UserRole.valueOf(cursor.getString(2)),
            )
        }
    }

    private fun findUserAuthRow(normalized: String): UserAuthRow? {
        database.readableDatabase.rawQuery(
            "SELECT id, username, role, password_hash, password_salt FROM users WHERE username_normalized = ? LIMIT 1",
            arrayOf(normalized)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return UserAuthRow(
                user = AuthUser(
                    id = cursor.getString(0),
                    username = cursor.getString(1),
                    role = UserRole.valueOf(cursor.getString(2)),
                ),
                passwordHash = cursor.getString(3),
                passwordSalt = cursor.getString(4),
            )
        }
    }

    private fun countUsers(): Int {
        database.readableDatabase.rawQuery("SELECT COUNT(*) FROM users", emptyArray<String>()).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun generateSessionToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun hashSessionToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    private fun normalizeUsername(username: String): String = username.trim().lowercase(Locale.US)

    private data class UserAuthRow(
        val user: AuthUser,
        val passwordHash: String,
        val passwordSalt: String,
    )

    private data class FailedLoginState(
        val failures: Int,
        val windowStartMs: Long,
        val blockedUntilMs: Long,
    )

    companion object {
        const val SESSION_ABSOLUTE_TIMEOUT_MS = 12L * 60L * 60L * 1000L
        const val SESSION_IDLE_TIMEOUT_MS = 2L * 60L * 60L * 1000L
        const val FAILED_LOGIN_LIMIT = 5
        const val FAILED_LOGIN_WINDOW_MS = 15L * 60L * 1000L
        const val FAILED_LOGIN_BACKOFF_MS = 60L * 1000L
        const val MAX_FAILED_LOGIN_BACKOFF_MS = 5L * 60L * 1000L
    }
}
