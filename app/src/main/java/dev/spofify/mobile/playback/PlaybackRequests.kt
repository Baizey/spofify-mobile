package dev.spofify.mobile.playback

import java.util.concurrent.atomic.AtomicLong

/** Cancels deferred play taps across ALL UI connections, including one being rotated out. */
internal object PlaybackRequests {
    private val generation = AtomicLong()
    private val queueGeneration = AtomicLong()
    val epoch: Long get() = generation.get()
    val queueEpoch: Long get() = queueGeneration.get()
    fun invalidate() { generation.incrementAndGet() }
    fun queueChanged() { queueGeneration.incrementAndGet() }
}
