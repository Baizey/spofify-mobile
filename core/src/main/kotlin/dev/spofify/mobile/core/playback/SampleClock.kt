package dev.spofify.mobile.core.playback

/** Connection-scoped server minus client offset, with monotonic RTT and jump detection. */
internal class SampleClock(private val clock: PlaybackClock) {
    private data class Stamp(val wall: Long, val mono: Long)
    private var attempted: Stamp? = null
    private var anchor: Stamp? = null
    private var bestMs: Long = Long.MAX_VALUE
    private var measured: Long? = null
    private var approximate: Long? = null

    fun reset() {
        attempted = null
        anchor = null
        bestMs = Long.MAX_VALUE
        measured = null
        approximate = null
    }

    private fun now(): Stamp {
        val sample = Stamp(clock.wallTimeMs(), clock.monotonicMs())
        val previous = anchor
        if (previous != null && kotlin.math.abs((sample.wall - previous.wall) - (sample.mono - previous.mono)) > 1_000) reset()
        anchor = sample
        return sample
    }

    fun seed(serverTimeMs: Long) {
        val now = now()
        approximate = serverTimeMs - now.wall
    }

    fun ping(): Long = now().also { attempted = it }.wall

    fun accept(sentTimeMs: Long, serverTimeMs: Long) {
        val now = now()
        val sent = attempted?.takeIf { it.wall == sentTimeMs } ?: return
        attempted = null
        val rtt = now.mono - sent.mono
        if (rtt !in 0..2_000 || rtt > bestMs) return
        bestMs = rtt
        measured = serverTimeMs - (sent.wall + rtt / 2)
    }

    fun serverNow(): Long? {
        val wall = now().wall
        return (measured ?: approximate)?.let { wall + it }
    }
    fun offset(): Long? { now(); return measured }
}
