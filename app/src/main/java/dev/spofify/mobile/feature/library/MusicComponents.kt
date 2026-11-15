package dev.spofify.mobile.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.ImageRequest
import coil3.decode.DataSource
import dev.spofify.mobile.R
import dev.spofify.mobile.core.library.*
import java.io.IOException
import okio.Buffer

private data class Cover(val kind: MusicKind, val id: String)

/** A loader belongs to one account graph. No cookie headers or disk cache in presentation. */
internal fun artworkLoader(context: android.content.Context, api: LibraryApi): ImageLoader =
    ImageLoader.Builder(context).diskCache(null).components {
        add(object : Fetcher.Factory<Cover> {
            override fun create(data: Cover, options: Options, imageLoader: ImageLoader): Fetcher = Fetcher {
                val bytes = api.artwork(data.kind, data.id) ?: throw IOException("Artwork unavailable")
                SourceFetchResult(ImageSource(Buffer().write(bytes), options.fileSystem), null, DataSource.NETWORK)
            }
        })
    }.build()

@Composable
internal fun MusicArtwork(item: MusicItem, loader: ImageLoader, modifier: Modifier = Modifier.size(48.dp)) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context).data(Cover(item.kind, item.id))
            .memoryCacheKey(item.key).build(),
        imageLoader = loader,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        fallback = painterResource(R.drawable.ic_music_note),
        error = painterResource(R.drawable.ic_music_note),
        modifier = modifier,
    )
}

@Composable
internal fun MusicRow(item: MusicItem, loader: ImageLoader, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(role = Role.Button, onClick = onOpen)
        .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        MusicArtwork(item, loader)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(item.name.ifBlank { "Unknown title" }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge)
            val subtitle = item.artists.joinToString(", ") { it.name }.ifBlank {
                when (item.kind) { MusicKind.ARTIST -> "Artist"; MusicKind.ALBUM -> "Album"; MusicKind.TRACK -> "Track" }
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (item.kind == MusicKind.TRACK && item.durationMs > 0) {
            Text(duration(item.durationMs), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun PageTitle(title: String) {
    Text(title, style = MaterialTheme.typography.headlineLarge,
        modifier = Modifier.padding(top = 24.dp, bottom = 20.dp).semantics { heading() })
}

internal fun duration(milliseconds: Long): String {
    val seconds = milliseconds / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
