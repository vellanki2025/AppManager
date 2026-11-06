package com.lucid.appmanager.signals

import com.lucid.appmanager.download.model.DrivingState
import com.lucid.appmanager.download.model.Ignition
import com.lucid.appmanager.download.monitor.DrivingStateProvider
import com.lucid.appmanager.download.policy.DrivingStateMapper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Adapter from the vehicle signal library to [DrivingStateProvider]. Ignition, gear and speed are mapped
 * through [DrivingStateMapper]. The flow is cold: subscribe on collect and unsubscribe in `awaitClose`.
 * Until the library is connected it emits UNKNOWN, which blocks the gate.
 *
 * Ignition is the signal that distinguishes `TURNED_OFF` and `ACCESSORY`, neither of which can be derived
 * from gear and speed — a parked car with the ignition off reports gear Park and zero speed exactly like a
 * parked car with the ignition on.
 */
class VehicleDrivingStateProvider : DrivingStateProvider {

    private val signals: Flow<RawDrivingSignals> = callbackFlow {
        // TODO(signal-lib): subscribe to ignition position, gear selection and vehicle speed (m/s).
        // On every update: trySend(RawDrivingSignals(ignition, gearIsPark, speedMps)); null = unavailable.
        trySend(RawDrivingSignals(ignition = null, gearIsPark = null, speedMps = null))
        awaitClose { /* TODO(signal-lib): unsubscribe */ }
    }

    override val driving: Flow<DrivingState> =
        signals.map { DrivingStateMapper.map(it.ignition, it.gearIsPark, it.speedMps) }.distinctUntilChanged()

    data class RawDrivingSignals(
        val ignition: Ignition?,
        val gearIsPark: Boolean?,
        val speedMps: Float?,
    )
}
