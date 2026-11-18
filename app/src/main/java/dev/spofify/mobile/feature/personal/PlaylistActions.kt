package dev.spofify.mobile.feature.personal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.spofify.mobile.core.personal.PersonalLibraryClient
import dev.spofify.mobile.core.personal.PersonalPlaylistKind
import dev.spofify.mobile.core.personal.PersonalPlaylistSummary
import dev.spofify.mobile.core.playback.PlaybackConnectionState
import dev.spofify.mobile.core.playback.PlaybackController
import dev.spofify.mobile.designsystem.*

/** FINAL collection menu: Play and Queue; shuffle belongs to the player, not this sheet. */
@Composable
internal fun PlaylistCollectionSheet(
    state: PlaylistDetailState,
    playable: Boolean,
    onPlay: () -> Unit,
    onQueue: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    val summary = state.summary
    SpofifySheet(onDismiss) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 22.dp)) {
            item("heading") {
                SheetHeading(summary?.name ?: "Playlist")
                PersonalStatus(state.loading || state.writing, state.error ?: state.message, onRetry)
            }
            if (state.rows.isNotEmpty() || summary?.trackCount?.let { it > 0 } == true) {
                item("play") { SheetAction("Play now", SpofifyGlyph.Play, playable, onPlay) }
                item("queue") { SheetAction("Queue", SpofifyGlyph.Queue, playable, onQueue) }
            }
            if (summary != null) {
                item("library") { SheetGroup("Library") }
                if (summary.kind == PersonalPlaylistKind.MANUAL && summary.capabilities.editMetadata) {
                    item("edit") { SheetAction("Edit details", SpofifyGlyph.Edit, state.canEdit, onEdit) }
                }
                if (summary.capabilities.delete) item("delete") {
                    SheetAction("Delete playlist", SpofifyGlyph.Trash, state.canEdit, onDelete)
                }
            }
        }
    }
}

/** A summary's More sheet resolves fresh action authority before enabling musical actions. */
@Composable
internal fun PlaylistSummaryActions(
    api: PersonalLibraryClient,
    summary: PersonalPlaylistSummary,
    playback: PlaybackController?,
    onMutationAcknowledged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val id = summary.id
    val owner = requireNotNull(LocalViewModelStoreOwner.current)
    val model: PlaylistDetailViewModel = viewModel(owner, key = "playlist-menu-${System.identityHashCode(api)}-$id",
        factory = viewModelFactory { initializer { PlaylistDetailViewModel(api, id, onMutationAcknowledged) } })
    val state by model.state.collectAsStateWithLifecycle()
    val player = playback?.state?.collectAsStateWithLifecycle()?.value
    val context = state.context
    val playable = player?.connection == PlaybackConnectionState.READY && context != null &&
        !state.loading && !state.writing && !state.needsRefresh && state.rows.isNotEmpty() && state.summary?.capabilities?.play == true
    var editing by remember { mutableStateOf<PersonalPlaylistSummary?>(null) }
    var deleting by remember { mutableStateOf<PersonalPlaylistSummary?>(null) }
    LaunchedEffect(model) { if (model.state.value.loaded) model.refresh() }
    LaunchedEffect(state.deleted) { if (state.deleted) onDismiss() }
    when {
        editing != null -> {
            val original = requireNotNull(editing)
            PlaylistNameSheet("Edit details", original.name, original.description, onDismiss = { editing = null }) { name, description ->
                editing = null
                model.rename(name, description, original.revision)
            }
        }
        deleting != null -> {
            val original = requireNotNull(deleting)
            PersonalConfirmSheet("Delete playlist?", "This removes the playlist, not the songs.", "Delete",
                onDismiss = { deleting = null }) {
                deleting = null
                model.delete(original.revision)
            }
        }
        else -> PlaylistCollectionSheet(if (state.summary == null) state.copy(summary = summary) else state, playable,
            onPlay = {
                if (context != null && playback?.playCollection(context) != null) onDismiss()
            },
            onQueue = {
                if (context != null && playback?.queueCollection(context) != null) onDismiss()
            },
            onEdit = { editing = state.summary }, onDelete = { deleting = state.summary },
            onDismiss = onDismiss, onRetry = model::refresh)
    }
}
