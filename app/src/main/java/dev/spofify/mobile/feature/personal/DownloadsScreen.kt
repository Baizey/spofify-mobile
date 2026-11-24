package dev.spofify.mobile.feature.personal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import dev.spofify.mobile.core.playback.PlaybackTrackReference
import dev.spofify.mobile.feature.library.MusicRow
import dev.spofify.mobile.playback.AccountMusic
import dev.spofify.mobile.playback.AppPlayback

@Composable
internal fun DownloadsScreen(music: AccountMusic, loader: ImageLoader, playback: AppPlayback?, onBack: () -> Unit) {
    val tracks by music.downloads.collectAsStateWithLifecycle()
    val ready by music.indexReady.collectAsStateWithLifecycle()
    val direct by music.directDownloads.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(music) { music.verifyDownloads() }
    val player = playback?.observe()
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        TextButton(onBack, Modifier.padding(start = 12.dp)) { Text("Back to playlists") }
        Text("Downloads", Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            style = MaterialTheme.typography.headlineMedium)
        (error ?: player?.error)?.let { Text(it, Modifier.padding(horizontal = 20.dp)) }
        if (ready && tracks.isEmpty()) Text("No downloads", Modifier.padding(horizontal = 20.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.fillMaxSize()) {
            items(tracks, key = { it.id }) { track ->
                MusicRow(track, loader, onMore = if (track.id in direct) ({ removing = track.id }) else null, onOpen = {
                    error = if (playback?.playTrack(PlaybackTrackReference(track.id)) == true) null else "Audio unavailable"
                })
            }
        }
    }
    removing?.let { id ->
        PersonalConfirmSheet("Remove download?", "It stays in your playlists.", "Remove", onDismiss = { removing = null }) {
            removing = null
            music.removeDownload(id) { error = "Couldn’t remove download." }
        }
    }
}
