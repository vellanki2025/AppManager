package com.lucid.appmanager.download.monitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.lucid.appmanager.download.model.NetworkState
import com.lucid.appmanager.download.model.NetworkTransport
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Observes the default network through [ConnectivityManager] and maps OEM vehicle networks to cellular. */
class ConnectivityNetworkMonitor(context: Context) : NetworkMonitor {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    override val network: Flow<NetworkState> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { trySend(caps.toState()) }
            override fun onLost(network: Network) { trySend(NONE) }
        }
        trySend(cm.activeNetwork?.let(cm::getNetworkCapabilities)?.toState() ?: NONE)
        cm.registerDefaultNetworkCallback(callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    private companion object {
        val NONE = NetworkState(NetworkTransport.NONE, metered = false, validated = false)

        // System-API capabilities used by AAOS for the vehicle's own connectivity; not in the public SDK.
        const val NET_CAPABILITY_OEM_PAID = 22
        const val NET_CAPABILITY_OEM_PRIVATE = 26

        fun NetworkCapabilities.toState(): NetworkState {
            val transport = when {
                hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
                hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> NetworkTransport.BLUETOOTH
                hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.ETHERNET
                hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
                hasCapability(NET_CAPABILITY_OEM_PAID) || hasCapability(NET_CAPABILITY_OEM_PRIVATE) -> NetworkTransport.CELLULAR
                else -> NetworkTransport.OTHER
            }
            return NetworkState(
                transport = transport,
                metered = !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                validated = hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            )
        }
    }
}
