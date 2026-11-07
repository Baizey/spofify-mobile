package dev.spofify.mobile.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.LibraryItem

/** Row-major cells are hosted by the page's single lazy list, never a nested scroller. */
@Composable
internal fun ArtistReleaseCard(
    item: LibraryItem,
    onOpen: (LibraryItem) -> Unit,
    onMore: (LibraryItem) -> Unit,
    artwork: @Composable (LibraryItem, Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(bottom = 12.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            Box(Modifier.fillMaxSize().clickable(onClickLabel = "Open ${item.name}") { onOpen(item) }) {
                artwork(item, Modifier.fillMaxSize())
            }
            IconButton(onClick = { onMore(item) }, modifier = Modifier.align(Alignment.TopEnd).size(48.dp)
                .background(MaterialTheme.colorScheme.surface)) {
                Icon(Icons.Default.MoreVert, "More actions for ${item.name}")
            }
        }
        Column(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(onClickLabel = "Open ${item.name}") { onOpen(item) }
            .padding(top = 6.dp)) {
            Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            listOfNotNull(itemYear(item), item.albumType?.takeIf { it.isNotBlank() })
                .joinToString(" · ").takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
        }
    }
}

/** Conservative readable cells; native font scaling/target sizes are never reduced. */
internal fun artistReleaseColumns(availableWidthDp: Float, fontScale: Float): Int =
    if (availableWidthDp >= 256f * fontScale.coerceAtLeast(1f) + 12f) 2 else 1

/** Hero, release-group selector and section heading precede the first release row. */
internal fun artistReleaseScrollIndex(screen: CatalogScreen, columns: Int): Int {
    if (screen.item?.kind != "artist") return screen.scrollIndex
    val releaseIndex = screen.sections["albums"]?.items.orEmpty().indexOfFirst { it.id == screen.releaseAnchorId }
    return if (releaseIndex >= 0) 3 + releaseIndex / columns else screen.scrollIndex
}
