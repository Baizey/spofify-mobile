package dev.spofify.mobile.playback

import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import dev.spofify.mobile.AppGraph
import dev.spofify.mobile.LoginStore
import dev.spofify.mobile.RememberedLogin
import dev.spofify.mobile.SpofifyApplication
import dev.spofify.mobile.core.PlaybackContext
import dev.spofify.mobile.core.PlaybackDeviceCommand
import dev.spofify.mobile.core.PlaybackSnapshot
import dev.spofify.mobile.core.PlaybackTrack
import dev.spofify.mobile.core.ServerAddress
import dev.spofify.mobile.core.SessionStore
import dev.spofify.mobile.core.StoredSession
import dev.spofify.mobile.feature.devices.PlaybackAuthority
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Real service import/enqueue and ExoPlayer transition callbacks. No tier mutation/reflection,
 * fake transition listener, prepared audio source or audible/native progression claim.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SpofifyApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackManualProgressionServiceTest {
    private lateinit var web: MockWebServer
    private lateinit var graph: AppGraph
    private var controller: ServiceController<PlaybackService>? = null
    private val endpoint = Media3PlaybackEndpoint()
    private val permit = PlaybackAuthority(23, 4) { true }

    @Before fun setUp() = runBlocking {
        System.getenv("SPOFIFY_TEST_TMPDIR")?.let { expected ->
            assertEquals("Verify the forked test JVM's temp directory", expected, System.getProperty("java.io.tmpdir"))
        }
        val app = ApplicationProvider.getApplicationContext<SpofifyApplication>()
        app.graph.clear()
        graph = AppGraph(app, object : SessionStore {
            private var value: StoredSession? = null
            override fun read() = value
            override fun write(value: StoredSession?) { this.value = value }
        }, object : LoginStore {
            override fun read(): RememberedLogin? = null
            override fun write(value: RememberedLogin?) = Unit
        })
        SpofifyApplication::class.java.getDeclaredField("graph").apply { isAccessible = true; set(app, graph) }
        web = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path == "/api/auth/login" && request.method == "POST") MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setHeader("Set-Cookie", "spofify_session=synthetic-cookie; Path=/; HttpOnly; Max-Age=3600")
                        .setBody("""{"accountId":"fixture-account","username":"listener","role":"user","csrfToken":"synthetic-csrf"}""")
                    else MockResponse().setResponseCode(500)
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
        PlaybackService.onServiceActiveChanged = null // No backend registration/polling.
        client.login("listener", "synthetic-password")
        assertEquals("/api/auth/login", web.takeRequest(1, TimeUnit.SECONDS)?.path)
        controller = Robolectric.buildService(PlaybackService::class.java).create()
        val baseline = PlaybackSnapshot(
            queue = listOf(track("A", 1), track("C1", 2), track("C2", 3)), position = 0,
            context = PlaybackContext("album", "album", "Album"),
        ).validated()
        assertEquals("ok", endpoint.activate(baseline, permit).status)
        enqueue("M1")
        enqueue("M2")
        // Flush setup-only timeline/prune work before the unsampled traversal under test.
        shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
        assertEquals(listOf("A", "M1", "M2", "C1", "C2"), endpoint.exportSnapshot().queue.map { it.name })
        assertCurrent("A")
    }

    @After fun tearDown() {
        PlaybackService.onServiceActiveChanged = null
        try { controller?.destroy() }
        finally { try { if (::graph.isInitialized) graph.clear() } finally { if (::web.isInitialized) web.shutdown() } }
    }

    // Read-only access to the real service-owned output, not a proxy or manually invoked listener.
    // Seeks below model actual selected-occurrence changes without preparing/starting audio.
    private val output: ExoPlayer get() = PlaybackService::class.java.getDeclaredField("player").let {
        it.isAccessible = true
        it.get(requireNotNull(controller).get()) as ExoPlayer
    }

    private fun track(name: String, key: Long = 1) = PlaybackTrack(
        // Deliberate duplicate track IDs: only occurrence keys identify M1 versus M2.
        if (name.startsWith("M")) "duplicate-track" else name, name, emptyList(), "Album", "album", 120.0,
        key = key, order = key - 1,
    )

    private suspend fun enqueue(name: String): PlaybackSnapshot {
        val wire = PlaybackSnapshot(queue = listOf(track(name)), position = 0).toJson()
        val result = endpoint.execute(PlaybackDeviceCommand.musical("enqueue",
            track = wire.getValue("queue").jsonArray.single().jsonObject, first = false), permit)
        assertEquals("ok", result.status)
        assertFalse(output.playWhenReady)
        assertFalse(output.isPlaying)
        assertEquals("Only synthetic login may hit HTTP", 1, web.requestCount)
        return result.snapshot
    }

    private fun assertCurrent(name: String) {
        assertEquals(name, output.currentMediaItem?.mediaMetadata?.title?.toString())
    }

    private fun seekTo(name: String) {
        val index = (0 until output.mediaItemCount).single {
            output.getMediaItemAt(it).mediaMetadata.title?.toString() == name
        }
        output.seekTo(index, 0)
        assertCurrent(name)
    }

    private suspend fun assertFreshManualPrecedesRetiredOccurrences() {
        val before = endpoint.exportSnapshot()
        val after = enqueue("X")
        assertEquals(listOf("A", "X", "M1", "M2", "C1", "C2"), after.queue.map { it.name })
        val retainedKeys = before.queue.map { it.key }.toSet()
        assertEquals(before.queue, after.queue.filter { it.key in retainedKeys })
        assertEquals(before.queue[before.position], after.queue[after.position])
        assertEquals(before.elapsed, after.elapsed, 0.0)
        assertEquals(before.occurrence, after.occurrence)
        assertEquals(before.context, after.context)
        assertEquals(before.shuffle, after.shuffle)
    }

    @Test fun consumedManualOccurrencesStayRetiredAcrossUnsampledPreviousAndReturn() = runBlocking {
        // No looper pumping, tier planner, enqueue or exported snapshot between these actual
        // callbacks. The deferred prune would otherwise see only the final return to A.
        output.seekToNextMediaItem(); assertCurrent("M1")
        output.seekToNextMediaItem(); assertCurrent("M2")
        output.seekToPreviousMediaItem(); assertCurrent("M1")
        output.seekToPreviousMediaItem(); assertCurrent("A")
        assertFreshManualPrecedesRetiredOccurrences()
    }

    @Test fun skippingPastManualOccurrencesAlsoRetiresTheirProvenanceBeforeReturn() = runBlocking {
        seekTo("M2")
        seekTo("A")
        assertFreshManualPrecedesRetiredOccurrences()
    }

    @Test fun returningFromFirstManualKeepsTheStillPendingSecondManualInTheFifoTier() = runBlocking {
        output.seekToNextMediaItem(); assertCurrent("M1")
        output.seekToPreviousMediaItem(); assertCurrent("A")
        val after = enqueue("X")
        assertEquals(listOf("A", "M1", "M2", "X", "C1", "C2"), after.queue.map { it.name })
    }

    @Test fun exactImportStillResetsManualProvenanceWithoutChangingItsTraversal() = runBlocking {
        val before = endpoint.exportSnapshot()
        val imported = endpoint.activate(before, permit)
        assertEquals("ok", imported.status)
        assertEquals(before, imported.snapshot)
        assertFreshManualPrecedesRetiredOccurrences()
    }
}
