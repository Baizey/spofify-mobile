package dev.spofify.mobile.playback

import java.util.concurrent.atomic.AtomicLong

/** Cancels deferred play taps across ALL UI connections, including one being rotated out. */
internal object PlaybackRequests {
    private val generation = AtomicLong()
    val epoch: Long get() = generation.get()
    fun invalidate() { generation.incrementAndGet() }
}
