package com.lucid.appmanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lucid.appmanager.DownloadConfig
import com.lucid.appmanager.DownloadStatus
import java.time.LocalTime

private val NETWORK_MODES = listOf("WIFI_ONLY" to "Wi-Fi only", "WIFI_OR_CELLULAR" to "Wi-Fi or cellular")

/** Developer status screen, fed entirely by the AIDL service through [StatusViewModel]. */
@Composable
fun StatusScreen(vm: StatusViewModel) {
    val status by vm.status.collectAsStateWithLifecycle()
    val config by vm.config.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { RunCard(status, config, vm::updateConfig, onRun = vm::runNow, onCancel = vm::cancel, onEnabled = vm::setEnabled) }
        item { InstallCard(status, onMode = vm::setInstallMode) }
        item { NetworkCard(status, config, vm::updateConfig) }
        item { DrivingCard(status) }
        item { BatteryCard(status, config, vm::updateConfig) }
        item { Text("Events", style = MaterialTheme.typography.titleMedium) }
        items(events.asReversed()) { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

private typealias Update = ((DownloadConfig) -> DownloadConfig) -> Unit

@Composable
private fun RunCard(s: DownloadStatus?, c: DownloadConfig?, update: Update, onRun: () -> Unit, onCancel: () -> Unit, onEnabled: (Boolean) -> Unit) {
    var time by remember(c?.scheduleTime) { mutableStateOf(c?.scheduleTime ?: "") }
    Section("Run") {
        LabeledSwitch("Download service enabled (feature flag)", s?.enabled ?: true, onChange = onEnabled)
        Text("State: ${s?.phase ?: "-"}" + (s?.waitReason?.let { " (waiting: $it)" } ?: ""))
        if (s != null && s.totalBytes >= 0) Text("Progress: ${s.downloadedBytes / 1_048_576} / ${s.totalBytes / 1_048_576} MB")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(time, { time = it }, label = { Text("Daily schedule (HH:mm)") }, singleLine = true, enabled = c != null)
            Spacer(Modifier.width(8.dp))
            Button(onClick = { runCatching { LocalTime.parse(time) }.onSuccess { t -> update { it.copy(scheduleTime = t.toString()) } } }, enabled = c != null) { Text("Apply") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRun, enabled = s?.enabled ?: false) { Text("Run now") }
            OutlinedButton(onClick = onCancel) { Text("Cancel run") }
        }
    }
}

/** Install path and any staged partition override (FR-52, FR-48). */
@Composable
private fun InstallCard(s: DownloadStatus?, onMode: (String) -> Unit) {
    Section("Install") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "PACKAGE_MANAGER" to "Package manager",
                "APK_OVERRIDE" to "APK override",
            ).forEach { (mode, label) ->
                FilterChip(
                    selected = s?.installMode == mode,
                    onClick = { onMode(mode) },
                    label = { Text(label) },
                    enabled = s != null,
                )
            }
        }
        if (s?.installMode == "APK_OVERRIDE") {
            Text("Staging only: the APK is verified and placed, then applied by adb and a reboot. Needs a writable partition (userdebug).")
        }
        s?.pendingOverride?.let { Text("Pending override: $it") }
    }
}

@Composable
private fun NetworkCard(s: DownloadStatus?, c: DownloadConfig?, update: Update) {
    Section("Network") {
        Text("Current: " + (s?.networkClass?.ifEmpty { null }?.let { it + if (s.networkValidated) "" else " (no internet)" } ?: "unknown"))
        if (s?.cellularSubscribed == true) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NETWORK_MODES.forEach { (mode, label) ->
                    FilterChip(selected = c?.networkMode == mode, onClick = { update { it.copy(networkMode = mode) } }, label = { Text(label) }, enabled = c != null)
                }
            }
        } else {
            Text("Wi-Fi only. Cellular needs a connectivity subscription.", style = MaterialTheme.typography.bodySmall)
        }
        Text("Metered hotspots are never used.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DrivingCard(s: DownloadStatus?) {
    Section("Driving state") {
        Text("Current: " + (s?.driving?.ifEmpty { null } ?: "waiting for signals"))
        Text("Download runs only while PARKED (gear in Park, speed zero).", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BatteryCard(s: DownloadStatus?, c: DownloadConfig?, update: Update) {
    var threshold by remember(c?.batteryThresholdPercent) { mutableFloatStateOf((c?.batteryThresholdPercent ?: 20).toFloat()) }
    Section("Battery") {
        Text(
            if (s == null || s.batteryPercent < 0) "Current: waiting for signals"
            else "Current: ${s.batteryPercent}%" + if (s.charging) ", charging" else ", not charging",
        )
        Text("Threshold: ${threshold.toInt()}%  (resume after a battery wait at ${threshold.toInt() + (c?.batteryResumeMarginPercent ?: 5)}%)")
        Slider(
            value = threshold,
            onValueChange = { threshold = it },
            onValueChangeFinished = { update { it.copy(batteryThresholdPercent = threshold.toInt()) } },
            valueRange = 5f..80f,
            enabled = c != null,
        )
        Text("Charging always allows the download, even below the threshold.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
