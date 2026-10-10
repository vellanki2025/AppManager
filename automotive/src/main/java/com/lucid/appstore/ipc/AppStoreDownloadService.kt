package com.lucid.appstore.ipc

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteCallbackList
import android.util.Log
import com.lucid.appstore.AppInfo
import com.lucid.appstore.AppStoreApplication
import com.lucid.appstore.DownloadStatus
import com.lucid.appstore.IAppStoreDownloadService
import com.lucid.appstore.IDownloadStatusCallback
import com.lucid.appstore.download.DownloadComponent
import com.lucid.appstore.download.events.Logger
import com.lucid.appstore.download.model.RunState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Bound service implementing [IAppStoreDownloadService]. Status callbacks fire on every run-state change. */
class AppStoreDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val callbacks = RemoteCallbackList<IDownloadStatusCallback>()
    private var statusJob: Job? = null
    private val component get() = DownloadComponent.get()
    private val catalogue get() = (application as AppStoreApplication).catalogue

    private val binder = object : IAppStoreDownloadService.Stub() {
        override fun getAvailableApps(): List<AppInfo> = runBlocking { catalogue.availableApps() }
        override fun getStatus(): DownloadStatus = runBlocking { currentStatus() }
        override fun startDownload(packageName: String?) {
            Log.i(Logger.TAG, "startDownload requested for $packageName")
            component.scheduler.enqueueRunIfAbsent()
        }
        override fun cancelDownload() { scope.launch { component.coordinator.cancelCurrentRun() } }
        override fun registerCallback(callback: IDownloadStatusCallback?) { callback?.let { callbacks.register(it) } }
        override fun unregisterCallback(callback: IDownloadStatusCallback?) { callback?.let { callbacks.unregister(it) } }
    }

    override fun onCreate() {
        super.onCreate()
        statusJob = scope.launch {
            combine(component.runStateStore.state, component.flags.flags) { s, f -> s.toStatus(f.downloadServiceEnabled) }
                .collect { status -> broadcast(status) }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        callbacks.kill()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun currentStatus() =
        component.runStateStore.load().toStatus(component.flags.flags.first().downloadServiceEnabled)

    private fun broadcast(status: DownloadStatus) {
        val n = callbacks.beginBroadcast()
        for (i in 0 until n) runCatching { callbacks.getBroadcastItem(i).onStatusChanged(status) }
        callbacks.finishBroadcast()
    }

    private fun RunState.toStatus(enabled: Boolean) = DownloadStatus(
        enabled = enabled,
        phase = phase.name,
        waitReason = waitReason?.name,
        downloadedBytes = progress?.downloaded ?: 0L,
        totalBytes = progress?.total ?: -1L,
        lastSuccessAt = lastSuccessAt ?: 0L,
    )
}
