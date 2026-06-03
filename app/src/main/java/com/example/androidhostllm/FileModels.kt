package com.example.androidhostllm

data class UploadedFileRecord(
    val id: String,
    val userId: String,
    val originalFilename: String,
    val safeFilename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val storagePath: String,
    val createdAtMs: Long,
    val chunkCount: Int,
)

data class FileChunkRecord(
    val id: String,
    val fileId: String,
    val chunkIndex: Int,
    val headingPath: String?,
    val content: String,
    val charCount: Int,
    val createdAtMs: Long,
)

sealed class FileUploadResult {
    data class Success(val file: UploadedFileRecord) : FileUploadResult()
    data object InvalidFilename : FileUploadResult()
    data object InvalidType : FileUploadResult()
    data object Oversized : FileUploadResult()
    data object InvalidEncoding : FileUploadResult()
}

data class FileContextBuildResult(
    val promptBlock: String,
    val fileIds: List<String>,
    val includedChunks: Int,
    val includedChars: Int,
    val truncated: Boolean,
)
