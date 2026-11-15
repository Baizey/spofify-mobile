package dev.spofify.mobile.feature.personal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import dev.spofify.mobile.core.library.MusicKind
import dev.spofify.mobile.core.personal.PersonalLibraryClient
import dev.spofify.mobile.core.personal.PersonalTrack
import dev.spofify.mobile.feature.library.MusicArtwork
import dev.spofify.mobile.feature.library.MusicRow

@Composable
internal fun PersonalSongRow(track: PersonalTrack, loader: ImageLoader, onPlay: () -> Unit, onMore: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { MusicRow(track.musicItem(), loader, onOpen = onPlay) }
        IconButton(onMore, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
            contentDescription = "More actions for ${track.name.ifBlank { "song" }}"
        }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
    }
}

@Composable
internal fun PersonalAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = enabled,
        contentPadding = PaddingValues(vertical = 12.dp)) {
        Text(label, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun PersonalStatus(loading: Boolean, error: String?, onRefresh: () -> Unit) {
    if (loading) Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(22.dp))
        Text("Updating library…", style = MaterialTheme.typography.bodySmall)
    }
    if (error != null) {
        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onRefresh, Modifier.heightIn(min = 48.dp), enabled = !loading) { Text("Refresh") }
    }
}

@Composable
internal fun PlaylistMetadataDialog(
    title: String,
    initialName: String = "",
    initialDescription: String = "",
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var description by remember(initialDescription) { mutableStateOf(initialDescription) }
    val valid = name.isNotBlank() && name.codePointCount(0, name.length) <= 100 &&
        description.codePointCount(0, description.length) <= 500
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
            OutlinedTextField(description, { description = it }, label = { Text("Description") }, minLines = 2, maxLines = 4)
        }
    }, confirmButton = {
        TextButton({ onSave(name, description) }, enabled = valid) { Text(if (initialName.isEmpty()) "Create" else "Save") }
    }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PersonalSongActions(
    api: PersonalLibraryClient,
    track: PersonalTrack,
    loader: ImageLoader,
    playable: Boolean,
    metadataMissing: Boolean = false,
    onPlay: () -> Unit,
    onQueue: () -> Unit,
    onPlayNext: () -> Unit,
    onNavigate: (MusicKind, String) -> Unit,
    onRemove: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    var adding by remember(track.id) { mutableStateOf(false) }
    if (adding) {
        AddTrackToPlaylistSheet(api, track.musicItem(), onDismiss = { adding = false }, onAdded = {
            adding = false
            onDismiss()
        })
    } else ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            item("heading") {
                Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MusicArtwork(track.musicItem(), loader)
                    Text(track.name.ifBlank { "Song metadata unavailable" }, style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).semantics { heading() })
                }
            }
            item("play") { PersonalAction("Play now", playable) {
                onDismiss()
                onPlay()
            } }
            item("next") { PersonalAction("Play next", playable) {
                onDismiss()
                onPlayNext()
            } }
            item("queue") { PersonalAction("Queue", playable) {
                onDismiss()
                onQueue()
            } }
            item("add") { PersonalAction("Add to playlist", track.hasAudio) { adding = true } }
            item("detail") { PersonalAction("Song details", !metadataMissing) {
                onDismiss()
                onNavigate(MusicKind.TRACK, track.id)
            } }
            if (!metadataMissing) {
                items(track.artists.size, key = { "artist-$it" }) { index ->
                    val artist = track.artists[index]
                    if (artist.id.isNotBlank()) PersonalAction("Artist · ${artist.name}") {
                        onDismiss()
                        onNavigate(MusicKind.ARTIST, artist.id)
                    }
                }
                if (track.albumId.isNotBlank()) item("album") {
                    PersonalAction(if (track.albumName.isBlank()) "Album" else "Album · ${track.albumName}") {
                        onDismiss()
                        onNavigate(MusicKind.ALBUM, track.albumId)
                    }
                }
            }
            onRemove?.let { remove -> item("remove") { PersonalAction("Remove this entry") {
                onDismiss()
                remove()
            } } }
            if (!track.hasAudio) item("unavailable") {
                Text("Audio is unavailable. Existing metadata can still be opened.", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 12.dp))
            }
        }
    }
}
