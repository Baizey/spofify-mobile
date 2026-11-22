package dev.spofify.mobile.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.ImageLoader
import dev.spofify.mobile.core.content.TrackContentApi
import dev.spofify.mobile.core.library.MusicKind
import dev.spofify.mobile.core.playback.*
import dev.spofify.mobile.designsystem.*
import dev.spofify.mobile.feature.library.MusicArtwork
import kotlin.math.abs
import kotlin.math.max

@Composable
internal fun FullPlayer(
    controller: AppPlayback,
    loader: ImageLoader,
    contentApi: TrackContentApi,
    onNavigate: ((MusicKind, String) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val state = controller.observe()
    val track = state.queue?.current ?: return
    val online = (state as? PlayerPresentation.Online)?.view
    val contentModel: TrackContentViewModel = viewModel(key = "track-content-${System.identityHashCode(contentApi)}",
        factory = viewModelFactory { initializer { TrackContentViewModel(contentApi, local = controller is AppPlayback.Local) } })
    val content by contentModel.state.collectAsStateWithLifecycle()
    val binding = "${online?.sessionId}:${online?.snapshot?.outputVersion}:${online?.snapshot?.selectedDeviceId}:${online?.snapshot?.state?.occurrence?.eventId}:${track.key}"
    LaunchedEffect(binding, track.id) { contentModel.select(track.id, binding) }
    DisposableEffect(contentModel) { onDispose { contentModel.select(null, "") } }
    val currentContent = content.takeIf { it.trackId == track.id && it.binding == binding } ?: TrackContentState()
    val elapsedMs = (state.elapsed * 1000).toLong()
    val durationMs = (state.duration * 1000).toLong()
    val lyricsEntry = lyricEntry(currentContent)
    val inlineLine = if (lyricsEntry == LyricEntry.AVAILABLE) lyricViewportKey(currentContent, elapsedMs, durationMs) else null
    val ready = state.ready
    var queue by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf(false) }
    var lyrics by remember { mutableStateOf(false) }
    LaunchedEffect(binding) { lyrics = false }
    val scroll = rememberScrollState()
    val dismissWithoutScrolling by remember(scroll) {
        derivedStateOf { scroll.maxValue == 0 && scroll.value == 0 }
    }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        SpofifyDialogSystemBars()
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                val density = LocalDensity.current
                val largeText = density.fontScale >= 1.5f
                val horizontal = if (largeText) 16.dp else 20.dp
                val dismissGesture = Modifier.playerDismissGesture(binding, dismissWithoutScrolling, onDismiss)
                PlayerBodyLayout(
                    availableHeight = (maxHeight - 34.dp).coerceAtLeast(0.dp),
                    largeText = largeText,
                    reserveLyrics = lyricsEntry != LyricEntry.HIDDEN,
                    modifier = Modifier.fillMaxWidth().verticalScroll(scroll)
                        .padding(start = horizontal, end = horizontal, top = 16.dp, bottom = 18.dp)
                        .semantics { customActions = listOf(CustomAccessibilityAction("Dismiss player") { onDismiss(); true }) },
                    header = {
                        val canOpenAlbum = onNavigate != null && track.albumId.isNotBlank()
                        val albumLabel = track.albumName.ifBlank { if (canOpenAlbum) "Open album" else "" }
                        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .then(if (canOpenAlbum) Modifier.clickable(role = Role.Button, onClickLabel = "Open album") {
                                onNavigate?.invoke(MusicKind.ALBUM, track.albumId)
                            } else Modifier), contentAlignment = Alignment.CenterStart) {
                            if (albumLabel.isNotBlank()) Text(albumLabel,
                                style = MaterialTheme.typography.bodyLarge.merge(playerSupportingTextStyle),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    artwork = { size ->
                        // Keep the swipe region's original corners; MusicArtwork paints the shared tile.
                        MusicArtwork(MusicKind.TRACK, track.id, loader,
                            Modifier.size(size).clip(RoundedCornerShape(7.dp)).then(dismissGesture))
                    },
                    gap = { height ->
                        when {
                            inlineLine != null -> Column(Modifier.fillMaxWidth().height(height)) {
                                PlayerLyricViewport(inlineLine, (height - 8.dp).coerceAtLeast(0.dp)) { lyrics = true }
                                Spacer(Modifier.height(8.dp))
                            }
                            lyricsEntry != LyricEntry.HIDDEN -> Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
                                if (lyricsEntry == LyricEntry.AVAILABLE) {
                                    TextButton({ lyrics = true }) { Text("View lyrics") }
                                } else {
                                    Text("Lyrics unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            else -> Spacer(Modifier.fillMaxWidth().height(height).then(dismissGesture))
                        }
                    },
                    controls = { Column(Modifier.fillMaxWidth()) {
                        val artist = track.artists.firstOrNull { !it.id.isNullOrBlank() }
                        val credit = track.artists.joinToString(", ") { it.name }
                        Column(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .then(if (artist != null && onNavigate != null) Modifier.clickable(
                                role = Role.Button, onClickLabel = "Open artist") {
                                onNavigate(MusicKind.ARTIST, requireNotNull(artist.id))
                            } else Modifier)) {
                            Text(track.name, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 22.5.sp,
                                lineHeight = 26.1.sp, fontWeight = FontWeight.Bold))
                            if (credit.isNotBlank()) Text(credit, Modifier.fillMaxWidth().padding(top = 2.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyLarge.merge(playerSupportingTextStyle))
                        }
                        PlaybackSeek(state, binding, currentContent) { controller.seek(it) }
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            TransportSlot(Modifier.weight(1f), "Shuffle", SpofifyGlyph.Shuffle, ready,
                                active = state.queue?.shuffle == true) { controller.toggleShuffle() }
                            TransportSlot(Modifier.weight(1f), "Previous song", SpofifyGlyph.Previous, ready) { controller.previous() }
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                FilledIconButton({ controller.toggle() }, Modifier.size(64.dp), enabled = ready, shape = CircleShape) {
                                    SpofifyIcon(if (state.wantsPause) SpofifyGlyph.Pause else SpofifyGlyph.Play,
                                        if (state.wantsPause) "Pause" else "Play")
                                }
                            }
                            TransportSlot(Modifier.weight(1f), "Next song", SpofifyGlyph.Next, ready) { controller.next() }
                            val repeat = state.queue?.repeat ?: "off"
                            TransportSlot(Modifier.weight(1f), "Repeat ${if (repeat == "all") "queue" else repeat}",
                                if (repeat == "one") SpofifyGlyph.RepeatOne else SpofifyGlyph.Repeat, ready,
                                active = repeat != "off") { controller.cycleRepeat() }
                        }
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { PlayerOutputButton(state) { devices = true } }
                            Spacer(Modifier.weight(3f))
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                IconButton({ queue = true }, Modifier.size(48.dp)) { SpofifyIcon(SpofifyGlyph.Queue, "See queue") }
                            }
                        }
                        state.error?.let { notice ->
                            SelectionContainer {
                                Text(notice, Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                })
            }
        }
        if (queue) QueueSheet(controller, loader) { queue = false }
        if (devices && controller is AppPlayback.Online) DevicesSheet(controller.controller) { devices = false }
        if (lyrics && lyricsEntry == LyricEntry.AVAILABLE) {
            PlayerLyricsSheet(track, currentContent, elapsedMs, durationMs) { lyrics = false }
        }
    }
}

@Composable
private fun TransportSlot(modifier: Modifier, label: String, glyph: SpofifyGlyph, enabled: Boolean, active: Boolean = false, onClick: () -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) {
        IconButton(onClick, Modifier.size(48.dp).semantics { if (label == "Shuffle") stateDescription = if (active) "On" else "Off" }, enabled = enabled) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                SpofifyIcon(glyph, label, tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                if (active) Box(Modifier.align(Alignment.BottomCenter).size(4.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            }
        }
    }
}

private fun Modifier.playerDismissGesture(binding: String, eligible: Boolean, onDismiss: () -> Unit): Modifier =
    pointerInput(binding, eligible) {
        if (!eligible) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var peak = 0f
            while (true) {
                val event = awaitPointerEvent()
                val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                val dx = pointer.position.x - down.position.x
                val dy = pointer.position.y - down.position.y
                if (pointer.isConsumed || event.changes.count { it.pressed } > 1 || dy < -12.dp.toPx() ||
                    (abs(dx) > 12.dp.toPx() && abs(dx) > abs(dy) / 1.5f) ||
                    (peak > 24.dp.toPx() && dy < peak - 20.dp.toPx())) break
                peak = max(peak, dy)
                if (dy > 16.dp.toPx() && dy > abs(dx) * 1.5f) pointer.consume()
                if (!pointer.pressed) {
                    if (dy >= 72.dp.toPx() && peak - dy <= 20.dp.toPx() && abs(dx) < 40.dp.toPx() && dy > abs(dx) * 1.5f) onDismiss()
                    break
                }
            }
        }
    }
