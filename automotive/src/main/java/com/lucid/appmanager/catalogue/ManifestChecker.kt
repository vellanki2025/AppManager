package com.lucid.appmanager.catalogue

import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The plan for one run: which apps in the current manifest need work, and which already do not.
 *
 * [upToDate] is reported once, here, so those apps never enter the loop and never cost a byte.
 */
data class ManifestCheck(
    val manifest: Manifest,
    /** Apps whose manifest `versionCode` beats what is installed, in manifest order. */
    val updates: List<ManifestApp>,
    /** Apps already at or past the manifest version. */
    val upToDate: List<ManifestApp>,
) {
    val hasWork: Boolean get() = updates.isNotEmpty()
}

/**
 * Decides what a run has to do (FR-28, FR-29).
 *
 * Runs **once per run**, not once per app: one catalogue call produces the whole plan, and the loop that
 * follows never asks again. Each app is compared on `versionCode` and never on `versionName` —
 * `versionName` is a free-form string and comparing it as text sorts "1.10.0" below "1.9.0".
 *
 * An app the vehicle does not have installed at all is still an update: the comparison treats a missing
 * package as version 0, so a manifest can add an app as well as move one forward.
 */
class ManifestChecker(
    private val catalogue: CatalogueProvider,
    private val packages: PackageManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * @param only restrict the plan to one package, for a manual `startDownload(packageName)`. Null
     *   plans every app in the manifest, which is what the nightly run does.
     * @return null when the catalogue offers no manifest at all.
     */
    suspend fun check(only: String? = null): ManifestCheck? = withContext(ioDispatcher) {
        val manifest = catalogue.currentManifest() ?: return@withContext null
        val considered = if (only == null) manifest.apps else manifest.apps.filter { it.packageName == only }

        val updates = mutableListOf<ManifestApp>()
        val upToDate = mutableListOf<ManifestApp>()
        for (app in considered) {
            val installed = installedVersionCode(app.packageName) ?: 0L
            if (app.versionCode > installed) updates += app else upToDate += app
        }
        ManifestCheck(manifest, updates, upToDate)
    }

    /** @return the installed `versionCode`, or null when the package is not installed. */
    fun installedVersionCode(packageName: String): Long? = try {
        PackageInfoCompat.getLongVersionCode(packages.getPackageInfo(packageName, 0))
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}
