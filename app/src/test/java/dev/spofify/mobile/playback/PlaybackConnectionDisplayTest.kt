package dev.spofify.mobile.playback

import androidx.media3.common.Player
import dev.spofify.mobile.core.PlaybackArtist
import dev.spofify.mobile.core.PlaybackContext
import dev.spofify.mobile.core.PlaybackOccurrence
import dev.spofify.mobile.core.PlaybackSnapshot
import dev.spofify.mobile.core.PlaybackTrack
import org.junit.Assert.*
import org.junit.Test

/** Remote rendering and command identity need neither a player nor a client/network. */
class PlaybackConnectionDisplayTest {
    private fun track(key: Long, order: Long) = PlaybackTrack(
        id = "duplicate", name = "Repeated song", artists = listOf(PlaybackArtist("artist", "Artist")),
        albumName = "Album", albumId = "album", duration = 125.125,
        key = key, order = order, entryId = "entry-$key", targetId = "target-$key",
        occurrenceId = "opaque-occurrence-$key",
    )

    @Test fun remoteDisplayPreservesRealizedOrderDuplicateOccurrencesAndFullWireMetadata() {
        val perTrack = PlaybackContext("derived", "mix", "Occurrence context", 8, "approved-fence")
        val queue = listOf(track(19, 900), track(3, 0).copy(playbackContext = perTrack), track(81, 40))
        val snapshot = PlaybackSnapshot(
            queue = queue, position = 1, context = PlaybackContext("playlist", "list", "Whole queue", 12),
            shuffle = false, repeat = "one", elapsed = 41.125, duration = 125.125, playing = true,
            occurrence = PlaybackOccurrence(3, "duplicate", "history-event", 0.75, false),
        ).validated()
        val display = remotePlaybackState(snapshot)

        assertEquals(listOf("entry-19", "entry-3", "entry-81"), display.queue.map { it.entryId })
        assertEquals("wire-3", display.currentEntryId)
        assertEquals(1, display.queueIndex)
        assertEquals("entry-3", display.item?.entryId)
        assertEquals(listOf("wire-81"), display.upcoming.map { it.occurrenceId })
        assertEquals(listOf(2), display.upcoming.map { it.timelineIndex })
        assertEquals(display.queue[2], display.upcoming.single().item)
        assertEquals("Occurrence context", display.context?.name)
        assertEquals(snapshot, display.remoteSnapshot)
        assertEquals(snapshot.toJson(), display.remoteSnapshot!!.toJson())
        assertEquals(perTrack, display.remoteSnapshot!!.queue[1].playbackContext)
        assertEquals("Whole queue", display.remoteSnapshot!!.context?.name)
        assertEquals(41_125L, display.positionMs)
        assertEquals(125_125L, display.durationMs)
        assertTrue(display.isPlaying)
        assertTrue(display.playWhenReady)
        assertFalse(display.shuffle) // Baseline and realized order need not match even without shuffle.
        assertEquals(Player.REPEAT_MODE_ONE, display.repeatMode)
        assertFalse(display.canUndoQueueRemoval)
        assertTrue(display.queue.all { it.libraryPath == null })
    }

    @Test fun remoteCollectionContextIsFallbackWithoutInventingAnAlbumContext() {
        val context = PlaybackContext("playlist", "collection", "Collection")
        val display = remotePlaybackState(PlaybackSnapshot(queue = listOf(track(1, 0)), position = 0, context = context))
        assertEquals("playlist", display.context?.kind)
        assertEquals("collection", display.context?.id)
        val noContext = remotePlaybackState(PlaybackSnapshot(queue = listOf(track(1, 0)), position = 0))
        assertNull(noContext.context)
    }

    @Test fun remoteDisplayUsesReportedTimingAndLoadingNotLocalOrExtrapolatedValues() {
        val source = PlaybackSnapshot(queue = listOf(track(1, 0)), position = 0,
            elapsed = 15.0006, duration = 0.0, loading = true, repeat = "all")
        val display = remotePlaybackState(source)
        assertEquals(15_001L, display.positionMs)
        assertEquals(0L, display.durationMs) // Unknown remote duration is not local track duration.
        assertTrue(display.isBuffering)
        assertFalse(display.isPlaying)
        assertFalse(display.playWhenReady)
        assertEquals(Player.REPEAT_MODE_ALL, display.repeatMode)
        assertTrue(remotePlaybackState(source.copy(loading = false, waiting = true)).isBuffering)
        assertFalse(remotePlaybackState(source.copy(loading = false)).isBuffering)
    }

    @Test fun emptyRemoteQueueClearsEveryLocalDisplayAndUndoField() {
        val display = remotePlaybackState(PlaybackSnapshot())
        assertNull(display.item)
        assertNull(display.currentEntryId)
        assertNull(display.context)
        assertTrue(display.queue.isEmpty())
        assertTrue(display.upcoming.isEmpty())
        assertEquals(-1, display.queueIndex)
        assertFalse(display.canUndoQueueRemoval)
        assertFalse(display.isPlaying)
        assertEquals(Player.REPEAT_MODE_OFF, display.repeatMode)
        assertEquals(PlaybackSnapshot(), display.remoteSnapshot)
    }

    @Test fun remoteErrorsAndTruncationAreSafeActionableDisplayText() {
        val source = PlaybackSnapshot()
        assertTrue(remotePlaybackState(source.copy(error = "blocked")).error!!.contains("tap"))
        assertTrue(remotePlaybackState(source.copy(error = "media")).error!!.contains("Retry"))
        assertTrue(remotePlaybackState(source.copy(error = "queue")).error!!.contains("queue"))
        assertEquals("Move is unsupported.", remotePlaybackState(source, "Move is unsupported.").error)
        assertNull(remotePlaybackState(source).error)
        assertTrue(remotePlaybackState(source.copy(truncated = true)).notice!!.contains("1,000"))
        assertTrue(remotePlaybackState(source.copy(notice = "PLAYBACK_LIMIT")).notice!!.contains("1,000"))
        assertTrue(remotePlaybackState(source.copy(notice = "CLICKED_OCCURRENCE_ONLY")).notice!!.contains("selected occurrence"))
    }

    @Test fun remoteQueueCommandsResolveOnlyUpcomingWireKeysNotTrackOrEntryOrOccurrenceMetadata() {
        val source = PlaybackSnapshot(queue = listOf(track(11, 0), track(12, 1), track(13, 2)), position = 1)
        assertEquals(13L, remoteUpcomingKey(source, "wire-13"))
        assertNull(remoteUpcomingKey(source, "wire-11"))
        assertNull(remoteUpcomingKey(source, "wire-12"))
        assertNull(remoteUpcomingKey(source, "wire-14"))
        assertNull(remoteUpcomingKey(source, "duplicate"))
        assertNull(remoteUpcomingKey(source, "entry-13"))
        assertNull(remoteUpcomingKey(source, "opaque-occurrence-13"))
        assertNull(remoteUpcomingKey(source, "wire-013"))
        val max = 9_007_199_254_740_991L
        assertEquals(max, remoteUpcomingKey(source.copy(queue = source.queue + track(max, 3)), "wire-$max"))
    }

    @Test fun remoteMoveIsOneSupportedDirectionAndNeverABatchForArbitraryReorder() {
        assertEquals("up", remoteMoveDirection(4, 3))
        assertEquals("down", remoteMoveDirection(4, 5))
        assertEquals("next", remoteMoveDirection(4, 0))
        assertEquals("next", remoteMoveDirection(1, 0))
        assertNull(remoteMoveDirection(1, 4))
        assertNull(remoteMoveDirection(5, 2))
        assertNull(remoteMoveDirection(4, 4))
        assertNull(remoteMoveDirection(-1, 0))
        assertNull(remoteMoveDirection(4, -1))
    }
}
