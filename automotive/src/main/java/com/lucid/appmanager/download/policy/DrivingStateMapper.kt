package com.lucid.appmanager.download.policy

import com.lucid.appmanager.download.model.DrivingState
import kotlin.math.abs

/**
 * Maps gear and speed to [DrivingState] (section 5.1). Shared by every
 * [com.lucid.appmanager.download.monitor.DrivingStateProvider] implementation so the rule lives in one place.
 */
object DrivingStateMapper {
    /** Speed below this magnitude (m/s) counts as stopped. */
    const val SPEED_NOISE_MPS = 0.1f

    fun map(gearIsPark: Boolean?, speedMps: Float?): DrivingState {
        if (gearIsPark == null || speedMps == null) return DrivingState.UNKNOWN
        return when {
            abs(speedMps) > SPEED_NOISE_MPS -> DrivingState.MOVING
            !gearIsPark -> DrivingState.IDLING
            else -> DrivingState.PARKED
        }
    }
}
