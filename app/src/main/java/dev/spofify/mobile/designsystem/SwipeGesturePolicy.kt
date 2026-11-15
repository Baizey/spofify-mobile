package dev.spofify.mobile.designsystem

import kotlin.math.abs
import kotlin.math.max

/** Immutable pointer-down selection. Callback replacement must not retarget an in-flight swipe. */
internal class CapturedSwipe(
    val binding: Any,
    val onRight: (() -> Unit)?,
    val onLeft: (() -> Unit)?,
) {
    fun action(direction: Int?): (() -> Unit)? = when (direction) {
        1 -> onRight
        -1 -> onLeft
        else -> null
    }
}

/** Identity, not binding equality alone, owns both animation frames and the one release commit. */
internal class SwipeGestureOwnership {
    private var active: CapturedSwipe? = null
    private var latest: CapturedSwipe? = null

    fun begin(binding: Any, onRight: (() -> Unit)?, onLeft: (() -> Unit)?): CapturedSwipe =
        CapturedSwipe(binding, onRight, onLeft).also { active = it; latest = it }

    fun owns(captured: CapturedSwipe, binding: Any): Boolean = active === captured && captured.binding == binding

    fun cancel(captured: CapturedSwipe): Boolean {
        if (active !== captured) return false
        active = null
        return true
    }

    fun canResetFace(captured: CapturedSwipe): Boolean = latest === captured

    fun invalidate() { active = null; latest = null }

    fun finish(captured: CapturedSwipe, binding: Any, released: Boolean, direction: Int?): Boolean {
        val valid = owns(captured, binding)
        // Consume before invoking a callback, including re-entrant attempts to commit again.
        if (!cancel(captured) || !valid || !released) return false
        val action = captured.action(direction) ?: return false
        action()
        return true
    }
}

/** Distances are logical dp, matching the agreed pointer thresholds. */
internal fun cancelsHorizontalSwipe(dx: Float, dy: Float, peak: Float, initialSign: Float): Boolean =
    abs(dy) > max(24f, abs(dx) * .8f) ||
        (peak > 28f && (dx * initialSign < 0 || abs(dx) < peak - 28f))

internal fun releasedSwipeDirection(width: Float, dx: Float, dy: Float, peak: Float, initialSign: Float): Int? {
    val threshold = (width * .28f).coerceIn(56f, 96f)
    if (cancelsHorizontalSwipe(dx, dy, peak, initialSign) || abs(dx) < threshold ||
        dx * initialSign <= 0 || peak - abs(dx) > 28f) return null
    return if (dx > 0) 1 else -1
}
