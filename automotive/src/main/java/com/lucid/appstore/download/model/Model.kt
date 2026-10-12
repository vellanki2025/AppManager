package com.lucid.appstore.download.model

import java.time.LocalTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

enum class DrivingState { PARKED, IDLING, MOVING, UNKNOWN }
enum class NetworkTransport { NONE, WIFI, CELLULAR, BLUETOOTH, ETHERNET, OTHER }
enum class NetworkClass { NONE, UNMETERED_WIFI, METERED_WIFI, CELLULAR, BLUETOOTH_TETHER, ETHERNET, OTHER }
enum class NetworkMode { WIFI_ONLY, WIFI_OR_CELLULAR }
enum class BlockReason { DRIVING, BATTERY, NETWORK, SYSTEM_STOP }
enum class RunPhase { IDLE, RUNNING, WAITING }

data class BatteryState(val levelPercent: Int, val charging: Boolean)
data class NetworkState(val transport: NetworkTransport, val metered: Boolean, val validated: Boolean)
data class DeviceState(
    val battery: BatteryState,
    val network: NetworkState,
    val driving: DrivingState,
    /** The vehicle has a connectivity subscription. Without it, cellular is never used whatever the setting says. */
    val cellularSubscribed: Boolean = false,
)
data class Progress(val downloaded: Long, val total: Long?)

/** User-adjustable policy and schedule settings. Persisted by [com.lucid.appstore.download.config.ConfigRepository]. */
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
)

sealed interface PolicyDecision {
    data class Allowed(val chargingOverride: Boolean) : PolicyDecision
    data class Blocked(val reason: BlockReason, val detail: String) : PolicyDecision
}

/** Persisted run state, see FR-24. `waitReason` doubles as the last notified reason. */
data class RunState(
    val runId: String = "",
    val phase: RunPhase = RunPhase.IDLE,
    val waitReason: BlockReason? = null,
    /** True once the engine has been called at least once in this run (RESUMED vs DOWNLOAD_STARTED). */
    val hasStarted: Boolean = false,
    val progress: Progress? = null,
    val lastStateChangeAt: Long = 0L,
    val lastSuccessAt: Long? = null,
)
