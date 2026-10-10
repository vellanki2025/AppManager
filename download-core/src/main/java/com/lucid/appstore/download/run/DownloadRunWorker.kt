package com.lucid.appstore.download.run

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.lucid.appstore.download.DownloadComponent

/** Thin WorkManager shell around [RunCoordinator] (FR-3 `download_run`). */
class DownloadRunWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val c = DownloadComponent.get()
        c.coordinator.execute(isRecheck = inputData.getBoolean(KEY_RECHECK, false)) { info -> setForeground(info) }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = DownloadComponent.get().notifications.foregroundInfo(null)

    companion object {
        const val KEY_RECHECK = "recheck"
    }
}
