package com.lucid.appstore.signals

import com.lucid.appstore.download.monitor.ConnectivitySubscriptionProvider
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Adapter from the vehicle connectivity service to [ConnectivitySubscriptionProvider]: true while the vehicle
 * has an active data subscription. Without it the cellular option is hidden in settings and ignored by the
 * policy. Until the source is connected it emits false, so downloads stay Wi-Fi only.
 */
class VehicleConnectivitySubscription : ConnectivitySubscriptionProvider {

    override val subscribed: Flow<Boolean> = callbackFlow {
        // TODO(signal-lib): subscribe to the connectivity subscription state; trySend(true/false) on change.
        trySend(false)
        awaitClose { /* TODO(signal-lib): unsubscribe */ }
    }.distinctUntilChanged()
}
