package dev.spofify.mobile.feature.devices

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.spofify.mobile.core.PlaybackDevice

/** Presentation only: dismissing the picker never tears down output ownership or heartbeats. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackDevicesSheet(coordinator: PlaybackCoordinator, onDismiss: () -> Unit) {
    val state by coordinator.state.collectAsStateWithLifecycle()
    // One authenticated read is presentation, not a receiving service heartbeat/registration.
    LaunchedEffect(coordinator) { if (coordinator.state.value.view == null) coordinator.retry() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        key(coordinator) {
            PlaybackDevicesContent(state, onDismiss, coordinator::retry, coordinator::continuePlaybackHere,
                coordinator::select, coordinator::rename)
        }
    }
}

@Composable
internal fun ColumnScope.PlaybackDevicesContent(
    state: PlaybackDevicesState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
    onSelect: (String) -> Unit,
    onRename: (String, String) -> Unit,
) {
    val viewOnly = state.deviceId.isEmpty()
    var editing by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text("Playback devices", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onDismiss) { Text("Close", maxLines = 1, softWrap = false) }
        }
        if (viewOnly) Text("View only · Playback transfer is unavailable on this phone.",
            Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
        if (state.message.isNotEmpty()) Text(state.message,
            Modifier.padding(vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })
        if (state.connecting || state.verifyingConnection) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.retryAvailable) TextButton(onClick = onRetry) { Text("Retry connection") }
        if (!viewOnly && state.needsTap) Button(onClick = onContinue) { Text("Continue playback here") }
        if (state.devices.isEmpty() && !state.connecting && !state.verifyingConnection) Text("No devices available.")
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            items(state.devices, key = { it.deviceId }) { device ->
                PlaybackDeviceCard(state, device, editing == device.deviceId && !viewOnly, draft,
                    onDraft = { draft = it }, onSelect = { onSelect(device.deviceId) },
                    onEdit = { editing = device.deviceId; draft = device.name },
                    onSave = { onRename(device.deviceId, draft); editing = null },
                    onCancel = { editing = null })
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
internal fun PlaybackDeviceCard(
    state: PlaybackDevicesState, device: PlaybackDevice, editing: Boolean, draft: String,
    onDraft: (String) -> Unit, onSelect: () -> Unit, onEdit: () -> Unit,
    onSave: () -> Unit, onCancel: () -> Unit,
) {
    val active = device.deviceId == state.activeDeviceId
    val selected = device.deviceId == state.selectedDeviceId
    val viewOnly = state.deviceId.isEmpty()
    val musical = state.view?.state
    val status = listOfNotNull(
        "This device".takeIf { device.deviceId == state.deviceId },
        (if (state.selectionUnconfirmed) "Selecting" else "Selected").takeIf { selected },
        when { !device.available -> "Unavailable"; !active -> "Available"; musical?.error == "blocked" -> "Needs a tap"; musical?.playing == true -> "Playing"; else -> "Active output · Paused" },
    ).joinToString(" · ")
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val actionStyle = MaterialTheme.typography.labelLarge
    // Measure production scaled typography, not names, to select one layout for all cards.
    val playWidth = with(density) { measurer.measure("Play from here", actionStyle, softWrap = false).size.width.toDp() }
    val renameWidth = with(density) { measurer.measure("Rename", actionStyle, softWrap = false).size.width.toDp() } + 24.dp
    Surface(color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        BoxWithConstraints(Modifier.padding(12.dp)) {
            val stacked = maxWidth < playWidth + renameWidth + 8.dp
            Column {
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(device.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(status, style = MaterialTheme.typography.bodySmall)
                    }
                    if (!viewOnly && !editing && stacked) RenameDeviceButton(device.name, renameWidth, onEdit)
                }
                if (editing) {
                    OutlinedTextField(value = draft, onValueChange = onDraft, label = { Text("Device name") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    Row {
                        TextButton(onClick = onSave) { Text("Save") }
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                } else if (!viewOnly) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Pending/quarantine intentionally DO NOT disable other available choices.
                    TextButton(onClick = onSelect,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "Play from here on ${device.name}" },
                        contentPadding = PaddingValues(vertical = 8.dp),
                        enabled = device.available && state.deviceId.isNotEmpty() && !selected && (!active || state.selectedDeviceId.isNotEmpty())) {
                        // The text, not just the target, starts at the name/status edge.
                        Text("Play from here", Modifier.fillMaxWidth(), maxLines = 1, softWrap = false)
                    }
                    if (!stacked) RenameDeviceButton(device.name, renameWidth, onEdit)
                }
            }
        }
    }
}

@Composable
private fun RenameDeviceButton(name: String, width: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.requiredWidth(width).heightIn(min = 48.dp)
        .semantics { contentDescription = "Rename $name" }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        Text("Rename", maxLines = 1, softWrap = false)
    }
}

@Composable
fun DevicePickerButton(coordinator: PlaybackCoordinator, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val state by coordinator.state.collectAsStateWithLifecycle()
    val active = state.devices.find { it.deviceId == state.activeDeviceId }
    TextButton(onClick = onClick, modifier = modifier) { Text(active?.let { "Devices · ${it.name}" } ?: "Playback devices") }
}
