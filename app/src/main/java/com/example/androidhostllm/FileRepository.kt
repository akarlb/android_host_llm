package com.example.androidhostllm

import android.content.ContentValues
import android.content.Context
import java.io.File
import java.util.Locale
import java.util.UUID

class FileRepository(context: Context) {
    private val appContext = context.applicationContext
    private val database = AppDatabase(appContext)
    private val uploadRoot = File(appContext.filesDir, "uploaded_markdown")

    @Synchronized
    fun uploadMarkdown(
        userId: String,
        filename: String?,
        content: String?,
        mimeType: String?,
    ): FileUploadResult {
        val cleanFilename = filename?.trim().orEmpty()
        val safeFilename = sanitizeFilename(cleanFilename) ?: return FileUploadResult.InvalidFilename
        val cleanMimeType = mimeType?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotBlank() } ?: "text/markdown"
        if (!isAllowedMarkdown(cleanFilename, cleanMimeType)) return FileUploadResult.InvalidType

        val markdown = content ?: return FileUploadResult.InvalidEncoding
        val bytes = markdown.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_UPLOAD_BYTES) return FileUploadResult.Oversized
        if (markdown.contains('\u0000')) return FileUploadResult.InvalidEncoding

        val now = System.currentTimeMillis()
        val fileId = UUID.randomUUID().toString()
        val userDir = File(uploadRoot, userId).canonicalFile
        val rootCanonical = uploadRoot.canonicalFile
        if (!userDir.path.startsWith(rootCanonical.path)) return FileUploadResult.InvalidFilename
        userDir.mkdirs()

        val storageFile = File(userDir, "$fileId-$safeFilename").canonicalFile
        if (!storageFile.path.startsWith(userDir.path)) return FileUploadResult.InvalidFilename
        storageFile.writeBytes(bytes)

        val chunks = MarkdownChunker.chunk(markdown)
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            db.insertOrThrow(
                "uploaded_files",
                null,
                ContentValues().apply {
                    put("id", fileId)
                    put("user_id", userId)
                    put("original_filename", cleanFilename)
                    put("safe_filename", safeFilename)
                    put("mime_type", cleanMimeType)
                    put("size_bytes", bytes.size.toLong())
                    put("storage_path", storageFile.path)
                    put("created_at_ms", now)
                }
            )
            chunks.forEachIndexed { index, chunk ->
                db.insertOrThrow(
                    "file_chunks",
                    null,
                    ContentValues().apply {
                        put("id", UUID.randomUUID().toString())
                        put("file_id", fileId)
                        put("chunk_index", index)
                        if (chunk.headingPath == null) putNull("heading_path") else put("heading_path", chunk.headingPath)
                        put("content", chunk.content)
                        put("char_count", chunk.content.length)
                        put("created_at_ms", now)
                    }
                )
            }
            db.setTransactionSuccessful()
        } catch (error: Throwable) {
            runCatching { storageFile.delete() }
            throw error
        } finally {
            db.endTransaction()
        }

        return FileUploadResult.Success(
            UploadedFileRecord(
                id = fileId,
                userId = userId,
                originalFilename = cleanFilename,
                safeFilename = safeFilename,
                mimeType = cleanMimeType,
                sizeBytes = bytes.size.toLong(),
                storagePath = storageFile.path,
                createdAtMs = now,
                chunkCount = chunks.size,
            )
        )
    }

    @Synchronized
    fun listFiles(userId: String): List<UploadedFileRecord> {
        database.readableDatabase.rawQuery(
            """
            SELECT f.id, f.user_id, f.original_filename, f.safe_filename, f.mime_type, f.size_bytes,
                   f.storage_path, f.created_at_ms, COUNT(c.id) AS chunk_count
            FROM uploaded_files f
            LEFT JOIN file_chunks c ON c.file_id = f.id
            WHERE f.user_id = ?
            GROUP BY f.id
            ORDER BY f.created_at_ms DESC
            """.trimIndent(),
            arrayOf(userId)
        ).use { cursor ->
            val files = mutableListOf<UploadedFileRecord>()
            while (cursor.moveToNext()) files += cursor.toUploadedFileRecord()
            return files
        }
    }

    @Synchronized
    fun listAdminFileOverviews(): List<AdminFileOverview> {
        database.readableDatabase.rawQuery(
            """
            SELECT f.id, u.username, f.original_filename, f.size_bytes, COUNT(c.id) AS chunk_count, f.created_at_ms
            FROM uploaded_files f
            JOIN users u ON u.id = f.user_id
            LEFT JOIN file_chunks c ON c.file_id = f.id
            GROUP BY f.id
            ORDER BY f.created_at_ms DESC
            """.trimIndent(),
            emptyArray<String>()
        ).use { cursor ->
            val files = mutableListOf<AdminFileOverview>()
            while (cursor.moveToNext()) {
                files += AdminFileOverview(
                    id = cursor.getString(0),
                    username = cursor.getString(1),
                    filename = cursor.getString(2),
                    sizeBytes = cursor.getLong(3),
                    chunkCount = cursor.getInt(4),
                    createdAtMs = cursor.getLong(5),
                )
            }
            return files
        }
    }

    @Synchronized
    fun totalFileCount(): Int {
        database.readableDatabase.rawQuery("SELECT COUNT(*) FROM uploaded_files", emptyArray<String>()).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    @Synchronized
    fun getFile(userId: String, fileId: String): UploadedFileRecord? {
        database.readableDatabase.rawQuery(
            """
            SELECT f.id, f.user_id, f.original_filename, f.safe_filename, f.mime_type, f.size_bytes,
                   f.storage_path, f.created_at_ms, COUNT(c.id) AS chunk_count
            FROM uploaded_files f
            LEFT JOIN file_chunks c ON c.file_id = f.id
            WHERE f.id = ? AND f.user_id = ?
            GROUP BY f.id
            LIMIT 1
            """.trimIndent(),
            arrayOf(fileId, userId)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursor.toUploadedFileRecord()
        }
    }

    @Synchronized
    fun listChunks(userId: String, fileId: String): List<FileChunkRecord>? {
        getFile(userId, fileId) ?: return null
        database.readableDatabase.rawQuery(
            """
            SELECT id, file_id, chunk_index, heading_path, content, char_count, created_at_ms
            FROM file_chunks
            WHERE file_id = ?
            ORDER BY chunk_index ASC
            """.trimIndent(),
            arrayOf(fileId)
        ).use { cursor ->
            val chunks = mutableListOf<FileChunkRecord>()
            while (cursor.moveToNext()) chunks += cursor.toFileChunkRecord()
            return chunks
        }
    }

    @Synchronized
    fun deleteFile(userId: String, fileId: String): Boolean {
        val file = getFile(userId, fileId) ?: return false
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            db.delete("file_chunks", "file_id = ?", arrayOf(fileId))
            val deleted = db.delete("uploaded_files", "id = ? AND user_id = ?", arrayOf(fileId, userId)) > 0
            if (!deleted) return false
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        runCatching { File(file.storagePath).delete() }
        return true
    }

    @Synchronized
    fun buildContext(
        userId: String,
        fileIds: List<String>,
        budgetChars: Int = CONTEXT_BUDGET_CHARS,
        question: String = "",
        continuationMode: Boolean = false,
        continuationState: Map<String, Int> = emptyMap(),
    ): FileContextBuildResult? {
        if (fileIds.isEmpty()) return null
        val files = fileIds.map { fileId -> getFile(userId, fileId) ?: return null }
        val safeBudget = budgetChars.coerceAtLeast(0)
        val keywords = keywords(question)
        val builder = StringBuilder()
        var includedChunks = 0
        var includedChars = 0
        var omittedChunks = 0
        var truncated = false
        val lastIncludedIndexes = linkedMapOf<String, Int>()

        builder.appendLine("You may use the following uploaded Markdown context.")
        builder.appendLine()
        files.forEachIndexed { filePosition, file ->
            val fileHeader = "[File: ${file.safeFilename}]\n"
            if (includedChars + fileHeader.length > safeBudget) {
                truncated = true
                return@forEachIndexed
            }
            builder.append(fileHeader)
            includedChars += fileHeader.length
            val chunks = listChunks(userId, file.id).orEmpty()
            val rankedChunks = rankChunks(file, chunks, keywords, continuationMode, continuationState[file.id] ?: -1)
            var includedForFile = 0
            var skippedForFile = 0
            val fileLimit = if (files.size > 1 && filePosition < files.lastIndex) {
                (includedChars + (safeBudget / files.size).coerceAtLeast(1)).coerceAtMost(safeBudget)
            } else {
                safeBudget
            }
            for (chunk in rankedChunks) {
                val chunkText = chunk.promptText()
                if (includedChars + chunkText.length > safeBudget || includedChars + chunkText.length > fileLimit) {
                    skippedForFile += 1
                    truncated = true
                    continue
                }
                builder.append(chunkText)
                includedChunks += 1
                includedForFile += 1
                includedChars += chunkText.length
                lastIncludedIndexes[file.id] = maxOf(lastIncludedIndexes[file.id] ?: -1, chunk.chunkIndex)
            }
            val footer = "[/File]\n\n"
            omittedChunks += maxOf(skippedForFile, chunks.size - includedForFile)
            if (includedChars + footer.length <= safeBudget) {
                builder.append(footer)
                includedChars += footer.length
            } else {
                truncated = true
            }
        }
        val uniqueOmittedChunks = omittedChunks.coerceAtLeast(0)
        val message = when {
            continuationMode && includedChunks > 0 -> "Continuing with the next available part of the selected file context."
            continuationMode -> "All available selected file context has already been used. Ask about a specific section to revisit it."
            truncated -> "Using selected file context. Large files were shortened automatically."
            else -> null
        }

        return FileContextBuildResult(
            promptBlock = builder.toString().trimEnd(),
            fileIds = files.map { it.id },
            includedChunks = includedChunks,
            includedChars = includedChars,
            omittedChunks = uniqueOmittedChunks,
            truncated = truncated,
            continuationMode = continuationMode,
            message = message,
            lastIncludedChunkIndexes = lastIncludedIndexes,
        )
    }

    private fun rankChunks(
        file: UploadedFileRecord,
        chunks: List<FileChunkRecord>,
        keywords: Set<String>,
        continuationMode: Boolean,
        lastIncludedChunkIndex: Int,
    ): List<FileChunkRecord> {
        if (chunks.isEmpty()) return emptyList()
        val startIndex = if (continuationMode) lastIncludedChunkIndex + 1 else 0
        val available = if (continuationMode) chunks.filter { it.chunkIndex >= startIndex } else chunks
        val candidates = available.ifEmpty { chunks }
        val intro = chunks.firstOrNull()
        return candidates
            .sortedWith(
                compareByDescending<FileChunkRecord> { chunk ->
                    scoreChunk(file, chunk, keywords, continuationMode, startIndex, intro?.chunkIndex)
                }.thenBy { it.chunkIndex }
            )
    }

    private fun scoreChunk(
        file: UploadedFileRecord,
        chunk: FileChunkRecord,
        keywords: Set<String>,
        continuationMode: Boolean,
        startIndex: Int,
        introIndex: Int?,
    ): Int {
        var score = 0
        val headingWords = keywords(chunk.headingPath.orEmpty())
        val contentWords = keywords(chunk.content.take(2_000))
        val filenameWords = keywords(file.safeFilename)
        score += headingWords.count { it in keywords } * 12
        score += contentWords.count { it in keywords } * 3
        score += filenameWords.count { it in keywords } * 8
        if (continuationMode && chunk.chunkIndex >= startIndex) score += 40 - (chunk.chunkIndex - startIndex).coerceAtLeast(0)
        if (!continuationMode && chunk.chunkIndex == introIndex) score += 20
        return score
    }

    private fun FileChunkRecord.promptText(): String {
        return buildString {
            append("[Chunk ")
            append(chunkIndex + 1)
            if (!headingPath.isNullOrBlank()) {
                append(": ")
                append(headingPath)
            }
            appendLine("]")
            appendLine(content)
            appendLine("[/Chunk]")
        }
    }

    private fun keywords(text: String): Set<String> {
        return text.lowercase(Locale.US)
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .toSet()
    }

    private fun sanitizeFilename(filename: String): String? {
        if (filename.isBlank()) return null
        if (filename.contains('/') || filename.contains('\\') || filename.contains("..")) return null
        val sanitized = filename.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_', '-')
        if (sanitized.isBlank() || !sanitized.lowercase(Locale.US).endsWith(".md")) return null
        return sanitized.take(MAX_SAFE_FILENAME_CHARS)
    }

    private fun isAllowedMarkdown(filename: String, mimeType: String): Boolean {
        if (!filename.lowercase(Locale.US).endsWith(".md")) return false
        return mimeType == "text/markdown" || mimeType == "text/plain"
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

    private fun android.database.Cursor.toFileChunkRecord(): FileChunkRecord {
        return FileChunkRecord(
            id = getString(0),
            fileId = getString(1),
            chunkIndex = getInt(2),
            headingPath = if (isNull(3)) null else getString(3),
            content = getString(4),
            charCount = getInt(5),
            createdAtMs = getLong(6),
        )
    }

    private companion object {
        const val MAX_UPLOAD_BYTES = 2 * 1024 * 1024
        const val MAX_SAFE_FILENAME_CHARS = 120
        const val CONTEXT_BUDGET_CHARS = 14_000
        val STOP_WORDS = setOf(
            "the", "and", "for", "with", "this", "that", "from", "into", "about", "give",
            "much", "possible", "please", "file", "continue", "answer", "detail", "summary"
        )
    }
}

private object MarkdownChunker {
    private const val TARGET_CHUNK_CHARS = 3_000

    fun chunk(markdown: String): List<MarkdownChunk> {
        val sections = splitSections(markdown)
        val chunks = mutableListOf<MarkdownChunk>()
        sections.forEach { section ->
            splitLargeSection(section.content).forEach { part ->
                if (part.isNotBlank()) chunks += MarkdownChunk(section.headingPath, part.trim())
            }
        }
        return chunks.ifEmpty { listOf(MarkdownChunk(null, markdown.trim())) }
    }

    private fun splitSections(markdown: String): List<MarkdownSection> {
        val sections = mutableListOf<MarkdownSection>()
        val current = StringBuilder()
        val headingStack = mutableListOf<String>()
        var currentHeading: String? = null

        fun flush() {
            if (current.isNotBlank()) {
                sections += MarkdownSection(currentHeading, current.toString().trim())
                current.clear()
            }
        }

        markdown.lineSequence().forEach { line ->
            val match = Regex("^(#{1,3})\\s+(.+)$").find(line)
            if (match != null) {
                flush()
                val level = match.groupValues[1].length
                val heading = "${match.groupValues[1]} ${match.groupValues[2].trim()}"
                while (headingStack.size >= level) headingStack.removeAt(headingStack.lastIndex)
                headingStack += heading
                currentHeading = headingStack.joinToString(" > ")
            }
            current.appendLine(line)
        }
        flush()
        return sections.ifEmpty { listOf(MarkdownSection(null, markdown)) }
    }

    private fun splitLargeSection(content: String): List<String> {
        if (content.length <= TARGET_CHUNK_CHARS) return listOf(content)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < content.length) {
            val endLimit = (start + TARGET_CHUNK_CHARS).coerceAtMost(content.length)
            val splitAt = content.lastIndexOf("\n\n", endLimit).takeIf { it > start + 1000 } ?: endLimit
            chunks += content.substring(start, splitAt)
            start = splitAt
            while (start < content.length && content[start].isWhitespace()) start += 1
        }
        return chunks
    }
}

private data class MarkdownSection(
    val headingPath: String?,
    val content: String,
)

private data class MarkdownChunk(
    val headingPath: String?,
    val content: String,
)
