package dev.spofify.mobile.feature.personal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
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
import dev.spofify.mobile.core.library.MusicItem
import dev.spofify.mobile.core.library.MusicKind
import dev.spofify.mobile.playback.LivePersonal
import dev.spofify.mobile.core.personal.PersonalPlaylistSummary
import dev.spofify.mobile.playback.AppPlayback
import dev.spofify.mobile.playback.PlaylistOfflineStatus
import dev.spofify.mobile.designsystem.*
import dev.spofify.mobile.feature.library.MusicRowGeometry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistsScreen(
    api: LivePersonal,
    loader: ImageLoader,
    playback: AppPlayback?,
    music: dev.spofify.mobile.playback.AccountMusic?,
    onOpenPlaylist: (String) -> Unit,
    onNavigate: (MusicKind, String) -> Unit,
    onDownloads: () -> Unit,
) {
    val owner = requireNotNull(LocalViewModelStoreOwner.current)
    val model: PlaylistsViewModel = viewModel(owner, key = "playlists-${System.identityHashCode(api)}",
        factory = viewModelFactory { initializer { PlaylistsViewModel(api) } })
    val state by model.state.collectAsStateWithLifecycle()
    val offlineStatuses = music?.offlinePlaylistStatuses?.collectAsStateWithLifecycle()?.value.orEmpty()
    val player = playback?.observe()
    val ready = player?.canLoadTrack == true
    var creating by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf<PersonalPlaylistSummary?>(null) }
    val queue: (PersonalPlaylistSummary) -> Unit = { summary ->
        if (playback?.presentation?.canLoadTrack == true && model.state.value.items.any { it.id == summary.id }) {
            playback.queueCollection(playlistContext(summary))
        }
    }
    LaunchedEffect(model) { model.refresh() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Playlists", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
            IconButton({ creating = true }, enabled = state.canCreate, modifier = Modifier.size(48.dp)) {
                SpofifyIcon(SpofifyGlyph.Plus, "Create playlist", Modifier.size(24.dp))
            }
        }
        TextButton(onDownloads, Modifier.padding(start = 12.dp)) { Text("Downloads") }
        state.message?.let { message -> Box(Modifier.padding(horizontal = 20.dp)) { PersonalStatus(false, message) } }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.loaded && state.items.isEmpty() && state.message == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No playlists", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else LazyColumn(Modifier.fillMaxSize(), reverseLayout = true,
                contentPadding = PaddingValues(top = 20.dp, bottom = 8.dp)) {
                // Ascending state is used directly. reverseLayout puts A at the bottom, Z at the top.
                items(state.items, key = { it.id }) { summary ->
                    PlaylistSummaryRow(summary,
                        offlineStatus = offlineStatuses[summary.id],
                        onQueue = if (ready && summary.capabilities.play && summary.trackCount?.let { it > 0 } == true) ({ queue(summary) }) else null,
                        onMore = { more = summary }, gestureBinding = playback to personalGestureBinding(summary, null, player?.view)) { onOpenPlaylist(summary.id) }
                }
            }
        }
    }
    if (creating) PlaylistNameSheet("New playlist", onDismiss = { creating = false }) { name, description ->
        creating = false
        model.create(name, description)
    }
    more?.let { selection ->
        state.items.firstOrNull { it.id == selection.id }?.let { summary ->
            PlaylistSummaryActions(api, summary, playback, music, onDismiss = { more = null })
        }
    }
}

@Composable
internal fun PlaylistSummaryRow(
    summary: PersonalPlaylistSummary,
    offlineStatus: PlaylistOfflineStatus? = null,
    enabled: Boolean = true,
    onQueue: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    gestureBinding: Any = summary,
    onClick: () -> Unit,
) {
    val geometry = MusicRowGeometry()
    SwipeActionSurface(binding = gestureBinding, onRight = onQueue,
        excludeEnd = geometry.swipeEndExclusion(onMore != null).dp) {
        Column(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick)) {
            Row(Modifier.fillMaxWidth().heightIn(min = MusicRowGeometry.MIN_HEIGHT.dp)
                .padding(horizontal = geometry.gutter.dp, vertical = MusicRowGeometry.VERTICAL_PADDING.dp), verticalAlignment = Alignment.CenterVertically) {
                PlaylistOfflineIndicator(offlineStatus, Modifier.size(MusicRowGeometry.ARTWORK_SLOT.dp), tile = true)
                Spacer(Modifier.width(geometry.identityGap.dp))
                Column(Modifier.weight(1f)) {
                    Text(summary.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.7.sp, lineHeight = 19.11.sp, fontWeight = FontWeight(560)))
                    Text(playlistCount(summary), Modifier.padding(top = 3.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.8.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(Modifier.size(MusicRowGeometry.ACTION_SLOT.dp)) {
                    onMore?.let { action -> IconButton(action, Modifier.fillMaxSize()) {
                        SpofifyIcon(SpofifyGlyph.More, "More actions for ${summary.name}", Modifier.size(24.dp))
                    } }
                }
            }
            ListRowDivider()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddTrackToPlaylistSheet(
    api: LivePersonal,
    track: MusicItem,
    onDismiss: () -> Unit,
) {
    val owner = requireNotNull(LocalViewModelStoreOwner.current)
    val model: PlaylistsViewModel = viewModel(owner, key = "playlist-picker-${System.identityHashCode(api)}-${track.id}",
        factory = viewModelFactory { initializer { PlaylistsViewModel(api, manualOnly = true) } })
    val state by model.state.collectAsStateWithLifecycle()
    val scroll = rememberLazyListState()
    var creating by remember { mutableStateOf(false) }
    val priorSent = remember(model) { model.state.value.addSent }
    LaunchedEffect(state.addSent) { if (state.addSent != priorSent) onDismiss() }
    LaunchedEffect(model) { model.refresh() }
    if (creating) PlaylistNameSheet("New playlist", onDismiss = { creating = false }) { name, description ->
        creating = false
        model.create(name, description)
    } else SpofifySheet(onDismiss) {
        Box {
            LazyColumn(Modifier.fillMaxWidth(), state = scroll,
                contentPadding = PaddingValues(bottom = 22.dp)) {
                item("heading") {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        SheetHeading("Add to playlist")
                        PersonalStatus(false, state.message)
                    }
                    SheetListAction("New playlist", SpofifyGlyph.Plus, state.canCreate) { creating = true }
                }
                items(state.items.filter { it.capabilities.addTracks }, key = { it.id }) { summary ->
                    SheetListAction(summary.name, SpofifyGlyph.Playlists,
                        state.canCreate && track.kind == MusicKind.TRACK && track.hasAudio) {
                        model.add(track.id, summary)
                    }
                }
                if (state.loaded && state.message == null && state.items.none { it.capabilities.addTracks }) item("empty") {
                    Text("No playlists", Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
