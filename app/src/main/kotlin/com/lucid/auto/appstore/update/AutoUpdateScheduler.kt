package com.lucid.auto.appstore.update

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Owns the lifecycle of the periodic auto-update job.
 *
 * The contract from FR-6.4 is symmetric: enabling the flag enqueues the unique periodic work,
 * disabling it cancels that work by name. While the flag is off there is no background update
 * job registered with the system at all.
 */
interface AutoUpdateScheduler {
    fun enqueue()
    fun cancel()

    /** Convenience so callers can just forward the flag. */
    fun applyFlag(enabled: Boolean) = if (enabled) enqueue() else cancel()
}

class WorkManagerAutoUpdateScheduler(
    private val workManager: WorkManager,
) : AutoUpdateScheduler {

    override fun enqueue() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<AutoUpdateWorker>(
            REPEAT_INTERVAL_HOURS, TimeUnit.HOURS,
            FLEX_INTERVAL_HOURS, TimeUnit.HOURS,
        )
            .setConstraints(constraints)
            .addTag(WORK_TAG)
            .build()

        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    override fun cancel() {
        workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "lucid_appstore_auto_update"
        const val WORK_TAG = "auto-update"
        const val REPEAT_INTERVAL_HOURS = 6L
        const val FLEX_INTERVAL_HOURS = 1L
    }
}
