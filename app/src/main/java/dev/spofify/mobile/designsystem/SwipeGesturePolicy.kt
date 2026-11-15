package dev.spofify.mobile.designsystem

import kotlin.math.abs
import kotlin.math.max

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
