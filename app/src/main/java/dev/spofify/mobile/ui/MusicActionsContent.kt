package dev.spofify.mobile.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.spofify.mobile.core.LibraryItem

/** The actual More sheet body: every action and credit stays reachable without shrinking targets. */
@Composable
internal fun MusicActionsContent(
    item: LibraryItem,
    liked: Boolean?,
    likePending: Boolean,
    likeFailed: Boolean,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onEnqueue: () -> Unit,
    onToggleLike: () -> Unit,
    onRetryLike: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onOpenItem: (LibraryItem) -> Unit,
    modifier: Modifier = Modifier,
    contextualLabel: String = "Play now",
    onContextual: (() -> Unit)? = null,
    onQueueLow: (() -> Unit)? = null,
    artwork: (@Composable (LibraryItem, Modifier) -> Unit)? = null,
) {
    val content: @Composable () -> Unit = {
        SongActionGroup("Playback", divider = false) {
            SongActionRow("Play now", Icons.Default.PlayArrow, enabled = item.playable, onClick = onPlay)
            SongActionRow("Play next", Icons.Default.SkipNext, enabled = item.playable, onClick = onPlayNext)
            SongActionRow("Queue High", Icons.AutoMirrored.Filled.PlaylistPlay, enabled = item.playable, onClick = onContextual ?: onEnqueue)
            onQueueLow?.let { action -> SongActionRow("Queue Low", Icons.AutoMirrored.Filled.QueueMusic, enabled = item.playable, onClick = action) }
        }
        if (item.kind == "track") {
            val likeLabel = when {
                likePending -> "Saving like"
                liked == null && likeFailed -> "Retry like status"
                liked == null -> "Checking like status"
                liked -> "Unlike song"
                else -> "Like song"
            }
            SongActionGroup("Library") {
                SongActionRow(likeLabel, if (liked == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    enabled = !likePending && (liked != null || likeFailed),
                    onClick = { if (liked == null) onRetryLike() else onToggleLike() })
                SongActionRow("Add to playlist", Icons.AutoMirrored.Filled.PlaylistAdd, onClick = onAddToPlaylist)
            }
        }
        SongActionGroup("Navigation") {
            SongActionRow("Open details", Icons.Default.Info, onClick = { onOpenItem(item) })
            SongActionNavigation(item, onOpenItem = onOpenItem)
        }
    }
    if (artwork == null) SongActionLayout(item, modifier) { content() }
    else SongActionLayout(item, modifier, artwork) { content() }
}
