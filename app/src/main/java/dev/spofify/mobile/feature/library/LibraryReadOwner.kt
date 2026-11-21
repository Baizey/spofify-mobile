package dev.spofify.mobile.feature.library

import dev.spofify.mobile.core.auth.OnlineGrant
import dev.spofify.mobile.core.auth.SessionRepository

/** One online source owns the whole load, not just each HTTP exchange. */
internal class LibraryReadOwner(private val sessions: SessionRepository, private val grant: OnlineGrant) {
    suspend fun <T : Any> read(block: suspend () -> T): T = sessions.withOnlineGrant(grant) { block() }
    fun publish(block: () -> Unit): Boolean = sessions.publishIfCurrent(grant, block)
}
