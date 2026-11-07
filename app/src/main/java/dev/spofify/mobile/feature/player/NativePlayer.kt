package dev.spofify.mobile.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.Player
import kotlin.math.roundToInt
import dev.spofify.mobile.LikeButton
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.playback.PlaybackControls
import dev.spofify.mobile.playback.PlaybackState

/** UI only: expanding does not select, prepare, reconnect, or restart playback. */
@Composable
fun NativeMiniPlayer(
    state: PlaybackState,
    controls: PlaybackControls,
    onExpand: () -> Unit,
    artwork: @Composable (LibraryItem, Modifier) -> Unit,
    modifier: Modifier = Modifier,
    onPlaybackDevices: (() -> Unit)? = null,
) {
    val item = state.item
    if (item == null) {
        Surface(modifier = modifier.fillMaxWidth(), shape = RectangleShape) {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("No song selected", Modifier.weight(1f).padding(8.dp),
                    maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PlaybackDevicesButton(onPlaybackDevices)
                        PlayPauseButton(PlaybackState(), {}, enabled = false)
                    }
                }
            }
        }
        return
    }
    val key = state.transportInteractionKey
    // A new token on any observed identity/capability/controller transition also fences A→B→A
    // recompositions. Progress, artwork and buffering alone leave a held gesture intact.
    val transportToken = remember(key, state.canSkipNext, state.canSkipPrevious, controls) { Any() }
    val currentToken = rememberUpdatedState(transportToken)
    val currentExpand = rememberUpdatedState(onExpand)
    var mounted by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { mounted = false } }
    val tapGuard = remember { MiniPlayerTapGuard() }
    val transport: (PlayerSwipeAction) -> Boolean = { action ->
        if (!mounted || key == null || currentToken.value !== transportToken) false
        else when (action) {
            PlayerSwipeAction.NEXT -> state.canSkipNext && controls.next(key)
            PlayerSwipeAction.PREVIOUS -> state.canSkipPrevious && controls.previous(key)
            else -> false
        }
    }
    Surface(modifier = modifier.fillMaxWidth(), shape = RectangleShape) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).testTag("mini-player-identity")
                        .miniPlayerSwipe(tapGuard) {
                            val transportAtDown = transport
                            val dispatch: (PlayerSwipeAction) -> Unit = { action ->
                                when (action) {
                                    PlayerSwipeAction.EXPAND -> currentExpand.value()
                                    PlayerSwipeAction.NEXT, PlayerSwipeAction.PREVIOUS -> { transportAtDown(action) }
                                    else -> Unit
                                }
                            }
                            dispatch
                        }
                        .semantics {
                            stateDescription = buildList {
                                if (key != null && state.canSkipNext) add("Swipe left for Next song")
                                if (key != null && state.canSkipPrevious) add("Swipe right for Previous song")
                                add("Swipe up to expand")
                            }.joinToString("; ")
                            customActions = buildList {
                                if (key != null && state.canSkipNext) add(CustomAccessibilityAction("Next song") {
                                    transport(PlayerSwipeAction.NEXT)
                                })
                                if (key != null && state.canSkipPrevious) add(CustomAccessibilityAction("Previous song") {
                                    transport(PlayerSwipeAction.PREVIOUS)
                                })
                            }
                        }
                        .clickable(role = Role.Button, onClickLabel = "Expand Now Playing") {
                            if (tapGuard.allowClick) onExpand()
                        }
                        .heightIn(min = 64.dp).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    artwork(item, Modifier.size(48.dp))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall)
                        Text(item.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PlaybackDevicesButton(onPlaybackDevices)
                        PlayPauseButton(state, controls::toggle)
                    }
                }
            }
            LinearProgressIndicator(
                progress = { if (state.durationMs > 0)
                    (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp)
                    .semantics { contentDescription = "Playback progress" },
                strokeCap = StrokeCap.Butt,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}

/** Dialog-owned presentation only; Back dismisses a disclosed queue before the player itself. */
class NowPlayingPresentationState {
    var queueOpen by mutableStateOf(false)
        private set

    fun openQueue() { queueOpen = true }
    fun dismissQueue(): Boolean {
        if (!queueOpen) return false
        queueOpen = false
        return true
    }
}

/** Production window and Back path, shared with focused Dialog-dispatch regression tests. */
@Composable
fun NativeNowPlayingDialog(onCollapse: () -> Unit, content: @Composable (NowPlayingPresentationState) -> Unit) {
    val presentation = remember { NowPlayingPresentationState() }
    Dialog(onDismissRequest = { if (!presentation.dismissQueue()) onCollapse() }, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
    )) { content(presentation) }
}

/** Finite artwork-first viewport. Only genuine overflow scrolls; queue is a separate window. */
@Composable
fun NativeNowPlaying(
    state: PlaybackState,
    controls: PlaybackControls,
    liked: Boolean?,
    likePending: Boolean,
    likeFailed: Boolean,
    onToggleLike: () -> Unit,
    onRetryLike: () -> Unit,
    onAddToPlaylist: (LibraryItem) -> Unit,
    onOpenItem: (LibraryItem) -> Unit,
    artwork: @Composable (LibraryItem, Modifier) -> Unit,
    modifier: Modifier = Modifier,
    presentation: NowPlayingPresentationState = remember { NowPlayingPresentationState() },
    onPlaybackDevices: (() -> Unit)? = null,
    selectedOutputLabel: String = "No output selected",
    queueFeedback: @Composable () -> Unit = {},
) {
    val item = state.item ?: return
    BoxWithConstraints(modifier.fillMaxSize().testTag("now-playing")
        .semantics { paneTitle = "Now Playing" }.padding(horizontal = 16.dp, vertical = 8.dp)) {
        // The host, not artwork, progress or queue length, supplies this finite height.
        val viewport = maxHeight
        val scroll = rememberScrollState()
        Layout(
            modifier = Modifier.fillMaxSize().verticalScroll(scroll).testTag("now-playing-main-view"),
            content = {
                Text(state.context?.let { "Playing from ${it.name}" } ?: "Now Playing",
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.testTag("now-playing-artwork").playerSwipe(PlayerSwipeArea.COVER) { action ->
                    when (action) {
                        PlayerSwipeAction.NEXT -> controls.next()
                        PlayerSwipeAction.PREVIOUS -> controls.previous()
                        else -> Unit
                    }
                }) { artwork(item, Modifier.fillMaxSize()) }
                Column(Modifier.fillMaxWidth().testTag("now-playing-essential")) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).heightIn(min = 48.dp)
                            .clickable(role = Role.Button, onClickLabel = "Open song details") { onOpenItem(item) }
                            .padding(vertical = 4.dp)) {
                            Text(item.name, style = MaterialTheme.typography.titleMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(item.subtitle, color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        LikeButton(liked, likePending, likeFailed, onToggleLike, onRetryLike)
                        PlayerMore(state, controls, onAddToPlaylist, onOpenItem)
                    }
                    PlaybackSeekBar(state, controls)
                    CenteredTransport(state, controls)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).heightIn(min = 48.dp)
                            .semantics { contentDescription = "Playback devices" }
                            .then(if (onPlaybackDevices != null) Modifier.clickable(
                                role = Role.Button, onClickLabel = "Choose playback output", onClick = onPlaybackDevices
                            ) else Modifier)
                            .padding(end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Devices, null, Modifier.size(24.dp))
                            Text(selectedOutputLabel, Modifier.padding(start = 8.dp),
                                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = presentation::openQueue,
                            modifier = Modifier.size(48.dp).testTag("now-playing-show-queue")) {
                            Icon(Icons.AutoMirrored.Filled.QueueMusic, "Queue")
                        }
                    }
                }
            },
        ) { children, constraints ->
            val width = constraints.maxWidth
            val unbounded = Constraints(minWidth = width, maxWidth = width)
            val heading = children[0].measure(unbounded)
            val essential = children[2].measure(unbounded)
            val available = (viewport.roundToPx() - heading.height - essential.height - 24.dp.roundToPx()).coerceAtLeast(0)
            val side = minOf(width, 360.dp.roundToPx(), available).let { if (it < 64.dp.roundToPx()) 0 else it }
            val cover = children[1].measure(Constraints.fixed(side, side))
            val height = maxOf(viewport.roundToPx(), heading.height + side + essential.height)
            layout(width, height) {
                heading.placeRelative(0, 0)
                cover.placeRelative((width - side) / 2, heading.height)
                essential.placeRelative(0, height - essential.height)
            }
        }
    }
    if (presentation.queueOpen) {
        Dialog(onDismissRequest = { presentation.dismissQueue() }, properties = DialogProperties(
            usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
        )) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 8.dp, vertical = 8.dp).testTag("now-playing-queue-view")
                    .semantics { paneTitle = "Queue" }) {
                    // Existing queue owns occurrence actions, reorder/removal/Undo and its sole scroller.
                    NativeUpcomingQueue(state, controls, onOpenItem, artwork, showStatus = false)
                    // Same presentation owner/timer as the player; never hide queue action results
                    // behind this opaque window or cover the last occurrence's controls.
                    queueFeedback()
                }
            }
        }
    }
}

/** Five equal cells make Play/Pause the geometric center, including RTL and narrow layouts. */
@Composable
private fun CenteredTransport(state: PlaybackState, controls: PlaybackControls) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Layout(modifier = Modifier.fillMaxWidth().testTag("now-playing-controls"), content = {
            val buttons: List<@Composable () -> Unit> = listOf(
                {
                    IconButton(onClick = controls::toggleShuffle, modifier = Modifier.size(48.dp)
                        .semantics { stateDescription = if (state.shuffle) "On" else "Off" }) {
                        Icon(Icons.Default.Shuffle, "Shuffle",
                            tint = if (state.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                },
                { IconButton(onClick = controls::previous, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.SkipPrevious, "Previous song") } },
                { PlayPauseButton(state, controls::toggle, large = true) },
                { IconButton(onClick = controls::next, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.SkipNext, "Next song") } },
                {
                    val repeat = when (state.repeatMode) {
                        Player.REPEAT_MODE_ONE -> "One song"
                        Player.REPEAT_MODE_ALL -> "All songs"
                        else -> "Off"
                    }
                    IconButton(onClick = controls::cycleRepeat, modifier = Modifier.size(48.dp)
                        .semantics { stateDescription = repeat }) {
                        Icon(if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            "Repeat", tint = if (state.repeatMode != Player.REPEAT_MODE_OFF)
                                MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                },
            )
            buttons.forEach { button -> Box(contentAlignment = Alignment.Center) { button() } }
        }) { children, constraints ->
            val width = constraints.maxWidth
            // Preserve an even, circular 64dp Play target, rather than constraining it to an
            // odd rounded cell width. At 320dp the five centers remain disjoint (56dp minimum
            // center distance needed between the 64dp center and its 48dp neighbors).
            val measured = children.map { it.measure(Constraints(maxWidth = 64.dp.roundToPx(), maxHeight = 64.dp.roundToPx())) }
            val height = measured.maxOf { it.height }
            layout(width, height) {
                measured.forEachIndexed { index, child ->
                    // Round positions from the complete row, not cumulative rounded cell widths.
                    val center = width * (index + .5f) / 5f
                    child.place((center - child.width / 2f).roundToInt(), (height - child.height) / 2)
                }
            }
        }
    }
}

/** Secondary capabilities retain every real credit ID without making transport scroll away. */
@Composable
private fun PlayerMore(
    state: PlaybackState,
    controls: PlaybackControls,
    onAddToPlaylist: (LibraryItem) -> Unit,
    onOpenItem: (LibraryItem) -> Unit,
) {
    val item = state.item ?: return
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.MoreVert, "Now Playing actions")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.context?.let { context ->
                DropdownMenuItem(text = { Text("Playing from ${context.name}") },
                    onClick = { expanded = false; onOpenItem(context) })
            }
            item.artists.forEach { credit ->
                DropdownMenuItem(text = { Text(credit.name) }, enabled = credit.id.isNotBlank(),
                    onClick = { expanded = false; onOpenItem(LibraryItem("artist", credit.id, credit.name)) })
            }
            if (!item.albumId.isNullOrBlank() || !item.albumName.isNullOrBlank()) {
                val name = item.albumName?.takeIf { it.isNotBlank() } ?: "Open album"
                DropdownMenuItem(text = { Text(name) }, enabled = !item.albumId.isNullOrBlank(),
                    onClick = { expanded = false; onOpenItem(LibraryItem("album", item.albumId!!, name, artists = item.artists)) })
            }
            DropdownMenuItem(text = { Text("Add to playlist") },
                onClick = { expanded = false; onAddToPlaylist(item) })
            DropdownMenuItem(text = { Text(if (state.shuffle) "Shuffle: on" else "Shuffle: off") },
                leadingIcon = { Icon(Icons.Default.Shuffle, null) },
                onClick = { controls.toggleShuffle() })
            val repeat = when (state.repeatMode) {
                Player.REPEAT_MODE_ONE -> "one song"
                Player.REPEAT_MODE_ALL -> "all songs"
                else -> "off"
            }
            DropdownMenuItem(text = { Text("Repeat: $repeat") },
                leadingIcon = { Icon(if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, null) },
                onClick = { controls.cycleRepeat() })
        }
    }
}

@Composable
private fun PlaybackDevicesButton(onClick: (() -> Unit)?) {
    if (onClick != null) IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Default.Devices, "Playback devices")
    }
}

@Composable
private fun PlayPauseButton(state: PlaybackState, onClick: () -> Unit, large: Boolean = false, enabled: Boolean = true) {
    // Buffering may temporarily make isPlaying false while playback intent still calls for Pause.
    val showPause = state.isPlaying || state.playWhenReady
    val modifier = Modifier.size(if (large) 64.dp else 48.dp).semantics {
        contentDescription = if (showPause) "Pause" else "Play"
        stateDescription = when {
            state.isBuffering -> "Buffering"
            state.isPlaying -> "Playing"
            state.playWhenReady -> "Starting playback"
            else -> "Paused"
        }
    }
    val content: @Composable () -> Unit = {
        if (state.isBuffering) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        else Icon(if (showPause) Icons.Default.Pause else Icons.Default.PlayArrow, null)
    }
    if (large) FilledIconButton(onClick = onClick, modifier = modifier, enabled = enabled) { content() }
    else IconButton(onClick = onClick, modifier = modifier, enabled = enabled) { content() }
}

@Composable
private fun PlaybackSeekBar(state: PlaybackState, controls: PlaybackControls) {
    // An occurrence change (including the same track twice) abandons an in-flight seek.
    var scrub by remember(state.currentEntryId, state.item?.id) { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.coerceAtLeast(1).toFloat()
    val position = (scrub ?: state.positionMs.toFloat()).coerceIn(0f, duration)
    Slider(value = position, onValueChange = { scrub = it },
        onValueChangeFinished = {
            scrub?.let { controls.seekTo(it.toLong().coerceIn(0, state.durationMs.coerceAtLeast(0))) }
            scrub = null
        }, valueRange = 0f..duration, enabled = state.durationMs > 0,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "Playback position" })
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(playerTime(position.toLong()), maxLines = 1, style = MaterialTheme.typography.bodySmall)
        Text(playerTime(state.durationMs), maxLines = 1, style = MaterialTheme.typography.bodySmall)
    }
}
