package dev.spofify.mobile.feature.personal

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.ImageLoader
import dev.spofify.mobile.core.library.MusicKind
import dev.spofify.mobile.core.personal.*
import dev.spofify.mobile.core.playback.PlaybackConnectionState
import dev.spofify.mobile.core.playback.PlaybackController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    api: PersonalLibraryClient,
    id: String,
    loader: ImageLoader,
    playback: PlaybackController?,
    onBack: () -> Unit,
    onNavigate: (MusicKind, String) -> Unit,
) {
    val owner = requireNotNull(LocalViewModelStoreOwner.current)
    val model: PlaylistDetailViewModel = viewModel(owner, key = "playlist-${System.identityHashCode(api)}-$id",
        factory = viewModelFactory { initializer { PlaylistDetailViewModel(api, id) } })
    val state by model.state.collectAsStateWithLifecycle()
    val player = playback?.state?.collectAsStateWithLifecycle()?.value
    val ready = player?.connection == PlaybackConnectionState.READY
    val context = state.context
    val summary = state.summary
    val playable = ready && context != null && !state.loading && !state.writing && !state.needsRefresh &&
        summary?.capabilities?.play == true && state.rows.isNotEmpty()
    var menu by remember(model) { mutableStateOf(false) }
    var songMenu by remember(model) { mutableStateOf<PlaylistSong?>(null) }
    var renaming by remember(model) { mutableStateOf<PersonalPlaylistSummary?>(null) }
    var deleting by remember(model) { mutableStateOf<PersonalPlaylistSummary?>(null) }
    var removing by remember(model) { mutableStateOf<Pair<PlaylistSong, Long>?>(null) }
    var actionError by remember(model) { mutableStateOf<String?>(null) }
    val send: (() -> String?) -> Unit = { action ->
        actionError = if (action() == null) "Playback action wasn't sent. Check the playback connection." else null
    }
    val playRow: (PlaylistSong) -> Unit = { row ->
        val reference = row.takeIf { it in state.rows }?.reference(context)
        when {
            !row.track.hasAudio -> actionError = "Audio is unavailable for this song."
            !playable || reference == null -> actionError = "Refresh this playlist and connect playback before listening."
            else -> send { playback?.playTrack(reference, context) }
        }
    }
    val queueRow: (PlaylistSong, Boolean) -> Unit = { row, next ->
        if (playable && row in state.rows) row.reference(context)?.let { reference ->
            send { playback?.queueTrack(reference, context, next) }
        }
    }
    LaunchedEffect(model) { if (model.state.value.loaded) model.refresh() }
    LaunchedEffect(state.context, state.rows) { songMenu = null }
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    BackHandler(onBack = onBack)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 28.dp)) {
        item("back") { TextButton(onBack, Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(0.dp)) { Text("Back") } }
        item("heading") {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(76.dp).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    Text("≡", style = MaterialTheme.typography.headlineLarge)
                }
                Column(Modifier.weight(1f)) {
                    Text(summary?.name ?: "Playlist", style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() })
                    summary?.let {
                        Text(playlistCount(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (it.kind == PersonalPlaylistKind.DERIVED) Text("Rules playlist", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (!summary?.description.isNullOrBlank()) Text(summary!!.description, Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.bodySmall)
        }
        item("controls") {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    FilledIconButton({ context?.let { send { playback?.playCollection(it) } } }, Modifier.size(48.dp), enabled = playable) {
                        Text("▶", Modifier.semantics { contentDescription = "Play ${summary?.name.orEmpty()}" })
                    }
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    IconButton({ menu = true }, Modifier.size(48.dp).semantics {
                        contentDescription = "More playlist actions"
                    }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                }
            }
        }
        item("status") {
            PersonalStatus(state.loading, state.error, model::refresh)
            if (state.writing) LinearProgressIndicator(Modifier.fillMaxWidth())
            listOfNotNull(state.message, actionError, player?.error).distinct().forEach {
                Text(it, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.loaded && !state.completeCollection && summary?.kind == PersonalPlaylistKind.DERIVED) {
                Text("Rules results aren't ready. Stored rows are shown without playback authority; refresh to check again.",
                    Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall)
            } else if (state.loaded && !ready) {
                Text("Connect playback to listen.", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (state.rows.isNotEmpty() || state.filter.isNotBlank()) item("filter") {
            HorizontalDivider()
            BasicTextField(state.filter, model::filter,
                Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 12.dp).semantics { contentDescription = "Filter songs" },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface), singleLine = true,
                decorationBox = { field ->
                    if (state.filter.isEmpty()) Text("Filter songs", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    field()
                })
        }
        items(state.visibleRows, key = { it.identity }) { row ->
            PersonalSongRow(row.track, loader, onPlay = { playRow(row) }, onMore = { songMenu = row })
        }
        if (state.noMatches) item("no-matches") { Text("No matches", Modifier.padding(vertical = 20.dp)) }
        else if (state.loaded && !state.completeCollection && state.filter.isNotBlank() && state.visibleRows.isEmpty()) item("incomplete-filter") {
            Text("No matches in these stored rows. Refresh to search the complete playlist.", Modifier.padding(vertical = 20.dp))
        }
        else if (state.loaded && state.completeCollection && state.rows.isEmpty()) item("empty") {
            Text("No songs yet", Modifier.padding(vertical = 20.dp))
        }
        item("refresh") { PersonalAction("Refresh playlist", !state.loading && !state.writing, model::refresh) }
    }

    if (menu) ModalBottomSheet(onDismissRequest = { menu = false }) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            item("heading") { Text(summary?.name ?: "Playlist", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 12.dp).semantics { heading() }) }
            item("play") { PersonalAction("Play now", playable) {
                menu = false
                context?.let { send { playback?.playCollection(it) } }
            } }
            item("shuffle") { PersonalAction("Shuffle play", playable) {
                menu = false
                context?.let { send { playback?.playCollection(it, shuffle = true) } }
            } }
            item("queue") { PersonalAction("Queue", playable) {
                menu = false
                context?.let { send { playback?.queueCollection(it) } }
            } }
            item("next") { PersonalAction("Play next", playable) {
                menu = false
                context?.let { send { playback?.queueCollection(it, next = true) } }
            } }
            item("rename") { PersonalAction("Edit details", state.canEdit && summary?.capabilities?.editMetadata == true) {
                menu = false
                renaming = summary
            } }
            item("delete") { PersonalAction("Delete playlist", state.canEdit && summary?.capabilities?.delete == true) {
                menu = false
                deleting = summary
            } }
            item("refresh") { PersonalAction("Refresh", !state.loading && !state.writing) {
                menu = false
                model.refresh()
            } }
        }
    }
    songMenu?.let { row ->
        PersonalSongActions(api, row.track, loader, playable && row in state.rows && row.reference(context) != null,
            onPlay = { playRow(row) }, onQueue = { queueRow(row, false) }, onPlayNext = { queueRow(row, true) }, onNavigate = onNavigate,
            onRemove = if (state.canEdit && summary?.capabilities?.editEntries == true && row.entryId != null) ({
                removing = row to requireNotNull(summary).revision
            }) else null,
            onDismiss = { songMenu = null })
    }
    renaming?.let { original ->
        PlaylistMetadataDialog("Edit playlist details", original.name, original.description, onDismiss = { renaming = null }) { name, description ->
            renaming = null
            model.rename(name, description, original.revision)
        }
    }
    deleting?.let { original ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete ${original.name}?") },
            text = { Text("The playlist will be deleted. Its music files aren't removed.") },
            confirmButton = { TextButton({
                deleting = null
                model.delete(original.revision)
            }) { Text("Delete") } },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } })
    }
    removing?.let { (row, revision) ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove this entry?") },
            text = { Text("Remove this occurrence of ${row.track.name}? Other copies of the song stay in the playlist.") },
            confirmButton = { TextButton({
                removing = null
                model.remove(row, revision)
            }) { Text("Remove") } },
            dismissButton = { TextButton({ removing = null }) { Text("Cancel") } })
    }
}
