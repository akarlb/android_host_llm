package com.example.androidhostllm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class DownloadProgress(
    val status: DownloadStatus,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val speedBytesPerSecond: Long = 0L,
    val message: String = "",
) {
    val percent: Int?
        get() = totalBytes?.takeIf { it > 0L }?.let { ((downloadedBytes * 100) / it).toInt().coerceIn(0, 100) }
}

enum class DownloadStatus {
    DOWNLOADING,
    CANCELLED,
    FAILED,
    COMPLETE,
}

class ModelDownloadManager {
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var activeJob: Job? = null

    @Volatile
    private var activeCall: Call? = null

    fun cancel() {
        activeCall?.cancel()
        activeJob?.cancel()
    }

    suspend fun download(
        preset: ModelPreset,
        targetFile: File,
        hfToken: String?,
        onProgress: (DownloadProgress) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        activeJob = coroutineContext[Job]
        runCatching {
            targetFile.parentFile?.mkdirs()
            val partFile = File(targetFile.absolutePath + ".part")
            val existingBytes = if (partFile.exists()) partFile.length() else 0L
            val firstResponse = executeDownloadRequest(preset.url, hfToken, existingBytes)
            if (firstResponse.code == 401 || firstResponse.code == 403) {
                val code = firstResponse.code
                firstResponse.close()
                throw IOException("This model may require a Hugging Face token or accepted terms. HTTP $code.")
            }
            if (!firstResponse.isSuccessful) {
                val code = firstResponse.code
                firstResponse.close()
                throw IOException("Download failed with HTTP $code")
            }

            val append = existingBytes > 0L && firstResponse.code == 206
            val response = if (existingBytes > 0L && !append) {
                firstResponse.close()
                partFile.delete()
                executeDownloadRequest(preset.url, hfToken, 0L).also {
                    if (it.code == 401 || it.code == 403) {
                        val code = it.code
                        it.close()
                        throw IOException("This model may require a Hugging Face token or accepted terms. HTTP $code.")
                    }
                    if (!it.isSuccessful) {
                        val code = it.code
                        it.close()
                        throw IOException("Download failed with HTTP $code")
                    }
                }
            } else {
                firstResponse
            }

            response.use { resp ->
                val body = resp.body ?: throw IOException("Download response had no body")
                val contentLength = body.contentLength().takeIf { it >= 0L }
                val startingBytes = if (append) existingBytes else 0L
                val total = contentLength?.let { it + startingBytes } ?: preset.expectedBytes
                var downloaded = startingBytes
                var lastBytes = downloaded
                var lastNanos = System.nanoTime()
                onProgress(DownloadProgress(DownloadStatus.DOWNLOADING, downloaded, total, message = if (append) "Resuming download" else "Downloading"))
                FileOutputStream(partFile, append).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    val input = body.byteStream()
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.nanoTime()
                        if (now - lastNanos >= 500_000_000L || downloaded == total) {
                            val elapsedSeconds = (now - lastNanos) / 1_000_000_000.0
                            val speed = if (elapsedSeconds > 0) ((downloaded - lastBytes) / elapsedSeconds).toLong() else 0L
                            onProgress(DownloadProgress(DownloadStatus.DOWNLOADING, downloaded, total, speed, "Downloading"))
                            lastBytes = downloaded
                            lastNanos = now
                        }
                    }
                    output.fd.sync()
                }
            }

            if (targetFile.exists() && !targetFile.delete()) {
                throw IOException("Could not replace existing model file: ${targetFile.absolutePath}")
            }
            if (!partFile.renameTo(targetFile)) {
                partFile.copyTo(targetFile, overwrite = true)
                if (!partFile.delete()) {
                    throw IOException("Downloaded model copied, but partial file could not be deleted: ${partFile.absolutePath}")
                }
            }
            onProgress(DownloadProgress(DownloadStatus.COMPLETE, targetFile.length(), targetFile.length(), message = "Download complete"))
        }.onFailure { error ->
            if (coroutineContext.isActive) {
                onProgress(DownloadProgress(DownloadStatus.FAILED, message = error.message ?: "Download failed"))
            } else {
                onProgress(DownloadProgress(DownloadStatus.CANCELLED, message = "Download cancelled"))
            }
        }.also {
            activeCall = null
            activeJob = null
        }
    }

    private fun executeDownloadRequest(url: String, hfToken: String?, resumeFromBytes: Long) = client.newCall(
        Request.Builder()
            .url(url)
            .apply {
                if (!hfToken.isNullOrBlank()) header("Authorization", "Bearer $hfToken")
                if (resumeFromBytes > 0L) header("Range", "bytes=$resumeFromBytes-")
            }
            .build()
    ).also { activeCall = it }.execute()
}
