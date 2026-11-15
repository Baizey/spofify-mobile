package dev.spofify.mobile.playback

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import dev.spofify.mobile.core.library.MusicKind
import dev.spofify.mobile.core.playback.*
import dev.spofify.mobile.feature.library.MusicArtwork

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DevicesSheet(controller: PlaybackController, onDismiss: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Playback devices", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp))
            state.devices.forEach { device ->
                val selected = device.deviceId == state.snapshot?.selectedDeviceId
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable {
                    controller.selectDevice(device.deviceId)
                }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected, onClick = { controller.selectDevice(device.deviceId) })
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(device.name)
                        if (device.deviceId == state.deviceId) Text("This phone", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else if (!device.canPlay) Text("Controller only", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (state.devices.isEmpty()) Text("No connected devices", Modifier.padding(vertical = 20.dp))
            state.snapshot?.transfer?.let {
                Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Switching output", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QueueSheet(controller: PlaybackController, loader: ImageLoader, onDismiss: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val ready = state.connection == PlaybackConnectionState.READY
    val position = state.snapshot?.state?.position ?: -1
    val upcoming = state.queue.drop((position + 1).coerceAtLeast(0))
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Queue", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton({ controller.clearUpcoming() }, enabled = ready && upcoming.isNotEmpty()) { Text("Clear") }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                state.current?.let { current -> item("current") {
                    Text("Now playing", Modifier.padding(top = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    QueueTrack(current, loader, onOpen = null) {}
                    Text("Up next", Modifier.padding(top = 24.dp, bottom = 8.dp), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                itemsIndexed(upcoming, key = { _, track -> track.key }) { index, track ->
                    var menu by remember(track.key) { mutableStateOf(false) }
                    QueueTrack(track, loader, onOpen = { if (ready) controller.jump(track.key) }) {
                        Box {
                            IconButton({ menu = true }, enabled = ready, modifier = Modifier.semantics {
                                contentDescription = "More actions for ${track.name}"
                            }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; controller.remove(track.key) })
                                DropdownMenuItem(text = { Text("Queue again") }, onClick = {
                                    menu = false
                                    controller.queueTrack(PlaybackTrackReference(track.id))
                                })
                                if (index > 0) DropdownMenuItem(text = { Text("Move up") }, onClick = {
                                    menu = false
                                    controller.moveUpcoming(track.key, upcoming.getOrNull(index - 2)?.key)
                                })
                                if (index < upcoming.lastIndex) DropdownMenuItem(text = { Text("Move down") }, onClick = {
                                    menu = false
                                    controller.moveUpcoming(track.key, upcoming[index + 1].key)
                                })
                            }
                        }
                    }
                }
                if (upcoming.isEmpty()) item("empty") { Text("Nothing queued", Modifier.padding(vertical = 24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            state.undo?.let {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Removed from queue", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton({ controller.undoRemove() }) { Text("Undo") }
                }
            }
        }
    }
}

@Composable
private fun QueueTrack(track: PlaybackTrack, loader: ImageLoader, onOpen: (() -> Unit)?, actions: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).then(if (onOpen == null) Modifier else Modifier.clickable(onClick = onOpen))
            .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            MusicArtwork(MusicKind.TRACK, track.id, loader)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(track.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(track.artists.joinToString(", ") { it.name }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        actions()
    }
}
