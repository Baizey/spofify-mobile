package dev.spofify.mobile.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSwipeGestureTest {
    private fun gesture(area: PlayerSwipeArea, startX: Float = 150f) =
        PlayerSwipeGesture(area, touchSlop = 8f, threshold = 64f,
            startX = startX, width = 300f, edgeInset = 24f)

    @Test fun coverLeftIsNextAndRightIsPreviousWithExactlyOneCommit() {
        val left = gesture(PlayerSwipeArea.COVER)
        assertTrue(left.move(-80f, 4f))
        assertEquals(PlayerSwipeAction.NEXT, left.finish())
        assertNull(left.finish())
        assertFalse(left.move(-100f, 0f))
        val right = gesture(PlayerSwipeArea.COVER)
        right.move(80f, -4f)
        assertEquals(PlayerSwipeAction.PREVIOUS, right.finish())
    }

    @Test fun tapsShortDragsDiagonalsAndVerticalScrollingDoNotChangeSongs() {
        assertNull(gesture(PlayerSwipeArea.COVER).finish())
        gesture(PlayerSwipeArea.COVER).also {
            it.move(-40f, 0f)
            assertNull(it.finish())
        }
        gesture(PlayerSwipeArea.COVER).also {
            assertFalse(it.move(-40f, 40f))
            it.move(-100f, 0f)
            assertNull(it.finish())
        }
        gesture(PlayerSwipeArea.COVER).also {
            assertFalse(it.move(0f, -20f))
            it.move(-100f, 0f)
            assertNull(it.finish())
        }
    }

    @Test fun coverAndQueueLeaveBothAndroidBackEdgesAlone() {
        for (area in listOf(PlayerSwipeArea.COVER, PlayerSwipeArea.UPCOMING)) {
            for (start in listOf(0f, 23f, 277f, 300f)) {
                val swipe = gesture(area, start)
                assertFalse(swipe.move(-150f, 0f))
                assertNull(swipe.finish())
            }
        }
    }

    @Test fun miniOnlyExpandsUpAndQueueOnlyRemovesLeft() {
        gesture(PlayerSwipeArea.MINI).also {
            assertTrue(it.move(2f, -80f))
            assertEquals(PlayerSwipeAction.EXPAND, it.finish())
        }
        for (delta in listOf(80f to 0f, 0f to 80f)) {
            gesture(PlayerSwipeArea.MINI).also {
                assertFalse(it.move(delta.first, delta.second))
                assertNull(it.finish())
            }
        }
        gesture(PlayerSwipeArea.UPCOMING).also {
            it.move(-80f, 1f)
            assertEquals(PlayerSwipeAction.REMOVE, it.finish())
        }
        gesture(PlayerSwipeArea.UPCOMING).also {
            assertFalse(it.move(80f, 0f))
            assertNull(it.finish())
        }
    }

    @Test fun cancelledAndDirectionChangedGesturesCannotCommit() {
        gesture(PlayerSwipeArea.COVER).also {
            it.move(-80f, 0f)
            it.cancel()
            assertNull(it.finish())
        }
        gesture(PlayerSwipeArea.COVER).also {
            it.move(-80f, 0f)
            it.move(0f, 100f)
            assertNull(it.finish())
        }
    }

    @Test fun dragReorderUsesUpcomingPositionsAndClampsToBounds() {
        assertEquals(3, queueMoveTarget(1, 160f, 80, 5))
        assertEquals(0, queueMoveTarget(1, -500f, 80, 5))
        assertEquals(4, queueMoveTarget(1, 500f, 80, 5))
        assertEquals(1, queueMoveTarget(1, 20f, 80, 5))
        assertEquals(1, queueMoveTarget(1, 160f, 0, 5))
    }

    @Test fun timestampsAreNonnegativeAndDoNotWrapAtAnHour() {
        assertEquals("0:00", playerTime(-1000))
        assertEquals("1:05", playerTime(65_999))
        assertEquals("61:01", playerTime(3_661_000))
    }
}
