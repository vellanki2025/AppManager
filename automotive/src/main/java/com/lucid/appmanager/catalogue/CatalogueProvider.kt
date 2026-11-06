package com.lucid.appmanager.catalogue

import java.time.Instant
import kotlin.time.Duration.Companion.minutes

/**
 * Source of the apps offered for download (FR-28).
 *
 * Replace [StubCatalogueProvider] with the backend-backed one. The contract here is our proposal and has
 * not been agreed with a backend (requirements A20); [ManifestChecker] and [UrlSession] are the blast
 * radius if the real one differs.
 */
interface CatalogueProvider {
    suspend fun availableManifests(): List<Manifest>
}

/**
 * Fetches a short-lived download URL for an manifest (FR-30).
 *
 * Separate from [CatalogueProvider] on purpose: an manifest is cached across a long wait, a URL must not
 * be. Called immediately before the download starts and never earlier.
 */
interface ApkUrlProvider {
    suspend fun downloadUrl(manifestId: String): SignedUrl
}

class StubCatalogueProvider : CatalogueProvider {
    override suspend fun availableManifests(): List<Manifest> = listOf(
        Manifest(
            manifestId = "stub-navigation-2.4.0",
            packageName = "com.lucid.navigation",
            title = "Lucid Navigation",
            versionName = "2.4.0",
            versionCode = 2_004_000,
            sizeBytes = 600L * 1024 * 1024,
            digest = Digest.sha256(""),
        ),
    )
}

/** Returns a URL that never resolves. Replaced by the backend client. */
class StubApkUrlProvider(private val ttl: kotlin.time.Duration = 30.minutes) : ApkUrlProvider {
    override suspend fun downloadUrl(manifestId: String) = SignedUrl(
        url = "https://stub.invalid/manifests/$manifestId",
        expiresAt = Instant.now().plusMillis(ttl.inWholeMilliseconds),
    )
}
