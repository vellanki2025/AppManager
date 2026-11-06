package com.lucid.appmanager.download.engine

import com.lucid.appmanager.catalogue.ApkUrlProvider
import com.lucid.appmanager.catalogue.SignedUrl
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger

/** Thrown when the per-run re-fetch bound is exhausted (FR-32). */
class UrlRefreshExhausted(val attempts: Int) : Exception("URL re-fetched $attempts times in one run")

/**
 * Owns the lifetime of the signed download URL for one run (FR-30 … FR-32).
 *
 * The TTL is short (30 min) relative to how long this service waits, so the first fetch happens at
 * download time and expiry mid-download is a routine event rather than a failure: the engine calls
 * [refresh] and resumes from its current offset.
 *
 * Not thread-safe beyond the attempt counter; one session belongs to one run.
 */
class UrlSession(
    private val provider: ApkUrlProvider,
    private val manifestId: String,
    private val clock: Clock = Clock.systemUTC(),
    /** Bound on transparent re-fetches within a single run (C2). */
    private val maxRefreshes: Int = DEFAULT_MAX_REFRESHES,
    private val onRefreshed: (SignedUrl) -> Unit = {},
) {
    private val refreshes = AtomicInteger(0)

    @Volatile
    private var current: SignedUrl? = null

    val refreshCount: Int get() = refreshes.get()

    /** First fetch. Call only once the policy gate has opened and the download is about to begin. */
    suspend fun open(): SignedUrl = provider.downloadUrl(manifestId).also { current = it }

    /** The URL in hand, fetching one if [open] has not run yet. */
    suspend fun current(): SignedUrl = current ?: open()

    /**
     * Fetches a fresh URL after an expiry or a 401/403.
     *
     * @throws UrlRefreshExhausted past [maxRefreshes], so an endlessly-rejecting server fails the run
     *   instead of looping.
     */
    suspend fun refresh(): SignedUrl {
        val attempt = refreshes.incrementAndGet()
        if (attempt > maxRefreshes) throw UrlRefreshExhausted(attempt - 1)
        return provider.downloadUrl(manifestId).also {
            current = it
            onRefreshed(it)
        }
    }

    /** True when the URL in hand is gone or about to be, so the engine can renew before a long read. */
    fun isExpiringWithin(millis: Long): Boolean {
        val url = current ?: return true
        return !clock.instant().plusMillis(millis).isBefore(url.expiresAt)
    }

    companion object {
        const val DEFAULT_MAX_REFRESHES = 3
    }
}
