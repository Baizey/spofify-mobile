package dev.spofify.mobile.feature.playlists

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.feature.library.DetailIdentity

/** Real saved identity/count only. Playlist APIs do not provide owner or playlist artwork. */
@Composable
internal fun PlaylistDetailIdentity(
    name: String,
    description: String?,
    count: Long?,
    firstTrack: LibraryItem?,
    artwork: @Composable (LibraryItem, Modifier) -> Unit,
) {
    DetailIdentity(name,
        count?.let { "$it ${if (it == 1L) "song" else "songs"}" }.orEmpty(),
        artwork = { modifier ->
            if (firstTrack != null) artwork(firstTrack, modifier)
            else Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.MusicNote, null, Modifier.size(32.dp))
            }
        }, description = description)
}
