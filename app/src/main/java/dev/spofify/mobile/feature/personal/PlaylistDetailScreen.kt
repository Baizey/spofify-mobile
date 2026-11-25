package dev.spofify.mobile.feature.personal

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import dev.spofify.mobile.playback.AppPlayback
import dev.spofify.mobile.feature.library.MusicRowGeometry
import dev.spofify.mobile.designsystem.SpofifyGlyph
import dev.spofify.mobile.designsystem.SpofifyIcon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistDetailScreen(
    api: dev.spofify.mobile.playback.LivePersonal,
    id: String,
    loader: ImageLoader,
    playback: AppPlayback?,
    music: dev.spofify.mobile.playback.AccountMusic?,
    onBack: () -> Unit,
    onNavigate: (MusicKind, String) -> Unit,
) {
    val owner = requireNotNull(LocalViewModelStoreOwner.current)
    val model: PlaylistDetailViewModel = viewModel(owner, key = "playlist-${System.identityHashCode(api)}-$id",
        factory = viewModelFactory { initializer { PlaylistDetailViewModel(api, id) } })
    val state by model.state.collectAsStateWithLifecycle()
    val player = playback?.observe()
    val ready = player?.canLoadTrack == true
    val context = state.context
    val summary = state.summary
    val playable = ready && context != null &&
        summary?.capabilities?.play == true && state.rows.any { it.track.hasAudio }
    val offlineStatus = music?.offlinePlaylistStatuses?.collectAsStateWithLifecycle()?.value?.get(id)
    val downloaded = music?.downloadedIds?.collectAsStateWithLifecycle()?.value.orEmpty()
    val direct = music?.directDownloads?.collectAsStateWithLifecycle()?.value.orEmpty()
    val downloading = music?.trackDownloads?.collectAsStateWithLifecycle()?.value.orEmpty()
    val canDownload = music?.canManageDownloads?.collectAsStateWithLifecycle()?.value == true
    var menu by remember(model) { mutableStateOf(false) }
    var songMenu by remember(model) { mutableStateOf<PlaylistSong?>(null) }
    var renaming by remember(model) { mutableStateOf<PersonalPlaylistSummary?>(null) }
    var deleting by remember(model) { mutableStateOf<PersonalPlaylistSummary?>(null) }
    var removing by remember(model) { mutableStateOf<PlaylistSong?>(null) }
    var actionError by remember(model) { mutableStateOf<String?>(null) }
    val send: (() -> Boolean?) -> Unit = { action -> action(); actionError = null }
    val playRow: (PlaylistSong) -> Unit = { row ->
        val reference = row.takeIf { it in state.rows }?.reference(context)
        if (playable && reference != null) send { playback?.playTrack(reference, context) }
        else if (row.track.hasAudio && !ready && row in state.rows) onNavigate(MusicKind.TRACK, row.track.id)
        else actionError = if (player?.view?.offline == true) "This song isn’t downloaded." else "Audio unavailable"
    }
    val queueRow: (PlaylistSong) -> Unit = { row ->
        if (playable && row in state.rows) row.reference(context)?.let { reference -> send { playback?.queueTrack(reference, context) } }
    }
    DisposableEffect(model) {
        val watch = model.watch()
        onDispose { watch.close() }
    }
    LaunchedEffect(state.context, state.rows) { songMenu = null }
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    BackHandler(onBack = onBack)

    val visibleRows = state.visibleRows
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(summary?.name ?: "Playlist", style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 22.5.sp, fontWeight = FontWeight(650), lineHeight = 26.1.sp),
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                summary?.let { Text(playlistCount(it), Modifier.padding(top = 3.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (offlineStatus != null) PlaylistOfflineIndicator(offlineStatus, Modifier.widthIn(max = 96.dp))
            IconButton({ menu = true }, Modifier.size(MusicRowGeometry.ACTION_SLOT.dp)) {
                SpofifyIcon(SpofifyGlyph.More, "More actions for ${summary?.name ?: "playlist"}", Modifier.size(24.dp))
            }
        }
        val failure = state.message ?: actionError ?: player?.error
        if (failure != null) Box(Modifier.padding(horizontal = 20.dp)) { PersonalStatus(false, failure) }
        if (state.rows.isNotEmpty() || state.filter.isNotBlank()) PersonalSongFilter(state.filter, model::filter)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val emptyMessage = when {
                state.noMatches -> "No matches"
                state.showEmpty -> "No songs yet"
                else -> null
            }
            if (emptyMessage != null) Text(emptyMessage, Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Index 0 is at the visual bottom. Reverse the lazy indices, not the playlist order.
            LazyColumn(Modifier.fillMaxSize(), reverseLayout = true, contentPadding = PaddingValues(bottom = 8.dp)) {
                items(visibleRows.asReversed(), key = { it.identity }) { row ->
                    PersonalSongRow(row.track, loader, onPlay = { playRow(row) }, onMore = { songMenu = row },
                        onQueue = if (playable && row.reference(context) != null) ({ queueRow(row) }) else null,
                        gestureBinding = playback to personalGestureBinding(row, context, player?.view),
                        downloaded = row.track.id in downloaded, downloading = row.track.id in downloading)
                }
            }
        }
    }

    if (menu) PlaylistCollectionSheet(state, playable, music,
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
        onDismiss = { menu = false })
    songMenu?.let { row ->
        val downloadAction: Pair<String, () -> Unit>? = when {
            music == null -> null
            row.track.id in direct -> "Remove download" to { music.removeDownload(row.track.id) { actionError = "Couldn’t remove download." } }
            canDownload && state.canSaveOnline && row.track.hasAudio && row.track.id !in downloaded -> "Download" to { music.download(row.track, id, row.identity) { actionError = "Couldn’t download this song." } }
            else -> null
        }
        PersonalSongActions(api, row.track, loader, playable && row in state.rows && row.reference(context) != null,
            onPlay = { playRow(row) }, onQueue = { queueRow(row) }, onNavigate = onNavigate,
            onRemove = if (state.canEdit && summary?.capabilities?.editEntries == true && row.entryId != null) ({
                removing = row
            }) else null, onDismiss = { songMenu = null }, downloadAction = downloadAction, downloading = row.track.id in downloading)
    }
    renaming?.let { original ->
        PlaylistNameSheet("Edit details", original.name, original.description, onDismiss = { renaming = null }) { name, description ->
            renaming = null
            model.rename(name, description)
        }
    }
    if (deleting != null) {
        PersonalConfirmSheet("Delete playlist?", "This removes the playlist, not the songs.", "Delete", onDismiss = { deleting = null }) {
            deleting = null
            model.delete()
        }
    }
    removing?.let { row ->
        PersonalConfirmSheet("Remove this entry?", "Other occurrences and music files are unchanged.", "Remove", onDismiss = { removing = null }) {
            removing = null
            model.remove(row)
        }
    }
}
