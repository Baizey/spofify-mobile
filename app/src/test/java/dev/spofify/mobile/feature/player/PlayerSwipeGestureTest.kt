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

    @Test fun allHorizontalAreasLeaveBothAndroidBackEdgesAlone() {
        for (area in PlayerSwipeArea.entries) {
            for (start in listOf(0f, 23f, 277f, 300f)) {
                val swipe = gesture(area, start)
                assertFalse(swipe.move(-150f, 0f))
                assertNull(swipe.finish())
            }
        }
    }

    @Test fun miniExpandsUpAndQueueOnlyRemovesLeft() {
        gesture(PlayerSwipeArea.MINI).also {
            assertTrue(it.move(2f, -80f))
            assertEquals(PlayerSwipeAction.EXPAND, it.finish())
        }
        gesture(PlayerSwipeArea.MINI).also {
            assertFalse(it.move(0f, 80f))
            assertNull(it.finish())
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

    @Test fun miniPhysicalDirectionsCommitOnceOnlyOnRelease() {
        for ((dx, expected) in listOf(-80f to PlayerSwipeAction.NEXT, 80f to PlayerSwipeAction.PREVIOUS)) {
            val swipe = gesture(PlayerSwipeArea.MINI)
            assertFalse(swipe.move(dx / 20, 0f)) // below Android slop
            assertTrue(swipe.move(dx, 2f))
            assertEquals(expected, swipe.finish())
            assertNull(swipe.finish())
            assertFalse(swipe.move(dx, 0f))
        }
    }

    @Test fun miniShortFastFlickReversalCancellationAndRejectedIntentStayCancelled() {
        gesture(PlayerSwipeArea.MINI).also {
            assertTrue(it.move(-63f, 0f)) // no velocity override
            assertNull(it.finish())
        }
        gesture(PlayerSwipeArea.MINI).also {
            it.move(-100f, 0f)
            it.move(60f, 0f)
            assertNull(it.finish())
        }
        gesture(PlayerSwipeArea.MINI).also {
            it.move(-100f, 0f)
            it.cancel()
            assertNull(it.finish())
        }
        for ((dx, dy) in listOf(-20f to 20f, 0f to 20f)) {
            gesture(PlayerSwipeArea.MINI).also {
                assertFalse(it.move(dx, dy))
                assertFalse(it.move(-150f, 0f))
                assertNull(it.finish())
            }
        }
        // An upward lock may not turn into transport, nor a horizontal lock into expansion.
        for ((first, second) in listOf((0f to -20f) to (-150f to 20f),
            (-20f to 0f) to (20f to -150f))) {
            gesture(PlayerSwipeArea.MINI).also {
                assertTrue(it.move(first.first, first.second))
                it.move(second.first, second.second)
                assertNull(it.finish())
            }
        }
        assertEquals(PlayerSwipeAction.NEXT, gesture(PlayerSwipeArea.MINI).run { move(-80f, 0f); finish() })
    }

    @Test fun miniIdentityNarrowedByTwoSiblingTargetsKeepsPhysicalDirectionsAndReleaseThreshold() {
        // A 320dp dock reserves separate 48dp Devices and Play targets. Only the
        // remaining identity owns this recognizer; sibling hit-testing is covered by
        // NativePlayerTest's actual pointer-down/crossing drags in LTR and RTL.
        val identityWidth = 320f - 48f - 48f
        for ((dx, expected) in listOf(-100f to PlayerSwipeAction.NEXT, 100f to PlayerSwipeAction.PREVIOUS)) {
            val swipe = PlayerSwipeGesture(PlayerSwipeArea.MINI, 8f, 64f,
                identityWidth / 2f, identityWidth, 24f)
            assertTrue(swipe.move(dx, 0f))
            assertEquals(expected, swipe.finish())
            assertNull("Narrow identity never duplicates a commit", swipe.finish())
        }
        val short = PlayerSwipeGesture(PlayerSwipeArea.MINI, 8f, 64f,
            identityWidth / 2f, identityWidth, 24f)
        assertTrue(short.move(-63f, 0f))
        assertNull("Reserving Devices must not lower the music-gesture threshold", short.finish())
    }

    @Test fun miniCrossingIntoSiblingRegionDoesNotReinterpretAnAlreadyLockedIdentityGesture() {
        val identityWidth = 224f
        val fromIdentity = PlayerSwipeGesture(PlayerSwipeArea.MINI, 8f, 64f,
            identityWidth - 30f, identityWidth, 24f)
        assertTrue(fromIdentity.move(90f, 0f)) // pointer is now outside identity, over sibling controls
        assertEquals(PlayerSwipeAction.PREVIOUS, fromIdentity.finish())
        assertNull(fromIdentity.finish())
        val edgeStart = PlayerSwipeGesture(PlayerSwipeArea.MINI, 8f, 64f,
            identityWidth - 2f, identityWidth, 24f)
        assertFalse(edgeStart.move(-100f, 0f))
        assertNull("An edge-start cannot become transport by crossing back into identity", edgeStart.finish())
    }

    @Test fun miniEdgeStillAllowsUpAndHonorsAsymmetricSystemInsets() {
        for (start in listOf(0f, 300f)) {
            gesture(PlayerSwipeArea.MINI, start).also {
                assertTrue(it.move(0f, -80f))
                assertEquals(PlayerSwipeAction.EXPAND, it.finish())
            }
        }
        val systemEdge = PlayerSwipeGesture(PlayerSwipeArea.MINI, 8f, 64f, 255f, 300f, 24f, 50f)
        assertFalse(systemEdge.move(-100f, 0f))
        assertNull(systemEdge.finish())
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
