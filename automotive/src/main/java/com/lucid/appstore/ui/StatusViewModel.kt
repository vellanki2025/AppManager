package com.lucid.appstore.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lucid.appstore.download.DownloadComponent
import com.lucid.appstore.download.events.ServiceEvent
import com.lucid.appstore.download.model.Config
import com.lucid.appstore.download.model.DeviceState
import com.lucid.appstore.download.model.RunState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Exposes the download component to the status screen. Device state is observed only while the screen is visible. */
class StatusViewModel(private val c: DownloadComponent = DownloadComponent.get()) : ViewModel() {
    private val started = SharingStarted.WhileSubscribed(5_000)

    val runState: StateFlow<RunState> = c.runStateStore.state.stateIn(viewModelScope, started, RunState())
    val config: StateFlow<Config> = c.config.config.stateIn(viewModelScope, started, Config())
    val enabled: StateFlow<Boolean> = c.flags.flags.map { it.downloadServiceEnabled }.stateIn(viewModelScope, started, true)
    val device: StateFlow<DeviceState?> = c.monitor.state.stateIn(viewModelScope, started, null)
    val events: StateFlow<List<ServiceEvent>> = c.eventBus.events
        .scan(emptyList<ServiceEvent>()) { list, e -> (list + e).takeLast(40) }
        .stateIn(viewModelScope, started, emptyList())

    fun runNow() = c.scheduler.enqueueRunIfAbsent()
    fun cancel() { viewModelScope.launch { c.coordinator.cancelCurrentRun() } }
    fun setEnabled(on: Boolean) { viewModelScope.launch { c.flags.setDownloadServiceEnabled(on) } }
    fun updateConfig(transform: (Config) -> Config) { viewModelScope.launch { c.config.update(transform) } }
}
