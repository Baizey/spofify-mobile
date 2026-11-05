package dev.spofify.mobile.feature.devices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.spofify.mobile.core.PlaybackDevice
import dev.spofify.mobile.ui.LoadingRing

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
        Text("Playback devices", style = MaterialTheme.typography.titleLarge)
        if (viewOnly) Text("View only · Playback transfer is unavailable on this phone.",
            Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
        if (state.message.isNotEmpty() && state.messagePurpose != PlaybackDevicesMessagePurpose.PROGRESS) Text(state.message,
            Modifier.padding(vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })
        if (state.connecting || state.verifyingConnection) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.retryAvailable) TextButton(onClick = onRetry) { Text("Retry connection") }
        if (!viewOnly && state.needsTap) Button(onClick = onContinue) { Text("Continue playback here") }
        if (state.devices.isEmpty() && !state.connecting && !state.verifyingConnection) Text("No devices available.")
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("Playback device list")) {
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
        when { !device.available -> "Unavailable"; !active -> "Available"; musical?.error == "blocked" -> "Needs a tap"; musical?.playing == true -> "Playing"; else -> "Active output · Paused" },
    ).joinToString(" · ")
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val actionStyle = MaterialTheme.typography.labelLarge
    // A stable trailing slot, measured with production scaled typography, never competes with identity.
    val renameWidth = with(density) { measurer.measure("Rename", actionStyle, softWrap = false).size.width.toDp() } + 24.dp
    val canSelect = device.available && !viewOnly && !selected && (!active || state.selectedDeviceId.isNotEmpty())
    val pending = state.pendingSelection?.deviceId == device.deviceId
    val ringSize = with(density) { MaterialTheme.typography.bodyMedium.fontSize.toDp() }
    val selectionModifier = if (editing) Modifier else Modifier.clickable(
        enabled = canSelect, role = Role.Button, onClickLabel = "Select output on ${device.name}", onClick = onSelect,
    ).semantics {
        contentDescription = "Select output on ${device.name}"
        this.selected = active // Confirmed authority only, never the optimistic request.
        stateDescription = listOfNotNull(status, "Switching output".takeIf { pending }).joinToString(" · ")
    }
    Surface(color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(selectionModifier.padding(12.dp).heightIn(min = 48.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    // Reserve the small overlay footprint even when idle: no text reflow when intent changes.
                    Column(Modifier.fillMaxWidth().padding(end = ringSize + 8.dp)) {
                        Text(device.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(status, style = MaterialTheme.typography.bodySmall)
                    }
                    if (pending) LoadingRing(Modifier.align(Alignment.TopEnd).size(ringSize).testTag("Pending output"))
                }
                if (!viewOnly && !editing) RenameDeviceButton(device.name, renameWidth, onEdit)
            }
            if (editing) {
                OutlinedTextField(value = draft, onValueChange = onDraft, label = { Text("Device name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Row {
                    TextButton(onClick = onSave, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save") }
                    TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun RenameDeviceButton(name: String, width: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.requiredWidth(width.coerceAtLeast(48.dp)).heightIn(min = 48.dp)
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
