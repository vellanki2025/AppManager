package com.lucid.appstore.download.events

import android.util.Log
import com.lucid.appstore.download.model.BlockReason
import com.lucid.appstore.download.model.Config
import com.lucid.appstore.download.model.DeviceState
import com.lucid.appstore.download.model.Progress
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Event catalog (section 7). `level` is the `android.util.Log` priority. */
enum class EventType(val level: Int = Log.INFO) {
    SCHEDULE_FIRED, SCHEDULE_COALESCED,
    BLOCKED_DRIVING, BLOCKED_BATTERY, BLOCKED_NETWORK, CHARGING_OVERRIDE,
    DOWNLOAD_STARTED, DOWNLOAD_PROGRESS(Log.DEBUG),
    PAUSED_DRIVING, PAUSED_BATTERY, PAUSED_NETWORK, RESUMED,
    RECHECK_STILL_BLOCKED(Log.DEBUG), WORK_STOPPED_BY_SYSTEM(Log.WARN), STATE_RESTORED,
    CONFIG_CHANGED, DEVICE_STATE_CHANGED(Log.DEBUG),
    DOWNLOAD_COMPLETE, CANCELLED, SERVICE_DISABLED;

    /** Log-only events that would otherwise storm the debug Toast. */
    val quiet: Boolean get() = this == DOWNLOAD_PROGRESS || this == DEVICE_STATE_CHANGED

    companion object {
        fun blocked(reason: BlockReason) = when (reason) {
            BlockReason.DRIVING -> BLOCKED_DRIVING
            BlockReason.BATTERY -> BLOCKED_BATTERY
            BlockReason.NETWORK -> BLOCKED_NETWORK
            BlockReason.SYSTEM_STOP -> WORK_STOPPED_BY_SYSTEM
        }

        fun paused(reason: BlockReason) = when (reason) {
            BlockReason.DRIVING -> PAUSED_DRIVING
            BlockReason.BATTERY -> PAUSED_BATTERY
            BlockReason.NETWORK -> PAUSED_NETWORK
            BlockReason.SYSTEM_STOP -> WORK_STOPPED_BY_SYSTEM
        }
    }
}

data class ServiceEvent(
    val type: EventType,
    val runId: String = "-",
    val device: DeviceState? = null,
    val progress: Progress? = null,
    val config: Config? = null,
    val message: String = "",
)

fun interface EventSink { fun onEvent(event: ServiceEvent) }

/** Single fan-out point for every state change (FR-25). */
interface EventBus {
    /** Recent events for in-process observers such as the status screen. */
    val events: SharedFlow<ServiceEvent>
    fun emit(event: ServiceEvent)
}

class DefaultEventBus(private val sinks: List<EventSink>) : EventBus {
    private val flow = MutableSharedFlow<ServiceEvent>(replay = 50, extraBufferCapacity = 50, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<ServiceEvent> = flow

    override fun emit(event: ServiceEvent) {
        sinks.forEach { it.onEvent(event) }
        flow.tryEmit(event)
    }
}
