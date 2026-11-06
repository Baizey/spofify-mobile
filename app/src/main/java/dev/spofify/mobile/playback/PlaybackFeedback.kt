package dev.spofify.mobile.playback

import java.util.concurrent.atomic.AtomicLong

/** Only accepted explicit queue requests enter the transient feedback channel.
 * IDs are process-local presentation identity, never playback authority or durable state. */
data class PlaybackFeedback private constructor(
    val id: Long,
    val message: String,
    internal val isCurrent: () -> Boolean = { true },
) {
    val baseTimeoutMillis: Long get() = 1_500L

    companion object {
        private val serial = AtomicLong()
        fun from(result: PlaybackActionResult, isCurrent: () -> Boolean = { true },
            action: PlaybackListAction? = result.requestedAction): PlaybackFeedback? {
            val message = queueAcceptanceMessage(result, action) ?: return null
            return PlaybackFeedback(serial.incrementAndGet(), message, isCurrent)
        }
    }
}

/** No backlog. Silent outcomes cannot create or replace a queue acceptance. */
internal fun nextPlaybackFeedback(current: PlaybackFeedback?, incoming: PlaybackFeedback?): PlaybackFeedback? =
    incoming ?: current
