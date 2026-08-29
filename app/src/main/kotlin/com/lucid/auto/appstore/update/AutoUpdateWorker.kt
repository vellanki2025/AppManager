package com.lucid.auto.appstore.update

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lucid.auto.appstore.di.ServiceLocator
import com.lucid.auto.appstore.domain.SelfUpdate

/**
 * The periodic half of auto-update.
 *
 * Re-checks the flag on every run: WorkManager persists jobs across reboots and upgrades, so a
 * job could in principle outlive the setting that created it. Checking here means "off" is
 * always honoured even if a stale job survives.
 */
class AutoUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val graph = ServiceLocator.from(applicationContext)

        if (!graph.settingsRepository.isAutoUpdateEnabledNow()) {
            Log.i(TAG, "Auto-update is disabled; skipping and cancelling the periodic job.")
            graph.autoUpdateScheduler.cancel()
            return Result.success()
        }

        val baseUrl = graph.settingsRepository.catalogBaseUrlNow()
        val catalog = graph.catalogRepository.refresh(baseUrl).getOrElse { error ->
            Log.w(TAG, "Catalog refresh failed; will retry on the next run.", error)
            return Result.retry()
        }

        graph.appRepository.refreshLocal()
        val byPackage = catalog.byPackage()
        val sweep = graph.updateCoordinator.applyPendingUpdates(byPackage)
        Log.i(TAG, "Auto-update finished. Updated: ${sweep.updated.ifEmpty { listOf("nothing") }}")
        sweep.failed.forEach { Log.w(TAG, "Auto-update failed for ${it.title}: ${it.message}") }

        // The store updates itself LAST, and only here in the background — never from a tap.
        // Committing an update to our own package kills this process, so nothing may follow.
        when (val self = graph.updateCoordinator.applySelfUpdate(byPackage)) {
            is SelfUpdate.Refused -> Log.w(TAG, "Self-update refused: ${self.reason}")
            is SelfUpdate.Failed -> Log.w(TAG, "Self-update failed: ${self.message}")
            else -> Unit
        }

        // A transient failure (server hiccup, storage) is worth one retry; the next run re-reads
        // local versions, so anything that actually succeeded will not be attempted again.
        return if (sweep.failed.isEmpty()) Result.success() else Result.retry()
    }

    private companion object {
        const val TAG = "AutoUpdateWorker"
    }
}
