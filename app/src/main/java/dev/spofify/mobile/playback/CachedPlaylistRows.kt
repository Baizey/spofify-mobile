package dev.spofify.mobile.playback

import dev.spofify.mobile.core.personal.PersonalManualEntry
import dev.spofify.mobile.core.playback.*

/** Preserve every occurrence and its order; only a collection start may pick the first playable row. */
internal fun cachedPlaylistRows(entries: List<PersonalManualEntry>, context: PlaybackContext,
    selected: PlaybackTrackReference?, downloads: Set<String>): LocalPlaybackRows? {
    val derived = context.kind == "derived"
    val rows = entries.map { (entry, _, track) ->
        PlaybackTrack(track.id, track.name, track.artists.map { PlaybackArtist(it.name, it.id) }, track.albumName,
            track.albumId, track.durationMs / 1000.0, 0, 0, "", entryId = entry.takeUnless { derived },
            targetId = context.id.takeIf { derived }, occurrenceId = entry.takeIf { derived })
    }
    val index = if (selected == null) rows.indexOfFirst { it.id in downloads } else rows.indexOfFirst {
        it.id == selected.id && (selected.entryId == null || it.entryId == selected.entryId) &&
            (selected.targetId == null || it.targetId == selected.targetId) &&
            (selected.occurrenceId == null || it.occurrenceId == selected.occurrenceId)
    }
    return if (index < 0) null else LocalPlaybackRows(rows, index)
}
