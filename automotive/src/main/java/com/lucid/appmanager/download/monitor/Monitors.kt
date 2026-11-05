package com.lucid.appmanager.download.monitor

import com.lucid.appmanager.download.model.BatteryState
import com.lucid.appmanager.download.model.DeviceState
import com.lucid.appmanager.download.model.DrivingState
import com.lucid.appmanager.download.model.NetworkState
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

/** Whether the vehicle has a connectivity (cellular data) subscription. Gates the cellular option. */
interface ConnectivitySubscriptionProvider { val subscribed: Flow<Boolean> }

/** Free space on the volume the download writes to (FR-38). */
interface StorageMonitor { fun freeBytes(): Long }

/** Reads the usable space of the directory downloads land in. */
class FileStorageMonitor(private val dir: java.io.File) : StorageMonitor {
    override fun freeBytes(): Long = runCatching {
        dir.mkdirs()
        dir.usableSpace
    }.getOrDefault(-1L)
}

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
    subscription: ConnectivitySubscriptionProvider,
    private val storage: StorageMonitor,
) : DeviceStateMonitor {
    override val state: Flow<DeviceState> =
        combine(battery.battery, network.network, driving.driving, subscription.subscribed) { b, n, d, s ->
            DeviceState(b, n, d, s, storage.freeBytes())
        }
            .distinctUntilChanged()
            .conflate()

    override suspend fun snapshot(): DeviceState = state.first()
}
