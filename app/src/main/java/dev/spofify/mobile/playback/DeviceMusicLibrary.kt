package dev.spofify.mobile.playback

import dev.spofify.mobile.core.library.MusicItem
import dev.spofify.mobile.core.offline.OfflineManifest
import dev.spofify.mobile.core.offline.OfflinePlaylistKind
import dev.spofify.mobile.core.playback.*

/** Immutable, preloaded index. The player looper never blocks on catalog/storage. A manifest
 * occurrence is a local row identity, not a transferable server selection token. */
internal class DeviceMusicLibrary(
    items: List<MusicItem>,
    manifests: List<OfflineManifest>,
    private val available: (String) -> Boolean,
) : LocalPlaybackLibrary {
    private val tracks = items.associateBy { it.id }
    private val albums = items.groupBy { it.albumId }
    private val artists = items.flatMap { item -> item.artists.distinctBy { it.id }.map { it.id to item } }
        .groupBy({ it.first }, { it.second })
    private val saved = manifests.associateBy { it.collection.reference.id }

    override fun resolve(track: PlaybackTrackReference?, context: PlaybackContext?): LocalPlaybackRows? {
        if (track?.selectionToken != null) return null
        val rows = when (context?.kind) {
            null -> listOfNotNull(tracks[track?.id]?.toPlayback())
            "album", "artist" -> (if (context.kind == "album") albums else artists)[context.id].orEmpty()
                .map { it.toPlayback() }
            "playlist", "derived" -> {
                val manifest = saved[context.id] ?: return null
                val derived = manifest.collection.playlistKind == OfflinePlaylistKind.DERIVED
                if (derived != (context.kind == "derived") || context.fence != null && context.fence != manifest.fence ||
                    context.revision != null && context.revision != manifest.collection.revision) return null
                manifest.items.map { row ->
                    val t = row.track
                    PlaybackTrack(t.id, t.name, t.artists.map { PlaybackArtist(it.name, it.id) }, t.albumName,
                        t.albumId, t.durationMs / 1000.0, 0, 0, "", entryId = row.occurrenceId.takeUnless { derived },
                        targetId = context.id.takeIf { derived }, occurrenceId = row.occurrenceId.takeIf { derived })
                }
            }
            else -> return null
        }
        // Do not silently advance over a missing occurrence in a complete saved playlist.
        if (context?.kind in setOf("playlist", "derived") && rows.any { !available(it.id) }) return null
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
