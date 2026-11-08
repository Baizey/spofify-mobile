package dev.spofify.mobile.playback

import android.app.ActivityManager
import android.media.AudioManager
import android.os.Looper
import android.os.Process
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import dev.spofify.mobile.*
import dev.spofify.mobile.core.*
import dev.spofify.mobile.feature.devices.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
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

/** Real service queue edits and Media3 callbacks; native audio/render acceptance is separate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SpofifyApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackQueueIntentReceiverTest {
    private lateinit var graph: AppGraph
    private lateinit var web: MockWebServer
    private lateinit var controller: ServiceController<PlaybackService>
    private lateinit var actual: ExoPlayer
    private val endpoint = Media3PlaybackEndpoint()
    private val permit = PlaybackAuthority(7, 1) { true }
    private val calls = mutableListOf<String>()
    @Volatile private var responseNames = emptyList<String>()
    private var activeRead = false
    private var holdStart = false
    private var syntheticStart = false
    private var started = false
    private var wantsPlay = false
    private var holdPlaying = false
    private var onSyntheticPlay: (() -> Unit)? = null
    private var syntheticState = Player.STATE_IDLE
    private val playerField get() = PlaybackService::class.java.getDeclaredField("player").apply { isAccessible = true }

    @Before fun setup() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<SpofifyApplication>()
        app.graph.clear()
        graph = AppGraph(app, object : SessionStore {
            private var stored: StoredSession? = null
            override fun read() = stored
            override fun write(value: StoredSession?) { stored = value }
        }, object : LoginStore {
            override fun read(): RememberedLogin? = null
            override fun write(value: RememberedLogin?) = Unit
        })
        SpofifyApplication::class.java.getDeclaredField("graph").apply { isAccessible = true; set(app, graph) }
        web = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/api/auth/login" -> MockResponse().setHeader("Content-Type", "application/json")
                        .setHeader("Set-Cookie", "spofify_session=synthetic; Path=/; HttpOnly; Max-Age=3600")
                        .setBody("""{"accountId":"fixture","username":"listener","role":"user","csrfToken":"synthetic"}""")
                    request.path == "/api/library/albums/vector/playback" -> MockResponse().setHeader("Content-Type", "application/json")
                        .setBody(buildJsonObject {
                            putJsonArray("items") { responseNames.forEach { name -> add(buildJsonObject {
                                put("kind", "track"); put("id", name); put("name", name); put("hasAudio", true); put("durationMs", 120000)
                            }) } }
                            put("total", responseNames.size)
                        }.toString())
                    else -> MockResponse().setResponseCode(500)
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
        PlaybackService.onServiceActiveChanged = null
        client.login("listener", "synthetic")
        web.takeRequest(1, TimeUnit.SECONDS)
        controller = Robolectric.buildService(PlaybackService::class.java).create()
        actual = playerField.get(controller.get()) as ExoPlayer
        // Read-only registration seam: this suite tests leased service transactions, not HTTP
        // negotiation. The production graph suite separately verifies negotiated admission.
        val owner = requireNotNull(graph.playbackCoordinator)
        @Suppress("UNCHECKED_CAST")
        val state = owner.javaClass.getDeclaredField("mutableState").let {
            it.isAccessible = true; it.get(owner) as MutableStateFlow<PlaybackDevicesState>
        }
        state.value = PlaybackDevicesState(connected = true, deviceId = "phone",
            view = PlaybackDevicesView("phone", listOf(PlaybackDevice("phone", "Phone", true)), "phone", 7, 8000,
                state = null, stateSeq = 0, stateAgeMs = 0, transfer = null, directives = emptyList(), commands = emptyList()))
        background(true)
    }

    @After fun cleanup() {
        PlaybackService.onServiceActiveChanged = null
        try {
            if (::controller.isInitialized) { if (::actual.isInitialized) playerField.set(controller.get(), actual); controller.destroy() }
        } finally { try { if (::graph.isInitialized) graph.clear() } finally { if (::web.isInitialized) web.shutdown() } }
    }

    private fun background(background: Boolean) {
        shadowOf(controller.get().getSystemService(ActivityManager::class.java)).setProcesses(listOf(
            ActivityManager.RunningAppProcessInfo().apply {
                pid = Process.myPid(); importance = if (background) ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND
                    else ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            }))
    }

    private fun proxy() {
        val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, args ->
            when {
                method.name == "getPlaybackState" && syntheticStart -> syntheticState
                method.name == "getPlaybackState" && activeRead -> Player.STATE_BUFFERING
                method.name == "getPlayWhenReady" && (activeRead || syntheticStart) -> activeRead || wantsPlay
                method.name == "isPlaying" && (activeRead || syntheticStart) -> started
                method.name in setOf("prepare", "play", "pause", "stop") && syntheticStart -> {
                    calls.add(method.name)
                    if (method.name == "prepare") syntheticState = if (holdStart) Player.STATE_BUFFERING else Player.STATE_READY
                    if (method.name == "play") { wantsPlay = true; started = !holdPlaying; onSyntheticPlay?.invoke() }
                    if (method.name == "pause" || method.name == "stop") { wantsPlay = false; started = false }
                    if (method.name == "stop") syntheticState = Player.STATE_IDLE
                    null
                }
                else -> {
                    if (method.name in setOf("prepare", "play", "pause", "stop", "setMediaItems") || method.name.startsWith("seekTo")) calls.add(method.name)
                    try { method.invoke(actual, *(args ?: emptyArray())) }
                    catch (e: InvocationTargetException) { throw e.targetException }
                }
            }
        } as ExoPlayer
        playerField.set(controller.get(), player)
    }

    private fun binding(): QueueIntentBinding {
        val snapshot = endpoint.exportSnapshot()
        val metadata = requireNotNull(endpoint.exportQueueIntentState())
        return QueueIntentBinding("phone", metadata.receiverId, metadata.revision,
            snapshot.queue.getOrNull(snapshot.position)?.key, snapshot.occurrence?.eventId)
    }
    private fun command(intent: String, selection: QueueIntentSelection) = PlaybackDeviceCommand.queueIntent(
        QueueIntentIntent.fromWire(intent), binding(), selection)
    private fun row(name: String): QueueIntentSelection.Track {
        val track = QueueIntentGolden.track(name, 1)
        return QueueIntentSelection.Track(JsonObject(PlaybackSnapshot(queue = listOf(track), position = 0)
            .toJson().getValue("queue").jsonArray.single().jsonObject.filterKeys { it != "key" && it != "order" }), null)
    }

    @Test fun frozenGoRecordsDriveActualOwnerTransactionsWithUnchangedCurrentNegativeEffects() = runBlocking {
        for (element in QueueIntentGolden.records()) {
            val record = element.jsonObject
            activeRead = false; syntheticStart = false; playerField.set(controller.get(), actual)
            val names = QueueIntentGolden.names(record, "queue")
            val tracks = names.mapIndexed { index, name -> QueueIntentGolden.track(name, index + 101L) }
            val position = record.getValue("position").jsonPrimitive.int
            val before = PlaybackSnapshot(queue = tracks, position = position, elapsed = if (tracks.isEmpty()) 0.0 else 42.0,
                duration = 120.0, occurrence = tracks.getOrNull(position)?.let { PlaybackOccurrence(it.key, it.id, "history_A", 3.125, true) })
            val high = QueueIntentGolden.names(record, "high").toSet()
            val state = QueueIntentState("foreign_receiver", 20, "tracked",
                tracks.map { QueueIntentOrigin(it.key, if (it.name in high) "high" else "low") }, tracks.filter { it.name in high }.map { it.key })
            assertTrue(endpoint.prepare(before, state))
            assertEquals("ok", endpoint.activate(before, state, permit).status)
            assertNotEquals(state.receiverId, endpoint.exportQueueIntentState()!!.receiverId)
            activeRead = record.getValue("active").jsonPrimitive.boolean
            proxy(); calls.clear()
            responseNames = QueueIntentGolden.names(record, "additions")
            val selectedName = record["selectionKey"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
            val selection = if (selectedName != null) QueueIntentSelection.Occurrence(tracks.single { it.name == selectedName }.key)
                else QueueIntentSelection.Collection(PlaybackContext("album", "vector", "Vector"))
            val result = endpoint.execute(command(record.getValue("intent").jsonPrimitive.content, selection), permit)
            val label = record.getValue("name").jsonPrimitive.content
            assertEquals(label, QueueIntentGolden.names(record, "expectedQueue"), result.snapshot.queue.map { it.name })
            assertEquals(label, QueueIntentGolden.names(record, "expectedHigh"), result.queueIntentState!!.highKeys.map { key -> result.snapshot.queue.single { it.key == key }.name })
            assertEquals(label, record.getValue("expectedCurrent").jsonPrimitive.content, result.snapshot.queue[result.snapshot.position].name)
            if (tracks.getOrNull(position)?.key == result.snapshot.queue[result.snapshot.position].key) {
                assertTrue(label, calls.none { it in setOf("prepare", "play", "pause", "stop", "setMediaItems") || it.startsWith("seekTo") })
                assertEquals(label, 42.0, result.snapshot.elapsed, 0.0)
                assertEquals(label, before.occurrence, result.snapshot.occurrence)
                assertEquals(label, "unchanged", result.queueIntentResult!!.playback)
                assertNull(result.queueIntentResult!!.playbackKey)
            } else {
                assertEquals(label, "blocked", result.queueIntentResult!!.playback)
                assertEquals(label, result.snapshot.queue[result.snapshot.position].key, result.queueIntentResult!!.playbackKey)
                assertEquals(label, 0, calls.count { it == "play" })
            }
            result.queueIntentResult!!.validated(result.snapshot, result.queueIntentState!!, result.status)
        }
    }

    @Test fun legacyAppendIsNonstartingAbsoluteAndCapableLegacyHopLosesOnlyPriority() = runBlocking {
        val tracks = listOf("A", "H1", "C1").mapIndexed { i, name -> QueueIntentGolden.track(name, i + 1L) }
        val snapshot = PlaybackSnapshot(queue = tracks, position = 0, elapsed = 42.0,
            occurrence = PlaybackOccurrence(1, "A", "history_A", 3.125, true))
        endpoint.activate(snapshot, QueueIntentState("foreign", 20, "tracked",
            tracks.map { QueueIntentOrigin(it.key, if (it.key == 2L) "high" else "low") }, listOf(2)), permit)
        proxy(); calls.clear()
        val result = endpoint.execute(PlaybackDeviceCommand.musical("enqueue", track = row("X").track, first = false), permit)
        assertEquals(listOf("A", "H1", "C1", "X"), result.snapshot.queue.map { it.name })
        assertTrue(calls.none { it == "play" || it == "prepare" || it.startsWith("seekTo") })
        val metadata = endpoint.exportQueueIntentState()!!
        assertEquals(listOf("H1", "X"), metadata.highKeys.map { k -> result.snapshot.queue.single { it.key == k }.name })
        assertEquals("legacy", metadata.origins.last().intent)
        val hopped = endpoint.activate(result.snapshot, permit)
        assertEquals(result.snapshot, hopped.snapshot)
        assertEquals("legacy-reset", hopped.queueIntentState!!.provenance)
        assertTrue(hopped.queueIntentState!!.highKeys.isEmpty())
        assertTrue(hopped.queueIntentState!!.origins.all { it.intent == "legacy" })
    }

    @Test fun sanctionedUpcomingAppendKeepsPendingStartAndOnlyItsOwnFirstAudibleHistoryBaseline() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            background(false)
            shadowOf(controller.get().getSystemService(AudioManager::class.java))
                .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            syntheticStart = true; holdStart = true; started = false; proxy(); calls.clear()
            val pending = async { endpoint.activate(PlaybackSnapshot(queue = listOf(QueueIntentGolden.track("A", 1)),
                position = 0, playing = true), permit) }
            runCurrent()
            assertFalse(pending.isCompleted)
            val token = requireNotNull(serviceField("startAttempt"))
            assertNull(endpoint.exportSnapshot().occurrence)
            val held = requireNotNull(PlaybackService.interactionKey())
            val added = endpoint.execute(PlaybackDeviceCommand.musical("enqueue", track = row("B").track, first = false), permit)
            assertEquals("ok", added.status)
            assertSame(token, serviceField("startAttemptValidity"))
            assertNotSame("The start exception does not refresh a held action", held, PlaybackService.interactionKey())
            val revision = requireNotNull(endpoint.exportQueueIntentState()).revision
            onSyntheticPlay = {
                // This suite's existing synthetic playing seam exercises first-audible history
                // bookkeeping, not device/acoustic acceptance or a history POST/backfill.
                PlaybackService::class.java.getDeclaredMethod("sampleHistory", androidx.media3.common.MediaItem::class.java,
                    java.lang.Long.TYPE, java.lang.Boolean.TYPE).apply { isAccessible = true }
                    .invoke(controller.get(), actual.currentMediaItem, 0L, true)
                assertNotNull(endpoint.exportSnapshot().occurrence)
                assertSame(token, serviceField("startAttemptValidity"))
            }
            syntheticState = Player.STATE_READY
            advanceTimeBy(30); runCurrent()
            val result = pending.await()
            assertEquals("ok", result.status)
            assertEquals(listOf("A", "B"), result.snapshot.queue.map { it.id })
            assertNotNull(result.snapshot.occurrence)
            assertEquals(revision + 1, requireNotNull(result.queueIntentState).revision)
            assertSame(token, serviceField("startAttemptValidity"))
            assertEquals(1, calls.count { it == "prepare" }); assertEquals(1, calls.count { it == "play" })
            assertEquals("No progression was observed", 0.0, result.snapshot.occurrence!!.progress, 0.0)
            assertFalse("First audible identity is not a history report/backfill", result.snapshot.occurrence!!.reported)
        } finally { onSyntheticPlay = null; Dispatchers.resetMain() }
    }

    @Test fun heldChangedCurrentStartKeepsOwnReceiptWhenPauseOrSemanticEditSupersedes() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (pauseOnly in listOf(true, false)) {
                playerField.set(controller.get(), actual); activeRead = false; syntheticStart = false
                endpoint.activate(PlaybackSnapshot(), permit)
                background(false)
                shadowOf(controller.get().getSystemService(AudioManager::class.java))
                    .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                syntheticStart = true; holdStart = true; started = false; proxy(); calls.clear()
                val intent = command("high", row("X"))
                val pending = async { endpoint.execute(intent, permit) }
                runCurrent()
                assertFalse(pending.isCompleted)
                val ownRevision = endpoint.exportQueueIntentState()!!.revision
                val pauses = calls.count { it == "pause" }; val stops = calls.count { it == "stop" }
                assertTrue(serviceField("audioFocus").let { it as PlaybackAudioFocus }.isGranted)
                assertTrue(serviceField("foreground").let { it as PlaybackForegroundNotification }.foregroundConfirmed)
                if (pauseOnly) assertTrue(endpoint.interruptQueueIntentStart())
                else endpoint.execute(PlaybackDeviceCommand.transport("repeat"), permit)
                advanceTimeBy(30); runCurrent()
                val result = pending.await()
                assertEquals("queued", result.queueIntentResult!!.outcome)
                assertEquals("superseded", result.queueIntentResult!!.playback)
                assertEquals(1, result.queueIntentResult!!.acceptedCount)
                assertEquals(ownRevision, result.queueIntentResult!!.commitRevision)
                assertNull(result.queueIntentResult!!.playbackKey)
                if (pauseOnly) assertEquals(ownRevision, result.queueIntentState!!.revision)
                else assertTrue(result.queueIntentState!!.revision > ownRevision)
                assertEquals(1, calls.count { it == "prepare" }); assertEquals(0, calls.count { it == "play" })
                assertTrue(calls.count { it == "pause" } > pauses); assertTrue(calls.count { it == "stop" } > stops)
                assertDrained()
            }
        } finally { Dispatchers.resetMain() }
    }

    private fun serviceField(name: String): Any? = PlaybackService::class.java.getDeclaredField(name).let {
        it.isAccessible = true; it.get(controller.get())
    }

    private fun assertDrained() {
        val raw = playerField.get(controller.get()) as ExoPlayer
        assertFalse(raw.playWhenReady); assertFalse(raw.isPlaying)
        assertFalse((serviceField("audioFocus") as PlaybackAudioFocus).isGranted)
        assertFalse((serviceField("foreground") as PlaybackForegroundNotification).foregroundConfirmed)
        assertTrue(shadowOf(controller.get()).isForegroundStopped)
        assertNull(serviceField("startAttempt"))
    }

    @Test fun semanticSupersessionAfterRawPlayDrainsResourcesAndRetainsOnlyItsOwnQueueReceipt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            endpoint.activate(PlaybackSnapshot(), permit)
            background(false)
            val audio = shadowOf(controller.get().getSystemService(AudioManager::class.java))
            audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            syntheticStart = true; holdStart = false; holdPlaying = true; started = false; wantsPlay = false
            proxy(); calls.clear()
            val pending = async { endpoint.execute(command("high", row("X")), permit) }
            runCurrent()
            assertFalse(pending.isCompleted)
            assertEquals(1, calls.count { it == "play" })
            assertTrue((playerField.get(controller.get()) as ExoPlayer).playWhenReady)
            assertFalse((playerField.get(controller.get()) as ExoPlayer).isPlaying)
            val ownSnapshot = endpoint.exportSnapshot()
            val ownRevision = endpoint.exportQueueIntentState()!!.revision
            val focusRequest = requireNotNull(audio.lastAudioFocusRequest).audioFocusRequest
            val ownerToken = requireNotNull(serviceField("startAttempt"))
            val pauses = calls.count { it == "pause" }; val stops = calls.count { it == "stop" }
            endpoint.execute(PlaybackDeviceCommand.transport("repeat"), permit)
            assertSame("Semantic cancellation retains the cleanup obligation until it drains", ownerToken, serviceField("startAttempt"))
            assertNull(serviceField("startAttemptValidity"))
            advanceTimeBy(30); runCurrent()
            val result = pending.await()
            val detail = requireNotNull(result.queueIntentResult)
            assertEquals("queued", detail.outcome); assertEquals("superseded", detail.playback)
            assertEquals(1, detail.acceptedCount); assertEquals("high", detail.placement)
            assertEquals(ownRevision, detail.commitRevision); assertNull(detail.playbackKey)
            assertNull(result.additiveTransition)
            assertEquals(ownSnapshot.queue, result.snapshot.queue)
            assertEquals(ownSnapshot.occurrence, result.snapshot.occurrence)
            assertEquals("all", result.snapshot.repeat)
            assertTrue(requireNotNull(result.queueIntentState).revision > ownRevision)
            assertFalse(result.snapshot.playing)
            assertTrue(calls.count { it == "pause" } > pauses); assertTrue(calls.count { it == "stop" } > stops)
            assertDrained()
            assertSame(focusRequest, audio.lastAbandonedAudioFocusRequest)
            // Release the output's delayed playing signal. A drained play intent cannot start.
            holdPlaying = false; started = wantsPlay && syntheticState == Player.STATE_READY
            advanceTimeBy(600); runCurrent()
            assertDrained()
            assertEquals(1, calls.count { it == "prepare" }); assertEquals(1, calls.count { it == "play" })
            detail.validated(result.snapshot, requireNotNull(result.queueIntentState), result.status)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun removalUndoAndClearRetainRequestedOriginsForCurrentAndPlayedHistory() = runBlocking {
        val tracks = listOf("played", "A", "H1").mapIndexed { i, name -> QueueIntentGolden.track(name, i + 1L) }
        val snapshot = PlaybackSnapshot(queue = tracks, position = 1, elapsed = 42.0,
            occurrence = PlaybackOccurrence(2, "A", "history_A", 3.125, true))
        val metadata = QueueIntentState("foreign", 8, "tracked", listOf(
            QueueIntentOrigin(1, "next"), QueueIntentOrigin(2, "now"), QueueIntentOrigin(3, "high")), listOf(3))
        endpoint.activate(snapshot, metadata, permit)
        endpoint.execute(PlaybackDeviceCommand.queue("remove", 3), permit)
        PlaybackService::class.java.getDeclaredMethod("undoRemoval").apply { isAccessible = true; invoke(controller.get()) }
        assertEquals(metadata.origins, endpoint.exportQueueIntentState()!!.origins)
        assertEquals(listOf(3L), endpoint.exportQueueIntentState()!!.highKeys)
        val cleared = endpoint.execute(PlaybackDeviceCommand.transport("clear"), permit)
        assertEquals(listOf("played", "A"), cleared.snapshot.queue.map { it.name })
        assertEquals(metadata.origins.take(2), cleared.queueIntentState!!.origins)
        assertEquals("tracked", cleared.queueIntentState!!.provenance)
        assertTrue(cleared.queueIntentState!!.highKeys.isEmpty())
        assertEquals(snapshot.occurrence, cleared.snapshot.occurrence)
        assertEquals(42.0, cleared.snapshot.elapsed, 0.0)
    }

    @Test fun actualServiceFocusPauseRetainsCommittedReceiptThroughCoordinatorPublication() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var owner: PlaybackCoordinator? = null
        try {
            requireNotNull(graph.playbackCoordinator).close()
            var published = PlaybackSnapshot()
            var metadata = requireNotNull(endpoint.exportQueueIntentState())
            var sequence = 0L
            fun deviceView() = PlaybackDevicesView("phone", listOf(PlaybackDevice("phone", "Phone", true)), "phone", 7, 8000,
                state = published, stateSeq = sequence, stateAgeMs = 0, transfer = null,
                directives = emptyList(), commands = emptyList(), queueIntents = QueueIntentsView(1,
                    listOf(QueueIntentReceiver("phone", 1)), QueueIntentBinding("phone", metadata.receiverId, metadata.revision,
                        published.queue.getOrNull(published.position)?.key, published.occurrence?.eventId), metadata, emptyList(), emptyList()))
            val protocol = object : PlaybackDevicesProtocol {
                override suspend fun view() = deviceView()
                override suspend fun probeQueueIntents() = deviceView()
                override suspend fun register(name: String) = deviceView()
                override suspend fun register(name: String, queueIntentsVersion: Int?) = deviceView()
                override suspend fun poll(deviceId: String, epoch: Long, stateSeq: Long?, state: PlaybackSnapshot?, acks: List<PlaybackAck>) =
                    poll(deviceId, epoch, stateSeq, state, acks, null)
                override suspend fun poll(deviceId: String, epoch: Long, stateSeq: Long?, state: PlaybackSnapshot?,
                    acks: List<PlaybackAck>, queueIntentState: QueueIntentState?): PlaybackDevicesView {
                    if (state != null) {
                        published = state; metadata = requireNotNull(queueIntentState); sequence = requireNotNull(stateSeq)
                        PlaybackPublication.validatePoll(deviceId, epoch, stateSeq, state, acks, queueIntentState)
                    }
                    return deviceView()
                }
                override suspend fun rename(deviceId: String, name: String) = deviceView()
                override suspend fun command(deviceId: String, epoch: Long, commandId: String, request: PlaybackDeviceCommand) = deviceView()
                override suspend fun transfer(deviceId: String, epoch: Long, commandId: String, targetDeviceId: String, selectionSeq: Long) = deviceView()
                override suspend fun leave(deviceId: String) = Unit
            }
            owner = PlaybackCoordinator(protocol, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
            AppGraph::class.java.getDeclaredField("playbackCoordinator").apply { isAccessible = true; set(graph, owner) }
            owner.setServiceActive(true); runCurrent()
            background(false)
            shadowOf(controller.get().getSystemService(AudioManager::class.java))
                .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            syntheticStart = true; holdStart = true; proxy(); calls.clear()
            var detail: QueueIntentResult? = null
            var completion: PlaybackEndpointResult? = null
            owner.queueIntent(QueueIntentIntent.HIGH, requireNotNull(owner.state.value.queueIntentBinding), row("X"),
                isCurrent = { true }, onResult = { detail = it }, onLocalCompleted = { completion = it })
            runCurrent()
            assertEquals(1, calls.count { it == "prepare" })
            val committed = endpoint.exportQueueIntentState()!!.revision
            val requestEpoch = PlaybackRequests.epoch
            // Actual focus/noisy callback path, not the endpoint hook in isolation.
            PlaybackService::class.java.getDeclaredMethod("interruptForSystemPause").apply {
                isAccessible = true; invoke(controller.get())
            }
            assertEquals(requestEpoch, PlaybackRequests.epoch)
            advanceTimeBy(30); runCurrent()
            assertEquals("superseded", detail?.playback)
            assertEquals(1, detail?.acceptedCount)
            assertEquals(committed, detail?.commitRevision)
            assertEquals(committed, completion?.queueIntentState?.revision)
            assertEquals(listOf("X"), published.queue.map { it.id })
            assertFalse(published.playing)
            assertEquals(0, calls.count { it == "play" })
        } finally { owner?.close(); Dispatchers.resetMain() }
    }

    @Test fun localGenericIdentityFencesSemanticRepeatAndHistoryWithoutTrackingOutputPresentation() = runBlocking {
        endpoint.activate(PlaybackSnapshot(queue = listOf(QueueIntentGolden.track("A", 1)), position = 0), permit)
        val held = requireNotNull(PlaybackService.interactionKey())
        val before = endpoint.exportSnapshot()
        val revision = requireNotNull(endpoint.exportQueueIntentState()).revision
        val queueEpoch = PlaybackRequests.queueEpoch
        activeRead = true; proxy()
        assertTrue(endpoint.exportSnapshot().waiting)
        assertSame("Buffering/play intent does not authorize or cancel a musical edit", held, PlaybackService.interactionKey())
        assertEquals(revision, endpoint.exportQueueIntentState()!!.revision)
        activeRead = false
        repeat(3) { endpoint.execute(PlaybackDeviceCommand.transport("repeat"), permit) }
        assertEquals(queueEpoch, PlaybackRequests.queueEpoch)
        assertEquals(before, endpoint.exportSnapshot())
        val afterRepeat = requireNotNull(PlaybackService.interactionKey())
        assertNotSame("Equal musical order after repeat ABA cannot refresh a held gesture", held, afterRepeat)
        val afterRepeatRevision = endpoint.exportQueueIntentState()!!.revision
        // Mutate a same-key traversal synchronously without exporting a snapshot first.
        PlaybackService::class.java.getDeclaredMethod("restartTraversal").apply { isAccessible = true; invoke(controller.get()) }
        val afterHistory = requireNotNull(PlaybackService.interactionKey())
        assertNotSame("The generic key itself observes a new current history event", afterRepeat, afterHistory)
        assertEquals(afterRepeatRevision + 1, endpoint.exportQueueIntentState()!!.revision)
        assertEquals(before.queue, endpoint.exportSnapshot().queue)
        assertNotNull(endpoint.exportSnapshot().occurrence)
    }

    @Test fun malformedHandoffIsRejectedWithoutReplacingCurrentAndUnsampledRepeatABAStalesBinding() = runBlocking {
        val track = QueueIntentGolden.track("A", 1)
        val snapshot = PlaybackSnapshot(queue = listOf(track), position = 0)
        endpoint.activate(snapshot, permit)
        val original = endpoint.exportSnapshot()
        assertFalse(endpoint.prepare(snapshot, QueueIntentState("foreign", 1, "tracked", emptyList(), emptyList())))
        assertEquals("rejected", endpoint.activate(snapshot, QueueIntentState("foreign", 1, "tracked", emptyList(), emptyList()), permit).status)
        assertEquals(original, endpoint.exportSnapshot())
        val held = command("high", row("X"))
        repeat(3) { endpoint.execute(PlaybackDeviceCommand.transport("repeat"), permit) }
        val result = endpoint.execute(held, permit)
        assertEquals("stale", result.queueIntentResult!!.outcome)
        assertEquals(original.queue, result.snapshot.queue)
    }
}
