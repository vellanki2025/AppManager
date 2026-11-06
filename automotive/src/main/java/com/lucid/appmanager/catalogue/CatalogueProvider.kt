package com.lucid.appmanager.catalogue

import java.time.Instant
import kotlin.time.Duration.Companion.minutes

/**
 * Source of the current release (FR-28).
 *
 * Returns one [Manifest], not a list: only one release is in flight at a time, and a different
 * `manifestId` means the previous one is superseded rather than queued alongside.
 *
 * Replace [StubCatalogueProvider] with the backend-backed one. The contract here is our proposal and has
 * not been agreed with a backend (requirements A20); [ManifestChecker] and [UrlSession] are the blast
 * radius if the real one differs.
 */
interface CatalogueProvider {
    suspend fun currentManifest(): Manifest?
}

/**
 * Fetches a short-lived download URL for one app in a manifest (FR-30).
 *
 * Separate from [CatalogueProvider] on purpose: a manifest is cached across a long wait, a URL must not
 * be. Called immediately before that app's download starts and never earlier — fetching every app's URL
 * up front would start all their 30 minute clocks at once, and the last app's would expire while the
 * first was still downloading.
 */
interface ApkUrlProvider {
    suspend fun downloadUrl(manifestId: String, packageName: String): SignedUrl
}

class StubCatalogueProvider : CatalogueProvider {
    override suspend fun currentManifest(): Manifest = Manifest(
        manifestId = "stub-2026.11",
        apps = listOf(
            ManifestApp(
                packageName = "com.lucid.navigation",
                title = "Lucid Navigation",
                versionName = "2.4.0",
                versionCode = 2_004_000,
                sizeBytes = 600L * 1024 * 1024,
                digest = Digest.sha256(""),
            ),
        ),
    )
}

/** Returns a URL that never resolves. Replaced by the backend client. */
class StubApkUrlProvider(private val ttl: kotlin.time.Duration = 30.minutes) : ApkUrlProvider {
    override suspend fun downloadUrl(manifestId: String, packageName: String) = SignedUrl(
        url = "https://stub.invalid/manifests/$manifestId/apps/$packageName",
        expiresAt = Instant.now().plusMillis(ttl.inWholeMilliseconds),
    )
}
