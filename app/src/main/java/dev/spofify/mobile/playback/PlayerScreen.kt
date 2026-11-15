package dev.spofify.mobile.playback

import android.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import dev.spofify.mobile.core.library.MusicKind
import dev.spofify.mobile.core.playback.*
import dev.spofify.mobile.feature.library.MusicArtwork
import dev.spofify.mobile.feature.library.duration

@Composable
internal fun MiniPlayer(controller: PlaybackController, loader: ImageLoader, onExpand: () -> Unit, onDevices: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val track = state.current ?: return
    val ready = state.connection == PlaybackConnectionState.READY
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onExpand, Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) {
                MusicArtwork(MusicKind.TRACK, track.id, loader, Modifier.size(44.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(track.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
                    Text(track.artists.joinToString(", ") { it.name }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            IconButton(onDevices, modifier = Modifier.semantics { contentDescription = "Playback devices" }) {
                val remote = state.snapshot?.selectedDeviceId?.let { it.isNotEmpty() && it != state.deviceId } == true
                Text(if (remote) "↗" else "▣", color = if (remote) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface)
            }
            MediaButton(if (state.desiredPlaying) "Pause" else "Play",
                if (state.desiredPlaying) R.drawable.ic_media_pause else R.drawable.ic_media_play, ready) { controller.toggle() }
        }
    }
}

@Composable
internal fun FullPlayer(controller: PlaybackController, loader: ImageLoader, onDismiss: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val track = state.current ?: return
    val ready = state.connection == PlaybackConnectionState.READY
    var queue by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf(false) }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                IconButton(onDismiss) { Icon(painterResource(R.drawable.arrow_down_float), "Dismiss player") }
                MusicArtwork(MusicKind.TRACK, track.id, loader,
                    Modifier.widthIn(max = 330.dp).fillMaxWidth().aspectRatio(1f).align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(36.dp))
                Text(track.name, style = MaterialTheme.typography.headlineSmall)
                Text(track.artists.joinToString(", ") { it.name }, Modifier.padding(top = 6.dp, bottom = 20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                PlaybackSeek(state) { controller.seek(it) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ controller.toggleShuffle() }, enabled = ready,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
                            contentDescription = "Shuffle"
                            stateDescription = if (state.snapshot?.state?.shuffle == true) "On" else "Off"
                        }) { Text("⇄", color = if (state.snapshot?.state?.shuffle == true)
                            MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
                    MediaButton("Previous song", R.drawable.ic_media_previous, ready) { controller.previous() }
                    FilledIconButton({ controller.toggle() }, enabled = ready, modifier = Modifier.size(64.dp)) {
                        Icon(painterResource(if (state.desiredPlaying) R.drawable.ic_media_pause else R.drawable.ic_media_play),
                            if (state.desiredPlaying) "Pause" else "Play", Modifier.size(28.dp))
                    }
                    MediaButton("Next song", R.drawable.ic_media_next, ready) { controller.next() }
                    TextButton({ controller.cycleRepeat() }, enabled = ready,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
                            contentDescription = "Repeat"
                            stateDescription = state.snapshot?.state?.repeat ?: "off"
                        }) { Text(if (state.snapshot?.state?.repeat == "one") "↻1" else "↻",
                            color = if (state.snapshot?.state?.repeat != "off") MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton({ devices = true }, enabled = ready) {
                        Text(state.devices.firstOrNull { it.deviceId == state.snapshot?.selectedDeviceId }?.name ?: "Output")
                    }
                    TextButton({ queue = true }) { Text("Queue") }
                }
                if (state.playback?.isBuffering == true || state.playback?.isLoading == true || state.snapshot?.transfer != null)
                    CircularProgressIndicator(Modifier.padding(16.dp).size(24.dp))
                state.error?.let { Text(it, Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(24.dp))
            }
        }
        if (queue) QueueSheet(controller, loader) { queue = false }
        if (devices) DevicesSheet(controller) { devices = false }
    }
}

@Composable
private fun PlaybackSeek(state: PlaybackView, onSeek: (Double) -> Unit) {
    val facts = state.playback
    val seconds = facts?.takeIf { it.hasReportedPlayback }?.elapsed ?: 0.0
    val total = facts?.takeIf { it.hasReportedPlayback }?.duration ?: 0.0
    var preview by remember(state.current?.key, state.snapshot?.state?.occurrence?.eventId) { mutableStateOf<Float?>(null) }
    Column {
        Slider(value = preview ?: seconds.toFloat().coerceIn(0f, total.toFloat().coerceAtLeast(1f)),
            onValueChange = { preview = it },
            valueRange = 0f..total.toFloat().coerceAtLeast(1f),
            enabled = state.connection == PlaybackConnectionState.READY && total > 0,
            modifier = Modifier.semantics { contentDescription = "Seek playback" },
            onValueChangeFinished = { preview?.let { onSeek(it.toDouble()) }; preview = null })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(duration(((preview?.toDouble() ?: seconds) * 1000).toLong()), style = MaterialTheme.typography.labelSmall)
            Text(duration((total * 1000).toLong()), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun MediaButton(label: String, drawable: Int, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick, enabled = enabled, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
        Icon(painterResource(drawable), label, Modifier.size(24.dp))
    }
}
