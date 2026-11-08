package dev.spofify.mobile.feature.playlists

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** One service/client demand record. Publish the service's aggregate loading/buffering demand,
 * not a false transition for each individual read. A new record starts conservatively active
 * until its first aggregate false. Superseded handles cannot change successors.
 * Calls are nonblocking: atomic gates/cancellation/enqueue only, never disk or waiting. */
class SavedPlaybackDemand internal constructor(
    private val update: (Boolean) -> Unit,
    private val release: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    fun setActive(active: Boolean) { if (!closed.get()) update(active) }
    override fun close() { if (closed.compareAndSet(false, true)) release() }
}
