package dev.spofify.mobile.core.auth

/** A captured session's resource gate. Actions must only enqueue bounded work, never perform I/O. */
internal class FeatureAuthority(private val monitor: Any, private val current: () -> Boolean) {
    val isCurrent: Boolean get() = synchronized(monitor) { current() }

    fun runIfCurrent(action: () -> Boolean): Boolean = synchronized(monitor) {
        current() && action()
    }
}
