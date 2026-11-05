package dev.spofify.mobile.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.Player
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

/**
 * Fixed viewport content: only Up next scrolls. The fullscreen host owns system Back; expanding,
 * layout changes, artwork completion and progress publication never issue playback commands.
 */
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
) {
    val item = state.item ?: return
    BoxWithConstraints(modifier.fillMaxSize().testTag("now-playing")
        .semantics { paneTitle = "Now Playing" }.padding(8.dp)) {
        val landscape = maxWidth >= 480.dp && maxHeight < 420.dp
        val short = maxHeight < 480.dp
        val cramped = !landscape && maxHeight < 360.dp
        val showArtwork = maxHeight >= 560.dp && LocalDensity.current.fontScale < 1.8f
        LaunchedEffect(cramped) { if (!cramped) presentation.dismissQueue() }
        val feedback = state.error ?: state.notice
        val identity: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClickLabel = "Open song details") { onOpenItem(item) }
                    .padding(horizontal = 8.dp, vertical = if (cramped) 0.dp else 4.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // In the shortest view feedback replaces the credit line rather than adding
                    // height or hiding current playback failures behind the queue disclosure.
                    Text(if (cramped) feedback ?: item.subtitle else item.subtitle,
                        modifier = Modifier.semantics { if (cramped && feedback != null) liveRegion = LiveRegionMode.Polite },
                        color = if (cramped && state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                LikeButton(liked, likePending, likeFailed, onToggleLike, onRetryLike)
                PlayerMore(state, controls, onAddToPlaylist, onOpenItem)
            }
        }
        val transport: @Composable () -> Unit = {
            // Musical direction and the requested physical-left Devices placement do not mirror.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(Modifier.then(if (cramped) Modifier.fillMaxWidth() else Modifier).testTag("now-playing-controls"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconButton(onClick = controls::previous, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.SkipPrevious, "Previous song")
                    }
                    PlaybackDevicesButton(onPlaybackDevices)
                    PlayPauseButton(state, controls::toggle, large = !short)
                    IconButton(onClick = controls::next, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.SkipNext, "Next song")
                    }
                }
            }
        }
        val pinned: @Composable ColumnScope.() -> Unit = {
            identity()
            if (showArtwork) {
                BoxWithConstraints(Modifier.fillMaxWidth().weight(0.8f), contentAlignment = Alignment.Center) {
                    val side = minOf(maxWidth, maxHeight, 360.dp)
                    Box(Modifier.size(side).playerSwipe(PlayerSwipeArea.COVER) { action ->
                        when (action) {
                            PlayerSwipeAction.NEXT -> controls.next()
                            PlayerSwipeAction.PREVIOUS -> controls.previous()
                            else -> Unit
                        }
                    }) { artwork(item, Modifier.matchParentSize()) }
                }
            }
            // Seek gets its own full-width line: four independent transport targets must not
            // squeeze it out in short/narrow or landscape panes.
            PlaybackSeekBar(state, controls)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { transport() }
        }
        if (cramped) {
            if (presentation.queueOpen) {
                Column(Modifier.fillMaxSize().testTag("now-playing-queue-view")) {
                    // A stable full-width status/identity slot remains visible in both views.
                    Text(feedback ?: item.name, Modifier.fillMaxWidth().semantics {
                        if (feedback != null) liveRegion = LiveRegionMode.Polite
                    }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodySmall)
                    NativeUpcomingQueue(state, controls, onOpenItem, artwork, compact = true,
                        showStatus = false, onClose = { presentation.dismissQueue() })
                    transport()
                    LinearProgressIndicator(
                        progress = { if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth().height(2.dp).semantics { contentDescription = "Queue playback progress" },
                        strokeCap = StrokeCap.Butt, gapSize = 0.dp, drawStopIndicator = {},
                    )
                }
            } else {
                Column(Modifier.fillMaxSize().testTag("now-playing-main-view")) {
                    identity()
                    PlaybackSeekBar(state, controls)
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { transport() }
                    TextButton(onClick = presentation::openQueue, contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("now-playing-show-queue")) {
                        Text("Up next · ${state.upcoming.size}", maxLines = 1,
                            style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        } else if (landscape) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { pinned() }
                Column(Modifier.weight(1f).fillMaxHeight()) { NativeUpcomingQueue(state, controls, onOpenItem, artwork) }
            }
        } else {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                pinned()
                NativeUpcomingQueue(state, controls, onOpenItem, artwork)
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
            state.notice?.let { Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
            state.error?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
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
