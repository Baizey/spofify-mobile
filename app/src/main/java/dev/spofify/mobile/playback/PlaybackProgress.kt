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

    /**
     * Returns true once, on NEW positive audible progress after reaching the threshold.
     * Imported progress is credit toward the threshold, never itself permission to report:
     * paused samples, the initial anchor and seeks must not emit a transferred history event.
     */
    fun sample(positionMs: Long, realtimeMs: Long, isPlaying: Boolean, speed: Float = 1f): Boolean {
        val previous = position
        var addedMs = 0L
        if (!recorded && previous != null && wasPlaying && isPlaying) {
            val elapsed = (realtimeMs - realtime).coerceAtLeast(0)
            val delta = positionMs - previous
            val expected = (elapsed * speed.coerceAtLeast(0f)).toLong()
            if (delta > 0 && delta <= expected + 250) {
                addedMs = min(delta, expected)
                accumulatedMs = (accumulatedMs + addedMs).coerceAtMost(604_800_000)
            }
        }
        position = positionMs
        realtime = realtimeMs
        wasPlaying = isPlaying
        if (!recorded && addedMs > 0 && accumulatedMs >= thresholdMs) {
            recorded = true
            return true
        }
        return false
    }
}
