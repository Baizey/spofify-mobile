package dev.spofify.mobile.core.auth

/** One installation-wide user choice, independent of credentials. Called off Main. */
interface ManualOfflineStore {
    fun read(): Boolean
    /** Return only after durable commit; throw on failure. */
    fun write(enabled: Boolean)
}

/** Network worker key; unlike local account identity this never survives disconnect/revalidation. */
class OnlineGrant internal constructor(val identity: AccountIdentity, val generation: Long)
