package dev.spofify.mobile.playback

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import dev.spofify.mobile.core.PlaybackArtist
import dev.spofify.mobile.core.PlaybackContext
import dev.spofify.mobile.core.PlaybackSnapshot
import dev.spofify.mobile.core.PlaybackTrack
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RemoteSessionViewTest {
    private fun track(key: Long, order: Long, duration: Double = 30.125) = PlaybackTrack(
        id = "duplicate-track", name = "Song", artists = listOf(PlaybackArtist("artist-1", "First"), PlaybackArtist("artist-2", "Second")),
        albumName = "Album", albumId = "album", duration = duration, key = key, order = order,
    )

    private fun snapshot() = PlaybackSnapshot(
        // Deliberately not baseline order: remote timeline MUST NOT sort by track.order.
        queue = listOf(track(91, 20), track(7, 0), track(33, 10)),
        position = 1, shuffle = true, repeat = "all", elapsed = 12.345, duration = 31.25,
    )

    @Test fun duplicateOccurrencesHaveDistinctStableWireKeysAndExactIndexMapping() {
        val view = RemoteSessionView(snapshot())
        assertEquals(listOf("duplicate-track", "duplicate-track", "duplicate-track"), view.mediaItems.map { it.mediaId })
        assertEquals(listOf(91L, 7L, 33L), view.mediaItems.map { it.mediaMetadata.extras!!.getLong(RemoteSessionView.WIRE_KEY) })
        assertEquals(3, view.timeline.windowCount)
        assertEquals(3, view.timeline.periodCount)
        val window = Timeline.Window()
        val period = Timeline.Period()
        view.snapshot.queue.forEachIndexed { index, track ->
            assertEquals(track.key, view.keyAtMediaItemIndex(index))
            assertEquals(track.key, view.timeline.getUidOfPeriod(index))
            assertEquals(index, view.timeline.getIndexOfPeriod(track.key))
            view.timeline.getWindow(index, window, 1_000_000)
            assertEquals(track.key, window.uid)
            assertSame(view.mediaItems[index], window.mediaItem)
            assertEquals(index, window.firstPeriodIndex)
            assertEquals(index, window.lastPeriodIndex)
            assertEquals(0L, window.defaultPositionUs)
            assertEquals(0L, window.positionInFirstPeriodUs)
            assertTrue(window.isSeekable)
            assertFalse(window.isDynamic)
            assertFalse(window.isLive)
            assertNull(window.manifest)
            view.timeline.getPeriod(index, period, true)
            assertEquals(track.key, period.id)
            assertEquals(track.key, period.uid)
            assertEquals(index, period.windowIndex)
            assertEquals(window.durationUs, period.durationUs)
            assertEquals(0L, period.positionInWindowUs)
            val mapped = view.timeline.getPeriodPositionUs(window, period, index, C.TIME_UNSET)
            assertEquals(track.key, mapped.first)
            assertEquals(0L, mapped.second)
        }
        view.timeline.getPeriod(0, period, false)
        assertNull(period.id)
        assertNull(period.uid)
        assertEquals(C.INDEX_UNSET, view.timeline.getIndexOfPeriod(91)) // Int is not a wire Long.
        assertEquals(C.INDEX_UNSET, view.timeline.getIndexOfPeriod("91"))
        assertEquals(C.INDEX_UNSET, view.timeline.getIndexOfPeriod(999L))
        assertNull(view.keyAtMediaItemIndex(-1))
        assertNull(view.keyAtMediaItemIndex(3))

        val reordered = RemoteSessionView(view.snapshot.copy(queue = view.snapshot.queue.reversed()))
        assertEquals(view.timeline.getUidOfPeriod(0), reordered.timeline.getUidOfPeriod(2))
        assertEquals(view.mediaItems[0], reordered.mediaItems[2])
    }

    @Test fun metadataHasNoAudioArtworkRequestOrSensitiveContext() {
        val context = PlaybackContext("derived", "mix", "Mix", 1, "synthetic-sensitive-fence")
        val state = snapshot().let { state ->
            state.copy(context = context, queue = state.queue.map {
                it.copy(playbackContext = context, targetId = "https://untrusted.invalid/audio", occurrenceId = "opaque-synthetic-occurrence")
            })
        }
        val view = RemoteSessionView(state)
        assertEquals(state, view.snapshot)
        view.mediaItems.forEach { item ->
            assertNull(item.localConfiguration)
            assertNull(item.requestMetadata.mediaUri)
            assertNull(item.requestMetadata.extras)
            with(item.mediaMetadata) {
                assertEquals("Song", title.toString())
                assertEquals("First, Second", artist.toString())
                assertEquals("Album", albumTitle.toString())
                assertEquals(true, isPlayable)
                assertEquals(false, isBrowsable)
                assertNull(artworkUri)
                assertNull(artworkData)
                assertEquals(setOf(RemoteSessionView.WIRE_KEY), extras!!.keySet())
                assertTrue(extras!!.getLong(RemoteSessionView.WIRE_KEY) > 0)
            }
        }
    }

    @Test fun repeatAndShuffleTraverseTheRealizedQueueWithoutASecondShuffle() {
        val timeline = RemoteSessionView(snapshot()).timeline
        for (shuffle in listOf(false, true)) {
            assertEquals(0, timeline.getFirstWindowIndex(shuffle))
            assertEquals(2, timeline.getLastWindowIndex(shuffle))
            for (index in 0..2) {
                assertEquals(if (index == 2) C.INDEX_UNSET else index + 1,
                    timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffle))
                assertEquals(if (index == 0) C.INDEX_UNSET else index - 1,
                    timeline.getPreviousWindowIndex(index, Player.REPEAT_MODE_OFF, shuffle))
                assertEquals((index + 1) % 3, timeline.getNextWindowIndex(index, Player.REPEAT_MODE_ALL, shuffle))
                assertEquals((index + 2) % 3, timeline.getPreviousWindowIndex(index, Player.REPEAT_MODE_ALL, shuffle))
                assertEquals(index, timeline.getNextWindowIndex(index, Player.REPEAT_MODE_ONE, shuffle))
                assertEquals(index, timeline.getPreviousWindowIndex(index, Player.REPEAT_MODE_ONE, shuffle))
                assertEquals((index + 1) % 3, timeline.getNextPeriodIndex(
                    index, Timeline.Period(), Timeline.Window(), Player.REPEAT_MODE_ALL, shuffle))
            }
        }
    }

    @Test fun currentDurationOverridesTrackAndElapsedIsBoundedWithoutExtrapolation() {
        val view = RemoteSessionView(snapshot().copy(elapsed = 99.0))
        assertEquals(1, view.currentIndex)
        assertEquals(31_250L, view.durationMs)
        assertEquals(31_250L, view.positionMs)
        assertEquals(30_125L, view.timeline.getWindow(0, Timeline.Window()).durationMs)
        assertEquals(31_250L, view.timeline.getWindow(1, Timeline.Window()).durationMs)
        assertEquals(12_345L, RemoteSessionView(snapshot()).positionMs)
        assertEquals(30_125L, RemoteSessionView(snapshot().copy(duration = 0.0)).durationMs)
        val unknown = RemoteSessionView(PlaybackSnapshot(queue = listOf(track(1, 0, 0.0)), position = 0, elapsed = 4.25))
        assertEquals(C.TIME_UNSET, unknown.durationMs)
        assertEquals(4_250L, unknown.positionMs)
        assertEquals(C.TIME_UNSET, unknown.timeline.getWindow(0, Timeline.Window()).durationUs)
        assertEquals(C.TIME_UNSET, unknown.timeline.getPeriod(0, Timeline.Period()).durationUs)
    }

    @Test fun emptySnapshotHasNoCurrentItemDurationOrPhantomTraversal() {
        val view = RemoteSessionView(PlaybackSnapshot())
        assertTrue(view.mediaItems.isEmpty())
        assertTrue(view.timeline.isEmpty)
        assertEquals(0, view.timeline.periodCount)
        assertEquals(C.INDEX_UNSET, view.currentIndex)
        assertEquals(C.TIME_UNSET, view.durationMs)
        assertEquals(0L, view.positionMs)
        assertNull(view.keyAtMediaItemIndex(0))
        assertEquals(C.INDEX_UNSET, view.timeline.getIndexOfPeriod(1L))
        for (shuffle in listOf(false, true)) {
            assertEquals(C.INDEX_UNSET, view.timeline.getFirstWindowIndex(shuffle))
            assertEquals(C.INDEX_UNSET, view.timeline.getLastWindowIndex(shuffle))
            for (repeat in listOf(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL, Player.REPEAT_MODE_ONE)) {
                assertEquals(C.INDEX_UNSET, view.timeline.getNextWindowIndex(-1, repeat, shuffle))
                assertEquals(C.INDEX_UNSET, view.timeline.getPreviousWindowIndex(0, repeat, shuffle))
            }
        }
        assertThrows(IndexOutOfBoundsException::class.java) { view.timeline.getWindow(0, Timeline.Window()) }
        assertThrows(IndexOutOfBoundsException::class.java) { view.timeline.getUidOfPeriod(0) }
    }

    @Test fun conversionsAreFiniteBoundedAndPreserveUnknownDuration() {
        assertEquals(604_800_000L, RemoteSessionView.secondsToDurationMs(604800.0))
        assertEquals(12_346L, RemoteSessionView.secondsToDurationMs(12.3456))
        for (seconds in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 604800.001, Double.MAX_VALUE)) {
            assertEquals(C.TIME_UNSET, RemoteSessionView.secondsToDurationMs(seconds))
            assertEquals(0L, RemoteSessionView.secondsToElapsedMs(seconds))
        }
        assertEquals(604_800_000L, RemoteSessionView.secondsToElapsedMs(604800.0))
        assertEquals(1_000L, RemoteSessionView.secondsToElapsedMs(12.0, 1_000))
        assertEquals(0L, RemoteSessionView.secondsToElapsedMs(12.0, 0))
        assertEquals(12_000L, RemoteSessionView.secondsToElapsedMs(12.0, Long.MAX_VALUE))
    }

    @Test fun malformedSnapshotsAreRejectedBeforeProjection() {
        for (seconds in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, 604800.001)) {
            assertThrows(IllegalArgumentException::class.java) { RemoteSessionView(snapshot().copy(duration = seconds)) }
            assertThrows(IllegalArgumentException::class.java) { RemoteSessionView(snapshot().copy(elapsed = seconds)) }
            assertThrows(IllegalArgumentException::class.java) {
                RemoteSessionView(PlaybackSnapshot(queue = listOf(track(1, 0, seconds)), position = 0))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteSessionView(snapshot().copy(queue = listOf(track(1, 0), track(1, 1))))
        }
    }

    @Test fun projectionOwnsQueueAndArtistListContainersAndRetainsDisplayFlags() {
        val artists = mutableListOf(PlaybackArtist("artist", "Original"))
        val queue = mutableListOf(track(1, 0).copy(artists = artists))
        val state = PlaybackSnapshot(queue = queue, position = 0, playing = true, waiting = true, loading = true, error = "blocked")
        val view = RemoteSessionView(state)
        artists.clear()
        queue.clear()
        assertEquals(1, view.snapshot.queue.size)
        assertEquals("Original", view.snapshot.queue.single().artists.single().name)
        assertEquals("Original", view.mediaItems.single().mediaMetadata.artist.toString())
        assertEquals(1L, view.timeline.getUidOfPeriod(0))
        assertTrue(view.snapshot.playing)
        assertTrue(view.snapshot.waiting)
        assertTrue(view.snapshot.loading)
        assertEquals("blocked", view.snapshot.error)
    }
}
