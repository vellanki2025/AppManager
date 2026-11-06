package com.lucid.appmanager.download.engine

import android.util.Log
import com.lucid.appmanager.download.events.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Real HTTP download (FR-34 … FR-37).
 *
 * Streams to `<target>.part` and renames atomically on success, so a half file is never mistaken for a
 * finished one. Resumes with `Range` + `If-Range`; a server that ignores the range restarts the download
 * from zero rather than corrupting the file.
 *
 * A 401 or 403 is read as "the signed URL expired", not as a real authorization failure, because a 30
 * minute TTL against multi-hundred-megabyte downloads makes expiry the expected cause (FR-36). The engine
 * re-fetches the URL and resumes before the retry ladder sees the failure at all.
 *
 * Uses [HttpURLConnection] rather than adding an HTTP client dependency; the project keeps its dependency
 * set deliberately small and this needs only ranged GETs.
 */
class HttpDownloadEngine(
    private val request: DownloadRequest,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val initialBackoff: Duration = 30.seconds,
    private val maxBackoff: Duration = 15.minutes,
    private val connectTimeoutMs: Int = 30_000,
    private val readTimeoutMs: Int = 60_000,
    private val onEvent: (EngineEvent) -> Unit = {},
) : DownloadEngine {

    /** Engine-level notifications the coordinator turns into [com.lucid.appmanager.download.events.EventType]. */
    sealed interface EngineEvent {
        data object UrlRefreshed : EngineEvent
        data object RestartedFromZero : EngineEvent
        data class RetryScheduled(val attempt: Int, val delay: Duration, val reason: String) : EngineEvent
    }

    private val part = File(request.target.parentFile, request.target.name + PART_SUFFIX)

    /** Opaque resume validator from the first response; `If-Range` makes the server police it for us. */
    private var validator: String? = null

    override suspend fun download(onProgress: (Long, Long?) -> Unit): DownloadResult =
        withContext(ioDispatcher) {
            request.target.parentFile?.mkdirs()
            var attempt = 0
            var backoff = initialBackoff

            while (true) {
                coroutineContext.ensureActive()
                when (val outcome = attemptDownload(onProgress)) {
                    is Outcome.Done -> {
                        if (!renameIntoPlace()) return@withContext DownloadResult.Fatal("could not finalise ${request.target.name}")
                        return@withContext DownloadResult.Success
                    }

                    is Outcome.Fatal -> return@withContext DownloadResult.Fatal(outcome.reason)

                    is Outcome.Retry -> {
                        attempt++
                        if (attempt >= maxAttempts) return@withContext DownloadResult.Retryable("gave up after $attempt attempts: ${outcome.reason}")
                        onEvent(EngineEvent.RetryScheduled(attempt, backoff, outcome.reason))
                        Log.w(Logger.TAG, "download attempt $attempt failed (${outcome.reason}); retrying in $backoff")
                        delay(backoff.inWholeMilliseconds)
                        backoff = minOf(backoff * 2, maxBackoff)
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            DownloadResult.Fatal("unreachable")
        }

    override suspend fun discard() {
        withContext(ioDispatcher) {
            part.delete()
            request.target.delete()
        }
    }

    // ---------------------------------------------------------------- internals

    private sealed interface Outcome {
        data object Done : Outcome
        data class Retry(val reason: String) : Outcome
        data class Fatal(val reason: String) : Outcome
    }

    private suspend fun attemptDownload(onProgress: (Long, Long?) -> Unit): Outcome {
        var offset = if (part.exists()) part.length() else 0L

        // Renew proactively rather than discovering expiry mid-read.
        if (request.urls.isExpiringWithin(EXPIRY_MARGIN_MS)) {
            when (val r = renew()) {
                is Outcome.Fatal -> return r
                is Outcome.Retry -> return r
                else -> Unit
            }
        }

        val url = try {
            request.urls.current()
        } catch (e: UrlRefreshExhausted) {
            return Outcome.Fatal(e.message ?: "URL refresh exhausted")
        } catch (e: IOException) {
            return Outcome.Retry("URL fetch failed: ${e.message}")
        }

        var connection: HttpURLConnection? = null
        try {
            connection = open(url.url, offset)
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    // Range ignored (or none asked for): start over (FR-35).
                    if (offset > 0) {
                        onEvent(EngineEvent.RestartedFromZero)
                        part.delete()
                        offset = 0
                    }
                    validator = connection.validator()
                }

                HTTP_PARTIAL -> {
                    if (!connection.contentRangeMatches(offset)) {
                        onEvent(EngineEvent.RestartedFromZero)
                        part.delete()
                        offset = 0
                        connection.disconnect()
                        return Outcome.Retry("Content-Range did not match offset $offset")
                    }
                }

                HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> {
                    // Expected: the signed URL aged out mid-download (FR-31).
                    connection.disconnect()
                    return when (val r = renew()) {
                        is Outcome.Fatal -> r
                        else -> Outcome.Retry("url expired ($code), refreshed")
                    }
                }

                HttpURLConnection.HTTP_NOT_FOUND -> return Outcome.Fatal("app not found (404)")

                else -> {
                    val retryable = code >= 500 || code == HTTP_TOO_MANY_REQUESTS
                    return if (retryable) Outcome.Retry("HTTP $code") else Outcome.Fatal("HTTP $code")
                }
            }

            val total = connection.totalLength(offset) ?: request.app.sizeBytes.takeIf { it > 0 }
            connection.inputStream.use { stream -> copy(stream, offset, total, onProgress) }
            return Outcome.Done
        } catch (e: IOException) {
            return Outcome.Retry(e.message ?: e::class.java.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    private suspend fun renew(): Outcome = try {
        request.urls.refresh()
        onEvent(EngineEvent.UrlRefreshed)
        Outcome.Done
    } catch (e: UrlRefreshExhausted) {
        Outcome.Fatal(e.message ?: "URL refresh exhausted")
    } catch (e: IOException) {
        Outcome.Retry("URL refresh failed: ${e.message}")
    }

    private fun open(url: String, offset: Long): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            if (offset > 0) {
                setRequestProperty("Range", "bytes=$offset-")
                validator?.let { setRequestProperty("If-Range", it) }
            }
            connect()
        }

    private suspend fun copy(stream: InputStream, startOffset: Long, total: Long?, onProgress: (Long, Long?) -> Unit) {
        RandomAccessFile(part, "rw").use { file ->
            file.seek(startOffset)
            val buffer = ByteArray(BUFFER_BYTES)
            var written = startOffset
            onProgress(written, total)
            while (true) {
                coroutineContext.ensureActive()        // cancellation means pause; the .part file stays
                val read = stream.read(buffer)
                if (read == -1) break
                file.write(buffer, 0, read)
                written += read
                onProgress(written, total)
            }
        }
    }

    private fun renameIntoPlace(): Boolean {
        request.target.delete()
        return part.renameTo(request.target)
    }

    private fun HttpURLConnection.validator(): String? =
        getHeaderField("ETag") ?: getHeaderField("Last-Modified")

    private fun HttpURLConnection.contentRangeMatches(offset: Long): Boolean {
        val header = getHeaderField("Content-Range") ?: return false
        // "bytes <start>-<end>/<total>"
        val start = header.substringAfter("bytes ", "").substringBefore('-', "").trim()
        return start.toLongOrNull() == offset
    }

    private fun HttpURLConnection.totalLength(offset: Long): Long? {
        getHeaderField("Content-Range")?.let { header ->
            val size = header.substringAfterLast('/', "").trim()
            if (size != "*" && size.isNotEmpty()) return size.toLongOrNull()
        }
        val length = getHeaderFieldLong("Content-Length", -1L)
        return if (length >= 0) length + offset else null
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val DEFAULT_MAX_ATTEMPTS = 5
        const val HTTP_PARTIAL = 206
        const val HTTP_TOO_MANY_REQUESTS = 429

        /** Renew when less than this is left on the clock, rather than failing mid-read. */
        const val EXPIRY_MARGIN_MS = 60_000L

        fun min(a: Long, b: Long) = kotlin.math.min(a, b)
    }
}
