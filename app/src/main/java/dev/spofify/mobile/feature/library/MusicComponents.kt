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
internal fun artworkLoader(context: android.content.Context, api: LibraryApi, local: Boolean = false): ImageLoader =
    ImageLoader.Builder(context).diskCache(null).components {
        add(object : Fetcher.Factory<Cover> {
            override fun create(data: Cover, options: Options, imageLoader: ImageLoader): Fetcher = Fetcher {
                val bytes = api.artwork(data.kind, data.id) ?: throw IOException("Artwork unavailable")
                SourceFetchResult(ImageSource(Buffer().write(bytes), options.fileSystem), null, if (local) DataSource.DISK else DataSource.NETWORK)
            }
        })
    }.build()

@Composable
internal fun MusicArtwork(item: MusicItem, loader: ImageLoader, modifier: Modifier = Modifier) =
    MusicArtwork(item.kind, item.id, loader, modifier)

@Composable
internal fun MusicArtwork(kind: MusicKind, id: String, loader: ImageLoader, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var loaded by remember(kind, id, loader) { mutableStateOf(false) }
    val request = remember(context, kind, id) {
        ImageRequest.Builder(context).data(Cover(kind, id)).memoryCacheKey("${kind.name}:$id").build()
    }
    val shape = if (kind == MusicKind.ARTIST) androidx.compose.foundation.shape.CircleShape
        else androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
    Box(modifier.size(48.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
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
    val geometry = MusicRowGeometry(numbered = trackNumber != null)
    val hasMore = onMore != null || onQueue != null || onPlayNext != null
    SwipeActionSurface(binding = gestureBinding, onRight = onQueue,
        excludeEnd = geometry.swipeEndExclusion(hasMore).dp) {
        Surface(color = containerColor) {
            // The parent owns the full-row press indication, including both gutters and More's column.
            // IconButton consumes its own click; it never bubbles a second Play/Open to this parent.
            Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen)) {
                Row(Modifier.fillMaxWidth().heightIn(min = MusicRowGeometry.MIN_HEIGHT.dp)
                    .padding(horizontal = geometry.gutter.dp, vertical = MusicRowGeometry.VERTICAL_PADDING.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (trackNumber != null) {
                        Text(trackNumber.toString(), Modifier.width(MusicRowGeometry.NUMBER_WIDTH.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(MusicRowGeometry.NUMBER_GAP.dp))
                    }
                    MusicArtwork(item, loader, Modifier.size(MusicRowGeometry.ARTWORK_SLOT.dp))
                    Spacer(Modifier.width(geometry.identityGap.dp))
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
                    if (reservePopularity) Spacer(Modifier.width(MusicRowGeometry.POPULARITY_SLOT.dp)
                        .height(MusicRowGeometry.ACTION_SLOT.dp))
                    Box(Modifier.size(MusicRowGeometry.ACTION_SLOT.dp)) {
                        if (hasMore) {
                            IconButton({ if (onMore != null) onMore() else menu = true }, Modifier.fillMaxSize()) {
                                SpofifyIcon(SpofifyGlyph.More, "More actions for ${item.name}")
                            }
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                onQueue?.let { queue -> DropdownMenuItem(text = { Text("Queue") }, onClick = { menu = false; queue() }) }
                                onPlayNext?.let { next -> DropdownMenuItem(text = { Text("Play next") }, onClick = { menu = false; next() }) }
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = geometry.gutter.dp), color = MaterialTheme.colorScheme.surface)
            }
        }
    }
}

/** Play occupies the artwork column; More occupies the same trailing target as every listening row. */
@Composable
internal fun MusicCollectionControls(
    modifier: Modifier = Modifier,
    numbered: Boolean = false,
    gutter: Int = MusicRowGeometry.EDGE_GUTTER,
    play: @Composable () -> Unit,
    more: @Composable () -> Unit,
) {
    val geometry = MusicRowGeometry(numbered, gutter)
    Row(modifier.fillMaxWidth().padding(horizontal = geometry.gutter.dp), verticalAlignment = Alignment.CenterVertically) {
        if (geometry.leadingWidth > 0) Spacer(Modifier.width(geometry.leadingWidth.dp))
        Box(Modifier.size(MusicRowGeometry.ARTWORK_SLOT.dp), contentAlignment = Alignment.Center) { play() }
        Spacer(Modifier.weight(1f)) // No invented offline status; the identity/status columns remain available.
        Box(Modifier.size(MusicRowGeometry.ACTION_SLOT.dp), contentAlignment = Alignment.Center) { more() }
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
