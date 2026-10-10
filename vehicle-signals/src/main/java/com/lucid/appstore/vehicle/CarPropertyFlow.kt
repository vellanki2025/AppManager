package com.lucid.appstore.vehicle

import android.car.Car
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers

/**
 * Cold flow over a set of vehicle properties. Connects to the car service on collect and disconnects on
 * cancel. Emits the latest known value per property; a property that errored or is unavailable maps to null.
 *
 * Uses the callback API that exists on Android 12. `subscribePropertyEvents` and `CarPropertyValue.getPropertyStatus`
 * replace it on Android 15+ and can be adopted once minSdk moves.
 */
@Suppress("DEPRECATION")
internal fun carProperties(context: Context, rates: Map<Int, Float>): Flow<Map<Int, Any?>> = callbackFlow {
    val car = Car.createCar(context) ?: run { close(IllegalStateException("car service unavailable")); return@callbackFlow }
    val manager = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
    val values = HashMap<Int, Any?>()
    val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            synchronized(values) {
                values[value.propertyId] = if (value.status == CarPropertyValue.STATUS_AVAILABLE) value.value else null
                trySend(values.toMap())
            }
        }

        override fun onErrorEvent(propertyId: Int, zone: Int) {
            synchronized(values) { values[propertyId] = null; trySend(values.toMap()) }
        }
    }
    rates.forEach { (id, rate) -> manager.registerCallback(callback, id, rate) }
    awaitClose {
        manager.unregisterCallback(callback)
        car.disconnect()
    }
}.flowOn(Dispatchers.IO)
