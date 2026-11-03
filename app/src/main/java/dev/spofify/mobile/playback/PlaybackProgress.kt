package dev.spofify.mobile.playback

import kotlin.math.min

/**
 * Pure history gate, one instance per queue occurrence. Call [discontinuity] for seeks,
 * transitions and playback-state changes. Preparing, buffering, pausing and seeking do
 * not count. A defensive wall-clock bound also rejects unannounced position jumps.
 */
internal class PlaybackProgress(private val thresholdMs: Long = 1_000) {
    data class Snapshot(val accumulatedMs: Long, val recorded: Boolean)
    fun snapshot(): Snapshot = Snapshot(accumulatedMs, recorded)
    fun restore(snapshot: Snapshot) {
        require(snapshot.accumulatedMs in 0..604_800_000)
        accumulatedMs = snapshot.accumulatedMs
        recorded = snapshot.recorded
        discontinuity()
    }
    private var position: Long? = null
    private var realtime: Long = 0
    private var wasPlaying = false
    private var accumulatedMs = 0L
    private var recorded = false

    fun discontinuity() {
        position = null
        wasPlaying = false
    }

    /** Returns true once, only when enough actual forward playback has accumulated. */
    fun sample(positionMs: Long, realtimeMs: Long, isPlaying: Boolean, speed: Float = 1f): Boolean {
        val previous = position
        if (!recorded && previous != null && wasPlaying && isPlaying) {
            val elapsed = (realtimeMs - realtime).coerceAtLeast(0)
            val delta = positionMs - previous
            val expected = (elapsed * speed.coerceAtLeast(0f)).toLong()
            if (delta > 0 && delta <= expected + 250) {
                accumulatedMs += min(delta, expected)
            }
        }
        position = positionMs
        realtime = realtimeMs
        wasPlaying = isPlaying
        if (!recorded && accumulatedMs >= thresholdMs) {
            recorded = true
            return true
        }
        return false
    }
}
