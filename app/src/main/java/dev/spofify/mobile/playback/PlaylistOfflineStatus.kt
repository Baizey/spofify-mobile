package dev.spofify.mobile.playback

import dev.spofify.mobile.core.offline.OfflinePlaylistProgress

internal sealed interface PlaylistOfflineStatus {
    data object Saved : PlaylistOfflineStatus
    data object Pending : PlaylistOfflineStatus
    data object Failed : PlaylistOfflineStatus
    data class Downloading(val progress: OfflinePlaylistProgress) : PlaylistOfflineStatus
}

internal fun playlistOfflineStatuses(
    wanted: Set<String>?,
    committed: Set<String>,
    progress: Map<String, OfflinePlaylistProgress>,
    failed: Set<String> = emptySet(),
): Map<String, PlaylistOfflineStatus> = wanted.orEmpty().associateWith { id ->
    when {
        id in committed -> PlaylistOfflineStatus.Saved
        id in failed -> PlaylistOfflineStatus.Failed
        else -> progress[id]?.let { PlaylistOfflineStatus.Downloading(it) } ?: PlaylistOfflineStatus.Pending
    }
}
