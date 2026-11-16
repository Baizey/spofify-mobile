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
import dev.spofify.mobile.core.content.LyricsState
import dev.spofify.mobile.core.content.TrackContentApi
import dev.spofify.mobile.core.library.MusicKind
import dev.spofify.mobile.core.playback.*
import dev.spofify.mobile.designsystem.*
import dev.spofify.mobile.feature.library.MusicArtwork
import kotlin.math.abs
import kotlin.math.max

@Composable
internal fun FullPlayer(
    controller: PlaybackController,
    loader: ImageLoader,
    contentApi: TrackContentApi,
    onNavigate: ((MusicKind, String) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val track = state.current ?: return
    val contentModel: TrackContentViewModel = viewModel(key = "track-content-${System.identityHashCode(contentApi)}",
        factory = viewModelFactory { initializer { TrackContentViewModel(contentApi) } })
    val content by contentModel.state.collectAsStateWithLifecycle()
    val binding = "${state.sessionId}:${state.snapshot?.outputVersion}:${state.snapshot?.selectedDeviceId}:${state.snapshot?.state?.occurrence?.eventId}:${track.key}"
    LaunchedEffect(binding, track.id) { contentModel.select(track.id, binding) }
    DisposableEffect(contentModel) { onDispose { contentModel.select(null, "") } }
    val currentContent = content.takeIf { it.trackId == track.id && it.binding == binding } ?: TrackContentState()
    val actual = state.playback?.takeIf { it.hasReportedPlayback }
    val elapsedMs = ((actual?.elapsed ?: 0.0) * 1000).toLong()
    val durationMs = ((actual?.duration ?: 0.0) * 1000).toLong()
    val inlineLine = lyricViewportKey(currentContent, elapsedMs, durationMs)
    val inline = inlineLine?.text
    val ready = state.connection == PlaybackConnectionState.READY
    var queue by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var lyrics by remember { mutableStateOf(false) }
    LaunchedEffect(binding) { more = false; lyrics = false }
    val scroll = rememberScrollState()
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                val density = LocalDensity.current
                val largeText = density.fontScale >= 1.5f
                val horizontal = if (largeText) 16.dp else 20.dp
                val dismissGesture = Modifier.playerDismissGesture(binding, scroll.maxValue == 0 && scroll.value == 0, onDismiss)
                PlayerBodyLayout(
                    availableHeight = (maxHeight - 34.dp).coerceAtLeast(0.dp),
                    largeText = largeText,
                    reserveLyrics = currentContent.lyrics?.state == LyricsState.TIMED,
                    modifier = Modifier.fillMaxWidth().verticalScroll(scroll)
                        .padding(start = horizontal, end = horizontal, top = 16.dp, bottom = 18.dp)
                        .semantics { customActions = listOf(CustomAccessibilityAction("Dismiss player") { onDismiss(); true }) },
                    header = {
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            val canOpenAlbum = onNavigate != null && track.albumId.isNotBlank() && track.albumName.isNotBlank()
                            Box(Modifier.weight(1f).heightIn(min = 48.dp)
                                .then(if (canOpenAlbum) Modifier.clickable(role = Role.Button, onClickLabel = "Open album") {
                                    onNavigate?.invoke(MusicKind.ALBUM, track.albumId)
                                } else Modifier), contentAlignment = Alignment.CenterStart) {
                                if (track.albumName.isNotBlank()) Text(track.albumName,
                                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 13.2.sp, lineHeight = 17.82.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton({ more = true }, Modifier.size(48.dp)) {
                                SpofifyIcon(SpofifyGlyph.More, "More for this song")
                            }
                        }
                    },
                    artwork = { size ->
                        MusicArtwork(MusicKind.TRACK, track.id, loader,
                            Modifier.size(size).clip(RoundedCornerShape(7.dp)).then(dismissGesture))
                    },
                    gap = { height ->
                        if (inlineLine == null) Spacer(Modifier.fillMaxWidth().height(height).then(dismissGesture))
                        else Column(Modifier.fillMaxWidth().height(height)) {
                            PlayerLyricViewport(inlineLine, (height - 8.dp).coerceAtLeast(0.dp)) { lyrics = true }
                            Spacer(Modifier.height(8.dp))
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
                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 13.2.sp, lineHeight = 17.82.sp))
                        }
                        PlaybackSeek(state, currentContent) { controller.seek(it) }
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            TransportSlot(Modifier.weight(1f), "Shuffle", SpofifyGlyph.Shuffle, ready,
                                active = state.snapshot?.state?.shuffle == true) { controller.toggleShuffle() }
                            TransportSlot(Modifier.weight(1f), "Previous song", SpofifyGlyph.Previous, ready) { controller.previous() }
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                FilledIconButton({ controller.toggle() }, Modifier.size(64.dp), enabled = ready, shape = CircleShape) {
                                    SpofifyIcon(if (state.desiredPlaying) SpofifyGlyph.Pause else SpofifyGlyph.Play,
                                        if (state.desiredPlaying) "Pause" else "Play")
                                }
                            }
                            TransportSlot(Modifier.weight(1f), "Next song", SpofifyGlyph.Next, ready) { controller.next() }
                            val repeat = state.snapshot?.state?.repeat ?: "off"
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
                        playbackNotice(state)?.let { notice ->
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
        if (devices) DevicesSheet(controller) { devices = false }
        if (more) SpofifySheet({ more = false }) {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 22.dp)) {
                SheetHeading(track.name)
                if (inline == null) SheetAction("Lyrics", SpofifyGlyph.Lyrics) { more = false; lyrics = true }
                SheetAction("Open album", SpofifyGlyph.Album, onNavigate != null && track.albumId.isNotBlank()) {
                    more = false
                    onNavigate?.invoke(MusicKind.ALBUM, track.albumId)
                }
            }
        }
        if (lyrics) PlayerLyricsSheet(track, currentContent, elapsedMs, durationMs, contentModel::refresh) { lyrics = false }
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
