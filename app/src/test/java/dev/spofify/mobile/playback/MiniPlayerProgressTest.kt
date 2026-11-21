package dev.spofify.mobile.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MiniPlayerProgressTest {
    @Test fun progressUsesReportedTimelineAndClampsToTheTrack() {
        assertEquals(0f, miniPlayerProgress(-1.0, 120.0))
        assertEquals(.25f, miniPlayerProgress(30.0, 120.0))
        assertEquals(1f, miniPlayerProgress(130.0, 120.0))
    }

    @Test fun unknownTimelinesDoNotDrawAProgressLine() {
        assertNull(miniPlayerProgress(0.0, 0.0))
        assertNull(miniPlayerProgress(1.0, -1.0))
        assertNull(miniPlayerProgress(Double.NaN, 120.0))
        assertNull(miniPlayerProgress(1.0, Double.POSITIVE_INFINITY))
    }
}
