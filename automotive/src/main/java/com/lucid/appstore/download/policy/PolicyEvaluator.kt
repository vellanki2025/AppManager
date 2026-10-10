package com.lucid.appstore.download.policy

import com.lucid.appstore.download.model.BlockReason
import com.lucid.appstore.download.model.Config
import com.lucid.appstore.download.model.DeviceState
import com.lucid.appstore.download.model.DrivingState
import com.lucid.appstore.download.model.NetworkClass
import com.lucid.appstore.download.model.NetworkMode
import com.lucid.appstore.download.model.PolicyDecision
import com.lucid.appstore.download.model.PolicyDecision.Allowed
import com.lucid.appstore.download.model.PolicyDecision.Blocked

interface PolicyEvaluator {
    /** Rules run in order driving, battery, network; the first failure wins (FR-6). */
    fun evaluate(state: DeviceState, config: Config, previousBlock: BlockReason? = null): PolicyDecision
}

/** @param logAllFailures debug builds log every failing rule, not only the first. */
class DefaultPolicyEvaluator(
    private val logAllFailures: Boolean = false,
    private val log: (String) -> Unit = {},
) : PolicyEvaluator {

    override fun evaluate(state: DeviceState, config: Config, previousBlock: BlockReason?): PolicyDecision {
        val failures = listOfNotNull(driving(state), battery(state, config, previousBlock), network(state, config))
        if (logAllFailures && failures.size > 1) log("failing rules: " + failures.joinToString { "${it.reason} (${it.detail})" })
        return failures.firstOrNull()
            ?: Allowed(chargingOverride = state.battery.charging && state.battery.levelPercent < config.batteryThresholdPercent)
    }

    private fun driving(s: DeviceState): Blocked? =
        if (s.driving == DrivingState.PARKED) null else Blocked(BlockReason.DRIVING, "driving state ${s.driving}")

    private fun battery(s: DeviceState, c: Config, previousBlock: BlockReason?): Blocked? {
        if (s.battery.charging) return null
        val required = c.batteryThresholdPercent + if (previousBlock == BlockReason.BATTERY) c.batteryResumeMarginPercent else 0
        return if (s.battery.levelPercent >= required) null
        else Blocked(BlockReason.BATTERY, "battery ${s.battery.levelPercent}% below $required%")
    }

    private fun network(s: DeviceState, c: Config): Blocked? {
        val cls = NetworkClassifier.classify(s.network)
        val allowed = when (cls) {
            NetworkClass.UNMETERED_WIFI -> true
            NetworkClass.CELLULAR -> c.networkMode == NetworkMode.WIFI_OR_CELLULAR
            NetworkClass.METERED_WIFI, NetworkClass.BLUETOOTH_TETHER,
            NetworkClass.NONE, NetworkClass.ETHERNET, NetworkClass.OTHER -> false
        }
        return when {
            !allowed && NetworkClassifier.isTethered(cls) -> Blocked(BlockReason.NETWORK, "$cls: tethering not allowed")
            !allowed -> Blocked(BlockReason.NETWORK, "$cls not allowed in ${c.networkMode}")
            !s.network.validated -> Blocked(BlockReason.NETWORK, "$cls has no validated internet")
            else -> null
        }
    }
}
