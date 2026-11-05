package com.lucid.appmanager.download.scheduling

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lucid.appmanager.download.DownloadComponent
import com.lucid.appmanager.download.events.EventType
import com.lucid.appmanager.download.events.ServiceEvent
import com.lucid.appmanager.download.model.RunPhase
import kotlinx.coroutines.flow.first

/** Fires at the scheduled time, starts or coalesces a run, re-arms tomorrow (FR-3, FR-4). */
class ScheduleTriggerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val c = DownloadComponent.get()
        c.eventBus.emit(ServiceEvent(EventType.SCHEDULE_FIRED))
        if (c.flags.flags.first().downloadServiceEnabled) {
            val state = c.runStateStore.load()
            if (state.phase == RunPhase.IDLE) {
                c.scheduler.enqueueRunIfAbsent()
            } else {
                c.eventBus.emit(ServiceEvent(EventType.SCHEDULE_COALESCED, state.runId, message = "run already ${state.phase}"))
            }
        }
        c.scheduler.armNextTrigger()
        return Result.success()
    }
}
