package com.lucid.appmanager.download.run

import com.lucid.appmanager.download.config.ConfigRepository
import com.lucid.appmanager.download.events.EventBus
import com.lucid.appmanager.download.events.EventType
import com.lucid.appmanager.download.events.ServiceEvent
import com.lucid.appmanager.download.model.PolicyDecision
import com.lucid.appmanager.download.model.RunPhase
import com.lucid.appmanager.download.monitor.DeviceStateMonitor
import com.lucid.appmanager.download.policy.PolicyEvaluator
import com.lucid.appmanager.download.scheduling.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * In-process wake-up while the run is WAITING (FR-15). Observes device state and config, applies the
 * stability window, and enqueues `download_run` as soon as the gate has held. Restarting the window on
 * any change is what `collectLatest` does for free.
 */
class WaitingWatcher(
    private val store: RunStateStore,
    private val config: ConfigRepository,
    private val monitor: DeviceStateMonitor,
    private val policy: PolicyEvaluator,
    private val scheduler: Scheduler,
    private val events: EventBus,
) {
    fun start(scope: CoroutineScope): Job = scope.launch {
        store.state.map { it.phase == RunPhase.WAITING }.distinctUntilChanged().collectLatest { waiting ->
            if (waiting) watch()
        }
    }

    private suspend fun watch() {
        combine(monitor.state, config.config, store.state) { d, c, s -> Triple(d, c, s) }
            .map { (d, c, s) -> Snapshot(d, c, s.runId, s.waitReason, policy.evaluate(d, c, s.waitReason)) }
            .distinctUntilChangedBy { it.key }
            .collectLatest { snap ->
                events.emit(ServiceEvent(EventType.DEVICE_STATE_CHANGED, snap.runId, snap.device, config = snap.config))
                when (val d = snap.decision) {
                    is PolicyDecision.Allowed -> {
                        delay(snap.config.resumeStabilityWindow)
                        scheduler.enqueueRunNow()
                    }
                    is PolicyDecision.Blocked -> if (d.reason != snap.waitReason) {
                        // Reason changed: update the waiting notification once (FR-17).
                        store.update { it.copy(waitReason = d.reason) }
                        events.emit(ServiceEvent(EventType.blocked(d.reason), snap.runId, snap.device, config = snap.config, message = d.detail))
                    }
                }
            }
    }

    private data class Snapshot(
        val device: com.lucid.appmanager.download.model.DeviceState,
        val config: com.lucid.appmanager.download.model.Config,
        val runId: String,
        val waitReason: com.lucid.appmanager.download.model.BlockReason?,
        val decision: PolicyDecision,
    ) {
        val key: Any get() = (decision as? PolicyDecision.Blocked)?.reason ?: "ALLOWED"
    }
}
