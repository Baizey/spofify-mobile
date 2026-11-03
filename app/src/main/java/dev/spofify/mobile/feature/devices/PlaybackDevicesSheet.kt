package dev.spofify.mobile.feature.devices

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Presentation only: dismissing the picker never tears down output ownership or heartbeats. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackDevicesSheet(coordinator: PlaybackCoordinator, onDismiss: () -> Unit) {
    val state by coordinator.state.collectAsStateWithLifecycle()
    // One authenticated read is presentation, not a receiving service heartbeat/registration.
    LaunchedEffect(coordinator) { if (coordinator.state.value.view == null) coordinator.retry() }
    var editing by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Playback devices", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onDismiss) { Text("Close") }
            }
            if (state.message.isNotEmpty()) Text(state.message,
                Modifier.padding(vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })
            if (state.connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = coordinator::retry) { Text("Retry connection") }
                if (state.needsTap) Button(onClick = coordinator::continuePlaybackHere) { Text("Continue playback here") }
            }
            if (state.devices.isEmpty()) Text("No playback services can be verified. Open Spofify on the other device.")
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                items(state.devices, key = { it.deviceId }) { device ->
                    val active = device.deviceId == state.activeDeviceId
                    val selected = device.deviceId == state.selectedDeviceId
                    val musical = state.view?.state
                    Surface(color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(device.name, style = MaterialTheme.typography.titleMedium)
                            Text(listOfNotNull(
                                "This device".takeIf { device.deviceId == state.deviceId },
                                (if (state.selectionUnconfirmed) "Selecting" else "Selected").takeIf { selected },
                                when { !device.available -> "Unavailable"; !active -> "Available"; musical?.error == "blocked" -> "Needs a tap"; musical?.playing == true -> "Playing"; else -> "Active output · Paused" },
                            ).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                            if (editing == device.deviceId) {
                                OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text("Device name") }, singleLine = true,
                                    modifier = Modifier.fillMaxWidth())
                                Row {
                                    TextButton(onClick = { coordinator.rename(device.deviceId, draft); editing = null }) { Text("Save") }
                                    TextButton(onClick = { editing = null }) { Text("Cancel") }
                                }
                            } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                // Pending/quarantine intentionally DO NOT disable other available choices.
                                TextButton(onClick = { coordinator.select(device.deviceId) },
                                    enabled = device.available && state.deviceId.isNotEmpty() && !selected && (!active || state.selectedDeviceId.isNotEmpty())) {
                                    Text("Play on ${device.name}")
                                }
                                TextButton(onClick = { editing = device.deviceId; draft = device.name }) { Text("Rename") }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun DevicePickerButton(coordinator: PlaybackCoordinator, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val state by coordinator.state.collectAsStateWithLifecycle()
    val active = state.devices.find { it.deviceId == state.activeDeviceId }
    TextButton(onClick = onClick, modifier = modifier) { Text(active?.let { "Devices · ${it.name}" } ?: "Playback devices") }
}
