package dev.spofify.mobile.feature.personal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.ImageLoader
import dev.spofify.mobile.core.library.MusicKind
import dev.spofify.mobile.core.personal.PersonalHistoryEntry
import dev.spofify.mobile.core.personal.PersonalLibraryApi
import dev.spofify.mobile.playback.AppPlayback
import dev.spofify.mobile.core.playback.PlaybackTrackReference
import dev.spofify.mobile.feature.library.MusicRowGeometry
import dev.spofify.mobile.feature.library.PageTitle
import dev.spofify.mobile.feature.library.activateTrackRow

/** History's Recent list: persisted occurrences, latest first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecentScreen(
    api: PersonalLibraryApi,
    loader: ImageLoader,
    playback: AppPlayback?,
    onNavigate: (MusicKind, String) -> Unit,
) {
    val owner = requireNotNull(LocalViewModelStoreOwner.current)
    val model: RecentViewModel = viewModel(owner, key = "recent-${System.identityHashCode(api)}",
        factory = viewModelFactory { initializer { RecentViewModel(api) } })
    val state by model.state.collectAsStateWithLifecycle()
    val player = playback?.observe()
    val ready = player?.canLoadTrack == true
    var menu by remember(model) { mutableStateOf<PersonalHistoryEntry?>(null) }
    var actionError by remember(model) { mutableStateOf<String?>(null) }
    val send: (() -> Boolean?) -> Unit = { action -> action(); actionError = null }
    val play: (PersonalHistoryEntry) -> Unit = play@{ entry ->
        if (!entry.track.hasAudio && player?.view?.offline == true) {
            actionError = "This song isn’t downloaded."
            return@play
        }
        activateTrackRow(entry.track.id, entry.track.hasAudio && playback?.presentation?.canLoadTrack == true,
            play = { playback?.playTrack(it) },
            openDetails = if (entry.metadataMissing || api is dev.spofify.mobile.playback.DevicePersonalLibrary && !entry.track.hasAudio) null else ({ onNavigate(MusicKind.TRACK, entry.track.id) }),
            notice = { actionError = it })
    }
    val queue: (PersonalHistoryEntry) -> Unit = { entry ->
        if (ready && entry.track.hasAudio) send { playback?.queueTrack(PlaybackTrackReference(entry.track.id)) }
    }
    LaunchedEffect(model) { model.refresh() }
    LaunchedEffect(state.items) { menu = null }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = MusicRowGeometry.EDGE_GUTTER.dp)) {
            PageTitle("Recently Played")
            val error = state.error ?: actionError ?: player?.error
            if (error != null) PersonalStatus(false, error)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.items?.isEmpty() == true && !state.loading && state.error == null) {
                Text("Nothing played yet", Modifier.padding(horizontal = MusicRowGeometry.EDGE_GUTTER.dp, vertical = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // The API is newest-first; index 0 is the visual bottom, as in Search.
            LazyColumn(Modifier.fillMaxSize(), reverseLayout = true, contentPadding = PaddingValues(bottom = 8.dp)) {
                items(state.items.orEmpty(), key = { "event:${it.origin.orEmpty()}:${it.id}" }) { entry ->
                    PersonalSongRow(entry.track, loader, onPlay = { play(entry) }, onMore = { menu = entry },
                        onQueue = if (ready && entry.track.hasAudio) ({ queue(entry) }) else null,
                        gestureBinding = playback to personalGestureBinding(entry, null, player?.view))
                }
            }
        }
    }
    menu?.let { entry ->
        PersonalSongActions(api, entry.track, loader, ready && entry.track.hasAudio, metadataMissing = entry.metadataMissing,
            onPlay = { play(entry) }, onQueue = { queue(entry) }, onNavigate = onNavigate, onDismiss = { menu = null })
    }
}
