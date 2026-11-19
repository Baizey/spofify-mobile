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
import dev.spofify.mobile.core.personal.PersonalLibraryApi
import dev.spofify.mobile.core.personal.PersonalPlaylistKind
import dev.spofify.mobile.core.personal.PersonalPlaylistSummary
import dev.spofify.mobile.playback.AppPlayback
import dev.spofify.mobile.designsystem.*
import dev.spofify.mobile.playback.AccountMusic

/** FINAL collection menu: Play and Queue; shuffle belongs to the player, not this sheet. */
@Composable
internal fun PlaylistCollectionSheet(
    state: PlaylistDetailState,
    playable: Boolean,
    music: AccountMusic?,
    onPlay: () -> Unit,
    onQueue: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    val summary = state.summary
    val saved = music?.saved?.collectAsStateWithLifecycle()?.value?.any { it.collection.reference.id == summary?.id } == true
    val saving = music?.saving?.collectAsStateWithLifecycle()?.value?.contains(summary?.id) == true
    val failed = music?.failed?.collectAsStateWithLifecycle()?.value?.contains(summary?.id) == true
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
                if (music != null && (saved || summary.capabilities.play && state.completeCollection && state.rows.isNotEmpty() && state.rows.all { it.track.hasAudio })) {
                    item("save-offline") { SheetAction(when {
                        saved -> "Saved for offline"
                        saving -> "Saving for offline…"
                        failed -> "Retry saving for offline"
                        else -> "Save for offline"
                    }, if (saved) SpofifyGlyph.Check else SpofifyGlyph.Down, !saved && !saving) { music.save(summary.id) } }
                }
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
    api: PersonalLibraryApi,
    summary: PersonalPlaylistSummary,
    playback: AppPlayback?,
    music: AccountMusic?,
    onMutationAcknowledged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val id = summary.id
    val owner = requireNotNull(LocalViewModelStoreOwner.current)
    val model: PlaylistDetailViewModel = viewModel(owner, key = "playlist-menu-${System.identityHashCode(api)}-$id",
        factory = viewModelFactory { initializer { PlaylistDetailViewModel(api, id, onMutationAcknowledged) } })
    val state by model.state.collectAsStateWithLifecycle()
    val player = playback?.observe()
    val context = state.context
    val playable = player?.ready == true && context != null &&
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
        else -> PlaylistCollectionSheet(if (state.summary == null) state.copy(summary = summary) else state, playable, music,
            onPlay = {
                if (context != null && playback?.playCollection(context) == true) onDismiss()
            },
            onQueue = {
                if (context != null && playback?.queueCollection(context) == true) onDismiss()
            },
            onEdit = { editing = state.summary }, onDelete = { deleting = state.summary },
            onDismiss = onDismiss, onRetry = model::refresh)
    }
}
