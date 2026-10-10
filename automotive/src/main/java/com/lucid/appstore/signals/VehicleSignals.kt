package com.lucid.appstore.signals

import com.lucid.appstore.download.model.BatteryState
import com.lucid.appstore.download.model.DrivingState
import com.lucid.appstore.download.monitor.BatteryMonitor
import com.lucid.appstore.download.monitor.DrivingStateProvider
import com.lucid.appstore.download.policy.DrivingStateMapper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/*
 * Adapters between the vehicle signal library and the download service. Both flows are cold: subscribe to
 * the library on collect and unsubscribe in `awaitClose`, so callbacks are live only during a run or a wait.
 * Until the library is connected they emit fail-safe values (UNKNOWN, 0 % not charging), which block the gate.
 */

/** Gear, speed and parking brake mapped through [DrivingStateMapper] (requirements section 5.1). */
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

/** EV traction battery: state of charge in percent and whether it is charging (requirements section 13.7). */
class VehicleBatteryMonitor : BatteryMonitor {

    override val battery: Flow<BatteryState> = callbackFlow {
        // TODO(signal-lib): subscribe to state of charge and charge port / charge rate.
        // On every update: trySend(BatteryState(levelPercent, charging)).
        trySend(BatteryState(levelPercent = 0, charging = false))
        awaitClose { /* TODO(signal-lib): unsubscribe */ }
    }.distinctUntilChanged()
}
