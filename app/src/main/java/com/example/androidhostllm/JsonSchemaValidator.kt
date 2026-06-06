package com.example.androidhostllm

import org.json.JSONArray
import org.json.JSONObject

data class ValidationResult(val valid: Boolean, val error: String? = null) {
    companion object {
        val OK = ValidationResult(true)
        fun fail(message: String) = ValidationResult(false, message)
    }
}

object JsonSchemaValidator {
    fun validateObject(schema: JSONObject?, value: JSONObject): ValidationResult {
        if (schema == null) return ValidationResult.OK
        if (schema.optString("type", "object") != "object") return ValidationResult.fail("Schema root must be an object")
        val properties = schema.optJSONObject("properties") ?: JSONObject()
        val allowed = mutableSetOf<String>()
        properties.keys().forEach { allowed += it }
        value.keys().forEach { key ->
            if (key !in allowed) return ValidationResult.fail("Unexpected field: $key")
        }
        val required = schema.optJSONArray("required") ?: JSONArray()
        for (index in 0 until required.length()) {
            val key = required.optString(index)
            if (key.isBlank() || !value.has(key) || value.isNull(key)) return ValidationResult.fail("Missing required field: $key")
        }
        value.keys().forEach { key ->
            val fieldSchema = properties.optJSONObject(key) ?: return@forEach
            val result = validateValue(key, fieldSchema, value.opt(key))
            if (!result.valid) return result
        }
        return ValidationResult.OK
    }

    private fun validateValue(key: String, schema: JSONObject, value: Any?): ValidationResult {
        if (value == null || value == JSONObject.NULL) return ValidationResult.fail("$key must not be null")
        return when (schema.optString("type", "string")) {
            "string" -> {
                if (value !is String) return ValidationResult.fail("$key must be a string")
                val maxLength = schema.optInt("maxLength", DEFAULT_MAX_STRING_LENGTH)
                if (value.length > maxLength) return ValidationResult.fail("$key exceeds max length $maxLength")
                if (looksExecutable(value)) return ValidationResult.fail("$key looks like executable code")
                ValidationResult.OK
            }
            "integer" -> {
                if (value !is Number || value.toDouble() % 1.0 != 0.0) return ValidationResult.fail("$key must be an integer")
                val intValue = value.toInt()
                if (schema.has("minimum") && intValue < schema.optInt("minimum")) return ValidationResult.fail("$key is below minimum")
                if (schema.has("maximum") && intValue > schema.optInt("maximum")) return ValidationResult.fail("$key is above maximum")
                ValidationResult.OK
            }
            "number" -> {
                if (value !is Number) return ValidationResult.fail("$key must be a number")
                val doubleValue = value.toDouble()
                if (schema.has("minimum") && doubleValue < schema.optDouble("minimum")) return ValidationResult.fail("$key is below minimum")
                if (schema.has("maximum") && doubleValue > schema.optDouble("maximum")) return ValidationResult.fail("$key is above maximum")
                ValidationResult.OK
            }
            "boolean" -> if (value is Boolean) ValidationResult.OK else ValidationResult.fail("$key must be a boolean")
            "object" -> {
                val nested = value as? JSONObject ?: return ValidationResult.fail("$key must be an object")
                if (!schema.has("properties")) return ValidationResult.fail("$key does not allow nested JSON")
                validateObject(schema, nested)
            }
            "array" -> {
                value as? JSONArray ?: return ValidationResult.fail("$key must be an array")
                if (!schema.has("items")) return ValidationResult.fail("$key does not allow nested arrays")
                ValidationResult.OK
            }
            else -> ValidationResult.fail("$key has unsupported schema type")
        }
    }

    private fun looksExecutable(value: String): Boolean {
        val normalized = value.trim().lowercase()
        return EXECUTABLE_PATTERNS.any { it.containsMatchIn(normalized) }
    }

    private const val DEFAULT_MAX_STRING_LENGTH = 1_000
    private val EXECUTABLE_PATTERNS = listOf(
        Regex("""(^|[;&|`$])\s*(sh|bash|zsh|powershell|cmd|python|node|ruby|perl)\b"""),
        Regex("""\b(import\s+os|subprocess|eval\s*\(|exec\s*\(|runtime\.getruntime|processbuilder)\b"""),
        Regex("""<\s*script\b|javascript:"""),
    )
}
