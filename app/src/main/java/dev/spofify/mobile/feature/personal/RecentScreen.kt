package dev.spofify.mobile.feature.personal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
import dev.spofify.mobile.core.personal.PersonalHistoryEntry
import dev.spofify.mobile.core.personal.PersonalLibraryClient
import dev.spofify.mobile.core.playback.PlaybackConnectionState
import dev.spofify.mobile.core.playback.PlaybackController
import dev.spofify.mobile.core.playback.PlaybackTrackReference

/** Recently played only. Retained recent rows do not establish period rankings or year completeness. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentScreen(
    api: PersonalLibraryClient,
    loader: ImageLoader,
    playback: PlaybackController?,
    onNavigate: (MusicKind, String) -> Unit,
) {
    val owner = requireNotNull(LocalViewModelStoreOwner.current)
    val model: RecentViewModel = viewModel(owner, key = "recent-${System.identityHashCode(api)}",
        factory = viewModelFactory { initializer { RecentViewModel(api) } })
    val state by model.state.collectAsStateWithLifecycle()
    val player = playback?.state?.collectAsStateWithLifecycle()?.value
    val ready = player?.connection == PlaybackConnectionState.READY
    var menu by remember(model) { mutableStateOf<PersonalHistoryEntry?>(null) }
    var actionError by remember(model) { mutableStateOf<String?>(null) }
    val send: (() -> String?) -> Unit = { action -> actionError = if (action() == null) "Playback action wasn't sent." else null }
    val play: (PersonalHistoryEntry) -> Unit = { entry ->
        when {
            entry.track.hasAudio && ready -> send { playback?.playTrack(PlaybackTrackReference(entry.track.id)) }
            !entry.metadataMissing -> onNavigate(MusicKind.TRACK, entry.track.id)
            else -> actionError = "Track unavailable."
        }
    }
    val queue: (PersonalHistoryEntry) -> Unit = { entry ->
        if (ready && entry.track.hasAudio) send { playback?.queueTrack(PlaybackTrackReference(entry.track.id)) }
    }
    LaunchedEffect(model) { if (model.state.value.loaded) model.refresh() }
    LaunchedEffect(state.items) { menu = null }
    PullToRefreshBox(state.loading, onRefresh = model::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 28.dp)) {
            item("heading") {
                Text("History", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                Text("Recently played", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 24.dp, bottom = 12.dp).semantics { heading() })
                val error = state.error ?: actionError ?: player?.error
                if (error != null) PersonalStatus(false, error) {
                    actionError = null
                    model.refresh()
                }
            }
            items(state.items, key = { it.track.id }) { entry ->
                PersonalSongRow(entry.track, loader, onPlay = { play(entry) }, onMore = { menu = entry },
                    onQueue = if (ready && entry.track.hasAudio) ({ queue(entry) }) else null,
                    gestureBinding = personalGestureBinding(entry, null, player))
            }
            if (state.loaded && state.items.isEmpty()) item("empty") {
                Text("Nothing played yet", Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    menu?.let { entry ->
        PersonalSongActions(api, entry.track, loader, ready && entry.track.hasAudio, metadataMissing = entry.metadataMissing,
            onPlay = { play(entry) }, onQueue = { queue(entry) }, onNavigate = onNavigate, onDismiss = { menu = null })
    }
}
