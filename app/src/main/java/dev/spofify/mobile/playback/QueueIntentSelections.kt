package dev.spofify.mobile.playback

import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.LibraryPlaybackContext
import dev.spofify.mobile.core.LibraryPlaybackSelection
import dev.spofify.mobile.core.LibraryPlaybackTrackRef

/** Identifier-only selection. Metadata, queue expansion and canonical membership belong to the server. */
internal fun queueIntentSelection(item: LibraryItem, context: LibraryPlaybackContext? = null,
    occurrenceId: String? = null, selectionToken: String? = null): LibraryPlaybackSelection {
    if (item.kind != "track") return LibraryPlaybackSelection.Collection(context ?: LibraryPlaybackContext(
        item.kind, if (item.kind == "likes") "liked-songs" else item.id, item.name))
    require(item.playable)
    val ref = when (context?.kind) {
        "derived" -> {
            val revision = context.revision ?: 0
            require(revision > 0 && !context.fence.isNullOrEmpty())
            require(!occurrenceId.isNullOrEmpty() && !selectionToken.isNullOrEmpty())
            LibraryPlaybackTrackRef(item.id, targetId = context.id, occurrenceId = occurrenceId, selectionToken = selectionToken)
        }
        "playlist" -> LibraryPlaybackTrackRef(item.id, entryId = requireNotNull(item.entryId).also { require(it.isNotEmpty()) })
        else -> LibraryPlaybackTrackRef(item.id)
    }
    return LibraryPlaybackSelection.Track(ref, context)
}
