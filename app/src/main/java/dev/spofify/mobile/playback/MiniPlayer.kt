package dev.spofify.mobile.playback

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MiniPlayer(controller: PlaybackController, loader: ImageLoader, onExpand: () -> Unit, onDevices: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val track = state.current
    val ready = state.connection == PlaybackConnectionState.READY
    val position = state.snapshot?.state?.position ?: -1
    val previous = state.queue.getOrNull(position - 1)
    val next = state.queue.getOrNull(position + 1)
    val binding = listOf(state.sessionId, state.snapshot?.stateVersion, state.snapshot?.selectedDeviceId,
        state.snapshot?.state?.occurrence?.eventId, track?.key)
    var actions by remember { mutableStateOf(false) }
    LaunchedEffect(binding) { actions = false }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (track == null) {
                    Text("No song selected", Modifier.weight(1f).padding(vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    SwipeActionSurface(binding,
                        onRight = if (ready && previous != null) ({ controller.previous() }) else null,
                        onLeft = if (ready && next != null) ({ controller.next() }) else null,
                        modifier = Modifier.weight(1f), excludeEnd = 0.dp, slideOutOnCommit = true,
                        background = { offset, width ->
                            val neighbour = if (offset > 0) previous else next
                            if (neighbour != null) Box(Modifier.matchParentSize().graphicsLayer {
                                translationX = if (offset > 0) -width + offset else width + offset
                            }) { MiniIdentity(neighbour, loader, Modifier.fillMaxSize()) }
                        }) {
                        MiniIdentity(track, loader, Modifier.fillMaxWidth().heightIn(min = 64.dp)
                            .semantics { contentDescription = "Open player for ${track.name}" }
                            .onPreviewKeyEvent {
                                if (it.type != KeyEventType.KeyDown) false
                                else when (it.key) {
                                    Key.DirectionLeft -> if (ready && next != null) { controller.next(); true } else false
                                    Key.DirectionRight -> if (ready && previous != null) { controller.previous(); true } else false
                                    else -> false
                                }
                            }.combinedClickable(onClick = onExpand, onLongClick = { actions = true }))
                    }
                }
                PlayerOutputButton(state, onDevices)
                IconButton({ controller.toggle() }, enabled = ready && track != null, modifier = Modifier.size(48.dp)) {
                    SpofifyIcon(if (state.desiredPlaying) SpofifyGlyph.Pause else SpofifyGlyph.Play,
                        if (state.desiredPlaying) "Pause" else "Play")
                }
            }
        }
    }
    if (actions && track != null) SpofifySheet({ actions = false }) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 22.dp)) {
            SheetHeading(track.name)
            SheetAction("Next", SpofifyGlyph.Next, ready && next != null) { actions = false; controller.next() }
            SheetAction("Previous", SpofifyGlyph.Previous, ready && previous != null) { actions = false; controller.previous() }
        }
    }
}

@Composable
private fun MiniIdentity(track: PlaybackTrack, loader: ImageLoader, modifier: Modifier) {
    Row(modifier.background(MaterialTheme.colorScheme.surface).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (LocalDensity.current.fontScale < 1.5f) MusicArtwork(MusicKind.TRACK, track.id, loader, Modifier.size(40.dp))
        Column(Modifier.weight(1f)) {
            Text(track.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 12.9.sp, lineHeight = 16.77.sp, fontWeight = FontWeight.SemiBold))
            Text(track.artists.joinToString(", ") { it.name }, Modifier.padding(top = 3.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.95.sp, lineHeight = 15.33.sp))
        }
    }
}

@Composable
internal fun PlayerOutputButton(state: PlaybackView, onClick: () -> Unit) {
    val selected = state.devices.firstOrNull { it.deviceId == state.snapshot?.selectedDeviceId }
    val remote = state.isPlaying && selected != null && selected.deviceId != state.deviceId
    IconButton(onClick, enabled = state.connection == PlaybackConnectionState.READY, modifier = Modifier.size(48.dp)) {
        Box(contentAlignment = Alignment.Center) {
            SpofifyIcon(SpofifyGlyph.Devices,
                "Playback devices: ${if (state.isPlaying) "playing" else "paused"} on ${selected?.name ?: "no output"}",
                tint = if (remote) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            if (remote) Text("↗", Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-8).dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}
