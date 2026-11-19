package dev.spofify.mobile.feature.personal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
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
import dev.spofify.mobile.playback.DevicePersonalLibrary

/** Shared playlist menu for the list and detail screens. */
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
    onOfflineRemoved: (() -> Unit)? = null,
) {
    val summary = state.summary
    val offline = music?.offlinePlaylists
    val wanted = offline?.wanted?.collectAsStateWithLifecycle()?.value
    val pending = offline?.pending?.collectAsStateWithLifecycle()?.value.orEmpty()
    val checked = summary?.id in wanted.orEmpty()
    val removed by rememberUpdatedState(onOfflineRemoved)
    LaunchedEffect(summary?.id, checked) {
        // Offline screens show complete downloads only; after removing one, leave/refresh it.
        if (wanted != null && !checked) removed?.invoke()
    }
    SpofifySheet(onDismiss) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 22.dp)) {
            item("heading") {
                SheetHeading(summary?.name ?: "Playlist")
                PersonalStatus(state.loading || state.writing, state.error ?: state.message)
            }
            if (state.rows.isNotEmpty() || summary?.trackCount?.let { it > 0 } == true) {
                item("play") { SheetAction("Play now", SpofifyGlyph.Play, playable, onPlay) }
                item("queue") { SheetAction("Queue", SpofifyGlyph.Queue, playable, onQueue) }
            }
            if (summary != null) {
                item("library") { SheetGroup("Library") }
                if (offline != null) item("save-offline") {
                    val enabled = wanted != null && summary.id !in pending
                    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp)
                        .toggleable(checked, enabled = enabled, role = Role.Switch) { offline.set(summary.id, it) }
                        .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        SpofifyIcon(SpofifyGlyph.Down, null, Modifier.size(22.dp))
                        Text("Save for offline", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        SpofifySwitchIndicator(checked, enabled)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
            onDismiss = onDismiss,
            onOfflineRemoved = if (api is DevicePersonalLibrary) ({ onDismiss(); onMutationAcknowledged() }) else null)
    }
}
