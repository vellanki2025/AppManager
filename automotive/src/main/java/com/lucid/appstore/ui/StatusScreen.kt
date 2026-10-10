package com.lucid.appstore.ui

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
import com.lucid.appstore.DownloadConfig
import com.lucid.appstore.DownloadStatus
import java.time.LocalTime

private val NETWORK_MODES = listOf("WIFI_ONLY", "WIFI_OR_CELLULAR")

/** Developer status screen, fed entirely by the AIDL service through [StatusViewModel]. */
@Composable
fun StatusScreen(vm: StatusViewModel) {
    val status by vm.status.collectAsStateWithLifecycle()
    val config by vm.config.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { StateCard(status, onRun = vm::runNow, onCancel = vm::cancel, onEnabled = vm::setEnabled) }
        item { config?.let { ConfigCard(it, vm::updateConfig) } ?: Text("Connecting to download service…") }
        item { Text("Events", style = MaterialTheme.typography.titleMedium) }
        items(events.asReversed()) { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun StateCard(s: DownloadStatus?, onRun: () -> Unit, onCancel: () -> Unit, onEnabled: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LabeledSwitch("Download service enabled (feature flag)", s?.enabled ?: true, onEnabled)
            Text("Run: ${s?.phase ?: "-"}" + (s?.waitReason?.let { " ($it)" } ?: ""))
            if (s != null && s.totalBytes >= 0) Text("Progress: ${s.downloadedBytes / 1_048_576} / ${s.totalBytes / 1_048_576} MB")
            Text(
                if (s == null || s.driving.isEmpty()) "Vehicle: waiting for signals"
                else "Vehicle: ${s.driving}  battery ${s.batteryPercent}%${if (s.charging) " charging" else ""}  net ${s.networkClass}${if (s.networkValidated) "" else " (unvalidated)"}",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRun, enabled = s?.enabled ?: false) { Text("Run now") }
                OutlinedButton(onClick = onCancel) { Text("Cancel run") }
            }
        }
    }
}

@Composable
private fun ConfigCard(config: DownloadConfig, update: ((DownloadConfig) -> DownloadConfig) -> Unit) {
    var time by remember(config.scheduleTime) { mutableStateOf(config.scheduleTime) }
    var threshold by remember(config.batteryThresholdPercent) { mutableFloatStateOf(config.batteryThresholdPercent.toFloat()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Config", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(time, { time = it }, label = { Text("Schedule (HH:mm)") }, singleLine = true)
                Spacer(Modifier.width(8.dp))
                Button(onClick = { runCatching { LocalTime.parse(time) }.onSuccess { t -> update { it.copy(scheduleTime = t.toString()) } } }) { Text("Apply") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NETWORK_MODES.forEach { mode ->
                    FilterChip(selected = config.networkMode == mode, onClick = { update { it.copy(networkMode = mode) } }, label = { Text(mode) })
                }
            }
            LabeledSwitch("Allow tethering (hotspot, Bluetooth)", config.allowTethered) { v -> update { it.copy(allowTethered = v) } }
            LabeledSwitch("Require parking brake", config.requireParkingBrake) { v -> update { it.copy(requireParkingBrake = v) } }
            Text("Battery threshold: ${threshold.toInt()}% (resume at +${config.batteryResumeMarginPercent})")
            Slider(
                value = threshold,
                onValueChange = { threshold = it },
                onValueChangeFinished = { update { it.copy(batteryThresholdPercent = threshold.toInt()) } },
                valueRange = 5f..80f,
            )
        }
    }
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
