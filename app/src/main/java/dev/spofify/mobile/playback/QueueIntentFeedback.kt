package dev.spofify.mobile.playback

import dev.spofify.mobile.core.QueueIntentResult

/** Preserve the terminal receipt verbatim. Never infer playback from the display snapshot. */
internal fun queueIntentActionResult(detail: QueueIntentResult?, action: PlaybackListAction): PlaybackActionResult =
    PlaybackActionResult(status = when (detail?.outcome) {
        "playing" -> PlaybackActionStatus.PLAYING
        "queued" -> PlaybackActionStatus.ADDED
        "limited" -> PlaybackActionStatus.LIMITED
        "no_op" -> PlaybackActionStatus.NO_OP
        "full" -> PlaybackActionStatus.FULL
        "stale" -> PlaybackActionStatus.STALE
        "unsupported" -> PlaybackActionStatus.UNSUPPORTED
        "blocked" -> PlaybackActionStatus.BLOCKED
        else -> PlaybackActionStatus.UNAVAILABLE
    }, acceptedCount = detail?.acceptedCount ?: 0, queueIntentResult = detail, requestedAction = action)

/** Safe static facts: even a real started key is not assumed to be the requested row/album.
 * Historical retained receipts must never be relabelled by looking up today's queue. */
internal fun queueIntentMessage(result: QueueIntentResult, action: PlaybackListAction?): String {
    val accepted = result.acceptedCount > 0
    val selection = action == PlaybackListAction.PLAY_NOW
    if (result.playback == "superseded") return "${if (selection) "Selection" else "Queue change"} accepted; playback start superseded by a newer action."
    if (result.playback in setOf("started", "resumed") &&
        result.outcome in setOf("playing", "queued") && result.notice.isNullOrEmpty()) {
        return if (result.acceptedCount > 1) "Playing · ${result.acceptedCount} tracks queued" else "Playing track"
    }
    val acceptance = when {
        !accepted -> null
        result.outcome == "limited" -> "Queued ${result.acceptedCount}; queue limited to 1,000 songs."
        selection -> "Selection accepted"
        result.acceptedCount == 1 -> "Track queued"
        else -> "${result.acceptedCount} tracks queued"
    }
    val playback = when (result.playback) {
        "started", "resumed" -> "Playback started"
        "blocked" -> "Playback needs a tap on the selected device."
        "media" -> "The selected device could not play this selection. Retry playback explicitly."
        else -> null
    }
    if (playback != null) return listOfNotNull(acceptance, playback).joinToString(" ")
    return acceptance ?: when (result.outcome) {
        "no_op" -> "Already in this queue position."
        "full" -> "Queue is full (1,000 songs)."
        "stale" -> "Playback action cancelled after the selection or output changed."
        "unsupported" -> "Update the selected playback device and server to use queue priorities."
        else -> "This selection or playback output is unavailable. Nothing was resubmitted."
    }
}
