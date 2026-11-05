package com.lucid.appmanager.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import com.lucid.appmanager.DownloadConfig
import com.lucid.appmanager.DownloadStatus
import com.lucid.appmanager.IAppManagerDownloadService
import com.lucid.appmanager.IDownloadStatusCallback
import com.lucid.appmanager.ipc.AppManagerDownloadService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Talks to the download service only through its AIDL binder, like any external store client would. */
class StatusViewModel(app: Application) : AndroidViewModel(app) {
    private val _status = MutableStateFlow<DownloadStatus?>(null)
    private val _config = MutableStateFlow<DownloadConfig?>(null)
    private val _events = MutableStateFlow<List<String>>(emptyList())
    val status: StateFlow<DownloadStatus?> = _status
    val config: StateFlow<DownloadConfig?> = _config
    val events: StateFlow<List<String>> = _events

    private var service: IAppManagerDownloadService? = null

    private val callback = object : IDownloadStatusCallback.Stub() {
        override fun onStatusChanged(status: DownloadStatus) { _status.value = status }
        override fun onEvent(line: String) { _events.update { (it + line).takeLast(40) } }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val s = IAppManagerDownloadService.Stub.asInterface(binder)
            service = s
            s.registerCallback(callback)
            _status.value = s.status
            _config.value = s.config
        }

        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }

    init {
        app.bindService(Intent(app, AppManagerDownloadService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    fun runNow() { service?.startDownload(null) }
    fun cancel() { service?.cancelDownload() }
    fun setEnabled(on: Boolean) { service?.setEnabled(on) }

    /** FR-52. Takes effect on the next run; a flip mid-run does not change the run in flight. */
    fun setInstallMode(mode: String) { service?.setInstallMode(mode) }
    fun updateConfig(transform: (DownloadConfig) -> DownloadConfig) {
        val s = service ?: return
        val new = transform(_config.value ?: s.config)
        s.setConfig(new)
        _config.value = new
    }

    override fun onCleared() {
        service?.unregisterCallback(callback)
        getApplication<Application>().unbindService(connection)
    }
}
