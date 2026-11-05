package dev.spofify.mobile.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.LibraryItem

/** Search-canonical list rhythm. Shelf spacing belongs to the host, never between songs. */
internal object MusicListLayout {
    val horizontalPadding = 16.dp
    val arrangement = Arrangement.Top
}

/** Individual lazy rows with caller-owned identity/order and unmodified host action owners. */
internal fun LazyListScope.musicRows(
    items: List<LibraryItem>,
    key: (Int, LibraryItem) -> Any,
    onOpen: (LibraryItem) -> Unit,
    onPlay: (LibraryItem, Boolean) -> Unit,
    onMore: (LibraryItem) -> Unit,
    onQueue: (LibraryItem) -> Unit,
    artwork: @Composable (LibraryItem, Modifier) -> Unit,
    playback: CatalogPlaybackFeedback,
    likes: Map<String, CatalogLikeState>,
    onToggleLike: ((LibraryItem) -> Unit)?,
) {
    itemsIndexed(items, key = key) { _, item ->
        MusicRow(item, onOpen, onPlay, onMore, onQueue, artwork,
            playback = playback, like = likes[item.id], onToggleLike = onToggleLike)
    }
}
