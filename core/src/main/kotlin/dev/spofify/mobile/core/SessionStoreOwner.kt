package dev.spofify.mobile.core

/** Fences persistence across client instances, not just requests within one client. */
class SessionStoreOwner(private val delegate: SessionStore) {
    private val lock = Any()
    private var epoch = 0L

    fun read(): StoredSession? = synchronized(lock) { delegate.read() }

    fun acquire(): SessionStore = synchronized(lock) {
        val lease = ++epoch
        object : SessionStore {
            override fun read(): StoredSession? = synchronized(lock) {
                if (lease == epoch) delegate.read() else null
            }
            override fun write(value: StoredSession?) = synchronized(lock) {
                if (lease == epoch) delegate.write(value)
            }
        }
    }

    fun clear() = synchronized(lock) {
        epoch++
        delegate.write(null)
    }
}
