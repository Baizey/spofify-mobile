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

/** Local/read-only presentation projections, never an online queue import or resolver. */
class PlaybackSnapshotCodecTest {
    @Test fun largeLocalProjectionsRetainEveryDuplicateEntryAndExactOccurrenceKey() {
        for (size in listOf(1001, 5000)) {
            val items = (0 until size).map { LibraryItem("track", "same", "Song", hasAudio = true, entryId = "e$it") }
            val projected = items.mapIndexed { index, item -> PlaybackSnapshotCodec.track(item, index + 1L, index.toLong()) }
            assertEquals(size, projected.size)
            assertEquals(items.map { it.entryId }, projected.map { it.entryId })
            assertEquals((1..size).map(Int::toLong), projected.map { it.key })
            assertEquals((0 until size).map(Int::toLong), projected.map { it.order })
            assertEquals(size, projected.map(PlaybackSnapshotCodec::occurrenceId).toSet().size)
            assertEquals("e${size - 1}", PlaybackSnapshotCodec.libraryItem(projected.last()).entryId)
        }
    }

    @Test fun projectionContainsMusicOnlyNeverPrivateLibraryPathOrRawDiagnostics() {
        val item = LibraryItem("track", "song", "Song", artists = listOf(ArtistCredit("artist", "Artist")),
            albumId = "album", albumName = "Album", durationMs = 123_456,
            libraryPath = "/private/music.flac", entryId = "saved-entry")
        val projected = PlaybackSnapshotCodec.track(item, 7, 3)
        assertEquals("saved-entry", projected.entryId)
        assertEquals(123.456, projected.duration, 0.00001)
        assertFalse(projected.toString().contains("private"))
        assertEquals(7L, projected.key)
        assertEquals(3L, projected.order)
        assertNull(PlaybackSnapshotCodec.libraryItem(projected).libraryPath)
    }

    private fun musical(key: Long, order: Long) = PlaybackTrack(
        id = "duplicate/a%雪🎵", name = "Song 雪🎵", artists = listOf(PlaybackArtist("artist/雪", "Artist 🎵")),
        albumName = "Album 雪", albumId = "album/雪", duration = 123.456789,
        entryId = "entry-$key", targetId = "target-$key", occurrenceId = "移動/".repeat(200) + key,
        playbackContext = PlaybackContext("derived", "mix", "Mix", 17, "signed-fence"), key = key, order = order)

    @Test fun libraryProjectionPreservesUnicodeMetadataWithoutInventingPrivatePathsOrChangingSource() {
        val track = musical(9, 5)
        val item = PlaybackSnapshotCodec.libraryItem(track)
        assertEquals("track", item.kind)
        assertEquals(track.id, item.id)
        assertEquals(track.name, item.name)
        assertEquals(listOf(ArtistCredit("artist/雪", "Artist 🎵")), item.artists)
        assertEquals(track.albumId, item.albumId)
        assertEquals(track.albumName, item.albumName)
        assertEquals(track.entryId, item.entryId)
        assertEquals(123_457L, item.durationMs)
        assertTrue(item.playable)
        assertNull(item.libraryPath)
        assertEquals(123.456789, track.duration, 0.0)
        assertEquals("移動/".repeat(200) + 9, track.occurrenceId)
        assertEquals(17L, track.playbackContext!!.revision)
    }

    @Test fun rowIdentityUsesExactLongQueueKeyNeverTrackEntryOrDerivedPath() {
        val first = musical(91, 800)
        val second = first.copy(key = 7, order = 0)
        assertEquals(first.id, second.id)
        assertEquals(first.entryId, second.entryId)
        assertEquals(first.occurrenceId, second.occurrenceId)
        assertEquals("wire-91", PlaybackSnapshotCodec.occurrenceId(first))
        assertEquals("wire-7", PlaybackSnapshotCodec.occurrenceId(second))
        assertEquals("wire-9007199254740991", PlaybackSnapshotCodec.occurrenceId(
            first.copy(key = 9_007_199_254_740_991L, order = 9_007_199_254_740_991L)))
    }

    @Test fun projectionsOwnArtistContainersAndDoNotBorrowMutableInput() {
        val artists = mutableListOf(PlaybackArtist("a", "Original"))
        val item = PlaybackSnapshotCodec.libraryItem(musical(1, 0).copy(artists = artists))
        artists.clear()
        assertEquals(listOf(ArtistCredit("a", "Original")), item.artists)
        val credits = mutableListOf(ArtistCredit("a", "Original"))
        val track = PlaybackSnapshotCodec.track(LibraryItem("track", "t", "Song", artists = credits), 1, 0)
        credits.clear()
        assertEquals(listOf(PlaybackArtist("a", "Original")), track.artists)
    }

    @Test fun realizedLocalOrderKeepsDuplicateKeysBaselineAndCurrentNonce() {
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
