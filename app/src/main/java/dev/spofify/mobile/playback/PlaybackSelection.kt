package dev.spofify.mobile.playback

import dev.spofify.mobile.core.LibraryItem
import androidx.media3.common.Player

/** Playlist entries are occurrences: [A, B, A] must allow starting at the second A. */
internal fun playbackStartIndex(items: List<LibraryItem>, trackId: String?, entryId: String?): Int =
    items.indexOfFirst { if (entryId != null) it.entryId == entryId else it.id == trackId }.coerceAtLeast(0)

internal fun keepPlaybackAfterTaskRemoved(playWhenReady: Boolean, hasItems: Boolean, state: Int, hasError: Boolean): Boolean =
    playWhenReady && hasItems && !hasError && (state == Player.STATE_READY || state == Player.STATE_BUFFERING)
