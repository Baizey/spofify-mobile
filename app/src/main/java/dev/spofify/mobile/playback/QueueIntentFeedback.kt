package dev.spofify.mobile.playback

/** Queue acceptance and observed playback are separate, including blocked execution. */
internal fun queueAcceptanceMessage(result: PlaybackActionResult, action: PlaybackListAction?): String? {
    if (action !in setOf(PlaybackListAction.ADD_TO_QUEUE, PlaybackListAction.PLAY_NEXT,
            PlaybackListAction.QUEUE_LOW, PlaybackListAction.QUEUE_HIGH)) return null
    val receipt = result.serverResult
    val count = if (receipt != null) {
        if (receipt.outcome != "accepted" || receipt.placement == "none") return null
        receipt.acceptedCount
    } else {
        if (result.status !in setOf(PlaybackActionStatus.ADDED, PlaybackActionStatus.LIMITED)) return null
        result.acceptedCount
    }
    if (count <= 0) return null
    return if (count == 1) "Track queued" else "$count tracks queued"
}
