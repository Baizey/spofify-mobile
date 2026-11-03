package dev.spofify.mobile.playback

import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.PlaybackArtist
import dev.spofify.mobile.core.PlaybackTrack

/** Positive musical projection only; never copy private Library paths or selection tokens. */
internal object PlaybackSnapshotCodec {
    fun track(item: LibraryItem, key: Long, order: Long): PlaybackTrack = PlaybackTrack(
        id = item.id, name = item.name,
        artists = item.artists.map { PlaybackArtist(it.id, it.name) },
        albumName = item.albumName.orEmpty(), albumId = item.albumId.orEmpty(),
        duration = item.durationMs.coerceAtLeast(0) / 1000.0,
        entryId = item.entryId.orEmpty(), key = key, order = order,
    )
}
