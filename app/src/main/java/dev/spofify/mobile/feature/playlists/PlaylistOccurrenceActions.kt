package dev.spofify.mobile.feature.playlists

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import dev.spofify.mobile.ui.*
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.SpofifyClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Generic track/album playback must not replace a signed derived occurrence selection. */
@Composable
internal fun PlaylistOccurrenceActions(
    client: SpofifyClient, track: LibraryItem, onDismiss: () -> Unit,
    onOpen: () -> Unit, onAdd: () -> Unit, onPersonalChange: () -> Unit,
    playbackEnabled: Boolean, contextualLabel: String, onPlayNow: () -> Unit,
    onContextual: (() -> Unit)?, onEnqueue: (() -> Unit)?, onPlayNext: (() -> Unit)?,
    onOpenItem: (LibraryItem) -> Unit,
    onRemove: (() -> Unit)? = null,
    removeEnabled: Boolean = true,
    isCurrent: () -> Boolean = { true },
    artwork: (@Composable (LibraryItem, Modifier) -> Unit)? = null,
) {
    var liked by remember(client, track.id) { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var statusRefresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(isCurrent)
    val actionsEnabled = !busy && isCurrent()
    fun perform(action: () -> Unit) { if (current()) action() }
    LaunchedEffect(client, track.id, statusRefresh) {
        busy = true
        try { liked = client.likeStatus(track.id); message = null }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { liked = null; message = "Like status could not load. Check it again explicitly." }
        finally { busy = false }
    }
    SongActionSheet(onDismiss = onDismiss, dismissEnabled = !busy) {
        PlaylistOccurrenceActionsContent(track, liked, busy, message, playbackEnabled, actionsEnabled,
            onOpen = { perform(onOpen) }, onAdd = { perform(onAdd) },
            onPlayNow = { perform(onPlayNow) },
            onContextual = onContextual?.let { action -> { perform(action) } },
            onEnqueue = onEnqueue?.let { action -> { perform(action) } },
            onPlayNext = onPlayNext?.let { action -> { perform(action) } },
            onOpenItem = { item -> perform { onOpenItem(item) } },
            onRemove = onRemove?.let { action -> { perform(action) } }, removeEnabled = removeEnabled,
            onCheckLike = { perform { statusRefresh++ } },
            onToggleLike = {
                if (current() && !busy && liked != null) {
                    val wanted = !liked!!
                    busy = true
                    scope.launch {
                        try {
                            client.setLiked(track.id, wanted)
                            liked = wanted; onPersonalChange(); message = null
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) {
                            liked = null
                            message = "The like response was not confirmed. Check saved status before another change; nothing was resubmitted."
                        } finally { busy = false }
                    }
                }
            }, artwork = artwork)
    }
}

/** Production body for supported component hosts too; modal/runtime acceptance remains separate. */
@Composable
internal fun PlaylistOccurrenceActionsContent(
    track: LibraryItem, liked: Boolean?, busy: Boolean, message: String?,
    playbackEnabled: Boolean, actionsEnabled: Boolean,
    onOpen: () -> Unit, onAdd: () -> Unit, onPlayNow: () -> Unit,
    onContextual: (() -> Unit)?, onEnqueue: (() -> Unit)?, onPlayNext: (() -> Unit)?,
    onOpenItem: (LibraryItem) -> Unit, onRemove: (() -> Unit)?, removeEnabled: Boolean,
    onCheckLike: () -> Unit, onToggleLike: () -> Unit,
    artwork: (@Composable (LibraryItem, Modifier) -> Unit)? = null,
) {
    val content: @Composable () -> Unit = {
        SongActionGroup("Playback", divider = false) {
            SongActionRow("Play now", Icons.Default.PlayArrow, playbackEnabled && actionsEnabled, onClick = onPlayNow)
            onPlayNext?.let { action -> SongActionRow("Play next", Icons.Default.SkipNext,
                playbackEnabled && actionsEnabled, onClick = action) }
            onContextual?.let { action -> SongActionRow("Queue High", Icons.AutoMirrored.Filled.PlaylistPlay,
                playbackEnabled && actionsEnabled, onClick = action) }
            // Priority remains explicit and independent of the output's play/pause state.
            onEnqueue?.let { action -> SongActionRow("Queue Low", Icons.AutoMirrored.Filled.QueueMusic,
                playbackEnabled && actionsEnabled, onClick = action) }
        }
        SongActionGroup("Library") {
            if (liked == null) SongActionRow(if (busy) "Checking like status" else "Check like status",
                Icons.Default.FavoriteBorder, actionsEnabled, onClick = onCheckLike)
            else SongActionRow(if (busy) "Saving like" else if (liked == true) "Unlike song" else "Like song",
                if (liked == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                enabled = actionsEnabled, onClick = onToggleLike)
            SongActionRow("Add to playlist", Icons.AutoMirrored.Filled.PlaylistAdd, actionsEnabled, onClick = onAdd)
            message?.let { Text(it) }
        }
        SongActionGroup("Navigation") {
            SongActionRow("Open track details", Icons.Default.Info, actionsEnabled, onClick = onOpen)
            SongActionNavigation(track, actionsEnabled, onOpenItem)
        }
        SongActionRemoval(removeEnabled && actionsEnabled, onRemove)
    }
    if (artwork == null) SongActionLayout(track) { content() }
    else SongActionLayout(track, artwork = artwork) { content() }
}
