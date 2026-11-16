package dev.spofify.mobile.feature.personal

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import dev.spofify.mobile.feature.library.MusicCollectionControls
import dev.spofify.mobile.feature.library.MusicRowGeometry
import dev.spofify.mobile.designsystem.SpofifyGlyph
import dev.spofify.mobile.designsystem.SpofifyIcon

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
    val largeText = LocalDensity.current.fontScale >= 1.5f
    var menu by remember(model) { mutableStateOf(false) }
    var songMenu by remember(model) { mutableStateOf<PlaylistSong?>(null) }
    var renaming by remember(model) { mutableStateOf<PersonalPlaylistSummary?>(null) }
    var deleting by remember(model) { mutableStateOf<PersonalPlaylistSummary?>(null) }
    var removing by remember(model) { mutableStateOf<Pair<PlaylistSong, Long>?>(null) }
    var actionError by remember(model) { mutableStateOf<String?>(null) }
    val send: (() -> String?) -> Unit = { action -> actionError = if (action() == null) "Playback action wasn't sent." else null }
    val playRow: (PlaylistSong) -> Unit = { row ->
        val reference = row.takeIf { it in state.rows }?.reference(context)
        if (playable && reference != null) send { playback?.playTrack(reference, context) }
        else actionError = "This song isn't available to play."
    }
    val queueRow: (PlaylistSong) -> Unit = { row ->
        if (playable && row in state.rows) row.reference(context)?.let { reference -> send { playback?.queueTrack(reference, context) } }
    }
    LaunchedEffect(model) { if (model.state.value.loaded) model.refresh() }
    LaunchedEffect(state.context, state.rows) { songMenu = null }
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    BackHandler(onBack = onBack)

    PullToRefreshBox(state.loading, onRefresh = { if (!state.writing) model.refresh() }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 20.dp, bottom = 28.dp)) {
            item("heading") {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                    verticalAlignment = if (largeText) Alignment.Top else Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                    PlaylistArtwork(Modifier.size(if (largeText) 52.dp else 76.dp), large = true)
                    Column(Modifier.weight(1f)) {
                        Text(summary?.name ?: "Playlist", style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = 22.5.sp, fontWeight = FontWeight(650), lineHeight = 26.1.sp), modifier = Modifier.semantics { heading() })
                        summary?.let { Text(playlistCount(it), Modifier.padding(top = 3.dp), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            item("controls") {
                MusicCollectionControls(Modifier.padding(vertical = 12.dp),
                    play = {
                        if (state.rows.isNotEmpty()) FilledIconButton({ context?.let { send { playback?.playCollection(it) } } },
                            Modifier.size(MusicRowGeometry.ARTWORK_SLOT.dp), enabled = playable) {
                            SpofifyIcon(SpofifyGlyph.Play, "Play ${summary?.name.orEmpty()}", Modifier.size(24.dp))
                        }
                    },
                    more = {
                        IconButton({ menu = true }, Modifier.size(MusicRowGeometry.ACTION_SLOT.dp)) {
                            SpofifyIcon(SpofifyGlyph.More, "More actions for ${summary?.name ?: "playlist"}", Modifier.size(24.dp))
                        }
                    })
            }
            val failure = state.error ?: state.message ?: actionError ?: player?.error ?:
                if (state.loaded && !state.completeCollection) "Rules result unavailable." else null
            if (failure != null || state.writing) item("failure") {
                Box(Modifier.padding(horizontal = 20.dp)) {
                    PersonalStatus(state.writing, failure) {
                        actionError = null
                        model.refresh()
                    }
                }
            }
            if (state.rows.isNotEmpty() || state.filter.isNotBlank()) item("filter") {
                PersonalSongFilter(state.filter, model::filter)
            }
            items(state.visibleRows, key = { it.identity }) { row ->
                PersonalSongRow(row.track, loader, onPlay = { playRow(row) }, onMore = { songMenu = row },
                    onQueue = if (playable && row.reference(context) != null) ({ queueRow(row) }) else null,
                    gestureBinding = personalGestureBinding(row, context, player))
            }
            if (state.noMatches) item("no-matches") {
                Text("No matches", Modifier.padding(horizontal = 20.dp, vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (state.loaded && state.completeCollection && state.rows.isEmpty()) item("empty") {
                Text("No songs yet", Modifier.padding(horizontal = 20.dp, vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (menu) PlaylistCollectionSheet(state, playable,
        onPlay = {
            menu = false
            context?.let { send { playback?.playCollection(it) } }
        },
        onQueue = {
            menu = false
            context?.let { send { playback?.queueCollection(it) } }
        },
        onEdit = {
            menu = false
            renaming = summary
        },
        onDelete = {
            menu = false
            deleting = summary
        },
        onDismiss = { menu = false }, onRetry = model::refresh)
    songMenu?.let { row ->
        PersonalSongActions(api, row.track, loader, playable && row in state.rows && row.reference(context) != null,
            onPlay = { playRow(row) }, onQueue = { queueRow(row) }, onNavigate = onNavigate,
            onRemove = if (state.canEdit && summary?.capabilities?.editEntries == true && row.entryId != null) ({
                removing = row to requireNotNull(summary).revision
            }) else null, onDismiss = { songMenu = null })
    }
    renaming?.let { original ->
        PlaylistNameSheet("Edit details", original.name, original.description, onDismiss = { renaming = null }) { name, description ->
            renaming = null
            model.rename(name, description, original.revision)
        }
    }
    deleting?.let { original ->
        PersonalConfirmSheet("Delete playlist?", "This removes the playlist, not the songs.", "Delete", onDismiss = { deleting = null }) {
            deleting = null
            model.delete(original.revision)
        }
    }
    removing?.let { (row, revision) ->
        PersonalConfirmSheet("Remove this entry?", "Other occurrences and music files are unchanged.", "Remove", onDismiss = { removing = null }) {
            removing = null
            model.remove(row, revision)
        }
    }
}
