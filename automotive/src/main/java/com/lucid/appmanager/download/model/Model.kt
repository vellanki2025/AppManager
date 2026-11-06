package com.lucid.appmanager.download.model

import java.time.LocalTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What the vehicle is doing, from ignition position plus gear and speed.
 *
 * `UNKNOWN` is not a vehicle state: it is "the signal could not be read", which happens before the first
 * reading and whenever the signal library is unavailable. It blocks, so an unreadable signal never permits
 * an install.
 */
enum class DrivingState {
    /** Ignition on, gear in Park, stationary. */
    PARKED,

    /** Moving. */
    DRIVING,

    /** Ignition in accessory: the head unit is powered, the engine is not. */
    ACCESSORY,

    /** Ignition on, stationary, but in gear - a traffic light. Can move again without warning. */
    STOPPED,

    /** Ignition off. */
    TURNED_OFF,

    /** The signal could not be read. */
    UNKNOWN,
}

/** Ignition position reported by the vehicle. */
enum class Ignition { OFF, ACCESSORY, ON }
enum class NetworkTransport { NONE, WIFI, CELLULAR, ETHERNET, OTHER }
enum class NetworkClass { NONE, UNMETERED_WIFI, METERED_WIFI, CELLULAR, ETHERNET, OTHER }
enum class NetworkMode { WIFI_ONLY, WIFI_OR_CELLULAR }
enum class BlockReason { DRIVING, BATTERY, NETWORK, STORAGE, SYSTEM_STOP }

/** `INSTALLING` means the APK is downloaded and verified; a resumed run skips straight to install. */
enum class RunPhase { IDLE, RUNNING, WAITING, INSTALLING }

data class BatteryState(val levelPercent: Int, val charging: Boolean)
data class NetworkState(val transport: NetworkTransport, val metered: Boolean, val validated: Boolean)
data class DeviceState(
    val battery: BatteryState,
    val network: NetworkState,
    val driving: DrivingState,
    /** The vehicle has a connectivity subscription. Without it, cellular is never used whatever the setting says. */
    val cellularSubscribed: Boolean = false,
    /** Free bytes on the download volume. -1 means unknown, which never blocks. */
    val freeSpaceBytes: Long = -1L,
)
data class Progress(val downloaded: Long, val total: Long?)

/** User-adjustable policy and schedule settings. Persisted by [com.lucid.appmanager.download.config.ConfigRepository]. */
data class Config(
    val scheduleTime: LocalTime = LocalTime.of(2, 0),
    /** Tethered networks are always blocked. WIFI_OR_CELLULAR takes effect only with a connectivity subscription. */
    val networkMode: NetworkMode = NetworkMode.WIFI_ONLY,
    val batteryThresholdPercent: Int = 20,
    /** After a battery wait the level must reach threshold + margin before resuming. */
    val batteryResumeMarginPercent: Int = 5,
    /** The gate must pass for this long before a start or resume that follows a wait. */
    val resumeStabilityWindow: Duration = 10.seconds,
    /** Persisted safety-net recheck while waiting. */
    val recheckInterval: Duration = 15.minutes,
    /** Headroom required above the manifest size before a download starts (C3). */
    val storageMarginPercent: Int = 10,
)

sealed interface PolicyDecision {
    data class Allowed(val chargingOverride: Boolean) : PolicyDecision
    data class Blocked(val reason: BlockReason, val detail: String) : PolicyDecision
}

/** Persisted run state, see FR-24. `waitReason` doubles as the last notified reason. */
data class RunState(
    val runId: String = "",
    val phase: RunPhase = RunPhase.IDLE,
    /** Manifest this run is working on. Identity for "has a new release arrived?". */
    val manifestId: String? = null,
    /** The app inside that manifest whose APK [apkPath] holds. The three only make sense together. */
    val currentPackage: String? = null,
    /** Set once the APK is downloaded and verified. Its presence is what lets a resume skip to install. */
    val apkPath: String? = null,
    val waitReason: BlockReason? = null,
    /** True once the engine has been called at least once in this run (RESUMED vs DOWNLOAD_STARTED). */
    val hasStarted: Boolean = false,
    val progress: Progress? = null,
    val lastStateChangeAt: Long = 0L,
    val lastSuccessAt: Long? = null,
)
