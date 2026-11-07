package dev.spofify.mobile.playback

import dev.spofify.mobile.core.ArtistCredit
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.PlaybackArtist
import dev.spofify.mobile.core.PlaybackContext
import dev.spofify.mobile.core.PlaybackOccurrence
import dev.spofify.mobile.core.PlaybackSnapshot
import dev.spofify.mobile.core.PlaybackTrack
import org.junit.Assert.*
import org.junit.Test

class PlaybackSnapshotCodecTest {
    @Test fun largePreparedAndImportedQueuesRetainLastDuplicateAndFullBaseline() {
        for (size in listOf(1001, 5000)) {
            val items = (0 until size).map { LibraryItem("track", "same", "Song", hasAudio = true, entryId = "e$it") }
            val prepared = PlaybackSnapshotCodec.preparedQueue(dev.spofify.mobile.core.PlaybackQueue(items),
                "same", "e${size - 1}", false, null)
            assertEquals(size, prepared.queue.size)
            assertEquals(size - 1, prepared.position)
            assertEquals(items.map { it.entryId }, prepared.queue.map { it.entryId })
            val retained = prepared.copy(queue = prepared.queue.reversed(), position = 0, playing = false,
                elapsed = 42.125, error = "media", loading = true,
                occurrence = PlaybackOccurrence(prepared.queue.last().key, "same", "event", 12.0, true))
            val plan = PlaybackSnapshotCodec.plan(retained)
            assertEquals(retained, plan.snapshot)
            assertEquals(prepared.queue, plan.physicalTracks)
            assertEquals(size - 1, plan.currentIndex)
            assertEquals((size - 1 downTo 0).toList(), plan.traversalIndices)
        }
    }

    @Test fun wireProjectionContainsMusicOnlyNeverPrivateLibraryPath() {
        val track = LibraryItem("track", "song", "Song", artists = listOf(ArtistCredit("artist", "Artist")),
            albumId = "album", albumName = "Album", durationMs = 123_456, libraryPath = "/private/music.flac", entryId = "saved-entry")
        val projected = PlaybackSnapshotCodec.track(track, 7, 3)
        assertEquals("saved-entry", projected.entryId)
        assertEquals(123.456, projected.duration, 0.00001)
        assertFalse(projected.toString().contains("private"))
        assertEquals(7L, projected.key)
        assertEquals(3L, projected.order)
    }

    private fun wire(key: Long, order: Long) = PlaybackTrack(
        id = "duplicate/a%", name = "Song", artists = listOf(PlaybackArtist("artist", "Artist")),
        albumName = "Album", albumId = "album", duration = 123.456789,
        entryId = "entry-$key", targetId = "target-$key", occurrenceId = "移動/".repeat(200) + key,
        playbackContext = PlaybackContext("derived", "mix", "Mix", 17, "signed-fence"),
        key = key, order = order,
    )

    @Test fun importPlanRetainsFullWireTracksAndSnapshotWithSparseBaselineAndRealizedTraversal() {
        val queue = listOf(wire(91, 800), wire(7, 0), wire(32, 30))
        val snapshot = PlaybackSnapshot(queue = queue, position = 2,
            context = PlaybackContext("playlist", "playlist", "Queue", 18), shuffle = true,
            repeat = "one", elapsed = 65.536, duration = 123.456789, playing = true,
            waiting = true, loading = true, error = "media", truncated = true, notice = "PLAYBACK_LIMIT",
            occurrence = PlaybackOccurrence(32, queue[2].id, "same-history-nonce", 1.375, false))
        val plan = PlaybackSnapshotCodec.plan(snapshot)

        assertEquals(snapshot, plan.snapshot)
        assertEquals(listOf(queue[1], queue[2], queue[0]), plan.physicalTracks)
        assertEquals(3, plan.physicalTracks.size)
        assertEquals(listOf(2, 0, 1), plan.traversalIndices)
        assertEquals(queue, plan.traversalIndices.map { plan.physicalTracks[it] })
        assertEquals(1, plan.currentIndex)
        assertEquals(queue[2], plan.physicalTracks[plan.currentIndex])
        assertEquals(65_536L, plan.positionMs)
        assertEquals(snapshot.toJson(), plan.snapshot.toJson())
        assertEquals(listOf("wire-7", "wire-32", "wire-91"), plan.physicalTracks.map(PlaybackSnapshotCodec::occurrenceId))
    }

    @Test fun libraryProjectionIsPlayableWithoutInventingPrivatePathsOrChangingTheWireTrack() {
        val track = wire(9, 5)
        val item = PlaybackSnapshotCodec.libraryItem(track)
        assertEquals("track", item.kind)
        assertEquals(track.id, item.id)
        assertEquals(track.name, item.name)
        assertEquals(listOf(ArtistCredit("artist", "Artist")), item.artists)
        assertEquals(track.albumId, item.albumId)
        assertEquals(track.albumName, item.albumName)
        assertEquals(track.entryId, item.entryId)
        assertEquals(123_457L, item.durationMs)
        assertTrue(item.playable)
        assertNull(item.libraryPath)
        assertEquals(123.456789, track.duration, 0.0)
    }

    @Test fun emptyAndUnshuffledPlansDoNotInventACurrentTrackOrRandomizeTraversal() {
        val empty = PlaybackSnapshotCodec.plan(PlaybackSnapshot())
        assertTrue(empty.physicalTracks.isEmpty())
        assertTrue(empty.traversalIndices.isEmpty())
        assertEquals(-1, empty.currentIndex)
        assertEquals(0L, empty.positionMs)
        val snapshot = PlaybackSnapshot(queue = listOf(wire(8, 20), wire(4, 10)), position = 0)
        val plan = PlaybackSnapshotCodec.plan(snapshot)
        assertFalse(plan.snapshot.shuffle)
        assertEquals(listOf(1, 0), plan.traversalIndices)
        assertEquals(1, plan.currentIndex)
        assertEquals(snapshot, plan.snapshot)
    }

    @Test fun maximumWireKeysAndOrdersRemainLongsAndPositionIsNotClampedToUnknownDuration() {
        val track = wire(9_007_199_254_740_991L, 9_007_199_254_740_991L).copy(duration = 0.0)
        val snapshot = PlaybackSnapshot(queue = listOf(track), position = 0, elapsed = 604800.0)
        val plan = PlaybackSnapshotCodec.plan(snapshot)
        assertEquals(listOf(track), plan.physicalTracks)
        assertEquals(listOf(0), plan.traversalIndices)
        assertEquals(0, plan.currentIndex)
        assertEquals(604_800_000L, plan.positionMs)
        assertEquals("wire-9007199254740991", PlaybackSnapshotCodec.occurrenceId(track))
        assertEquals(snapshot, plan.snapshot)
    }

    @Test fun importPlanOwnsQueueAndArtistListContainers() {
        val artists = mutableListOf(PlaybackArtist("a", "Original"))
        val queue = mutableListOf(wire(1, 0).copy(artists = artists))
        val plan = PlaybackSnapshotCodec.plan(PlaybackSnapshot(queue = queue, position = 0))
        artists.clear()
        queue.clear()
        assertEquals(1, plan.snapshot.queue.size)
        assertEquals(listOf(PlaybackArtist("a", "Original")), plan.physicalTracks.single().artists)
        assertEquals(plan.snapshot.queue, plan.physicalTracks)
    }

    @Test fun invalidImportsRejectRatherThanDeduplicateTruncateClampOrRepair() {
        val valid = PlaybackSnapshot(queue = listOf(wire(1, 0), wire(2, 1)), position = 0)
        val invalid = listOf(
            valid.copy(queue = listOf(wire(1, 0), wire(1, 1))),
            valid.copy(queue = listOf(wire(1, 0), wire(2, 0))),
            valid.copy(position = -1), valid.copy(position = 2),
            valid.copy(queue = listOf(wire(1, 0).copy(id = "."))),
            valid.copy(queue = listOf(wire(1, 0).copy(id = ".."))),
            valid.copy(elapsed = Double.NaN), valid.copy(elapsed = -1.0),
            valid.copy(queue = (1..1001).map { wire(it.toLong(), it.toLong()).copy(name = "\\".repeat(512), albumName = "\\".repeat(512)) }),
            valid.copy(occurrence = PlaybackOccurrence(2, valid.queue[1].id, "wrong-current", 1.0, false)),
            valid.copy(occurrence = PlaybackOccurrence(1, valid.queue[0].id, "nonce", 604801.0, false)),
        )
        invalid.forEach { snapshot ->
            assertThrows(IllegalArgumentException::class.java) { PlaybackSnapshotCodec.plan(snapshot) }
        }
    }

    @Test fun realizedOrderKeepsDuplicateKeysAndPhysicalBaselineAndCurrentNonce() {
        val item = LibraryItem("track", "duplicate", "Duplicate", hasAudio = true)
        val realized = listOf(PlaybackSnapshotCodec.track(item, 2, 1), PlaybackSnapshotCodec.track(item, 1, 0))
        val snapshot = PlaybackSnapshot(queue = realized, position = 0, shuffle = true, elapsed = 4.25,
            occurrence = PlaybackOccurrence(2, "duplicate", "event-nonce", 0.6, false)).validated()
        assertEquals(listOf(2L, 1L), snapshot.queue.map { it.key })
        assertEquals(listOf(1L, 2L), snapshot.queue.sortedBy { it.order }.map { it.key })
        assertEquals("event-nonce", snapshot.occurrence?.eventId)
        assertEquals(4.25, snapshot.elapsed, 0.0)
    }
}
