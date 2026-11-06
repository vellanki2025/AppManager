package com.lucid.appmanager.ipc

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteCallbackList
import android.util.Log
import com.lucid.appmanager.ui.AppInfo
import com.lucid.appmanager.DownloadConfig
import com.lucid.appmanager.DownloadStatus
import com.lucid.appmanager.IAppManagerDownloadService
import com.lucid.appmanager.IDownloadStatusCallback
import com.lucid.appmanager.download.DownloadComponent
import com.lucid.appmanager.download.events.Logger
import com.lucid.appmanager.download.model.DeviceState
import com.lucid.appmanager.download.model.RunState
import com.lucid.appmanager.download.policy.NetworkClassifier
import com.lucid.appmanager.install.InstallMode
import com.lucid.appmanager.install.OverrideCheck
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
 * Bound service implementing [IAppManagerDownloadService]. While bound it observes run state, feature flag
 * and vehicle signals and pushes a [DownloadStatus] to every registered callback on each change.
 */
class AppManagerDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val callbacks = RemoteCallbackList<IDownloadStatusCallback>()
    private var pushJob: Job? = null
    private val component get() = DownloadComponent.get()

    private val binder = object : IAppManagerDownloadService.Stub() {
        /** Every app the current manifest covers, whether or not it needs an update. One row each. */
        override fun getAvailableApps(): List<AppInfo> = runBlocking {
            component.catalogue.currentManifest()?.apps.orEmpty().map { a ->
                AppInfo(
                    packageName = a.packageName,
                    title = a.title,
                    versionName = a.versionName,
                    versionCode = a.versionCode,
                    sizeBytes = a.sizeBytes,
                    installedVersionCode = component.checker.installedVersionCode(a.packageName) ?: -1L,
                )
            }
        }

        override fun getStatus(): DownloadStatus = runBlocking {
            toStatus(component.runStateStore.load(), component.flags.flags.first(), null, pendingOverrideSummary())
        }
        override fun getConfig(): DownloadConfig = runBlocking { DownloadConfig.from(component.config.config.first()) }
        override fun setConfig(config: DownloadConfig) { scope.launch { component.config.update(config::applyTo) } }
        override fun setEnabled(enabled: Boolean) { scope.launch { component.flags.setDownloadServiceEnabled(enabled) } }
        override fun setInstallMode(mode: String?) { scope.launch { component.flags.setInstallMode(InstallMode.parse(mode)) } }
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
                ) { s, f, d -> toStatus(s, f, d, pendingOverrideSummary()) }.collect { status -> broadcast { it.onStatusChanged(status) } }
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

    /** Null unless a partition override is staged and still waiting for its reboot. */
    private suspend fun pendingOverrideSummary(): String? =
        when (val check = component.overrides.checkApplied()) {
            is OverrideCheck.AwaitingReboot -> "${check.override.packageName} v${check.override.targetVersionCode} staged, awaiting reboot"
            is OverrideCheck.NotApplied -> "${check.override.packageName} v${check.override.targetVersionCode} staged but not applied"
            else -> null
        }

    private fun toStatus(s: RunState, f: com.lucid.appmanager.download.config.Flags, d: DeviceState?, pendingOverride: String?) = DownloadStatus(
        enabled = f.downloadServiceEnabled,
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
        installMode = f.installMode.name,
        pendingOverride = pendingOverride,
    )
}
