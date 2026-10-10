package com.lucid.appstore.download.monitor

import com.lucid.appstore.download.model.BatteryState
import com.lucid.appstore.download.model.DeviceState
import com.lucid.appstore.download.model.DrivingState
import com.lucid.appstore.download.model.NetworkState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

/*
 * Signal sources. Each flow is cold: collecting it registers the underlying callbacks and cancelling the
 * collection unregisters them, so callbacks are live only while a run or a wait is active (NFR-2).
 * The vehicle implementations live in the `vehicle-signals` module and can be swapped for OEM libraries.
 */

/** Maps gear and speed to [DrivingState] (section 5.1). */
interface DrivingStateProvider { val driving: Flow<DrivingState> }

/** Battery level and charging. On an EV this is the traction battery state of charge. */
interface BatteryMonitor { val battery: Flow<BatteryState> }

/** Active network transport, metered and validated flags. */
interface NetworkMonitor { val network: Flow<NetworkState> }

interface DeviceStateMonitor {
    /** Combined, conflated. */
    val state: Flow<DeviceState>

    /** One-shot read for the policy gate. */
    suspend fun snapshot(): DeviceState
}

class DefaultDeviceStateMonitor(
    battery: BatteryMonitor,
    network: NetworkMonitor,
    driving: DrivingStateProvider,
) : DeviceStateMonitor {
    override val state: Flow<DeviceState> =
        combine(battery.battery, network.network, driving.driving) { b, n, d -> DeviceState(b, n, d) }
            .distinctUntilChanged()
            .conflate()

    override suspend fun snapshot(): DeviceState = state.first()
}
