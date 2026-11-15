package dev.spofify.mobile.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
internal fun MusicArtwork(item: MusicItem, loader: ImageLoader, modifier: Modifier = Modifier.size(48.dp)) =
    MusicArtwork(item.kind, item.id, loader, modifier)

@Composable
internal fun MusicArtwork(kind: MusicKind, id: String, loader: ImageLoader, modifier: Modifier = Modifier.size(48.dp)) {
    val context = LocalContext.current
    var loaded by remember(kind, id, loader) { mutableStateOf(false) }
    val request = remember(context, kind, id) {
        ImageRequest.Builder(context).data(Cover(kind, id)).memoryCacheKey("${kind.name}:$id").build()
    }
    val shape = if (kind == MusicKind.ARTIST) androidx.compose.foundation.shape.CircleShape
        else androidx.compose.ui.graphics.RectangleShape
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (!loaded) Icon(painterResource(R.drawable.ic_music_note), contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        AsyncImage(model = request, imageLoader = loader, contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize(),
            onLoading = { loaded = false }, onSuccess = { loaded = true }, onError = { loaded = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MusicRow(
    item: MusicItem,
    loader: ImageLoader,
    onQueue: (() -> Unit)? = null,
    onPlayNext: (() -> Unit)? = null,
    onOpen: () -> Unit,
) {
    var menu by remember(item.key) { mutableStateOf(false) }
    val queueAction by rememberUpdatedState(onQueue)
    val direction = if (LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Ltr)
        SwipeToDismissBoxValue.StartToEnd else SwipeToDismissBoxValue.EndToStart
    val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        if (value == direction) queueAction?.invoke()
        false // A queue action never removes the source row.
    })
    SwipeToDismissBox(state = swipe,
        enableDismissFromStartToEnd = onQueue != null && direction == SwipeToDismissBoxValue.StartToEnd,
        enableDismissFromEndToStart = onQueue != null && direction == SwipeToDismissBoxValue.EndToStart,
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondary.copy(alpha = .12f))
                .padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) { Text("Queue") }
        }) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Row(Modifier.fillMaxWidth().heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clickable(role = Role.Button, onClick = onOpen)
                    .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    MusicArtwork(item, loader)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(item.name.ifBlank { "Unknown title" }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyLarge)
                        val subtitle = item.artists.joinToString(", ") { it.name }.ifBlank {
                            when (item.kind) {
                                MusicKind.ARTIST -> "Artist"
                                MusicKind.ALBUM -> "Album"
                                MusicKind.TRACK -> "Track"
                            }
                        }
                        Text(subtitle, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (onQueue != null || onPlayNext != null) Box {
                    IconButton({ menu = true }, modifier = Modifier.semantics {
                        contentDescription = "More actions for ${item.name}"
                    }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        onQueue?.let { queue -> DropdownMenuItem(text = { Text("Queue") }, onClick = { menu = false; queue() }) }
                        onPlayNext?.let { next -> DropdownMenuItem(text = { Text("Play next") }, onClick = { menu = false; next() }) }
                    }
                }
            }
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
