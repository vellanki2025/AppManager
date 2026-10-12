package com.lucid.appstore.ipc

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteCallbackList
import android.util.Log
import com.lucid.appstore.ui.AppInfo
import com.lucid.appstore.AppStoreApplication
import com.lucid.appstore.DownloadConfig
import com.lucid.appstore.DownloadStatus
import com.lucid.appstore.IAppStoreDownloadService
import com.lucid.appstore.IDownloadStatusCallback
import com.lucid.appstore.download.DownloadComponent
import com.lucid.appstore.download.events.Logger
import com.lucid.appstore.download.model.DeviceState
import com.lucid.appstore.download.model.RunState
import com.lucid.appstore.download.policy.NetworkClassifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Bound service implementing [IAppStoreDownloadService]. While bound it observes run state, feature flag
 * and vehicle signals and pushes a [DownloadStatus] to every registered callback on each change.
 */
class AppStoreDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val callbacks = RemoteCallbackList<IDownloadStatusCallback>()
    private var pushJob: Job? = null
    private val component get() = DownloadComponent.get()
    private val catalogue get() = (application as AppStoreApplication).catalogue

    private val binder = object : IAppStoreDownloadService.Stub() {
        override fun getAvailableApps(): List<AppInfo> = runBlocking { catalogue.availableApps() }
        override fun getStatus(): DownloadStatus = runBlocking {
            toStatus(component.runStateStore.load(), component.flags.flags.first().downloadServiceEnabled, null)
        }
        override fun getConfig(): DownloadConfig = runBlocking { DownloadConfig.from(component.config.config.first()) }
        override fun setConfig(config: DownloadConfig) { scope.launch { component.config.update(config::applyTo) } }
        override fun setEnabled(enabled: Boolean) { scope.launch { component.flags.setDownloadServiceEnabled(enabled) } }
        override fun startDownload(packageName: String?) {
            Log.i(Logger.TAG, "startDownload requested for $packageName")
            component.scheduler.enqueueRunIfAbsent()
        }
        override fun cancelDownload() { scope.launch { component.coordinator.cancelCurrentRun() } }
        override fun registerCallback(callback: IDownloadStatusCallback?) { callback?.let { callbacks.register(it) } }
        override fun unregisterCallback(callback: IDownloadStatusCallback?) { callback?.let { callbacks.unregister(it) } }
    }

    override fun onBind(intent: Intent?): IBinder {
        pushJob?.cancel()
        pushJob = scope.launch {
            launch {
                combine(
                    component.runStateStore.state,
                    component.flags.flags,
                    component.monitor.state.map<DeviceState, DeviceState?> { it }.onStart { emit(null) },
                ) { s, f, d -> toStatus(s, f.downloadServiceEnabled, d) }.collect { status -> broadcast { it.onStatusChanged(status) } }
            }
            launch { component.eventBus.events.collect { e -> broadcast { it.onEvent(Logger.format(e)) } } }
        }
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        pushJob?.cancel()
        pushJob = null
        return true
    }

    override fun onRebind(intent: Intent?) { onBind(intent) }

    override fun onDestroy() {
        callbacks.kill()
        scope.cancel()
        super.onDestroy()
    }

    private fun broadcast(call: (IDownloadStatusCallback) -> Unit) {
        val n = callbacks.beginBroadcast()
        for (i in 0 until n) runCatching { call(callbacks.getBroadcastItem(i)) }
        callbacks.finishBroadcast()
    }

    private fun toStatus(s: RunState, enabled: Boolean, d: DeviceState?) = DownloadStatus(
        enabled = enabled,
        phase = s.phase.name,
        waitReason = s.waitReason?.name,
        downloadedBytes = s.progress?.downloaded ?: 0L,
        totalBytes = s.progress?.total ?: -1L,
        lastSuccessAt = s.lastSuccessAt ?: 0L,
        driving = d?.driving?.name ?: "",
        batteryPercent = d?.battery?.levelPercent ?: -1,
        charging = d?.battery?.charging ?: false,
        networkClass = d?.let { NetworkClassifier.classify(it.network).name } ?: "",
        networkValidated = d?.network?.validated ?: false,
        cellularSubscribed = d?.cellularSubscribed ?: false,
    )
}
