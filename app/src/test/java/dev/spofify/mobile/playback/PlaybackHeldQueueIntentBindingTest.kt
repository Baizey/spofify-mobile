package dev.spofify.mobile.playback

import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import dev.spofify.mobile.AppGraph
import dev.spofify.mobile.LibraryViewModel
import dev.spofify.mobile.LoginStore
import dev.spofify.mobile.RememberedLogin
import dev.spofify.mobile.SpofifyApplication
import dev.spofify.mobile.core.*
import dev.spofify.mobile.feature.devices.*
import dev.spofify.mobile.feature.library.CatalogPlaybackFeedback
import dev.spofify.mobile.feature.library.MusicRow
import dev.spofify.mobile.feature.player.NativeUpcomingQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** NATIVE-01: actual row/menu capture -> VM/Connection -> coordinator -> service-created
 * ExoPlayer. Only registration transport, harmless output reads and publication timing are
 * controlled. No fabricated interaction keys/bindings/receipts authorize an action here.
 * Robolectric input and queue effects are not native render/audio acceptance evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SpofifyApplication::class, qualifiers = "w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackHeldQueueIntentBindingTest {
    @get:Rule val compose = createComposeRule()
    private val scheduler = TestScope(StandardTestDispatcher())
    private val store = ViewModelStore()
    private val endpoint = Media3PlaybackEndpoint()
    private val web = MockWebServer()
    private val playbackReads = AtomicInteger()
    private val outputCalls = mutableListOf<String>()
    private val wireCommands = mutableListOf<PlaybackDeviceCommand>()
    private val endpointResults = mutableListOf<PlaybackEndpointResult>()
    private var prepares = 0
    private var activations = 0
    private var publicationGate: CompletableDeferred<Unit>? = null
    private var progressMs: Long? = null
    private var durationMs: Long? = null
    private var buffering = false
    private var loading = false
    private var leaseMs = 8_000L
    private var clock = 0L
    private var published = PlaybackSnapshot()
    private lateinit var publishedMetadata: QueueIntentState
    private var sequence = 0L
    private lateinit var graph: AppGraph
    private lateinit var client: SpofifyClient
    private lateinit var service: ServiceController<PlaybackService>
    private lateinit var player: ExoPlayer
    private lateinit var owner: PlaybackCoordinator
    private lateinit var connection: PlaybackConnection
    private lateinit var controls: ObservedControls
    private lateinit var model: LibraryViewModel
    private val shown = mutableStateOf(true)
    private val row = LibraryItem("track", "X", "Catalog selection", albumId = "album", hasAudio = true)
    private val playerField get() = PlaybackService::class.java.getDeclaredField("player").apply { isAccessible = true }

    private data class Invocation(val selection: QueueIntentSelection, val key: PlaybackInteractionKey?,
        val binding: QueueIntentBinding?)
    private inner class ObservedControls : PlaybackControls by connection {
        val rows = mutableListOf<Invocation>()
        val upcoming = mutableListOf<Pair<PlaybackInteractionKey, String>>()
        val results = mutableListOf<PlaybackActionResult>()
        override fun queueIntent(selection: QueueIntentSelection, client: SpofifyClient, action: PlaybackListAction,
            expectedKey: PlaybackInteractionKey?, expectedBinding: QueueIntentBinding?, isCurrent: () -> Boolean,
            onResult: (PlaybackActionResult) -> Unit) {
            rows += Invocation(selection, expectedKey, expectedBinding)
            connection.queueIntent(selection, client, action, expectedKey, expectedBinding, isCurrent) {
                results += it; onResult(it)
            }
        }
        override fun queueIntentOccurrence(expectedKey: PlaybackInteractionKey, occurrenceId: String,
            action: PlaybackListAction, isCurrent: () -> Boolean, onResult: (PlaybackActionResult) -> Unit) {
            upcoming += expectedKey to occurrenceId
            connection.queueIntentOccurrence(expectedKey, occurrenceId, action, isCurrent) {
                results += it; onResult(it)
            }
        }
    }
    private val recordedEndpoint = object : NativePlaybackEndpoint by endpoint {
        override suspend fun prepare(snapshot: PlaybackSnapshot): Boolean { prepares++; return endpoint.prepare(snapshot) }
        override suspend fun prepare(snapshot: PlaybackSnapshot, queueIntentState: QueueIntentState?): Boolean {
            prepares++; return endpoint.prepare(snapshot, queueIntentState)
        }
        override suspend fun activate(snapshot: PlaybackSnapshot, authority: PlaybackAuthority): PlaybackEndpointResult {
            activations++; return endpoint.activate(snapshot, authority)
        }
        override suspend fun activate(snapshot: PlaybackSnapshot, queueIntentState: QueueIntentState?,
            authority: PlaybackAuthority): PlaybackEndpointResult {
            activations++; return endpoint.activate(snapshot, queueIntentState, authority)
        }
        override suspend fun execute(command: PlaybackDeviceCommand, authority: PlaybackAuthority): PlaybackEndpointResult {
            wireCommands += command
            return endpoint.execute(command, authority).also { endpointResults += it }
        }
        override suspend fun execute(command: PlaybackDeviceCommand, authority: PlaybackAuthority,
            isCurrent: () -> Boolean): PlaybackEndpointResult {
            wireCommands += command
            return endpoint.execute(command, authority, isCurrent).also { endpointResults += it }
        }
    }

    private fun deviceView() = PlaybackDevicesView("phone", listOf(PlaybackDevice("phone", "Phone", true)),
        "phone", 7, leaseMs, state = published.copy(loading = loading), stateSeq = sequence, stateAgeMs = 0,
        transfer = null, directives = emptyList(), commands = emptyList(),
        queueIntents = QueueIntentsView(1, listOf(QueueIntentReceiver("phone", 1)),
            QueueIntentBinding("phone", publishedMetadata.receiverId, publishedMetadata.revision,
                published.queue.getOrNull(published.position)?.key, published.occurrence?.eventId),
            publishedMetadata, emptyList(), emptyList()))
    private val protocol = object : PlaybackDevicesProtocol {
        override suspend fun probeQueueIntents() = deviceView()
        override suspend fun register(name: String) = deviceView()
        override suspend fun register(name: String, queueIntentsVersion: Int?) = deviceView().also {
            assertEquals(1, queueIntentsVersion)
        }
        override suspend fun view() = deviceView()
        override suspend fun poll(deviceId: String, epoch: Long, stateSeq: Long?, state: PlaybackSnapshot?,
            acks: List<PlaybackAck>) = deviceView()
        override suspend fun poll(deviceId: String, epoch: Long, stateSeq: Long?, state: PlaybackSnapshot?,
            acks: List<PlaybackAck>, queueIntentState: QueueIntentState?): PlaybackDevicesView {
            publicationGate?.await()
            if (state != null) {
                PlaybackPublication.validatePoll(deviceId, epoch, stateSeq, state, acks, queueIntentState)
                published = state; publishedMetadata = requireNotNull(queueIntentState); sequence = requireNotNull(stateSeq)
            }
            return deviceView()
        }
        override suspend fun rename(deviceId: String, name: String) = deviceView()
        override suspend fun command(deviceId: String, epoch: Long, commandId: String,
            request: PlaybackDeviceCommand): PlaybackDevicesView = error("Local output must not route remote commands")
        override suspend fun transfer(deviceId: String, epoch: Long, commandId: String, targetDeviceId: String,
            selectionSeq: Long): PlaybackDevicesView = error("Queue-only intents must not acquire output")
        override suspend fun leave(deviceId: String) = Unit
    }

    @Before fun setup() = runBlocking {
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
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                if (path.endsWith("/playback")) playbackReads.incrementAndGet()
                val body = when (path) {
                    "/api/auth/login" -> """{"accountId":"fixture","username":"listener","role":"user","csrfToken":"synthetic"}"""
                    "/api/library/revision" -> """{"revision":1}"""
                    else -> """{"items":[],"total":0,"offset":0,"limit":40,"revision":1}"""
                }
                return MockResponse().setHeader("Content-Type", "application/json")
                    .setHeader("Set-Cookie", "spofify_session=synthetic; Path=/; HttpOnly; Max-Age=3600").setBody(body)
            }
        }
        web.start()
        client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
        PlaybackService.onServiceActiveChanged = null
        client.login("listener", "synthetic")
        service = Robolectric.buildService(PlaybackService::class.java).create()
        player = playerField.get(service.get()) as ExoPlayer
        graph.playbackCoordinator!!.close()
        publishedMetadata = requireNotNull(endpoint.exportQueueIntentState())
        owner = PlaybackCoordinator(protocol, recordedEndpoint, scheduler.backgroundScope, clockMs = { clock })
        AppGraph::class.java.getDeclaredField("playbackCoordinator").apply { isAccessible = true; set(graph, owner) }
        owner.setServiceActive(true); scheduler.runCurrent()
        owner.localAction { endpoint.activate(initialQueue(), it) }; scheduler.runCurrent()
        repeat(3) { pump() }
        installReadObserver()
        val session = PlaybackService::class.java.getDeclaredField("session").let {
            it.isAccessible = true; it.get(service.get()) as MediaSession
        }
        val future = MediaController.Builder(service.get(), session.token).buildAsync()
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        connection = PlaybackConnection(app)
        PlaybackConnection::class.java.getDeclaredField("controller").apply {
            isAccessible = true; set(connection, future.get(1, TimeUnit.SECONDS))
        }
        connection.connect(); refresh()
        controls = ObservedControls()
        compose.runOnIdle { model = LibraryViewModel(app, graph, controls); store.put("held-binding", model) }
        until { !model.state.value.restoring && !model.state.value.busy && model.state.value.session != null }
        refresh()
        assertTrue(owner.state.value.canQueueIntent)
        assertNotNull(connection.state.value.interactionKey)
        assertNotNull(connection.state.value.queueIntentBinding)
        assertEquals(initialQueue().queue, endpoint.exportSnapshot().queue)
        wireCommands.clear(); endpointResults.clear(); outputCalls.clear(); prepares = 0; activations = 0
    }

    @After fun cleanup() {
        var failure: Throwable? = null
        fun clean(block: () -> Unit) { try { block() } catch (error: Throwable) {
            if (failure == null) failure = error else failure!!.addSuppressed(error)
        } }
        clean { publicationGate?.complete(Unit); publicationGate = null }
        clean { compose.runOnIdle { shown.value = false }; compose.waitForIdle() }
        clean { store.clear() }
        clean { if (::connection.isInitialized) connection.release() }
        clean { if (::owner.isInitialized) owner.close() }
        clean { scheduler.backgroundScope.cancel() }
        PlaybackService.onServiceActiveChanged = null
        clean { if (::service.isInitialized) { if (::player.isInitialized) playerField.set(service.get(), player); service.destroy() } }
        clean { if (::graph.isInitialized) graph.clear() }
        clean { web.shutdown() }
        failure?.let { throw it }
    }

    private fun initialQueue() = PlaybackSnapshot(queue = listOf("played", "A", "duplicate", "duplicate").mapIndexed { i, id ->
        PlaybackTrack(id, if (id == "duplicate") "Upcoming duplicate" else id, emptyList(), "Album", "album", 120.0,
            key = i + 1L, order = i.toLong())
    }, position = 1, context = PlaybackContext("album", "album", "Album"), elapsed = 12.375, duration = 120.0,
        occurrence = PlaybackOccurrence(2, "A", "history_A", 3.125, true)).publicationValidated()

    private fun installReadObserver() {
        val watched = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, args ->
            when {
                method.name == "getCurrentPosition" && progressMs != null -> progressMs
                method.name == "getDuration" && durationMs != null -> durationMs
                method.name == "getPlaybackState" && buffering -> Player.STATE_BUFFERING
                method.name == "isLoading" -> loading
                else -> {
                    if (method.name in setOf("prepare", "play", "pause", "stop", "setMediaItems", "addMediaItems", "removeMediaItem") ||
                        method.name.startsWith("seekTo")) outputCalls += method.name
                    try { method.invoke(player, *(args ?: emptyArray())) }
                    catch (error: InvocationTargetException) { throw error.targetException }
                }
            }
        } as ExoPlayer
        playerField.set(service.get(), watched)
    }
    private fun refresh() {
        PlaybackConnection::class.java.getDeclaredMethod("publish", Boolean::class.javaPrimitiveType).apply {
            isAccessible = true; invoke(connection, true)
        }
    }
    private fun pump() { scheduler.runCurrent(); shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS); scheduler.runCurrent() }
    private fun until(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) { pump(); Thread.sleep(5) }
        pump(); assertTrue("Timed out waiting for actual integration path", condition())
    }
    private fun content(compact: Boolean = false) {
        compose.setContent {
            val state by connection.state.collectAsState()
            MaterialTheme { if (shown.value) Column(Modifier.size(400.dp, 650.dp)) {
                MusicRow(row, {}, { _, _ -> error("No legacy replacement") }, {}, { error("No legacy enqueue") },
                    artwork = { _, modifier -> Box(modifier) }, modifier = Modifier.testTag("catalog-row"),
                    playback = CatalogPlaybackFeedback(interactionKey = state.interactionKey,
                        onContextual = { item, key, current -> model.contextualFrom(client, item, key, current) }))
                NativeUpcomingQueue(state, controls, {}, { _, modifier -> Box(modifier) }, compact = compact)
            } }
        }
    }
    private fun holdSwipe() {
        compose.onNodeWithTag("catalog-row").performTouchInput {
            down(Offset(80f, 36f)); moveTo(Offset(190f, 36f))
        }
        compose.runOnIdle { assertTrue(controls.rows.isEmpty()); assertTrue(wireCommands.isEmpty()) }
    }
    private fun releaseSwipe() = compose.onNodeWithTag("catalog-row").performTouchInput { up() }
    private fun openUpcomingMenu() {
        compose.onAllNodesWithContentDescription("Queue actions for Upcoming duplicate")[1].performClick()
        compose.onNodeWithText("Queue").assertIsDisplayed()
    }

    private enum class SemanticChange { REPEAT_ABA, HISTORY_EVENT }
    private fun changeSemantics(change: SemanticChange) {
        val before = endpoint.exportSnapshot()
        val revision = requireNotNull(endpoint.exportQueueIntentState()).revision
        val queueEpoch = PlaybackRequests.queueEpoch
        val requestEpoch = PlaybackRequests.epoch
        val permit = requireNotNull(owner.currentAuthority())
        val authorityIdentity = permit.epoch to permit.generation
        when (change) {
            SemanticChange.REPEAT_ABA -> listOf("all", "one", "off").forEachIndexed { index, repeat ->
                owner.remoteCommand(PlaybackDeviceCommand.transport("repeat")); scheduler.runCurrent()
                assertEquals("Every semantic change is accepted before the next, without a UI frame",
                    repeat, owner.state.value.view!!.state!!.repeat)
                assertEquals(revision + index + 1, owner.state.value.queueIntentBinding!!.revision)
            }
            SemanticChange.HISTORY_EVENT -> {
                // Production's restart-current history operation, without a seek/import or
                // queueChanged that could mask the missing same-key/event identity fence.
                PlaybackService::class.java.getDeclaredMethod("restartTraversal").apply { isAccessible = true; invoke(service.get()) }
                owner.localAction { PlaybackEndpointResult("ok", endpoint.exportSnapshot(),
                    queueIntentState = endpoint.exportQueueIntentState()) }
                scheduler.runCurrent()
            }
        }
        val after = endpoint.exportSnapshot()
        assertEquals(before.queue, after.queue); assertEquals(before.position, after.position)
        assertEquals(before.context, after.context); assertEquals(before.repeat, after.repeat)
        assertEquals("This regression must not borrow queueChanged cancellation", queueEpoch, PlaybackRequests.queueEpoch)
        assertEquals(requestEpoch, PlaybackRequests.epoch)
        val afterPermit = requireNotNull(owner.currentAuthority())
        assertEquals("Same leased output; only its semantic ledger/history changed", authorityIdentity,
            afterPermit.epoch to afterPermit.generation)
        assertEquals(revision + if (change == SemanticChange.REPEAT_ABA) 3 else 1,
            requireNotNull(endpoint.exportQueueIntentState()).revision)
        if (change == SemanticChange.REPEAT_ABA) assertEquals(before.occurrence, after.occurrence)
        else {
            val oldEvent = requireNotNull(before.occurrence)
            val newEvent = requireNotNull(after.occurrence)
            assertEquals(oldEvent.key, newEvent.key)
            assertNotEquals(oldEvent.eventId, newEvent.eventId)
        }
        // All three accepted repeat publications happened above in one main-thread block;
        // Compose sees only the final equal-valued musical snapshot and its NEW binding.
        refresh()
        assertEquals(endpoint.exportQueueIntentState()!!.revision, connection.state.value.queueIntentBinding!!.revision)
        wireCommands.clear(); endpointResults.clear(); outputCalls.clear()
    }
    private fun assertNoHeldEffect(before: PlaybackSnapshot, metadata: QueueIntentState) {
        repeat(4) { pump() }; compose.waitForIdle()
        assertEquals("Held UI must not emit a queueIntent with a refreshed expected binding", emptyList<PlaybackDeviceCommand>(),
            wireCommands.filter { it.type == "queueIntent" })
        assertEquals(before, endpoint.exportSnapshot()); assertEquals(metadata, endpoint.exportQueueIntentState())
        assertEquals(0, prepares); assertEquals(0, activations)
        assertTrue("No prepare/play/pause/seek or queue mutation", outputCalls.isEmpty())
        assertEquals(0, playbackReads.get())
        assertTrue(controls.results.all { it.status == PlaybackActionStatus.STALE && it.acceptedCount == 0 })
        assertNull(model.state.value.notice)
    }
    private fun heldSwipeAcross(change: SemanticChange) {
        content(); val key = connection.state.value.interactionKey
        val oldBinding = connection.state.value.queueIntentBinding
        holdSwipe()
        compose.runOnIdle { changeSemantics(change) }
        val before = endpoint.exportSnapshot(); val metadata = requireNotNull(endpoint.exportQueueIntentState())
        releaseSwipe()
        assertNoHeldEffect(before, metadata)
        assertTrue("The old gesture must never reach VM raw admission", controls.rows.isEmpty())
        assertNotSame(key, connection.state.value.interactionKey)
        assertNotEquals(oldBinding, connection.state.value.queueIntentBinding)
    }
    @Test fun actualHeldCatalogSwipeCannotBorrowNewBindingAfterUnsampledRepeatABA() = heldSwipeAcross(SemanticChange.REPEAT_ABA)
    @Test fun actualHeldCatalogSwipeCannotBorrowNewBindingAfterSameKeyNewHistoryEvent() = heldSwipeAcross(SemanticChange.HISTORY_EVENT)

    private fun heldMoreAcross(change: SemanticChange, compact: Boolean) {
        content(compact); val oldKey = requireNotNull(connection.state.value.interactionKey)
        val oldBinding = connection.state.value.queueIntentBinding
        val oldOccurrenceId = connection.state.value.upcoming[1].occurrenceId
        assertEquals("wire-4", oldOccurrenceId)
        openUpcomingMenu()
        compose.runOnIdle { changeSemantics(change) }
        val before = endpoint.exportSnapshot(); val metadata = requireNotNull(endpoint.exportQueueIntentState())
        // The actual normal/compact popup must dismiss on its changed semantic key.
        // Do not invoke a detached Compose clickable: that wrapper still requires an
        // attached Modifier node for its click sound/CompositionLocal, before app code.
        compose.onNodeWithText("Queue").assertDoesNotExist()
        assertNoHeldEffect(before, metadata)
        assertTrue("Auto-dismissal must not dispatch a queue action", controls.upcoming.isEmpty())
        // Independently emulate an already-delivered production callback at the real
        // Connection boundary, retaining ONLY the key/occurrence captured at menu opening.
        // The host is still alive; no freshly sampled binding may authorize the old action.
        compose.runOnIdle {
            controls.queueIntentOccurrence(oldKey, oldOccurrenceId, PlaybackListAction.QUEUE_HIGH, { true }) {}
        }
        until { controls.results.size == 1 }
        assertEquals(PlaybackActionStatus.STALE, controls.results.single().status)
        assertEquals(0, controls.results.single().acceptedCount)
        assertEquals(listOf(oldKey to oldOccurrenceId), controls.upcoming)
        assertNoHeldEffect(before, metadata)
        compose.onNodeWithText("Queue").assertDoesNotExist()
        assertNotSame(oldKey, connection.state.value.interactionKey)
        assertNotEquals(oldBinding, connection.state.value.queueIntentBinding)
    }
    @Test fun actualUpcomingMoreCannotBorrowNewBindingAfterUnsampledRepeatABA() = heldMoreAcross(SemanticChange.REPEAT_ABA, false)
    @Test fun actualCompactUpcomingMoreCannotBorrowNewBindingAfterUnsampledRepeatABA() = heldMoreAcross(SemanticChange.REPEAT_ABA, true)
    @Test fun actualUpcomingMoreCannotBorrowNewBindingAfterSameKeyNewHistoryEvent() = heldMoreAcross(SemanticChange.HISTORY_EVENT, false)
    @Test fun actualCompactUpcomingMoreCannotBorrowNewBindingAfterSameKeyNewHistoryEvent() = heldMoreAcross(SemanticChange.HISTORY_EVENT, true)

    private fun connectionRejectsHeldKey(change: SemanticChange) {
        content()
        val key = requireNotNull(connection.state.value.interactionKey)
        val binding = requireNotNull(connection.state.value.queueIntentBinding)
        compose.runOnIdle { changeSemantics(change) }
        val before = endpoint.exportSnapshot(); val metadata = requireNotNull(endpoint.exportQueueIntentState())
        val results = mutableListOf<PlaybackActionResult>()
        // Connection's upcoming API itself samples its binding late. Its original generic
        // key must reject BEFORE a queueIntent using that new binding reaches the service.
        connection.queueIntentOccurrence(key, "wire-4", PlaybackListAction.PLAY_NOW, onResult = results::add)
        until { results.size == 1 }
        assertEquals(PlaybackActionStatus.STALE, results.single().status)
        assertTrue(wireCommands.none { it.type == "queueIntent" })
        // Bypass the UI-key gate to exercise the actual coordinator/service binding fence as
        // well: no-key admission still cannot turn the old captured receiver revision fresh.
        connection.queueIntent(QueueIntentSelection.Occurrence(4), client, PlaybackListAction.PLAY_NOW,
            null, binding, { true }, results::add)
        until { results.size == 2 }
        assertEquals(0, results.last().acceptedCount)
        assertTrue(results.last().status in setOf(PlaybackActionStatus.STALE, PlaybackActionStatus.UNAVAILABLE))
        // Independently reach the real receiver (not a fake endpoint) with the old wire
        // binding. It must refuse before resolver preparation, even without a UI key gate.
        val rejected = runBlocking { endpoint.execute(PlaybackDeviceCommand.queueIntent(QueueIntentIntent.NOW,
            binding, QueueIntentSelection.Occurrence(4)), requireNotNull(owner.currentAuthority())) }
        assertEquals("rejected", rejected.status)
        assertEquals("stale", rejected.queueIntentResult!!.outcome)
        assertEquals(0, rejected.queueIntentResult!!.acceptedCount)
        assertNoHeldEffect(before, metadata)
    }
    @Test fun realConnectionRejectsOldUpcomingKeyAndBindingAfterRepeatABA() = connectionRejectsHeldKey(SemanticChange.REPEAT_ABA)
    @Test fun realConnectionRejectsOldUpcomingKeyAndBindingAfterSameKeyNewHistoryEvent() = connectionRejectsHeldKey(SemanticChange.HISTORY_EVENT)

    private fun benignChurn() {
        val key = connection.state.value.interactionKey
        val binding = connection.state.value.queueIntentBinding
        val transportKey = connection.state.value.transportInteractionKey
        progressMs = 23_456; durationMs = 180_000; buffering = true; loading = true; leaseMs = 7_000; clock = 50
        PlaybackService::class.java.getDeclaredField("transportCapabilityRevision").apply {
            isAccessible = true; setLong(service.get(), getLong(service.get()) + 1)
        }
        owner.localAction { PlaybackEndpointResult("ok", endpoint.exportSnapshot(), queueIntentState = endpoint.exportQueueIntentState()) }
        scheduler.runCurrent(); refresh()
        assertSame("Progress/duration/loading/lease/transport changes are not generic intent semantics", key, connection.state.value.interactionKey)
        assertEquals(binding, connection.state.value.queueIntentBinding)
        assertNotNull(transportKey); assertNotSame(transportKey, connection.state.value.transportInteractionKey)
        assertEquals(23.456, endpoint.exportSnapshot().elapsed, 0.0)
        assertEquals(180.0, endpoint.exportSnapshot().duration, 0.0)
        assertTrue(owner.state.value.view!!.state!!.loading)
        assertEquals(7_000L, owner.state.value.view!!.leaseMs)
        wireCommands.clear(); endpointResults.clear(); outputCalls.clear()
    }
    @Test fun actualHeldSwipeSurvivesOnlyBenignProgressDurationLoadingLeaseAndTransportChurn() {
        content(); val key = connection.state.value.interactionKey; val binding = connection.state.value.queueIntentBinding
        holdSwipe(); compose.runOnIdle { benignChurn() }
        val before = endpoint.exportSnapshot()
        releaseSwipe(); until { controls.results.size == 1 && !model.state.value.preparing }
        val command = wireCommands.single { it.type == "queueIntent" }
        assertEquals(binding, command.expected)
        assertSame(key, controls.rows.single().key); assertEquals(binding, controls.rows.single().binding)
        assertEquals(listOf("played", "A", "X", "duplicate", "duplicate"), endpoint.exportSnapshot().queue.map { it.id })
        assertEquals(before.occurrence, endpoint.exportSnapshot().occurrence)
        assertEquals(before.elapsed, endpoint.exportSnapshot().elapsed, 0.0)
        assertEquals(PlaybackActionStatus.ADDED, controls.results.single().status)
        assertEquals("unchanged", controls.results.single().queueIntentResult!!.playback)
        assertEquals(0, prepares); assertEquals(0, activations); assertEquals(0, playbackReads.get())
        assertTrue(outputCalls.none { it in setOf("prepare", "play", "pause", "stop") || it.startsWith("seekTo") })
    }
    @Test fun actualOpenUpcomingMoreSurvivesBenignChurnAndPromotesOnlyExactSecondDuplicate() {
        content(); val binding = connection.state.value.queueIntentBinding
        openUpcomingMenu(); compose.runOnIdle { benignChurn() }
        val before = endpoint.exportSnapshot()
        compose.onNodeWithText("Queue").performClick()
        until { controls.results.size == 1 }
        val command = wireCommands.single { it.type == "queueIntent" }
        assertEquals(binding, command.expected); assertEquals(QueueIntentSelection.Occurrence(4), command.selection)
        assertEquals(listOf(1L, 2L, 4L, 3L), endpoint.exportSnapshot().queue.map { it.key })
        assertEquals(before.occurrence, endpoint.exportSnapshot().occurrence)
        assertEquals(before.elapsed, endpoint.exportSnapshot().elapsed, 0.0)
        assertEquals(listOf(4L), endpoint.exportQueueIntentState()!!.highKeys)
        assertEquals(0, prepares); assertEquals(0, activations); assertEquals(0, playbackReads.get())
        assertTrue(outputCalls.none { it in setOf("prepare", "play", "pause", "stop") || it.startsWith("seekTo") })
    }

    private fun committedFifo(semanticEdit: Boolean) {
        content()
        val oldKey = requireNotNull(connection.state.value.interactionKey)
        val oldBinding = requireNotNull(connection.state.value.queueIntentBinding)
        val gate = CompletableDeferred<Unit>(); publicationGate = gate
        model.contextualFrom(client, row, oldKey)
        model.contextualFrom(client, row.copy(id = "Y", name = "Second committed selection"), oldKey)
        until { endpoint.exportSnapshot().queue.any { it.id == "X" } }
        assertEquals(1, controls.rows.size); assertTrue(model.state.value.preparing)
        assertNull("No optimistic completion while exact publication is held", model.state.value.notice)
        val firstEndpoint = endpointResults.single { it.queueIntentResult != null }
        val receipt = requireNotNull(firstEndpoint.additiveTransition)
        assertSame(oldKey, receipt.before); assertEquals(oldBinding, receipt.beforeBinding)
        assertNotSame(receipt.before, receipt.after); assertNotEquals(receipt.beforeBinding, receipt.afterBinding)
        if (semanticEdit) {
            // A repeat ABA after X committed but before its local receipt is released must
            // disqualify that receipt, despite an equal-valued final musical state.
            repeat(3) { runBlocking { endpoint.execute(PlaybackDeviceCommand.transport("repeat"), requireNotNull(owner.currentAuthority())) } }
            assertNotSame(receipt.after, PlaybackService.interactionKey())
        } else {
            PlaybackService::class.java.getDeclaredField("transportCapabilityRevision").apply {
                isAccessible = true; setLong(service.get(), getLong(service.get()) + 1)
            }
            assertSame(receipt.after, PlaybackService.interactionKey())
        }
        publicationGate = null; gate.complete(Unit)
        until { !model.state.value.preparing && controls.results.size == 2 }
        assertEquals(if (semanticEdit) listOf("played", "A", "X", "duplicate", "duplicate")
            else listOf("played", "A", "X", "Y", "duplicate", "duplicate"), endpoint.exportSnapshot().queue.map { it.id })
        if (semanticEdit) {
            assertNull("An intervening semantic ledger change cannot mint a successor receipt", controls.results.first().additiveTransition)
            assertEquals(oldBinding, controls.rows.last().binding)
            assertSame(oldKey, controls.rows.last().key)
            assertEquals(PlaybackActionStatus.STALE, controls.results.last().status)
            assertEquals(1, wireCommands.count { it.type == "queueIntent" })
        } else {
            assertSame(receipt.after, controls.rows.last().key)
            assertEquals(receipt.afterBinding, controls.rows.last().binding)
            assertEquals(receipt, controls.results.first().additiveTransition)
            assertEquals(listOf(oldBinding, receipt.afterBinding), wireCommands.filter { it.type == "queueIntent" }.map { it.expected })
            assertEquals(List(2) { PlaybackActionStatus.ADDED }, controls.results.map { it.status })
        }
        // A separate held UI callback was never a committed ticket and cannot inherit X's
        // exact local receipt, even in the successful FIFO case.
        val held = mutableListOf<PlaybackActionResult>()
        val after = endpoint.exportSnapshot()
        connection.queueIntentOccurrence(oldKey, "wire-4", PlaybackListAction.QUEUE_HIGH, onResult = held::add)
        until { held.size == 1 }
        assertEquals(PlaybackActionStatus.STALE, held.single().status)
        assertEquals(after, endpoint.exportSnapshot())
        assertEquals(initialQueue().occurrence, after.occurrence)
        assertEquals(0, playbackReads.get()); assertEquals(0, prepares); assertEquals(0, activations)
        assertTrue(outputCalls.none { it in setOf("prepare", "play", "pause", "stop") || it.startsWith("seekTo") })
    }
    @Test fun committedLocalFifoFollowsOnlyExactOwnKeyAndBindingReceiptDespiteTransportChurn() = committedFifo(false)
    @Test fun committedLocalFifoCannotFollowReceiptAfterInterveningUnsampledRepeatABA() = committedFifo(true)
}
