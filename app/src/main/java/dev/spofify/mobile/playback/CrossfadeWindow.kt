package dev.spofify.mobile.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Native media time (not wall time): a short song never spends more than half its duration fading. */
internal object CrossfadeWindow {
    const val PREPARE_MS = 9_000L
    fun length(outMs: Long, inMs: Long): Long = minOf(5_000L, outMs / 2, inMs / 2).coerceAtLeast(0)
    fun gains(remainingMs: Long, spanMs: Long): Pair<Float, Float> {
        require(spanMs > 0)
        val phase = (1.0 - remainingMs.toDouble() / spanMs).coerceIn(0.0, 1.0) * PI / 2
        return cos(phase).toFloat() to sin(phase).toFloat()
    }
}
