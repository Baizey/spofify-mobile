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
import coil3.ImageLoader
import dev.spofify.mobile.core.library.MusicKind
import dev.spofify.mobile.core.playback.*
import dev.spofify.mobile.designsystem.*
import dev.spofify.mobile.feature.library.MusicArtwork

private val PlayerTouchTarget = 48.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MiniPlayer(controller: AppPlayback, loader: ImageLoader, onExpand: () -> Unit, onDevices: () -> Unit) {
    val state = controller.observe()
    val online = state.online
    val queue = state.queue
    val track = queue?.current
    val ready = state.ready
    val position = queue?.position ?: -1
    val previous = queue?.queue?.getOrNull(position - 1)
    val next = queue?.queue?.getOrNull(position + 1)
    fun swipe(neighbour: PlaybackTrack?, step: Int): (() -> Unit)? {
        if (!ready || neighbour == null) return null
        return {
            val latest = controller.presentation.queue
            if (latest?.current?.key == track?.key && latest?.queue?.getOrNull(position + step)?.key == neighbour.key) {
                if (step < 0) controller.previous() else controller.next()
            }
        }
    }
    val previousSwipe = swipe(previous, -1)
    val nextSwipe = swipe(next, 1)
    val binding = listOf(controller, online?.connectionId, track?.key, previous?.key, next?.key)
    var actions by remember(controller) { mutableStateOf(false) }
    LaunchedEffect(binding) { actions = false }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (track == null) {
                    Text("No song selected", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    SwipeActionSurface(binding,
                        onRight = previousSwipe,
                        onLeft = nextSwipe,
                        modifier = Modifier.weight(1f), excludeEnd = 0.dp, slideOutOnCommit = true,
                        background = { offset, width ->
                            val neighbour = if (offset > 0) previous else next
                            if (neighbour != null) Box(Modifier.matchParentSize().graphicsLayer {
                                translationX = if (offset > 0) -width + offset else width + offset
                            }) { MiniIdentity(neighbour, loader, Modifier.fillMaxSize()) }
                        }) {
                        MiniIdentity(track, loader, Modifier.fillMaxWidth()
                            .semantics {
                                contentDescription = "Open player for ${track.name}"
                            }
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
                IconButton({ controller.toggle() }, enabled = ready && track != null, modifier = Modifier.size(PlayerTouchTarget)) {
                    val wantsPlay = queue?.desiredPlaying == true
                    SpofifyIcon(if (wantsPlay) SpofifyGlyph.Pause else SpofifyGlyph.Play,
                        if (wantsPlay) "Pause" else "Play")
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
    // The identity stays tappable when large text hides artwork, and can grow with the text.
    Row(
        modifier.heightIn(min = PlayerTouchTarget).background(MaterialTheme.colorScheme.surface).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
internal fun PlayerOutputButton(state: PlayerPresentation, onClick: () -> Unit) {
    val online = (state as? PlayerPresentation.Online)?.view
    val selected = online?.devices?.firstOrNull { it.deviceId == online.snapshot?.selectedDeviceId }
    val remote = state.isPlaying && selected != null && selected.deviceId != online.deviceId
    val output = selected?.name ?: if (state is PlayerPresentation.Local) "this phone" else "no output"
    IconButton(onClick, enabled = online != null && state.ready, modifier = Modifier.size(PlayerTouchTarget)) {
        Box(contentAlignment = Alignment.Center) {
            SpofifyIcon(SpofifyGlyph.Devices,
                "Playback devices: ${if (state.isPlaying) "playing" else "paused"} on $output",
                tint = if (remote) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            if (remote) Text("↗", Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-8).dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}
