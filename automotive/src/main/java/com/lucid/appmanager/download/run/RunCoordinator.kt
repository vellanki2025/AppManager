package com.lucid.appmanager.download.run

import androidx.work.ForegroundInfo
import com.lucid.appmanager.catalogue.Artifact
import com.lucid.appmanager.catalogue.ArtifactResolver
import com.lucid.appmanager.catalogue.ArtifactUrlProvider
import com.lucid.appmanager.catalogue.Resolution
import com.lucid.appmanager.download.config.ConfigRepository
import com.lucid.appmanager.download.config.FeatureFlags
import com.lucid.appmanager.download.engine.DownloadEngineFactory
import com.lucid.appmanager.download.engine.DownloadRequest
import com.lucid.appmanager.download.engine.DownloadResult
import com.lucid.appmanager.download.engine.HttpDownloadEngine
import com.lucid.appmanager.download.engine.UrlSession
import com.lucid.appmanager.download.events.EventBus
import com.lucid.appmanager.download.events.EventType
import com.lucid.appmanager.download.events.NotificationController
import com.lucid.appmanager.download.events.ServiceEvent
import com.lucid.appmanager.download.model.BlockReason
import com.lucid.appmanager.download.model.Config
import com.lucid.appmanager.download.model.DeviceState
import com.lucid.appmanager.download.model.PolicyDecision
import com.lucid.appmanager.download.model.Progress
import com.lucid.appmanager.download.model.RunPhase
import com.lucid.appmanager.download.model.RunState
import com.lucid.appmanager.download.monitor.DeviceStateMonitor
import com.lucid.appmanager.download.policy.PolicyEvaluator
import com.lucid.appmanager.download.scheduling.Scheduler
import com.lucid.appmanager.download.verify.IntegrityVerifier
import com.lucid.appmanager.download.verify.VerifyResult
import com.lucid.appmanager.install.InstallMode
import com.lucid.appmanager.install.InstallOutcome
import com.lucid.appmanager.install.Installer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Orchestrates one invocation of `download_run`.
 *
 * v3 shape — policy gate, stability window, engine job with mid-run enforcement, pause, completion and the
 * three cancel causes — is unchanged. v4 adds four stages around the engine, each delegated so this class
 * stays an orchestrator rather than growing the logic itself:
 *
 * ```
 * gate → ① resolve artifact → ② mint URL → ③ transfer → ④ verify → gate again → ⑤ install
 * ```
 *
 * A resumed run that already has a verified APK on disk skips ①–④ entirely. That is what stops a
 * driving-blocked install from re-downloading several hundred megabytes when the vehicle parks.
 */
class RunCoordinator(
    private val store: RunStateStore,
    private val config: ConfigRepository,
    private val flags: FeatureFlags,
    private val monitor: DeviceStateMonitor,
    private val policy: PolicyEvaluator,
    private val engineFactory: DownloadEngineFactory,
    private val scheduler: Scheduler,
    private val events: EventBus,
    private val notifications: NotificationController,
    private val clock: Clock,
    private val resolver: ArtifactResolver,
    private val urlProvider: ArtifactUrlProvider,
    private val verifier: IntegrityVerifier,
    private val installerFor: (InstallMode) -> Installer,
    private val downloadDir: File,
) {
    /** Distinguishes a user cancel from a system stop inside the cancellation handler. */
    private val userCancelled = AtomicBoolean(false)

    /** The engine of the run in flight, so a cancel can tell it to drop its partial file. */
    private val activeEngine = AtomicReference<com.lucid.appmanager.download.engine.DownloadEngine?>(null)

    /** Outcome of the engine stage, which can finish, pause on policy, or fail. */
    private sealed interface Transfer {
        data object Done : Transfer
        data class Paused(val blocked: PolicyDecision.Blocked) : Transfer
        data class Failed(val reason: String, val retryable: Boolean) : Transfer
    }

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
            // ---------------------------------------------------------------- gate (FR-6 .. FR-10)
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

            // ---------------------------------------------------------------- ① artifact (FR-28, FR-29)
            val resolution = resolver.resolve()
            val artifact: Artifact = when (resolution) {
                is Resolution.Update -> resolution.artifact
                is Resolution.UpToDate -> {
                    complete(runId, device, cfg, EventType.UP_TO_DATE, "${resolution.packageName} already at ${resolution.versionCode}")
                    return
                }
                is Resolution.NotOffered -> {
                    complete(runId, device, cfg, EventType.UP_TO_DATE, "catalogue offers nothing for ${resolution.packageName}")
                    return
                }
            }

            // A verified APK from an earlier attempt means ①–④ are already done (design §3).
            val readyApk = saved.apkPath?.let(::File)?.takeIf { it.exists() }
            var verified: File? = readyApk

            if (verified == null) {
                // -------------------------------------------------------- storage gate (FR-38)
                val storage = policy.evaluate(device, cfg, previousBlock, requiredBytes = artifact.sizeBytes)
                if (storage is PolicyDecision.Blocked) {
                    wait(runId, storage, device, progress.get(), cfg, saved, paused = false)
                    return
                }

                // -------------------------------------------------------- ② URL, as late as possible (FR-30)
                val urls = UrlSession(
                    provider = urlProvider,
                    artifactId = artifact.artifactId,
                    clock = Clock.systemUTC(),
                ) { emit(EventType.URL_REFRESHED, runId, device, progress.get(), cfg, "expires ${it.expiresAt}") }

                val first = try {
                    urls.open()
                } catch (e: Exception) {
                    fail(runId, device, progress.get(), cfg, EventType.DOWNLOAD_FAILED, "could not mint a download URL: ${e.message}")
                    return
                }
                emit(EventType.URL_FETCHED, runId, device, progress.get(), cfg, "expires ${first.expiresAt}")

                // -------------------------------------------------------- ③ + ④ transfer and verify (FR-34 .. FR-41)
                val target = File(downloadDir, "${artifact.packageName}.apk")
                var attempt = 0
                while (verified == null) {
                    store.update {
                        it.copy(runId = runId, phase = RunPhase.RUNNING, waitReason = null, hasStarted = true, artifactId = artifact.artifactId, apkPath = null)
                    }
                    if ((decision as? PolicyDecision.Allowed)?.chargingOverride == true && attempt == 0) {
                        emit(EventType.CHARGING_OVERRIDE, runId, device, progress.get(), cfg, "charging below threshold")
                    }
                    setForeground(notifications.foregroundInfo(progress.get()))
                    emit(if (saved.hasStarted || attempt > 0) EventType.RESUMED else EventType.DOWNLOAD_STARTED, runId, device, progress.get(), cfg)

                    val engine = engineFactory.create(DownloadRequest(artifact, urls, target))
                    activeEngine.set(engine)
                    val transfer = try {
                        runEngine(engine, runId, progress, cfg, device) { d, c -> device = d; cfg = c }
                    } finally {
                        activeEngine.set(null)
                    }
                    when (transfer) {
                        is Transfer.Paused -> {
                            wait(runId, transfer.blocked, device, progress.get(), cfg, saved, paused = true)
                            return
                        }
                        is Transfer.Failed -> {
                            fail(runId, device, progress.get(), cfg, EventType.DOWNLOAD_FAILED, transfer.reason)
                            return
                        }
                        Transfer.Done -> Unit
                    }

                    when (val result = verifier.verify(target, artifact)) {
                        VerifyResult.Ok -> verified = target
                        is VerifyResult.Failed -> {
                            target.delete()
                            attempt++
                            emit(EventType.VERIFY_FAILED, runId, device, progress.get(), cfg, "${result.reason}: ${result.detail}")
                            if (attempt >= MAX_VERIFY_ATTEMPTS) {                // FR-41: one retry, then stop
                                fail(runId, device, null, cfg, EventType.DOWNLOAD_FAILED, "verification failed twice")
                                return
                            }
                            progress.set(null)
                        }
                    }
                }
                store.update { it.copy(phase = RunPhase.INSTALLING, apkPath = verified.absolutePath, progress = null) }
            }

            // ---------------------------------------------------------------- install gate (FR-42)
            device = monitor.snapshot()
            val installGate = policy.evaluate(device, cfg, previousBlock)
            if (installGate is PolicyDecision.Blocked) {
                // The APK stays on disk; a resumed run comes straight back here.
                store.update { it.copy(phase = RunPhase.WAITING, waitReason = installGate.reason, apkPath = verified.absolutePath) }
                emit(EventType.blocked(installGate.reason), runId, device, null, cfg, "install waiting: ${installGate.detail}")
                scheduler.enqueueRecheck(cfg.recheckInterval)
                return
            }

            // ---------------------------------------------------------------- ⑤ install (FR-43 .. FR-51)
            val mode = flags.flags.first().installMode
            val installer = installerFor(mode)
            emit(EventType.INSTALL_STARTED, runId, device, null, cfg, "mode $mode")

            when (val outcome = installer.install(artifact, verified)) {
                InstallOutcome.Installed -> {
                    // FR-44: the platform's answer is advisory, the device is authoritative.
                    val now = resolver.installedVersionCode(artifact.packageName)
                    if (now != null && now >= artifact.versionCode) {
                        complete(runId, device, cfg, EventType.INSTALL_COMPLETE, "${artifact.packageName} at $now")
                    } else {
                        fail(runId, device, null, cfg, EventType.INSTALL_FAILED, "installer reported success but versionCode is $now")
                    }
                }

                InstallOutcome.Staged ->
                    complete(runId, device, cfg, EventType.OVERRIDE_STAGED, "staged ${artifact.packageName} v${artifact.versionCode}; apply with adb, takes effect next reboot")

                InstallOutcome.NeedsUserAction ->
                    fail(runId, device, null, cfg, EventType.INSTALL_NEEDS_USER_ACTION, "platform wants a confirmation dialog; not privileged?")

                is InstallOutcome.Failed ->
                    fail(runId, device, null, cfg, EventType.INSTALL_FAILED, outcome.message)
            }
        } catch (e: CancellationException) {
            // System stop or user cancel (FR-18, FR-22). The user path has already updated the state.
            withContext(NonCancellable) {
                if (!userCancelled.get()) {
                    store.update { it.copy(runId = runId, phase = RunPhase.WAITING, waitReason = BlockReason.SYSTEM_STOP, progress = progress.get()) }
                    emit(EventType.WORK_STOPPED_BY_SYSTEM, runId, device, progress.get(), cfg, "worker stopped by WorkManager")
                    scheduler.enqueueRecheck(kotlin.time.Duration.ZERO)
                }
            }
            throw e
        }
    }

    /**
     * Cancel from the notification or the UI. Stops only the current run; `daily_trigger` stays armed (FR-22).
     *
     * Also drops any verified-but-not-installed APK, so a cancel during an install wait does not leave
     * several hundred megabytes on the device with nothing intending to use it.
     */
    suspend fun cancelCurrentRun(event: EventType = EventType.CANCELLED) {
        userCancelled.set(true)
        scheduler.cancelRun()
        activeEngine.getAndSet(null)?.discard()
        val saved = store.load()
        saved.apkPath?.let { File(it).delete() }
        store.update { it.copy(phase = RunPhase.IDLE, waitReason = null, hasStarted = false, progress = null, artifactId = null, apkPath = null) }
        emit(event, saved.runId, null, null, null, "run cancelled")
    }

    /** v3's engine job plus mid-run enforcement, unchanged in behaviour and lifted out for readability. */
    private suspend fun runEngine(
        engine: com.lucid.appmanager.download.engine.DownloadEngine,
        runId: String,
        progress: AtomicReference<Progress?>,
        cfgIn: Config,
        deviceIn: DeviceState?,
        onState: (DeviceState, Config) -> Unit,
    ): Transfer {
        var cfg = cfgIn
        var device = deviceIn
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
                    device = d; cfg = c; onState(d, c)
                    emit(EventType.DEVICE_STATE_CHANGED, runId, d, progress.get(), c)
                    val dec = policy.evaluate(d, c)
                    if (dec is PolicyDecision.Blocked) { pause = dec; engineJob.cancel() }
                }
            }
            engineJob.join()
            watcher.cancel()
        }

        pause?.let { return Transfer.Paused(it) }
        return when (val r = result) {
            DownloadResult.Success -> Transfer.Done
            is DownloadResult.Retryable -> Transfer.Failed(r.reason, retryable = true)
            is DownloadResult.Fatal -> Transfer.Failed(r.reason, retryable = false)
            null -> Transfer.Failed("engine returned nothing", retryable = true)
        }
    }

    private suspend fun complete(runId: String, device: DeviceState?, cfg: Config, type: EventType, message: String) {
        store.update {
            it.copy(
                phase = RunPhase.IDLE, waitReason = null, hasStarted = false,
                progress = null, artifactId = null, apkPath = null, lastSuccessAt = clock.millis(),
            )
        }
        emit(type, runId, device, null, cfg, message)
        if (type != EventType.DOWNLOAD_COMPLETE) emit(EventType.DOWNLOAD_COMPLETE, runId, device, null, cfg, message)
        scheduler.ensureScheduled()
    }

    private suspend fun fail(runId: String, device: DeviceState?, progress: Progress?, cfg: Config, type: EventType, message: String) {
        store.update { it.copy(phase = RunPhase.IDLE, waitReason = null, hasStarted = false, progress = null, apkPath = null) }
        emit(type, runId, device, progress, cfg, message)
        scheduler.ensureScheduled()
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

    private companion object {
        /** One retry from scratch after a verification failure, then the run fails (FR-41). */
        const val MAX_VERIFY_ATTEMPTS = 2
    }
}
