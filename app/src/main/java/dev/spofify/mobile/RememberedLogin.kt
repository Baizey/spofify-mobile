package dev.spofify.mobile

import dev.spofify.mobile.core.ServerAddress

/** The latest successful login. Never put this object in saved state, parcels, or logs. */
class RememberedLogin(val server: String, val username: String, val password: String) {
    init {
        require(ServerAddress.parse(server).origin == server) { "A canonical server origin is required" }
    }

    override fun toString(): String = "RememberedLogin([REDACTED])"
}

interface LoginStore {
    fun read(): RememberedLogin?
    fun write(value: RememberedLogin?)
}
