package com.lucid.appstore.vehicle

import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import com.lucid.appstore.download.model.BatteryState
import com.lucid.appstore.download.monitor.BatteryMonitor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

/**
 * EV traction battery as the "battery" rule input (section 13.7). Level is `EV_BATTERY_LEVEL` over
 * `INFO_EV_BATTERY_CAPACITY`; charging means the charge port is connected and the charge rate is positive.
 * Missing signals report 0 % and not charging, which blocks the run (fail safe).
 */
class CarEvBatteryMonitor(context: Context) : BatteryMonitor {

    private val signals = carProperties(
        context,
        mapOf(
            VehiclePropertyIds.EV_BATTERY_LEVEL to CarPropertyManager.SENSOR_RATE_ONCHANGE,
            VehiclePropertyIds.INFO_EV_BATTERY_CAPACITY to CarPropertyManager.SENSOR_RATE_ONCHANGE,
            VehiclePropertyIds.EV_CHARGE_PORT_CONNECTED to CarPropertyManager.SENSOR_RATE_ONCHANGE,
            VehiclePropertyIds.EV_BATTERY_INSTANTANEOUS_CHARGE_RATE to CarPropertyManager.SENSOR_RATE_ONCHANGE,
        ),
    )

    override val battery: Flow<BatteryState> = signals.map { v ->
        val level = v[VehiclePropertyIds.EV_BATTERY_LEVEL] as? Float
        val capacity = v[VehiclePropertyIds.INFO_EV_BATTERY_CAPACITY] as? Float
        val percent = if (level != null && capacity != null && capacity > 0f) (level / capacity * 100f).roundToInt().coerceIn(0, 100) else 0
        val portConnected = v[VehiclePropertyIds.EV_CHARGE_PORT_CONNECTED] as? Boolean ?: false
        val rate = v[VehiclePropertyIds.EV_BATTERY_INSTANTANEOUS_CHARGE_RATE] as? Float
        BatteryState(levelPercent = percent, charging = portConnected && (rate == null || rate > 0f))
    }.distinctUntilChanged()
}
