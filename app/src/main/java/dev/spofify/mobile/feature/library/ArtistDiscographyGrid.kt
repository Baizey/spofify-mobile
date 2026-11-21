package dev.spofify.mobile.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import dev.spofify.mobile.core.library.MusicItem

@Composable
internal fun ArtistDiscographyGrid(
    artistId: String,
    releases: List<MusicItem>,
    loader: ImageLoader,
    onOpen: (MusicItem) -> Unit,
    header: @Composable () -> Unit,
    loadState: @Composable () -> Unit,
) {
    val groups = remember(releases) { groupArtistDiscography(releases) }
    var appearancesExpanded by rememberSaveable(artistId) { mutableStateOf(false) }
    val largeText = LocalDensity.current.fontScale > 1.4f
    LazyVerticalGrid(
        columns = GridCells.Fixed(if (largeText) 1 else 2),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 28.dp),
    ) {
        item("discography:header", span = { GridItemSpan(maxLineSpan) }) { header() }
        if (groups.albums.isNotEmpty()) {
            item("discography:section:albums", span = { GridItemSpan(maxLineSpan) }) { DiscographyHeading("Albums") }
            items(groups.albums, key = { "discography:tile:${it.key}" }) { album -> DiscographyTile(album, loader, onOpen) }
        }
        if (groups.singles.isNotEmpty()) {
            item("discography:section:singles", span = { GridItemSpan(maxLineSpan) }) { DiscographyHeading("Singles") }
            items(groups.singles, key = { "discography:tile:${it.key}" }) { album -> DiscographyTile(album, loader, onOpen) }
        }
        // The caller's pagination/error state must remain reachable without expanding secondary releases.
        item("discography:load-state", span = { GridItemSpan(maxLineSpan) }) { loadState() }
        if (groups.appearsOn.isNotEmpty()) {
            item("discography:section:appears-on", span = { GridItemSpan(maxLineSpan) }) {
                TextButton(
                    onClick = { appearancesExpanded = !appearancesExpanded },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                        heading()
                        stateDescription = if (appearancesExpanded) "Expanded" else "Collapsed"
                    },
                    contentPadding = PaddingValues(vertical = 8.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                ) {
                    Text("Appears on", Modifier.weight(1f), textAlign = TextAlign.Start,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.4.sp, lineHeight = 21.75.sp,
                            fontWeight = FontWeight(620)))
                    Text(if (appearancesExpanded) "Collapse" else "Expand", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 12.dp))
                }
            }
            if (appearancesExpanded) {
                item("discography:appears-on:help", span = { GridItemSpan(maxLineSpan) }) {
                    Text("Guest appearances, compilations, and other releases.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(groups.appearsOn, key = { "discography:tile:${it.key}" }) { album -> DiscographyTile(album, loader, onOpen) }
            }
        }
    }
}

@Composable
private fun DiscographyHeading(title: String) {
    Text(title, style = MaterialTheme.typography.bodyLarge.copy(
        fontSize = 17.4.sp, lineHeight = 21.75.sp, fontWeight = FontWeight(620)),
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp).semantics { heading() })
}

@Composable
private fun DiscographyTile(album: MusicItem, loader: ImageLoader, onOpen: (MusicItem) -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onOpen(album) }) {
        MusicArtwork(album, loader, Modifier.fillMaxWidth().aspectRatio(1f))
        Text(album.name.ifBlank { "Unknown title" }, style = MaterialTheme.typography.bodyLarge.copy(
            fontSize = 14.1.sp, lineHeight = 19.04.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(top = 7.dp))
        releaseYear(album.releaseDate).takeIf(String::isNotBlank)?.let { year ->
            Text(year, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.7.sp, lineHeight = 16.38.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
        }
    }
}
