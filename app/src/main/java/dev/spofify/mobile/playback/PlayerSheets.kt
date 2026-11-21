package dev.spofify.mobile.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import dev.spofify.mobile.core.library.MusicKind
import dev.spofify.mobile.core.playback.*
import dev.spofify.mobile.designsystem.*
import dev.spofify.mobile.feature.library.MusicArtwork

@Composable
internal fun DevicesSheet(controller: AccountChannel, onDismiss: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val ready = state.connection == PlaybackConnectionState.READY
    var renaming by remember { mutableStateOf<PlaybackDevice?>(null) }
    SpofifySheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 22.dp)) {
            SheetHeading("Playback devices")
            state.devices.forEach { device ->
                val active = device.deviceId == state.snapshot?.selectedDeviceId
                val available = ready && device.available && device.canPlay
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.weight(1f).heightIn(min = 80.dp).clickable(enabled = available, role = Role.Button) {
                        controller.selectDevice(device.deviceId); onDismiss()
                    }.semantics { selected = active }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SpofifyIcon(SpofifyGlyph.Devices, null)
                        Column(Modifier.weight(1f)) {
                            Text(device.name)
                            val kind = when {
                                !device.available -> "Unavailable"
                                !device.canPlay -> "Controller only"
                                device.deviceId == state.deviceId -> "This phone"
                                else -> "Playback device"
                            }
                            Text(kind + if (active) " · Selected" else "", Modifier.padding(top = 4.dp),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box(Modifier.width(18.dp)) { if (active) SpofifyIcon(SpofifyGlyph.Check, "Selected", Modifier.size(18.dp)) }
                    }
                    TextButton({ renaming = device }, enabled = ready, contentPadding = PaddingValues(vertical = 8.dp)) {
                        Text("Rename", style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (state.devices.isEmpty()) Text("No connected devices", Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.snapshot?.transfer != null) Text("Switching output", Modifier.padding(vertical = 12.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    renaming?.let { device -> DeviceNameSheet(controller, device) { renaming = null } }
}

@Composable
private fun DeviceNameSheet(controller: AccountChannel, device: PlaybackDevice, onDismiss: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    var name by remember(device.deviceId) { mutableStateOf(device.name) }
    var focused by remember { mutableStateOf(false) }
    val valid = isValidDeviceName(name)
    val currentName = state.devices.firstOrNull { it.deviceId == device.deviceId }?.name
    SpofifySheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 22.dp)) {
            Box(Modifier.padding(horizontal = 20.dp)) { SheetHeading("Rename device") }
            Text("Device name", Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 9.dp), style = MaterialTheme.typography.bodySmall)
            BasicTextField(name, { name = it }, Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .onFocusChanged { focused = it.isFocused }.spofifyField(focused)
                .semantics { contentDescription = "Device name" }.padding(horizontal = 20.dp, vertical = 14.dp),
                singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary))
            Spacer(Modifier.height(1.dp)) // Keep the former divider's space.
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onDismiss, Modifier.weight(1f), shape = SpofifySurfaces.control) { Text("Cancel") }
                Button({
                    if (currentName != name.trim()) controller.renameDevice(name.trim(), device.deviceId)
                    onDismiss()
                }, Modifier.weight(1f), shape = SpofifySurfaces.control,
                    enabled = valid && state.connection == PlaybackConnectionState.READY) { Text("Save") }
            }
        }
    }
}

@Composable
internal fun QueueSheet(controller: AppPlayback, loader: ImageLoader, onDismiss: () -> Unit) {
    val state = controller.observe()
    val online = (state as? PlayerPresentation.Online)?.view
    val ready = state.ready
    val position = state.queue?.position ?: -1
    val upcoming = state.queue?.queue.orEmpty().drop((position + 1).coerceAtLeast(0))
    val list = rememberLazyListState()
    var actions by remember(controller) { mutableStateOf<Long?>(null) }
    fun editable(key: Long) = online == null || isPublishedQueueKey(key)
    val selected = upcoming.firstOrNull { it.key == actions && editable(it.key) }
    SpofifySheet(onDismiss) {
        LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(), state = list,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 22.dp)) {
            item("heading") { SheetHeading("Queue") }
            state.queue?.current?.let { current -> item("current") {
                SheetGroup("Current song")
                QueueTrack(current, loader)
            } }
            item("up-next") { SheetGroup("Up next") }
            itemsIndexed(upcoming, key = { _, track -> track.key }) { _, track ->
                SwipeActionSurface(binding = listOf(controller, online?.sessionId, online?.snapshot?.stateVersion ?: upcoming, track.key),
                    onRight = if (ready) ({ controller.queueTrack(PlaybackTrackReference(track.id)) }) else null,
                    onLeft = if (ready && editable(track.key)) ({ controller.remove(track.key) }) else null,
                    slideOutOnLeft = true) {
                    QueueTrack(track, loader,
                        onOpen = if (ready && editable(track.key)) ({ controller.jump(track.key) }) else null,
                        onMore = if (editable(track.key)) ({ actions = track.key }) else null)
                }
            }
            if (upcoming.isEmpty()) item("empty") { Text("Nothing up next", Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    if (selected != null) SpofifySheet({ actions = null }) {
        val index = upcoming.indexOfFirst { it.key == selected.key }
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 22.dp)) {
            SheetHeading(selected.name)
            SheetAction("Move up", SpofifyGlyph.Up, ready && index > 0 && upcoming.getOrNull(index - 2)?.let { editable(it.key) } != false) {
                actions = null
                controller.moveUpcoming(selected.key, upcoming.getOrNull(index - 2)?.key)
            }
            SheetAction("Move down", SpofifyGlyph.Down, ready && index >= 0 && upcoming.getOrNull(index + 1)?.let { editable(it.key) } == true) {
                actions = null
                controller.moveUpcoming(selected.key, upcoming[index + 1].key)
            }
            SheetAction("Queue", SpofifyGlyph.Queue, ready) {
                actions = null
                controller.queueTrack(PlaybackTrackReference(selected.id))
            }
            SheetAction("Remove from queue", SpofifyGlyph.Trash, ready) {
                actions = null
                controller.remove(selected.key)
            }
        }
    }
}

@Composable
private fun QueueTrack(track: PlaybackTrack, loader: ImageLoader, onOpen: (() -> Unit)? = null, onMore: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).heightIn(min = 70.dp).then(if (onOpen == null) Modifier else Modifier.clickable(onClick = onOpen))
                .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                MusicArtwork(MusicKind.TRACK, track.id, loader)
                Column(Modifier.weight(1f)) {
                    Text(track.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 14.7.sp, lineHeight = 19.1.sp, fontWeight = FontWeight.SemiBold))
                    Text(track.artists.joinToString(", ") { it.name }, Modifier.padding(top = 3.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (onMore != null) Box(Modifier.width(40.dp)) {
                IconButton(onMore, Modifier.size(48.dp)) { SpofifyIcon(SpofifyGlyph.More, "More actions for ${track.name}") }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
