package com.lucid.appstore.download.run

import androidx.work.ForegroundInfo
import com.lucid.appstore.download.config.ConfigRepository
import com.lucid.appstore.download.config.FeatureFlags
import com.lucid.appstore.download.engine.DownloadEngine
import com.lucid.appstore.download.engine.DownloadResult
import com.lucid.appstore.download.events.EventBus
import com.lucid.appstore.download.events.EventType
import com.lucid.appstore.download.events.NotificationController
import com.lucid.appstore.download.events.ServiceEvent
import com.lucid.appstore.download.model.BlockReason
import com.lucid.appstore.download.model.Config
import com.lucid.appstore.download.model.DeviceState
import com.lucid.appstore.download.model.PolicyDecision
import com.lucid.appstore.download.model.Progress
import com.lucid.appstore.download.model.RunPhase
import com.lucid.appstore.download.model.RunState
import com.lucid.appstore.download.monitor.DeviceStateMonitor
import com.lucid.appstore.download.policy.PolicyEvaluator
import com.lucid.appstore.download.scheduling.Scheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * Orchestrates one invocation of `download_run`: policy gate, stability window, engine job with mid-run
 * enforcement, pause, completion, and the three cancel causes (policy pause, user cancel, system stop).
 */
class RunCoordinator(
    private val store: RunStateStore,
    private val config: ConfigRepository,
    private val flags: FeatureFlags,
    private val monitor: DeviceStateMonitor,
    private val policy: PolicyEvaluator,
    private val engine: DownloadEngine,
    private val scheduler: Scheduler,
    private val events: EventBus,
    private val notifications: NotificationController,
    private val clock: Clock,
) {
    /** Distinguishes a user cancel from a system stop inside the cancellation handler. */
    private val userCancelled = AtomicBoolean(false)

    /**
     * @param isRecheck true for the persisted safety-net recheck; it must not start a brand-new run.
     * @param setForeground promotes the worker to a `dataSync` foreground service just before the engine starts.
     */
    suspend fun execute(isRecheck: Boolean, setForeground: suspend (ForegroundInfo) -> Unit) {
        userCancelled.set(false)
        val saved = store.load()
        if (!flags.flags.first().downloadServiceEnabled) return
        if (isRecheck && saved.phase == RunPhase.IDLE) return
        val runId = if (saved.phase == RunPhase.IDLE) UUID.randomUUID().toString().take(8) else saved.runId
        val previousBlock = saved.waitReason
        val progress = AtomicReference(saved.progress)
        var cfg = config.config.first()
        var device: DeviceState? = null
        if (saved.phase == RunPhase.RUNNING) emit(EventType.STATE_RESTORED, runId, null, progress.get(), cfg, "process restarted mid-run")

        try {
            // Gate (FR-6 .. FR-10)
            device = monitor.snapshot()
            var decision = policy.evaluate(device, cfg, previousBlock)
            if (decision is PolicyDecision.Allowed && previousBlock != null) {
                val violation = withTimeoutOrNull(cfg.resumeStabilityWindow) {
                    combine(monitor.state, config.config) { d, c -> d to c }
                        .first { (d, c) -> policy.evaluate(d, c, previousBlock) is PolicyDecision.Blocked }
                }
                if (violation != null) {
                    device = violation.first; cfg = violation.second
                    decision = policy.evaluate(device, cfg, previousBlock)
                }
            }
            if (decision is PolicyDecision.Blocked) {
                wait(runId, decision, device, progress.get(), cfg, saved, paused = false)
                return
            }

            // Run (FR-11 .. FR-13, FR-20)
            store.update { it.copy(runId = runId, phase = RunPhase.RUNNING, waitReason = null, hasStarted = true) }
            if ((decision as PolicyDecision.Allowed).chargingOverride) emit(EventType.CHARGING_OVERRIDE, runId, device, progress.get(), cfg, "charging below threshold")
            setForeground(notifications.foregroundInfo(progress.get()))
            emit(if (saved.hasStarted) EventType.RESUMED else EventType.DOWNLOAD_STARTED, runId, device, progress.get(), cfg)

            var pause: PolicyDecision.Blocked? = null
            var result: DownloadResult? = null
            coroutineScope {
                val engineJob = launch {
                    var lastEmit = 0L
                    result = engine.download { downloaded, total ->
                        progress.set(Progress(downloaded, total))
                        val now = clock.millis()
                        if (now - lastEmit >= 1000) { lastEmit = now; emit(EventType.DOWNLOAD_PROGRESS, runId, device, progress.get(), cfg) }
                    }
                }
                val watcher = launch {
                    combine(monitor.state, config.config) { d, c -> d to c }.collect { (d, c) ->
                        device = d; cfg = c
                        emit(EventType.DEVICE_STATE_CHANGED, runId, d, progress.get(), c)
                        val dec = policy.evaluate(d, c)
                        if (dec is PolicyDecision.Blocked) { pause = dec; engineJob.cancel() }
                    }
                }
                engineJob.join()
                watcher.cancel()
            }
            pause?.let { wait(runId, it, device, progress.get(), cfg, saved, paused = true); return }
            check(result is DownloadResult.Success) { "engine returned $result" }

            // Completion (FR-23)
            store.update { it.copy(phase = RunPhase.IDLE, waitReason = null, hasStarted = false, progress = null, lastSuccessAt = clock.millis()) }
            emit(EventType.DOWNLOAD_COMPLETE, runId, device, progress.get(), cfg)
            scheduler.ensureScheduled()
        } catch (e: CancellationException) {
            // System stop or user cancel (FR-18, FR-22). The user path has already updated the state.
            withContext(NonCancellable) {
                if (!userCancelled.get()) {
                    store.update { it.copy(runId = runId, phase = RunPhase.WAITING, waitReason = BlockReason.SYSTEM_STOP, progress = progress.get()) }
                    emit(EventType.WORK_STOPPED_BY_SYSTEM, runId, device, progress.get(), cfg, "worker stopped by WorkManager")
                    scheduler.enqueueRecheck(Duration.ZERO)
                }
            }
            throw e
        }
    }

    /** Cancel from the notification or the UI. Stops only the current run; `daily_trigger` stays armed (FR-22). */
    suspend fun cancelCurrentRun(event: EventType = EventType.CANCELLED) {
        userCancelled.set(true)
        scheduler.cancelRun()
        engine.discard()
        val runId = store.load().runId
        store.update { it.copy(phase = RunPhase.IDLE, waitReason = null, hasStarted = false, progress = null) }
        emit(event, runId, null, null, null, "run cancelled")
    }

    private suspend fun wait(runId: String, b: PolicyDecision.Blocked, device: DeviceState?, progress: Progress?, cfg: Config, saved: RunState, paused: Boolean) {
        store.update { it.copy(runId = runId, phase = RunPhase.WAITING, waitReason = b.reason, progress = progress ?: it.progress) }
        val type = when {
            paused -> EventType.paused(b.reason)
            saved.phase == RunPhase.WAITING && saved.waitReason == b.reason -> EventType.RECHECK_STILL_BLOCKED
            else -> EventType.blocked(b.reason)
        }
        emit(type, runId, device, progress, cfg, b.detail)
        scheduler.enqueueRecheck(cfg.recheckInterval)
    }

    private fun emit(type: EventType, runId: String, device: DeviceState?, progress: Progress?, cfg: Config?, message: String = "") =
        events.emit(ServiceEvent(type, runId, device, progress, cfg, message))
}
