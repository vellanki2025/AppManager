package com.lucid.appmanager.catalogue

import java.time.Instant
import kotlin.time.Duration.Companion.minutes

/**
 * Source of the apps offered for download (FR-28).
 *
 * Replace [StubCatalogueProvider] with the backend-backed one. The contract here is our proposal and has
 * not been agreed with a backend (requirements A20); [ArtifactResolver] and [UrlSession] are the blast
 * radius if the real one differs.
 */
interface CatalogueProvider {
    suspend fun availableArtifacts(): List<Artifact>
}

/**
 * Mints a short-lived download URL for an artifact (FR-30).
 *
 * Separate from [CatalogueProvider] on purpose: an artifact is cached across a long wait, a URL must not
 * be. Called immediately before the transfer starts and never earlier.
 */
interface ArtifactUrlProvider {
    suspend fun downloadUrl(artifactId: String): SignedUrl
}

class StubCatalogueProvider : CatalogueProvider {
    override suspend fun availableArtifacts(): List<Artifact> = listOf(
        Artifact(
            artifactId = "stub-navigation-2.4.0",
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
class StubArtifactUrlProvider(private val ttl: kotlin.time.Duration = 30.minutes) : ArtifactUrlProvider {
    override suspend fun downloadUrl(artifactId: String) = SignedUrl(
        url = "https://stub.invalid/artifacts/$artifactId",
        expiresAt = Instant.now().plusMillis(ttl.inWholeMilliseconds),
    )
}
