package com.lucid.appstore.download.policy

import com.lucid.appstore.download.model.DrivingState
import kotlin.math.abs

/**
 * Maps raw vehicle signals to [DrivingState] (section 5.1). Shared by every
 * [com.lucid.appstore.download.monitor.DrivingStateProvider] implementation so the rule lives in one place.
 */
object DrivingStateMapper {
    /** Speed below this magnitude (m/s) counts as stopped. */
    const val SPEED_NOISE_MPS = 0.1f

    fun map(gearIsPark: Boolean?, speedMps: Float?, parkingBrakeOn: Boolean?, requireParkingBrake: Boolean): DrivingState {
        if (gearIsPark == null || speedMps == null) return DrivingState.UNKNOWN
        if (requireParkingBrake && parkingBrakeOn == null) return DrivingState.UNKNOWN
        return when {
            abs(speedMps) > SPEED_NOISE_MPS -> DrivingState.MOVING
            !gearIsPark -> DrivingState.IDLING
            requireParkingBrake && parkingBrakeOn != true -> DrivingState.IDLING // stopped in Park but not secured
            else -> DrivingState.PARKED
        }
    }
}
