package com.lucid.appmanager.download.scheduling

import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.lucid.appmanager.download.config.ConfigRepository
import com.lucid.appmanager.download.config.FeatureFlags
import com.lucid.appmanager.download.run.DownloadRunWorker
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * Two unique work names (FR-3): `daily_trigger` fires at the configured time and re-arms itself,
 * `download_run` is the gated run, also used for the delayed safety-net recheck.
 */
class Scheduler(
    private val workManager: WorkManager,
    private val config: ConfigRepository,
    private val flags: FeatureFlags,
    private val clock: Clock,
) {
    /** Idempotent. Called at app start, on time or time-zone change and after completion (FR-2). */
    suspend fun ensureScheduled() = armTrigger(ExistingWorkPolicy.KEEP)

    /** Replaces the pending trigger, for example after the schedule time changed. */
    suspend fun reschedule() = armTrigger(ExistingWorkPolicy.REPLACE)

    /** Called from inside the running trigger worker: appends tomorrow's trigger after itself. */
    suspend fun armNextTrigger() = armTrigger(ExistingWorkPolicy.APPEND_OR_REPLACE)

    /** Start a run now unless one is already pending or running. Used by the daily trigger and manual start. */
    fun enqueueRunIfAbsent() = enqueueRun(ExistingWorkPolicy.KEEP, Duration.ZERO, recheck = false)

    /** Start a run now, replacing any pending recheck. Used after the stability window. */
    fun enqueueRunNow() = enqueueRun(ExistingWorkPolicy.REPLACE, Duration.ZERO, recheck = false)

    /** Called from inside the running run worker: appends a recheck after itself. */
    fun enqueueRecheck(delay: Duration) = enqueueRun(ExistingWorkPolicy.APPEND_OR_REPLACE, delay, recheck = true)

    fun cancelRun() { workManager.cancelUniqueWork(DOWNLOAD_RUN) }

    fun cancelAll() { workManager.cancelUniqueWork(DAILY_TRIGGER); cancelRun() }

    private suspend fun armTrigger(policy: ExistingWorkPolicy) {
        if (!flags.flags.first().downloadServiceEnabled) { cancelAll(); return }
        val delay = delayToNext(config.config.first().scheduleTime)
        val request = OneTimeWorkRequestBuilder<ScheduleTriggerWorker>().setInitialDelay(delay).build()
        workManager.enqueueUniqueWork(DAILY_TRIGGER, policy, request)
    }

    private fun enqueueRun(policy: ExistingWorkPolicy, delay: Duration, recheck: Boolean) {
        val request = OneTimeWorkRequestBuilder<DownloadRunWorker>()
            .setInitialDelay(delay.toJavaDuration())
            .setInputData(Data.Builder().putBoolean(DownloadRunWorker.KEY_RECHECK, recheck).build())
            .build()
        workManager.enqueueUniqueWork(DOWNLOAD_RUN, policy, request)
    }

    internal fun delayToNext(time: LocalTime): java.time.Duration {
        val now = ZonedDateTime.now(clock)
        var next = now.with(time)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return java.time.Duration.between(now, next)
    }

    companion object {
        const val DAILY_TRIGGER = "daily_trigger"
        const val DOWNLOAD_RUN = "download_run"
    }
}
