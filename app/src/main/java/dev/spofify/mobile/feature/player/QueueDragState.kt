package dev.spofify.mobile.feature.player

import dev.spofify.mobile.playback.QueueEntry

/**
 * Projection of authoritative state, intentionally EXCLUDING elapsed time/buffering/artwork loads.
 * [guard] must be the playback owner's immutable transient authority/queue guard, not a screen's
 * guessed revision. [allowDrag] is false for remote directional-only queues or unavailable guards.
 * This feature-owned projection grants no playback authority and cannot execute a mutation.
 */
internal data class QueueDragSnapshot<Guard : Any>(
    val upcoming: List<QueueEntry>,
    val currentOccurrenceId: String?,
    val guard: Guard,
    val allowDrag: Boolean,
) {
    val order: List<String> get() = upcoming.map { it.occurrenceId }
}

/**
 * A single changed-drop PLAN, never an applied/acknowledged mutation. The playback boundary must
 * validate [expected] at execution as well as the UI's drop-time check. Do not fall back to the
 * unguarded absolute move API if conditional execution is unavailable, or replay a rejected plan.
 */
internal data class QueueDragDrop<Guard : Any>(
    val occurrenceId: String,
    val toUpcomingIndex: Int,
    val expected: QueueDragSnapshot<Guard>,
)

/**
 * Pure, bounded local-preview state. A stable QUEUE/PARENT owner holds this session; disposing a
 * virtualized source row must not dispose it. No coroutine, UI node, bitmap, playback control or
 * mutation lives here. Terminal drop/cancel clears geometry/order; the caller releases the session.
 */
internal class QueueDragSession<Guard : Any> private constructor(
    private val captured: QueueDragSnapshot<Guard>,
    val occurrenceId: String,
    val sourceHeight: Float,
    private val grabOffset: Float,
    pointerY: Float,
) {
    private val originalIndex = captured.upcoming.indexOfFirst { it.occurrenceId == occurrenceId }
    private var preview = captured.order
    private var pointer: Float? = pointerY

    var isActive: Boolean = true
        private set
    val pointerY: Float? get() = pointer
    val liftedTop: Float? get() = pointer?.minus(grabOffset)
    val previewOrder: List<String> get() = preview.toList()
    val targetIndex: Int? get() = if (isActive) preview.indexOf(occurrenceId) else null

    /** Authoritative change cancels instead of reconciling/replaying a stale local preview. */
    fun validate(current: QueueDragSnapshot<Guard>): Boolean {
        if (!isActive) return false
        if (current != captured) {
            cancel()
            return false
        }
        return true
    }

    fun movePointer(y: Float, current: QueueDragSnapshot<Guard>) {
        if (!validate(current)) return
        if (!y.isFinite()) {
            cancel()
            return
        }
        pointer = y
    }

    /**
     * Call after pointer movement AND after parent scroll/measurement, even with a held pointer.
     * Stale reflow frames are ignored. Measured neighbors can be any height, and the source need
     * not remain visible/composed. Insertion remains occurrence-keyed across parent headers.
     */
    fun measure(layout: QueueDragLayout, current: QueueDragSnapshot<Guard>) {
        if (!validate(current) || !layout.hasViewport || layout.previewOrder != preview) return
        val midpoint = (liftedTop ?: return) + sourceHeight / 2f
        val from = preview.indexOf(occurrenceId)
        val to = queueDragInsertionIndex(preview, occurrenceId, midpoint, layout.visibleItems) ?: return
        if (from != to) preview = preview.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** Exactly one plan for a valid changed drop. Repeated/unchanged/cancelled drops are no-ops. */
    fun drop(current: QueueDragSnapshot<Guard>): QueueDragDrop<Guard>? {
        if (!validate(current)) return null
        val to = targetIndex ?: return null
        val plan = if (to == originalIndex) null else QueueDragDrop(occurrenceId, to, captured)
        cancel()
        return plan
    }

    fun cancel() {
        isActive = false
        pointer = null
        preview = emptyList()
    }

    companion object {
        fun <Guard : Any> begin(
            current: QueueDragSnapshot<Guard>,
            occurrenceId: String,
            pointerY: Float,
            layout: QueueDragLayout,
        ): QueueDragSession<Guard>? {
            if (!current.allowDrag || current.currentOccurrenceId == null ||
                current.upcoming.isEmpty() ||
                !layout.hasViewport || !pointerY.isFinite() || pointerY !in layout.viewportStart..layout.viewportEnd) return null
            val captured = current.copy(upcoming = current.upcoming.toList())
            val order = captured.order
            if (order.toSet().size != order.size || occurrenceId !in order ||
                current.currentOccurrenceId in order || layout.previewOrder != order) return null
            val source = layout.visibleItems.singleOrNull { it.key == occurrenceId } ?: return null
            if (!source.isMeasured || pointerY !in source.offset..(source.offset + source.height)) return null
            return QueueDragSession(captured, occurrenceId, source.height, pointerY - source.offset, pointerY)
        }
    }
}
