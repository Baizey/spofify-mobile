package dev.spofify.mobile.playback

import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import dev.spofify.mobile.core.library.MusicKind
import dev.spofify.mobile.core.playback.*
import dev.spofify.mobile.designsystem.*
import dev.spofify.mobile.feature.library.MusicArtwork

private data class DevicesView(
    val ready: Boolean, val devices: List<PlaybackDevice>, val deviceId: String,
    val selectedDeviceId: String?, val transferring: Boolean, val notice: String?,
)

@Composable
internal fun DevicesSheet(controller: AccountChannel, onDismiss: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle().project {
        DevicesView(!it.offline && it.connection == PlaybackConnectionState.READY, it.devices, it.deviceId,
            it.snapshot?.selectedDeviceId, it.transferPending, playbackNotice(it))
    }
    val ready = state.ready
    var renaming by remember { mutableStateOf<PlaybackDevice?>(null) }
    SpofifySheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 22.dp)) {
            Box(Modifier.padding(horizontal = 20.dp)) { SheetHeading("Playback devices") }
            state.devices.forEach { device ->
                val active = device.deviceId == state.selectedDeviceId
                val available = ready && device.available && device.canPlay
                ListRowIndication(onClick = { controller.selectDevice(device.deviceId) },
                    enabled = available, role = Role.Button) { target ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.weight(1f).heightIn(min = 80.dp).then(target)
                            .semantics { selected = active }.padding(vertical = 12.dp),
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
                }
                ListRowDivider()
            }
            val notice = when {
                state.transferring -> "Switching output"
                state.notice != null -> state.notice
                state.devices.isEmpty() -> "No connected devices"
                else -> null
            }
            if (notice != null) Text(notice, Modifier.padding(horizontal = 20.dp, vertical = if (state.devices.isEmpty()) 24.dp else 12.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    renaming?.let { device -> DeviceNameSheet(controller, device) { renaming = null } }
}

@Composable
private fun DeviceNameSheet(controller: AccountChannel, device: PlaybackDevice, onDismiss: () -> Unit) {
    val (ready, currentName) = controller.state.collectAsStateWithLifecycle().project {
        (!it.offline && it.connection == PlaybackConnectionState.READY) to it.devices.firstOrNull { row -> row.deviceId == device.deviceId }?.name
    }.value
    var name by remember(device.deviceId) { mutableStateOf(device.name) }
    var focused by remember { mutableStateOf(false) }
    val valid = isValidDeviceName(name)
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
                    enabled = valid && ready) { Text("Save") }
            }
        }
    }
}

private data class QueueView(
    val ready: Boolean, val canLoadTrack: Boolean, val tracks: List<PlaybackTrack>, val position: Int,
    val context: PlaybackGestureContext, val stateVersion: Long?,
)

@Composable
internal fun QueueSheet(controller: AppPlayback, loader: ImageLoader, onDismiss: () -> Unit) {
    val state = controller.observeState().project {
        QueueView(it.ready, it.canLoadTrack, it.queue?.queue.orEmpty(), it.queue?.position ?: -1, it.gestureContext, it.view.snapshot?.stateVersion)
    }.value
    val ready = state.ready
    val upcoming = remember(state.tracks, state.position) { state.tracks.drop((state.position + 1).coerceAtLeast(0)) }
    val list = rememberLazyListState()
    var actions by remember(controller) { mutableStateOf<Long?>(null) }
    fun editable(key: Long) = state.context.offline || isPublishedQueueKey(key)
    fun current() = controller.presentation.let { it.ready && it.gestureContext == state.context &&
        it.view.snapshot?.stateVersion == state.stateVersion && it.queue?.queue == state.tracks }
    val selected = upcoming.firstOrNull { it.key == actions && editable(it.key) }
    LaunchedEffect(state.context) { actions = null }
    SpofifySheet(onDismiss) {
        LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(), state = list,
            contentPadding = PaddingValues(bottom = 22.dp)) {
            item("heading") { Box(Modifier.padding(horizontal = 20.dp)) { SheetHeading("Queue") } }
            state.tracks.getOrNull(state.position)?.let { current -> item("current") {
                Box(Modifier.padding(horizontal = 20.dp)) { SheetGroup("Current song") }
                QueueTrack(current, loader)
            } }
            item("up-next") { Box(Modifier.padding(horizontal = 20.dp)) { SheetGroup("Up next") } }
            itemsIndexed(upcoming, key = { _, track -> track.key }) { _, track ->
                ListRowIndication(onClick = if (ready && editable(track.key)) ({ if (current()) controller.jump(track.key) }) else null) { target ->
                    // The outer layer paints the gutters; the inset face keeps its original
                    // divider, measured swipe width, action exclusion, and translation clip.
                    ListRowDivider(Modifier.align(Alignment.BottomCenter))
                    SwipeActionSurface(binding = listOf(controller, state.context, state.stateVersion, upcoming, track.key),
                        onRight = if (state.canLoadTrack) ({ if (current()) controller.queueTrack(PlaybackTrackReference(track.id)) }) else null,
                        modifier = Modifier.padding(horizontal = 20.dp),
                        onLeft = if (ready && editable(track.key)) ({ if (current()) controller.remove(track.key) }) else null,
                        slideOutOnLeft = true) {
                        QueueTrack(track, loader, target = target,
                            onMore = if (editable(track.key)) ({ actions = track.key }) else null,
                            gutter = 0.dp)
                    }
                }
            }
            if (upcoming.isEmpty()) item("empty") {
                Text("Nothing up next", Modifier.padding(horizontal = 20.dp, vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (selected != null) SpofifySheet({ actions = null }) {
        val index = upcoming.indexOfFirst { it.key == selected.key }
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 22.dp)) {
            SheetHeading(selected.name)
            SheetAction("Move up", SpofifyGlyph.Up, ready && index > 0 && upcoming.getOrNull(index - 2)?.let { editable(it.key) } != false) {
                actions = null
                if (current()) controller.moveUpcoming(selected.key, upcoming.getOrNull(index - 2)?.key)
            }
            SheetAction("Move down", SpofifyGlyph.Down, ready && index >= 0 && upcoming.getOrNull(index + 1)?.let { editable(it.key) } == true) {
                actions = null
                if (current()) controller.moveUpcoming(selected.key, upcoming[index + 1].key)
            }
            SheetAction("Queue", SpofifyGlyph.Queue, state.canLoadTrack) {
                actions = null
                if (current()) controller.queueTrack(PlaybackTrackReference(selected.id))
            }
            SheetAction("Remove from queue", SpofifyGlyph.Trash, ready) {
                actions = null
                if (current()) controller.remove(selected.key)
            }
        }
    }
}

@Composable
private fun QueueTrack(
    track: PlaybackTrack,
    loader: ImageLoader,
    target: Modifier = Modifier,
    onMore: (() -> Unit)? = null,
    gutter: Dp = 20.dp,
) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = gutter).heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).heightIn(min = 70.dp).then(target)
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
        ListRowDivider()
    }
}
