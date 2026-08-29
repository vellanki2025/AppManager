package com.lucid.auto.appstore.domain

/**
 * The fixed catalogue of apps this store manages.
 *
 * This list is the single source of truth for *which* packages the store cares about, and it
 * must stay in sync with the `<queries>` block in AndroidManifest.xml — without a matching
 * `<package>` entry there, [com.lucid.auto.appstore.data.local.PackageManagerInspector] cannot
 * see the app on API 30+ and it would incorrectly report as "not installed".
 */
data class ManagedApp(
    val packageName: String,
    val fallbackTitle: String,
)

object ManagedApps {
    val ALL: List<ManagedApp> = listOf(
        ManagedApp("com.lucid.auto.media", "Lucid Media"),
        ManagedApp("com.lucid.auto.radio", "Lucid Radio"),
        ManagedApp("com.spotify.music", "Spotify"),
    )

    val PACKAGE_NAMES: List<String> = ALL.map { it.packageName }

    fun titleFor(packageName: String): String =
        ALL.firstOrNull { it.packageName == packageName }?.fallbackTitle ?: packageName
}
