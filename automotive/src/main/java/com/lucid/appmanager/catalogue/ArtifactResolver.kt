package com.lucid.appmanager.catalogue

import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Outcome of asking the catalogue what, if anything, this run should transfer. */
sealed interface Resolution {
    /** Transfer and install this. */
    data class Update(val artifact: Artifact) : Resolution

    /** The installed version already satisfies the artifact (FR-29). No bytes are moved. */
    data class UpToDate(val packageName: String, val versionCode: Long) : Resolution

    /** The catalogue does not offer this package at all. */
    data class NotOffered(val packageName: String) : Resolution
}

/**
 * Decides whether a run has anything to do (FR-28, FR-29).
 *
 * Compares the artifact's `versionCode` against what [PackageManager] reports, never `versionName` —
 * `versionName` is a free-form string and comparing it as text sorts "1.10.0" below "1.9.0".
 */
class ArtifactResolver(
    private val catalogue: CatalogueProvider,
    private val packages: PackageManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** @param packageName null picks the first artifact the catalogue offers (this phase ships one app). */
    suspend fun resolve(packageName: String? = null): Resolution = withContext(ioDispatcher) {
        val artifacts = catalogue.availableArtifacts()
        val artifact = when (packageName) {
            null -> artifacts.firstOrNull()
            else -> artifacts.firstOrNull { it.packageName == packageName }
        } ?: return@withContext Resolution.NotOffered(packageName ?: "-")

        val installed = installedVersionCode(artifact.packageName)
        if (installed != null && installed >= artifact.versionCode) {
            Resolution.UpToDate(artifact.packageName, installed)
        } else {
            Resolution.Update(artifact)
        }
    }

    /** @return the installed `versionCode`, or null when the package is not installed. */
    fun installedVersionCode(packageName: String): Long? = try {
        PackageInfoCompat.getLongVersionCode(packages.getPackageInfo(packageName, 0))
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}
