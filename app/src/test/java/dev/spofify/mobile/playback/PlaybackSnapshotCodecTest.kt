package dev.spofify.mobile.playback

import dev.spofify.mobile.core.ArtistCredit
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.PlaybackOccurrence
import dev.spofify.mobile.core.PlaybackSnapshot
import org.junit.Assert.*
import org.junit.Test

class PlaybackSnapshotCodecTest {
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
