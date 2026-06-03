package com.example.androidhostllm

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PasswordHasher {
    private const val SALT_BYTES = 16
    private const val HASH_BYTES = 32
    private const val PBKDF2_ITERATIONS = 120_000
    private const val PASSWORD_ALGORITHM = "PBKDF2WithHmacSHA256"
    private val secureRandom = SecureRandom()

    fun hashPassword(password: String): PasswordHash {
        val salt = ByteArray(SALT_BYTES)
        secureRandom.nextBytes(salt)
        val hash = pbkdf2(password, salt)
        return PasswordHash(
            hash = base64(hash),
            salt = base64(salt),
        )
    }

    fun verifyPassword(password: String, expectedHash: String, salt: String): Boolean {
        val saltBytes = runCatching { Base64.decode(salt, Base64.NO_WRAP) }.getOrNull() ?: return false
        val actualHash = pbkdf2(password, saltBytes)
        val expectedHashBytes = runCatching { Base64.decode(expectedHash, Base64.NO_WRAP) }.getOrNull() ?: return false
        return MessageDigest.isEqual(expectedHashBytes, actualHash)
    }

    private fun pbkdf2(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, HASH_BYTES * 8)
        return SecretKeyFactory.getInstance(PASSWORD_ALGORITHM).generateSecret(spec).encoded
    }

    private fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
}

data class PasswordHash(
    val hash: String,
    val salt: String,
)
