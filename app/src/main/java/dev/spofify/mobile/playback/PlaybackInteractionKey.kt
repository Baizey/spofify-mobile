package dev.spofify.mobile.playback

/**
 * Opaque in-process interaction identity, NOT backend authority. Compare by identity/equality only.
 * Capture from [PlaybackState] at gesture start; never persist, save, log or put in media extras.
 * Progress-only publications retain the same key. Null means interactions are unavailable.
 */
class PlaybackInteractionKey internal constructor() {
    override fun toString(): String = "PlaybackInteractionKey(transient)"
}

/** Session DTO value equality must not make a same-account reauthentication look unchanged. */
internal class PlaybackReference(private val value: Any) {
    override fun equals(other: Any?): Boolean = other is PlaybackReference && value === other.value
    override fun hashCode(): Int = System.identityHashCode(value)
}

/** Keeps identity material private and never encodes client/session/queue data into the key. */
internal class PlaybackInteractionKeys {
    private var identity: Any? = null
    private var key: PlaybackInteractionKey? = null

    fun current(identity: Any?): PlaybackInteractionKey? {
        if (identity == null) {
            invalidate()
            return null
        }
        if (this.identity != identity) {
            this.identity = identity
            key = PlaybackInteractionKey()
        }
        return key
    }

    fun invalidate() {
        identity = null
        key = null
    }
}
