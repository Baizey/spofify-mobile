package dev.spofify.mobile.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.spofify.mobile.core.LibraryItem

/**
 * One menu body for catalog items, saved-playlist occurrences, collections and upcoming songs.
 * Unsupported callbacks are absent, not reordered. Callers own bindings, guards and dismissal;
 * contextual saved-data/queue actions stay last, outside the shared non-destructive groups.
 */
@Composable
internal fun MusicActionsContent(
    item: LibraryItem,
    liked: Boolean? = null,
    likePending: Boolean = false,
    likeFailed: Boolean = false,
    onPlay: (() -> Unit)? = null,
    onPlayNext: (() -> Unit)? = null,
    onQueue: (() -> Unit)? = null,
    onToggleLike: (() -> Unit)? = null,
    onRetryLike: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onOpenItem: ((LibraryItem) -> Unit)? = null,
    modifier: Modifier = Modifier,
    playbackEnabled: Boolean = item.playable,
    actionsEnabled: Boolean = true,
    onOpen: (() -> Unit)? = onOpenItem?.let { open -> { open(item) } },
    pendingLikeLabel: String = "Saving like",
    checkLikeLabel: String = if (likeFailed) "Retry like status" else "Checking like status",
    likeStatusEnabled: Boolean = likeFailed,
    likeMessage: String? = null,
    libraryContent: (@Composable () -> Unit)? = null,
    contextualContent: (@Composable () -> Unit)? = null,
    artwork: (@Composable (LibraryItem, Modifier) -> Unit)? = null,
    // MediaSession/wire queue projections omit genres/disc/track metadata; absence is unknown.
    trackMetadataComplete: Boolean = true,
) {
    val content: @Composable () -> Unit = {
        if (onPlay != null || onPlayNext != null || onQueue != null) {
            SongActionGroup("Playback", divider = false) {
                onPlay?.let { SongActionRow("Play now", Icons.Default.PlayArrow,
                    enabled = playbackEnabled && actionsEnabled, onClick = it) }
                onPlayNext?.let { SongActionRow("Play next", Icons.Default.SkipNext,
                    enabled = playbackEnabled && actionsEnabled, onClick = it) }
                onQueue?.let { SongActionRow("Queue", Icons.AutoMirrored.Filled.QueueMusic,
                    enabled = playbackEnabled && actionsEnabled, onClick = it) }
            }
        }
        val hasLike = item.kind == "track" && (onToggleLike != null || onRetryLike != null)
        val addToPlaylist = onAddToPlaylist.takeIf { item.kind == "track" }
        if (hasLike || addToPlaylist != null || libraryContent != null) {
            SongActionGroup("Library") {
                if (hasLike) {
                    val likeLabel = when {
                        likePending -> pendingLikeLabel
                        liked == null -> checkLikeLabel
                        liked -> "Unlike song"
                        else -> "Like song"
                    }
                    SongActionRow(likeLabel, if (liked == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        enabled = actionsEnabled && !likePending &&
                            (if (liked == null) likeStatusEnabled && onRetryLike != null else onToggleLike != null),
                        onClick = { if (liked == null) onRetryLike?.invoke() else onToggleLike?.invoke() })
                }
                addToPlaylist?.let { SongActionRow("Add to playlist", Icons.AutoMirrored.Filled.PlaylistAdd,
                    enabled = actionsEnabled, onClick = it) }
                likeMessage?.let { Text(it) }
                libraryContent?.invoke()
            }
        }
        // Credits/album already have destinations below. Keep track details only for actual
        // track-specific metadata, never as a duplicate route to the containing album.
        val hasTrackMetadata = item.genres.any { it.isNotBlank() } || item.discNumber > 0 || item.trackNumber > 0
        val openDetails = onOpen.takeUnless { item.kind == "track" && trackMetadataComplete && !hasTrackMetadata }
        if (openDetails != null || onOpenItem != null && (item.artists.isNotEmpty() ||
                item.kind == "track" && !item.albumName.isNullOrBlank())) {
            SongActionGroup("Navigation") {
                openDetails?.let { SongActionRow(if (item.kind == "track") "Song information" else "Open details",
                    Icons.Default.Info, actionsEnabled, onClick = it) }
                onOpenItem?.let { SongActionNavigation(item, actionsEnabled, onOpenItem = it) }
            }
        }
        contextualContent?.invoke()
    }
    if (artwork == null) SongActionLayout(item, modifier) { content() }
    else SongActionLayout(item, modifier, artwork) { content() }
}
