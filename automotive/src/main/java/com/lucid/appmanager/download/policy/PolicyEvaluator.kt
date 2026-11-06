package com.lucid.appmanager.download.policy

import com.lucid.appmanager.download.model.BlockReason
import com.lucid.appmanager.download.model.Config
import com.lucid.appmanager.download.model.DeviceState
import com.lucid.appmanager.download.model.DrivingState
import com.lucid.appmanager.download.model.NetworkClass
import com.lucid.appmanager.download.model.NetworkMode
import com.lucid.appmanager.download.model.PolicyDecision
import com.lucid.appmanager.download.model.PolicyDecision.Allowed
import com.lucid.appmanager.download.model.PolicyDecision.Blocked

interface PolicyEvaluator {
    /**
     * Rules run in order driving, battery, network, storage; the first failure wins (FR-6, FR-38).
     *
     * @param requiredBytes artifact size for the storage rule. 0 skips it, which is what the install
     *   gate does: by then the bytes are already on disk.
     * @param checkNetwork false for a gate that moves no bytes (FR-42a). Installing reads a verified APK
     *   off local storage, so a dropped link is no reason to refuse it — otherwise a car that finished
     *   downloading and then lost Wi-Fi could never install what it already has. Driving and battery
     *   still apply: those are about whether installing now is *safe*, which has not changed.
     */
    fun evaluate(
        state: DeviceState,
        config: Config,
        previousBlock: BlockReason? = null,
        requiredBytes: Long = 0L,
        checkNetwork: Boolean = true,
    ): PolicyDecision
}

/** @param logAllFailures debug builds log every failing rule, not only the first. */
class DefaultPolicyEvaluator(
    private val logAllFailures: Boolean = false,
    private val log: (String) -> Unit = {},
) : PolicyEvaluator {

    override fun evaluate(
        state: DeviceState,
        config: Config,
        previousBlock: BlockReason?,
        requiredBytes: Long,
        checkNetwork: Boolean,
    ): PolicyDecision {
        val failures = listOfNotNull(
            driving(state),
            battery(state, config, previousBlock),
            if (checkNetwork) network(state, config) else null,
            storage(state, config, requiredBytes),
        )
        if (logAllFailures && failures.size > 1) log("failing rules: " + failures.joinToString { "${it.reason} (${it.detail})" })
        return failures.firstOrNull()
            ?: Allowed(chargingOverride = state.battery.charging && state.battery.levelPercent < config.batteryThresholdPercent)
    }

    private fun driving(s: DeviceState): Blocked? =
        if (s.driving in PERMITTED_DRIVING) null
        else Blocked(BlockReason.DRIVING, "driving state ${s.driving}")

    private fun battery(s: DeviceState, c: Config, previousBlock: BlockReason?): Blocked? {
        if (s.battery.charging) return null
        val required = c.batteryThresholdPercent + if (previousBlock == BlockReason.BATTERY) c.batteryResumeMarginPercent else 0
        return if (s.battery.levelPercent >= required) null
        else Blocked(BlockReason.BATTERY, "battery ${s.battery.levelPercent}% below $required%")
    }

    /** Free space must cover the artifact plus margin. Unknown space never blocks (FR-38). */
    private fun storage(s: DeviceState, c: Config, requiredBytes: Long): Blocked? {
        if (requiredBytes <= 0L || s.freeSpaceBytes < 0L) return null
        val needed = requiredBytes + requiredBytes * c.storageMarginPercent / 100
        return if (s.freeSpaceBytes >= needed) null
        else Blocked(BlockReason.STORAGE, "free ${s.freeSpaceBytes / MB} MB below required ${needed / MB} MB")
    }

    private fun network(s: DeviceState, c: Config): Blocked? {
        val cls = NetworkClassifier.classify(s.network)
        val allowed = when (cls) {
            NetworkClass.UNMETERED_WIFI -> true
            NetworkClass.CELLULAR -> c.networkMode == NetworkMode.WIFI_OR_CELLULAR && s.cellularSubscribed
            NetworkClass.METERED_WIFI,
            NetworkClass.NONE, NetworkClass.ETHERNET, NetworkClass.OTHER -> false
        }
        return when {
            !allowed && NetworkClassifier.isTethered(cls) -> Blocked(BlockReason.NETWORK, "$cls: tethering not allowed")
            !allowed && cls == NetworkClass.CELLULAR && !s.cellularSubscribed -> Blocked(BlockReason.NETWORK, "cellular needs a connectivity subscription")
            !allowed -> Blocked(BlockReason.NETWORK, "$cls not allowed in ${c.networkMode}")
            !s.network.validated -> Blocked(BlockReason.NETWORK, "$cls has no validated internet")
            else -> null
        }
    }

    private companion object {
        const val MB = 1024L * 1024

        /**
         * Driving states that permit a download and an install.
         *
         * `DRIVING` blocks for the obvious reason. `UNKNOWN` blocks because it means the signal could not
         * be read, and a missing reading must never be treated as a stationary vehicle.
         *
         * `STOPPED` — stationary but in gear — is permitted by decision on 2026-11-05. It is the one entry
         * here that is not inherently safe: the vehicle can move again without warning. The mid-run watcher
         * covers a download, which pauses as soon as the state becomes `DRIVING`, but an install already
         * committed has no such check. Narrow this set to drop `STOPPED` if that turns out to matter.
         */
        val PERMITTED_DRIVING = setOf(
            DrivingState.PARKED,
            DrivingState.ACCESSORY,
            DrivingState.STOPPED,
            DrivingState.TURNED_OFF,
        )
    }
}
