package dev.spofify.mobile.playback

import dev.spofify.mobile.core.ArtistCredit
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.PlaybackArtist
import dev.spofify.mobile.core.PlaybackTrack
import kotlin.math.roundToLong

/** Local/read-only musical projections. Never imports, resolves or uploads an online queue. */
internal object PlaybackSnapshotCodec {
    /** Process-local row identity; a queue key is not a catalog playlist entry ID. */
    fun occurrenceId(track: PlaybackTrack): String = "wire-${track.key}"

    fun libraryItem(track: PlaybackTrack): LibraryItem = LibraryItem(
        kind = "track", id = track.id, name = track.name,
        artists = track.artists.map { ArtistCredit(it.id, it.name) },
        albumName = track.albumName, albumId = track.albumId,
        durationMs = (track.duration * 1000).roundToLong(), hasAudio = true, entryId = track.entryId,
    )

    /** Deliberate local queue projection. Server queue identities come only from its socket. */
    fun track(item: LibraryItem, key: Long, order: Long): PlaybackTrack = PlaybackTrack(
        id = item.id, name = item.name,
        artists = item.artists.map { PlaybackArtist(it.id, it.name) },
        albumName = item.albumName.orEmpty(), albumId = item.albumId.orEmpty(),
        duration = item.durationMs.coerceAtLeast(0) / 1000.0,
        entryId = item.entryId.orEmpty(), key = key, order = order,
    )
}
