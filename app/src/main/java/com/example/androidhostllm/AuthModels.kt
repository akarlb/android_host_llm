package com.example.androidhostllm

data class AuthUser(
    val id: String,
    val username: String,
    val role: UserRole,
)

data class AdminUserOverview(
    val id: String,
    val username: String,
    val role: UserRole,
    val createdAtMs: Long,
    val chatCount: Int,
    val fileCount: Int,
)

data class AuthSession(
    val id: String,
    val token: String,
    val user: AuthUser,
)

enum class UserRole {
    ADMIN,
    USER,
}

sealed class AuthResult {
    data class Success(val session: AuthSession) : AuthResult()
    object InvalidFields : AuthResult()
    object DuplicateUsername : AuthResult()
    object InvalidCredentials : AuthResult()
}
