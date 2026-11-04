package dev.spofify.mobile.playback

import android.app.Application
import dev.spofify.mobile.core.PlaybackContext
import dev.spofify.mobile.core.PlaybackSnapshot
import dev.spofify.mobile.core.PlaybackTrack
import dev.spofify.mobile.core.ServerAddress
import dev.spofify.mobile.core.Session
import dev.spofify.mobile.core.SessionStore
import dev.spofify.mobile.core.SpofifyClient
import dev.spofify.mobile.core.StoredSession
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlaybackSnapshotMediaTest {
    private fun client(authenticated: Boolean) = SpofifyClient(
        ServerAddress.parse("https://music.example.test"),
        object : SessionStore {
            override fun read(): StoredSession? = null
            override fun write(value: StoredSession?) = Unit
        },
    ).also { client ->
        // Offline synthetic auth fixture only: no login, DNS, socket, audio source, or player.
        if (authenticated) SpofifyClient::class.java.getDeclaredField("session").apply {
            isAccessible = true
            set(client, Session("fixture-account", "fixture-listener", "user", "fixture-csrf"))
        }
    }

    private fun snapshot(): PlaybackSnapshot {
        val track = PlaybackTrack(
            id = "song/a%", name = "Song", artists = emptyList(), albumName = "Album", albumId = "album",
            duration = 30.125, entryId = "saved-entry", targetId = "https://untrusted.invalid/audio",
            occurrenceId = "長い occurrence ".repeat(100),
            playbackContext = PlaybackContext("derived", "mix", "Mix", 1, "signed-fence"),
            key = 9, order = 50,
        )
        return PlaybackSnapshot(queue = listOf(track, track.copy(key = 4, order = 0)), position = 0, shuffle = true)
    }

    @Test fun mediaConstructionUsesAuthenticatedOriginAndSyntheticInternalOccurrenceOnly() {
        val snapshot = snapshot()
        val plan = PlaybackSnapshotCodec.plan(snapshot)
        val media = plan.mediaItems(client(authenticated = true))
        assertEquals(listOf("wire-4", "wire-9"), media.map(PlaybackMedia::occurrence))
        assertEquals(listOf("song/a%", "song/a%"), media.map { it.mediaId })
        media.forEach {
            assertEquals("https://music.example.test/api/library/tracks/song%2Fa%25/audio",
                it.localConfiguration!!.uri.toString())
            assertNull(it.mediaMetadata.artworkUri)
            assertEquals("saved-entry", PlaybackMedia.libraryItem(it)!!.entryId)
            assertEquals(30_125L, PlaybackMedia.libraryItem(it)!!.durationMs)
        }
        assertEquals(snapshot, plan.snapshot)
        assertEquals(snapshot.queue, plan.traversalIndices.map { plan.physicalTracks[it] })
    }

    @Test fun preparationPlanIsOfflineButMaterializingEvenAnEmptyQueueRequiresAuthentication() {
        val signedOut = client(authenticated = false)
        val plan = PlaybackSnapshotCodec.plan(snapshot())
        assertThrows(IllegalArgumentException::class.java) { plan.mediaItems(signedOut) }
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackSnapshotCodec.plan(PlaybackSnapshot()).mediaItems(signedOut)
        }
    }
}
