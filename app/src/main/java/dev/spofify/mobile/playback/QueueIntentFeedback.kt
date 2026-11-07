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

/** Queue acceptance is separate from playback start, including an initial queue that starts audio.
 * A retained terminal receipt supplies the count; never sample the current player/queue. */
internal fun queueAcceptanceMessage(result: PlaybackActionResult, action: PlaybackListAction?): String? {
    if (action !in setOf(PlaybackListAction.ADD_TO_QUEUE, PlaybackListAction.PLAY_NEXT,
            PlaybackListAction.QUEUE_LOW, PlaybackListAction.QUEUE_HIGH)) return null
    val receipt = result.queueIntentResult
    val count = if (receipt != null) {
        // A committed queue mutation still succeeded when only playback was blocked or failed.
        if (receipt.outcome !in setOf("playing", "queued", "limited", "blocked", "media") ||
            receipt.commitRevision == null || receipt.placement == "none") return null
        receipt.acceptedCount
    } else {
        // Legacy mutation results prove acceptance only for these terminal statuses.
        if (result.status !in setOf(PlaybackActionStatus.ADDED, PlaybackActionStatus.LIMITED)) return null
        result.acceptedCount
    }
    if (count <= 0) return null
    return if (count == 1) "Track queued" else "$count tracks queued"
}

/** Safe static facts: even a real started key is not assumed to be the requested row/album.
 * Historical retained receipts must never be relabelled by looking up today's queue. */
internal fun queueIntentMessage(result: QueueIntentResult, action: PlaybackListAction?): String {
    if (result.notice == "PUBLICATION_LIMIT") return "This queue is too large to share safely. Nothing was added."
    val accepted = result.acceptedCount > 0
    val selection = action == PlaybackListAction.PLAY_NOW
    if (result.playback == "superseded") return "${if (selection) "Selection" else "Queue change"} accepted; playback start superseded by a newer action."
    if (result.playback in setOf("started", "resumed") &&
        result.outcome in setOf("playing", "queued") && result.notice.isNullOrEmpty()) {
        return if (result.acceptedCount > 1) "Playing · ${result.acceptedCount} tracks queued" else "Playing track"
    }
    val acceptance = when {
        !accepted -> null
        result.outcome == "limited" -> "The selected device accepted only ${result.acceptedCount} tracks."
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
        "full" -> "The selected device could not accept this queue."
        "stale" -> "Playback action cancelled after the selection or output changed."
        "unsupported" -> "Update the selected playback device and server to use queue priorities."
        else -> "This selection or playback output is unavailable. Nothing was resubmitted."
    }
}
