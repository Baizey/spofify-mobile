package dev.spofify.mobile.feature.personal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import dev.spofify.mobile.core.personal.PersonalLibraryClient
import dev.spofify.mobile.core.personal.PersonalPlaylistSummary
import dev.spofify.mobile.core.playback.PlaybackConnectionState
import dev.spofify.mobile.core.playback.PlaybackController
import dev.spofify.mobile.designsystem.*
import dev.spofify.mobile.feature.library.MusicRowGeometry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(
    api: PersonalLibraryClient,
    loader: ImageLoader,
    playback: PlaybackController?,
    onOpenPlaylist: (String) -> Unit,
    onNavigate: (MusicKind, String) -> Unit,
) {
    val owner = requireNotNull(LocalViewModelStoreOwner.current)
    val model: PlaylistsViewModel = viewModel(owner, key = "playlists-${System.identityHashCode(api)}",
        factory = viewModelFactory { initializer { PlaylistsViewModel(api) } })
    val state by model.state.collectAsStateWithLifecycle()
    val player = playback?.state?.collectAsStateWithLifecycle()?.value
    val ready = player?.connection == PlaybackConnectionState.READY
    val scope = rememberCoroutineScope()
    var queueJob by remember { mutableStateOf<Job?>(null) }
    var queueError by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf<PersonalPlaylistSummary?>(null) }
    val queue: (PersonalPlaylistSummary) -> Unit = { summary ->
        if (queueJob?.isActive != true) {
            queueError = null
            val captured = personalGestureBinding(summary, null, playback?.state?.value)
            queueJob = scope.launch {
                try {
                    val context = freshPlaylistContext(api, summary.id)
                    val current = playback?.state?.value
                    if (current?.connection != PlaybackConnectionState.READY || captured != personalGestureBinding(summary, null, current) ||
                        summary !in model.state.value.items || playback?.queueCollection(context) == null) {
                        queueError = "Playback action wasn't sent."
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) { queueError = personalError(failure) }
            }
        }
    }
    LaunchedEffect(model) { if (model.state.value.loaded) model.refresh() }
    LaunchedEffect(state.created) {
        state.created?.let {
            model.consumeCreated()
            onOpenPlaylist(it.id)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Playlists", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
            IconButton({ creating = true }, enabled = !state.writing && !state.needsReview, modifier = Modifier.size(48.dp)) {
                SpofifyIcon(SpofifyGlyph.Plus, "Create playlist", Modifier.size(24.dp))
            }
        }
        if (state.writing) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
        val failure = state.error ?: state.message ?: queueError
        if (failure != null) Box(Modifier.padding(horizontal = 20.dp)) {
            PersonalStatus(false, failure) {
                queueError = null
                model.refresh()
            }
        }
        PullToRefreshBox(state.loading, onRefresh = { if (!state.writing) model.refresh() }, modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (state.loaded && state.items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No playlists", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else LazyColumn(Modifier.fillMaxSize(), reverseLayout = true,
                contentPadding = PaddingValues(top = 20.dp, bottom = 8.dp)) {
                // Ascending state is used directly. reverseLayout puts A at the bottom, Z at the top.
                items(state.items, key = { it.id }) { summary ->
                    PlaylistSummaryRow(summary,
                        onQueue = if (ready && summary.capabilities.play && summary.trackCount?.let { it > 0 } == true) ({ queue(summary) }) else null,
                        onMore = { more = summary }, gestureBinding = personalGestureBinding(summary, null, player)) { onOpenPlaylist(summary.id) }
                }
            }
        }
    }
    if (creating) PlaylistNameSheet("New playlist", onDismiss = { creating = false }) { name, description ->
        creating = false
        model.create(name, description)
    }
    more?.let { summary ->
        PlaylistSummaryActions(api, summary, playback, onMutationAcknowledged = model::refresh, onDismiss = { more = null })
    }
}

@Composable
internal fun PlaylistSummaryRow(
    summary: PersonalPlaylistSummary,
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
                .padding(horizontal = geometry.gutter.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                PlaylistArtwork(Modifier.size(MusicRowGeometry.ARTWORK_SLOT.dp))
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
            HorizontalDivider(Modifier.padding(horizontal = geometry.gutter.dp), color = MaterialTheme.colorScheme.surface)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTrackToPlaylistSheet(
    api: PersonalLibraryClient,
    track: MusicItem,
    onDismiss: () -> Unit,
    onAdded: (PersonalPlaylistSummary) -> Unit = {},
) {
    val owner = requireNotNull(LocalViewModelStoreOwner.current)
    val model: PlaylistsViewModel = viewModel(owner, key = "playlist-picker-${System.identityHashCode(api)}-${track.id}",
        factory = viewModelFactory { initializer { PlaylistsViewModel(api, manualOnly = true) } })
    val state by model.state.collectAsStateWithLifecycle()
    val scroll = rememberLazyListState()
    var creating by remember { mutableStateOf(false) }
    val priorAddition = remember(model) { model.state.value.added }
    LaunchedEffect(model) { if (model.state.value.loaded) model.refresh() }
    LaunchedEffect(state.created) { if (state.created != null) model.consumeCreated() }
    LaunchedEffect(state.added) {
        state.added?.let {
            model.consumeAdded()
            if (it !== priorAddition) {
                onAdded(it)
                onDismiss()
            }
        }
    }
    if (creating) PlaylistNameSheet("New playlist", onDismiss = { creating = false }) { name, description ->
        creating = false
        model.create(name, description)
    } else SpofifySheet(onDismiss) {
        PullToRefreshBox(state.loading, onRefresh = { if (!state.writing) model.refresh() }) {
            LazyColumn(Modifier.fillMaxWidth(), state = scroll,
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 22.dp)) {
                item("heading") {
                    SheetHeading("Add to playlist")
                    PersonalStatus(state.writing, state.error ?: state.message, model::refresh)
                    SheetAction("New playlist", SpofifyGlyph.Plus, !state.writing && !state.needsReview) { creating = true }
                }
                items(state.items.filter { it.capabilities.addTracks }, key = { it.id }) { summary ->
                    SheetAction(summary.name, SpofifyGlyph.Playlists,
                        !state.writing && !state.loading && !state.needsReview && track.kind == MusicKind.TRACK && track.hasAudio) {
                        model.add(track.id, summary)
                    }
                }
                if (state.loaded && state.items.none { it.capabilities.addTracks }) item("empty") {
                    Text("No playlists", Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
