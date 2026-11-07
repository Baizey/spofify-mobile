package dev.spofify.mobile.feature.playlists

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
    playbackEnabled: Boolean, onPlayNow: () -> Unit,
    onQueue: (() -> Unit)?, onPlayNext: (() -> Unit)?,
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
            onQueue = onQueue?.let { action -> { perform(action) } },
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
    onQueue: (() -> Unit)?, onPlayNext: (() -> Unit)?,
    onOpenItem: (LibraryItem) -> Unit, onRemove: (() -> Unit)?, removeEnabled: Boolean,
    onCheckLike: () -> Unit, onToggleLike: () -> Unit,
    artwork: (@Composable (LibraryItem, Modifier) -> Unit)? = null,
) {
    MusicActionsContent(track, liked, likePending = busy,
        onPlay = onPlayNow, onPlayNext = onPlayNext, onQueue = onQueue,
        onToggleLike = onToggleLike, onRetryLike = onCheckLike, onAddToPlaylist = onAdd,
        onOpenItem = onOpenItem, onOpen = onOpen,
        playbackEnabled = playbackEnabled, actionsEnabled = actionsEnabled,
        pendingLikeLabel = if (liked == null) "Checking like status" else "Saving like",
        checkLikeLabel = "Check like status", likeStatusEnabled = true, likeMessage = message,
        contextualContent = { SongActionRemoval(removeEnabled && actionsEnabled, onRemove) },
        artwork = artwork)
}
