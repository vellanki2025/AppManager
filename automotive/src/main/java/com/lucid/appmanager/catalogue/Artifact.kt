package com.lucid.appmanager.catalogue

import java.time.Instant

/**
 * What to install (FR-28).
 *
 * Deliberately carries no download URL. The URL has a 30 minute TTL (A13) and this service routinely
 * waits hours for the policy gate to open, so a URL held alongside the artifact would almost always be
 * dead by the time it was used. [ArtifactUrlProvider] mints one at transfer time instead.
 */
data class Artifact(
    /** Opaque server handle used to mint a download URL. */
    val artifactId: String,
    val packageName: String,
    val title: String,
    val versionName: String,
    /** The ordering key. `versionName` is a display string and is never compared. */
    val versionCode: Long,
    val sizeBytes: Long,
    val digest: Digest,
)

/** Integrity digest of the APK bytes, as published by the server. */
data class Digest(val algorithm: String, val value: String) {
    init {
        require(algorithm.isNotBlank()) { "digest algorithm must not be blank" }
    }

    fun matches(other: String): Boolean = value.equals(other, ignoreCase = true)

    companion object {
        const val SHA_256 = "SHA-256"
        fun sha256(hex: String) = Digest(SHA_256, hex)
    }
}

/** A short-lived download URL (FR-30). */
data class SignedUrl(val url: String, val expiresAt: Instant) {
    fun isExpired(now: Instant): Boolean = !now.isBefore(expiresAt)
}
