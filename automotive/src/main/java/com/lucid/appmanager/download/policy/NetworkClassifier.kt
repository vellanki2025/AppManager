package com.lucid.appmanager.download.policy

import com.lucid.appmanager.download.model.NetworkClass
import com.lucid.appmanager.download.model.NetworkState
import com.lucid.appmanager.download.model.NetworkTransport

/** Pure mapping from transport and metered flag to a policy class (section 5.2). */
object NetworkClassifier {
    fun classify(state: NetworkState): NetworkClass = when (state.transport) {
        NetworkTransport.NONE -> NetworkClass.NONE
        NetworkTransport.WIFI -> if (state.metered) NetworkClass.METERED_WIFI else NetworkClass.UNMETERED_WIFI
        NetworkTransport.CELLULAR -> NetworkClass.CELLULAR
        NetworkTransport.BLUETOOTH -> NetworkClass.BLUETOOTH_TETHER
        NetworkTransport.ETHERNET -> NetworkClass.ETHERNET
        NetworkTransport.OTHER -> NetworkClass.OTHER
    }

    fun isTethered(cls: NetworkClass) = cls == NetworkClass.METERED_WIFI || cls == NetworkClass.BLUETOOTH_TETHER
}
