package dev.spofify.mobile.playback

import java.util.concurrent.atomic.AtomicLong

/** Presentation only. IDs are process-local, never playback authority or durable state. */
enum class PlaybackFeedbackKind { SUCCESS, GUIDANCE }

data class PlaybackFeedback private constructor(
    val id: Long,
    val message: String,
    val kind: PlaybackFeedbackKind,
    internal val isCurrent: () -> Boolean = { true },
) {
    val baseTimeoutMillis: Long get() = if (kind == PlaybackFeedbackKind.SUCCESS) 1_500L else 10_000L

    companion object {
        private val serial = AtomicLong()
        fun guidance(message: String) = PlaybackFeedback(serial.incrementAndGet(), message, PlaybackFeedbackKind.GUIDANCE)
        fun from(result: PlaybackActionResult, isCurrent: () -> Boolean = { true }): PlaybackFeedback {
            val receipt = result.queueIntentResult
            val routine = if (receipt == null) {
                result.status == PlaybackActionStatus.PLAYING || result.status == PlaybackActionStatus.ADDED
            } else {
                receipt.outcome in setOf("playing", "queued") && receipt.notice.isNullOrEmpty() &&
                    receipt.playback in setOf("started", "resumed", "unchanged") && receipt.acceptedCount > 0
            }
            return PlaybackFeedback(serial.incrementAndGet(), result.userMessage,
                if (routine) PlaybackFeedbackKind.SUCCESS else PlaybackFeedbackKind.GUIDANCE, isCurrent)
        }
    }
}

/** No success backlog, and routine acceptance must never displace unread recovery guidance. */
internal fun nextPlaybackFeedback(current: PlaybackFeedback?, incoming: PlaybackFeedback): PlaybackFeedback =
    if (current?.kind == PlaybackFeedbackKind.GUIDANCE && current.isCurrent() &&
        incoming.kind == PlaybackFeedbackKind.SUCCESS) current else incoming
