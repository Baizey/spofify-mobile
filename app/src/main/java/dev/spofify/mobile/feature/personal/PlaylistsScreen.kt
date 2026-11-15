package dev.spofify.mobile.feature.personal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
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
import dev.spofify.mobile.core.library.MusicItem
import dev.spofify.mobile.core.library.MusicKind
import dev.spofify.mobile.core.personal.PersonalLibraryClient
import dev.spofify.mobile.core.personal.PersonalPlaylistSummary
import dev.spofify.mobile.core.playback.PlaybackController

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
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(model) { if (model.state.value.loaded) model.refresh() }
    LaunchedEffect(state.created) {
        state.created?.let {
            model.consumeCreated()
            onOpenPlaylist(it.id)
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Playlists", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineLarge)
            IconButton({ creating = true }, enabled = !state.writing && !state.needsReview, modifier = Modifier.semantics {
                contentDescription = "Create playlist"
            }) { Text("+", style = MaterialTheme.typography.headlineMedium) }
        }
        PersonalStatus(state.loading, state.error, model::refresh)
        state.message?.let { Text(it, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall) }
        if (state.writing) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true, contentPadding = PaddingValues(bottom = 16.dp)) {
            // The wireframe opens at the alphabetic bottom; global order is computed only after all pages arrive.
            items(state.items.asReversed(), key = { it.id }) { summary ->
                PlaylistSummaryRow(summary) { onOpenPlaylist(summary.id) }
            }
            if (state.loaded && state.items.isEmpty()) item("empty") {
                Text("No playlists", Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(model::refresh, enabled = !state.loading && !state.writing) { Text("Refresh playlists") }
    }
    if (creating) PlaylistMetadataDialog("Create playlist", onDismiss = { creating = false }) { name, description ->
        creating = false
        model.create(name, description)
    }
}

@Composable
internal fun PlaylistSummaryRow(summary: PersonalPlaylistSummary, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Summary DTOs contain no catalog cover identity. Do not invent an album/artwork request.
        Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Text("≡", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f)) {
            Text(summary.name, style = MaterialTheme.typography.bodyLarge)
            Text(playlistCount(summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    var creating by remember { mutableStateOf(false) }
    val priorAddition = remember(model) { model.state.value.added }
    LaunchedEffect(model) { if (model.state.value.loaded) model.refresh() }
    LaunchedEffect(state.created) { if (state.created != null) model.consumeCreated() }
    LaunchedEffect(state.added) {
        state.added?.let {
            model.consumeAdded()
            // Reopening the picker must not re-deliver a receipt from a previous opening.
            if (it !== priorAddition) {
                onAdded(it)
                onDismiss()
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 600.dp),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            item("heading") {
                Text("Add to playlist", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp).semantics { heading() })
                Text(track.name, style = MaterialTheme.typography.bodyMedium)
                PersonalStatus(state.loading, state.error, model::refresh)
                state.message?.let { Text(it, Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall) }
                if (state.writing) LinearProgressIndicator(Modifier.fillMaxWidth())
                PersonalAction("New playlist", !state.writing && !state.needsReview) { creating = true }
                if (track.kind != MusicKind.TRACK || !track.hasAudio) {
                    Text("This song isn't available to add.", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            items(state.items.filter { it.capabilities.addTracks }, key = { it.id }) { summary ->
                PlaylistSummaryRow(summary, enabled = !state.writing && !state.loading && !state.needsReview && track.kind == MusicKind.TRACK && track.hasAudio) {
                    model.add(track.id, summary)
                }
            }
            if (state.loaded && state.items.none { it.capabilities.addTracks }) item("empty") {
                Text("No editable playlists. Create a manual playlist first.", Modifier.padding(vertical = 16.dp))
            }
            item("refresh") { PersonalAction("Refresh playlists", !state.loading && !state.writing, model::refresh) }
        }
    }
    if (creating) PlaylistMetadataDialog("Create playlist", onDismiss = { creating = false }) { name, description ->
        creating = false
        model.create(name, description)
    }
}
