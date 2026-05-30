package com.example.androidhostllm

import java.io.File

data class ModelPreset(
    val displayName: String,
    val repo: String,
    val fileName: String,
    val expectedBytes: Long,
    val minimumFreeBytes: Long,
) {
    val url: String
        get() = "https://huggingface.co/$repo/resolve/main/$fileName?download=true"

    fun targetFile(modelDirectory: File): File = File(modelDirectory, fileName)

    override fun toString(): String = displayName

    companion object {
        val GEMMA_4_E2B = ModelPreset(
            displayName = "Gemma 4 E2B IT LiteRT-LM",
            repo = "litert-community/gemma-4-E2B-it-litert-lm",
            fileName = "gemma-4-E2B-it.litertlm",
            expectedBytes = 2_590_000_000L,
            minimumFreeBytes = 3_800_000_000L,
        )

        // E4B is intentionally omitted until its exact LiteRT-LM filename can be verified.
        val ALL = listOf(GEMMA_4_E2B)
    }
}
