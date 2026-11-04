package dev.spofify.mobile.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.LibraryItem
import kotlin.math.abs
import kotlin.math.roundToInt

/** Host-supplied actual transport state; navigating never prepares or restores audio. */
data class CatalogPlaybackFeedback(
    val trackId: String? = null,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val onToggle: (() -> Unit)? = null,
)

/** Like state belongs to the account's shared personal-action owner, not each row. */
data class CatalogLikeState(val liked: Boolean? = null, val pending: Boolean = false, val failed: Boolean = false)

/**
 * Shared compact identity row. Opening only navigates; Play is always a separate action.
 * [like] and [onToggleLike] supply the optional, pending-guarded Like/Unlike accessibility
 * action without a third inline button. Rich credit links belong in [MusicCredits] in More/details.
 */
@Composable
fun MusicRow(
    item: LibraryItem,
    onOpenItem: (LibraryItem) -> Unit,
    onPlayItem: (LibraryItem, Boolean) -> Unit,
    onShowActions: (LibraryItem) -> Unit,
    onEnqueueItem: (LibraryItem) -> Unit,
    artwork: @Composable (LibraryItem, Modifier) -> Unit,
    modifier: Modifier = Modifier,
    playback: CatalogPlaybackFeedback = CatalogPlaybackFeedback(),
    like: CatalogLikeState? = null,
    onToggleLike: ((LibraryItem) -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    val typography = MaterialTheme.typography
    // Reserve the same two-line copy slot even when metadata is absent. Convert through
    // Android's font scaling rather than fixing a height that clips enlarged text.
    val copyHeight = with(LocalDensity.current) {
        typography.bodyMedium.lineHeight.toDp() + typography.bodySmall.lineHeight.toDp() + 2.dp
    }
    val rowHeight = maxOf(72.dp, copyHeight + 20.dp)
    val subtitle = compactMusicSubtitle(item)
    val current = item.kind == "track" && playback.trackId == item.id
    val playLabel = if (current && playback.isPlaying) "Pause ${item.name}" else "Play ${item.name}"
    var drag by remember(item.id, item.entryId) { mutableFloatStateOf(0f) }
    val enqueue by rememberUpdatedState(onEnqueueItem)
    val rowItem by rememberUpdatedState(item)
    Box(modifier.fillMaxWidth()) {
        if (drag > 0f) Row(Modifier.matchParentSize().background(MaterialTheme.colorScheme.secondaryContainer).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.QueueMusic, null)
            if (drag > 90f) Text("Add to queue", style = MaterialTheme.typography.labelSmall)
        }
        val gesture = if (item.playable) Modifier.pointerInput(item.kind, item.id, item.entryId) {
            val edge = 24.dp.toPx()
            val threshold = 72.dp.toPx()
            val maxDrag = 120.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // Leave Android edge Back untouched. Vertical movement wins until horizontal intent is clear.
                if (down.position.x < edge || down.position.x > size.width - edge) return@awaitEachGesture
                var x = 0f
                var y = 0f
                var horizontal = false
                var cancelled = false
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed) { cancelled = true; break }
                        x += change.position.x - change.previousPosition.x
                        y += change.position.y - change.previousPosition.y
                        if (!horizontal && abs(y) > viewConfiguration.touchSlop && abs(y) >= abs(x)) { cancelled = true; break }
                        if (!horizontal && x > viewConfiguration.touchSlop && x > abs(y) * 1.5f) horizontal = true
                        if (event.changes.any { it.id != down.id && it.pressed }) { cancelled = true; break }
                        if (horizontal) { change.consume(); drag = x.coerceIn(0f, maxDrag) }
                        if (!change.pressed) break
                    }
                    if (!cancelled && horizontal && x >= threshold) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        enqueue(rowItem)
                    }
                } finally { drag = 0f }
            }
        } else Modifier
        Row(Modifier.offset { IntOffset(drag.roundToInt(), 0) }.background(
            if (current) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.background)
            .then(gesture)
            .combinedClickable(onClick = { onOpenItem(item) }, onLongClickLabel = "More actions for ${item.name}",
                onLongClick = { onShowActions(item) })
            .semantics {
                if (current) stateDescription = if (playback.buffering) "Buffering" else if (playback.isPlaying) "Playing" else "Paused"
                customActions = buildList {
                    add(CustomAccessibilityAction("More actions") { onShowActions(item); true })
                    if (item.playable) add(CustomAccessibilityAction("Add to queue") { onEnqueueItem(item); true })
                    if (item.kind == "track" && onToggleLike != null && like?.pending != true) {
                        add(CustomAccessibilityAction(if (like?.liked == true) "Unlike song" else "Like song") {
                            onToggleLike(item); true
                        })
                    }
                }
            }.heightIn(min = rowHeight).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val type = musicTypeLabel(item.kind)
            Box(Modifier.size(52.dp).clearAndSetSemantics { contentDescription = type }) {
                artwork(item, Modifier.fillMaxSize().clip(if (item.kind == "artist") CircleShape else RectangleShape))
                Icon(when (item.kind) {
                    "artist" -> Icons.Default.Person
                    "album" -> Icons.Default.Album
                    else -> Icons.Default.MusicNote
                }, contentDescription = null, modifier = Modifier.align(Alignment.BottomEnd).size(18.dp)
                    .background(MaterialTheme.colorScheme.background, CircleShape).padding(2.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp).heightIn(min = copyHeight),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium,
                    color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                if (subtitle.isNotBlank()) Text(subtitle, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = {
                if (current && playback.onToggle != null) playback.onToggle.invoke() else onPlayItem(item, false)
            }, enabled = item.playable, modifier = Modifier.size(48.dp).semantics { contentDescription = playLabel }) {
                if (current && playback.buffering) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(if (current && playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null)
            }
            IconButton(onClick = { onShowActions(item) }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.MoreVert, "More actions for ${item.name}")
            }
        }
    }
}

internal fun musicTypeLabel(kind: String): String = when (kind) {
    "artist" -> "Artist"
    "album" -> "Album"
    else -> "Song"
}

/** Compact rows never grow with credit count; full opaque-ID credit links live in More/details. */
internal fun compactMusicSubtitle(item: LibraryItem): String = buildList {
    val artists = item.artists.map { it.name.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
    if (artists.isNotEmpty()) add(artists)
    else if (item.kind == "artist") item.genres.map { it.trim() }.filter { it.isNotEmpty() }
        .take(2).joinToString(" · ").takeIf { it.isNotEmpty() }?.let(::add)
    itemYear(item)?.let(::add)
    itemDuration(item.durationMs)?.let(::add)
    if (!item.playable) add("Unavailable")
}.joinToString(" · ")

/** Every credited artist and the album retain distinct opaque-ID navigation targets. */
@Composable
fun MusicCredits(item: LibraryItem, onOpenItem: (LibraryItem) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        item.artists.forEach { artist ->
            if (artist.id.isNotBlank()) TextButton(onClick = { onOpenItem(LibraryItem("artist", artist.id, artist.name)) },
                contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 48.dp)) {
                Text(artist.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else Text(artist.name, style = MaterialTheme.typography.bodySmall)
        }
        val albumName = item.albumName
        val albumId = item.albumId
        if (item.kind == "track" && !albumName.isNullOrBlank()) {
            if (!albumId.isNullOrBlank()) TextButton(onClick = { onOpenItem(LibraryItem("album", albumId, albumName)) },
                contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 48.dp)) {
                Text(albumName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else Text(albumName, style = MaterialTheme.typography.bodySmall)
        }
    }
}
