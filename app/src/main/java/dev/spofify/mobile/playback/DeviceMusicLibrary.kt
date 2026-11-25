package dev.spofify.mobile.playback

import dev.spofify.mobile.core.library.MusicItem
import dev.spofify.mobile.core.playback.*

/** Immutable local catalog index. Saved playlist membership belongs to the personal replica. */
internal class DeviceMusicLibrary(
    items: List<MusicItem>,
    private val available: (String) -> Boolean,
) : LocalPlaybackLibrary {
    private val tracks = items.associateBy { it.id }
    private val albums = items.groupBy { it.albumId }
    private val artists = items.flatMap { item -> item.artists.distinctBy { it.id }.map { it.id to item } }
        .groupBy({ it.first }, { it.second })

    override fun resolve(track: PlaybackTrackReference?, context: PlaybackContext?): LocalPlaybackRows? {
        if (track?.selectionToken != null) return null
        val rows = when (context?.kind) {
            null -> listOfNotNull(tracks[track?.id]?.toPlayback())
            "album", "artist" -> (if (context.kind == "album") albums else artists)[context.id].orEmpty()
                .map { it.toPlayback() }
            else -> return null
        }
        val playable = rows.filter { available(it.id) }
        if (playable.isEmpty()) return null
        val index = if (track == null) 0 else playable.indexOfFirst {
            it.id == track.id && (track.entryId == null || it.entryId == track.entryId) &&
                (track.occurrenceId == null || it.occurrenceId == track.occurrenceId) &&
                (track.targetId == null || it.targetId == track.targetId)
        }
        return if (index < 0) null else LocalPlaybackRows(playable, index)
    }

    override suspend fun hasAudio(track: PlaybackTrack, isAuthorized: () -> Boolean) =
        isAuthorized() && available(track.id)

    private fun MusicItem.toPlayback() = PlaybackTrack(id, name, artists.map { PlaybackArtist(it.name, it.id) },
        albumName, albumId, durationMs / 1000.0, 0, 0, "")
}
