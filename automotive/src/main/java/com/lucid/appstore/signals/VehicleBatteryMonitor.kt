package com.lucid.appstore.signals

import com.lucid.appstore.download.model.BatteryState
import com.lucid.appstore.download.monitor.BatteryMonitor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Adapter from the vehicle signal library to [BatteryMonitor]: EV traction battery state of charge in percent
 * and whether it is charging (requirements section 13.7). The flow is cold: subscribe on collect and
 * unsubscribe in `awaitClose`. Until the library is connected it emits 0 % not charging, which blocks the gate.
 */
class VehicleBatteryMonitor : BatteryMonitor {

    override val battery: Flow<BatteryState> = callbackFlow {
        // TODO(signal-lib): subscribe to state of charge and charge port / charge rate.
        // On every update: trySend(BatteryState(levelPercent, charging)).
        trySend(BatteryState(levelPercent = 0, charging = false))
        awaitClose { /* TODO(signal-lib): unsubscribe */ }
    }.distinctUntilChanged()
}
