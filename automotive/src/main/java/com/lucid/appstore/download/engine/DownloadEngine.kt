package com.lucid.appstore.download.engine

import kotlinx.coroutines.delay
import kotlin.math.min

/** Black-box download contract, frozen for this phase (section 4.3). */
interface DownloadEngine {
    /** Runs until done. Cancelling the calling coroutine means "pause". */
    suspend fun download(onProgress: (downloaded: Long, total: Long?) -> Unit): DownloadResult

    /** Called on user cancel. The engine decides what to clean up. */
    suspend fun discard()
}

sealed interface DownloadResult {
    data object Success : DownloadResult
}

/**
 * Simulates a download at a fixed speed. The simulated offset survives cancellation (pause) within the
 * process, so pause and resume are visible in the logs; it does not survive process death.
 */
class FakeDownloadEngine(
    private val totalBytes: Long = 600L * 1024 * 1024,
    totalDuration: kotlin.time.Duration = kotlin.time.Duration.parse("2m"),
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
    }
}
