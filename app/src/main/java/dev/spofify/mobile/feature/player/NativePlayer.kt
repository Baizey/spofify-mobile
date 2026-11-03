package dev.spofify.mobile.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
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
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import dev.spofify.mobile.LikeButton
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.playback.PlaybackControls
import dev.spofify.mobile.playback.PlaybackState
import dev.spofify.mobile.playback.QueueEntry

/** UI only: expanding does not select, prepare, reconnect, or restart playback. */
@Composable
fun NativeMiniPlayer(
    state: PlaybackState,
    controls: PlaybackControls,
    onExpand: () -> Unit,
    artwork: @Composable (LibraryItem, Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = state.item ?: return
    Surface(modifier = modifier, shape = MaterialTheme.shapes.medium, tonalElevation = 3.dp) {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f)
                        .playerSwipe(PlayerSwipeArea.MINI) { onExpand() }
                        .clickable(role = Role.Button, onClickLabel = "Expand Now Playing", onClick = onExpand)
                        .heightIn(min = 64.dp).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    artwork(item, Modifier.size(48.dp))
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall)
                        Text(item.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                PlayPauseButton(state, controls::toggle)
                IconButton(onClick = controls::next, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.SkipNext, "Next song")
                }
            }
            LinearProgressIndicator(
                progress = { if (state.durationMs > 0)
                    (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
        }
    }
}

/**
 * The root sheet owns collapse/Back handling. This content deliberately has no dismiss or stop
 * action, and only consumes horizontal transport gestures over the cover itself.
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
) {
    val item = state.item ?: return
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("NOW PLAYING", style = MaterialTheme.typography.labelMedium)
        state.context?.let { context ->
            TextButton(onClick = { onOpenItem(context) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Playing from ${context.name}", maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f)
                .playerSwipe(PlayerSwipeArea.COVER) { action ->
                    when (action) {
                        PlayerSwipeAction.NEXT -> controls.next()
                        PlayerSwipeAction.PREVIOUS -> controls.previous()
                        else -> Unit
                    }
                },
        ) { artwork(item, Modifier.matchParentSize()) }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(item.name,
                Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = "Open song details") {
                    onOpenItem(item)
                }.heightIn(min = 48.dp).padding(vertical = 8.dp),
                style = MaterialTheme.typography.headlineSmall)
            LikeButton(liked, likePending, likeFailed, onToggleLike, onRetryLike)
        }
        Column(Modifier.fillMaxWidth()) {
            item.artists.forEach { credit ->
                if (credit.id.isNotBlank()) {
                    TextButton(onClick = { onOpenItem(LibraryItem("artist", credit.id, credit.name)) },
                        modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(credit.name)
                    }
                } else {
                    Text(credit.name, Modifier.padding(horizontal = 12.dp, vertical = 12.dp))
                }
            }
            val albumId = item.albumId?.takeIf { it.isNotBlank() }
            if (albumId != null) {
                TextButton(onClick = {
                    onOpenItem(LibraryItem("album", albumId, item.albumName ?: "Album", artists = item.artists))
                }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(item.albumName ?: "Open album")
                }
            } else {
                item.albumName?.takeIf { it.isNotBlank() }?.let { albumName ->
                    Text(albumName, Modifier.padding(horizontal = 12.dp, vertical = 12.dp))
                }
            }
        }
        TextButton(onClick = { onAddToPlaylist(item) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Add to playlist")
        }
        PlaybackSeekBar(state, controls)
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconToggleButton(checked = state.shuffle, onCheckedChange = { controls.toggleShuffle() },
                modifier = Modifier.size(48.dp).semantics {
                    stateDescription = if (state.shuffle) "On" else "Off"
                }) { Icon(Icons.Default.Shuffle, "Shuffle") }
            IconButton(onClick = controls::previous, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.SkipPrevious, "Previous song")
            }
            PlayPauseButton(state, controls::toggle, large = true)
            IconButton(onClick = controls::next, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.SkipNext, "Next song")
            }
            IconButton(onClick = controls::cycleRepeat, modifier = Modifier.size(48.dp).semantics {
                stateDescription = when (state.repeatMode) {
                    Player.REPEAT_MODE_ONE -> "One song"
                    Player.REPEAT_MODE_ALL -> "All songs"
                    else -> "Off"
                }
            }) {
                Icon(if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    "Repeat: ${when (state.repeatMode) {
                        Player.REPEAT_MODE_ONE -> "one"
                        Player.REPEAT_MODE_ALL -> "all"
                        else -> "off"
                    }}")
            }
        }
        state.notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
        }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
        }
        UpcomingQueue(state, controls, onOpenItem, artwork)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PlayPauseButton(state: PlaybackState, onClick: () -> Unit, large: Boolean = false) {
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
    if (large) FilledIconButton(onClick = onClick, modifier = modifier) { content() }
    else IconButton(onClick = onClick, modifier = modifier) { content() }
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
        Text(playerTime(position.toLong()), style = MaterialTheme.typography.bodySmall)
        Text(playerTime(state.durationMs), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun UpcomingQueue(
    state: PlaybackState,
    controls: PlaybackControls,
    onOpenItem: (LibraryItem) -> Unit,
    artwork: @Composable (LibraryItem, Modifier) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text("Up next · ${state.upcoming.size}", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = controls::clearUpcoming, enabled = state.upcoming.isNotEmpty(),
            modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear upcoming") }
    }
    Text("Reordering turns shuffle off", style = MaterialTheme.typography.bodySmall)
    if (state.canUndoQueueRemoval) {
        Row(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically) {
            Text("Queue item removed", Modifier.weight(1f))
            TextButton(onClick = controls::undoQueueRemoval, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Undo")
            }
        }
    }
    if (state.upcoming.isEmpty()) {
        Text("No upcoming songs", Modifier.fillMaxWidth().padding(vertical = 24.dp))
    } else {
        // Explicit finite bounds: nested scrolling cooperates with the root sheet and this page.
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)
            .semantics { contentDescription = "Upcoming queue" }) {
            itemsIndexed(state.upcoming, key = { _, entry -> entry.occurrenceId }) { index, entry ->
                UpcomingRow(entry, index, state.upcoming.size, controls, onOpenItem, artwork)
            }
        }
    }
}

@Composable
private fun UpcomingRow(
    entry: QueueEntry,
    index: Int,
    count: Int,
    controls: PlaybackControls,
    onOpenItem: (LibraryItem) -> Unit,
    artwork: @Composable (LibraryItem, Modifier) -> Unit,
) {
    var menuOpen by remember(entry.occurrenceId) { mutableStateOf(false) }
    var rowHeight by remember { mutableIntStateOf(80) }
    var dragY by remember(entry.occurrenceId) { mutableFloatStateOf(0f) }
    var dragging by remember(entry.occurrenceId) { mutableStateOf(false) }
    val latestIndex by rememberUpdatedState(index)
    val latestCount by rememberUpdatedState(count)
    val latestControls by rememberUpdatedState(controls)
    val haptics = LocalHapticFeedback.current
    val moveUp = { controls.moveUpcoming(entry.occurrenceId, (index - 1).coerceAtLeast(0)) }
    val moveDown = { controls.moveUpcoming(entry.occurrenceId, (index + 1).coerceAtMost(count - 1)) }
    val playNext = { controls.moveUpcoming(entry.occurrenceId, 0) }
    val remove = { controls.removeUpcoming(entry.occurrenceId) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 80.dp).onSizeChanged { rowHeight = it.height }
            .playerSwipe(PlayerSwipeArea.UPCOMING) { remove() }
            .semantics {
                customActions = buildList {
                    add(CustomAccessibilityAction("Play now") { controls.selectOccurrence(entry.occurrenceId); true })
                    if (index > 0) {
                        add(CustomAccessibilityAction("Move up") { moveUp(); true })
                        add(CustomAccessibilityAction("Play next") { playNext(); true })
                    }
                    if (index < count - 1) add(CustomAccessibilityAction("Move down") { moveDown(); true })
                    add(CustomAccessibilityAction("Remove from queue") { remove(); true })
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp)
            .semantics {
                contentDescription = "Reorder ${entry.item.name}"
                stateDescription = if (dragging) "Move to position ${queueMoveTarget(index, dragY, rowHeight, count) + 1}"
                    else "Position ${index + 1} of $count. Use queue actions to move up or down."
            }
            .pointerInput(entry.occurrenceId) {
                detectVerticalDragGestures(
                    onDragStart = {
                        dragY = 0f
                        dragging = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDragCancel = { dragY = 0f; dragging = false },
                    onDragEnd = {
                        val target = queueMoveTarget(latestIndex, dragY, rowHeight, latestCount)
                        if (target != latestIndex) latestControls.moveUpcoming(entry.occurrenceId, target)
                        dragY = 0f
                        dragging = false
                    },
                ) { change, amount -> change.consume(); dragY += amount }
            }, contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.DragHandle, null) }
        artwork(entry.item, Modifier.size(40.dp))
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(entry.item.name,
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClickLabel = "Open song details") { onOpenItem(entry.item) }
                    .padding(vertical = 8.dp),
                maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(if (dragging) "Move to position ${queueMoveTarget(index, dragY, rowHeight, count) + 1}" else entry.item.subtitle,
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { controls.selectOccurrence(entry.occurrenceId) }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.PlayArrow, "Play queued song ${entry.item.name}")
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.MoreVert, "Queue actions for ${entry.item.name}")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Play next") }, enabled = index > 0,
                    onClick = { menuOpen = false; playNext() })
                DropdownMenuItem(text = { Text("Move up") }, enabled = index > 0,
                    onClick = { menuOpen = false; moveUp() })
                DropdownMenuItem(text = { Text("Move down") }, enabled = index < count - 1,
                    onClick = { menuOpen = false; moveDown() })
                DropdownMenuItem(text = { Text("Remove from queue") },
                    onClick = { menuOpen = false; remove() })
            }
        }
    }
}
