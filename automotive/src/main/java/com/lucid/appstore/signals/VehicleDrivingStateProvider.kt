package com.lucid.appstore.signals

import com.lucid.appstore.download.model.DrivingState
import com.lucid.appstore.download.monitor.DrivingStateProvider
import com.lucid.appstore.download.policy.DrivingStateMapper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Adapter from the vehicle signal library to [DrivingStateProvider]. Gear, speed and parking brake are mapped
 * through [DrivingStateMapper] (requirements section 5.1). The flow is cold: subscribe on collect and
 * unsubscribe in `awaitClose`. Until the library is connected it emits UNKNOWN, which blocks the gate.
 *
 * @param requireParkingBrake live config value; PARKED then also needs the brake engaged.
 */
class VehicleDrivingStateProvider(requireParkingBrake: Flow<Boolean>) : DrivingStateProvider {

    private val signals: Flow<RawDrivingSignals> = callbackFlow {
        // TODO(signal-lib): subscribe to gear selection, vehicle speed (m/s) and parking brake.
        // On every update: trySend(RawDrivingSignals(gearIsPark, speedMps, parkingBrakeOn)); null = unavailable.
        trySend(RawDrivingSignals(gearIsPark = null, speedMps = null, parkingBrakeOn = null))
        awaitClose { /* TODO(signal-lib): unsubscribe */ }
    }

    override val driving: Flow<DrivingState> = combine(signals, requireParkingBrake) { s, requireBrake ->
        DrivingStateMapper.map(s.gearIsPark, s.speedMps, s.parkingBrakeOn, requireBrake)
    }.distinctUntilChanged()

    data class RawDrivingSignals(val gearIsPark: Boolean?, val speedMps: Float?, val parkingBrakeOn: Boolean?)
}
