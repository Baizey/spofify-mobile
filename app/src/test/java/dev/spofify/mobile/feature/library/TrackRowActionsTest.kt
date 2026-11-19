package dev.spofify.mobile.feature.library

import dev.spofify.mobile.core.playback.PlaybackTrackReference
import org.junit.Assert.*
import org.junit.Test

class TrackRowActionsTest {
    @Test fun unsentPlayableTapDoesNotFallThroughToDetailsOrWaitForReconnect() {
        val attempts = mutableListOf<PlaybackTrackReference>()
        val notices = mutableListOf<String?>()
        var ready = false
        val tap = {
            activateTrackRow("track", true, play = {
                attempts += it
                ready
            }, openDetails = { fail("A playable track tap is not metadata navigation") }, notice = { notices += it })
        }
        tap()
        assertEquals(listOf(PlaybackTrackReference("track")), attempts)
        assertEquals(listOf<String?>(null), notices)
        ready = true
        assertEquals(1, attempts.size)
        tap() // Only another explicit tap attempts the command again.
        assertEquals(2, attempts.size)
        assertNull(notices.last()) // A sent ID isn't a success or acceptance notice.
    }

    @Test fun unavailableTrackOpensDetailsOnlyWhenMetadataExists() {
        var detailOpens = 0
        val notices = mutableListOf<String?>()
        val play: (PlaybackTrackReference) -> Boolean? = { error("Unavailable track must not be sent") }
        activateTrackRow("track", false, play, openDetails = { detailOpens++ }, notice = { notices += it })
        assertEquals(1, detailOpens)
        assertTrue(notices.isEmpty())
        activateTrackRow("track", false, play, openDetails = null, notice = { notices += it })
        assertEquals(1, detailOpens)
        assertEquals(listOf("Track unavailable."), notices)
    }
}
