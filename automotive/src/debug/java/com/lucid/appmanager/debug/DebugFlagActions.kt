package com.lucid.appmanager.debug

import com.lucid.appmanager.catalogue.Artifact
import com.lucid.appmanager.catalogue.Digest
import com.lucid.appmanager.download.DownloadComponent
import com.lucid.appmanager.download.model.BatteryState
import com.lucid.appmanager.download.model.DrivingState
import com.lucid.appmanager.download.model.NetworkClass
import com.lucid.appmanager.download.model.NetworkMode
import com.lucid.appmanager.install.InstallMode
import com.lucid.appmanager.install.OverrideCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalTime

/**
 * Every use case this service implements, drivable from adb or from code. **Debug builds only** (FR-54).
 *
 * This object does not exist in release: it lives in `src/debug` and nothing in `src/main` names it
 * (FR-57). [DebugActionReceiver] is likewise declared only in the debug manifest and is not exported —
 * a privileged app that can install packages must not let another app drive its installer (FR-55).
 *
 * ### adb form
 *
 * ```
 * adb shell am broadcast -n com.lucid.appmanager/.debug.DebugActionReceiver \
 *     -a com.lucid.appmanager.debug.<ACTION> [--es|--ei|--ez <key> <value>]
 * ```
 *
 * Each function below documents the use case it exercises, its adb action and the requirement behind it.
 * The same table appears in the README.
 */
object DebugFlagActions {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val component get() = DownloadComponent.get()

    // ---------------------------------------------------------------- 8.1 flags

    /**
     * Kill switch. Off cancels all work, resets run state and makes the AIDL report disabled.
     *
     * Use case: prove the kill switch tears everything down. Action `SET_SERVICE_ENABLED --ez enabled`.
     */
    fun setServiceEnabled(enabled: Boolean) = scope.launch {
        component.flags.setDownloadServiceEnabled(enabled)
    }

    /**
     * Choose the install path (FR-52). Read at install time, so a flip mid-run lands on the next run.
     *
     * Use case: compare `PACKAGE_MANAGER` against `APK_OVERRIDE` on one device.
     * Action `SET_INSTALL_MODE --es mode APK_OVERRIDE`.
     */
    fun setInstallMode(mode: InstallMode) = scope.launch {
        component.flags.setInstallMode(mode)
    }

    /** Current flag values. Action `DUMP_FLAGS`. */
    fun dumpFlags(): String = runBlocking {
        val f = component.flags.flags.first()
        "downloadServiceEnabled=${f.downloadServiceEnabled} installMode=${f.installMode}"
    }

    // ---------------------------------------------------------------- 8.2 schedule

    /**
     * Run now instead of waiting for the configured time.
     *
     * Use case: every end-to-end case, without waiting until 02:00. Action `TRIGGER_NOW`.
     */
    fun triggerNow() {
        component.scheduler.enqueueRunIfAbsent()
    }

    /** Move the daily schedule. Use case: prove rescheduling re-arms. Action `SET_SCHEDULE_TIME --es at 02:00`. */
    fun setScheduleTime(at: LocalTime) = scope.launch {
        component.config.update { it.copy(scheduleTime = at) }
    }

    /** Cancel the run in flight and any staged APK (FR-22). Action `CANCEL_RUN`. */
    fun cancelRun() = scope.launch {
        component.coordinator.cancelCurrentRun()
    }

    // ---------------------------------------------------------------- 8.3 policy gate

    /**
     * Force the driving signal. `null` returns the real one.
     *
     * Use case: driving blocks a start, pauses a download mid-run, and blocks an install (FR-42).
     * Action `SET_DRIVING --es state DRIVING`.
     */
    fun setDrivingState(state: DrivingState?) = Decorators.driving.set(state)

    /**
     * Force battery level and charging.
     *
     * Use case: the threshold blocks, and the resume margin prevents flapping.
     * Action `SET_BATTERY --ei level 15 --ez charging false`.
     */
    fun setBattery(levelPercent: Int, charging: Boolean) =
        Decorators.battery.set(BatteryState(levelPercent, charging))

    /** Return battery to the real signal. Action `SET_BATTERY` with no extras. */
    fun clearBattery() = Decorators.battery.set(null)

    /**
     * Force the network class.
     *
     * Use case: tethering always blocked; cellular allowed only with a subscription; unvalidated blocked.
     * Action `SET_NETWORK --es class CELLULAR`.
     */
    fun setNetworkClass(cls: NetworkClass?) = Decorators.network.set(cls)

    /**
     * Force the connectivity subscription.
     *
     * Use case: cellular is refused without a subscription whatever the setting says.
     * Action `SET_CELLULAR_SUBSCRIBED --ez subscribed false`.
     */
    fun setCellularSubscribed(value: Boolean?) = Decorators.subscription.set(value)

    /**
     * The five network situations the gate can actually be in, as one control.
     *
     * The service keeps the observed [NetworkClass] and the owner's [NetworkMode] apart, because they are
     * different kinds of input: one is what the vehicle *has*, the other is what the owner *allows*. On a
     * bench that separation is only bookkeeping — three knobs with combinations that cannot occur — so
     * this sets the class, the subscription and the mode together to one coherent situation.
     *
     * Use case: step through every network outcome without reasoning about which knob does what.
     * Action `SET_NETWORK_TYPE --es type CELLULAR_ENABLED`.
     */
    fun setNetworkType(type: NetworkType) = scope.launch {
        // Config goes through DataStore and the decorators are in-memory, so the slow one goes first:
        // otherwise a TRIGGER_NOW landing in between sees the new class against the old mode and
        // reports a verdict for a situation that was never selected.
        component.config.update { it.copy(networkMode = type.mode) }
        Decorators.network.set(type.cls)
        Decorators.subscription.set(type.subscribed)
    }

    /** One coherent network situation: what the car has, plus what the owner has allowed. */
    enum class NetworkType(
        val cls: NetworkClass,
        val subscribed: Boolean,
        val mode: NetworkMode,
    ) {
        /** Validated unmetered Wi-Fi. The only class allowed in `WIFI_ONLY`. */
        UNMETERED_WIFI(NetworkClass.UNMETERED_WIFI, false, NetworkMode.WIFI_ONLY),

        /** A phone hotspot. Refused in both modes — the owner's phone allowance is not ours to spend. */
        METERED_WIFI(NetworkClass.METERED_WIFI, false, NetworkMode.WIFI_ONLY),

        /** The vehicle's own cellular, with the Settings option on and a subscription. Allowed. */
        CELLULAR_ENABLED(NetworkClass.CELLULAR, true, NetworkMode.WIFI_OR_CELLULAR),

        /** Cellular present and allowed in Settings, but the vehicle has no subscription. Refused. */
        CELLULAR_NO_SUBSCRIPTION(NetworkClass.CELLULAR, false, NetworkMode.WIFI_OR_CELLULAR),

        /** No usable link at all. */
        NONE(NetworkClass.NONE, false, NetworkMode.WIFI_ONLY),
    }

    /**
     * Force free space, in megabytes.
     *
     * Use case: the storage rule blocks when space is below artifact size plus margin (FR-38).
     * Action `SET_FREE_SPACE --ei mb 100`.
     */
    fun setFreeSpaceMb(mb: Int?) {
        DebugState.freeSpaceBytes = mb?.toLong()?.times(1024L * 1024L)
    }

    /** Drop every signal override and use the real vehicle signals. Action `CLEAR_SIGNAL_OVERRIDES`. */
    fun clearSignalOverrides() {
        Decorators.driving.set(null)
        Decorators.battery.set(null)
        Decorators.network.set(null)
        Decorators.subscription.set(null)
        DebugState.freeSpaceBytes = null
    }

    // ---------------------------------------------------------------- 8.4 cloud and URL

    /**
     * Inject the artifact the catalogue returns.
     *
     * Use case: drive a specific version, size and digest without a backend. Setting `versionCode` to what
     * is already installed proves the up-to-date short-circuit (FR-29).
     * Action `SET_ARTIFACT --es pkg com.x --ei code 42 [--es digest <hex>] [--el size <bytes>]`.
     */
    fun setArtifact(packageName: String, versionCode: Long, sizeBytes: Long = 0L, digestHex: String = "") {
        DebugState.artifact = Artifact(
            artifactId = "debug-$packageName-$versionCode",
            packageName = packageName,
            title = packageName.substringAfterLast('.'),
            versionName = "debug-$versionCode",
            versionCode = versionCode,
            sizeBytes = sizeBytes,
            digest = Digest.sha256(digestHex),
        )
    }

    /** Return to the real catalogue. Action `CLEAR_ARTIFACT`. */
    fun clearArtifact() { DebugState.artifact = null }

    /**
     * Make the next fetched URL already expired.
     *
     * Use case: the transparent mid-download refresh (FR-31) — the run must not fail or restart.
     * Action `EXPIRE_URL_NOW`.
     */
    fun expireUrlNow() { DebugState.expireUrlNow = true }

    /**
     * Fail the next `n` URL fetches.
     *
     * Use case: exhausting the per-run refresh bound fails the run instead of looping (FR-32). Pass more
     * than the bound (default 3) to see `URL_REFRESH_EXHAUSTED`.
     * Action `FAIL_URL_FETCH --ei times 4`.
     */
    fun failUrlFetch(times: Int) { DebugState.failUrlFetchTimes = times }

    /** Shorten the URL TTL so expiry happens during a download. Action `SET_URL_TTL --ei seconds 30`. */
    fun setUrlTtlSeconds(seconds: Int) { DebugState.urlTtlMillis = seconds * 1000L }

    // ---------------------------------------------------------------- 8.5 download

    /**
     * Pretend the server does not support `Range`.
     *
     * Use case: a rejected resume restarts from zero and still completes (FR-35).
     * Action `SET_RANGE_SUPPORT --ez supported false`.
     */
    fun setRangeSupport(supported: Boolean) { DebugState.rangeSupported = supported }

    /**
     * Force one HTTP status on the next attempt.
     *
     * Use case: 5xx and 429 retry; 404 fails immediately (FR-36).
     * Action `FORCE_HTTP_STATUS --ei code 503`.
     */
    fun forceHttpStatus(code: Int) { DebugState.forcedHttpStatus = code }

    /**
     * Fail the next `n` attempts transiently.
     *
     * Use case: walk the retry ladder — 30 s doubling to 15 min, 5 attempts (FR-36).
     * Action `FORCE_TRANSIENT_FAILURES --ei times 3`.
     */
    fun forceTransientFailures(times: Int) { DebugState.transientFailuresLeft = times }

    /**
     * Throttle the download so pause and resume are observable.
     *
     * Use case: driving or battery pausing mid-download, and resume picking up the offset.
     * Action `SET_SPEED --ei kbps 500`.
     */
    fun setSpeedKbps(kbps: Int?) { DebugState.speedKbps = kbps }

    // ---------------------------------------------------------------- 8.6 integrity

    /**
     * Append junk to the next completed download.
     *
     * Use case: digest mismatch is caught, the file is deleted and one retry from scratch runs (FR-39, FR-41).
     * Action `CORRUPT_NEXT_DOWNLOAD`.
     */
    fun corruptNextDownload() { DebugState.corruptNextDownload = true }

    /**
     * Chop bytes off the next completed download.
     *
     * Use case: the length check rejects it before anything hashes 600 MB.
     * Action `TRUNCATE_NEXT_DOWNLOAD --ei bytes 1024`.
     */
    fun truncateNextDownload(bytes: Int) { DebugState.truncateNextDownloadBy = bytes }

    /**
     * Use an APK already on the device instead of downloading one.
     *
     * Use case: signature mismatch (FR-40) with a differently-signed APK, and every install case without a
     * backend. Push one first: `adb push app.apk /data/local/tmp/app.apk`.
     * Action `USE_APK --es path /data/local/tmp/app.apk`.
     */
    fun useLocalApk(path: String?) { DebugState.localApkPath = path }

    // ---------------------------------------------------------------- 8.7 install, path A

    /**
     * Install a local APK immediately, skipping the catalogue and the download.
     *
     * Use case: the `PackageInstaller` path end to end, including the versionCode re-read (FR-44).
     * Combine with `SET_DRIVING DRIVING` to prove install is gated (FR-42).
     * Action `INSTALL_NOW --es path /data/local/tmp/app.apk`.
     */
    fun installNow(apk: File) = scope.launch {
        useLocalApk(apk.absolutePath)
        triggerNow()
    }

    /**
     * Force the next install to fail with a status code.
     *
     * Use case: `INSTALL_FAILED` is surfaced and the run ends cleanly rather than hanging.
     * Action `FORCE_INSTALL_FAILURE --ei code 4`.
     */
    fun forceInstallFailure(code: Int) { DebugState.forcedInstallFailureCode = code }

    // ---------------------------------------------------------------- 8.8 install, path B

    /**
     * Stage a local APK as a partition override without running the pipeline.
     *
     * Use case: `OVERRIDE_STAGED`, the record, and the adb sequence (FR-48, FR-49).
     * Action `STAGE_OVERRIDE --es path /data/local/tmp/app.apk`.
     */
    fun stageOverride(apk: File, packageName: String, versionCode: Long) = scope.launch {
        val artifact = Artifact(
            artifactId = "debug-stage",
            packageName = packageName,
            title = packageName.substringAfterLast('.'),
            versionName = "debug-$versionCode",
            versionCode = versionCode,
            sizeBytes = apk.length(),
            digest = Digest.sha256(""),
        )
        component.overrides.install(artifact, apk)
    }

    /**
     * The adb sequence that applies the staged override.
     *
     * The app never remounts a partition and never reboots the vehicle (A14, A15); this is the handoff.
     * Action `PRINT_OVERRIDE_COMMANDS`.
     */
    fun printOverrideCommands(): String = runBlocking {
        when (val check = component.overrides.checkApplied()) {
            is OverrideCheck.AwaitingReboot -> component.overrides.applyCommands(check.override)
            is OverrideCheck.NotApplied -> component.overrides.applyCommands(check.override)
            else -> "no override staged"
        }
    }

    /** The staged record as text. Action `DUMP_PENDING_OVERRIDE`. */
    fun dumpPendingOverride(): String = runBlocking {
        when (val check = component.overrides.checkApplied()) {
            OverrideCheck.NoneStaged -> "none staged"
            is OverrideCheck.Applied -> "applied: ${check.packageName} at ${check.versionCode}"
            is OverrideCheck.AwaitingReboot -> "awaiting reboot: ${check.override}"
            is OverrideCheck.NotApplied -> "rebooted but not applied: ${check.override}, installed=${check.installedVersionCode}"
        }
    }

    /**
     * Re-run the post-reboot check.
     *
     * Use case: `OVERRIDE_APPLIED` after a successful adb apply and reboot, `OVERRIDE_NOT_APPLIED` when the
     * partition was read-only (FR-50).
     * Action `CHECK_OVERRIDE_APPLIED`.
     */
    fun checkOverrideApplied(): String = dumpPendingOverride()

    /** Drop the staged APK and its record. Action `CLEAR_PENDING_OVERRIDE`. */
    fun clearPendingOverride() = scope.launch { component.overrides.clear() }

    // ---------------------------------------------------------------- 8.9 state

    /** Run state, config, flags and every debug override. Action `DUMP_STATE`. */
    fun dumpState(): String = runBlocking {
        buildString {
            appendLine("flags:     ${dumpFlags()}")
            appendLine("run:       ${component.runStateStore.load()}")
            appendLine("config:    ${component.config.config.first()}")
            appendLine("override:  ${dumpPendingOverride()}")
            appendLine("debug overrides:")
            append(DebugState.dump())
        }
    }

    /** Clear every override and cancel any run. Action `RESET_ALL`. */
    fun resetAll() = scope.launch {
        DebugState.reset()
        clearSignalOverrides()
        component.coordinator.cancelCurrentRun()
    }

    /** Resolves a mode name from an adb extra, falling back to the default. */
    internal fun parseMode(value: String?) = InstallMode.parse(value)
}
