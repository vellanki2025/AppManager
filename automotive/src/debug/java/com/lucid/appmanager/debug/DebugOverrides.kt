package com.lucid.appmanager.debug

import com.lucid.appmanager.catalogue.Artifact
import com.lucid.appmanager.catalogue.ArtifactUrlProvider
import com.lucid.appmanager.catalogue.CatalogueProvider
import com.lucid.appmanager.catalogue.SignedUrl
import com.lucid.appmanager.download.model.BatteryState
import com.lucid.appmanager.download.model.DrivingState
import com.lucid.appmanager.download.model.NetworkClass
import com.lucid.appmanager.download.model.NetworkState
import com.lucid.appmanager.download.model.NetworkTransport
import com.lucid.appmanager.download.monitor.BatteryMonitor
import com.lucid.appmanager.download.monitor.ConnectivitySubscriptionProvider
import com.lucid.appmanager.download.monitor.DrivingStateProvider
import com.lucid.appmanager.download.monitor.NetworkMonitor
import com.lucid.appmanager.download.monitor.StorageMonitor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.Instant

/**
 * Mutable state the debug decorators read. Debug builds only.
 *
 * Every field is null or false by default, so an untouched debug build behaves exactly like release. The
 * decorators below sit in front of the real sources rather than replacing them, so clearing an override
 * returns the real signal without restarting anything.
 */
object DebugState {
    // signals
    @Volatile var driving: DrivingState? = null
    @Volatile var battery: BatteryState? = null
    @Volatile var networkClass: NetworkClass? = null
    @Volatile var cellularSubscribed: Boolean? = null
    @Volatile var freeSpaceBytes: Long? = null

    // cloud
    @Volatile var artifact: Artifact? = null
    @Volatile var urlTtlMillis: Long = 30 * 60 * 1000L
    @Volatile var expireUrlNow: Boolean = false
    @Volatile var failUrlFetchTimes: Int = 0

    // transfer
    @Volatile var rangeSupported: Boolean = true
    @Volatile var forcedHttpStatus: Int? = null
    @Volatile var transientFailuresLeft: Int = 0
    @Volatile var speedKbps: Int? = null
    @Volatile var corruptNextDownload: Boolean = false
    @Volatile var truncateNextDownloadBy: Int = 0
    @Volatile var localApkPath: String? = null

    // install
    @Volatile var forcedInstallFailureCode: Int? = null

    fun clearSignals() {
        driving = null; battery = null; networkClass = null; cellularSubscribed = null; freeSpaceBytes = null
    }

    fun reset() {
        clearSignals()
        artifact = null
        urlTtlMillis = 30 * 60 * 1000L
        expireUrlNow = false
        failUrlFetchTimes = 0
        rangeSupported = true
        forcedHttpStatus = null
        transientFailuresLeft = 0
        speedKbps = null
        corruptNextDownload = false
        truncateNextDownloadBy = 0
        localApkPath = null
        forcedInstallFailureCode = null
    }

    fun dump(): String = buildString {
        appendLine("driving=$driving battery=$battery network=$networkClass cellular=$cellularSubscribed freeSpace=$freeSpaceBytes")
        appendLine("artifact=${artifact?.packageName}@${artifact?.versionCode} urlTtlMs=$urlTtlMillis expireNow=$expireUrlNow failUrlFetch=$failUrlFetchTimes")
        appendLine("range=$rangeSupported httpStatus=$forcedHttpStatus transientLeft=$transientFailuresLeft speedKbps=$speedKbps")
        appendLine("corrupt=$corruptNextDownload truncateBy=$truncateNextDownloadBy localApk=$localApkPath")
        append("installFailureCode=$forcedInstallFailureCode")
    }
}

// ---------------------------------------------------------------- signal decorators

class DebugDrivingProvider(private val real: DrivingStateProvider) : DrivingStateProvider {
    private val overrides = MutableStateFlow<DrivingState?>(null)
    fun set(state: DrivingState?) { DebugState.driving = state; overrides.value = state }
    override val driving: Flow<DrivingState> =
        combine(real.driving, overrides) { r, o -> o ?: r }
}

class DebugBatteryMonitor(private val real: BatteryMonitor) : BatteryMonitor {
    private val overrides = MutableStateFlow<BatteryState?>(null)
    fun set(state: BatteryState?) { DebugState.battery = state; overrides.value = state }
    override val battery: Flow<BatteryState> =
        combine(real.battery, overrides) { r, o -> o ?: r }
}

class DebugNetworkMonitor(private val real: NetworkMonitor) : NetworkMonitor {
    private val overrides = MutableStateFlow<NetworkClass?>(null)
    fun set(cls: NetworkClass?) { DebugState.networkClass = cls; overrides.value = cls }
    override val network: Flow<NetworkState> =
        combine(real.network, overrides) { r, o -> o?.let(::synthesise) ?: r }

    /** Builds a [NetworkState] the real classifier will map back to the requested class. */
    private fun synthesise(cls: NetworkClass): NetworkState = when (cls) {
        NetworkClass.UNMETERED_WIFI -> NetworkState(NetworkTransport.WIFI, metered = false, validated = true)
        NetworkClass.METERED_WIFI -> NetworkState(NetworkTransport.WIFI, metered = true, validated = true)
        NetworkClass.CELLULAR -> NetworkState(NetworkTransport.CELLULAR, metered = true, validated = true)
        NetworkClass.BLUETOOTH_TETHER -> NetworkState(NetworkTransport.BLUETOOTH, metered = true, validated = true)
        NetworkClass.ETHERNET -> NetworkState(NetworkTransport.ETHERNET, metered = false, validated = true)
        NetworkClass.NONE -> NetworkState(NetworkTransport.NONE, metered = false, validated = false)
        NetworkClass.OTHER -> NetworkState(NetworkTransport.OTHER, metered = false, validated = true)
    }
}

class DebugSubscriptionProvider(private val real: ConnectivitySubscriptionProvider) : ConnectivitySubscriptionProvider {
    private val overrides = MutableStateFlow<Boolean?>(null)
    fun set(value: Boolean?) { DebugState.cellularSubscribed = value; overrides.value = value }
    override val subscribed: Flow<Boolean> =
        combine(real.subscribed, overrides) { r, o -> o ?: r }
}

class DebugStorageMonitor(private val real: StorageMonitor) : StorageMonitor {
    override fun freeBytes(): Long = DebugState.freeSpaceBytes ?: real.freeBytes()
}

// ---------------------------------------------------------------- cloud decorators

class DebugCatalogueProvider(private val real: CatalogueProvider) : CatalogueProvider {
    override suspend fun availableArtifacts(): List<Artifact> =
        DebugState.artifact?.let { listOf(it) } ?: real.availableArtifacts()
}

/** Injects expiry and fetch failures so FR-31 and FR-32 can be exercised on demand. */
class DebugUrlProvider(private val real: ArtifactUrlProvider) : ArtifactUrlProvider {
    override suspend fun downloadUrl(artifactId: String): SignedUrl {
        if (DebugState.failUrlFetchTimes > 0) {
            DebugState.failUrlFetchTimes--
            throw IOException("debug: forced URL fetch failure")
        }
        val url = real.downloadUrl(artifactId)
        return when {
            DebugState.expireUrlNow -> {
                DebugState.expireUrlNow = false
                url.copy(expiresAt = Instant.now().minusSeconds(1))
            }
            else -> url.copy(expiresAt = Instant.now().plusMillis(DebugState.urlTtlMillis))
        }
    }
}
