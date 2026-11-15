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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.spofify.mobile.designsystem.SpofifyGlyph
import dev.spofify.mobile.designsystem.SpofifyIcon
import dev.spofify.mobile.designsystem.SwipeActionSurface
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
        else androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (!loaded) SpofifyIcon(SpofifyGlyph.Note, null, Modifier.size(22.dp),
            MaterialTheme.colorScheme.onSurfaceVariant)
        AsyncImage(model = request, imageLoader = loader, contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize(),
            onLoading = { loaded = false }, onSuccess = { loaded = true }, onError = { loaded = false })
    }
}

@Composable
internal fun MusicRow(
    item: MusicItem,
    loader: ImageLoader,
    onQueue: (() -> Unit)? = null,
    onPlayNext: (() -> Unit)? = null,
    trackNumber: Int? = null,
    reservePopularity: Boolean = false,
    onMore: (() -> Unit)? = null,
    gestureBinding: Any = item.key,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.background,
    onOpen: () -> Unit,
) {
    var menu by remember(item.key) { mutableStateOf(false) }
    SwipeActionSurface(binding = gestureBinding, onRight = onQueue) {
        Surface(color = containerColor) {
            Column {
                Row(Modifier.fillMaxWidth().heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).heightIn(min = 70.dp).clickable(role = Role.Button, onClick = onOpen)
                        .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(if (trackNumber != null) 10.dp else 13.dp)) {
                        if (trackNumber != null) Text(trackNumber.toString(), Modifier.width(22.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MusicArtwork(item, loader)
                        Column(Modifier.weight(1f)) {
                            Text(item.name.ifBlank { "Unknown title" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.7.sp, lineHeight = 19.1.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
                            val credit = item.artists.joinToString(", ") { it.name }
                            val subtitle = when (item.kind) {
                                MusicKind.ARTIST -> "Artist"
                                MusicKind.ALBUM -> if (credit.isBlank()) "Album" else "$credit · Album"
                                MusicKind.TRACK -> credit
                            }
                            Text(subtitle, Modifier.padding(top = 3.dp), style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.8.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (reservePopularity) Spacer(Modifier.width(24.dp).height(48.dp))
                    if (onMore != null || onQueue != null || onPlayNext != null) Box(Modifier.width(40.dp)) {
                        IconButton({ if (onMore != null) onMore() else menu = true }, Modifier.size(48.dp)) {
                            SpofifyIcon(SpofifyGlyph.More, "More actions for ${item.name}")
                        }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            onQueue?.let { queue -> DropdownMenuItem(text = { Text("Queue") }, onClick = { menu = false; queue() }) }
                            onPlayNext?.let { next -> DropdownMenuItem(text = { Text("Play next") }, onClick = { menu = false; next() }) }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surface)
            }
        }
    }
}

@Composable
internal fun PageTitle(title: String) {
    Text(title, style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.padding(top = 20.dp, bottom = 12.dp).semantics { heading() })
}

internal fun duration(milliseconds: Long): String {
    val seconds = milliseconds / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
