package com.lucid.auto.appstore.domain

import android.util.Log
import com.lucid.auto.appstore.data.catalog.RemoteApp
import com.lucid.auto.appstore.data.download.ApkDownloader
import com.lucid.auto.appstore.data.download.DownloadResult
import com.lucid.auto.appstore.data.install.AppInstaller
import com.lucid.auto.appstore.data.install.InstallOutcome
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one place where download -> verify -> install -> re-read happens.
 *
 * Both the user-tap path and the auto-update path go through here, so a manual install and a
 * background update can never behave differently.
 */
class UpdateCoordinator(
    private val repository: AppRepository,
    private val downloader: ApkDownloader,
    private val installer: AppInstaller,
) {
    /** Serialises installs — the platform installs one session at a time anyway. */
    private val installLock = Mutex()

    val installMode get() = installer.installMode

    suspend fun installOrUpdate(remote: RemoteApp): InstallOutcome = installLock.withLock {
        val pkg = remote.packageName
        try {
            repository.setProgress(pkg, InstallProgress.Downloading(0))

            val download = downloader.download(remote.apkUrl, remote.sha256) { percent ->
                repository.setProgress(pkg, InstallProgress.Downloading(percent))
            }

            val apk = when (download) {
                is DownloadResult.Success -> download.file
                is DownloadResult.Failure -> {
                    // The row shows a bare "Failed"; only the caller learns why, and reports it
                    // above the list.
                    repository.setProgress(pkg, InstallProgress.Failed)
                    return@withLock InstallOutcome.Failure(-1, download.reason)
                }
            }

            repository.setProgress(pkg, InstallProgress.Installing)
            val outcome = installer.install(pkg, apk)

            // A failure leaves a bare "Failed" marker; anything else clears the row.
            repository.setProgress(
                pkg,
                if (outcome is InstallOutcome.Failure) InstallProgress.Failed else null,
            )

            // Whatever the platform said, the device is the source of truth (FR-5.6).
            repository.refreshLocal()
            outcome
        } catch (e: Exception) {
            Log.e(TAG, "installOrUpdate($pkg) failed", e)
            repository.setProgress(pkg, InstallProgress.Failed)
            InstallOutcome.Failure(-1, e.message ?: e::class.java.simpleName)
        }
    }

    /**
     * Applies every pending update.
     *
     * Only rows in [AppState.UPDATE] are touched. Apps in [AppState.INSTALL] are deliberately
     * skipped (FR-6.6): auto-update must never install software the owner did not ask for.
     *
     * @return which packages updated and which failed. Failures are returned rather than written
     *   into a row, so the list itself never has to carry an error.
     */
    suspend fun applyPendingUpdates(catalogByPackage: Map<String, RemoteApp>): UpdateSweep {
        val updated = mutableListOf<String>()
        val failed = mutableListOf<UpdateFailure>()
        val pending = repository.currentItems().filter { it.state == AppState.UPDATE }

        for (item in pending) {
            val remote = catalogByPackage[item.packageName] ?: continue
            when (val outcome = installOrUpdate(remote)) {
                InstallOutcome.Success -> updated += item.packageName
                is InstallOutcome.Failure -> failed += UpdateFailure(item.title, outcome.message)
                else -> Unit // cancelled or awaiting the user: nothing to report
            }
        }
        return UpdateSweep(updated, failed)
    }

    private companion object {
        const val TAG = "UpdateCoordinator"
    }
}

/** One app that failed to update, named for a user-facing report outside the list. */
data class UpdateFailure(val title: String, val message: String)

/** The outcome of one auto-update sweep. */
data class UpdateSweep(
    val updated: List<String> = emptyList(),
    val failed: List<UpdateFailure> = emptyList(),
)
