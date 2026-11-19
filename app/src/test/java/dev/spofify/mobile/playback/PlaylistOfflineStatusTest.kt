package dev.spofify.mobile.playback

import dev.spofify.mobile.core.offline.OfflinePlaylistProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistOfflineStatusTest {
    @Test fun onlyWantedPlaylistsHaveStatusAndOnlyCommittedPlaylistsAreSaved() {
        val partial = OfflinePlaylistProgress(1, 3)
        val fullButNotCommitted = OfflinePlaylistProgress(3, 3)
        val statuses = playlistOfflineStatuses(setOf("pending", "partial", "finalizing", "saved"),
            setOf("saved", "removed"), mapOf("partial" to partial, "finalizing" to fullButNotCommitted,
                "saved" to partial, "removed" to partial))
        assertEquals(mapOf("pending" to PlaylistOfflineStatus.Pending,
            "partial" to PlaylistOfflineStatus.Downloading(partial),
            "finalizing" to PlaylistOfflineStatus.Downloading(fullButNotCommitted),
            "saved" to PlaylistOfflineStatus.Saved), statuses)
    }
}
