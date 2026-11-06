package com.lucid.appmanager.download.policy

import com.lucid.appmanager.download.model.DrivingState
import com.lucid.appmanager.download.model.Ignition
import kotlin.math.abs

/**
 * Maps ignition, gear and speed to a [DrivingState]. Shared by every
 * [com.lucid.appmanager.download.monitor.DrivingStateProvider] implementation so the rule lives in one place.
 *
 * Ignition is read first because it decides two states on its own: off is `TURNED_OFF` and accessory is
 * `ACCESSORY`, whatever gear and speed say. Only with the ignition on do gear and speed separate `DRIVING`
 * from `PARKED` and `STOPPED`.
 *
 * Any signal that cannot be read maps to `UNKNOWN`, which blocks. A missing reading must never be mistaken
 * for a stationary vehicle.
 */
object DrivingStateMapper {
    /** Speed below this magnitude (m/s) counts as stopped. */
    const val SPEED_NOISE_MPS = 0.1f

    fun map(ignition: Ignition?, gearIsPark: Boolean?, speedMps: Float?): DrivingState = when (ignition) {
        null -> DrivingState.UNKNOWN
        Ignition.OFF -> DrivingState.TURNED_OFF
        Ignition.ACCESSORY -> DrivingState.ACCESSORY
        Ignition.ON -> when {
            gearIsPark == null || speedMps == null -> DrivingState.UNKNOWN
            abs(speedMps) > SPEED_NOISE_MPS -> DrivingState.DRIVING
            gearIsPark -> DrivingState.PARKED
            else -> DrivingState.STOPPED          // stationary but in gear
        }
    }
}
