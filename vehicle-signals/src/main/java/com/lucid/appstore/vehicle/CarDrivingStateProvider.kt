package com.lucid.appstore.vehicle

import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import com.lucid.appstore.download.model.DrivingState
import com.lucid.appstore.download.monitor.DrivingStateProvider
import com.lucid.appstore.download.policy.DrivingStateMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * PARKED = gear in Park and speed zero, optionally with the parking brake engaged (section 5.1).
 * Reads `GEAR_SELECTION`, `PERF_VEHICLE_SPEED` and `PARKING_BRAKE_ON` through [CarPropertyManager].
 *
 * @param requireParkingBrake live config value; see `Config.requireParkingBrake`.
 */
class CarDrivingStateProvider(context: Context, requireParkingBrake: Flow<Boolean>) : DrivingStateProvider {

    private val signals = carProperties(
        context,
        mapOf(
            VehiclePropertyIds.GEAR_SELECTION to CarPropertyManager.SENSOR_RATE_ONCHANGE,
            VehiclePropertyIds.PERF_VEHICLE_SPEED to CarPropertyManager.SENSOR_RATE_UI,
            VehiclePropertyIds.PARKING_BRAKE_ON to CarPropertyManager.SENSOR_RATE_ONCHANGE,
        ),
    )

    override val driving: Flow<DrivingState> = combine(signals, requireParkingBrake) { v, requireBrake ->
        DrivingStateMapper.map(
            gearIsPark = (v[VehiclePropertyIds.GEAR_SELECTION] as? Int)?.let { it == VehicleGear.GEAR_PARK },
            speedMps = v[VehiclePropertyIds.PERF_VEHICLE_SPEED] as? Float,
            parkingBrakeOn = v[VehiclePropertyIds.PARKING_BRAKE_ON] as? Boolean,
            requireParkingBrake = requireBrake,
        )
    }.distinctUntilChanged()
}
