package com.lucid.auto.appstore.data.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The catalogue document returned by `GET {baseUrl}/api/v1/apps`.
 *
 * Parsed with `ignoreUnknownKeys = true` so a newer server can add fields without breaking
 * already-shipped vehicles.
 */
@Serializable
data class AppCatalog(
    @SerialName("manifestVersion") val manifestVersion: Int = 1,
    @SerialName("generatedAt") val generatedAt: String? = null,
    @SerialName("apps") val apps: List<RemoteApp> = emptyList(),
) {
    fun byPackage(): Map<String, RemoteApp> = apps.associateBy { it.packageName }
}

/** One publishable app version as described by the server. */
@Serializable
data class RemoteApp(
    @SerialName("packageName") val packageName: String,
    @SerialName("title") val title: String,
    @SerialName("versionName") val versionName: String,
    /** The monotonic ordering key. This — not [versionName] — decides Update vs Installed. */
    @SerialName("versionCode") val versionCode: Long,
    @SerialName("apkUrl") val apkUrl: String,
    @SerialName("iconUrl") val iconUrl: String? = null,
    @SerialName("sizeBytes") val sizeBytes: Long = 0L,
    /** Optional lowercase hex SHA-256 of the APK. Verified before install when present. */
    @SerialName("sha256") val sha256: String? = null,
    @SerialName("releaseNotes") val releaseNotes: String? = null,
)
