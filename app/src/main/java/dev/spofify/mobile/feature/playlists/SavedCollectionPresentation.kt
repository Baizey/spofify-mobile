package dev.spofify.mobile.feature.playlists

import dev.spofify.mobile.core.Coverage
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.saved.SavedCollectionSnapshot
import dev.spofify.mobile.core.saved.SavedDownloadState
import dev.spofify.mobile.core.saved.SavedTarget
import dev.spofify.mobile.core.saved.SavedTargetKind

/** Saved album/artist identities remain album/artist identities, never fake server playlists. */
internal fun SavedCollectionSnapshot.libraryItem(): LibraryItem = LibraryItem(
    kind = when (target.kind) {
        SavedTargetKind.MANUAL_PLAYLIST -> "playlist"
        SavedTargetKind.DERIVED_PLAYLIST -> "derived"
        SavedTargetKind.ALBUM -> "album"
        SavedTargetKind.ARTIST -> "artist"
    },
    id = target.id,
    name = title,
    coverage = Coverage(tracks = occurrences.size, inLibrary = occurrences.count { it.available }),
)

internal fun savedByteLabel(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f KiB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024))
    else -> String.format(java.util.Locale.ROOT, "%.1f GiB", bytes / (1024.0 * 1024 * 1024))
}

internal fun SavedTarget.matchesPlaylist(id: String): Boolean = this.id == id &&
    kind in setOf(SavedTargetKind.MANUAL_PLAYLIST, SavedTargetKind.DERIVED_PLAYLIST)

/** Demand follows the rows actually placed at the visual bottom, not a relocated server ID. */
internal fun savedIndexFrontier(renderedServerIds: List<String>, lastSavedTarget: SavedTarget?): List<String> =
    renderedServerIds.takeLast(3).ifEmpty {
        lastSavedTarget?.let { listOf("saved:${it.kind}:${it.id}") }.orEmpty()
    }

internal fun SavedCollectionSnapshot.presentation(): SavedDownloadPresentation {
    val ready = occurrences.count { it.available }
    return SavedDownloadPresentation(
        phase = when {
            state == SavedDownloadState.DOWNLOADING -> SavedDownloadPhase.DOWNLOADING
            updating -> SavedDownloadPhase.PARTIAL
            state == SavedDownloadState.COMPLETE && ready == occurrences.size -> SavedDownloadPhase.COMPLETE
            failure == "cancelled" -> SavedDownloadPhase.PAUSED
            state == SavedDownloadState.FAILED -> SavedDownloadPhase.FAILED
            else -> SavedDownloadPhase.PARTIAL
        },
        ready = ready,
        total = occurrences.size,
        transferredBytes = transferredBytes,
        updating = updating,
        failure = when (failure) {
            null, "cancelled" -> null
            "space", "storage" -> "Not enough storage"
            "auth" -> "Download could not finish"
            "unavailable" -> "Some audio is unavailable"
            "retry_later" -> "Server busy. Try again later"
            else -> "Download could not finish"
        },
    )
}
