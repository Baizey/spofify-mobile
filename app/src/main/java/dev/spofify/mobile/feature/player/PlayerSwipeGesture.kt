package dev.spofify.mobile.feature.player

import java.util.Locale
import kotlin.math.abs

internal enum class PlayerSwipeArea { MINI, COVER, UPCOMING }
internal enum class PlayerSwipeAction { EXPAND, NEXT, PREVIOUS, REMOVE }

/**
 * A single pointer's directional intent. Distances are pixels supplied by the UI. Lock the axis
 * only after touch slop, reject diagonals/edge starts, and commit at most once, on release.
 * No playback mutation is performed while the pointer is moving.
 */
internal class PlayerSwipeGesture(
    private val area: PlayerSwipeArea,
    private val touchSlop: Float,
    private val threshold: Float,
    startX: Float,
    width: Float,
    edgeInset: Float,
    endEdgeInset: Float = edgeInset,
) {
    private val edgeStart = startX < edgeInset || startX > width - endEdgeInset ||
        width <= edgeInset + endEdgeInset
    private var rejected = area != PlayerSwipeArea.MINI && edgeStart
    private var locked = false
    private var upward = false
    private var ended = false
    private var x = 0f
    private var y = 0f

    /** True only when this gesture owns the pointer; vertical list scrolling stays unconsumed. */
    fun move(dx: Float, dy: Float): Boolean {
        if (ended || rejected) return false
        x += dx
        y += dy
        if (!locked && maxOf(abs(x), abs(y)) > touchSlop) {
            locked = when (area) {
                PlayerSwipeArea.MINI -> when {
                    -y > abs(x) * DIRECTION_RATIO -> { upward = true; true }
                    abs(x) > abs(y) * DIRECTION_RATIO -> !edgeStart
                    else -> false
                }
                PlayerSwipeArea.COVER -> abs(x) > abs(y) * DIRECTION_RATIO
                PlayerSwipeArea.UPCOMING -> -x > abs(y) * DIRECTION_RATIO
            }
            if (!locked) rejected = true
        }
        return locked
    }

    fun finish(): PlayerSwipeAction? {
        if (ended) return null
        ended = true
        if (rejected || !locked) return null
        return when (area) {
            PlayerSwipeArea.MINI -> if (upward) {
                if (-y >= threshold && -y > abs(x) * DIRECTION_RATIO) PlayerSwipeAction.EXPAND else null
            } else {
                if (abs(x) >= threshold && abs(x) > abs(y) * DIRECTION_RATIO)
                    if (x < 0) PlayerSwipeAction.NEXT else PlayerSwipeAction.PREVIOUS
                else null
            }
            PlayerSwipeArea.COVER -> if (abs(x) >= threshold && abs(x) > abs(y) * DIRECTION_RATIO)
                if (x < 0) PlayerSwipeAction.NEXT else PlayerSwipeAction.PREVIOUS
            else null
            PlayerSwipeArea.UPCOMING -> if (-x >= threshold && -x > abs(y) * DIRECTION_RATIO)
                PlayerSwipeAction.REMOVE else null
        }
    }

    fun cancel() { ended = true }

    private companion object { const val DIRECTION_RATIO = 1.4f }
}

internal fun queueMoveTarget(index: Int, deltaY: Float, rowHeight: Int, count: Int): Int {
    if (count <= 0 || rowHeight <= 0) return index
    return (index + kotlin.math.round(deltaY / rowHeight).toInt()).coerceIn(0, count - 1)
}

internal fun playerTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
