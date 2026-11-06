package com.lucid.appmanager.download.engine

import com.lucid.appmanager.catalogue.Manifest
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.min

/**
 * Black-box download contract, frozen in v3 section 4.3 and unchanged by v4 (FR-34).
 *
 * The URL and its refresh are injected at construction via [DownloadRequest] rather than threaded
 * through [download], which is what keeps this contract still.
 */
/**
 * Suffix for a transfer still in flight. Public because the coordinator sweeps leftovers by name and the
 * two must agree: a .part the coordinator cannot recognise is a partial download it cannot clean up.
 */
const val PART_SUFFIX = ".part"

interface DownloadEngine {
    /** Runs until done. Cancelling the calling coroutine means "pause". */
    suspend fun download(onProgress: (downloaded: Long, total: Long?) -> Unit): DownloadResult

    /** Called on user cancel. The engine decides what to clean up. */
    suspend fun discard()
}

sealed interface DownloadResult {
    /** The file at [DownloadRequest.target] is complete. Integrity is verified separately. */
    data object Success : DownloadResult

    /** Transient. The run retries per the ladder, or waits. */
    data class Retryable(val reason: String) : DownloadResult

    /** Permanent for this run: 404, or the URL re-fetch bound exhausted. */
    data class Fatal(val reason: String) : DownloadResult
}

/** Everything one run's engine needs. Built fresh per run, which is why engines come from a factory. */
data class DownloadRequest(
    val manifest: Manifest,
    val urls: UrlSession,
    /** Final path. The engine streams to `<target>.part` and renames on success. */
    val target: File,
)

fun interface DownloadEngineFactory {
    fun create(request: DownloadRequest): DownloadEngine
}

/**
 * Simulates a download at a fixed speed. The simulated offset survives cancellation (pause) within the
 * process, so pause and resume are visible in the logs; it does not survive process death.
 *
 * Still the default in [com.lucid.appmanager.download.DownloadComponent.Dependencies] so the debug build and
 * the status screen work without a backend.
 */
class FakeDownloadEngine(
    private val totalBytes: Long = 600L * 1024 * 1024,
    totalDuration: kotlin.time.Duration = kotlin.time.Duration.parse("2m"),
    /** When set, a real file of [totalBytes] zeros is produced so verification and install can run. */
    private val target: File? = null,
) : DownloadEngine {
    private val tickMs = 250L
    private val bytesPerTick = totalBytes * tickMs / totalDuration.inWholeMilliseconds
    private var offset = 0L

    override suspend fun download(onProgress: (Long, Long?) -> Unit): DownloadResult {
        onProgress(offset, totalBytes)
        while (offset < totalBytes) {
            delay(tickMs)
            offset = min(totalBytes, offset + bytesPerTick)
            onProgress(offset, totalBytes)
        }
        offset = 0
        return DownloadResult.Success
    }

    override suspend fun discard() {
        offset = 0
        target?.delete()
    }
}
