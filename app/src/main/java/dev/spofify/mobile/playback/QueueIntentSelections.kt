package dev.spofify.mobile.playback

import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.PlaybackContext
import dev.spofify.mobile.core.QueueIntentSelection
import kotlinx.serialization.json.*

/** Positive musical projection only. Never forward a LibraryItem's private path or a URL.
 * Derived authority is supplied separately by the source that rendered the signed occurrence. */
internal fun queueIntentSelection(item: LibraryItem, context: PlaybackContext? = null,
    occurrenceId: String? = null, selectionToken: String? = null): QueueIntentSelection {
    if (item.kind != "track") return QueueIntentSelection.Collection(context ?: PlaybackContext(
        item.kind, if (item.kind == "likes") "likes" else item.id, item.name))
    require(item.playable)
    val track = buildJsonObject {
        put("id", item.id); put("name", item.name)
        putJsonArray("artists") { item.artists.forEach { artist -> add(buildJsonObject {
            put("id", artist.id.orEmpty()); put("name", artist.name)
        }) } }
        put("albumName", item.albumName.orEmpty()); put("albumId", item.albumId.orEmpty())
        put("duration", item.durationMs / 1000.0)
        if (context?.kind == "derived") {
            require(!occurrenceId.isNullOrEmpty() && !selectionToken.isNullOrEmpty())
            put("targetId", context.id); put("occurrenceId", occurrenceId); put("selectionToken", selectionToken)
            put("playbackContext", buildJsonObject {
                put("kind", context.kind); put("id", context.id); put("name", context.name)
                put("revision", context.revision); put("fence", context.fence)
            })
        } else if (context?.kind == "playlist") item.entryId?.takeIf { it.isNotEmpty() }?.let { put("entryId", it) }
    }
    return QueueIntentSelection.Track(track, context)
}
