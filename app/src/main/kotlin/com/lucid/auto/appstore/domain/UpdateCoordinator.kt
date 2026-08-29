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
    /** This app's own package, so it can update itself without appearing in the list. */
    private val selfPackageName: String,
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

    /**
     * Updates the store itself, if the catalogue offers a newer build.
     *
     * The store is never a row in the list, so this cannot be reached through
     * [applyPendingUpdates] — it is a separate, deliberate step.
     *
     * ### The process dies partway through
     *
     * Committing an update to your own package kills your own process. This function therefore
     * **cannot observe its own success**: on the happy path it never returns. Nothing may be
     * sequenced after it, which is why callers must run it *last*.
     *
     * ### Safety
     *
     * - Only ever called from the background worker. Doing it from a tap would kill the app
     *   under the user's finger.
     * - Runs after every other pending update, so a self-kill cannot abandon them.
     * - A checksum is mandatory. Every other app can be repaired by the store; a corrupt store
     *   cannot repair itself, so an unverifiable build is refused rather than installed.
     * - Downgrades are refused explicitly, for a clearer failure than the platform's.
     */
    suspend fun applySelfUpdate(catalogByPackage: Map<String, RemoteApp>): SelfUpdate {
        val remote = catalogByPackage[selfPackageName] ?: return SelfUpdate.NotOffered
        val local = repository.installed(selfPackageName) ?: return SelfUpdate.NotOffered

        if (remote.versionCode <= local.versionCode) return SelfUpdate.AlreadyCurrent

        if (remote.sha256.isNullOrBlank()) {
            Log.w(TAG, "Refusing self-update to ${remote.versionName}: catalogue has no checksum")
            return SelfUpdate.Refused("the catalogue entry has no sha256 checksum")
        }

        Log.i(TAG, "Self-updating ${local.versionName} -> ${remote.versionName}. " +
            "This process is expected to be killed on commit.")

        return when (val outcome = installOrUpdate(remote)) {
            // Reaching here at all means the platform did not kill us, which in practice means
            // the update did not actually complete.
            is InstallOutcome.Failure -> SelfUpdate.Failed(outcome.message)
            else -> SelfUpdate.Committed
        }
    }

    private companion object {
        const val TAG = "UpdateCoordinator"
    }
}

/** Outcome of a self-update attempt. [Committed] usually never reaches the caller. */
sealed interface SelfUpdate {
    /** The catalogue does not list the store at all. */
    data object NotOffered : SelfUpdate
    data object AlreadyCurrent : SelfUpdate
    /** Rejected before download - e.g. no checksum to verify against. */
    data class Refused(val reason: String) : SelfUpdate
    data class Failed(val message: String) : SelfUpdate
    data object Committed : SelfUpdate
}

/** One app that failed to update, named for a user-facing report outside the list. */
data class UpdateFailure(val title: String, val message: String)

/** The outcome of one auto-update sweep. */
data class UpdateSweep(
    val updated: List<String> = emptyList(),
    val failed: List<UpdateFailure> = emptyList(),
)
