package dev.spofify.mobile.feature.playlists

import dev.spofify.mobile.core.DerivedEvaluationPage
import dev.spofify.mobile.core.PersonalPlaylistSummary
import dev.spofify.mobile.core.PlaylistEvaluationState
import dev.spofify.mobile.core.PlaylistKind

/** A form keeps its captured source; a later read must not silently authorize that old intent. */
internal data class PlaylistCopySource(val revision: Long, val fence: String, val isCurrent: () -> Boolean)

internal fun playlistCountLabel(summary: PersonalPlaylistSummary, page: DerivedEvaluationPage? = null): String {
    if (summary.kind == PlaylistKind.MANUAL) return "${summary.trackCount} tracks · ${summary.availableCount} with audio"
    val state = page?.evaluationState ?: summary.evaluationState
    val counts = when {
        page?.current == true -> "${page.total} tracks · ${page.availableTotal} with audio"
        page != null && (page.items.isNotEmpty() || page.total > 0) -> "${page.total} tracks · ${page.availableTotal} with audio"
        summary.trackCount != null -> "${summary.trackCount} tracks · ${summary.availableCount} with audio"
        else -> null
    }
    return when (state) {
        PlaylistEvaluationState.READY -> counts ?: "Playlist ready"
        PlaylistEvaluationState.ERROR -> counts?.let { "$it · Previous result; update failed" }
            ?: "Playlist could not update. Try Refresh."
        PlaylistEvaluationState.PENDING, PlaylistEvaluationState.STALE -> counts?.let { "$it · Updating; previous result" }
            ?: "Updating playlist…"
        null -> if (page?.current == true) counts!! else "Updating playlist…"
    }
}
