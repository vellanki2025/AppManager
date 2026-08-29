package com.lucid.auto.appstore.data.local

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Reads what is installed on the device.
 *
 * An interface so that the whole "local version" story is fakeable in unit tests without
 * Robolectric or a device.
 */
interface PackageInspector {

    /** @return the installed app, or null when the package is not installed. */
    fun getInstalled(packageName: String): InstalledApp?

    /** @return all requested packages that are actually installed, keyed by package name. */
    fun getInstalled(packageNames: List<String>): Map<String, InstalledApp> =
        packageNames.mapNotNull { pkg -> getInstalled(pkg)?.let { pkg to it } }.toMap()

    /** The launcher icon of an installed app, or null when unavailable. */
    fun getIcon(packageName: String): ImageBitmap?
}
