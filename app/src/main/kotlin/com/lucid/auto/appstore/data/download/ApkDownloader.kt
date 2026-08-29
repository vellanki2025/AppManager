package com.lucid.auto.appstore.data.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

sealed interface DownloadResult {
    data class Success(val file: File) : DownloadResult
    data class Failure(val reason: String, val cause: Throwable? = null) : DownloadResult
}

/**
 * Downloads an APK into app-private cache storage and verifies its integrity.
 *
 * Never writes to shared storage (NFR-5) and never leaves a partial file behind that could be
 * handed to the installer (NFR-4).
 */
interface ApkDownloader {
    suspend fun download(
        url: String,
        expectedSha256: String?,
        onProgress: (percent: Int) -> Unit = {},
    ): DownloadResult
}

class HttpApkDownloader(
    private val client: OkHttpClient,
    private val cacheDir: File,
) : ApkDownloader {

    override suspend fun download(
        url: String,
        expectedSha256: String?,
        onProgress: (Int) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        val target = File(cacheDir, fileNameFor(url))
        target.parentFile?.mkdirs()
        target.delete()

        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext DownloadResult.Failure("Download failed: HTTP ${response.code}")
                }
                val body = response.body
                    ?: return@withContext DownloadResult.Failure("Download response had no body")

                val total = body.contentLength()
                val digest = MessageDigest.getInstance("SHA-256")
                var written = 0L
                var lastReported = -1

                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            written += read
                            if (total > 0) {
                                val percent = ((written * 100) / total).toInt().coerceIn(0, 100)
                                if (percent != lastReported) {
                                    lastReported = percent
                                    onProgress(percent)
                                }
                            }
                        }
                        output.flush()
                    }
                }

                if (written == 0L) {
                    target.delete()
                    return@withContext DownloadResult.Failure("Downloaded file was empty")
                }

                if (expectedSha256 != null) {
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!actual.equals(expectedSha256, ignoreCase = true)) {
                        target.delete()
                        return@withContext DownloadResult.Failure(
                            "Integrity check failed: expected $expectedSha256 but got $actual",
                        )
                    }
                }

                onProgress(100)
                DownloadResult.Success(target)
            }
        } catch (e: IOException) {
            target.delete()
            DownloadResult.Failure("Download failed: ${e.message ?: "network error"}", e)
        } catch (e: SecurityException) {
            target.delete()
            DownloadResult.Failure("Download failed: ${e.message ?: "permission denied"}", e)
        }
    }

    private fun fileNameFor(url: String): String {
        val raw = url.substringAfterLast('/').substringBefore('?')
        val safe = raw.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
        return safe.ifBlank { "download-${url.hashCode().toUInt()}.apk" }
    }
}
