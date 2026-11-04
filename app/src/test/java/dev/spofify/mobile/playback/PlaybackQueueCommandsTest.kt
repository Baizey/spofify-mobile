package dev.spofify.mobile.playback

import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import dev.spofify.mobile.AppGraph
import dev.spofify.mobile.LoginStore
import dev.spofify.mobile.RememberedLogin
import dev.spofify.mobile.SpofifyApplication
import dev.spofify.mobile.core.*
import dev.spofify.mobile.feature.devices.PlaybackAuthority
import dev.spofify.mobile.feature.devices.PlaybackCoordinator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
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

/** Queue-specific service/connection commands extracted from the recovery media fixture.
 * No media-key dispatch, trust shadow or remote session projection is required here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SpofifyApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackQueueCommandsTest {
    private lateinit var web: MockWebServer
    private lateinit var graph: AppGraph
    private lateinit var client: SpofifyClient
    private var serviceController: ServiceController<PlaybackService>? = null
    private val endpoint = Media3PlaybackEndpoint()
    private val requests = mutableListOf<PlaybackDeviceCommand>()
    private val published = mutableListOf<PlaybackSnapshot>()
    private var remotePlaying = false
    private var remoteElapsed = 41.0
    private var remoteDuration = 120.0
    private var remoteOutcome = "ok"
    private var latestCommand: String? = null

    @Before fun setUp() = runBlocking {
        System.getenv("SPOFIFY_TEST_TMPDIR")?.let { expected ->
            assertEquals("Verify the forked test JVM, not only Gradle/TMPDIR", expected, System.getProperty("java.io.tmpdir"))
        }
        val app = ApplicationProvider.getApplicationContext<SpofifyApplication>()
        app.graph.clear()
        graph = AppGraph(app, object : SessionStore {
            private var session: StoredSession? = null
            override fun read() = session
            override fun write(value: StoredSession?) { session = value }
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
        client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
        PlaybackService.onServiceActiveChanged = null // No real player registration or polling.
        client.login("listener", "synthetic-password")
        assertEquals("/api/auth/login", web.takeRequest(1, TimeUnit.SECONDS)?.path)
        serviceController = Robolectric.buildService(PlaybackService::class.java).create()
        assertEquals("ok", endpoint.activate(prepared(), PlaybackAuthority(23, 4) { true }).status)
    }

    @After fun tearDown() {
        PlaybackService.onServiceActiveChanged = null
        try { serviceController?.destroy() }
        finally { try { if (::graph.isInitialized) graph.clear() } finally { if (::web.isInitialized) web.shutdown() } }
    }

    private val service get() = requireNotNull(serviceController).get()
    private val session: MediaSession get() = PlaybackService::class.java.getDeclaredField("session").let {
        it.isAccessible = true; it.get(service) as MediaSession
    }
    private val output: ExoPlayer get() = PlaybackService::class.java.getDeclaredField("player").let {
        it.isAccessible = true; it.get(service) as ExoPlayer
    }
    private fun prepared() = PlaybackSnapshot(
        queue = listOf(91L to 2L, 7L to 0L, 32L to 1L).map { (key, order) ->
            PlaybackTrack("duplicate-track", "Occurrence $key", emptyList(), "Album", "album", 120.0, key = key, order = order)
        }, position = 1, elapsed = 17.25, duration = 120.0, shuffle = true,
        occurrence = PlaybackOccurrence(7, "duplicate-track", "retained-history", 3.25, false),
    ).validated()

    private fun TestScope.remoteOwner(output: String = "speaker", initial: PlaybackSnapshot? = null): PlaybackCoordinator {
        graph.playbackCoordinator!!.close()
        fun view(outcome: PlaybackOutcome? = latestCommand?.let { PlaybackOutcome(it, remoteOutcome) }) = PlaybackDevicesView("phone",
            listOf(PlaybackDevice("phone", "Phone", true), PlaybackDevice("speaker", "Speaker", true)),
            output, 1, 8000, state = initial ?: prepared().copy(playing = remotePlaying, elapsed = remoteElapsed, duration = remoteDuration),
            stateSeq = 1, stateAgeMs = 0, transfer = null, directives = emptyList(), commands = listOfNotNull(outcome))
        val protocol = object : PlaybackDevicesProtocol {
            override suspend fun register(name: String) = view()
            override suspend fun view() = view()
            override suspend fun poll(deviceId: String, epoch: Long, stateSeq: Long?, state: PlaybackSnapshot?, acks: List<PlaybackAck>): PlaybackDevicesView {
                state?.let(published::add)
                return view()
            }
            override suspend fun rename(deviceId: String, name: String) = view()
            override suspend fun command(deviceId: String, epoch: Long, commandId: String, command: PlaybackDeviceCommand): PlaybackDevicesView {
                requests += command
                latestCommand = commandId
                return view(PlaybackOutcome(commandId, remoteOutcome))
            }
            override suspend fun transfer(deviceId: String, epoch: Long, commandId: String, targetDeviceId: String, selectionSeq: Long) = view()
            override suspend fun leave(deviceId: String) = Unit
        }
        val owner = PlaybackCoordinator(protocol, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        AppGraph::class.java.getDeclaredField("playbackCoordinator").apply { isAccessible = true; set(graph, owner) }
        owner.setServiceActive(true)
        runCurrent()
        return owner
    }

    @Test fun explicitOccurrencePlayNextPromotesOnlyThatOccurrenceAndKeepsCurrentAudioState() = runTest {
        val base = prepared()
        val extended = base.copy(queue = base.queue + listOf(45L, 60L).mapIndexed { index, key ->
            base.queue.last().copy(key = key, order = index + 3L, name = "Future $key")
        })
        remoteOwner(output = "phone", initial = extended)
        graph.playbackCoordinator!!.remoteCommand(PlaybackDeviceCommand.transport("pause")); runCurrent()
        val before = endpoint.exportSnapshot()
        val future = PlaybackService.upcomingEntries()
        assertEquals(3, future.size)
        val selected = future.last().occurrenceId
        val other = future.dropLast(1).map { it.occurrenceId }
        val key = requireNotNull(PlaybackService.interactionKey())
        var result: PlaybackActionResult? = null
        PlaybackService.playNextOccurrence(key, selected, onResult = { result = it }); runCurrent()
        assertEquals(PlaybackActionStatus.ADDED, result?.status)
        val after = endpoint.exportSnapshot()
        assertEquals(selected, PlaybackService.upcomingEntries().first().occurrenceId)
        assertEquals(before.queue.map { it.key }.toSet(), after.queue.map { it.key }.toSet())
        assertEquals(before.queue[before.position].key, after.queue[after.position].key)
        assertEquals(before.elapsed, after.elapsed, 0.0)
        assertEquals(before.occurrence, after.occurrence)
        assertEquals(before.shuffle, after.shuffle)
        assertEquals(before.repeat, after.repeat)
        val tier = PlaybackService::class.java.getDeclaredField("manualTier").let {
            it.isAccessible = true; it.get(service) as PlaybackManualTier
        }
        assertTrue(tier.contains(selected))
        other.forEach { assertFalse("Explicit Play next must not adopt the rest of the future as manual", tier.contains(it)) }
        assertFalse(output.playWhenReady)
    }

    private fun remoteNext(results: MutableList<PlaybackActionResult>, isCurrent: () -> Boolean = { true }) {
        val queue = dev.spofify.mobile.core.PlaybackQueue(listOf(LibraryItem(kind = "track", id = "remote-next",
            name = "Prepared remote selection", hasAudio = true)), total = 1)
        PlaybackService.performPreparedAction(queue, client, PlaybackListAction.PLAY_NEXT, expectedKey = null,
            startTrackId = null, startEntryId = null, context = null, isCurrent = isCurrent, onResult = results::add)
    }

    @Test fun explicitRemotePlayNextSendsOneEnqueueAndWaitsForTerminalAck() = runTest {
        remoteOutcome = "pending"
        remoteOwner()
        val before = endpoint.exportSnapshot()
        val results = mutableListOf<PlaybackActionResult>()
        remoteNext(results); runCurrent()
        assertEquals(1, requests.size)
        assertEquals("enqueue", requests.single().type)
        assertEquals("true", requests.single().json["first"].toString())
        assertTrue("Pending is not confirmed enqueue success", results.isEmpty())
        remoteOutcome = "ok"
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf(PlaybackActionResult(PlaybackActionStatus.ADDED, 1)), results)
        advanceTimeBy(9_000); runCurrent()
        assertEquals(1, results.size)
        assertEquals(1, requests.size)
        assertEquals(before, endpoint.exportSnapshot())
        assertFalse(output.playWhenReady)
    }

    @Test fun rejectedOrTimedOutRemoteEnqueueNeverReportsAddedOrRetries() = runTest {
        remoteOutcome = "rejected"
        remoteOwner()
        val rejected = mutableListOf<PlaybackActionResult>()
        remoteNext(rejected); runCurrent()
        assertEquals(listOf(PlaybackActionResult(PlaybackActionStatus.UNAVAILABLE)), rejected)
        remoteOutcome = "pending"
        val timedOut = mutableListOf<PlaybackActionResult>()
        remoteNext(timedOut); runCurrent()
        advanceTimeBy(8_001); runCurrent()
        assertEquals(listOf(PlaybackActionResult(PlaybackActionStatus.UNAVAILABLE)), timedOut)
        assertEquals(2, requests.size)
        assertFalse(output.playWhenReady)
    }

    @Test fun unchangedOutputSelectionCompletesOldRemoteObserverBeforeAcceptingSuccessor() = runTest {
        remoteOutcome = "pending"
        val owner = remoteOwner()
        val first = mutableListOf<PlaybackActionResult>()
        remoteNext(first); runCurrent()
        assertTrue(first.isEmpty())
        assertEquals(1, requests.size)
        owner.select("speaker") // Same owner/epoch; no authority fence is required by this selection.
        runCurrent()
        assertEquals(listOf(PlaybackActionResult(PlaybackActionStatus.UNAVAILABLE)), first)
        remoteOutcome = "ok"
        val second = mutableListOf<PlaybackActionResult>()
        remoteNext(second); runCurrent()
        assertEquals(listOf(PlaybackActionResult(PlaybackActionStatus.ADDED, 1)), second)
        advanceTimeBy(9_000); runCurrent()
        assertEquals("The old timeout/ACK must not touch the successor callback", 1, second.size)
        assertEquals("Every retired caller completes exactly once", 1, first.size)
        assertEquals("No automatic command replay", 2, requests.size)
    }

    @Test fun staleRemotePlayNextIsCancelledBeforeWireDispatchAndUnsupportedAddRemainsHonest() = runTest {
        remoteOwner()
        val results = mutableListOf<PlaybackActionResult>()
        var alive = true
        remoteNext(results) { alive }
        alive = false
        runCurrent()
        assertEquals(listOf(PlaybackActionResult(PlaybackActionStatus.STALE)), results)
        assertTrue(requests.isEmpty())
        val queue = dev.spofify.mobile.core.PlaybackQueue(listOf(LibraryItem(kind = "track", id = "remote-add",
            name = "Unsupported FIFO add", hasAudio = true)), total = 1)
        PlaybackService.performPreparedAction(queue, client, PlaybackListAction.ADD_TO_QUEUE, expectedKey = null,
            startTrackId = null, startEntryId = null, context = null, onResult = results::add)
        runCurrent()
        assertEquals(PlaybackActionStatus.UNSUPPORTED_REMOTE, results.last().status)
        assertTrue(requests.isEmpty())
        remoteNext(results); runCurrent()
        assertEquals(PlaybackActionStatus.ADDED, results.last().status)
        assertEquals(1, requests.size)
    }

    private fun connectedUi(): PlaybackConnection {
        val future = MediaController.Builder(service, session.token).buildAsync()
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        val media = future.get(1, TimeUnit.SECONDS)
        return PlaybackConnection(service).also { connection ->
            PlaybackConnection::class.java.getDeclaredField("controller").apply { isAccessible = true; set(connection, media) }
        }
    }

    @Test fun actualConnectionAllowsOnlyRepresentableRemotePreparedPlayNextAndWaitsForAck() = runTest {
        remoteOutcome = "pending"
        remoteOwner()
        val connection = connectedUi()
        try {
            val queue = dev.spofify.mobile.core.PlaybackQueue(listOf(LibraryItem(kind = "track", id = "connection-next",
                name = "Connection selection", hasAudio = true)), total = 1)
            val results = mutableListOf<PlaybackActionResult>()
            connection.performPreparedAction(queue, client, PlaybackListAction.PLAY_NEXT, expectedKey = null, onResult = { results += it })
            shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS); runCurrent()
            assertEquals(1, requests.size)
            assertEquals("enqueue", requests.single().type)
            assertTrue(results.isEmpty())
            remoteOutcome = "ok"
            advanceTimeBy(1_000); runCurrent()
            assertEquals(listOf(PlaybackActionResult(PlaybackActionStatus.ADDED, 1)), results)
            connection.performPreparedAction(queue, client, PlaybackListAction.ADD_TO_QUEUE, expectedKey = null, onResult = { results += it })
            shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS); runCurrent()
            assertEquals(PlaybackActionStatus.UNSUPPORTED_REMOTE, results.last().status)
            assertEquals(1, requests.size)
        } finally { connection.release() }
    }

    @Test fun actualConnectionPromotesAnExactRemoteOccurrenceWithOneAcknowledgedMove() = runTest {
        val base = prepared()
        remoteOwner(initial = base.copy(queue = base.queue + base.queue.last().copy(key = 45, order = 3)))
        val connection = connectedUi()
        try {
            val key = PlaybackConnection::class.java.getDeclaredMethod("currentRemoteInteractionKey").let {
                it.isAccessible = true; it.invoke(connection) as PlaybackInteractionKey
            }
            val state = requireNotNull(graph.playbackCoordinator!!.state.value.view?.state)
            val selected = state.queue.last()
            val results = mutableListOf<PlaybackActionResult>()
            connection.playNextOccurrence(key, PlaybackSnapshotCodec.occurrenceId(selected), onResult = { results += it })
            shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS); runCurrent()
            assertEquals(listOf(PlaybackActionResult(PlaybackActionStatus.ADDED, 1)), results)
            assertEquals(1, requests.size)
            assertEquals(PlaybackDeviceCommand.queue("move", selected.key, "next").json, requests.single().json)
        } finally { connection.release() }
    }

}
