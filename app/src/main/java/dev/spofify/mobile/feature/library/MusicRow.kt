package dev.spofify.mobile.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.LibraryItem

/** Host-supplied actual transport state; navigating never prepares or restores audio. */
data class CatalogPlaybackFeedback(
    val trackId: String? = null,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val onToggle: (() -> Unit)? = null,
    val interactionKey: dev.spofify.mobile.playback.PlaybackInteractionKey? = null,
    val playWhenReady: Boolean = false,
    val onContextual: ((LibraryItem, dev.spofify.mobile.playback.PlaybackInteractionKey, () -> Boolean) -> Unit)? = null,
    val queueIntentBinding: dev.spofify.mobile.core.QueueIntentBinding? = null,
    val unselectedQueueOrigin: dev.spofify.mobile.playback.PlaybackQueueOrigin? = null,
    val onUnselectedHigh: ((LibraryItem, dev.spofify.mobile.playback.PlaybackQueueOrigin, () -> Boolean) -> Unit)? = null,
) {
    val activeIntent: Boolean get() = isPlaying || (buffering && playWhenReady)
    val contextualLabel: String get() = "Queue"
}

/** Like state belongs to the account's shared personal-action owner, not each row. */
data class CatalogLikeState(val liked: Boolean? = null, val pending: Boolean = false, val failed: Boolean = false)

/** Uncommitted UI captures also fence output ABA; admitted work keeps only source lifetime.
 * The playback owner, not this guard, validates bindings and exact additive successor receipts. */
internal class MusicRowActionGuard(val sourceCurrent: () -> Boolean, private val admissionCurrent: () -> Boolean) : () -> Boolean {
    override fun invoke(): Boolean = sourceCurrent() && admissionCurrent()
}

internal fun sourceCurrent(action: () -> Boolean): () -> Boolean =
    (action as? MusicRowActionGuard)?.sourceCurrent ?: action

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
    // Occurrence hosts keep their exact identity and admission without owning a second row/gesture.
    rowIdentity: Any = item,
    currentOccurrence: Boolean? = null,
    playbackEnabled: Boolean = true,
    actionsEnabled: Boolean = true,
    onShowScopedActions: ((LibraryItem, () -> Boolean) -> Unit)? = null,
    onPlayScoped: ((LibraryItem, () -> Boolean) -> Unit)? = null,
) {
    val typography = MaterialTheme.typography
    val titleStyle = typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
    val subtitleStyle = typography.bodySmall
    val textMeasurer = rememberTextMeasurer()
    // Native fallback ascent/descent can exceed the declared line height. Reserve a
    // content-independent envelope for common scripts/emoji using the actual row styles;
    // measuring each item's copy instead would recreate credit-dependent row pitch.
    // This is not a cap: unusual fallback metrics may still expand naturally, never clip.
    val lineHeights = remember(textMeasurer, titleStyle, subtitleStyle) {
        val fallbackProbe = "Ag Égj 音楽 🌙 العربية हिन्दी"
        listOf(titleStyle, subtitleStyle).map { style ->
            textMeasurer.measure(fallbackProbe, style = style, maxLines = 1, softWrap = false).size.height
        }
    }
    // Reserve both copy slots even when metadata is absent. Round each role separately,
    // matching native layout, and retain Android's current font scaling/density.
    val copyHeight = with(LocalDensity.current) {
        maxOf(titleStyle.lineHeight.roundToPx(), lineHeights[0]).toDp() +
            maxOf(subtitleStyle.lineHeight.roundToPx(), lineHeights[1]).toDp() + 2.dp
    }
    val rowHeight = maxOf(72.dp, copyHeight + 20.dp)
    val subtitle = compactMusicSubtitle(item)
    val current = currentOccurrence ?: (item.kind == "track" && playback.trackId == item.id)
    val canPlay = playbackEnabled && item.playable
    val latestCanPlay by rememberUpdatedState(canPlay)
    val latestActionsEnabled by rememberUpdatedState(actionsEnabled)
    val capture = playback.interactionKey ?: playback.unselectedQueueOrigin
    val latestCapture by rememberUpdatedState(capture)
    val contextualEnabled = canPlay && (playback.interactionKey != null && playback.onContextual != null ||
        item.kind == "track" && playback.unselectedQueueOrigin != null && playback.onUnselectedHigh != null)
    fun contextual(captured: Any, current: () -> Boolean) {
        if (!current()) return
        val admittedSource = sourceCurrent(current)
        when (captured) {
            is dev.spofify.mobile.playback.PlaybackInteractionKey -> playback.onContextual?.invoke(item, captured, admittedSource)
            is dev.spofify.mobile.playback.PlaybackQueueOrigin -> playback.onUnselectedHigh?.invoke(item, captured, admittedSource)
        }
    }
    // Separate output admission from source lifetime so an admitted action can follow its
    // owner's exact additive receipt. Progress alone changes neither lifetime.
    val outputLifetime = remember(rowIdentity, capture) { dev.spofify.mobile.ui.RowActionLifetime() }
    DisposableEffect(outputLifetime) { onDispose { outputLifetime.invalidate() } }
    fun guard(source: () -> Boolean) = MusicRowActionGuard(source) {
        outputLifetime.isCurrent() && latestCapture === capture
    }
    dev.spofify.mobile.ui.ContextualSwipeRow(rowIdentity, capture, contextualEnabled,
        playback.contextualLabel, onCommit = { captured, current -> contextual(captured, guard(current)) }, modifier = modifier) { swipeModifier, isCurrent ->
        val actionCurrent = guard(isCurrent)
        fun more() {
            if (actionCurrent() && latestActionsEnabled && latestCapture === capture) {
                if (onShowScopedActions != null) onShowScopedActions(item, actionCurrent) else onShowActions(item)
            }
        }
        Row(swipeModifier.fillMaxWidth().background(
            if (current) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.background)
            .combinedClickable(onClick = { if (isCurrent()) onOpenItem(item) },
                onLongClickLabel = if (actionsEnabled) "More actions for ${item.name}" else null,
                onLongClick = if (actionsEnabled) ({ more() }) else null)
            .semantics {
                if (current) stateDescription = if (playback.buffering) "Buffering" else if (playback.isPlaying) "Playing" else "Paused"
                customActions = buildList {
                    if (actionsEnabled) add(CustomAccessibilityAction("More actions") { more(); true })
                    if (canPlay) {
                        add(CustomAccessibilityAction("Play now") {
                            if (actionCurrent() && latestCanPlay && latestCapture === capture) {
                                if (onPlayScoped != null) onPlayScoped(item, actionCurrent) else onPlayItem(item, false)
                            }
                            true
                        })
                        if (contextualEnabled) add(CustomAccessibilityAction(playback.contextualLabel) {
                            if (actionCurrent() && latestCanPlay && latestCapture === capture) capture?.let { contextual(it, actionCurrent) }; true
                        })
                    }
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
                Text(item.name, style = titleStyle, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                if (subtitle.isNotBlank()) Text(subtitle, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = subtitleStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { more() }, enabled = actionsEnabled, modifier = Modifier.size(48.dp)) {
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
