package dev.spofify.mobile.playback

import androidx.media3.common.MediaItem
import dev.spofify.mobile.core.ArtistCredit
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.PlaybackArtist
import dev.spofify.mobile.core.PlaybackContext
import dev.spofify.mobile.core.PlaybackSnapshot
import dev.spofify.mobile.core.PlaybackTrack
import dev.spofify.mobile.core.SpofifyClient
import kotlin.math.roundToLong

/** Positive musical projections and pure import planning; never fetch or re-evaluate a queue. */
internal object PlaybackSnapshotCodec {
    /**
     * Wire queue order is realized traversal, while `order` describes the physical baseline.
     * Retain [snapshot]/[physicalTracks] in the service: LibraryItem/MediaItem cannot represent
     * every wire field, and re-projecting them for export would lose exact occurrence identity.
     * This plan grants no playback authority and does not prepare, open or play any audio.
     */
    data class ImportPlan internal constructor(
        val snapshot: PlaybackSnapshot,
        /** Ascending wire order, including gaps; never renumber keys or order. */
        val physicalTracks: List<PlaybackTrack>,
        /** Each realized queue entry's physical index, suitable for an explicit shuffle order. */
        val traversalIndices: List<Int>,
        /** Physical index of snapshot.queue[snapshot.position], or -1 for an empty queue. */
        val currentIndex: Int,
        /** Media3 millisecond precision; the exact wire seconds remain in snapshot.elapsed. */
        val positionMs: Long,
    ) {
        /** Only derive URLs from the authenticated client's origin, never from wire metadata. */
        fun mediaItems(client: SpofifyClient): List<MediaItem> {
            require(client.currentSession() != null) { "Sign in before importing playback" }
            return physicalTracks.map { PlaybackMedia.create(libraryItem(it), client, occurrenceId(it)) }
        }
    }

    /** Validates the entire snapshot without a client, player, audio, or network access. */
    fun plan(snapshot: PlaybackSnapshot): ImportPlan {
        snapshot.publicationValidated()
        // The wire permits opaque IDs; native audio paths additionally reject dot segments.
        require(snapshot.queue.none { it.id == "." || it.id == ".." }) { "Invalid playback track" }
        // Own list containers so later changes to a caller's mutable lists cannot alter the plan.
        val retained = snapshot.copy(queue = snapshot.queue.map { it.copy(artists = it.artists.toList()) })
        val physical = retained.queue.sortedBy { it.order }
        val indexByKey = physical.mapIndexed { index, track -> track.key to index }.toMap()
        val traversal = retained.queue.map { indexByKey.getValue(it.key) }
        return ImportPlan(retained, physical, traversal,
            traversal.getOrElse(retained.position) { -1 }, secondsToMs(retained.elapsed))
    }

    /** New explicit queue intent, not handoff/restoration. Never reconstruct a derived envelope
     * from lossy LibraryItems: only the original prepared response supplies its context/fence. */
    fun preparedQueue(queue: dev.spofify.mobile.core.PlaybackQueue, startTrackId: String?, startEntryId: String?,
        shuffle: Boolean, context: LibraryItem?): PlaybackSnapshot {
        val exact = queue.preparedState
        if (exact != null) {
            val retained = plan(exact).snapshot
            val selected = retained.queue.getOrNull(retained.position)
            require(retained.queue.map { it.id to if (it.targetId.isNotEmpty()) it.occurrenceId else it.entryId } ==
                queue.items.map { it.id to it.entryId.orEmpty() })
            require(startEntryId == null || (selected?.let { if (it.targetId.isNotEmpty()) it.occurrenceId else it.entryId }) == startEntryId)
            require(startTrackId == null || selected?.id == startTrackId)
            val order = if (shuffle && selected != null) listOf(selected) + retained.queue.filter { it.key != selected.key }.shuffled() else retained.queue
            return retained.copy(queue = order, position = if (selected == null) -1 else order.indexOf(selected),
                shuffle = shuffle || retained.shuffle, playing = true, waiting = false, loading = false, error = "", occurrence = null).publicationValidated()
        }
        require(queue.context?.kind != "derived" && context?.kind != "derived") { "Reload the original derived selection" }
        val playable = queue.items.filter { it.kind == "track" && it.playable }
        require(playable.isNotEmpty()) { "No playable tracks" }
        require(startEntryId == null || playable.any { it.entryId == startEntryId && (startTrackId == null || it.id == startTrackId) })
        require(startEntryId != null || startTrackId == null || playable.any { it.id == startTrackId })
        val chosen = playbackStartIndex(playable, startTrackId, startEntryId)
        val clickedOnly = chosen >= PlaybackQueue.LIMIT
        val accepted = if (clickedOnly) listOf(playable[chosen]) else playable.take(PlaybackQueue.LIMIT)
        val tracks = accepted.mapIndexed { index, item -> track(item, index + 1L, index.toLong()) }
        val selected = tracks[if (clickedOnly) 0 else playbackStartIndex(accepted, startTrackId, startEntryId)]
        val order = if (shuffle) listOf(selected) + tracks.filter { it.key != selected.key }.shuffled() else tracks
        val identity = queue.context?.let { LibraryItem(it.kind, it.id, it.name) } ?: context
        val wireContext = identity?.let { PlaybackContext(it.kind,
            if (it.kind == "likes" && it.id.isEmpty()) "likes" else it.id, it.name, queue.revision) }
        val limited = clickedOnly || queue.truncated || playable.size > PlaybackQueue.LIMIT
        return PlaybackSnapshot(queue = order, position = order.indexOf(selected), context = wireContext,
            shuffle = shuffle, playing = true, truncated = limited,
            notice = if (clickedOnly) "CLICKED_OCCURRENCE_ONLY" else if (limited) "PLAYBACK_LIMIT" else "").publicationValidated()
    }

    /** Internal queue identity is intentionally separate from arbitrary wire occurrenceId text. */
    fun occurrenceId(track: PlaybackTrack): String = "wire-${track.key}"

    /** Lossy display/audio projection only; keep the original wire track for subsequent export. */
    fun libraryItem(track: PlaybackTrack): LibraryItem = LibraryItem(
        kind = "track", id = track.id, name = track.name,
        artists = track.artists.map { ArtistCredit(it.id, it.name) },
        albumName = track.albumName, albumId = track.albumId,
        durationMs = secondsToMs(track.duration), hasAudio = true, entryId = track.entryId,
    )

    private fun secondsToMs(seconds: Double): Long = (seconds * 1000).roundToLong()

    /** Local-only export projection; imported occurrences should reuse their retained wire track. */
    fun track(item: LibraryItem, key: Long, order: Long): PlaybackTrack = PlaybackTrack(
        id = item.id, name = item.name,
        artists = item.artists.map { PlaybackArtist(it.id, it.name) },
        albumName = item.albumName.orEmpty(), albumId = item.albumId.orEmpty(),
        duration = item.durationMs.coerceAtLeast(0) / 1000.0,
        entryId = item.entryId.orEmpty(), key = key, order = order,
    )
}
