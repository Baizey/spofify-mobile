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
import androidx.compose.ui.unit.Dp
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

internal data class PlayerTrackSelection(val track: PlaybackTrack, val binding: String)

internal fun PlayerPresentation.trackSelection(): PlayerTrackSelection? {
    val track = queue?.current ?: return null
    val binding = "${view.sessionId}:${view.snapshot?.outputVersion}:${view.snapshot?.selectedDeviceId}:${queue?.occurrence?.eventId}:${track.key}"
    return PlayerTrackSelection(track, binding)
}

private data class TransportState(val ready: Boolean, val wantsPause: Boolean, val shuffle: Boolean, val repeat: String)

private sealed interface PlayerOverlay {
    data object Queue : PlayerOverlay
    data object Devices : PlayerOverlay
    data class Lyrics(val trackId: String, val binding: String) : PlayerOverlay {
        fun matches(selection: PlayerTrackSelection) = trackId == selection.track.id && binding == selection.binding
    }
}

@Composable
internal fun FullPlayer(
    controller: AppPlayback,
    loader: ImageLoader,
    contentApi: TrackContentApi,
    localContent: Boolean,
    onNavigate: ((MusicKind, String) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val playback = controller.observeState()
    val selection = playback.project { it.trackSelection() }.value ?: return
    val track = selection.track
    val binding = selection.binding
    val contentModel: TrackContentViewModel = viewModel(key = "track-content-${System.identityHashCode(contentApi)}",
        factory = viewModelFactory { initializer { TrackContentViewModel(contentApi, local = localContent) } })
    val content = contentModel.state.collectAsStateWithLifecycle()
    val currentContent = content.project {
        it.takeIf { it.trackId == track.id && it.binding == binding } ?: TrackContentState()
    }
    LaunchedEffect(contentModel, binding, track.id) { contentModel.select(track.id, binding) }
    DisposableEffect(contentModel) { onDispose { contentModel.select(null, "") } }

    var overlay by remember(controller, contentApi) { mutableStateOf<PlayerOverlay?>(null) }
    LaunchedEffect(binding, track.id) {
        if ((overlay as? PlayerOverlay.Lyrics)?.matches(selection) == false) overlay = null
    }
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
                    modifier = Modifier.fillMaxWidth().verticalScroll(scroll)
                        .padding(start = horizontal, end = horizontal, top = 16.dp, bottom = 18.dp)
                        .semantics { customActions = listOf(CustomAccessibilityAction("Dismiss player") { onDismiss(); true }) },
                    header = {
                        val canOpenAlbum = onNavigate != null && track.albumId.isNotBlank()
                        val albumLabel = track.albumName.ifBlank { if (canOpenAlbum) "Open album" else "" }
                        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .then(if (canOpenAlbum) Modifier.clickable(role = Role.Button, onClickLabel = "Open album") {
                                onNavigate(MusicKind.ALBUM, track.albumId)
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
                        FullPlayerLyricEntry(playback, currentContent, height, dismissGesture) {
                            overlay = PlayerOverlay.Lyrics(track.id, binding)
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
                        val seekContext = playback.project { it.gestureContext }.value
                        PlaybackSeek(playback, seekContext, currentContent) { controller.seek(seekContext, it) }
                        FullPlayerTransport(playback, controller)
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                FullPlayerOutput(playback) { overlay = PlayerOverlay.Devices }
                            }
                            Spacer(Modifier.weight(3f))
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                IconButton({ overlay = PlayerOverlay.Queue }, Modifier.size(48.dp)) { SpofifyIcon(SpofifyGlyph.Queue, "See queue") }
                            }
                        }
                        FullPlayerNotice(playback)
                    }
                })
            }
        }
        when (val panel = overlay) {
            PlayerOverlay.Queue -> QueueSheet(controller, loader) { overlay = null }
            PlayerOverlay.Devices -> DevicesSheet(controller.controller) { overlay = null }
            is PlayerOverlay.Lyrics -> if (panel.matches(selection)) {
                PlayerLyricsSheet(track, currentContent, playback) { overlay = null }
            }
            null -> Unit
        }
    }
}

@Composable
private fun FullPlayerLyricEntry(
    playback: State<PlayerPresentation>, content: State<TrackContentState>, height: Dp,
    dismissGesture: Modifier, onOpen: () -> Unit,
) {
    val entry by content.project(::lyricEntry)
    val line by playback.project {
        val current = content.value
        if (lyricEntry(current) == LyricEntry.AVAILABLE) {
            lyricViewportKey(current, (it.elapsed * 1000).toLong(), (it.duration * 1000).toLong())
        } else null
    }
    val currentLine = line
    when {
        currentLine != null -> Column(Modifier.fillMaxWidth().height(height)) {
            PlayerLyricViewport(currentLine, (height - 8.dp).coerceAtLeast(0.dp), onOpen)
            Spacer(Modifier.height(8.dp))
        }
        entry == LyricEntry.HIDDEN -> Spacer(Modifier.fillMaxWidth().height(height).then(dismissGesture))
        else -> Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
            if (entry == LyricEntry.AVAILABLE) TextButton(onOpen) { Text("View lyrics") }
            else Text("Lyrics unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FullPlayerTransport(playback: State<PlayerPresentation>, controller: AppPlayback) {
    val state by playback.project { TransportState(it.ready, it.wantsPause, it.queue?.shuffle == true, it.queue?.repeat ?: "off") }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TransportSlot(Modifier.weight(1f), "Shuffle", SpofifyGlyph.Shuffle, state.ready, active = state.shuffle) { controller.toggleShuffle() }
        TransportSlot(Modifier.weight(1f), "Previous song", SpofifyGlyph.Previous, state.ready) { controller.previous() }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            FilledIconButton({ controller.toggle() }, Modifier.size(64.dp), enabled = state.ready, shape = CircleShape) {
                SpofifyIcon(if (state.wantsPause) SpofifyGlyph.Pause else SpofifyGlyph.Play,
                    if (state.wantsPause) "Pause" else "Play")
            }
        }
        TransportSlot(Modifier.weight(1f), "Next song", SpofifyGlyph.Next, state.ready) { controller.next() }
        val repeat = state.repeat
        TransportSlot(Modifier.weight(1f), "Repeat ${if (repeat == "all") "queue" else repeat}",
            if (repeat == "one") SpofifyGlyph.RepeatOne else SpofifyGlyph.Repeat, state.ready,
            active = repeat != "off") { controller.cycleRepeat() }
    }
}

@Composable
private fun FullPlayerOutput(playback: State<PlayerPresentation>, onOpen: () -> Unit) {
    val output by playback.project { it.outputState() }
    PlayerOutputButton(output, onOpen)
}

@Composable
private fun FullPlayerNotice(playback: State<PlayerPresentation>) {
    val notice = playback.project { it.error }.value ?: return
    SelectionContainer {
        Text(notice, Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
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
