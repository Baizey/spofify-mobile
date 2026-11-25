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
    val connectionId: String?,
    val offline: Boolean?,
)

internal fun personalGestureBinding(source: Any, context: PlaybackContext?, view: PlaybackView?) = PersonalGestureBinding(
    source, context, view?.sessionId, view?.snapshot?.stateVersion, view?.snapshot?.outputVersion,
    view?.connectionId, view?.offline,
)

internal data class PlaylistSong(
    val identity: String,
    val position: Long,
    val track: PersonalTrack,
    val entryId: String? = null,
    val occurrenceId: String? = null,
) {
    fun reference(context: PlaybackContext?): PlaybackTrackReference? {
        if (context == null || !track.hasAudio) return null
        return when (context.kind) {
            "playlist" -> entryId?.let { PlaybackTrackReference(track.id, entryId = it) }
            "derived" -> occurrenceId?.let { PlaybackTrackReference(track.id, targetId = context.id, occurrenceId = it) }
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

internal fun playlistContext(summary: PersonalPlaylistSummary): PlaybackContext =
    PlaybackContext(if (summary.kind == PersonalPlaylistKind.DERIVED) "derived" else "playlist", summary.id, summary.name)

internal fun playlistCount(summary: PersonalPlaylistSummary): String = when {
    summary.trackCount == null -> "Count pending"
    summary.countState == PersonalCountState.STALE -> "${summary.trackCount} songs · stored result"
    summary.trackCount == 1L -> "1 song"
    else -> "${summary.trackCount} songs"
}
