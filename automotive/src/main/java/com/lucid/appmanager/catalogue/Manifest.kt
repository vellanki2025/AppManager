package com.lucid.appmanager.catalogue

import java.time.Instant

/**
 * One release: the set of apps the vehicle should be running (FR-28).
 *
 * The manifest is the unit that arrives and gets replaced. Only one is ever in flight, so when a new
 * `manifestId` appears, anything downloaded for the previous one is dropped rather than reused.
 *
 * It deliberately carries no download URLs. A URL has a 30 minute TTL (A13) and this service routinely
 * waits hours for the policy gate to open, so a URL held alongside the manifest would almost always be
 * dead by the time it was used. [ApkUrlProvider] fetches one per app, at download time.
 */
data class Manifest(
    /** Opaque server handle for this release. Identity for "is this the same manifest?". */
    val manifestId: String,
    /** Every app this release covers, whether or not the vehicle needs it. */
    val apps: List<ManifestApp>,
) {
    init {
        require(apps.distinctBy { it.packageName }.size == apps.size) {
            "a manifest must not list the same package twice"
        }
    }
}

/**
 * One app inside a [Manifest]: what to install, and what it should verify as.
 *
 * Each app is its own APK with its own size, digest and signed URL. An app whose [versionCode] is not
 * higher than the installed one is never downloaded — the comparison decides before any bytes move.
 */
data class ManifestApp(
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
