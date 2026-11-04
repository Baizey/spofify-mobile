package dev.spofify.mobile.feature.player

import kotlin.math.max
import kotlin.math.min

/** Actual parent-list coordinates, in pixels. Non-occurrence keys may be player/header items. */
internal data class QueueDragItemBounds(val key: Any, val offset: Float, val height: Float) {
    val midpoint: Float get() = offset + height / 2f
    val isMeasured: Boolean get() = offset.isFinite() && height.isFinite() && height > 0f &&
        midpoint.isFinite() && (offset + height).isFinite()
}

/**
 * A measurement belongs to one exact local preview order. Discard it after preview reflow until
 * the parent has measured that order; applying an old frame twice can move the wrong insertion.
 * Keys, not a hard-coded count of preceding player/header indices, identify upcoming occurrences.
 */
internal data class QueueDragLayout(
    val previewOrder: List<String>,
    val viewportStart: Float,
    val viewportEnd: Float,
    val visibleItems: List<QueueDragItemBounds>,
) {
    val hasViewport: Boolean get() = viewportStart.isFinite() && viewportEnd.isFinite() && viewportEnd > viewportStart
}

/** Pure measured-midpoint insertion; headers, missing/unmeasured rows and the source are ignored. */
internal fun queueDragInsertionIndex(
    order: List<String>,
    draggedOccurrence: String,
    liftedMidpoint: Float,
    visibleItems: List<QueueDragItemBounds>,
): Int? {
    val from = order.indexOf(draggedOccurrence)
    if (from < 0 || !liftedMidpoint.isFinite()) return null
    val indices = order.withIndex().associate { it.value to it.index }
    var target = from
    for (item in visibleItems) {
        if (!item.isMeasured || item.key == draggedOccurrence) continue
        val occurrence = item.key as? String ?: continue
        val index = indices[occurrence] ?: continue
        // Equality is deliberately unchanged: a pointer resting exactly on a boundary is stable.
        if (index < from && liftedMidpoint < item.midpoint) target = min(target, index)
        if (index > from && liftedMidpoint > item.midpoint) target = max(target, index)
    }
    return target
}

/**
 * Signed px/s. The pointer can remain motionless: each frame uses the same edge velocity. The
 * zones shrink rather than overlap in an extreme short viewport. Native scroll capability stops
 * requests at terminal boundaries; a later pointer/layout/capability change can resume them.
 */
internal fun queueDragEdgeVelocity(
    pointerY: Float,
    viewportStart: Float,
    viewportEnd: Float,
    edgeSizePx: Float,
    maxSpeedPxPerSecond: Float,
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
): Float {
    if (!pointerY.isFinite() || !viewportStart.isFinite() || !viewportEnd.isFinite() ||
        !edgeSizePx.isFinite() || !maxSpeedPxPerSecond.isFinite() || edgeSizePx <= 0f ||
        maxSpeedPxPerSecond <= 0f || viewportEnd <= viewportStart) return 0f
    val band = min(edgeSizePx, (viewportEnd - viewportStart) / 2f)
    val topDepth = ((viewportStart + band - pointerY) / band).coerceIn(0f, 1f)
    val bottomDepth = ((pointerY - (viewportEnd - band)) / band).coerceIn(0f, 1f)
    return when {
        topDepth > 0f && canScrollBackward -> -maxSpeedPxPerSecond * topDepth
        bottomDepth > 0f && canScrollForward -> maxSpeedPxPerSecond * bottomDepth
        else -> 0f
    }
}

/**
 * One frame's bounded scroll delta, NOT a coroutine or detached ticker. The eventual stable parent
 * owner must await the frame clock, remeasure after scroll and cancel its job on drop/disposal.
 * First/reversed/invalid timestamps produce no delta; long pauses never become huge scroll jumps.
 */
internal fun queueDragFrameScroll(velocityPxPerSecond: Float, previousFrameNanos: Long?, frameNanos: Long): Float {
    if (!velocityPxPerSecond.isFinite() || previousFrameNanos == null ||
        previousFrameNanos < 0L || frameNanos <= previousFrameNanos) return 0f
    val elapsedNanos = (frameNanos - previousFrameNanos).coerceAtMost(32_000_000L)
    return velocityPxPerSecond * (elapsedNanos / 1_000_000_000f)
}
