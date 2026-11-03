package dev.spofify.mobile

import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.PlaybackQueue
import dev.spofify.mobile.core.SpofifyClient

internal suspend fun playbackForSelection(client: SpofifyClient, source: Page, item: LibraryItem): PlaybackQueue {
    val result = when {
        item.kind != "track" -> client.playback(item)
        source == Page.Likes -> client.likedPlayback()
        source is Page.Playlist -> client.playlistPlayback(source.playlist.id)
        else -> client.playback(item)
    }
    val selectedIsPresent = result.items.any {
        if (source is Page.Playlist && item.entryId != null) it.entryId == item.entryId else it.id == item.id
    }
    // Never substitute a different song/occurrence because a context was capped.
    return if (item.kind == "track" && item.playable && !selectedIsPresent)
        result.copy(items = listOf(item) + result.items.take(999), truncated = true)
    else result
}

/** Recent is a bounded shelf, not an offset-paginated endpoint. */
internal fun displayedTotal(page: Page, returned: Int, total: Int): Int = if (page == Page.Home) returned else total
