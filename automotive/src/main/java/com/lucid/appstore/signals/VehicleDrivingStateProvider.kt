package com.lucid.appstore.signals

import com.lucid.appstore.download.model.DrivingState
import com.lucid.appstore.download.monitor.DrivingStateProvider
import com.lucid.appstore.download.policy.DrivingStateMapper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Adapter from the vehicle signal library to [DrivingStateProvider]. Gear and speed are mapped through
 * [DrivingStateMapper] (requirements section 5.1). The flow is cold: subscribe on collect and unsubscribe in
 * `awaitClose`. Until the library is connected it emits UNKNOWN, which blocks the gate.
 */
class VehicleDrivingStateProvider : DrivingStateProvider {

    private val signals: Flow<RawDrivingSignals> = callbackFlow {
        // TODO(signal-lib): subscribe to gear selection and vehicle speed (m/s).
        // On every update: trySend(RawDrivingSignals(gearIsPark, speedMps)); null = unavailable.
        trySend(RawDrivingSignals(gearIsPark = null, speedMps = null))
        awaitClose { /* TODO(signal-lib): unsubscribe */ }
    }

    override val driving: Flow<DrivingState> =
        signals.map { DrivingStateMapper.map(it.gearIsPark, it.speedMps) }.distinctUntilChanged()

    data class RawDrivingSignals(val gearIsPark: Boolean?, val speedMps: Float?)
}
