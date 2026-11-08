package dev.spofify.mobile.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.playback.PlaybackControls
import dev.spofify.mobile.playback.PlaybackState
import dev.spofify.mobile.playback.PlaybackListAction
import dev.spofify.mobile.playback.QueueEntry
import dev.spofify.mobile.ui.ContextualSwipeRow
import dev.spofify.mobile.ui.RowActionLifetime
import dev.spofify.mobile.ui.MusicActionsContent
import dev.spofify.mobile.ui.SongActionSheet
import dev.spofify.mobile.ui.SongActionGroup
import dev.spofify.mobile.ui.SongActionRow
import dev.spofify.mobile.playback.defaultQueueAction

/** Intentional actions always address the exact retained upcoming occurrence, never song ID. */
private fun upcomingIntentActions(entry: QueueEntry, state: PlaybackState, controls: PlaybackControls,
    rowIsCurrent: () -> Boolean, hostIsCurrent: () -> Boolean): List<Pair<String, () -> Unit>> {
    val key = state.interactionKey
    return listOf("Play now" to PlaybackListAction.PLAY_NOW, "Play next" to PlaybackListAction.PLAY_NEXT,
        "Queue" to entry.item.defaultQueueAction()).map { (label, action) ->
        label to { if (key != null && entry.item.playable && rowIsCurrent() && hostIsCurrent())
            controls.queueIntentOccurrence(key, entry.occurrenceId, action, hostIsCurrent) {} }
    }
}

/** Same grouped menu as catalog/playlist songs; queue edits retain their exact occurrence closures. */
@Composable
private fun UpcomingOccurrenceActions(
    entry: QueueEntry, state: PlaybackState, intents: List<Pair<String, () -> Unit>>,
    canMoveUp: Boolean, canMoveDown: Boolean, onDismiss: () -> Unit,
    onOpenItem: (LibraryItem) -> Unit, onMoveUp: () -> Unit, onMoveDown: () -> Unit, onRemove: () -> Unit,
    artwork: (@Composable (LibraryItem, Modifier) -> Unit)? = null,
    localNavigation: ((LibraryItem) -> Boolean)? = null,
) {
    val actions = intents.toMap()
    fun perform(action: () -> Unit) { onDismiss(); action() }
    SongActionSheet(onDismiss) {
        MusicActionsContent(entry.item, trackMetadataComplete = false,
            onPlay = { perform(actions.getValue("Play now")) },
            onPlayNext = { perform(actions.getValue("Play next")) },
            onQueue = { perform(actions.getValue("Queue")) },
            playbackEnabled = entry.item.playable && state.interactionKey != null,
            onOpenItem = if (localNavigation == null) ({ item -> perform { onOpenItem(item) } }) else null,
            artwork = artwork,
            contextualContent = {
                if (localNavigation != null) {
                    val targets = buildList {
                        state.context?.let(::add)
                        entry.item.artists.forEach { add(LibraryItem("artist", it.id, it.name)) }
                        entry.item.albumName?.let { add(LibraryItem("album", entry.item.albumId.orEmpty(), it)) }
                    }.distinctBy { it.kind to it.id }
                    if (targets.isNotEmpty()) SongActionGroup("Navigation") {
                        targets.forEach { target ->
                            SongActionRow(target.name, Icons.Default.Info, localNavigation(target)) {
                                perform { onOpenItem(target) }
                            }
                        }
                    }
                }
                SongActionGroup("This queue") {
                    SongActionRow("Move up", Icons.Default.KeyboardArrowUp, canMoveUp, onClick = { perform(onMoveUp) })
                    SongActionRow("Move down", Icons.Default.KeyboardArrowDown, canMoveDown, onClick = { perform(onMoveDown) })
                    SongActionRow("Remove from queue", Icons.Default.RemoveCircleOutline,
                        destructive = true, onClick = { perform(onRemove) })
                }
            })
    }
}

/**
 * Remember in the queue/sheet host, never in a lazy row/header. A committed action may remove its
 * own originating row; only leaving the owning presentation invalidates this UI-side intent.
 * Session/output/request/occurrence authority remains fenced by the service.
 */
@Composable
internal fun rememberUpcomingQueueActionIsCurrent(controls: PlaybackControls): () -> Boolean {
    val lifetime = remember(controls) { RowActionLifetime() }
    DisposableEffect(lifetime) { onDispose { lifetime.invalidate() } }
    return remember(lifetime) { lifetime::isCurrent }
}

/**
 * Existing bounded queue host: the header stays pinned and only rows scroll. A parent that already
 * owns a LazyColumn must use [nativeUpcomingQueue] instead; never nest this wrapper in that list.
 */
@Composable
internal fun ColumnScope.NativeUpcomingQueue(
    state: PlaybackState,
    controls: PlaybackControls,
    onOpenItem: (LibraryItem) -> Unit,
    artwork: @Composable (LibraryItem, Modifier) -> Unit,
    compact: Boolean = false,
    showStatus: Boolean = true,
    onClose: (() -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    localNavigation: ((LibraryItem) -> Boolean)? = null,
) {
    val actionIsCurrent = rememberUpcomingQueueActionIsCurrent(controls)
    UpcomingQueueHeader(state, controls, compact, showStatus, onClose)
    LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("now-playing-queue")
        .semantics { contentDescription = "Upcoming queue" }, state = listState) {
        nativeUpcomingQueueItems(state, controls, onOpenItem, artwork, listState, actionIsCurrent, compact, localNavigation)
    }
}

// Non-string keys cannot collide with authoritative occurrence-string keys.
private enum class QueueContentKey { HEADER, EMPTY }

/**
 * Header and occurrence-keyed rows for ONE parent scroller, including any preceding player items.
 * Pass that parent's active [listState]; future reorder geometry/autoscroll must use the same state
 * and its real item indices, not an independently remembered queue scroller or fixed header count.
 * Remember [actionIsCurrent] with [rememberUpcomingQueueActionIsCurrent] in the owning composable
 * queue/sheet host, outside this lazy content. It must survive an occurrence becoming current.
 */
internal fun LazyListScope.nativeUpcomingQueue(
    state: PlaybackState,
    controls: PlaybackControls,
    onOpenItem: (LibraryItem) -> Unit,
    artwork: @Composable (LibraryItem, Modifier) -> Unit,
    listState: LazyListState,
    actionIsCurrent: () -> Boolean,
    compact: Boolean = false,
    showStatus: Boolean = true,
    onClose: (() -> Unit)? = null,
) {
    item(key = QueueContentKey.HEADER) {
        UpcomingQueueHeader(state, controls, compact, showStatus, onClose)
    }
    nativeUpcomingQueueItems(state, controls, onOpenItem, artwork, listState, actionIsCurrent, compact)
}

/** Rows-only seam also used by the pinned-header wrapper; this phase does not change drag behavior. */
@Suppress("UNUSED_PARAMETER") // Explicit active-parent seam for the subsequent queue-owned drag phase.
internal fun LazyListScope.nativeUpcomingQueueItems(
    state: PlaybackState,
    controls: PlaybackControls,
    onOpenItem: (LibraryItem) -> Unit,
    artwork: @Composable (LibraryItem, Modifier) -> Unit,
    listState: LazyListState,
    actionIsCurrent: () -> Boolean,
    compact: Boolean = false,
    localNavigation: ((LibraryItem) -> Boolean)? = null,
) {
    if (state.upcoming.isEmpty()) item(key = QueueContentKey.EMPTY) {
        Text("No upcoming songs", Modifier.fillMaxWidth().padding(vertical = 16.dp),
            style = MaterialTheme.typography.bodyMedium)
    }
    itemsIndexed(state.upcoming, key = { _, entry -> entry.occurrenceId }) { index, entry ->
        if (compact) CompactUpcomingRow(entry, index, state.upcoming.size, controls, onOpenItem, state, actionIsCurrent, localNavigation)
        else UpcomingRow(entry, index, state.upcoming.size, controls, onOpenItem, artwork, state, actionIsCurrent, localNavigation)
    }
}

@Composable
private fun UpcomingQueueHeader(
    state: PlaybackState,
    controls: PlaybackControls,
    compact: Boolean,
    showStatus: Boolean,
    onClose: (() -> Unit)?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column {
        // Keep failures visible without a wall of prose above transport; More retains the full text.
        (state.error ?: state.notice)?.takeIf { showStatus }?.let { message ->
            Text(message, Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Up next · ${state.upcoming.size}",
                Modifier.weight(1f).semantics { stateDescription = "${state.upcoming.size} upcoming songs" }, maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            onClose?.let { close ->
                IconButton(onClick = close, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Close, "Close upcoming queue")
                }
            }
            if (state.canUndoQueueRemoval && !compact) {
                TextButton(onClick = controls::undoQueueRemoval, modifier = Modifier.heightIn(min = 48.dp)) { Text("Undo") }
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.MoreVert, "Upcoming queue actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (state.canUndoQueueRemoval && compact) {
                        DropdownMenuItem(text = { Text("Undo") }, onClick = { menuOpen = false; controls.undoQueueRemoval() })
                    }
                    DropdownMenuItem(text = { Text("Clear upcoming") }, enabled = state.upcoming.isNotEmpty(),
                        onClick = { menuOpen = false; controls.clearUpcoming() })
                    Text("Reordering turns shuffle off", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** Body and explicit Play now share the exact queued-occurrence action. The clickable's
 * incarnation is separate from the host lifetime retained by an admitted occurrence intent. */
@Composable
private fun UpcomingSongBody(entry: QueueEntry, state: PlaybackState, playNow: () -> Unit,
    modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val lifetime = remember(entry, state.interactionKey) { RowActionLifetime() }
    DisposableEffect(lifetime) { onDispose { lifetime.invalidate() } }
    val enabled = entry.item.playable && state.interactionKey != null
    val activate = { if (enabled && lifetime.isCurrent()) playNow() }
    key(lifetime) {
        Column(modifier.heightIn(min = 48.dp)
            .semantics { if (enabled) onClick("Play now") { activate(); true } }
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Play now", onClick = activate)
            .padding(vertical = 8.dp), content = content)
    }
}

/** Extreme short/narrow windows disclose reordering in More, keeping real 48dp actions. */
@Composable
private fun CompactUpcomingRow(
    entry: QueueEntry,
    index: Int,
    count: Int,
    controls: PlaybackControls,
    onOpenItem: (LibraryItem) -> Unit,
    state: PlaybackState,
    actionIsCurrent: () -> Boolean,
    localNavigation: ((LibraryItem) -> Boolean)?,
) {
    // An open action menu belongs to the rendered queue binding, not a later publication.
    // Progress keeps the key; queue/output/session replacement disposes the old menu.
    var menuOpen by remember(entry.occurrenceId, state.interactionKey) { mutableStateOf(false) }
    ContextualSwipeRow(entry, state.interactionKey, state.interactionKey != null, "Play next",
        onCommit = { key, rowIsCurrent ->
            if (entry.item.playable && rowIsCurrent() && actionIsCurrent())
                controls.playNextOccurrence(key, entry.occurrenceId, actionIsCurrent) {}
        }, onLeftCommit = { key, rowIsCurrent ->
            if (rowIsCurrent() && actionIsCurrent())
                controls.removeUpcoming(key, entry.occurrenceId, actionIsCurrent)
        }) { swipeModifier, isCurrent ->
    val intents = upcomingIntentActions(entry, state, controls, isCurrent, actionIsCurrent)
    Row(swipeModifier.fillMaxWidth().heightIn(min = 72.dp).background(MaterialTheme.colorScheme.surface).semantics {
        customActions = buildList {
            if (entry.item.playable && state.interactionKey != null)
                intents.forEach { (label, action) -> add(CustomAccessibilityAction(label) { action(); true }) }
            if (index > 0) add(CustomAccessibilityAction("Move up") { controls.moveUpcoming(entry.occurrenceId, index - 1); true })
            if (index < count - 1) add(CustomAccessibilityAction("Move down") { controls.moveUpcoming(entry.occurrenceId, index + 1); true })
            add(CustomAccessibilityAction("Remove from queue") { controls.removeUpcoming(entry.occurrenceId); true })
        }
    }, verticalAlignment = Alignment.CenterVertically) {
        UpcomingSongBody(entry, state, intents.single { it.first == "Play now" }.second,
            Modifier.weight(1f)) {
            Text(entry.item.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium)
            Text(entry.item.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall)
        }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.MoreVert, "Queue actions for ${entry.item.name}")
                }
                if (menuOpen) UpcomingOccurrenceActions(entry, state, intents, index > 0, index < count - 1,
                    onDismiss = { menuOpen = false }, onOpenItem = onOpenItem,
                    onMoveUp = { controls.moveUpcoming(entry.occurrenceId, index - 1) },
                    onMoveDown = { controls.moveUpcoming(entry.occurrenceId, index + 1) },
                    onRemove = { controls.removeUpcoming(entry.occurrenceId) }, localNavigation = localNavigation)
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
    state: PlaybackState,
    actionIsCurrent: () -> Boolean,
    localNavigation: ((LibraryItem) -> Boolean)?,
) {
    // Progress preserves this menu; a new queue/output/session binding dismisses it.
    var menuOpen by remember(entry.occurrenceId, state.interactionKey) { mutableStateOf(false) }
    var rowHeight by remember { mutableIntStateOf(80) }
    var dragY by remember(entry.occurrenceId) { mutableFloatStateOf(0f) }
    var dragging by remember(entry.occurrenceId) { mutableStateOf(false) }
    val latestIndex by rememberUpdatedState(index)
    val latestCount by rememberUpdatedState(count)
    val latestControls by rememberUpdatedState(controls)
    val haptics = LocalHapticFeedback.current
    val moveUp = { controls.moveUpcoming(entry.occurrenceId, (index - 1).coerceAtLeast(0)) }
    val moveDown = { controls.moveUpcoming(entry.occurrenceId, (index + 1).coerceAtMost(count - 1)) }
    val remove = { controls.removeUpcoming(entry.occurrenceId) }
    ContextualSwipeRow(entry, state.interactionKey, state.interactionKey != null, "Play next",
        onCommit = { key, rowIsCurrent ->
            if (entry.item.playable && rowIsCurrent() && actionIsCurrent())
                controls.playNextOccurrence(key, entry.occurrenceId, actionIsCurrent) {}
        }, onLeftCommit = { key, rowIsCurrent ->
            if (rowIsCurrent() && actionIsCurrent())
                controls.removeUpcoming(key, entry.occurrenceId, actionIsCurrent)
        }, excludeLeadingHandle = true, leadingTargetWidth = 88.dp) { swipeModifier, isCurrent ->
    val intents = upcomingIntentActions(entry, state, controls, isCurrent, actionIsCurrent)
    Row(
        swipeModifier.fillMaxWidth().heightIn(min = 80.dp).background(MaterialTheme.colorScheme.surface).onSizeChanged { rowHeight = it.height }
            .semantics {
                customActions = buildList {
                    if (entry.item.playable && state.interactionKey != null)
                        intents.forEach { (label, action) -> add(CustomAccessibilityAction(label) { action(); true }) }
                    if (index > 0) add(CustomAccessibilityAction("Move up") { moveUp(); true })
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
            .pointerInput(entry, state.interactionKey, state.canMoveUpcomingAbsolutely) {
                val expectedKey = state.interactionKey
                if (expectedKey == null || !state.canMoveUpcomingAbsolutely) return@pointerInput
                detectVerticalDragGestures(
                    onDragStart = {
                        dragY = 0f
                        dragging = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDragCancel = { dragY = 0f; dragging = false },
                    onDragEnd = {
                        val target = queueMoveTarget(latestIndex, dragY, rowHeight, latestCount)
                        if (target != latestIndex) latestControls.moveUpcoming(expectedKey, entry.occurrenceId, target)
                        dragY = 0f
                        dragging = false
                    },
                ) { change, amount -> change.consume(); dragY += amount }
            }, contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.DragHandle, null) }
        artwork(entry.item, Modifier.size(40.dp))
        UpcomingSongBody(entry, state, intents.single { it.first == "Play now" }.second,
            Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(entry.item.name,
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(if (dragging) "Move to position ${queueMoveTarget(index, dragY, rowHeight, count) + 1}" else entry.item.subtitle,
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.MoreVert, "Queue actions for ${entry.item.name}")
            }
            if (menuOpen) UpcomingOccurrenceActions(entry, state, intents, index > 0, index < count - 1,
                onDismiss = { menuOpen = false }, onOpenItem = onOpenItem,
                onMoveUp = moveUp, onMoveDown = moveDown, onRemove = remove, artwork = artwork,
                localNavigation = localNavigation)
        }
    }
    }
}
