package dev.spofify.mobile.playback

import org.junit.Assert.*
import org.junit.Test

class PlayerTimeLabelTest {
    @Test fun ordinaryLabelsCenterAboveShuffleAndRepeat() {
        for (halfWidth in listOf(180, 181, 240, 579)) {
            val textWidth = 32
            val elapsed = playerTimeLabelOffset(halfWidth, textWidth, end = false)
            val total = playerTimeLabelOffset(halfWidth, textWidth, end = true)
            assertEquals(halfWidth * .2f, elapsed + textWidth / 2f, .5f)
            assertEquals(halfWidth * .8f, total + textWidth / 2f, .5f)
        }
    }

    @Test fun longOrScaledLabelsStayInsideTheirHalfWithoutOverlapping() {
        assertEquals(0, playerTimeLabelOffset(180, 130, end = false))
        assertEquals(50, playerTimeLabelOffset(180, 130, end = true))
        for (textWidth in listOf(0, 32, 80, 130, 180)) {
            for (end in listOf(false, true)) {
                val offset = playerTimeLabelOffset(180, textWidth, end)
                assertTrue(offset >= 0)
                assertTrue(offset + textWidth <= 180)
            }
        }
    }

    @Test fun zeroWidthAndOversizedDefensiveInputsDoNotCreateNegativeOffsets() {
        assertEquals(0, playerTimeLabelOffset(0, 0, end = false))
        assertEquals(0, playerTimeLabelOffset(10, 20, end = true))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeMeasurementsAreRejected() {
        playerTimeLabelOffset(-1, 20, end = false)
    }
}
