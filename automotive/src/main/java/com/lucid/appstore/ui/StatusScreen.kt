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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
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
import com.lucid.appstore.download.events.Logger
import com.lucid.appstore.download.events.ServiceEvent
import com.lucid.appstore.download.model.Config
import com.lucid.appstore.download.model.DeviceState
import com.lucid.appstore.download.model.NetworkMode
import com.lucid.appstore.download.model.RunState
import com.lucid.appstore.download.policy.NetworkClassifier
import java.time.LocalTime

@Composable
fun StatusScreen(vm: StatusViewModel) {
    val run by vm.runState.collectAsStateWithLifecycle()
    val config by vm.config.collectAsStateWithLifecycle()
    val enabled by vm.enabled.collectAsStateWithLifecycle()
    val device by vm.device.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { StateCard(run, device, enabled, onRun = vm::runNow, onCancel = vm::cancel, onEnabled = vm::setEnabled) }
        item { ConfigCard(config, vm::updateConfig) }
        item { Text("Events", style = MaterialTheme.typography.titleMedium) }
        items(events.asReversed()) { EventRow(it) }
    }
}

@Composable
private fun StateCard(run: RunState, device: DeviceState?, enabled: Boolean, onRun: () -> Unit, onCancel: () -> Unit, onEnabled: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LabeledSwitch("Download service enabled (feature flag)", enabled, onEnabled)
            Text("Run: ${run.phase}" + (run.waitReason?.let { " ($it)" } ?: "") + "  id=${run.runId.ifEmpty { "-" }}")
            run.progress?.let { Text("Progress: ${it.downloaded / 1_048_576} / ${it.total?.div(1_048_576) ?: "?"} MB") }
            Text(
                device?.let { "Vehicle: ${it.driving}  battery ${it.battery.levelPercent}%${if (it.battery.charging) " charging" else ""}  net ${NetworkClassifier.classify(it.network)}${if (it.network.validated) "" else " (unvalidated)"}" }
                    ?: "Vehicle: waiting for signals",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRun, enabled = enabled) { Text("Run now") }
                OutlinedButton(onClick = onCancel) { Text("Cancel run") }
            }
        }
    }
}

@Composable
private fun ConfigCard(config: Config, update: ((Config) -> Config) -> Unit) {
    var time by remember(config.scheduleTime) { mutableStateOf(config.scheduleTime.toString()) }
    var threshold by remember(config.batteryThresholdPercent) { mutableFloatStateOf(config.batteryThresholdPercent.toFloat()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Config", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(time, { time = it }, label = { Text("Schedule (HH:mm)") }, singleLine = true)
                Spacer(Modifier.width(8.dp))
                Button(onClick = { runCatching { LocalTime.parse(time) }.getOrNull()?.let { t -> update { it.copy(scheduleTime = t) } } }) { Text("Apply") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NetworkMode.entries.forEach { mode ->
                    FilterChip(selected = config.networkMode == mode, onClick = { update { it.copy(networkMode = mode) } }, label = { Text(mode.name) })
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

@Composable
private fun EventRow(e: ServiceEvent) {
    Text(Logger.format(e), style = MaterialTheme.typography.bodySmall)
}
