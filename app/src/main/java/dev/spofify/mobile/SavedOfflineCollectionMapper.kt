package dev.spofify.mobile

import dev.spofify.mobile.core.ArtistCredit
import dev.spofify.mobile.core.Coverage
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.saved.SavedCollectionSnapshot
import dev.spofify.mobile.core.saved.SavedDownloadState
import dev.spofify.mobile.core.saved.SavedOccurrenceSnapshot
import dev.spofify.mobile.core.saved.SavedTargetKind

/** Metadata projection only. The owner adapter captures the exact snapshot/descriptor request;
 * this function neither opens media nor acquires, wraps or duplicates a saved-owner lease. */
internal fun savedOfflineCollection(
    snapshot: SavedCollectionSnapshot,
    referenceFor: (SavedOccurrenceSnapshot) -> OfflineMediaReference,
): OfflineSavedCollection {
    val references = snapshot.occurrences.mapNotNull { row ->
        val descriptor = row.media ?: return@mapNotNull null
        if (!row.available || !row.occurrence.hasAudio) return@mapNotNull null
        require(descriptor.trackId == row.occurrence.trackId)
        row.occurrence.occurrenceId to referenceFor(row)
    }.toMap()
    val rows = snapshot.occurrences.map { row ->
        val occurrence = row.occurrence
        LibraryItem(
            kind = "track", id = occurrence.trackId, name = occurrence.title,
            artists = occurrence.artist.takeIf(String::isNotBlank)?.let { listOf(ArtistCredit("", it)) }.orEmpty(),
            albumId = occurrence.albumId, albumName = occurrence.albumTitle, durationMs = occurrence.durationMs,
            hasAudio = occurrence.occurrenceId in references, entryId = occurrence.occurrenceId,
        )
    }
    val kind = when (snapshot.target.kind) {
        SavedTargetKind.MANUAL_PLAYLIST -> "playlist"
        SavedTargetKind.DERIVED_PLAYLIST -> "derived"
        SavedTargetKind.ALBUM -> "album"
        SavedTargetKind.ARTIST -> "artist"
    }
    val target = LibraryItem(kind, snapshot.target.id, snapshot.title,
        coverage = Coverage(tracks = rows.size, inLibrary = references.size))
    // Authoritative completion is independent of updating and metadata-only, unplayable rows.
    return OfflineSavedCollection(target, rows, references, snapshot.state == SavedDownloadState.COMPLETE)
}
