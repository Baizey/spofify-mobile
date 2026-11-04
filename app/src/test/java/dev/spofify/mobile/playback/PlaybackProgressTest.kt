package dev.spofify.mobile.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackProgressTest {
    @Test fun recordsOnceAfterOneSecondOfActualProgress() {
        val gate = PlaybackProgress()
        assertFalse(gate.sample(0, 0, true))
        assertFalse(gate.sample(999, 999, true))
        assertTrue(gate.sample(1000, 1000, true))
        assertFalse(gate.sample(2000, 2000, true))
    }

    @Test fun preparationBufferingAndPauseDoNotCount() {
        val gate = PlaybackProgress()
        assertFalse(gate.sample(0, 0, false))
        assertFalse(gate.sample(5000, 5000, false))
        assertFalse(gate.sample(5000, 6000, true))
        assertFalse(gate.sample(5500, 6500, true))
        gate.discontinuity()
        assertFalse(gate.sample(5500, 20_000, false))
        assertFalse(gate.sample(5500, 30_000, true))
        assertTrue(gate.sample(6000, 30_500, true))
    }

    @Test fun seeksDoNotCountButRealIntervalsAccumulate() {
        val gate = PlaybackProgress()
        assertFalse(gate.sample(0, 0, true))
        assertFalse(gate.sample(400, 400, true))
        gate.discontinuity()
        assertFalse(gate.sample(100_000, 400, true))
        assertFalse(gate.sample(100_599, 999, true))
        assertTrue(gate.sample(100_600, 1000, true))
    }

    @Test fun stalledClockAndUnannouncedJumpsAreNotListening() {
        val gate = PlaybackProgress()
        assertFalse(gate.sample(0, 0, true))
        assertFalse(gate.sample(0, 60_000, true))
        assertFalse(gate.sample(100_000, 60_001, true))
        gate.discontinuity()
        assertFalse(gate.sample(0, 60_002, true))
        assertFalse(gate.sample(-500, 60_502, true))
    }

    @Test fun duplicateTracksHaveIndependentOccurrenceGates() {
        val first = PlaybackProgress()
        val duplicate = PlaybackProgress()
        assertFalse(first.sample(0, 0, true))
        assertFalse(duplicate.sample(0, 0, true))
        assertTrue(first.sample(1000, 1000, true))
        assertTrue(duplicate.sample(1000, 1000, true))
    }

    @Test fun exportedProgressResumesWithoutCountingImportPositionJump() {
        val source = PlaybackProgress()
        assertFalse(source.sample(0, 0, true))
        assertFalse(source.sample(600, 600, true))
        val destination = PlaybackProgress().apply { restore(source.snapshot()) }
        assertFalse(destination.sample(50_000, 30_000, true))
        assertTrue(destination.sample(50_400, 30_400, true))
    }

    @Test fun exportedReportedGateNeverWritesAgainAndNewTraversalCanQualify() {
        val source = PlaybackProgress()
        source.sample(0, 0, true)
        assertTrue(source.sample(1000, 1000, true))
        val destination = PlaybackProgress().apply { restore(source.snapshot()) }
        assertFalse(destination.sample(1000, 10_000, true))
        assertFalse(destination.sample(2000, 11_000, true))
        val nextTraversal = PlaybackProgress()
        assertFalse(nextTraversal.sample(0, 12_000, true))
        assertTrue(nextTraversal.sample(1000, 13_000, true))
    }

    @Test fun importedQualifiedProgressWaitsForNewAudibleProgressNotPausedOrAnchorSamples() {
        for (importedMs in listOf(1000L, 5000L, 604_800_000L)) {
            val gate = PlaybackProgress().apply { restore(PlaybackProgress.Snapshot(importedMs, false)) }
            assertEquals(PlaybackProgress.Snapshot(importedMs, false), gate.snapshot())
            assertFalse(gate.sample(50_000, 10_000, false))
            assertFalse(gate.sample(60_000, 20_000, false))
            assertFalse(gate.sample(60_000, 30_000, true)) // resume anchor, no listening
            assertFalse(gate.sample(60_000, 30_100, true)) // stalled position
            assertFalse(gate.sample(60_001, 30_100, true)) // position changed, but no wall time
            assertEquals(PlaybackProgress.Snapshot(importedMs, false), gate.snapshot())
            assertTrue(gate.sample(60_002, 30_101, true))
            assertEquals(PlaybackProgress.Snapshot((importedMs + 1).coerceAtMost(604_800_000), true), gate.snapshot())
            assertFalse(gate.sample(61_002, 31_101, true))
        }
    }

    @Test fun importedQualifiedProgressRejectsSeeksDiscontinuitiesAndZeroSpeed() {
        val gate = PlaybackProgress().apply { restore(PlaybackProgress.Snapshot(2000, false)) }
        assertFalse(gate.sample(0, 0, true))
        assertFalse(gate.sample(100_000, 1, true)) // unannounced seek, not playback
        gate.discontinuity()
        assertFalse(gate.sample(200_000, 2, true)) // seek anchor
        assertFalse(gate.sample(200_100, 102, true, speed = 0f))
        assertFalse(gate.sample(200_101, 101, true)) // backwards clock
        assertEquals(PlaybackProgress.Snapshot(2000, false), gate.snapshot())
        assertTrue(gate.sample(200_102, 102, true))
    }

    @Test fun restoreDiscardsAnExistingPlayingAnchorAndHonorsReportedFlagEvenBelowThreshold() {
        val gate = PlaybackProgress()
        assertFalse(gate.sample(0, 0, true))
        gate.restore(PlaybackProgress.Snapshot(1000, false))
        assertFalse(gate.sample(1000, 1000, true))
        assertTrue(gate.sample(1001, 1001, true))
        gate.restore(PlaybackProgress.Snapshot(0, true))
        assertFalse(gate.sample(0, 2000, true))
        assertFalse(gate.sample(5000, 7000, true))
        assertEquals(PlaybackProgress.Snapshot(0, true), gate.snapshot())
    }

    @Test fun invalidRestoredProgressIsRejectedWithoutChangingTheGate() {
        val gate = PlaybackProgress().apply { restore(PlaybackProgress.Snapshot(600, false)) }
        for (value in listOf(-1L, 604_800_001L)) {
            assertThrows(IllegalArgumentException::class.java) { gate.restore(PlaybackProgress.Snapshot(value, true)) }
        }
        assertEquals(PlaybackProgress.Snapshot(600, false), gate.snapshot())
    }

    @Test fun playbackSpeedBoundsExpectedPositionProgress() {
        val gate = PlaybackProgress()
        assertFalse(gate.sample(0, 0, true, speed = 2f))
        assertTrue(gate.sample(1000, 500, true, speed = 2f))
        val jump = PlaybackProgress()
        assertFalse(jump.sample(0, 0, true))
        assertFalse(jump.sample(10_000, 10, true))
    }
}
