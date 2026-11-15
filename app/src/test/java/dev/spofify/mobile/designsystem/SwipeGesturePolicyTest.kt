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
}
