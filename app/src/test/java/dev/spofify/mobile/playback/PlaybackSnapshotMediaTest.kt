package dev.spofify.mobile.playback

import android.app.Application
import dev.spofify.mobile.core.PlaybackContext
import dev.spofify.mobile.core.PlaybackSnapshot
import dev.spofify.mobile.core.PlaybackTrack
import dev.spofify.mobile.core.ServerAddress
import dev.spofify.mobile.core.SessionStore
import dev.spofify.mobile.core.SpofifyClient
import dev.spofify.mobile.core.StoredSession
import org.junit.Assert.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlaybackSnapshotMediaTest {
    private fun client(origin: String) = SpofifyClient(ServerAddress.parse(origin), object : SessionStore {
        override fun read(): StoredSession? = null
        override fun write(value: StoredSession?) = Unit
    })

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
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setHeader("Set-Cookie", "spofify_session=synthetic-cookie; Path=/; HttpOnly; Max-Age=3600")
            .setBody("""{"accountId":"fixture-account","username":"fixture-listener","role":"user","csrfToken":"fixture-csrf"}"""))
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val origin = "http://127.0.0.1:${server.port}"
            val client = client(origin)
            runBlocking { client.login("fixture-listener", "synthetic-password") }
            assertEquals("/api/auth/login", server.takeRequest(1, TimeUnit.SECONDS)?.path)
            assertNotNull(client.validatedSessionIdentity())
            val snapshot = snapshot()
            val media = snapshot.queue.map { track -> PlaybackMedia.create(
                PlaybackSnapshotCodec.libraryItem(track), client, PlaybackSnapshotCodec.occurrenceId(track)) }
            assertEquals(listOf("wire-9", "wire-4"), media.map(PlaybackMedia::occurrence))
            assertEquals(listOf("song/a%", "song/a%"), media.map { it.mediaId })
            media.forEach {
                assertEquals("$origin/api/library/tracks/song%2Fa%25/audio", it.localConfiguration!!.uri.toString())
                assertNull(it.mediaMetadata.artworkUri)
                assertEquals("saved-entry", PlaybackMedia.libraryItem(it)!!.entryId)
                assertEquals(30_125L, PlaybackMedia.libraryItem(it)!!.durationMs)
            }
            assertEquals(listOf(9L, 4L), snapshot.queue.map { it.key })
            assertEquals(listOf(50L, 0L), snapshot.queue.map { it.order })
            assertEquals(2, media.map(PlaybackMedia::occurrence).toSet().size)
            assertTrue(media.none { it.localConfiguration!!.uri.toString().contains("untrusted.invalid") })
            assertEquals("Projection constructs media but never opens audio or fetches a context", 1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun localMetadataProjectionIsOfflineButOnlineAudioMaterializationRequiresAuthentication() {
        val signedOut = client("https://music.example.test")
        val projected = PlaybackSnapshotCodec.libraryItem(snapshot().queue.first())
        assertEquals("song/a%", projected.id)
        assertNull(projected.libraryPath)
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackMedia.create(projected, signedOut, "wire-9")
        }
    }
}
