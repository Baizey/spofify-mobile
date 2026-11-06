package dev.spofify.mobile.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextualRowGestureTest {
    private fun gesture(x: Float = 80f) = ContextualRowGesture(8f, 72f, x, 320f, 24f)

    @Test fun queuedRowsAcceptBothPhysicalDirectionsButCancelVerticalReversalAndMissingRelease() {
        for (sign in listOf(-1f, 1f)) {
            fun queued() = ContextualRowGesture(8f, 72f, 160f, 320f, 24f, bidirectional = true)
            val valid = queued()
            assertTrue(valid.move(sign * 80f, 0f))
            assertTrue(valid.release(true))
            assertFalse(valid.release(true))
            val reversed = queued()
            reversed.move(sign * 80f, 0f); reversed.move(-sign, 0f)
            assertFalse(reversed.release(true))
            val vertical = queued()
            assertFalse(vertical.move(0f, 20f))
            vertical.move(sign * 100f, 0f)
            assertFalse(vertical.release(true))
            val lost = queued(); lost.move(sign * 100f, 0f)
            assertFalse(lost.release(false))
            val cancelled = queued(); cancelled.move(sign * 100f, 0f); cancelled.cancel()
            assertFalse(cancelled.release(true))
        }
    }

    @Test fun commitsOnlyOnObservedReleaseAndOnlyOnce() {
        val recognizer = gesture()
        assertTrue(recognizer.move(80f, 0f))
        assertTrue(recognizer.release(true))
        assertFalse(recognizer.release(true))
        assertFalse(recognizer.move(100f, 0f))
        val missingRelease = gesture()
        missingRelease.move(100f, 0f)
        assertFalse(missingRelease.release(false))
    }

    @Test fun thresholdPhysicalDirectionAndEdgesAreStrict() {
        val short = gesture(); short.move(71f, 0f); assertFalse(short.release(true))
        val threshold = gesture(); threshold.move(72f, 0f); assertTrue(threshold.release(true))
        val left = gesture(); left.move(-100f, 0f); assertFalse(left.release(true))
        for (edge in listOf(0f, 23f, 297f, 320f)) {
            val excluded = gesture(edge); excluded.move(100f, 0f); assertFalse(excluded.release(true))
        }
    }

    @Test fun verticalCancellationAndReversalCannotBeRevived() {
        val vertical = gesture(); assertFalse(vertical.move(2f, 20f))
        vertical.move(100f, 0f); assertFalse(vertical.release(true))
        val reversed = gesture(); assertTrue(reversed.move(100f, 0f))
        assertFalse(reversed.move(-1f, 0f)); reversed.move(100f, 0f)
        assertFalse(reversed.release(true))
        val cancelled = gesture(); cancelled.move(100f, 0f); cancelled.cancel()
        assertFalse(cancelled.release(true))
    }
}
