package dev.spofify.mobile.playback

import android.app.ForegroundServiceStartNotAllowedException
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import dev.spofify.mobile.AppGraph
import dev.spofify.mobile.LoginStore
import dev.spofify.mobile.RememberedLogin
import dev.spofify.mobile.SpofifyApplication
import dev.spofify.mobile.core.*
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Production AppGraph -> PlaybackDevicesApi -> coordinator -> enabled real service endpoint.
 * Only transport is synthetic loopback; no capability overrides, fake endpoint, live account,
 * audio source or native-device claim. Foreground denial is intentional and must be published.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SpofifyApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
class AppGraphPlaybackParticipationTest {
    private class Sessions : SessionStore {
        private var value: StoredSession? = null
        override fun read() = value
        override fun write(value: StoredSession?) { this.value = value }
    }
    private class Logins : LoginStore {
        override fun read(): RememberedLogin? = null
        override fun write(value: RememberedLogin?) = Unit
    }

    private val seed = PlaybackSnapshot(
        queue = listOf(PlaybackTrack("same-song", "Prepared song", emptyList(), "Album", "album", 120.0,
            entryId = "manual-occurrence", key = 71, order = 3)),
        position = 0, context = PlaybackContext("playlist", "manual", "Original context", 9),
        elapsed = 31.25, duration = 120.0, playing = true,
        occurrence = PlaybackOccurrence(71, "same-song", "original-history-event", 17.875, false),
    ).validated()

    private inner class Fixture : Dispatcher() {
        val registrations = AtomicInteger()
        val unexpected = CopyOnWriteArrayList<String>()
        val polls = CopyOnWriteArrayList<JsonObject>()
        val pending = AtomicReference<PlaybackDirective?>()
        @Volatile private var deviceId = ""
        @Volatile private var snapshot: PlaybackSnapshot? = seed
        @Volatile private var sequence = 0L
        private fun view(directives: List<PlaybackDirective> = emptyList()) = PlaybackDevicesView(
            deviceId, if (deviceId.isEmpty()) emptyList() else listOf(PlaybackDevice(deviceId, "Synthetic phone", true)),
            deviceId, 1, if (deviceId.isEmpty()) 0 else 8000, state = snapshot, stateSeq = sequence,
            stateAgeMs = 0, transfer = null, directives = directives, commands = emptyList(),
        )
        @Synchronized override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.method == "POST" && request.path == "/api/auth/login") return response(
                """{"accountId":"fixture-participant","username":"listener","role":"user","csrfToken":"fixture-csrf"}""",
            ).setHeader("Set-Cookie", "spofify_session=fixture-session; Path=/; HttpOnly; Max-Age=3600")
            if (request.path != "/api/me/player") {
                unexpected += "${request.method} ${request.path}"
                return MockResponse().setResponseCode(500)
            }
            if (request.getHeader("Cookie")?.contains("spofify_session=fixture-session") != true ||
                request.method == "POST" && request.getHeader("X-CSRF-Token") != "fixture-csrf") {
                unexpected += "Unauthenticated player request"
                return MockResponse().setResponseCode(401)
            }
            if (request.method == "GET") return response(view().toJson().toString())
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            return when (body.getValue("action").jsonPrimitive.content) {
                "register" -> {
                    deviceId = "phone-${registrations.incrementAndGet()}"
                    snapshot = seed; sequence = 0
                    response(view().toJson().toString())
                }
                "poll" -> {
                    polls += body
                    assertEquals(deviceId, body.getValue("deviceId").jsonPrimitive.content)
                    body["state"]?.let { snapshot = PlaybackSnapshot.fromJson(it.jsonObject) }
                    body["stateSeq"]?.let { sequence = it.jsonPrimitive.long }
                    response(view(listOfNotNull(pending.getAndSet(null))).toJson().toString())
                }
                "leave" -> response(view().copy(deviceId = "", devices = emptyList(), activeDeviceId = "", leaseMs = 0).toJson().toString())
                else -> {
                    unexpected += "Unexpected player action"
                    MockResponse().setResponseCode(500)
                }
            }
        }
        private fun response(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
        fun states() = polls.mapNotNull { it["state"]?.jsonObject?.let(PlaybackSnapshot::fromJson) }
        fun acked(id: String) = ackStatus(id) != null
        fun ackStatus(id: String) = polls.asSequence().flatMap { it.getValue("acks").jsonArray.asSequence() }
            .map { it.jsonObject }.firstOrNull { it.getValue("deliveryId").jsonPrimitive.content == id }
            ?.getValue("status")?.jsonPrimitive?.content
    }

    private fun TestScope.await(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            runCurrent()
            shadowOf(Looper.getMainLooper()).idleFor(10, java.util.concurrent.TimeUnit.MILLISECONDS)
            Thread.sleep(5)
        }
        runCurrent()
        assertTrue(description, condition())
    }

    private fun longDerived(length: Int): PlaybackSnapshot {
        val context = PlaybackContext("derived", "rules", "Original rules", 7, "original-fence")
        val tracks = (0 until 1000).map { index -> PlaybackTrack("duplicate", "Song", emptyList(), "Album", "album", 120.0,
            targetId = context.id, occurrenceId = "x".repeat(length) + "-$index", playbackContext = context,
            key = index + 1L, order = (999 - index).toLong()) }
        return seed.copy(queue = tracks, position = 17, context = context, shuffle = true,
            occurrence = PlaybackOccurrence(18, "duplicate", "original-history-event", 17.875, true)).paused().validated()
    }

    @Test fun productionProtocolRejectsOversizedPrepareAndActivationBeforeChangingFullLongDerivedQueue() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val app = ApplicationProvider.getApplicationContext<SpofifyApplication>()
        app.graph.clear()
        val graph = AppGraph(app, Sessions(), Logins())
        SpofifyApplication::class.java.getDeclaredField("graph").apply { isAccessible = true; set(app, graph) }
        val fixture = Fixture()
        val web = MockWebServer().apply { dispatcher = fixture; start(InetAddress.getByName("127.0.0.1"), 0) }
        var service: ServiceController<PlaybackService>? = null
        try {
            graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}")).login("listener", "synthetic-password")
            val owner = requireNotNull(graph.playbackCoordinator)
            service = Robolectric.buildService(PlaybackService::class.java).create()
            await("Actual service registers") { owner.state.value.connected }
            val endpoint = Media3PlaybackEndpoint()
            val supported = longDerived(1400).publicationValidated()
            val oversized = longDerived(4080)
            assertEquals(1000, supported.queue.size)
            assertTrue(oversized.toJson().toString().toByteArray().size > 2 * 1024 * 1024)
            owner.localAction { permit -> endpoint.activate(supported, permit) }
            await("Full long derived state must publish exactly through the production API") { fixture.states().lastOrNull() == supported }
            assertEquals(supported, endpoint.exportSnapshot())

            fixture.pending.set(PlaybackDirective("oversized-prepare", 1, "prepare", state = oversized))
            owner.retry(); await("Oversized preparation is delivered") { fixture.pending.get() == null }
            owner.retry(); await("Oversized preparation must ACK rejected, not mutate") { fixture.acked("oversized-prepare") }
            assertEquals("rejected", fixture.ackStatus("oversized-prepare"))
            assertEquals(supported, endpoint.exportSnapshot())

            fixture.pending.set(PlaybackDirective("oversized-activate", 1, "activate", state = oversized))
            owner.retry(); await("Oversized activation is delivered") { fixture.pending.get() == null }
            owner.retry(); await("Oversized activation must ACK rejected with prior exact state") { fixture.acked("oversized-activate") }
            assertEquals("rejected", fixture.ackStatus("oversized-activate"))
            assertEquals(supported, endpoint.exportSnapshot())
            assertEquals(supported, fixture.states().last())
            assertTrue(fixture.polls.all { it.toString().toByteArray().size <= 2 * 1024 * 1024 })
            assertTrue("No audio/history/refetch: ${fixture.unexpected}", fixture.unexpected.isEmpty())
        } finally {
            graph.clear(); service?.destroy(); runCurrent(); web.shutdown(); Dispatchers.resetMain()
        }
    }

    @Test fun enabledProductionGraphRegistersOnlyWithServiceExecutesCommandsAndRecreatesSilent() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val app = ApplicationProvider.getApplicationContext<SpofifyApplication>()
        app.graph.clear()
        val graph = AppGraph(app, Sessions(), Logins())
        SpofifyApplication::class.java.getDeclaredField("graph").apply { isAccessible = true; set(app, graph) }
        val fixture = Fixture()
        val web = MockWebServer().apply { dispatcher = fixture; start(InetAddress.getByName("127.0.0.1"), 0) }
        var service: ServiceController<PlaybackService>? = null
        try {
            assertTrue(Media3PlaybackEndpoint().supportsExactHandoff)
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            client.login("listener", "synthetic-password")
            val owner = requireNotNull(graph.playbackCoordinator)
            runCurrent()
            assertEquals("Login/UI presence alone must not advertise a physical receiver", 0, fixture.registrations.get())
            service = Robolectric.buildService(PlaybackService::class.java).create()
            await("Production service must register via its AppGraph callback") { owner.state.value.connected }
            assertEquals(1, fixture.registrations.get())
            val raw = PlaybackService::class.java.getDeclaredField("player").let {
                it.isAccessible = true; it.get(service.get()) as ExoPlayer
            }
            assertEquals(0, raw.mediaItemCount)
            assertFalse(raw.playWhenReady)
            assertNull("Registration metadata must not grant a restored traversal", owner.currentAuthority())

            // Enqueue initializes the server's playing metadata PAUSED, preserves its occurrence,
            // and adds a duplicate without opening audio or earning imported-position history.
            val track = seed.toJson().getValue("queue").jsonArray.single().jsonObject
            owner.remoteCommand(PlaybackDeviceCommand.musical("enqueue", track = track, first = true))
            await("Real enqueue result must be published") { fixture.states().any { it.queue.size == 2 } }
            val queued = fixture.states().last()
            assertFalse(queued.playing)
            assertEquals(seed.occurrence, queued.occurrence)
            assertEquals(seed.elapsed, queued.elapsed, 0.0)
            assertEquals(seed.queue.single().key, queued.queue.first().key)
            assertNotEquals(queued.queue[0].key, queued.queue[1].key)
            assertEquals(Player.STATE_IDLE, raw.playbackState)

            // Positive Play of the authoritative current entry resumes/no-ops; no playlist GET,
            // replacement traversal or imported history credit. Android denial is honest state.
            shadowOf(service.get()).setThrowInStartForeground(ForegroundServiceStartNotAllowedException("Synthetic restriction"))
            owner.remoteCommand(PlaybackDeviceCommand.musical("play", track = track, context = seed.context,
                entryId = seed.queue.single().entryId))
            await("Real blocked outcome must be published") { fixture.states().lastOrNull()?.error == "blocked" }
            val blocked = fixture.states().last()
            assertEquals(queued.queue, blocked.queue)
            assertEquals(seed.occurrence, blocked.occurrence)
            assertFalse(raw.playWhenReady)

            fixture.pending.set(PlaybackDirective("server-pause", 1, "command", command = PlaybackDeviceCommand.transport("pause").json))
            owner.retry()
            await("Incoming Pause must reach the real service") { fixture.pending.get() == null }
            owner.retry()
            await("Execution ACK must return over the authenticated protocol") { fixture.acked("server-pause") }
            assertFalse(raw.playWhenReady)

            fixture.pending.set(PlaybackDirective("server-close", 1, "command", command = PlaybackDeviceCommand.transport("close").json))
            owner.retry()
            await("Close must publish ACK and dispose through AppGraph callback") {
                fixture.acked("server-close") && !owner.state.value.connected
            }
            val close = fixture.polls.single { body -> body.getValue("acks").jsonArray.any {
                it.jsonObject.getValue("deliveryId").jsonPrimitive.content == "server-close"
            } }
            assertEquals(PlaybackSnapshot(), PlaybackSnapshot.fromJson(close.getValue("state").jsonObject))
            assertTrue(shadowOf(service.get()).isStoppedBySelf)
            service.destroy(); service = null

            service = Robolectric.buildService(PlaybackService::class.java).create()
            await("New service must register a fresh session-scoped device") { fixture.registrations.get() == 2 && owner.state.value.connected }
            assertEquals("phone-2", owner.state.value.deviceId)
            assertEquals(PlaybackSnapshot(), PlaybackService.exportLocalSnapshot())
            assertNull(owner.currentAuthority())
            assertTrue("No audio/history/catalog/refetch routes permitted: ${fixture.unexpected}", fixture.unexpected.isEmpty())
        } finally {
            graph.clear()
            service?.destroy()
            runCurrent()
            web.shutdown()
            Dispatchers.resetMain()
        }
    }
}
