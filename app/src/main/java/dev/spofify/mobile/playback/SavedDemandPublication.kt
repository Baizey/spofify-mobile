package dev.spofify.mobile.playback

import java.util.concurrent.atomic.AtomicInteger

/** Serialize priority publications, never audio IO. A fast true completes before its operation
 * begins; the main aggregate re-reads the operation count under the same publication gate. */
internal class SavedDemandPublication(private val operations: AtomicInteger) {
    private val lock = Any()

    fun active(publish: (Boolean) -> Unit) = synchronized(lock) { publish(true) }

    fun aggregate(playerDemanded: Boolean, publish: (Boolean) -> Unit) = synchronized(lock) {
        publish(operations.get() > 0 || playerDemanded)
    }
}
