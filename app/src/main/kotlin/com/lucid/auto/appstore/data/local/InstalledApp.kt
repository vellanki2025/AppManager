package com.lucid.auto.appstore.data.local

/**
 * A snapshot of one app as it currently exists on the device.
 *
 * @param versionName human-readable, display only — never used for ordering.
 * @param versionCode monotonic Long from the platform; the only value compared against the server.
 */
data class InstalledApp(
    val packageName: String,
    val label: String?,
    val versionName: String,
    val versionCode: Long,
    val isSystemApp: Boolean = false,
)
