package dev.spofify.mobile.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueDragGeometryTest {
    private val order = listOf("first", "dragged", "third", "fourth")
    private val measured = listOf(
        QueueDragItemBounds(HeaderKey, -96f, 96f),
        QueueDragItemBounds("first", 0f, 160f),
        QueueDragItemBounds("dragged", 160f, 80f),
        QueueDragItemBounds("third", 240f, 240f),
        QueueDragItemBounds("fourth", 480f, 48f),
    )

    @Test fun measuredMidpointsHandleVariableFontTwoHeightsWithoutHeaderIndexArithmetic() {
        assertEquals(1, queueDragInsertionIndex(order, "dragged", 200f, measured))
        assertEquals(0, queueDragInsertionIndex(order, "dragged", 79f, measured))
        assertEquals(1, queueDragInsertionIndex(order, "dragged", 80f, measured))
        assertEquals(1, queueDragInsertionIndex(order, "dragged", 360f, measured))
        assertEquals(2, queueDragInsertionIndex(order, "dragged", 361f, measured))
        assertEquals(3, queueDragInsertionIndex(order, "dragged", 505f, measured))
        // Bounds, not declaration order, queue timeline indices or fixed initial pitch, decide it.
        assertEquals(3, queueDragInsertionIndex(order, "dragged", 505f, measured.reversed()))
    }

    @Test fun unknownHeaderKeysMissingSourceAndInvalidBoundsDoNotBecomeOccurrences() {
        val sourceVirtualized = measured.filterNot { it.key == "dragged" } + listOf(
            QueueDragItemBounds("not-in-queue", 600f, 80f),
            QueueDragItemBounds("third", Float.NaN, 120f),
            QueueDragItemBounds("fourth", 100f, 0f),
        )
        assertEquals(3, queueDragInsertionIndex(order, "dragged", 505f, sourceVirtualized))
        assertEquals(1, queueDragInsertionIndex(order, "dragged", 300f, emptyList()))
        assertNull(queueDragInsertionIndex(order, "gone", 505f, measured))
        assertNull(queueDragInsertionIndex(order, "dragged", Float.NaN, measured))
    }

    @Test fun heldEdgesRampBothDirectionsClampOutsidePointerAndStopAtBoundaries() {
        fun velocity(y: Float, backward: Boolean = true, forward: Boolean = true) = queueDragEdgeVelocity(
            y, 100f, 500f, 48f, 720f, backward, forward,
        )
        assertEquals(-720f, velocity(100f), .001f)
        assertEquals(-360f, velocity(124f), .001f)
        assertEquals(0f, velocity(148f), .001f)
        assertEquals(0f, velocity(300f), .001f)
        assertEquals(360f, velocity(476f), .001f)
        assertEquals(720f, velocity(500f), .001f)
        assertEquals(-720f, velocity(-100f), .001f)
        assertEquals(720f, velocity(900f), .001f)
        assertEquals(0f, velocity(100f, backward = false), .001f)
        assertEquals(0f, velocity(500f, forward = false), .001f)
    }

    @Test fun shortViewportZonesNeverOverlapAndInvalidGeometryCannotScroll() {
        fun velocity(y: Float, start: Float = 20f, end: Float = 60f, edge: Float = 48f) = queueDragEdgeVelocity(
            y, start, end, edge, 720f, true, true,
        )
        assertTrue(velocity(39f) < 0f)
        assertEquals(0f, velocity(40f), .001f)
        assertTrue(velocity(41f) > 0f)
        assertEquals(0f, velocity(Float.NaN), .001f)
        assertEquals(0f, velocity(30f, end = 20f), .001f)
        assertEquals(0f, velocity(30f, start = Float.NEGATIVE_INFINITY), .001f)
        assertEquals(0f, velocity(30f, edge = 0f), .001f)
    }

    @Test fun unchangedHeldPointerProducesContinuousFrameDeltasWithBoundedCatchup() {
        val velocity = queueDragEdgeVelocity(492f, 100f, 500f, 48f, 720f, true, true)
        val deltas = (1..300).map { frame ->
            queueDragFrameScroll(velocity, (frame - 1) * 16_000_000L, frame * 16_000_000L)
        }
        assertTrue(deltas.all { it > 0f && it <= 720f * .032f })
        assertTrue("Held-edge pure calculation travels beyond initial 400px viewport", deltas.sum() > 2_000f)
        assertEquals(23.04f, queueDragFrameScroll(720f, 1L, 10_000_000_000L), .001f)
        assertEquals(-23.04f, queueDragFrameScroll(-720f, 1L, 10_000_000_000L), .001f)
        assertEquals(0f, queueDragFrameScroll(720f, null, 10L), .001f)
        assertEquals(0f, queueDragFrameScroll(720f, 10L, 10L), .001f)
        assertEquals(0f, queueDragFrameScroll(720f, 10L, 9L), .001f)
        assertEquals(0f, queueDragFrameScroll(Float.NaN, 0L, 16_000_000L), .001f)
    }

    private object HeaderKey
}
