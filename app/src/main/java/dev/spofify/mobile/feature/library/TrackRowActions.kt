package dev.spofify.mobile.feature.library

import dev.spofify.mobile.core.playback.PlaybackTrackReference

/** A playable row is a playback intent, even when that intent can't be sent. */
internal fun activateTrackRow(
    id: String,
    hasAudio: Boolean,
    play: (PlaybackTrackReference) -> Boolean?,
    openDetails: (() -> Unit)?,
    notice: (String?) -> Unit,
) {
    when {
        hasAudio -> { play(PlaybackTrackReference(id)); notice(null) }
        openDetails != null -> openDetails()
        else -> notice("Track unavailable.")
    }
}
