package dev.spofify.mobile.playback

import android.app.ActivityManager
import android.media.AudioManager
import android.os.Looper
import android.os.Process
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import dev.spofify.mobile.AppGraph
import dev.spofify.mobile.LibraryViewModel
import dev.spofify.mobile.LoginStore
import dev.spofify.mobile.RememberedLogin
import dev.spofify.mobile.SpofifyApplication
import dev.spofify.mobile.core.*
import dev.spofify.mobile.feature.devices.PlaybackCoordinator
import dev.spofify.mobile.feature.devices.PlaybackAuthority
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.runBlocking
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
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Real connection/coordinator/service/ExoPlayer queues and asynchronous result delivery.
 * The active-state seam changes ONLY reads of transport intent, never queue operations or
 * callbacks. No source is prepared/opened and these fixtures make no audible/native claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SpofifyApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackListActionServiceTest {
    private lateinit var graph: AppGraph
    private lateinit var client: SpofifyClient
    private lateinit var web: MockWebServer
    private lateinit var service: ServiceController<PlaybackService>
    private lateinit var actualPlayer: ExoPlayer
    private val endpoint = Media3PlaybackEndpoint()
    private val outputCalls = mutableListOf<String>()
    private val commands = mutableListOf<PlaybackDeviceCommand>()
    private var connection: PlaybackConnection? = null
    private var owner: PlaybackCoordinator? = null
    private var pollGate: CompletableDeferred<Unit>? = null
    @Volatile private var derivedResponse: String? = null
    private var expectedRequests = 1
    private val preparationStarted = CountDownLatch(1)
    private val preparationRelease = CountDownLatch(1)
    private var remoteOutcome = "pending"
    private var remoteCommandId: String? = null
    private var remoteState: PlaybackSnapshot? = null
    private var remoteEpoch = 1L

    private class Sessions : SessionStore {
        private var stored: StoredSession? = null
        override fun read() = stored
        override fun write(value: StoredSession?) { stored = value }
    }
    private class Logins : LoginStore {
        override fun read(): RememberedLogin? = null
        override fun write(value: RememberedLogin?) = Unit
    }

    @Before fun setUp() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<SpofifyApplication>()
        app.graph.clear()
        graph = AppGraph(app, Sessions(), Logins())
        SpofifyApplication::class.java.getDeclaredField("graph").apply { isAccessible = true; set(app, graph) }
        web = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.method == "POST" && request.path == "/api/auth/login")
                        MockResponse().setHeader("Content-Type", "application/json")
                            .setHeader("Set-Cookie", "spofify_session=synthetic; Path=/; HttpOnly; Max-Age=3600")
                            .setBody("""{"accountId":"fixture","username":"listener","role":"user","csrfToken":"synthetic"}""")
                    else if (request.path == "/api/library/albums/delayed/playback") {
                        preparationStarted.countDown()
                        check(preparationRelease.await(5, TimeUnit.SECONDS)) { "Synthetic preparation gate timed out" }
                        MockResponse().setHeader("Content-Type", "application/json").setBody(
                            """{"items":[{"kind":"track","id":"X","name":"X","hasAudio":true}],"total":1}""")
                    }
                    else if (request.method == "POST" && request.path == "/api/me/playlists/rules/playback" && derivedResponse != null) {
                        val body = requireNotNull(derivedResponse); derivedResponse = null
                        MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                    } else MockResponse().setResponseCode(500)
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
        PlaybackService.onServiceActiveChanged = null
        client.login("listener", "synthetic")
        assertEquals("/api/auth/login", web.takeRequest(1, TimeUnit.SECONDS)?.path)
        service = Robolectric.buildService(PlaybackService::class.java).create()
        actualPlayer = playerField.get(service.get()) as ExoPlayer
    }

    @After fun tearDown() {
        var failure: Throwable? = null
        fun cleanup(block: () -> Unit) {
            try { block() } catch (error: Throwable) {
                val previous = failure
                if (previous == null) failure = error else previous.addSuppressed(error)
            }
        }
        // Every owned resource is released even if a safety assertion or earlier teardown
        // fails. Preserve all failures rather than hiding one or leaking a fixture server.
        preparationRelease.countDown()
        cleanup { pollGate?.complete(Unit) }
        cleanup { connection?.release() }
        cleanup { owner?.close() }
        PlaybackService.onServiceActiveChanged = null
        if (::service.isInitialized) {
            cleanup { if (::actualPlayer.isInitialized) playerField.set(service.get(), actualPlayer) }
            cleanup { service.destroy() }
        }
        cleanup { if (::graph.isInitialized) graph.clear() }
        if (::web.isInitialized) {
            cleanup { assertEquals("Only declared synthetic preparation/login requests", expectedRequests, web.requestCount) }
            cleanup { web.shutdown() }
        }
        failure?.let { throw it }
    }

    private val playerField get() = PlaybackService::class.java.getDeclaredField("player").apply { isAccessible = true }

    private fun track(id: String, key: Long, entry: String = "") =
        PlaybackTrack(id, id, emptyList(), "Album", "album", 120.0, entryId = entry, key = key, order = key - 1)

    private fun contextQueue(size: Int = 4, position: Int = 1) = PlaybackSnapshot(
        queue = (1..size).map { track(if (it == 1) "played" else if (it == 2) "A" else "C${it - 2}", it.toLong()) },
        position = position, context = PlaybackContext("album", "album", "Album"), elapsed = 12.375,
        duration = 120.0, occurrence = PlaybackOccurrence((position + 1).toLong(),
            if (position == 1) "A" else if (position == 0) "played" else "C${position - 1}", "event", 3.125, true),
    ).publicationValidated()

    private fun prepared(vararg ids: String) = dev.spofify.mobile.core.PlaybackQueue(
        ids.map { LibraryItem("track", it, it, hasAudio = true) })

    private fun installOwner(scope: CoroutineScope, remote: Boolean = false): PlaybackCoordinator {
        graph.playbackCoordinator!!.close()
        var published = endpoint.exportSnapshot()
        var publishedMetadata = requireNotNull(endpoint.exportQueueIntentState())
        var publishedSequence = 0L
        fun deviceView() = PlaybackDevicesView("phone", listOf(PlaybackDevice("phone", "Phone", true), PlaybackDevice("pc", "PC", true)),
            if (remote) "pc" else "phone", remoteEpoch, 8000, state = if (remote) remoteState ?: contextQueue() else published,
            stateSeq = publishedSequence, stateAgeMs = 0, transfer = null, directives = emptyList(),
            commands = remoteCommandId?.let { listOf(PlaybackOutcome(it, remoteOutcome)) } ?: emptyList(),
            queueIntents = if (remote) null else QueueIntentsView(1, listOf(QueueIntentReceiver("phone", 1)),
                QueueIntentBinding("phone", publishedMetadata.receiverId, publishedMetadata.revision,
                    published.queue.getOrNull(published.position)?.key, published.occurrence?.eventId),
                publishedMetadata, emptyList(), emptyList()))
        val protocol = object : PlaybackDevicesProtocol {
            override suspend fun register(name: String) = deviceView()
            override suspend fun register(name: String, queueIntentsVersion: Int?) = deviceView().also {
                assertEquals(if (remote) null else 1, queueIntentsVersion)
            }
            override suspend fun probeQueueIntents() = deviceView()
            override suspend fun view() = deviceView()
            override suspend fun poll(deviceId: String, epoch: Long, stateSeq: Long?, state: PlaybackSnapshot?, acks: List<PlaybackAck>): PlaybackDevicesView {
                pollGate?.await(); return deviceView()
            }
            override suspend fun poll(deviceId: String, epoch: Long, stateSeq: Long?, state: PlaybackSnapshot?,
                acks: List<PlaybackAck>, queueIntentState: QueueIntentState?): PlaybackDevicesView {
                pollGate?.await()
                if (state != null) {
                    PlaybackPublication.validatePoll(deviceId, epoch, stateSeq, state, acks, queueIntentState)
                    published = state; publishedMetadata = requireNotNull(queueIntentState); publishedSequence = requireNotNull(stateSeq)
                }
                return deviceView()
            }
            override suspend fun rename(deviceId: String, name: String) = deviceView()
            override suspend fun command(deviceId: String, epoch: Long, commandId: String, request: PlaybackDeviceCommand): PlaybackDevicesView {
                commands.add(request); remoteCommandId = commandId; return deviceView()
            }
            override suspend fun transfer(deviceId: String, epoch: Long, commandId: String, targetDeviceId: String, selectionSeq: Long) = deviceView()
            override suspend fun leave(deviceId: String) = Unit
        }
        return PlaybackCoordinator(protocol, endpoint, scope, clockMs = { 0L }).also {
            owner = it
            AppGraph::class.java.getDeclaredField("playbackCoordinator").apply { isAccessible = true; set(graph, it) }
            it.setServiceActive(true)
        }
    }

    private fun connect(): PlaybackConnection {
        val session = PlaybackService::class.java.getDeclaredField("session").let {
            it.isAccessible = true; it.get(service.get()) as MediaSession
        }
        val future = MediaController.Builder(service.get(), session.token).buildAsync()
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        return PlaybackConnection(service.get()).also {
            PlaybackConnection::class.java.getDeclaredField("controller").apply {
                isAccessible = true; set(it, future.get(1, TimeUnit.SECONDS))
            }
            connection = it
            it.connect()
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        }
    }

    private fun setActiveReadState(state: Int = Player.STATE_BUFFERING, playWhenReady: Boolean = true,
        error: ExoPlaybackException? = null) {
        val proxy = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, arguments ->
            when (method.name) {
                "getPlaybackState" -> state
                "getPlayWhenReady" -> playWhenReady
                "getPlayerError" -> error
                "isPlaying" -> false // No fixture audible progress/history credit.
                else -> {
                    if (method.name in setOf("prepare", "play", "pause", "stop") || method.name.startsWith("seekTo")) outputCalls.add(method.name)
                    try { method.invoke(actualPlayer, *(arguments ?: emptyArray())) }
                    catch (error: InvocationTargetException) { throw error.targetException }
                }
            }
        } as ExoPlayer
        playerField.set(service.get(), proxy)
    }

    private fun releaseDispatch() = shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)

    @Test fun fifoExplicitEnqueueAndContextualActiveRequestsPreserveCurrentHistoryAndIdentity() = runTest {
        val coordinator = installOwner(backgroundScope)
        runCurrent()
        val initial = contextQueue()
        coordinator.localAction { endpoint.activate(initial, it) }; runCurrent()
        val controls = connect()
        setActiveReadState()
        val results = mutableListOf<PlaybackActionResult>()
        for (id in listOf("M1", "M2", "X", "Y")) {
            val key = requireNotNull(PlaybackService.interactionKey())
            controls.performPreparedAction(prepared(id), client,
                if (id.startsWith("M")) PlaybackListAction.ADD_TO_QUEUE else PlaybackListAction.CONTEXTUAL,
                key, startTrackId = id, onResult = results::add)
            assertEquals("Not optimistic", results.size, listOf("M1", "M2", "X", "Y").indexOf(id))
            releaseDispatch(); runCurrent()
        }
        assertEquals(List(4) { PlaybackActionStatus.ADDED }, results.map { it.status })
        val after = endpoint.exportSnapshot()
        assertEquals(listOf("played", "A", "M1", "M2", "X", "Y", "C1", "C2"), after.queue.map { it.id })
        assertEquals(initial.queue[initial.position], after.queue[after.position])
        assertEquals(initial.occurrence, after.occurrence)
        assertEquals(initial.elapsed, after.elapsed, 0.0)
        assertEquals(initial.context, after.context)
        assertEquals(initial.shuffle, after.shuffle)
        assertTrue(outputCalls.isEmpty())
        // Re-import conservatively discards local provenance; new native manual goes before
        // all imported future, without guessing from old keys/order/context or changing import.
        coordinator.localAction { endpoint.activate(after.paused(), it) }; runCurrent()
        assertEquals(after.paused(), endpoint.exportSnapshot().paused())
        val key = requireNotNull(PlaybackService.interactionKey())
        controls.performPreparedAction(prepared("Z"), client, PlaybackListAction.ADD_TO_QUEUE, key,
            startTrackId = "Z", onResult = results::add)
        releaseDispatch(); runCurrent()
        assertEquals(listOf("played", "A", "Z", "M1", "M2", "X", "Y", "C1", "C2"), endpoint.exportSnapshot().queue.map { it.id })
    }

    @Test fun explicitAddCapturedWhileBufferingRemainsNonstartingWhenOutputBecomesIdleBeforePostedDispatch() = runTest {
        val coordinator = installOwner(backgroundScope)
        runCurrent()
        coordinator.localAction { endpoint.activate(contextQueue(), it) }; runCurrent()
        val controls = connect()
        setActiveReadState(Player.STATE_BUFFERING, playWhenReady = true)
        val setupResults = mutableListOf<PlaybackActionResult>()
        controls.performPreparedAction(prepared("M1"), client, PlaybackListAction.ADD_TO_QUEUE,
            expectedKey = null, startTrackId = "M1", onResult = setupResults::add)
        repeat(3) { releaseDispatch(); runCurrent() }
        assertEquals(listOf(PlaybackActionStatus.ADDED), setupResults.map { it.status })
        val before = endpoint.exportSnapshot()
        assertTrue("Authoritative output has active buffering intent at request capture", before.playing)
        assertTrue(before.waiting)
        assertEquals(listOf("played", "A", "M1", "C1", "C2"), before.queue.map { it.id })
        val selectedQueue = dev.spofify.mobile.core.PlaybackQueue(listOf(
            LibraryItem("track", "duplicate", "First", hasAudio = true, entryId = "first"),
            LibraryItem("track", "duplicate", "Second", hasAudio = true, entryId = "second")),
            context = LibraryPlaybackContext("playlist", "saved", "Saved"))
        val results = mutableListOf<PlaybackActionResult>()
        outputCalls.clear() // Observe only this explicit Add, not fixture installation.
        controls.performPreparedAction(selectedQueue, client, PlaybackListAction.ADD_TO_QUEUE,
            expectedKey = null, startTrackId = "duplicate", startEntryId = "second", onResult = results::add)
        assertTrue("Posted action must not announce optimistic acceptance", results.isEmpty())
        // State changes after the explicit Add intent was captured, before its posted dispatch.
        // A null expected key deliberately isolates intent semantics from gesture cancellation.
        setActiveReadState(Player.STATE_IDLE, playWhenReady = false)
        repeat(3) { releaseDispatch(); runCurrent() }
        assertEquals(listOf(PlaybackActionStatus.ADDED), results.map { it.status })
        assertEquals(1, results.single().acceptedCount)
        val after = endpoint.exportSnapshot()
        assertEquals(listOf("played", "A", "M1", "duplicate", "C1", "C2"), after.queue.map { it.id })
        val added = after.queue.single { it.id == "duplicate" }
        assertEquals("second", added.entryId)
        assertEquals("saved", added.playbackContext?.id)
        assertEquals(before.position, after.position)
        assertEquals(before.queue[before.position], after.queue[after.position])
        assertEquals(before.occurrence, after.occurrence)
        assertEquals(before.elapsed, after.elapsed, 0.0)
        assertEquals(before.context, after.context)
        assertFalse(after.playing)
        assertFalse(actualPlayer.playWhenReady)
        assertEquals(Player.STATE_IDLE, actualPlayer.playbackState)
        assertTrue("Explicit Add must not prepare/play/pause/stop/seek after becoming idle", outputCalls.isEmpty())
    }

    private fun TestScope.until(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) {
            runCurrent(); releaseDispatch(); Thread.sleep(5)
        }
        runCurrent()
        assertTrue("Timed out waiting for integrated action", condition())
    }

    @Test fun committedContextualFifoSurvivesItsOwnRealKeyChangeAfterDelayedPreparation() = delayedCommittedFifo()

    @Test fun committedFifoDoesNotFollowExternalQueueEditDuringPredecessorPublication() = delayedCommittedFifo("queue")

    @Test fun committedFifoAndFeedbackDoNotFollowNavigationDuringPredecessorPublication() = delayedCommittedFifo("route")

    @Test fun committedFifoAndFeedbackDoNotFollowReauthenticationDuringPredecessorPublication() = delayedCommittedFifo("auth")

    private fun delayedCommittedFifo(interruption: String? = null) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val publication = CompletableDeferred<Unit>()
        try {
            val coordinator = installOwner(backgroundScope)
            runCurrent()
            val initial = contextQueue()
            coordinator.localAction { endpoint.activate(initial, it) }; runCurrent()
            val controls = connect()
            setActiveReadState()
            val model = LibraryViewModel(ApplicationProvider.getApplicationContext(), graph, controls)
            store.put("fifo", model)
            until { model.state.value.session != null && !model.state.value.busy }
            val key = requireNotNull(controls.state.value.interactionKey)
            pollGate = publication
            // Raw High rows need no album fetch. Commit both tickets before dispatch; X's
            // mutation then waits at exact publication while Y retains the original binding.
            model.contextualFrom(client, LibraryItem("track", "X", "X", hasAudio = true, albumId = "delayed"), key)
            model.contextualFrom(client, LibraryItem("track", "Y", "Y", hasAudio = true), key)
            until { endpoint.exportSnapshot().queue.any { it.id == "X" } }
            assertNotSame(key, PlaybackService.interactionKey())
            assertEquals(listOf("played", "A", "X", "C1", "C2"), endpoint.exportSnapshot().queue.map { it.id })
            assertTrue(model.state.value.preparing)
            assertNull("No optimistic feedback while publication is held", model.state.value.notice)
            when (interruption) {
                "queue" -> endpoint.execute(PlaybackDeviceCommand.queue("remove", 4), requireNotNull(coordinator.currentAuthority()))
                "route" -> model.search("another route")
                "auth" -> {
                    assertTrue("The accepted FIFO insertion must not interrupt output before reauthentication", outputCalls.isEmpty())
                    expectedRequests++
                    val originalSession = client.currentSession()
                    client.login("listener", "synthetic")
                    assertEquals(originalSession, client.currentSession())
                    assertNotSame("Equal-valued login is still a new authority", originalSession, client.currentSession())
                }
            }
            pollGate = null
            publication.complete(Unit)
            until { !model.state.value.preparing }
            val after = endpoint.exportSnapshot()
            assertEquals(when (interruption) {
                "queue" -> listOf("played", "A", "X", "C1")
                "route", "auth" -> listOf("played", "A", "X", "C1", "C2")
                else -> listOf("played", "A", "X", "Y", "C1", "C2")
            }, after.queue.map { it.id })
            assertEquals(initial.queue[initial.position], after.queue[after.position])
            assertEquals(initial.occurrence, after.occurrence)
            assertEquals(initial.elapsed, after.elapsed, 0.0)
            assertEquals(when (interruption) {
                "queue" -> PlaybackActionResult(PlaybackActionStatus.STALE).userMessage
                "route", "auth" -> null
                else -> queueIntentMessage(QueueIntentResult("queued", 1, 1, "high", "unchanged", null, ""), PlaybackListAction.QUEUE_HIGH)
            }, model.state.value.notice)
            // A separately held gesture is NOT a committed ticket and never inherits X's
            // receipt. Even after successful FIFO completion it must reject the original key.
            val held = mutableListOf<PlaybackActionResult>()
            controls.contextualOccurrence(key, "wire-3", onResult = held::add)
            releaseDispatch(); runCurrent()
            assertEquals(listOf(PlaybackActionStatus.STALE), held.map { it.status })
            assertEquals(after, endpoint.exportSnapshot())
            if (interruption == "auth") {
                // Session replacement legitimately silences buffered output, even when the
                // renewed session is equal-valued. Stale FIFO work must never restart it.
                assertTrue("Reauthentication must pause and stop the old output", outputCalls.containsAll(listOf("pause", "stop")))
                assertTrue("No stale FIFO prepare/play/seek may follow reauthentication",
                    outputCalls.all { it == "pause" || it == "stop" })
            } else assertTrue("No FIFO action may prepare/play/pause/stop/seek", outputCalls.isEmpty())
        } finally {
            preparationRelease.countDown()
            pollGate = null
            publication.complete(Unit)
            store.clear()
            Dispatchers.resetMain()
        }
    }

    /** Controlled startup timing only: all actual queue/metadata/identity mutations remain on
     * the real service-created ExoPlayer. prepare/play do not open an audio source in this JVM. */
    private inner class StartupOutput {
        var state = Player.STATE_IDLE
        var wantsPlay = false
        var playing = false
        var prepares = 0
        var plays = 0
        val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, arguments ->
            when (method.name) {
                "getPlaybackState" -> state
                "getPlayWhenReady" -> wantsPlay
                "isPlaying" -> playing
                "getPlayerError" -> null
                "prepare" -> { prepares++; state = Player.STATE_BUFFERING; Unit }
                "play" -> { plays++; wantsPlay = true; playing = true; Unit }
                "pause" -> { wantsPlay = false; playing = false; Unit }
                "stop" -> { wantsPlay = false; playing = false; state = Player.STATE_IDLE; Unit }
                "setPlayWhenReady" -> { wantsPlay = arguments!![0] as Boolean; if (!wantsPlay) playing = false; Unit }
                else -> try { method.invoke(actualPlayer, *(arguments ?: emptyArray())) }
                    catch (error: InvocationTargetException) { throw error.targetException }
            }
        } as ExoPlayer
    }

    @Test fun committedContextualFifoFollowsOnlyItsOwnSuccessfulInitialStart() = delayedInitialStartFifo()
    @Test fun committedInitialStartFifoCannotFollowExternalEditWhileStartupIsSuspended() = delayedInitialStartFifo("edit")
    @Test fun committedInitialStartFifoCannotReviveAfterExternalEditAndEqualQueueReturn() = delayedInitialStartFifo("round-trip")
    @Test fun committedInitialStartFifoCannotFollowNavigationDuringStartup() = delayedInitialStartFifo("route")
    @Test fun committedInitialStartFifoCannotFollowReauthenticationDuringStartup() = delayedInitialStartFifo("auth")
    @Test fun committedInitialStartFifoSurvivesTransportCapabilityOnlyChurn() = delayedInitialStartFifo("transport")

    private fun delayedInitialStartFifo(interruption: String? = null) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val coordinator = installOwner(backgroundScope)
            runCurrent()
            val controls = connect() // Truly empty, no installed authority/queue yet.
            shadowOf(service.get().getSystemService(AudioManager::class.java))
                .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            shadowOf(service.get().getSystemService(ActivityManager::class.java)).setProcesses(listOf(
                ActivityManager.RunningAppProcessInfo().apply {
                    pid = Process.myPid()
                    importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                }))
            val startup = StartupOutput()
            playerField.set(service.get(), startup.player)
            val model = LibraryViewModel(ApplicationProvider.getApplicationContext(), graph, controls)
            store.put("initial-fifo", model)
            until { model.state.value.session != null && !model.state.value.busy }
            val key = requireNotNull(controls.state.value.interactionKey)
            // Raw High row preparation is receiver-owned and adds that row only: no album
            // fetch. Commit both local tickets before allowing X's synchronous installation.
            model.contextualFrom(client, LibraryItem("track", "X", "X", hasAudio = true, albumId = "delayed"), key)
            model.contextualFrom(client, LibraryItem("track", "Y", "Y", hasAudio = true), key)
            until { startup.state == Player.STATE_BUFFERING && endpoint.exportSnapshot().queue.map { it.id } == listOf("X") }
            val installedKey = requireNotNull(PlaybackService.interactionKey())
            val installedPermit = PlaybackService::class.java.getDeclaredField("authority").let {
                it.isAccessible = true
                it.get(service.get()) as PlaybackAuthority
            }
            assertNotSame(key, installedKey)
            assertTrue("Startup itself is held, not just HTTP or publication", model.state.value.preparing)
            assertNull(model.state.value.notice)
            when (interruption) {
                "edit", "round-trip" -> {
                    val wire = PlaybackSnapshot(queue = listOf(track("external", 1)), position = 0).toJson()
                    val edited = endpoint.execute(PlaybackDeviceCommand.musical("enqueue",
                        track = wire.getValue("queue").jsonArray.single().jsonObject, first = false),
                        installedPermit).snapshot
                    if (interruption == "round-trip") endpoint.execute(PlaybackDeviceCommand.queue("remove", edited.queue.last().key),
                        installedPermit)
                    assertNotSame(installedKey, PlaybackService.interactionKey())
                }
                "transport" -> {
                    PlaybackService::class.java.getDeclaredField("transportCapabilityRevision").apply {
                        isAccessible = true; setLong(service.get(), getLong(service.get()) + 1)
                    }
                    releaseDispatch(); runCurrent()
                }
                "route" -> model.search("changed route")
                "auth" -> {
                    expectedRequests++
                    client.login("listener", "synthetic")
                }
            }
            startup.state = Player.STATE_READY
            advanceTimeBy(100)
            until { !model.state.value.preparing }
            val after = endpoint.exportSnapshot()
            assertEquals(when (interruption) {
                null, "transport" -> listOf("X", "Y")
                "edit" -> listOf("X", "external")
                else -> listOf("X")
            }, after.queue.map { it.id })
            assertEquals("X", after.queue[after.position].id)
            assertEquals(1, startup.prepares)
            assertEquals(if (interruption == null || interruption == "transport") 1 else 0, startup.plays)
            assertEquals(when (interruption) {
                null, "transport" -> queueIntentMessage(QueueIntentResult("queued", 1, 1, "high", "unchanged", null, ""), PlaybackListAction.QUEUE_HIGH)
                "route", "auth" -> null
                else -> PlaybackActionResult(PlaybackActionStatus.STALE).userMessage
            }, model.state.value.notice)
            // A held non-ticket retains the original empty binding, even on positive completion.
            val held = mutableListOf<PlaybackActionResult>()
            controls.performPreparedAction(prepared("held"), client, PlaybackListAction.CONTEXTUAL, key,
                startTrackId = "held", onResult = held::add)
            releaseDispatch(); runCurrent()
            assertEquals(listOf(PlaybackActionStatus.STALE), held.map { it.status })
            assertEquals(after, endpoint.exportSnapshot())
        } finally {
            preparationRelease.countDown()
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun terminalAddCallbackWaitsForPublicationAndCoordinatorReadinessBeforeChainedFifoAdmission() = runTest {
        val coordinator = installOwner(backgroundScope)
        runCurrent()
        val initial = contextQueue()
        coordinator.localAction { endpoint.activate(initial, it) }; runCurrent()
        val controls = connect()
        setActiveReadState()
        val outcomes = mutableListOf<Pair<String, PlaybackActionResult>>()
        var yIssued = false
        val publication = CompletableDeferred<Unit>()
        pollGate = publication
        try {
            controls.performPreparedAction(prepared("X"), client, PlaybackListAction.ADD_TO_QUEUE,
                requireNotNull(PlaybackService.interactionKey()), startTrackId = "X") { result ->
                outcomes.add("X" to result)
                // Terminal completion is also the admission-readiness boundary. A new explicit
                // action issued here must not bounce off the completed action's pendingCommand.
                // Null deliberately avoids confusing admission readiness with a stale held key.
                yIssued = true
                controls.performPreparedAction(prepared("Y"), client, PlaybackListAction.ADD_TO_QUEUE,
                    expectedKey = null, startTrackId = "Y") { next -> outcomes.add("Y" to next) }
            }
            releaseDispatch(); runCurrent()
            releaseDispatch(); runCurrent()
            assertEquals("X has mutated; its exact result publication is still held",
                listOf("played", "A", "X", "C1", "C2"), endpoint.exportSnapshot().queue.map { it.id })
            assertTrue("No terminal callback before publication and coordinator admission readiness", outcomes.isEmpty())
            assertFalse("The chained Y request cannot issue before X terminal completion", yIssued)
            assertTrue("FIFO additions never prepare/play/pause/stop/seek", outputCalls.isEmpty())
        } finally {
            pollGate = null
            publication.complete(Unit)
        }
        repeat(3) { runCurrent(); releaseDispatch() }
        runCurrent()
        assertEquals(listOf("X", "Y"), outcomes.map { it.first })
        assertEquals(List(2) { PlaybackActionStatus.ADDED }, outcomes.map { it.second.status })
        assertEquals(listOf(1, 1), outcomes.map { it.second.acceptedCount })
        val after = endpoint.exportSnapshot()
        assertEquals(listOf("played", "A", "X", "Y", "C1", "C2"), after.queue.map { it.id })
        assertEquals(initial.queue[initial.position], after.queue[after.position])
        assertEquals(initial.occurrence, after.occurrence)
        assertEquals(initial.elapsed, after.elapsed, 0.0)
        assertTrue("Callback-chained FIFO additions must not interrupt transport", outputCalls.isEmpty())
    }

    @Test fun initialAuthoritativeEmptyKeySurvivesBootstrapAndHighStartsOnlyExactDuplicateRowHonestly() = runTest {
        val coordinator = installOwner(backgroundScope)
        runCurrent()
        val controls = connect()
        val key = requireNotNull(controls.state.value.interactionKey)
        assertEquals(0, actualPlayer.mediaItemCount)
        assertNull(coordinator.currentAuthority()) // No prior Play or separate explicit enqueue.
        shadowOf(service.get()).setThrowInStartForeground(android.app.ForegroundServiceStartNotAllowedException("Synthetic block"))
        val queue = dev.spofify.mobile.core.PlaybackQueue(listOf(
            LibraryItem("track", "duplicate", "First", hasAudio = true, entryId = "first"),
            LibraryItem("track", "duplicate", "Second", hasAudio = true, entryId = "second"),
            LibraryItem("track", "tail", "Tail", hasAudio = true, entryId = "tail")),
            context = LibraryPlaybackContext("playlist", "saved", "Saved"))
        val results = mutableListOf<PlaybackActionResult>()
        controls.performPreparedAction(queue, client, PlaybackListAction.CONTEXTUAL, key,
            startTrackId = "duplicate", startEntryId = "second", onResult = results::add)
        releaseDispatch(); runCurrent()
        assertEquals(listOf(PlaybackActionStatus.BLOCKED), results.map { it.status })
        assertFalse(results.single().userMessage.startsWith("Playing"))
        val after = endpoint.exportSnapshot()
        assertEquals(listOf("second"), after.queue.map { it.entryId })
        assertEquals("second", after.queue[after.position].entryId)
        assertEquals("saved", after.queue.single().playbackContext?.id)
        assertEquals(1, results.single().acceptedCount)
        assertEquals(after.queue.single().key, results.single().queueIntentResult!!.playbackKey)
        assertFalse(after.playing)
        assertFalse(actualPlayer.playWhenReady)
    }

    @Test fun canceledInitialIntentDoesNotInitializePreparedOutputAndCompletesOnce() = runTest {
        val coordinator = installOwner(backgroundScope)
        runCurrent()
        val controls = connect()
        val key = requireNotNull(controls.state.value.interactionKey)
        var live = true
        val results = mutableListOf<PlaybackActionResult>()
        controls.performPreparedAction(prepared("X"), client, PlaybackListAction.CONTEXTUAL, key,
            startTrackId = "X", isCurrent = { live }, onResult = results::add)
        releaseDispatch() // Queued coordinator job has not executed yet.
        live = false
        runCurrent()
        assertEquals(listOf(PlaybackActionStatus.STALE), results.map { it.status })
        assertEquals(0, actualPlayer.mediaItemCount)
        assertNull(coordinator.currentAuthority())
        assertFalse(actualPlayer.playWhenReady)
    }

    @Test fun addOnPausedQueueNeverStartsAndKeepsExactManualEntryAndSourceContext() = runTest {
        val coordinator = installOwner(backgroundScope)
        runCurrent()
        val initial = contextQueue()
        coordinator.localAction { endpoint.activate(initial, it) }; runCurrent()
        val controls = connect()
        val queue = dev.spofify.mobile.core.PlaybackQueue(listOf(
            LibraryItem("track", "duplicate", "First", hasAudio = true, entryId = "first"),
            LibraryItem("track", "duplicate", "Second", hasAudio = true, entryId = "second")),
            context = LibraryPlaybackContext("playlist", "saved", "Saved"))
        val results = mutableListOf<PlaybackActionResult>()
        controls.performPreparedAction(queue, client, PlaybackListAction.ADD_TO_QUEUE,
            requireNotNull(PlaybackService.interactionKey()), startTrackId = "duplicate", startEntryId = "second", onResult = results::add)
        releaseDispatch(); runCurrent()
        val after = endpoint.exportSnapshot()
        assertEquals(listOf(PlaybackActionStatus.ADDED), results.map { it.status })
        assertEquals(listOf("played", "A", "duplicate", "C1", "C2"), after.queue.map { it.id })
        assertEquals("second", after.queue[2].entryId)
        assertEquals("saved", after.queue[2].playbackContext?.id)
        assertEquals(initial.occurrence, after.occurrence)
        assertEquals(initial.elapsed, after.elapsed, 0.0)
        assertEquals(Player.STATE_IDLE, actualPlayer.playbackState)
        assertFalse(actualPlayer.playWhenReady)
    }

    @Test fun activeUpcomingPromotionUsesExactOccurrenceAndNoopDoesNotClaimInsertion() = runTest {
        val coordinator = installOwner(backgroundScope)
        runCurrent()
        coordinator.localAction { endpoint.activate(contextQueue(), it) }; runCurrent()
        val controls = connect()
        setActiveReadState(Player.STATE_READY)
        val results = mutableListOf<PlaybackActionResult>()
        val before = endpoint.exportSnapshot()
        val key = requireNotNull(PlaybackService.interactionKey())
        val selected = "wire-${before.queue.last().key}"
        controls.contextualOccurrence(key, selected, onResult = results::add)
        releaseDispatch(); runCurrent()
        val after = endpoint.exportSnapshot()
        assertEquals(listOf("played", "A", "C2", "C1"), after.queue.map { it.id })
        assertEquals(before.queue.map { it.key }.toSet(), after.queue.map { it.key }.toSet())
        assertEquals(before.occurrence, after.occurrence)
        assertEquals(before.elapsed, after.elapsed, 0.0)
        controls.contextualOccurrence(requireNotNull(PlaybackService.interactionKey()), selected, onResult = results::add)
        releaseDispatch(); runCurrent()
        assertEquals(listOf(PlaybackActionStatus.ADDED, PlaybackActionStatus.NO_OP), results.map { it.status })
        assertFalse(results.last().userMessage.startsWith("Added"))
        assertEquals(after, endpoint.exportSnapshot())
        assertTrue(outputCalls.isEmpty())
    }

    @Test fun remoteContextualAndExplicitFifoRejectWithoutWireOrLocalFallback() = runTest {
        installOwner(backgroundScope, remote = true)
        runCurrent()
        val controls = connect()
        val key = requireNotNull(controls.state.value.interactionKey)
        val results = mutableListOf<PlaybackActionResult>()
        // Shipped enqueue(first=true) can represent explicit remote Play next; C owns its
        // terminal-ACK acceptance tests. Contextual/manual FIFO remains unrepresentable.
        for (action in listOf(PlaybackListAction.CONTEXTUAL, PlaybackListAction.ADD_TO_QUEUE)) {
            controls.performPreparedAction(prepared("X"), client, action, key, startTrackId = "X", onResult = results::add)
            releaseDispatch(); runCurrent()
        }
        controls.contextualOccurrence(key, controls.state.value.upcoming.last().occurrenceId, onResult = results::add)
        releaseDispatch(); runCurrent()
        assertEquals(listOf(PlaybackActionStatus.UNSUPPORTED_REMOTE, PlaybackActionStatus.UNSUPPORTED_REMOTE,
            PlaybackActionStatus.UNSUPPORTED), results.map { it.status })
        assertTrue(results.all { it.userMessage == "Update the selected playback device and server to use queue priorities." })
        assertTrue(commands.isEmpty())
        assertEquals(0, actualPlayer.mediaItemCount)
        assertFalse(actualPlayer.playWhenReady)
    }

    @Test fun largeQueueAppendProtectsEntirePlayedCurrentFutureHistoryAndOrigins() = runTest {
        val coordinator = installOwner(backgroundScope)
        runCurrent()
        val initial = contextQueue(size = 5000, position = 2500)
        coordinator.localAction { endpoint.activate(initial, it) }; runCurrent()
        val controls = connect()
        val results = mutableListOf<PlaybackActionResult>()
        controls.performPreparedAction(prepared("X", "Y", "Z"), client, PlaybackListAction.ADD_TO_QUEUE,
            requireNotNull(PlaybackService.interactionKey()), onResult = results::add)
        releaseDispatch(); runCurrent()
        assertEquals(listOf(PlaybackActionStatus.ADDED), results.map { it.status })
        assertEquals(3, results.single().acceptedCount)
        val after = endpoint.exportSnapshot()
        assertEquals(5003, after.queue.size)
        val originalKeys = initial.queue.map { it.key }.toSet()
        assertEquals(initial.queue, after.queue.filter { it.key in originalKeys })
        assertEquals(initial.queue[initial.position], after.queue[after.position])
        assertEquals(initial.queue.drop(initial.position + 1), after.queue.drop(after.position + 1).filter { it.key in originalKeys })
        assertEquals(initial.occurrence, after.occurrence)
        assertEquals(initial.elapsed, after.elapsed, 0.0)
        assertEquals(listOf("X", "Y", "Z"), after.queue.filter { it.key !in originalKeys }.map { it.id })
        val metadata = endpoint.exportQueueIntentState()!!
        assertEquals(after.queue.map { it.key }, metadata.origins.map { it.key })
        assertEquals(listOf(5001L, 5002L, 5003L), metadata.highKeys)
        assertTrue(metadata.origins.filter { it.key in originalKeys }.all { it.intent == "legacy" })
        assertTrue(metadata.origins.filter { it.key !in originalKeys }.all { it.intent == "high" })
        assertFalse(actualPlayer.playWhenReady)
    }

    @Test fun completeLargeBatchReportsFullCountWithoutPartialAdmission() = runTest {
        val coordinator = installOwner(backgroundScope)
        runCurrent()
        val initial = contextQueue(size = 1, position = 0)
        coordinator.localAction { endpoint.activate(initial, it) }; runCurrent()
        val controls = connect()
        val results = mutableListOf<PlaybackActionResult>()
        val additions = (1..5000).map { "X$it" }
        controls.performPreparedAction(prepared(*additions.toTypedArray()), client, PlaybackListAction.ADD_TO_QUEUE,
            requireNotNull(PlaybackService.interactionKey()), onResult = results::add)
        releaseDispatch(); runCurrent()
        assertEquals(listOf(PlaybackActionStatus.ADDED), results.map { it.status })
        assertEquals(5000, results.single().acceptedCount)
        assertEquals(5000, results.single().queueIntentResult!!.acceptedCount)
        assertEquals("", results.single().queueIntentResult!!.notice)
        val after = endpoint.exportSnapshot()
        assertEquals(5001, after.queue.size)
        assertEquals(initial.queue.single(), after.queue.first())
        assertEquals(additions, after.queue.drop(1).map { it.id })
        assertEquals(initial.occurrence, after.occurrence)
        assertEquals(initial.elapsed, after.elapsed, 0.0)
        assertEquals((2L..5001L).toList(), endpoint.exportQueueIntentState()!!.highKeys)
        assertFalse(actualPlayer.playWhenReady)
    }

    @Test fun thousandEntryQueueStillAcceptsNextOccurrenceWithoutPruning() = runTest {
        val coordinator = installOwner(backgroundScope)
        runCurrent()
        val initial = contextQueue(size = 1000, position = 0)
        coordinator.localAction { endpoint.activate(initial, it) }; runCurrent()
        val controls = connect()
        val metadata = endpoint.exportQueueIntentState()!!
        val results = mutableListOf<PlaybackActionResult>()
        controls.performPreparedAction(prepared("Q"), client, PlaybackListAction.ADD_TO_QUEUE,
            requireNotNull(PlaybackService.interactionKey()), startTrackId = "Q", onResult = results::add)
        releaseDispatch(); runCurrent()
        assertEquals(listOf(PlaybackActionStatus.ADDED), results.map { it.status })
        assertEquals(1, results.single().acceptedCount)
        val after = endpoint.exportSnapshot()
        assertEquals(1001, after.queue.size)
        assertEquals(initial.queue, after.queue.filter { it.key <= 1000L })
        assertEquals(initial.occurrence, after.occurrence)
        assertEquals(initial.elapsed, after.elapsed, 0.0)
        assertEquals(metadata.origins, endpoint.exportQueueIntentState()!!.origins.filter { it.key <= 1000L })
        assertFalse(actualPlayer.playWhenReady)
    }

    @Test fun highOnTrulyEmptyOutputAttemptsInitialSelectionOnceAndPlayNextRemainsDistinctFromFifo() = runTest {
        val coordinator = installOwner(backgroundScope)
        runCurrent()
        val controls = connect()
        val results = mutableListOf<PlaybackActionResult>()
        shadowOf(service.get()).setThrowInStartForeground(android.app.ForegroundServiceStartNotAllowedException("Synthetic block"))
        controls.performPreparedAction(prepared("A", "C"), client, PlaybackListAction.ADD_TO_QUEUE, null, onResult = results::add)
        releaseDispatch(); runCurrent()
        assertEquals(listOf(PlaybackActionStatus.BLOCKED), results.map { it.status })
        assertEquals(2, results.single().acceptedCount)
        assertEquals(listOf("A", "C"), endpoint.exportSnapshot().queue.map { it.id })
        assertFalse(actualPlayer.playWhenReady)
        assertEquals(Player.STATE_IDLE, actualPlayer.playbackState)
        // Explicit Play next inserts immediately after current even when another manual is
        // pending; a subsequent FIFO append goes AFTER both pending manual requests.
        for ((id, action) in listOf("N" to PlaybackListAction.PLAY_NEXT, "F" to PlaybackListAction.ADD_TO_QUEUE)) {
            controls.performPreparedAction(prepared(id), client, action, requireNotNull(PlaybackService.interactionKey()),
                startTrackId = id, onResult = results::add)
            releaseDispatch(); runCurrent()
        }
        assertEquals(listOf("A", "N", "C", "F"), endpoint.exportSnapshot().queue.map { it.id })
        assertFalse(actualPlayer.playWhenReady)
        assertEquals(Player.STATE_IDLE, actualPlayer.playbackState)
        assertNotNull(coordinator.currentAuthority())
    }

    @Test fun pausedEndedIdleAndMediaErrorHighPreservesExactCurrentStateWithoutPrepareResumeOrAdvance() = runTest {
        val coordinator = installOwner(backgroundScope)
        runCurrent()
        val controls = connect()
        shadowOf(service.get()).setThrowInStartForeground(android.app.ForegroundServiceStartNotAllowedException("Synthetic block"))
        val mediaError = ExoPlaybackException.createForSource(
            java.io.IOException("Synthetic media error"), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        val states = listOf(
            Triple(Player.STATE_READY, false, null),
            Triple(Player.STATE_ENDED, true, null),
            Triple(Player.STATE_IDLE, true, null),
            Triple(Player.STATE_READY, true, mediaError), // Error wins even over active-looking state.
            Triple(Player.STATE_IDLE, true, mediaError), // Actual fatal-error idle shape retains error until prepare.
        )
        val results = mutableListOf<PlaybackActionResult>()
        for ((state, playWhenReady, error) in states) {
            playerField.set(service.get(), actualPlayer)
            coordinator.localAction { endpoint.activate(contextQueue(), it) }; runCurrent()
            // Settle this fixture replacement's real Media3 transition callbacks before
            // capturing a gesture key; those callbacks correctly invalidate older bindings.
            repeat(3) { releaseDispatch(); runCurrent() }
            setActiveReadState(state, playWhenReady, error)
            assertSame("The old-item media error is present at the contextual decision", error,
                (playerField.get(service.get()) as ExoPlayer).playerError)
            val beforeAction = endpoint.exportSnapshot()
            outputCalls.clear()
            controls.performPreparedAction(prepared("selected", "selected-context-tail"), client, PlaybackListAction.CONTEXTUAL,
                requireNotNull(PlaybackService.interactionKey()), startTrackId = "selected", onResult = results::add)
            releaseDispatch(); runCurrent()
            assertEquals("State=$state playWhenReady=$playWhenReady error=$error", PlaybackActionStatus.ADDED, results.last().status)
            assertEquals(1, results.last().acceptedCount)
            assertEquals("unchanged", results.last().queueIntentResult!!.playback)
            assertNull(results.last().queueIntentResult!!.playbackKey)
            val after = endpoint.exportSnapshot()
            assertEquals(beforeAction.error, after.error)
            assertEquals(beforeAction.playing, after.playing)
            assertTrue(coordinator.state.value.connected)
            assertEquals(listOf("played", "A", "selected", "C1", "C2"), after.queue.map { it.id })
            assertEquals(beforeAction.queue[beforeAction.position], after.queue[after.position])
            assertEquals(beforeAction.occurrence, after.occurrence)
            assertEquals(beforeAction.elapsed, after.elapsed, 0.0)
            assertTrue("Unchanged-current High never prepares/plays/pauses/stops/seeks", outputCalls.isEmpty())
        }
        assertEquals(states.size, results.size)
    }

    @Test fun idleUpcomingHighPromotesExactOccurrenceWithoutSelectingStartingOrDuplicatingIt() = runTest {
        val coordinator = installOwner(backgroundScope)
        runCurrent()
        val initial = contextQueue()
        coordinator.localAction { endpoint.activate(initial, it) }; runCurrent()
        val controls = connect()
        shadowOf(service.get()).setThrowInStartForeground(android.app.ForegroundServiceStartNotAllowedException("Synthetic block"))
        val results = mutableListOf<PlaybackActionResult>()
        controls.contextualOccurrence(requireNotNull(PlaybackService.interactionKey()), "wire-4", onResult = results::add)
        releaseDispatch(); runCurrent()
        val after = endpoint.exportSnapshot()
        assertEquals(listOf(PlaybackActionStatus.ADDED), results.map { it.status })
        assertEquals(listOf(1L, 2L, 4L, 3L), after.queue.map { it.key })
        assertEquals(initial.queue[initial.position], after.queue[after.position])
        assertEquals(initial.occurrence, after.occurrence)
        assertEquals(initial.elapsed, after.elapsed, 0.0)
        assertFalse(after.playing)
        assertFalse(actualPlayer.playWhenReady)
    }

    @Test fun preparedDerivedDuplicateSelectionEnqueuesOnlyApprovedOccurrenceAndNeverLeaksItIntoEntryId() = runTest {
        val coordinator = installOwner(backgroundScope)
        runCurrent()
        coordinator.localAction { endpoint.activate(contextQueue(), it) }; runCurrent()
        val controls = connect()
        fun item(occurrence: String) = """{"kind":"track","id":"duplicate","name":"Duplicate","artists":[],"albumName":"Album","albumId":"album","durationMs":120000,"hasAudio":true,"occurrenceId":"$occurrence","selectionToken":"returned-$occurrence"}"""
        derivedResponse = """{"context":{"kind":"derived","id":"rules","name":"Rules","revision":5,"fence":"original-fence"},"items":[${item("occ-first")},${item("occ-late")}],"total":2,"limit":2,"truncated":false,"revision":5,"fence":"original-fence","startOccurrenceId":"occ-late","notice":null}"""
        expectedRequests++
        val prepared = DerivedPlaylistsApi(client).playback("rules", 5, "original-fence", "signed-rendered-selection").queue
        assertEquals("/api/me/playlists/rules/playback", web.takeRequest(1, TimeUnit.SECONDS)?.path)
        val results = mutableListOf<PlaybackActionResult>()
        controls.performPreparedAction(prepared, client, PlaybackListAction.ADD_TO_QUEUE,
            requireNotNull(PlaybackService.interactionKey()), startTrackId = "duplicate", startEntryId = "occ-late", onResult = results::add)
        releaseDispatch(); runCurrent()
        assertEquals(listOf(PlaybackActionStatus.ADDED), results.map { it.status })
        val added = endpoint.exportSnapshot().queue.single { it.targetId == "rules" }
        assertEquals("occ-late", added.occurrenceId)
        assertEquals("", added.entryId)
        assertEquals("original-fence", added.playbackContext?.fence)
        assertFalse(actualPlayer.playWhenReady)
        // A selected ID from the wrong prepared occurrence fails without substituting its
        // duplicate track or interpreting a derived occurrence as a manual playlist entry.
        val before = endpoint.exportSnapshot()
        controls.performPreparedAction(prepared, client, PlaybackListAction.ADD_TO_QUEUE,
            requireNotNull(PlaybackService.interactionKey()), startTrackId = "duplicate", startEntryId = "occ-first", onResult = results::add)
        releaseDispatch(); runCurrent()
        assertEquals(PlaybackActionStatus.UNAVAILABLE, results.last().status)
        assertEquals(before, endpoint.exportSnapshot())
    }

    @Test fun oversizedCombinedPublicationRejectsBeforeAnyQueueCurrentOrHistoryMutation() = runTest {
        val coordinator = installOwner(backgroundScope)
        runCurrent()
        fun heavy(id: String, key: Long) = track(id, key).copy(name = "n".repeat(512), albumName = "a".repeat(512),
            artists = listOf(PlaybackArtist("a", "x".repeat(512)), PlaybackArtist("b", "y".repeat(512))))
        val initial = PlaybackSnapshot(queue = (1..500).map { heavy("old-$it", it.toLong()) }, position = 0).publicationValidated()
        coordinator.localAction { endpoint.activate(initial, it) }; runCurrent()
        val controls = connect()
        val batch = dev.spofify.mobile.core.PlaybackQueue((1..500).map {
            PlaybackSnapshotCodec.libraryItem(heavy("new-$it", it.toLong()))
        })
        assertNotNull(PlaybackSnapshotCodec.preparedQueue(batch, null, null, false, null).publicationValidated())
        val before = endpoint.exportSnapshot()
        val results = mutableListOf<PlaybackActionResult>()
        controls.performPreparedAction(batch, client, PlaybackListAction.ADD_TO_QUEUE,
            requireNotNull(PlaybackService.interactionKey()), onResult = results::add)
        releaseDispatch(); runCurrent()
        assertEquals(listOf(PlaybackActionStatus.UNAVAILABLE), results.map { it.status })
        assertEquals(before, endpoint.exportSnapshot())
        assertFalse(actualPlayer.playWhenReady)
        assertEquals(Player.STATE_IDLE, actualPlayer.playbackState)
    }

    @Test fun stalePreparedDispatchAndHostCancellationAfterTransportWaitNeverMutateOrStart() = runTest {
        val coordinator = installOwner(backgroundScope)
        runCurrent()
        coordinator.localAction { endpoint.activate(contextQueue(), it) }; runCurrent()
        val controls = connect()
        val results = mutableListOf<PlaybackActionResult>()
        var live = true
        pollGate = CompletableDeferred()
        coordinator.retry(); runCurrent() // In-flight harmless poll owns serialized transport.
        controls.performPreparedAction(prepared("X"), client, PlaybackListAction.CONTEXTUAL,
            requireNotNull(PlaybackService.interactionKey()), startTrackId = "X", isCurrent = { live }, onResult = results::add)
        releaseDispatch()
        live = false
        pollGate!!.complete(Unit); pollGate = null; runCurrent()
        assertEquals(listOf(PlaybackActionStatus.STALE), results.map { it.status })
        assertEquals(listOf("played", "A", "C1", "C2"), endpoint.exportSnapshot().queue.map { it.id })
        val key = requireNotNull(PlaybackService.interactionKey())
        controls.performPreparedAction(prepared("Y"), client, PlaybackListAction.ADD_TO_QUEUE, key,
            startTrackId = "Y", onResult = results::add)
        releaseDispatch()
        // Actual queue/current identity changes after connection/service checks but before
        // coordinator execution. A saved occurrence/key must not retarget this new state.
        endpoint.execute(PlaybackDeviceCommand.queue("remove", 4), requireNotNull(coordinator.currentAuthority()))
        val before = endpoint.exportSnapshot()
        runCurrent()
        assertEquals(PlaybackActionStatus.STALE, results.last().status)
        assertEquals(before, endpoint.exportSnapshot())
        assertFalse(actualPlayer.playWhenReady)
    }

    @Test fun manualTierSurvivesShuffleToggleRemovalUndoAndClearDeterministically() = runTest {
        val coordinator = installOwner(backgroundScope)
        runCurrent()
        coordinator.localAction { endpoint.activate(contextQueue(), it) }; runCurrent()
        val controls = connect()
        val results = mutableListOf<PlaybackActionResult>()
        for (id in listOf("M1", "M2")) {
            controls.performPreparedAction(prepared(id), client, PlaybackListAction.ADD_TO_QUEUE,
                requireNotNull(PlaybackService.interactionKey()), startTrackId = id, onResult = results::add)
            releaseDispatch(); runCurrent()
        }
        val permit = requireNotNull(coordinator.currentAuthority())
        val shuffled = endpoint.execute(PlaybackDeviceCommand.transport("shuffle"), permit).snapshot
        assertTrue(shuffled.shuffle)
        assertEquals(listOf("M1", "M2"), shuffled.queue.drop(shuffled.position + 1).take(2).map { it.id })
        val unshuffled = endpoint.execute(PlaybackDeviceCommand.transport("shuffle"), permit).snapshot
        assertFalse(unshuffled.shuffle)
        assertEquals(listOf("played", "A", "M1", "M2", "C1", "C2"), unshuffled.queue.map { it.id })
        val removed = unshuffled.queue.single { it.id == "M1" }
        val beforeRemovalMetadata = requireNotNull(endpoint.exportQueueIntentState())
        endpoint.execute(PlaybackDeviceCommand.queue("remove", removed.key), permit)
        PlaybackService.undoQueuedRemoval(); runCurrent()
        assertEquals(unshuffled.queue, endpoint.exportSnapshot().queue)
        assertTrue("Undo's exact snapshot+metadata publication must not fence a capable owner", coordinator.state.value.connected)
        val restoredMetadata = requireNotNull(endpoint.exportQueueIntentState())
        assertEquals(beforeRemovalMetadata.origins, restoredMetadata.origins)
        assertEquals(beforeRemovalMetadata.highKeys, restoredMetadata.highKeys)
        assertTrue(restoredMetadata.revision > beforeRemovalMetadata.revision)
        controls.performPreparedAction(prepared("M3"), client, PlaybackListAction.ADD_TO_QUEUE,
            requireNotNull(PlaybackService.interactionKey()), startTrackId = "M3", onResult = results::add)
        releaseDispatch(); runCurrent()
        assertEquals(listOf("played", "A", "M1", "M2", "M3", "C1", "C2"), endpoint.exportSnapshot().queue.map { it.id })
        endpoint.execute(PlaybackDeviceCommand.transport("clear"), permit)
        controls.performPreparedAction(prepared("M4"), client, PlaybackListAction.ADD_TO_QUEUE,
            requireNotNull(PlaybackService.interactionKey()), startTrackId = "M4", onResult = results::add)
        releaseDispatch(); runCurrent()
        assertEquals(listOf("played", "A", "M4"), endpoint.exportSnapshot().queue.map { it.id })
        assertFalse(actualPlayer.playWhenReady)
    }

    @Test fun coordinatorBusyAndClosedRejectionsCompleteWithoutAnyFalseSuccess() = runTest {
        val coordinator = installOwner(backgroundScope)
        runCurrent()
        coordinator.localAction { endpoint.activate(contextQueue(), it) }; runCurrent()
        val controls = connect()
        pollGate = CompletableDeferred()
        coordinator.localAction { endpoint.execute(PlaybackDeviceCommand.transport("repeat"), it) }
        runCurrent() // First action has mutated and is awaiting its publication.
        val before = endpoint.exportSnapshot()
        val results = mutableListOf<PlaybackActionResult>()
        controls.performPreparedAction(prepared("X"), client, PlaybackListAction.ADD_TO_QUEUE,
            requireNotNull(PlaybackService.interactionKey()), startTrackId = "X", onResult = results::add)
        releaseDispatch(); runCurrent()
        assertEquals(listOf(PlaybackActionStatus.UNAVAILABLE), results.map { it.status })
        assertEquals(before, endpoint.exportSnapshot())
        pollGate!!.complete(Unit); pollGate = null; runCurrent()
        coordinator.close()
        controls.performPreparedAction(prepared("Y"), client, PlaybackListAction.ADD_TO_QUEUE, null,
            startTrackId = "Y", onResult = results::add)
        releaseDispatch(); runCurrent()
        assertEquals(2, results.size)
        assertTrue(results.all { it.status !in setOf(PlaybackActionStatus.ADDED, PlaybackActionStatus.PLAYING) })
        assertFalse(actualPlayer.playWhenReady)
    }
}
