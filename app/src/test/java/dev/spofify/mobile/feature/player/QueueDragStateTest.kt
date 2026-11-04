package dev.spofify.mobile.feature.player

import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.playback.PlaybackState
import dev.spofify.mobile.playback.QueueEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure presentation planning only; these simulations do NOT establish real touch/autoscroll. */
class QueueDragStateTest {
    private data class SyntheticGuard(val authority: Int, val queue: Int)
    private val duplicate = LibraryItem("track", "same-track", "Same Unicode song 日本語 🎵", hasAudio = true)
    private val entries = (0 until 60).map { QueueEntry("occurrence-$it", duplicate, 900 - it) }
    private val snapshot = QueueDragSnapshot(entries, "current", SyntheticGuard(1, 1), allowDrag = true)

    @Test fun previewMovesOneDuplicateByMeasuredBoundsThenEmitsOnlyOneChangedDropPlan() {
        val initial = layout(snapshot.order, scroll = 720f)
        val source = initial.visibleItems.single { it.key == "occurrence-5" }
        val session = begin("occurrence-5", source.midpoint, initial)
        session.movePointer(352f, snapshot)
        session.measure(initial, snapshot)
        val target = session.targetIndex!!
        assertTrue("Crosses at least one measured neighbor", target > 5)
        assertEquals(snapshot.order.toMutableList().apply { add(target, removeAt(5)) }, session.previewOrder)
        assertEquals(entries, snapshot.upcoming)
        assertEquals(source.height, session.sourceHeight, .001f)
        assertEquals(352f - source.height / 2f, session.liftedTop!!, .001f)
        val plan = session.drop(snapshot)
        assertEquals("occurrence-5", plan?.occurrenceId)
        assertEquals(target, plan?.toUpcomingIndex)
        assertEquals(snapshot, plan?.expected)
        assertNull("Cannot emit another plan on a repeated drop", session.drop(snapshot))
        assertFalse(session.isActive)
        assertTrue(session.previewOrder.isEmpty())
        assertNull(session.pointerY)
        assertNull(session.liftedTop)
    }

    @Test fun unchangedCancelledAndChangedThenReturnedDropsAreNoOps() {
        val initial = layout(snapshot.order, scroll = 720f)
        val source = initial.visibleItems.single { it.key == "occurrence-5" }
        assertNull(begin("occurrence-5", source.midpoint, initial).drop(snapshot))
        val cancelled = begin("occurrence-5", source.midpoint, initial)
        cancelled.movePointer(352f, snapshot)
        cancelled.measure(initial, snapshot)
        cancelled.cancel()
        cancelled.cancel()
        assertNull(cancelled.drop(snapshot))
        assertTrue(cancelled.previewOrder.isEmpty())

        val returned = begin("occurrence-5", source.midpoint, initial)
        returned.movePointer(352f, snapshot)
        returned.measure(initial, snapshot)
        assertTrue(returned.targetIndex!! > 5)
        // Return above the moved-over neighbors, using the newly measured preview order.
        returned.movePointer(10f, snapshot)
        returned.measure(layout(returned.previewOrder, scroll = 720f), snapshot)
        // Aim at the original slot using measured centers rather than a guessed row pitch.
        if (returned.targetIndex != 5) {
            val originalSlot = layout(returned.previewOrder, scroll = 720f).visibleItems
                .single { it.key == "occurrence-4" }
            returned.movePointer(originalSlot.offset + originalSlot.height + returned.sourceHeight / 2f, snapshot)
            returned.measure(layout(returned.previewOrder, scroll = 720f), snapshot)
        }
        assertEquals(5, returned.targetIndex)
        assertNull(returned.drop(snapshot))
    }

    @Test fun staleReflowMeasurementIsIgnoredAndSourceVirtualizationDoesNotCancel() {
        val initial = layout(snapshot.order, scroll = 720f)
        val source = initial.visibleItems.single { it.key == "occurrence-5" }
        val session = begin("occurrence-5", source.midpoint, initial)
        session.movePointer(352f, snapshot)
        session.measure(initial, snapshot)
        val changed = session.previewOrder
        session.movePointer(0f, snapshot)
        session.measure(initial, snapshot)
        assertEquals("Old order's geometry cannot be applied again after reflow", changed, session.previewOrder)
        val remeasured = layout(changed, scroll = 720f).let { frame ->
            frame.copy(visibleItems = frame.visibleItems.filterNot { it.key == session.occurrenceId })
        }
        session.measure(remeasured, snapshot)
        assertTrue(session.isActive)
        assertTrue(session.targetIndex!! < changed.indexOf(session.occurrenceId))
    }

    @Test fun heldEdgePureFramesTravelBeyondInitialViewportInBothDirectionsWithoutSourceMeasurements() {
        val initial = layout(snapshot.order, scroll = 720f)
        val source = initial.visibleItems.single { it.key == "occurrence-5" }
        val session = begin("occurrence-5", source.midpoint, initial)
        var scroll = 720f
        var timestamp = 0L
        val totalHeight = 200f + entries.sumOf { height(it.occurrenceId).toDouble() }.toFloat()
        val maxScroll = totalHeight - 360f
        var sourceMissingFrames = 0
        var maxTarget = 5
        var zeroBoundaryFrames = 0
        for (heldPointer in listOf(358f, 2f)) {
            // Exactly one pointer movement per entire 900-frame hold.
            session.movePointer(heldPointer, snapshot)
            repeat(900) {
                val velocity = queueDragEdgeVelocity(heldPointer, 0f, 360f, 48f, 720f,
                    canScrollBackward = scroll > 0f, canScrollForward = scroll < maxScroll)
                val delta = queueDragFrameScroll(velocity, timestamp, timestamp + 16_000_000L)
                timestamp += 16_000_000L
                scroll = (scroll + delta).coerceIn(0f, maxScroll)
                if (delta == 0f) zeroBoundaryFrames++
                val frame = layout(session.previewOrder, scroll).let { measured ->
                    // Simulate row virtualization. Stable queue-owned session retains just one
                    // measured lifted source size, not a retained source composition/bitmap.
                    measured.copy(visibleItems = measured.visibleItems.filterNot { it.key == session.occurrenceId })
                }
                if (frame.visibleItems.none { it.key == session.occurrenceId }) sourceMissingFrames++
                session.measure(frame, snapshot)
                assertTrue(session.isActive)
                maxTarget = maxOf(maxTarget, session.targetIndex!!)
            }
        }
        assertTrue("Beyond initially visible occurrences", maxTarget > 40)
        assertTrue("No source row needed across either hold", sourceMissingFrames > 1_000)
        assertTrue("No further scroll requests at terminal boundaries", zeroBoundaryFrames > 100)
        assertEquals(0f, scroll, .001f)
        assertEquals(0, session.targetIndex)
        assertEquals(snapshot.order.toMutableList().apply { add(0, removeAt(5)) }, session.previewOrder)
        assertEquals(0, session.drop(snapshot)?.toUpcomingIndex)
        assertNull(session.drop(snapshot))
    }

    @Test fun authoritativeContentOrderCurrentGuardOrCapabilityChangeDeterministicallyCancels() {
        val changes = listOf(
            snapshot.copy(upcoming = entries.drop(1)),
            snapshot.copy(upcoming = entries + QueueEntry("added", duplicate, 1_000)),
            snapshot.copy(upcoming = entries.reversed()),
            snapshot.copy(upcoming = entries.mapIndexed { i, entry -> if (i == 5) entry.copy(item = duplicate.copy(name = "Updated")) else entry }),
            snapshot.copy(currentOccurrenceId = "traversed"),
            snapshot.copy(guard = SyntheticGuard(2, 1)), // Authority changes, identical visible queue.
            snapshot.copy(guard = SyntheticGuard(1, 2)),
            snapshot.copy(allowDrag = false),
        )
        for (changed in changes) {
            val initial = layout(snapshot.order, scroll = 720f)
            val source = initial.visibleItems.single { it.key == "occurrence-5" }
            val session = begin("occurrence-5", source.midpoint, initial)
            session.movePointer(352f, snapshot)
            session.measure(initial, snapshot)
            assertFalse(session.validate(changed))
            assertNull(session.drop(changed))
            assertNull(session.pointerY)
            assertNull(session.liftedTop)
            assertTrue(session.previewOrder.isEmpty())
        }
    }

    @Test fun progressOnlyProjectionDoesNotCancelRestartOrMovePointer() {
        val state = PlaybackState(item = duplicate, currentEntryId = "current", upcoming = entries)
        fun project(value: PlaybackState) = QueueDragSnapshot(value.upcoming, value.currentEntryId, snapshot.guard, allowDrag = true)
        val initial = layout(snapshot.order, scroll = 720f)
        val source = initial.visibleItems.single { it.key == "occurrence-5" }
        val session = QueueDragSession.begin(project(state), "occurrence-5", source.midpoint, initial)!!
        session.movePointer(352f, project(state))
        session.measure(initial, project(state))
        val before = session.previewOrder
        repeat(100) { frame ->
            assertTrue(session.validate(project(state.copy(positionMs = frame * 1_000L, durationMs = 600_000,
                isBuffering = frame % 2 == 0, isPlaying = frame % 2 != 0))))
            assertEquals(before, session.previewOrder)
            assertEquals(352f, session.pointerY!!, .001f)
        }
        assertNotNull(session.drop(project(state)))
    }

    @Test fun rejectsRemoteMissingCurrentDuplicateKeysStaleStartAndInvalidPointerWithoutPlan() {
        val initial = layout(snapshot.order, scroll = 720f)
        val source = initial.visibleItems.single { it.key == "occurrence-5" }
        assertNull(QueueDragSession.begin(snapshot.copy(allowDrag = false), "occurrence-5", source.midpoint, initial))
        assertNull(QueueDragSession.begin(snapshot.copy(currentOccurrenceId = null), "occurrence-5", source.midpoint, initial))
        assertNull(QueueDragSession.begin(snapshot.copy(upcoming = entries + entries[5]), "occurrence-5", source.midpoint, initial))
        assertNull(QueueDragSession.begin(snapshot, "occurrence-5", source.midpoint, initial.copy(previewOrder = snapshot.order.reversed())))
        assertNull(QueueDragSession.begin(snapshot, "occurrence-5", Float.NaN, initial))
        assertNull(QueueDragSession.begin(snapshot, "missing", source.midpoint, initial))
        val session = begin("occurrence-5", source.midpoint, initial)
        session.movePointer(Float.POSITIVE_INFINITY, snapshot)
        assertFalse(session.isActive)
        assertNull(session.drop(snapshot))
    }

    @Test fun activationDefensivelyCopiesMutableOccurrenceListAndBoundsResources() {
        val mutable = entries.toMutableList()
        val current = snapshot.copy(upcoming = mutable)
        val initial = layout(snapshot.order, scroll = 720f)
        val source = initial.visibleItems.single { it.key == "occurrence-5" }
        val session = QueueDragSession.begin(current, "occurrence-5", source.midpoint, initial)!!
        mutable.removeAt(0)
        assertFalse("Mutation cannot rewrite the captured baseline", session.validate(current))
        val tooMany = snapshot.copy(upcoming = (0..1_000).map { QueueEntry("large-$it", duplicate, it) })
        assertNull(QueueDragSession.begin(tooMany, "large-5", source.midpoint, initial))
    }

    private fun begin(occurrence: String, pointer: Float, initial: QueueDragLayout) =
        QueueDragSession.begin(snapshot, occurrence, pointer, initial)!!

    private fun height(id: String): Float = listOf(64f, 136f, 88f, 192f)[id.substringAfterLast('-').toInt() % 4]

    private fun layout(order: List<String>, scroll: Float): QueueDragLayout {
        var offset = 200f - scroll // Full parent includes core/header; never subtracted as an index.
        val items = mutableListOf(QueueDragItemBounds(HeaderKey, -scroll, 200f))
        for (id in order) {
            val size = height(id)
            if (offset < 360f && offset + size > 0f) items += QueueDragItemBounds(id, offset, size)
            offset += size
        }
        return QueueDragLayout(order.toList(), 0f, 360f, items)
    }

    private object HeaderKey
}
