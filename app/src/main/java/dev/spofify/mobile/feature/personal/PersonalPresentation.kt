package dev.spofify.mobile.feature.personal

import dev.spofify.mobile.core.library.Artist
import dev.spofify.mobile.core.library.MusicItem
import dev.spofify.mobile.core.library.MusicKind
import dev.spofify.mobile.core.personal.*
import dev.spofify.mobile.core.playback.PlaybackContext
import dev.spofify.mobile.core.playback.PlaybackTrackReference
import dev.spofify.mobile.core.playback.PlaybackView
import java.text.Collator
import java.util.Locale

internal fun PersonalTrack.musicItem() = MusicItem(
    MusicKind.TRACK, id, name, artists.map { Artist(it.id, it.name) }, albumId, albumName,
    releaseDate, durationMs, trackNumber, hasAudio, coverage.inLibrary, coverage.tracks,
)

/** Quiet scalar progress does not change a row gesture; occurrence, context and authority changes do. */
internal data class PersonalGestureBinding(
    val source: Any,
    val context: PlaybackContext?,
    val sessionId: String?,
    val stateVersion: Long?,
    val outputVersion: Long?,
)

internal fun personalGestureBinding(source: Any, context: PlaybackContext?, view: PlaybackView?) = PersonalGestureBinding(
    source, context, view?.sessionId, view?.snapshot?.stateVersion, view?.snapshot?.outputVersion,
)

internal data class PlaylistSong(
    val identity: String,
    val position: Long,
    val track: PersonalTrack,
    val entryId: String? = null,
    val occurrenceId: String? = null,
    val selectionToken: String? = null,
) {
    fun reference(context: PlaybackContext?): PlaybackTrackReference? {
        if (context == null || !track.hasAudio) return null
        return when (context.kind) {
            "playlist" -> entryId?.let { PlaybackTrackReference(track.id, entryId = it) }
            "derived" -> if (occurrenceId == null || selectionToken == null) null else PlaybackTrackReference(
                track.id, targetId = context.id, occurrenceId = occurrenceId, selectionToken = selectionToken,
            )
            else -> null
        }
    }
}

/** Ascending data feeds reverseLayout directly: visually descending, with A nearest the bottom. */
internal fun naturalPlaylistOrder(items: List<PersonalPlaylistSummary>): List<PersonalPlaylistSummary> {
    val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
    fun compareNames(left: String, right: String): Int {
        var a = 0
        var b = 0
        while (a < left.length && b < right.length) {
            val digits = left[a] in '0'..'9' && right[b] in '0'..'9'
            val aStart = a
            val bStart = b
            if (digits) {
                while (a < left.length && left[a] in '0'..'9') a++
                while (b < right.length && right[b] in '0'..'9') b++
                val first = left.substring(aStart, a).trimStart('0').ifEmpty { "0" }
                val second = right.substring(bStart, b).trimStart('0').ifEmpty { "0" }
                val result = first.length.compareTo(second.length).takeIf { it != 0 } ?: first.compareTo(second)
                if (result != 0) return result
            } else {
                a++
                b++
                while (a < left.length && left[a] !in '0'..'9') a++
                while (b < right.length && right[b] !in '0'..'9') b++
                val result = collator.compare(left.substring(aStart, a), right.substring(bStart, b))
                if (result != 0) return result
            }
        }
        return (left.length - a).compareTo(right.length - b)
    }
    return items.sortedWith { first, second ->
        compareNames(first.name, second.name).takeIf { it != 0 } ?: first.id.compareTo(second.id)
    }
}

/** Collection-only admission needs the freshly returned rules fence, never guessed row identities. */
internal suspend fun freshPlaylistContext(api: PersonalLibraryApi, id: String): PlaybackContext {
    val summary = api.playlist(id)
    if (summary.kind == PersonalPlaylistKind.MANUAL) return PlaybackContext("playlist", summary.id, summary.name)
    val page = api.derivedEntries(id, limit = 1)
    if (!page.complete || page.revision == null || page.fence.isBlank()) {
        throw PersonalLibraryException(409, PersonalLibraryErrorCode.STALE_EVALUATION)
    }
    return PlaybackContext("derived", summary.id, summary.name, page.revision, page.fence)
}

internal fun playlistCount(summary: PersonalPlaylistSummary): String = when {
    summary.trackCount == null -> "Count pending"
    summary.countState == PersonalCountState.STALE -> "${summary.trackCount} songs · stored result"
    summary.trackCount == 1L -> "1 song"
    else -> "${summary.trackCount} songs"
}

internal fun personalError(failure: Exception, mutation: Boolean = false): String {
    val error = failure as? PersonalLibraryException
    return when {
        error?.code == PersonalLibraryErrorCode.STALE_EVALUATION -> "Refresh the rules playlist before listening."
        error?.status == 409 -> "This playlist changed elsewhere. Refresh and review before trying again."
        error?.status == 401 -> "Sign in again to use your library."
        error?.status == 404 -> "This playlist or song is no longer available."
        mutation && (error == null || error.status == 0 || error.status == 408 || error.status >= 500) ->
            "The change couldn't be confirmed. Refresh to check before trying again; it wasn't resubmitted."
        mutation -> "The change wasn't saved. Refresh and review before trying again."
        else -> "Can't load music."
    }
}
