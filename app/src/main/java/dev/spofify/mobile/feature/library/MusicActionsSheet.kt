package dev.spofify.mobile.feature.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import dev.spofify.mobile.core.library.MusicItem
import dev.spofify.mobile.core.library.MusicKind
import dev.spofify.mobile.core.personal.PersonalLibraryClient
import dev.spofify.mobile.core.playback.*
import dev.spofify.mobile.designsystem.*
import dev.spofify.mobile.feature.personal.AddTrackToPlaylistSheet
import java.util.Locale

internal data class MusicActionSelection(val item: MusicItem, val context: PlaybackContext? = null)

@Composable
internal fun MusicActionsSheet(
    selection: MusicActionSelection,
    controller: PlaybackController?,
    personal: PersonalLibraryClient,
    loader: ImageLoader,
    onNavigate: (MusicKind, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val item = selection.item
    val player = controller?.state?.collectAsStateWithLifecycle()?.value
    val ready = player?.connection == PlaybackConnectionState.READY
    val playable = ready && if (item.kind == MusicKind.TRACK) item.hasAudio else item.availableTracks > 0
    val collection = selection.context ?: item.takeIf { it.kind != MusicKind.TRACK }?.let {
        PlaybackContext(it.kind.name.lowercase(Locale.ROOT), it.id, it.name)
    }
    var adding by remember(selection) { mutableStateOf(false) }
    if (adding) {
        AddTrackToPlaylistSheet(personal, item, onDismiss)
        return
    }
    SpofifySheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 22.dp)) {
            if (item.kind == MusicKind.TRACK) {
                Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                    MusicArtwork(item, loader)
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.5.sp, lineHeight = 26.sp))
                        Text(item.artists.joinToString(", ") { it.name }, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                SheetGroup("Listen")
            } else SheetHeading(item.name)
            SheetAction("Play now", SpofifyGlyph.Play, playable) {
                if (item.kind == MusicKind.TRACK) controller?.playTrack(PlaybackTrackReference(item.id), collection)
                else collection?.let { controller?.playCollection(it) }
                onDismiss()
            }
            SheetAction("Queue", SpofifyGlyph.Queue, playable) {
                if (item.kind == MusicKind.TRACK) controller?.queueTrack(PlaybackTrackReference(item.id), collection)
                else collection?.let { controller?.queueCollection(it) }
                onDismiss()
            }
            if (item.kind == MusicKind.TRACK) {
                SheetGroup("Library")
                SheetAction("Add to playlist", SpofifyGlyph.Plus, item.hasAudio) { adding = true }
                if (item.artists.isNotEmpty() || item.albumId.isNotBlank()) SheetGroup("Go to")
                item.artists.forEach { artist ->
                    SheetAction(artist.name, SpofifyGlyph.Artist) {
                        onDismiss()
                        onNavigate(MusicKind.ARTIST, artist.id)
                    }
                }
                if (item.albumId.isNotBlank()) SheetAction(item.albumName, SpofifyGlyph.Album) {
                    onDismiss()
                    onNavigate(MusicKind.ALBUM, item.albumId)
                }
            }
        }
    }
}
