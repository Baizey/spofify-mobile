package dev.spofify.mobile.feature.personal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
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
    val send: (() -> String?) -> Unit = { action ->
        actionError = if (action() == null) "Playback action wasn't sent. Check the playback connection." else null
    }
    val play: (PersonalHistoryEntry) -> Unit = { entry ->
        when {
            entry.track.hasAudio && ready -> send { playback?.playTrack(PlaybackTrackReference(entry.track.id)) }
            !entry.metadataMissing -> onNavigate(MusicKind.TRACK, entry.track.id)
            else -> actionError = "This historical song has no current metadata or playable audio."
        }
    }
    LaunchedEffect(model) { if (model.state.value.loaded) model.refresh() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 28.dp)) {
        item("heading") {
            Text("Home", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
            Text("Recently played", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 24.dp, bottom = 12.dp).semantics { heading() })
            PersonalStatus(state.loading, state.error, model::refresh)
            listOfNotNull(actionError, player?.error).distinct().forEach {
                Text(it, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        items(state.items, key = { it.track.id }) { entry ->
            Column {
                PersonalSongRow(entry.track, loader, onPlay = { play(entry) }, onMore = { menu = entry })
                if (entry.metadataMissing) Text("Historical identity · current metadata unavailable", style = MaterialTheme.typography.bodySmall)
                else if (!entry.track.hasAudio) Text("Audio unavailable", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (state.loaded && state.items.isEmpty()) item("empty") {
            Text("Nothing played yet", Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.total?.let { it > state.items.size } == true) item("limit") {
            Text("Showing the latest ${state.items.size} recently played songs.", Modifier.padding(vertical = 12.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item("refresh") { PersonalAction("Refresh recently played", !state.loading, model::refresh) }
    }
    menu?.let { entry ->
        PersonalSongActions(api, entry.track, loader, ready && entry.track.hasAudio, metadataMissing = entry.metadataMissing,
            onPlay = { play(entry) },
            onQueue = { send { playback?.queueTrack(PlaybackTrackReference(entry.track.id)) } },
            onPlayNext = { send { playback?.queueTrack(PlaybackTrackReference(entry.track.id), next = true) } },
            onNavigate = onNavigate, onDismiss = { menu = null })
    }
}
