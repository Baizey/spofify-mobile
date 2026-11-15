package dev.spofify.mobile.designsystem

import org.junit.Assert.*
import org.junit.Test

class SwipeGesturePolicyTest {
    @Test fun releaseThresholdHasTheSpecifiedMinimumAndMaximum() {
        assertNull(releasedSwipeDirection(160f, 55f, 0f, 55f, 1f))
        assertEquals(1, releasedSwipeDirection(160f, 56f, 0f, 56f, 1f))
        assertNull(releasedSwipeDirection(412f, 95f, 0f, 95f, 1f))
        assertEquals(-1, releasedSwipeDirection(412f, -96f, 0f, 96f, -1f))
    }

    @Test fun verticalIntentAndReversalCancelRatherThanCommitting() {
        assertTrue(cancelsHorizontalSwipe(40f, 50f, 40f, 1f))
        assertTrue(cancelsHorizontalSwipe(60f, 0f, 90f, 1f))
        assertTrue(cancelsHorizontalSwipe(-60f, 0f, 60f, 1f))
        assertNull(releasedSwipeDirection(300f, 90f, 90f, 90f, 1f))
        assertNull(releasedSwipeDirection(300f, 90f, 0f, 120f, 1f))
    }

    @Test fun aSmallFingerWobbleDoesNotCancelAnOtherwiseValidRelease() {
        assertFalse(cancelsHorizontalSwipe(100f, 12f, 110f, 1f))
        assertEquals(1, releasedSwipeDirection(360f, 100f, 12f, 110f, 1f))
    }

    @Test fun pointerDownCapturesBothCallbacksButTheNextGestureUsesTheirNewValues() {
        val ownership = SwipeGestureOwnership()
        val calls = mutableListOf<String>()
        var right: (() -> Unit)? = { calls.add("old right") }
        var left: (() -> Unit)? = { calls.add("old left") }
        val first = ownership.begin("row", right, left)
        val otherOwnership = SwipeGestureOwnership()
        val firstLeft = otherOwnership.begin("row", right, left)
        right = { calls.add("new right") }
        left = { calls.add("new left") }
        assertTrue(ownership.finish(first, "row", released = true, direction = 1))
        assertEquals(listOf("old right"), calls)
        assertTrue(otherOwnership.finish(firstLeft, "row", released = true, direction = -1))
        assertEquals(listOf("old right", "old left"), calls)
        val second = ownership.begin("row", right, left)
        assertTrue(ownership.finish(second, "row", released = true, direction = -1))
        assertEquals(listOf("old right", "old left", "new left"), calls)
        val third = ownership.begin("row", { calls.add("third right") }, { calls.add("third left") })
        assertTrue(ownership.finish(third, "row", released = true, direction = -1))
        assertEquals("third left", calls.last())
    }

    @Test fun crossingTheThresholdDoesNotDispatchUntilARealReleaseFinishes() {
        val ownership = SwipeGestureOwnership()
        var calls = 0
        val captured = ownership.begin("row", { calls++ }, null)
        val direction = releasedSwipeDirection(300f, 100f, 0f, 100f, 1f)
        assertEquals(1, direction)
        assertTrue(ownership.owns(captured, "row"))
        assertEquals(0, calls)
        assertFalse(ownership.finish(captured, "row", released = false, direction = direction))
        assertFalse(ownership.finish(captured, "row", released = true, direction = direction))
        assertEquals(0, calls)
    }

    @Test fun verticalOrReversedReleaseNeverRunsTheCapturedAction() {
        val ownership = SwipeGestureOwnership()
        var calls = 0
        val captured = ownership.begin("row", { calls++ }, { calls++ })
        val direction = releasedSwipeDirection(300f, 90f, 90f, 90f, 1f)
        assertFalse(ownership.finish(captured, "row", released = true, direction = direction))
        assertEquals(0, calls)
        assertFalse(ownership.owns(captured, "row"))
    }

    @Test fun staleSettleCannotAnimateOrCommitAfterStructuralBindingChange() {
        val ownership = SwipeGestureOwnership()
        var calls = 0
        val captured = ownership.begin("queue occurrence A", { calls++ }, null)
        assertFalse(ownership.owns(captured, "queue occurrence B"))
        assertFalse(ownership.finish(captured, "queue occurrence B", released = true, direction = 1))
        assertEquals(0, calls)
    }

    @Test fun invalidationPreventsAnOldSettleFromRevivingWhenTheBindingReturnsToTheSameValue() {
        val ownership = SwipeGestureOwnership()
        var calls = 0
        val old = ownership.begin("A", { calls++ }, null)
        ownership.invalidate() // A -> B structural change, or pointerInput disposal.
        assertFalse(ownership.owns(old, "A"))
        assertFalse(ownership.finish(old, "A", released = true, direction = 1))
        assertEquals(0, calls)
    }

    @Test fun aNewTouchIncludingAnExcludedTouchInvalidatesPendingCommit() {
        val ownership = SwipeGestureOwnership()
        var calls = 0
        val captured = ownership.begin("mini", { calls++ }, null)
        ownership.invalidate() // Happens before checking the new pointer's excluded action slot.
        assertFalse(ownership.finish(captured, "mini", released = true, direction = 1))
        assertEquals(0, calls)
    }

    @Test fun oldJobCancellationAndCompletionCannotConsumeOrResetTheNewGestureOwner() {
        val ownership = SwipeGestureOwnership()
        val calls = mutableListOf<String>()
        val old = ownership.begin("same row", { calls.add("old") }, null)
        val fresh = ownership.begin("same row", { calls.add("fresh") }, null)
        assertFalse(ownership.cancel(old))
        assertFalse(ownership.owns(old, "same row"))
        assertFalse(ownership.canResetFace(old))
        assertFalse(ownership.finish(old, "same row", released = true, direction = 1))
        assertTrue(ownership.owns(fresh, "same row"))
        assertTrue(ownership.finish(fresh, "same row", released = true, direction = 1))
        assertEquals(listOf("fresh"), calls)
        assertTrue(ownership.canResetFace(fresh))
        assertFalse(ownership.canResetFace(old))
    }

    @Test fun unsupportedCapturedDirectionCannotAcquireAnActionMidGesture() {
        val ownership = SwipeGestureOwnership()
        var calls = 0
        var left: (() -> Unit)? = null
        val captured = ownership.begin("row", { calls++ }, left)
        left = { calls++ }
        assertNotNull(left)
        assertNull(captured.action(-1))
        assertFalse(ownership.finish(captured, "row", released = true, direction = -1))
        assertEquals(0, calls)
    }

    @Test fun finishInstallsPresentationBeforeTheCallerResetsItsFace() {
        val ownership = SwipeGestureOwnership()
        var offset = 100f
        var pending = false
        val captured = ownership.begin("mini", {
            assertEquals(100f, offset, 0f)
            pending = true
        }, null)
        assertTrue(ownership.finish(captured, "mini", released = true, direction = 1))
        if (ownership.canResetFace(captured)) offset = 0f
        assertTrue(pending)
        assertEquals(0f, offset, 0f)
    }

    @Test fun aReleaseIsConsumedBeforeItsCallbackCanAttemptASecondCommit() {
        val ownership = SwipeGestureOwnership()
        var calls = 0
        lateinit var captured: CapturedSwipe
        captured = ownership.begin("row", {
            calls++
            assertFalse(ownership.finish(captured, "row", released = true, direction = 1))
        }, null)
        assertTrue(ownership.finish(captured, "row", released = true, direction = 1))
        assertEquals(1, calls)
        assertFalse(ownership.finish(captured, "row", released = true, direction = 1))
    }
}
