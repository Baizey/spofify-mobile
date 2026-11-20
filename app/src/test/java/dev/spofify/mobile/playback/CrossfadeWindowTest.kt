package dev.spofify.mobile.playback

import org.junit.Assert.*
import org.junit.Test

class CrossfadeWindowTest {
    @Test fun overlapIsFiveSecondsBoundedByBothNativeDurations() {
        assertEquals(5000L, CrossfadeWindow.length(90_000, 10_000))
        assertEquals(750L, CrossfadeWindow.length(1500, 300_000))
        assertEquals(600L, CrossfadeWindow.length(90_000, 1200))
        assertEquals(0L, CrossfadeWindow.length(90_000, -1))
    }

    @Test fun equalPowerGainsAreBoundedContinuousAndMonotonic() {
        var lastOut = 1f
        var lastIn = 0f
        for (left in 5100L downTo -100L step 20) {
            val (outgoing, incoming) = CrossfadeWindow.gains(left, 5000)
            assertTrue(outgoing in 0f..lastOut && incoming in lastIn..1f)
            assertEquals(1f, outgoing * outgoing + incoming * incoming, 0.00001f)
            lastOut = outgoing
            lastIn = incoming
        }
        assertEquals(0f, lastOut, 0.00001f)
        assertEquals(1f, lastIn, 0f)
    }
}
