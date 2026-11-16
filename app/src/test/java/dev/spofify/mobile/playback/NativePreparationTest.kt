package dev.spofify.mobile.playback

import androidx.media3.common.Player
import dev.spofify.mobile.core.playback.AudioBinding
import dev.spofify.mobile.core.playback.AudioResult
import org.junit.Assert.*
import org.junit.Test

class NativePreparationTest {
    private val binding = AudioBinding("instruction", 1, "event", "track")

    @Test fun bufferingRemainsPendingAndReadyCompletesNormally() {
        val preparation = preparation(0)
        assertNull(preparation.outcome(Player.STATE_BUFFERING, false, 120_000))
        assertEquals(AudioResult.OK, preparation.outcome(Player.STATE_READY, false, 120_000))
    }

    @Test fun stoppedPreparationAtKnownEofCompletesWithoutAReadyTransitionOrTraversalEvent() {
        val preparation = preparation(120_000)
        assertEquals(AudioResult.OK, preparation.outcome(Player.STATE_ENDED, false, 120_000))
        assertFalse(preparation.reportsNativeEnd(Player.STATE_ENDED, false, 120_000))
        assertFalse(preparation.result.isCompleted)
        preparation.result.complete(AudioResult.OK)
        assertFalse(preparation.reportsNativeEnd(Player.STATE_ENDED, false, 120_000))
    }

    @Test fun millisecondConversionRoundingDoesNotLeaveEofPreparationHanging() {
        assertEquals(AudioResult.OK, preparation(119_999).outcome(Player.STATE_ENDED, false, 120_000))
    }

    @Test fun errorAndUnexpectedOrUnknownTerminationAreNotAcceptedAsKnownEof() {
        assertEquals(AudioResult.MEDIA, preparation(120_000).outcome(Player.STATE_ENDED, true, 120_000))
        assertEquals(AudioResult.MEDIA, preparation(0).outcome(Player.STATE_ENDED, false, 120_000))
        assertEquals(AudioResult.MEDIA, preparation(120_000).outcome(Player.STATE_ENDED, false, -1))
        assertEquals(AudioResult.MEDIA, preparation(120_000).outcome(Player.STATE_ENDED, false, 0))
    }

    @Test fun staleGenerationBindingAndMediaCannotCompleteTheCurrentPreparation() {
        val preparation = preparation(120_000)
        assertTrue(preparation.matches(3, binding, "track"))
        assertFalse(preparation.matches(4, binding, "track"))
        assertFalse(preparation.matches(3, binding.copy(instructionId = "replacement"), "track"))
        assertFalse(preparation.matches(3, binding, "other"))
    }

    @Test fun onlyAnErrorFreeNativeEndForThePlayOperationCanReportTraversal() {
        val preparation = preparation(120_000, play = true)
        assertTrue(preparation.reportsNativeEnd(Player.STATE_ENDED, false, 120_000))
        assertFalse(preparation.reportsNativeEnd(Player.STATE_READY, false, 120_000))
        assertFalse(preparation.reportsNativeEnd(Player.STATE_ENDED, true, 120_000))
        assertFalse(preparation(0, play = true).reportsNativeEnd(Player.STATE_ENDED, false, 120_000))
    }

    private fun preparation(positionMs: Long, play: Boolean = false) = NativePreparation(3, binding, "track", positionMs, play)
}
