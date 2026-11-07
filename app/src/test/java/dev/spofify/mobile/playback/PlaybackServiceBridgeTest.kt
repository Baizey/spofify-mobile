package dev.spofify.mobile.playback

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import dev.spofify.mobile.AppGraph
import dev.spofify.mobile.LoginStore
import dev.spofify.mobile.RememberedLogin
import dev.spofify.mobile.SpofifyApplication
import dev.spofify.mobile.core.PlaybackArtist
import dev.spofify.mobile.core.PlaybackContext
import dev.spofify.mobile.core.PlaybackDeviceCommand
import dev.spofify.mobile.core.PlaybackOccurrence
import dev.spofify.mobile.core.PlaybackSnapshot
import dev.spofify.mobile.core.PlaybackTrack
import dev.spofify.mobile.core.ServerAddress
import dev.spofify.mobile.core.SessionStore
import dev.spofify.mobile.core.SpofifyClient
import dev.spofify.mobile.core.StoredSession
import dev.spofify.mobile.feature.devices.PlaybackAuthority
import dev.spofify.mobile.feature.devices.PlaybackCoordinator
import dev.spofify.mobile.core.PlaybackAck
import dev.spofify.mobile.core.PlaybackDevicesProtocol
import dev.spofify.mobile.core.PlaybackDevice
import dev.spofify.mobile.core.PlaybackDirective
import dev.spofify.mobile.core.PlaybackDevicesView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.runBlocking
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

/**
 * Actual service + ExoPlayer + endpoint, not a fake NativePlaybackEndpoint.
 * Only synthetic loopback login is served. No test prepares an audio source or grants the
 * coordinator a live device lease. This covers paused import/commands, not audible handoff,
 * real buffered audio drainage, foreground-start restrictions, or system-controller routing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SpofifyApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackServiceBridgeTest {
    private lateinit var web: MockWebServer
    private lateinit var graph: AppGraph
    private lateinit var client: SpofifyClient
    private var controller: ServiceController<PlaybackService>? = null
    private val endpoint = Media3PlaybackEndpoint()
    private var loginCount = 0
    @Volatile private var derivedResponse: String? = null
    @Volatile private var preparationCount = 0

    private class MemorySessionStore : SessionStore {
        private var value: StoredSession? = null
        override fun read() = value
        override fun write(value: StoredSession?) { this.value = value }
    }

    private class MemoryLoginStore : LoginStore {
        private var value: RememberedLogin? = null
        override fun read() = value
        override fun write(value: RememberedLogin?) { this.value = value }
    }

    @Before fun setUp() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<SpofifyApplication>()
        app.graph.clear()
        graph = AppGraph(app, MemorySessionStore(), MemoryLoginStore())
        SpofifyApplication::class.java.getDeclaredField("graph").apply {
            isAccessible = true
            set(app, graph)
        }
        web = MockWebServer().apply {
            // Unexpected audio/history/device requests fail promptly instead of hanging behind
            // an empty response queue. Every test additionally asserts the exact request count.
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.method == "POST" && request.path == "/api/auth/login") {
                        MockResponse().setHeader("Content-Type", "application/json")
                            .setHeader("Set-Cookie", "spofify_session=synthetic-cookie; Path=/; HttpOnly; Max-Age=3600")
                            .setBody("""{"accountId":"fixture-account","username":"listener","role":"user","csrfToken":"synthetic-csrf"}""")
                    } else if (request.method == "POST" && request.path == "/api/me/playlists/rules/playback" && derivedResponse != null) {
                        val body = requireNotNull(derivedResponse); derivedResponse = null; preparationCount++
                        MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                    } else MockResponse().setResponseCode(500)
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
        // configure installs this callback. Remove it BEFORE the actual service is created:
        // these tests exercise the endpoint directly and must never register/poll a device.
        PlaybackService.onServiceActiveChanged = null
        login()
        controller = Robolectric.buildService(PlaybackService::class.java).create()
        assertEquals(Looper.getMainLooper(), player.applicationLooper)
        assertEquals(0, player.mediaItemCount)
        assertSilent()
    }

    @After fun tearDown() {
        PlaybackService.onServiceActiveChanged = null
        try {
            controller?.destroy()
        } finally {
            try {
                if (::graph.isInitialized) graph.clear()
            } finally {
                if (::web.isInitialized) web.shutdown()
            }
        }
    }

    private suspend fun login() {
        client.login("listener", "synthetic-password")
        loginCount++
        val request = requireNotNull(web.takeRequest(1, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertEquals("/api/auth/login", request.path)
        assertEquals(client.server.origin, request.getHeader("Origin"))
        assertEquals(loginCount + preparationCount, web.requestCount)
    }

    /** Reflection is read-only here: all player mutation goes through the real endpoint/service. */
    private val player: ExoPlayer
        get() = PlaybackService::class.java.getDeclaredField("player").let {
            it.isAccessible = true
            requireNotNull(it.get(requireNotNull(controller).get()) as ExoPlayer?)
        }

    private fun physicalMedia(): List<MediaItem> =
        (0 until player.mediaItemCount).map(player::getMediaItemAt)

    private fun authority(valid: () -> Boolean = { true }) = PlaybackAuthority(23, 4, valid)

    private fun snapshot(shuffle: Boolean = true, reported: Boolean = false): PlaybackSnapshot {
        fun track(key: Long, order: Long) = PlaybackTrack(
            id = "duplicate/a%", name = "Song occurrence $key",
            artists = listOf(PlaybackArtist("artist-a", "First Artist"), PlaybackArtist("artist-b", "Second Artist")),
            albumName = "Album", albumId = "album", duration = 123.456789,
            entryId = "entry-$key", targetId = "target-$key", occurrenceId = "長い occurrence / ".repeat(30) + key,
            playbackContext = PlaybackContext("derived", "mix-$key", "Mix $key", 19, "signed-fence-$key"),
            key = key, order = order,
        )
        // Realized traversal deliberately differs from the physical baseline for BOTH flags.
        val queue = listOf(track(91, 800), track(7, 0), track(32, 30), track(45, 11))
        return PlaybackSnapshot(queue = queue, position = 1,
            context = PlaybackContext("playlist", "saved-playlist", "Prepared queue", 8),
            shuffle = shuffle, repeat = "off", elapsed = 65.536, duration = 207.123456,
            playing = true, waiting = true, loading = true, truncated = true, notice = "CLICKED_OCCURRENCE_ONLY",
            occurrence = PlaybackOccurrence(7, queue[1].id, "original-history-nonce", if (reported) 0.325875 else 1.375875, reported),
        ).validated()
    }

    private fun assertSilent() {
        // Exercise actual service history ticks and posted Media3 callbacks while paused.
        // Do not run the looper until empty: the service deliberately has a repeating tick.
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        assertEquals(Player.STATE_IDLE, player.playbackState)
        assertFalse(player.playWhenReady)
        assertFalse(player.isPlaying)
        assertFalse(player.isLoading)
        assertNull("Only the consumed synthetic login requests are allowed", web.takeRequest(20, TimeUnit.MILLISECONDS))
        assertEquals("No unexpected audio, history, playlist fetch, or device protocol requests", loginCount + preparationCount, web.requestCount)
    }

    private suspend fun derivedQueue(): dev.spofify.mobile.core.PlaybackQueue {
        fun track(occurrence: String, token: String) = """{"kind":"track","id":"same-song","name":"Duplicate song","artists":[],"albumName":"Album","albumId":"album","durationMs":120000,"hasAudio":true,"occurrenceId":"$occurrence","selectionToken":"$token"}"""
        derivedResponse = """{"context":{"kind":"derived","id":"rules","name":"Original rules","revision":5,"fence":"original-fence"},"items":[${track("occ-1", "returned-token-1")},${track("occ-2", "returned-token-2")}],"total":2,"limit":2,"truncated":false,"revision":5,"fence":"original-fence","startOccurrenceId":"occ-2","notice":null}"""
        val response = dev.spofify.mobile.core.DerivedPlaylistsApi(client).playback("rules", 5, "original-fence", "original-signed-click")
        val request = requireNotNull(web.takeRequest(1, TimeUnit.SECONDS))
        assertEquals("/api/me/playlists/rules/playback", request.path)
        assertTrue(request.body.readUtf8().contains("original-signed-click"))
        return response.queue
    }

    private fun installOwner(scope: kotlinx.coroutines.CoroutineScope, clock: () -> Long, output: String,
        published: MutableList<PlaybackSnapshot> = mutableListOf(),
        initial: PlaybackSnapshot? = null,
        directives: List<PlaybackDirective> = emptyList(),
        onPoll: suspend (PlaybackSnapshot?, List<PlaybackAck>) -> Unit = { _, _ -> },
        viewTransform: (PlaybackDevicesView) -> PlaybackDevicesView = { it },
        command: (PlaybackDeviceCommand) -> Unit = {}): PlaybackCoordinator {
        graph.playbackCoordinator!!.close()
        val view = PlaybackDevicesView("phone", listOf(PlaybackDevice("phone", "Phone", true), PlaybackDevice("pc", "PC", true)),
            output, 1, 8000, state = initial, stateSeq = 0, stateAgeMs = 0, transfer = null, directives = directives, commands = emptyList())
        val api = object : PlaybackDevicesProtocol {
            override suspend fun register(name: String) = viewTransform(view)
            override suspend fun view() = viewTransform(view)
            override suspend fun poll(deviceId: String, epoch: Long, stateSeq: Long?, state: PlaybackSnapshot?, acks: List<PlaybackAck>): PlaybackDevicesView {
                state?.let(published::add); onPoll(state, acks); return viewTransform(view)
            }
            override suspend fun rename(deviceId: String, name: String) = view
            override suspend fun command(deviceId: String, epoch: Long, commandId: String, request: PlaybackDeviceCommand): PlaybackDevicesView {
                command(request); return view
            }
            override suspend fun transfer(deviceId: String, epoch: Long, commandId: String, targetDeviceId: String, selectionSeq: Long) = view
            override suspend fun leave(deviceId: String) = Unit
        }
        assertTrue(endpoint.supportsExactHandoff)
        return PlaybackCoordinator(api, endpoint, scope, clockMs = clock).also {
            AppGraph::class.java.getDeclaredField("playbackCoordinator").apply { isAccessible = true; set(graph, it) }
            it.setServiceActive(true)
        }
    }

    private fun connectedPlayback(existing: PlaybackConnection? = null): PlaybackConnection {
        val service = controller!!.get()
        val mediaSession = PlaybackService::class.java.getDeclaredField("session").let {
            it.isAccessible = true; it.get(service) as androidx.media3.session.MediaSession
        }
        val future = androidx.media3.session.MediaController.Builder(service, mediaSession.token).buildAsync()
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        val mediaController = future.get(1, TimeUnit.SECONDS)
        return (existing ?: PlaybackConnection(service)).also { connection ->
            // Only binding injection: Play, delivery, authority, queue import and transport still
            // use the actual production connection/service. Robolectric does not bind an AVD.
            PlaybackConnection::class.java.getDeclaredField("controller").apply { isAccessible = true; set(connection, mediaController) }
        }
    }

    private fun assertCurrentPreserved(before: PlaybackSnapshot, after: PlaybackSnapshot) {
        assertEquals(before.queue[before.position].key, after.queue[after.position].key)
        assertEquals(before.elapsed, after.elapsed, 0.0)
        assertEquals(before.duration, after.duration, 0.0)
        assertEquals(before.occurrence, after.occurrence)
        assertFalse(after.playing)
        assertFalse(after.waiting)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun originalDerivedRouteThroughViewModelConnectionAndServicePreservesDuplicateStartAndFence() = runTest {
        val published = mutableListOf<PlaybackSnapshot>()
        val owner = installOwner(backgroundScope, { testScheduler.currentTime }, "phone", published)
        runCurrent()
        val connection = connectedPlayback()
        val app = ApplicationProvider.getApplicationContext<SpofifyApplication>()
        val model = dev.spofify.mobile.LibraryViewModel(app, graph, connection)
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        shadowOf(controller!!.get()).setThrowInStartForeground(
            android.app.ForegroundServiceStartNotAllowedException("Synthetic background block"))
        val ticket = dev.spofify.mobile.feature.playlists.PlaylistPlaybackRequest(
            dev.spofify.mobile.feature.playlists.PlaylistRequestGeneration())
        val queue = derivedQueue()
        try {
            val generation = model.state.value.playbackIntentGeneration
            ticket.deliver { model.playPrepared(client, client.currentSession()!!.accountId, queue, null, "occ-2", generation) }
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS); runCurrent()
            val actual = published.last()
            assertEquals("blocked", actual.error)
            assertEquals(queue.preparedState!!.context, actual.context)
            assertEquals(5L, actual.context!!.revision)
            assertEquals("original-fence", actual.context!!.fence)
            assertEquals(listOf("same-song", "same-song"), actual.queue.map { it.id })
            assertEquals(listOf("occ-1", "occ-2"), actual.queue.map { it.occurrenceId })
            assertTrue(actual.queue.all { it.targetId == "rules" && it.playbackContext == actual.context })
            assertEquals("occ-2", actual.queue[actual.position].occurrenceId)
            assertEquals(1, player.currentMediaItemIndex)
            assertFalse(actual.toJson().toString().contains("original-signed-click"))
            assertFalse(queue.toString().contains("original-signed-click"))
            physicalMedia().forEach { media ->
                val extras = requireNotNull(media.mediaMetadata.extras)
                @Suppress("DEPRECATION") val values = extras.keySet().map { extras.get(it).toString() }.joinToString()
                assertFalse(values.contains("original-signed-click"))
                assertFalse(values.contains("original-fence"))
            }
            assertSilent()
        } finally { connection.release(); owner.close() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun derivedPreparedRemotePlayForwardsOriginalSelectionOnceAndRejectsExpiredOrReauthenticatedAuthority() = runTest {
        val commands = mutableListOf<PlaybackDeviceCommand>()
        val owner = installOwner(backgroundScope, { testScheduler.currentTime }, "pc") {
            commands += it
            throw dev.spofify.mobile.core.ApiException(409, "Synthetic expired selection", "SELECTION_STALE")
        }
        runCurrent()
        val connection = connectedPlayback()
        val model = dev.spofify.mobile.LibraryViewModel(ApplicationProvider.getApplicationContext<SpofifyApplication>(), graph, connection)
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        val ticket = dev.spofify.mobile.feature.playlists.PlaylistPlaybackRequest(
            dev.spofify.mobile.feature.playlists.PlaylistRequestGeneration())
        val queue = derivedQueue()
        try {
            assertFalse("ViewModel startup must finish before the route can be tapped", model.state.value.busy)
            assertNotNull(model.state.value.session)
            var delivered = false
            ticket.deliver {
                delivered = true
                model.playPrepared(client, client.currentSession()!!.accountId, queue, null, "occ-2", model.state.value.playbackIntentGeneration)
            }
            assertTrue("The original route preparation must remain current", delivered)
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS); runCurrent()
            assertEquals("busy=${model.state.value.busy}, signedIn=${model.state.value.session != null}, notice=${model.state.value.notice}, connection=${connection.state.value.error}, owner=${owner.state.value.message}", 1, commands.size)
            val command = commands.single()
            assertEquals("play", command.type)
            val expected = PlaybackDeviceCommand.musical("play", track = command.json.getValue("track") as kotlinx.serialization.json.JsonObject,
                context = queue.preparedState!!.context, occurrenceId = "occ-2", selectionToken = "original-signed-click")
            assertEquals(expected.json, command.json)
            assertFalse(command.toString().contains("original-signed-click"))
            assertEquals(0, player.mediaItemCount)
            assertEquals(1, preparationCount) // No refetch/revision substitution after remote rejection.
            login() // Same origin/account/client, NEW authentication generation.
            assertFalse(queue.selectionAuthority!!.isValidFor(client, queue.preparedState!!))
            model.playPrepared(client, client.currentSession()!!.accountId, queue, null, "occ-2", model.state.value.playbackIntentGeneration)
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS); runCurrent()
            assertEquals(1, commands.size)
            assertEquals(0, player.mediaItemCount)
            assertTrue(model.state.value.notice!!.contains("expired"))
            assertSilent()
        } finally { connection.release(); owner.close() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun derivedRouteCompletionAfterPauseCannotReserveANewPreparedIntentInTheActualBridge() = runTest {
        val published = mutableListOf<PlaybackSnapshot>()
        val owner = installOwner(backgroundScope, { testScheduler.currentTime }, "phone", published)
        runCurrent()
        val connection = connectedPlayback()
        val model = dev.spofify.mobile.LibraryViewModel(ApplicationProvider.getApplicationContext<SpofifyApplication>(), graph, connection)
        val ticket = dev.spofify.mobile.feature.playlists.PlaylistPlaybackRequest(
            dev.spofify.mobile.feature.playlists.PlaylistRequestGeneration())
        val queue = derivedQueue()
        owner.remoteCommand(PlaybackDeviceCommand.transport("pause")); runCurrent()
        val before = published.size
        var delivered = false
        ticket.deliver {
            delivered = true
            model.playPrepared(client, client.currentSession()!!.accountId, queue, null, "occ-2", model.state.value.playbackIntentGeneration)
        }
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS); runCurrent()
        assertFalse(delivered)
        assertEquals(before, published.size)
        assertEquals(0, player.mediaItemCount)
        assertSilent()
        connection.release(); owner.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun actualServiceFirstRegistrationAcceptsGenuinePreBindPlayButRejectsOldRequestsAfterPause() = runTest {
        controller!!.destroy(); controller = null
        graph.playbackCoordinator?.close()
        val gate = CompletableDeferred<Unit>()
        val view = PlaybackDevicesView("phone", listOf(PlaybackDevice("phone", "Phone", true)), "phone", 1,
            8000, state = null, stateSeq = 0, stateAgeMs = 0, transfer = null, directives = emptyList(), commands = emptyList())
        var registrations = 0
        val published = mutableListOf<PlaybackSnapshot>()
        val protocol = object : PlaybackDevicesProtocol {
            override suspend fun register(name: String): PlaybackDevicesView { registrations++; gate.await(); return view }
            override suspend fun view() = view
            override suspend fun poll(deviceId: String, epoch: Long, stateSeq: Long?, state: PlaybackSnapshot?, acks: List<PlaybackAck>): PlaybackDevicesView {
                state?.let(published::add); return view
            }
            override suspend fun rename(deviceId: String, name: String) = view
            override suspend fun command(deviceId: String, epoch: Long, commandId: String, command: PlaybackDeviceCommand) = view
            override suspend fun transfer(deviceId: String, epoch: Long, commandId: String, targetDeviceId: String, selectionSeq: Long) = view
            override suspend fun leave(deviceId: String) = Unit
        }
        assertTrue(endpoint.supportsExactHandoff)
        val owner = PlaybackCoordinator(protocol, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        AppGraph::class.java.getDeclaredField("playbackCoordinator").apply { isAccessible = true; set(graph, owner) }
        PlaybackService.onServiceActiveChanged = owner::setServiceActive
        // The explicit tap precedes the service binding that starts registration.
        val tapEpoch = PlaybackRequests.epoch
        controller = Robolectric.buildService(PlaybackService::class.java).create()
        shadowOf(controller!!.get()).setThrowInStartForeground(
            android.app.ForegroundServiceStartNotAllowedException("Synthetic background block"))
        val selected = dev.spofify.mobile.core.LibraryItem("track", "genuine-tap", "Explicit selection", hasAudio = true)
        val media = listOf(PlaybackMedia.create(selected, client))
        assertNull(PlaybackService.replacePrepared(media, client, 0, false, true, requestEpoch = tapEpoch))
        runCurrent()
        assertEquals(1, registrations); assertEquals(0, player.mediaItemCount)
        gate.complete(Unit); runCurrent()
        assertEquals(tapEpoch, PlaybackRequests.epoch)
        assertEquals("genuine-tap", player.currentMediaItem?.mediaId)
        assertEquals("blocked", published.last().error)
        assertFalse(player.playWhenReady)
        owner.remoteCommand(PlaybackDeviceCommand.transport("pause")); runCurrent()
        assertTrue(PlaybackRequests.epoch > tapEpoch)
        assertNotNull(PlaybackService.replacePrepared(media, client, 0, false, true, requestEpoch = tapEpoch))
        assertEquals("genuine-tap", player.currentMediaItem?.mediaId)
        assertSilent()
        owner.close()
    }

    /** Deliberately unselected room with separately held guarded write/activation ACK.
     * The endpoint is still the real Media3 service; only server progression is synthetic. */
    private class PreparedResumeRoom : PlaybackDevicesProtocol {
        var current = PlaybackDevicesView("phone", listOf(PlaybackDevice("phone", "Phone", true)), "", 3,
            0, state = PlaybackSnapshot(), stateSeq = 0, stateAgeMs = 0, transfer = null,
            directives = emptyList(), commands = emptyList())
        val claimEntered = CompletableDeferred<Unit>()
        val claimGate = CompletableDeferred<Unit>()
        val ackEntered = CompletableDeferred<Unit>()
        val ackGate = CompletableDeferred<Unit>()
        var claims = 0
        var ordinaryTransfers = 0
        var claimId: String? = null
        var staged: PlaybackSnapshot? = null
        private val delivered = mutableSetOf<String>()
        override suspend fun register(name: String) = current
        override suspend fun view() = current
        override suspend fun rename(deviceId: String, name: String) = current
        override suspend fun leave(deviceId: String) = Unit
        override suspend fun command(deviceId: String, epoch: Long, commandId: String, command: PlaybackDeviceCommand): PlaybackDevicesView =
            error("Prepared local Resume must not become a remote musical command")
        override suspend fun transfer(deviceId: String, epoch: Long, commandId: String, targetDeviceId: String, selectionSeq: Long): PlaybackDevicesView {
            ordinaryTransfers++
            error("Prepared Resume must never use ordinary takeover")
        }
        override suspend fun claimUnselected(deviceId: String, epoch: Long, commandId: String, selectionSeq: Long): PlaybackDevicesView {
            claims++; claimId = commandId; claimEntered.complete(Unit); claimGate.await()
            assertEquals(current.epoch, epoch)
            assertEquals("", current.activeDeviceId)
            current = current.copy(transfer = dev.spofify.mobile.core.PlaybackTransfer(commandId, "", deviceId, "preparing"),
                directives = listOf(PlaybackDirective("prepare-$commandId", epoch, "prepare", commandId, state = PlaybackSnapshot())),
                commands = listOf(dev.spofify.mobile.core.PlaybackOutcome(commandId, "pending")))
            return current.copy(directives = emptyList())
        }
        override suspend fun poll(deviceId: String, epoch: Long, stateSeq: Long?, state: PlaybackSnapshot?, acks: List<PlaybackAck>): PlaybackDevicesView {
            val id = claimId
            if (id != null && acks.any { it.deliveryId == "prepare-$id" && it.status == "ok" }) {
                current = current.copy(activeDeviceId = deviceId, epoch = epoch + 1, leaseMs = 8000,
                    transfer = dev.spofify.mobile.core.PlaybackTransfer(id, "", deviceId, "activating"),
                    directives = listOf(PlaybackDirective("activate-$id", epoch + 1, "activate", id, state = PlaybackSnapshot())))
            } else if (id != null && acks.any { it.deliveryId == "activate-$id" }) {
                assertEquals("ok", acks.single { it.deliveryId == "activate-$id" }.status)
                staged = requireNotNull(state)
                assertFalse(state.playing)
                ackEntered.complete(Unit); ackGate.await()
                current = current.copy(transfer = null, directives = emptyList(), state = state,
                    commands = listOf(dev.spofify.mobile.core.PlaybackOutcome(id, "ok")))
            }
            return current.copy(directives = current.directives.filter { delivered.add(it.deliveryId) })
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun inAppPreparedResumeCannotOutliveItsOriginDuringGuardedClaimOrActivationAck() = runTest {
        for (stage in listOf("claim", "ack")) for (cancellation in listOf("route-aba", "dispose", "connection-failure")) {
            graph.playbackCoordinator!!.close()
            val room = PreparedResumeRoom()
            val owner = PlaybackCoordinator(room, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
            AppGraph::class.java.getDeclaredField("playbackCoordinator").apply { isAccessible = true; set(graph, owner) }
            owner.setServiceActive(true); runCurrent()
            val live = snapshot().paused()
            assertEquals("ok", endpoint.activate(live, authority()).status)
            val connection = connectedPlayback()
            val binding = PlaybackConnection::class.java.getDeclaredField("controller").apply { isAccessible = true }
            val originalBinding = requireNotNull(binding.get(connection))
            val model = dev.spofify.mobile.LibraryViewModel(ApplicationProvider.getApplicationContext<SpofifyApplication>(), graph, connection)
            val store = androidx.lifecycle.ViewModelStore().apply { put("resume-origin", model) }
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            try {
                assertFalse(model.state.value.busy)
                val before = endpoint.exportSnapshot()
                val queueEpoch = PlaybackRequests.queueEpoch
                connection.toggle(); runCurrent()
                assertTrue("$stage/$cancellation must actually enter conditional acquisition", room.claimEntered.isCompleted)
                assertEquals(1, room.claims)
                assertFalse(player.playWhenReady)
                if (stage == "ack") {
                    room.claimGate.complete(Unit); runCurrent()
                    repeat(3) {
                        if (!room.ackEntered.isCompleted) { advanceTimeBy(1000); runCurrent() }
                    }
                    assertTrue("Hold the actual activation-ACK response, not only preparation", room.ackEntered.isCompleted)
                    assertEquals(before, room.staged)
                    assertEquals(queueEpoch, PlaybackRequests.queueEpoch)
                }
                when (cancellation) {
                    "route-aba" -> {
                        val route = model.state.value.page
                        model.open(dev.spofify.mobile.Page.Detail(dev.spofify.mobile.core.LibraryItem("album", "away", "Other route")))
                        assertNotEquals(route, model.state.value.page)
                        model.back(); assertEquals(route, model.state.value.page)
                    }
                    "dispose" -> store.clear()
                    else -> PlaybackConnection::class.java.getDeclaredMethod("retirePlayRequest").apply { isAccessible = true }.invoke(connection)
                }
                room.claimGate.complete(Unit); room.ackGate.complete(Unit); runCurrent()
                repeat(3) { advanceTimeBy(1000); runCurrent() }
                // Robolectric's service-component bind has no session binder here. Rebind the
                // same controls to the actual existing MediaSession, as at initial attachment;
                // only binding is synthetic, never the admission/queue/start path under test.
                connection.release()
                connectedPlayback(connection)
                assertNotSame("Recovery must exercise a new controller on the same live service", originalBinding, binding.get(connection))
                connection.connect() // Reattachment/recovery is never another Play authorization.
                shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS); runCurrent()
                if (cancellation == "dispose") {
                    // Even invoking a retained callback AFTER disposal cannot capture a fresh
                    // lifetime and resume a now-selected phone or issue another guarded claim.
                    connection.toggle(); runCurrent()
                }
                assertEquals("$stage/$cancellation must not replay its claim", 1, room.claims)
                assertEquals(0, room.ordinaryTransfers)
                assertEquals(before, endpoint.exportSnapshot())
                assertEquals(queueEpoch, PlaybackRequests.queueEpoch)
                assertFalse(player.playWhenReady)
                assertFalse(player.isPlaying)
                assertSilent()
            } finally {
                room.claimGate.complete(Unit); room.ackGate.complete(Unit)
                store.clear(); connection.release(); owner.close(); runCurrent()
            }
        }
    }

    private fun explicitQueue(id: String = "clicked") = dev.spofify.mobile.core.PlaybackQueue(
        listOf(dev.spofify.mobile.core.LibraryItem("track", id, "First duplicate", hasAudio = true, entryId = "entry-1"),
            dev.spofify.mobile.core.LibraryItem("track", id, "Clicked duplicate", hasAudio = true, entryId = "entry-2")),
        total = 2, context = dev.spofify.mobile.core.LibraryPlaybackContext("playlist", "manual", "Complete context"))

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun replaceableLatestPayloadCannotRefreshTheOriginalAdmissionRouteLifetime() = runTest {
        val owner = installOwner(backgroundScope, { testScheduler.currentTime }, "phone")
        runCurrent()
        val connection = connectedPlayback()
        val model = dev.spofify.mobile.LibraryViewModel(ApplicationProvider.getApplicationContext<SpofifyApplication>(), graph, connection)
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        val pending = PlaybackConnection::class.java.getDeclaredField("pendingPlay").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        fun guard(request: Any, name: String) = request.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(request) as () -> Boolean
        try {
            model.playPrepared(client, client.currentSession()!!.accountId, explicitQueue("first"), null, "entry-1")
            val first = requireNotNull(pending.get(connection))
            val payload = guard(first, "isCurrent")
            val lifetime = guard(first, "admissionIsCurrent")
            assertTrue(payload()); assertTrue(lifetime())
            model.playPrepared(client, client.currentSession()!!.accountId, explicitQueue("latest"), null, "entry-2")
            assertFalse("New Play replaces the old music, not its immutable admission lifetime", payload())
            assertTrue("Same-route latest Play may reuse a still-valid conditional claim", lifetime())
            val route = model.state.value.page
            model.open(dev.spofify.mobile.Page.Detail(dev.spofify.mobile.core.LibraryItem("album", "away", "Other route")))
            assertNotEquals(route, model.state.value.page)
            model.back()
            model.playPrepared(client, client.currentSession()!!.accountId, explicitQueue("after-aba"), null, "entry-2")
            assertEquals(route, model.state.value.page)
            assertFalse("A later same-route payload must never refresh the old admission's route generation", lifetime())
            val successor = requireNotNull(pending.get(connection))
            assertTrue(guard(successor, "isCurrent")())
            assertTrue(guard(successor, "admissionIsCurrent")())
            PlaybackConnection::class.java.getDeclaredMethod("retirePlayRequest").apply { isAccessible = true }.invoke(connection)
            assertFalse(guard(successor, "admissionIsCurrent")())
            assertEquals(0, player.mediaItemCount)
            assertSilent()
        } finally { connection.release(); owner.close() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun originatingRouteAbaAfterForwardingCannotInstallTheOldExplicitSelection() = runTest {
        var gate: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()
        val published = mutableListOf<PlaybackSnapshot>()
        val owner = installOwner(backgroundScope, { testScheduler.currentTime }, "phone", published,
            onPoll = { _, _ -> gate?.let { entered.complete(Unit); it.await() } })
        runCurrent()
        val connection = connectedPlayback()
        val model = dev.spofify.mobile.LibraryViewModel(ApplicationProvider.getApplicationContext<SpofifyApplication>(), graph, connection)
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        try {
            gate = CompletableDeferred()
            owner.retry(); runCurrent()
            assertTrue(entered.isCompleted)
            val route = model.state.value.page
            model.playPrepared(client, client.currentSession()!!.accountId, explicitQueue(), null, "entry-2")
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS); runCurrent()
            assertNull("The request must already have left Connection, not just be cancelled before forwarding",
                PlaybackConnection::class.java.getDeclaredField("pendingPlay").apply { isAccessible = true }.get(connection))
            assertEquals(0, player.mediaItemCount)
            model.open(dev.spofify.mobile.Page.Detail(dev.spofify.mobile.core.LibraryItem("album", "away", "Other route")))
            assertNotEquals(route, model.state.value.page)
            model.back()
            assertEquals(route, model.state.value.page)
            val held = gate!!; gate = null; held.complete(Unit); runCurrent()
            assertEquals("Unsampled route A→B→A must retire the originating request", 0, player.mediaItemCount)
            assertTrue(published.none { it.queue.isNotEmpty() })
            assertSilent()
        } finally { gate?.complete(Unit); connection.release(); owner.close() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun newerExplicitPlayAfterForwardingInstallsOnlyTheLatestExactPayload() = runTest {
        var gate: CompletableDeferred<Unit>? = null
        val published = mutableListOf<PlaybackSnapshot>()
        val owner = installOwner(backgroundScope, { testScheduler.currentTime }, "phone", published,
            onPoll = { _, _ -> gate?.await() })
        runCurrent()
        val connection = connectedPlayback()
        val model = dev.spofify.mobile.LibraryViewModel(ApplicationProvider.getApplicationContext<SpofifyApplication>(), graph, connection)
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        shadowOf(controller!!.get()).setThrowInStartForeground(
            android.app.ForegroundServiceStartNotAllowedException("Synthetic background block"))
        try {
            gate = CompletableDeferred(); owner.retry(); runCurrent()
            model.playPrepared(client, client.currentSession()!!.accountId, explicitQueue("obsolete"), null, "entry-1")
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS); runCurrent()
            assertNull(PlaybackConnection::class.java.getDeclaredField("pendingPlay").apply { isAccessible = true }.get(connection))
            model.playPrepared(client, client.currentSession()!!.accountId, explicitQueue("latest"), null, "entry-2")
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS); runCurrent()
            val held = gate!!; gate = null; held.complete(Unit); runCurrent()
            val actual = published.single { it.queue.isNotEmpty() }
            assertEquals(listOf("latest", "latest"), actual.queue.map { it.id })
            assertEquals(listOf("entry-1", "entry-2"), actual.queue.map { it.entryId })
            assertEquals("entry-2", actual.queue[actual.position].entryId)
            assertEquals("manual", actual.context!!.id)
            assertEquals("blocked", actual.error)
            assertFalse(player.playWhenReady)
            assertSilent()
        } finally { gate?.complete(Unit); connection.release(); owner.close() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun equalValuedReauthenticationAfterForwardingRetiresTheExactOriginalSession() = runTest {
        var gate: CompletableDeferred<Unit>? = null
        val published = mutableListOf<PlaybackSnapshot>()
        val owner = installOwner(backgroundScope, { testScheduler.currentTime }, "phone", published,
            onPoll = { _, _ -> gate?.await() })
        runCurrent()
        val connection = connectedPlayback()
        try {
            gate = CompletableDeferred(); owner.retry(); runCurrent()
            val original = client.currentSession()!!
            connection.playPrepared(explicitQueue(), client, startEntryId = "entry-2")
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS); runCurrent()
            assertNull(PlaybackConnection::class.java.getDeclaredField("pendingPlay").apply { isAccessible = true }.get(connection))
            login()
            assertEquals(original, client.currentSession())
            assertNotSame(original, client.currentSession())
            val held = gate!!; gate = null; held.complete(Unit); runCurrent()
            assertEquals(0, player.mediaItemCount)
            assertTrue(published.none { it.queue.isNotEmpty() })
            assertSilent()
        } finally { gate?.complete(Unit); connection.release(); owner.close() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun uncertainConnectionRetiresPendingAndForwardedPlayRatherThanReplayingOnReconnect() = runTest {
        val published = mutableListOf<PlaybackSnapshot>()
        val owner = installOwner(backgroundScope, { testScheduler.currentTime }, "phone", published)
        runCurrent()
        val connection = connectedPlayback()
        try {
            connection.playPrepared(explicitQueue(), client, startEntryId = "entry-2")
            val pending = PlaybackConnection::class.java.getDeclaredField("pendingPlay").apply { isAccessible = true }
            val request = requireNotNull(pending.get(connection))
            @Suppress("UNCHECKED_CAST")
            val current = request.javaClass.getDeclaredField("isCurrent").apply { isAccessible = true }.get(request) as () -> Boolean
            assertTrue(current())
            // Same production retirement boundary used by both failed buildAsync completion
            // and onDisconnected; no synthetic start/permit bypasses admission here.
            PlaybackConnection::class.java.getDeclaredMethod("retirePlayRequest").apply { isAccessible = true }.invoke(connection)
            assertFalse("Already-forwarded work must also lose its origin predicate", current())
            assertNull(pending.get(connection))
            connection.connect()
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS); runCurrent()
            assertEquals(0, player.mediaItemCount)
            assertTrue(published.none { it.queue.isNotEmpty() })
            assertSilent()
        } finally { connection.release(); owner.close() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun endedInAppResumeAdoptsOnlyItsOwnTraversalRestartWithoutLosingOriginFences() = runTest {
        for (cancelDuringRestart in listOf(false, true)) {
            val published = mutableListOf<PlaybackSnapshot>()
            val owner = installOwner(backgroundScope, { testScheduler.currentTime }, "phone", published)
            runCurrent()
            owner.localAction { endpoint.activate(snapshot().paused(), it) }; runCurrent()
            val connection = connectedPlayback()
            val model = dev.spofify.mobile.LibraryViewModel(ApplicationProvider.getApplicationContext<SpofifyApplication>(), graph, connection)
            val store = androidx.lifecycle.ViewModelStore().apply { put("ended-resume", model) }
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            val service = controller!!.get()
            val field = PlaybackService::class.java.getDeclaredField("player").apply { isAccessible = true }
            val real = player
            var ended = true
            var restarts = 0
            var prepares = 0
            var plays = 0
            // Only terminal output timing is controlled. Queue/position/history/seek and all
            // Resume admission still use the actual service and its service-created ExoPlayer.
            val terminalOutput = java.lang.reflect.Proxy.newProxyInstance(ExoPlayer::class.java.classLoader,
                arrayOf(ExoPlayer::class.java)) { proxy, method, args ->
                when (method.name) {
                    "equals" -> proxy === args?.get(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "getPlaybackState" -> if (ended) Player.STATE_ENDED else real.playbackState
                    else -> {
                        if (method.name == "prepare") prepares++
                        if (method.name == "play") plays++
                        val result = try { method.invoke(real, *(args ?: emptyArray())) }
                            catch (error: java.lang.reflect.InvocationTargetException) { throw error.targetException }
                        if (method.name == "seekToDefaultPosition") {
                            restarts++; ended = false
                            if (cancelDuringRestart) {
                                val route = model.state.value.page
                                model.open(dev.spofify.mobile.Page.Detail(dev.spofify.mobile.core.LibraryItem("album", "away", "Other route")))
                                assertNotEquals(route, model.state.value.page)
                                model.back(); assertEquals(route, model.state.value.page)
                            }
                        }
                        result
                    }
                }
            } as ExoPlayer
            shadowOf(service).setThrowInStartForeground(android.app.ForegroundServiceStartNotAllowedException("Synthetic start block"))
            try {
                field.set(service, terminalOutput)
                assertEquals(Player.STATE_ENDED, player.playbackState)
                val before = endpoint.exportSnapshot()
                val queueEpoch = PlaybackRequests.queueEpoch
                published.clear()
                connection.toggle(); runCurrent()
                val after = endpoint.exportSnapshot()
                assertEquals("Exactly the admitted ended traversal may restart", 1, restarts)
                assertEquals(before.queue, after.queue)
                assertEquals(before.position, after.position)
                assertEquals(queueEpoch, PlaybackRequests.queueEpoch)
                val restarted = requireNotNull(after.occurrence)
                assertEquals(before.occurrence!!.key, restarted.key)
                assertNotEquals(before.occurrence!!.eventId, restarted.eventId)
                assertEquals(0.0, restarted.progress, 0.0)
                assertFalse(restarted.reported)
                assertEquals(0L, player.currentPosition)
                assertEquals(0, prepares)
                assertEquals(0, plays)
                assertEquals(if (cancelDuringRestart) "" else "blocked", after.error)
                assertEquals(after, published.last())
                assertEquals("Later snapshots cannot invent another traversal receipt", restarted, endpoint.exportSnapshot().occurrence)
                field.set(service, real)
                assertSilent()
            } finally {
                field.set(service, real)
                store.clear(); connection.release(); owner.close(); runCurrent()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun inAppResumeWithoutALivePreparedOccurrenceDoesNotImportServerDisplayMusic() = runTest {
        val owner = installOwner(backgroundScope, { testScheduler.currentTime }, "phone", initial = snapshot().paused())
        runCurrent()
        val connection = connectedPlayback()
        try {
            assertEquals(0, player.mediaItemCount)
            connection.toggle(); runCurrent()
            assertEquals(0, player.mediaItemCount)
            assertTrue(connection.state.value.error!!.contains("Choose music"))
            assertSilent()
        } finally { connection.release(); owner.close() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun connectionPositivePlayOfCurrentOccurrencePreservesLiveQueuePositionAndHistory() = runTest {
        val retained = snapshot(false).paused().let { it.copy(queue = it.queue.map { track ->
            track.copy(targetId = "", occurrenceId = "", playbackContext = null)
        }) }
        val owner = installOwner(backgroundScope, { testScheduler.currentTime }, "phone")
        runCurrent()
        owner.localAction { endpoint.activate(retained, it) }; runCurrent()
        val connection = connectedPlayback()
        shadowOf(controller!!.get()).setThrowInStartForeground(
            android.app.ForegroundServiceStartNotAllowedException("Synthetic background block"))
        try {
            val before = endpoint.exportSnapshot()
            val epoch = PlaybackRequests.queueEpoch
            val queue = dev.spofify.mobile.core.PlaybackQueue(before.queue.map(PlaybackSnapshotCodec::libraryItem),
                total = before.queue.size, context = dev.spofify.mobile.core.LibraryPlaybackContext("playlist", "saved-playlist", "Prepared queue"))
            connection.playPrepared(queue, client, startEntryId = before.queue[before.position].entryId)
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS); runCurrent()
            val after = endpoint.exportSnapshot()
            assertEquals("blocked", after.error)
            assertEquals(before.queue, after.queue)
            assertEquals(epoch, PlaybackRequests.queueEpoch)
            assertCurrentPreserved(before, after)
            assertSilent()
        } finally { connection.release(); owner.close() }
    }

    @Test fun typedPreparedResumeActivationAcceptsNewAuthorityWithoutReinstallingQueueOrHistory() = runBlocking {
        val original = snapshot().paused()
        assertEquals("ok", endpoint.activate(original, authority()).status)
        val before = endpoint.exportSnapshot()
        val epoch = PlaybackRequests.queueEpoch
        val media = physicalMedia()
        assertEquals("ok", endpoint.activatePreparedResume(before, PlaybackAuthority(24, 5) { true }).status)
        assertEquals(epoch, PlaybackRequests.queueEpoch)
        assertEquals(media, physicalMedia())
        assertEquals(before, endpoint.exportSnapshot())
        assertCurrentPreserved(before, endpoint.exportSnapshot())
        assertSilent()
    }

    @Test fun typedPreparedResumeRejectsEmptyRestoredAndDifferentOccurrenceSnapshotsWithoutImporting() = runBlocking {
        val supplied = snapshot().paused()
        val emptyEpoch = PlaybackRequests.queueEpoch
        assertEquals("rejected", endpoint.activatePreparedResume(supplied, authority()).status)
        assertEquals(0, player.mediaItemCount)
        assertEquals(emptyEpoch, PlaybackRequests.queueEpoch)
        assertEquals("rejected", endpoint.activatePreparedResume(PlaybackSnapshot(), authority()).status)
        assertEquals("ok", endpoint.activate(supplied, authority()).status)
        val before = endpoint.exportSnapshot()
        val epoch = PlaybackRequests.queueEpoch
        val changed = before.copy(position = before.position + 1, occurrence = null)
        for (invalid in listOf(changed, before.copy(playing = true), before.copy(waiting = true), before.copy(loading = true))) {
            assertEquals("rejected", endpoint.activatePreparedResume(invalid, PlaybackAuthority(24, 5) { true }).status)
            assertEquals(before, endpoint.exportSnapshot())
            assertEquals(epoch, PlaybackRequests.queueEpoch)
        }
        assertEquals("rejected", endpoint.activatePreparedResume(before, PlaybackAuthority(24, 5) { false }).status)
        assertEquals(before, endpoint.exportSnapshot())
        assertEquals(epoch, PlaybackRequests.queueEpoch)
        assertSilent()
    }

    @Test fun unsupportedPreparedResumeEndpointFailsClosedWithoutGenericActivationFallback() = runBlocking {
        var genericActivations = 0
        val unsupported = object : dev.spofify.mobile.feature.devices.NativePlaybackEndpoint {
            override val supportsExactHandoff = true
            override fun suspendOutput() = Unit
            override fun exportSnapshot() = endpoint.exportSnapshot()
            override suspend fun prepare(snapshot: PlaybackSnapshot) = true
            override suspend fun activate(snapshot: PlaybackSnapshot, authority: PlaybackAuthority): dev.spofify.mobile.feature.devices.PlaybackEndpointResult {
                genericActivations++
                return endpoint.activate(snapshot, authority)
            }
            override suspend fun execute(command: PlaybackDeviceCommand, authority: PlaybackAuthority) = endpoint.execute(command, authority)
        }
        assertEquals("rejected", unsupported.activatePreparedResume(snapshot().paused(), authority()).status)
        assertEquals(0, genericActivations)
        assertEquals(0, player.mediaItemCount)
        assertSilent()
    }

    @Test fun genericExactPausedActivationStillReimportsInsteadOfUsingPreparedResumeShortcut() = runBlocking {
        assertEquals("ok", endpoint.activate(snapshot().paused(), authority()).status)
        val before = endpoint.exportSnapshot()
        val epoch = PlaybackRequests.queueEpoch
        assertEquals("ok", endpoint.activate(before, PlaybackAuthority(24, 5) { true }).status)
        assertTrue("Generic activation must retain its queue/provenance reset boundary", PlaybackRequests.queueEpoch > epoch)
        assertEquals(before, endpoint.exportSnapshot())
        assertSilent()
    }

    @Test fun pausedActivationRoundTripsExactWireStateForBothShuffleFlagsAndHistoryFlags() = runBlocking {
        for (shuffle in listOf(true, false)) for (reported in listOf(false, true)) {
            val imported = snapshot(shuffle, reported).paused()
            val result = endpoint.activate(imported, authority())
            assertEquals("ok", result.status)
            assertEquals(imported, result.snapshot)
            assertSilent()
            assertEquals(imported, endpoint.exportSnapshot())

            assertEquals(listOf("wire-7", "wire-45", "wire-32", "wire-91"), physicalMedia().map(PlaybackMedia::occurrence))
            assertEquals(0, player.currentMediaItemIndex)
            assertEquals(65_536L, player.currentPosition)
            // Inspect actual Media3 traversal, not only the service's wire export.
            val timeline = player.currentTimeline
            val traversal = PlaybackQueue.traversal(player.mediaItemCount, timeline.getFirstWindowIndex(player.shuffleModeEnabled)) {
                timeline.getNextWindowIndex(it, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            }
            assertEquals(listOf(3, 0, 2, 1), traversal)
            assertEquals(imported.queue.map { "wire-${it.key}" }, traversal.map { PlaybackMedia.occurrence(player.getMediaItemAt(it)) })
            val currentContext = requireNotNull(PlaybackService.queueContext())
            assertEquals("derived", currentContext.kind)
            assertEquals("mix-7", currentContext.id)
            physicalMedia().forEach { media ->
                assertEquals("${client.server.origin}/api/library/tracks/duplicate%2Fa%25/audio", media.localConfiguration!!.uri.toString())
                assertNull(media.mediaMetadata.artworkUri)
                assertNull(PlaybackMedia.libraryItem(media)!!.libraryPath)
            }
        }
    }

    @Test fun pausedImportRetainsSubMillisecondWireElapsedUntilAnActualLocalSeek() = runBlocking {
        val imported = snapshot().paused().copy(elapsed = 65.536123)
        val permit = authority()
        assertEquals(imported, endpoint.activate(imported, permit).snapshot)
        assertEquals(65_536L, player.currentPosition)
        assertSilent()
        assertEquals(imported, endpoint.exportSnapshot())
        val seek = endpoint.execute(PlaybackDeviceCommand.seek(65.7), permit)
        assertEquals(65.7, seek.snapshot.elapsed, 0.0)
        assertEquals(imported.occurrence, seek.snapshot.occurrence)
        assertSilent()
    }

    @Test fun prepareValidatesButCannotReplaceCurrentQueueAudioIntentHistoryOrRequestGeneration() = runBlocking {
        val imported = snapshot().paused()
        assertEquals("ok", endpoint.activate(imported, authority()).status)
        val beforeMedia = physicalMedia()
        val epoch = PlaybackRequests.epoch
        val candidate = snapshot(shuffle = false, reported = true).let {
            it.copy(queue = it.queue.reversed(), position = 2, elapsed = 90.125)
        }
        assertTrue(endpoint.prepare(candidate)) // Contains playing/loading intent, but is validation only.
        assertFalse(endpoint.prepare(candidate.copy(queue = listOf(candidate.queue[0], candidate.queue[0]), position = 0, occurrence = null)))
        assertEquals(epoch, PlaybackRequests.epoch)
        assertEquals(imported, endpoint.exportSnapshot())
        beforeMedia.zip(physicalMedia()).forEach { (before, after) -> assertSame(before, after) }
        assertSilent()
        assertEquals(imported, endpoint.exportSnapshot())
    }

    @Test fun revokedAuthorityCannotReplaceTheQueueOrExecuteEvenASilentCommand() = runBlocking {
        var valid = true
        val permit = authority { valid }
        val imported = snapshot().paused()
        assertEquals("ok", endpoint.activate(imported, permit).status)
        val beforeMedia = physicalMedia()
        valid = false
        val replacement = snapshot(shuffle = false, reported = true).paused().copy(elapsed = 8.0)
        assertEquals("rejected", endpoint.activate(replacement, permit).status)
        var checks = 0
        val expiresAtServiceBoundary = authority { ++checks == 1 }
        assertEquals("rejected", endpoint.activate(replacement, expiresAtServiceBoundary).status)
        assertTrue(checks >= 2) // Endpoint accepted it, but the actual service must check again.
        assertEquals("rejected", endpoint.execute(PlaybackDeviceCommand.transport("close"), permit).status)
        assertEquals(imported, endpoint.exportSnapshot())
        beforeMedia.zip(physicalMedia()).forEach { (before, after) -> assertSame(before, after) }
        assertSilent()
    }

    @Test fun suspendOutputKeepsPreparedStateSilentAndInvalidatesDeferredReplacementRequests() = runBlocking {
        val imported = snapshot().paused()
        assertEquals("ok", endpoint.activate(imported, authority()).status)
        val deferredEpoch = PlaybackRequests.epoch
        val media = physicalMedia()
        endpoint.suspendOutput()
        assertTrue(PlaybackRequests.epoch > deferredEpoch)
        assertFalse(player.playWhenReady)
        assertEquals(Player.STATE_IDLE, player.playbackState)
        // A real old UI replacement request must not escape through the coordinator afterward.
        assertNotNull(PlaybackService.replacePrepared(media, client, 0, false, true, requestEpoch = deferredEpoch))
        assertEquals(imported, endpoint.exportSnapshot())
        endpoint.suspendOutput() // Synchronous and safe to repeat; history identity is not reset.
        assertSilent()
        assertEquals(imported, endpoint.exportSnapshot())
    }

    @Test fun reauthenticationOnTheSameClientCannotAuthorizeTheOldServiceSession() = runBlocking {
        val imported = snapshot().paused()
        assertEquals("ok", endpoint.activate(imported, authority()).status)
        val originalSession = requireNotNull(client.currentSession())
        val media = physicalMedia()
        login() // Same account, origin, and client object; a NEW validated session identity.
        assertSame(client, graph.client)
        assertNotSame(originalSession, client.currentSession())
        assertEquals("rejected", endpoint.activate(snapshot(false, true).paused(), authority()).status)
        assertEquals("rejected", endpoint.execute(PlaybackDeviceCommand.transport("repeat"), authority()).status)
        assertNotNull(PlaybackService.replacePrepared(media, client, 0, false, true))
        assertEquals(imported, endpoint.exportSnapshot())
        assertSilent()
    }

    @Test fun pauseShuffleAndRepeatCommandsMutateTheActualServiceWithoutLoadingAudio() = runBlocking {
        val imported = snapshot(shuffle = false).paused()
        val permit = authority()
        assertEquals("ok", endpoint.activate(imported, permit).status)
        val paused = endpoint.execute(PlaybackDeviceCommand.transport("pause"), permit)
        assertEquals("ok", paused.status)
        assertEquals(imported, paused.snapshot)
        for ((repeat, media3Mode) in listOf("all" to Player.REPEAT_MODE_ALL, "one" to Player.REPEAT_MODE_ONE, "off" to Player.REPEAT_MODE_OFF)) {
            val result = endpoint.execute(PlaybackDeviceCommand.transport("repeat"), permit)
            assertEquals("ok", result.status)
            assertEquals(repeat, result.snapshot.repeat)
            assertEquals(media3Mode, player.repeatMode)
            assertCurrentPreserved(imported, result.snapshot)
        }
        val shuffled = endpoint.execute(PlaybackDeviceCommand.transport("shuffle"), permit)
        assertEquals("ok", shuffled.status)
        assertTrue(shuffled.snapshot.shuffle)
        assertEquals(listOf(91L, 7L), shuffled.snapshot.queue.take(2).map { it.key })
        assertEquals(setOf(32L, 45L), shuffled.snapshot.queue.drop(2).map { it.key }.toSet())
        assertCurrentPreserved(imported, shuffled.snapshot)
        val baselineFuture = endpoint.execute(PlaybackDeviceCommand.transport("shuffle"), permit)
        assertEquals("ok", baselineFuture.status)
        assertFalse(baselineFuture.snapshot.shuffle)
        assertEquals(listOf(91L, 7L, 45L, 32L), baselineFuture.snapshot.queue.map { it.key })
        assertCurrentPreserved(imported, baselineFuture.snapshot)
        assertSilent()
        assertEquals(baselineFuture.snapshot, endpoint.exportSnapshot())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun actualMediaControllerFreshPlayAndPauseReachOneServiceWithoutUnscopedReplay() = runTest {
        val imported = snapshot().paused()
        assertEquals("ok", endpoint.activate(imported, authority()).status)
        val view = PlaybackDevicesView("phone", listOf(PlaybackDevice("phone", "Phone", true)), "phone", 1,
            8000, state = imported, stateSeq = 0, stateAgeMs = 0, transfer = null, directives = emptyList(), commands = emptyList())
        val published = mutableListOf<PlaybackSnapshot>()
        val protocol = object : PlaybackDevicesProtocol {
            override suspend fun register(name: String) = view
            override suspend fun view() = view
            override suspend fun poll(deviceId: String, epoch: Long, stateSeq: Long?, state: PlaybackSnapshot?, acks: List<PlaybackAck>): PlaybackDevicesView {
                state?.let(published::add); return view
            }
            override suspend fun rename(deviceId: String, name: String) = view
            override suspend fun command(deviceId: String, epoch: Long, commandId: String, command: PlaybackDeviceCommand) = view
            override suspend fun transfer(deviceId: String, epoch: Long, commandId: String, targetDeviceId: String, selectionSeq: Long) = view
            override suspend fun leave(deviceId: String) = Unit
        }
        assertTrue(endpoint.supportsExactHandoff)
        graph.playbackCoordinator!!.close()
        val owner = PlaybackCoordinator(protocol, endpoint, backgroundScope, clockMs = { testScheduler.currentTime })
        AppGraph::class.java.getDeclaredField("playbackCoordinator").apply { isAccessible = true; set(graph, owner) }
        owner.setServiceActive(true); runCurrent()
        // Establish the coordinator's prepared local output once, before observing media Play.
        // System Play must never initialize/import a queue to make an unprepared request valid.
        owner.remoteCommand(PlaybackDeviceCommand.transport("pause")); runCurrent()
        published.clear()
        val service = controller!!.get()
        shadowOf(service).setThrowInStartForeground(android.app.ForegroundServiceStartNotAllowedException("Synthetic user-start restriction"))
        val mediaSession = PlaybackService::class.java.getDeclaredField("session").let {
            it.isAccessible = true; it.get(service) as androidx.media3.session.MediaSession
        }
        val future = androidx.media3.session.MediaController.Builder(service, mediaSession.token).buildAsync()
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        val mediaController = future.get(1, TimeUnit.SECONDS)
        try {
            mediaController.play()
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS); runCurrent()
            assertEquals(1, published.size)
            assertEquals("blocked", published.single().error) // Actual foreground rejection, not fake success.
            assertEquals(imported.queue, published.single().queue)
            mediaController.pause()
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS); runCurrent()
            assertEquals(2, published.size)
            assertFalse(player.playWhenReady)
            mediaController.seekToDefaultPosition()
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS); runCurrent()
            assertEquals(0L, player.currentPosition)
            val restarted = requireNotNull(endpoint.exportSnapshot().occurrence)
            assertNotEquals(imported.occurrence!!.eventId, restarted.eventId)
            assertEquals(0.0, restarted.progress, 0.0)
            // A fresh Play followed by Pause before coordinator work must not load/start later.
            mediaController.play(); mediaController.pause()
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS); runCurrent()
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS); runCurrent()
            assertFalse(player.playWhenReady)
            assertEquals(restarted, endpoint.exportSnapshot().occurrence)
            assertSilent()
        } finally { mediaController.release(); owner.close() }
    }

    @Test fun actualConnectionPostedTransportAndIndexedSelectionCannotBecomeNewIntentAfterCancellationOrReplacement() = runBlocking {
        val connection = PlaybackConnection(controller!!.get())
        val before = graph.playbackCoordinator!!.state.value
        Thread { connection.next() }.apply { start(); join() }
        PlaybackRequests.invalidate()
        shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
        assertEquals(before, graph.playbackCoordinator!!.state.value)
        assertEquals("ok", endpoint.activate(snapshot().paused(), authority()).status)
        Thread { connection.select(2) }.apply { start(); join() }
        assertEquals("ok", endpoint.activate(snapshot(false, true).paused(), authority()).status)
        shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
        assertEquals(before, graph.playbackCoordinator!!.state.value)
        connection.next() // Fresh direct route reaches coordinator; old silence wasn't a dead bridge.
        assertNotEquals(before.message, graph.playbackCoordinator!!.state.value.message)
        connection.release()
        assertSilent()
    }

    @Test fun actualFocusLossAndLateGainStopServiceIntentAndNeverAutoResume() = runBlocking {
        val service = controller!!.get()
        val focus = PlaybackService::class.java.getDeclaredField("audioFocus").let {
            it.isAccessible = true; it.get(service) as PlaybackAudioFocus
        }
        val audio = service.getSystemService(android.media.AudioManager::class.java)
        for (loss in listOf(android.media.AudioManager.AUDIOFOCUS_LOSS,
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)) {
            assertEquals("ok", endpoint.activate(snapshot().paused(), authority()).status)
            assertTrue(focus.requestForExplicitStart())
            val listener = requireNotNull(requireNotNull(shadowOf(audio).lastAudioFocusRequest).listener)
            player.playWhenReady = true
            val epoch = PlaybackRequests.epoch
            listener.onAudioFocusChange(loss)
            assertTrue(PlaybackRequests.epoch > epoch)
            assertFalse(focus.isGranted)
            assertFalse(player.playWhenReady)
            assertEquals(Player.STATE_IDLE, player.playbackState)
            listener.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_GAIN)
            assertSilent()
        }
    }

    @Test fun actualNoisyBroadcastCancelsPendingServiceOutputAndOldAuthorityTransitionStaysSilent() = runBlocking {
        var valid = true
        assertEquals("ok", endpoint.activate(snapshot().paused(), authority { valid }).status)
        player.playWhenReady = true
        val epoch = PlaybackRequests.epoch
        controller!!.get().sendBroadcast(android.content.Intent(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            .setPackage(controller!!.get().packageName))
        shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
        assertTrue(PlaybackRequests.epoch > epoch)
        assertSilent()
        // Also exercise the actual Media3 transition callback after authority is revoked.
        assertEquals("ok", endpoint.activate(snapshot().paused(), authority { valid }).status)
        valid = false
        player.playWhenReady = true
        player.seekToNextMediaItem()
        assertSilent()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun retainedNotificationTokenCannotBeUpgradedIntoANewPlaybackGeneration() = runTest {
        val commands = mutableListOf<PlaybackDeviceCommand>()
        installOwner(backgroundScope, { testScheduler.currentTime }, "pc", initial = snapshot().paused(), command = { commands += it })
        runCurrent()
        val service = controller!!.get()
        val make = PlaybackService::class.java.getDeclaredMethod("notificationIntent", String::class.java).apply { isAccessible = true }
        val old = make.invoke(service, "resume") as android.app.PendingIntent
        val oldIntent = shadowOf(old).savedIntent
        val oldEpoch = oldIntent.getLongExtra("generation", -1)
        endpoint.suspendOutput()
        val fresh = make.invoke(service, "resume") as android.app.PendingIntent
        assertNotEquals(old, fresh)
        assertNotEquals(oldIntent.data, shadowOf(fresh).savedIntent.data)
        assertEquals(oldEpoch, shadowOf(old).savedIntent.getLongExtra("generation", -1))
        val before = graph.playbackCoordinator!!.state.value
        old.send() // Deliver the HELD PendingIntent after the fresh token was minted.
        service.onStartCommand(requireNotNull(shadowOf(service).nextStartedService), 0, 1)
        runCurrent()
        assertEquals(before, graph.playbackCoordinator!!.state.value)
        assertTrue(commands.isEmpty())
        // A prepared authoritative remote owner makes the positive control meaningful:
        // an unregistered/viewless fixture must now fail closed even for a fresh token.
        fresh.send()
        service.onStartCommand(requireNotNull(shadowOf(service).nextStartedService), 0, 2)
        runCurrent()
        assertEquals(listOf("resume"), commands.map { it.type })
        assertSilent()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun retainedNotificationTokenCannotTargetAReplacementQueueInTheSameClientGeneration() = runTest {
        val commands = mutableListOf<PlaybackDeviceCommand>()
        installOwner(backgroundScope, { testScheduler.currentTime }, "pc", initial = snapshot().paused(), command = { commands += it })
        runCurrent()
        val service = controller!!.get()
        val make = PlaybackService::class.java.getDeclaredMethod("notificationIntent", String::class.java).apply { isAccessible = true }
        assertEquals("ok", endpoint.activate(snapshot().paused(), authority()).status)
        val old = make.invoke(service, "next") as android.app.PendingIntent
        val epoch = PlaybackRequests.epoch
        assertEquals("ok", endpoint.activate(snapshot(false, true).paused(), authority()).status)
        assertEquals(epoch, PlaybackRequests.epoch) // Queue replacement alone, not revocation.
        val fresh = make.invoke(service, "next") as android.app.PendingIntent
        assertNotEquals(old, fresh)
        val before = graph.playbackCoordinator!!.state.value
        old.send() // Not a copied stale Intent: exercise the token after UPDATE_CURRENT minting.
        service.onStartCommand(requireNotNull(shadowOf(service).nextStartedService), 0, 1)
        runCurrent()
        assertEquals(before, graph.playbackCoordinator!!.state.value)
        assertTrue(commands.isEmpty())
        fresh.send()
        service.onStartCommand(requireNotNull(shadowOf(service).nextStartedService), 0, 2)
        runCurrent()
        assertEquals(listOf("next"), commands.map { it.type })
        assertSilent()
    }

    @Test fun actualEmptyTimelineEndReleasesForegroundAndFocusWithoutAnyAutoResume() = runBlocking {
        val service = controller!!.get()
        val mediaSession = PlaybackService::class.java.getDeclaredField("session").let {
            it.isAccessible = true; it.get(service) as androidx.media3.session.MediaSession
        }
        val focus = PlaybackService::class.java.getDeclaredField("audioFocus").let {
            it.isAccessible = true; it.get(service) as PlaybackAudioFocus
        }
        val foreground = PlaybackService::class.java.getDeclaredField("foreground").let {
            it.isAccessible = true; it.get(service) as PlaybackForegroundNotification
        }
        // Seed successful public platform ownership, not an audible stream or fake endpoint.
        assertTrue(foreground.promote(mediaSession, null, true))
        assertTrue(focus.requestForExplicitStart())
        player.prepare() // An actual empty ExoPlayer reaches END without opening any source.
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        assertEquals(Player.STATE_ENDED, player.playbackState)
        assertFalse(foreground.foregroundConfirmed)
        assertFalse(focus.isGranted)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertFalse(player.playWhenReady)
        assertNull(web.takeRequest(20, TimeUnit.MILLISECONDS))
        assertEquals(loginCount, web.requestCount)
    }

    @Test fun newMusicalReplacementNeverReusesOldOccurrenceKeysInTheSameOwnerEpoch() = runBlocking {
        assertEquals("ok", endpoint.activate(snapshot().paused(), authority()).status)
        shadowOf(controller!!.get()).setThrowInStartForeground(
            android.app.ForegroundServiceStartNotAllowedException("Synthetic start restriction"))
        var oldMaximum = endpoint.exportSnapshot().queue.maxOf { it.key }
        for (id in listOf("new-song", "another-song")) {
            val wire = PlaybackSnapshot(queue = listOf(PlaybackTrack(id, "New selection", emptyList(), "", "", 120.0,
                key = 1, order = 0)), position = 0).toJson().getValue("queue") as kotlinx.serialization.json.JsonArray
            val result = endpoint.execute(PlaybackDeviceCommand.musical("play", track = wire.single() as kotlinx.serialization.json.JsonObject), authority())
            assertEquals("blocked", result.status)
            val current = result.snapshot.queue.single()
            assertEquals(id, current.id)
            assertTrue(current.key > oldMaximum)
            oldMaximum = current.key
            assertEquals("rejected", endpoint.execute(PlaybackDeviceCommand.queue("remove", 32), authority()).status)
            assertSilent()
        }
    }

    @Test fun positivePlayOfCurrentPreparedDerivedOccurrenceNeedsNoNewSelectionTokenOrFetch() = runBlocking {
        val imported = snapshot().paused()
        val permit = authority()
        assertEquals("ok", endpoint.activate(imported, permit).status)
        val wire = imported.toJson().getValue("queue") as kotlinx.serialization.json.JsonArray
        val positive = PlaybackDeviceCommand.musical("play", track = wire[imported.position] as kotlinx.serialization.json.JsonObject)
        player.playWhenReady = true
        assertEquals(imported.copy(playing = true), endpoint.execute(positive, permit).snapshot)
        assertEquals("ok", endpoint.execute(PlaybackDeviceCommand.transport("pause"), permit).status)
        shadowOf(controller!!.get()).setThrowInStartForeground(
            android.app.ForegroundServiceStartNotAllowedException("Synthetic background block"))
        val resumed = endpoint.execute(positive, permit)
        assertEquals("blocked", resumed.status)
        assertEquals(imported.copy(error = "blocked"), resumed.snapshot)
        assertSilent()
    }

    @Test fun positivePlayOfCurrentOccurrencePreservesPreparedQueuePositionAndHistoryWithoutFetch() = runBlocking {
        val imported = snapshot().paused().let { state -> state.copy(queue = state.queue.map {
            it.copy(targetId = "", occurrenceId = "", playbackContext = null)
        }) }
        val permit = authority()
        assertEquals("ok", endpoint.activate(imported, permit).status)
        val selected = imported.toJson().getValue("queue").let { it as kotlinx.serialization.json.JsonArray }[imported.position] as kotlinx.serialization.json.JsonObject
        val positive = PlaybackDeviceCommand.musical("play", track = selected, context = imported.context,
            entryId = imported.queue[imported.position].entryId)
        player.playWhenReady = true // Actual pending transport intent, no source prepared/opened.
        val playingResult = endpoint.execute(positive, permit)
        assertEquals("ok", playingResult.status)
        assertEquals(imported.copy(playing = true), playingResult.snapshot)
        assertEquals("ok", endpoint.execute(PlaybackDeviceCommand.transport("pause"), permit).status)
        shadowOf(controller!!.get()).setThrowInStartForeground(
            android.app.ForegroundServiceStartNotAllowedException("Synthetic background block"))
        val pausedResult = endpoint.execute(positive, permit)
        assertEquals("blocked", pausedResult.status)
        assertEquals(imported.copy(error = "blocked"), pausedResult.snapshot)
        assertSilent()
    }

    @Test fun legacyTogglePausesActualPendingOutputWithoutTrustingImportedPlayingMetadata() = runBlocking {
        val permit = authority()
        assertEquals("ok", endpoint.activate(snapshot().paused(), permit).status)
        // A raw player can have pending output intent while still IDLE/buffering. This is the
        // only direct player mutation in this test: prove resolution uses real playWhenReady,
        // not the paused imported metadata or isPlaying (which remains false until READY).
        player.playWhenReady = true
        assertFalse(player.isPlaying)
        val toggle = PlaybackDeviceCommand.fromJson(kotlinx.serialization.json.buildJsonObject {
            put("type", kotlinx.serialization.json.JsonPrimitive("toggle"))
        })
        assertEquals("ok", endpoint.execute(toggle, permit).status)
        assertFalse(player.playWhenReady)
        assertSilent()
        // With no queue the same incoming legacy command resolves to Resume, not a no-op Pause.
        assertEquals("ok", endpoint.execute(PlaybackDeviceCommand.transport("close"), permit).status)
        assertEquals("rejected", endpoint.execute(toggle, permit).status)
        assertSilent()
    }

    @Test fun individuallyPublishableDerivedEnqueueRejectsOversizedCombinedQueueBeforeAnyMutation() = runBlocking {
        val context = PlaybackContext("derived", "rules", "Original rules", 5, "original-fence")
        val original = PlaybackSnapshot(queue = (0 until 500).map { index ->
            PlaybackTrack("duplicate", "Song", emptyList(), "Album", "album", 120.0,
                targetId = "rules", occurrenceId = "old-" + "x".repeat(2000) + index,
                playbackContext = context, key = index + 1L, order = index.toLong())
        }, position = 0, context = context, elapsed = 31.25,
            occurrence = PlaybackOccurrence(1, "duplicate", "original-event", 17.875, true)).publicationValidated()
        val permit = authority()
        assertEquals("ok", endpoint.activate(original, permit).status)
        val mediaBefore = physicalMedia()
        fun occurrence(index: Int) = "new-" + "x".repeat(2000) + index
        val items = (0 until 500).joinToString { index ->
            """{"kind":"track","id":"duplicate","name":"Song","artists":[],"albumName":"Album","albumId":"album","durationMs":120000,"hasAudio":true,"occurrenceId":"${occurrence(index)}","selectionToken":"returned-token"}"""
        }
        derivedResponse = """{"context":{"kind":"derived","id":"rules","name":"Original rules","revision":5,"fence":"original-fence"},"items":[$items],"total":500,"limit":500,"truncated":false,"revision":5,"fence":"original-fence","startOccurrenceId":"${occurrence(0)}","notice":null}"""
        val result = endpoint.execute(PlaybackDeviceCommand.musical("enqueueContext", context = context, first = true), permit)
        assertEquals("rejected", result.status)
        assertEquals(original, result.snapshot)
        assertEquals(original, endpoint.exportSnapshot())
        assertEquals(mediaBefore, physicalMedia())
        assertEquals(500, player.mediaItemCount)
        assertEquals(1, preparationCount)
        assertEquals("/api/me/playlists/rules/playback", requireNotNull(web.takeRequest(1, TimeUnit.SECONDS)).path)
        val ordinary = original.queue.first().copy(targetId = "", occurrenceId = "", playbackContext = null)
        val track = PlaybackSnapshot(queue = listOf(ordinary), position = 0).toJson().getValue("queue") as kotlinx.serialization.json.JsonArray
        val added = endpoint.execute(PlaybackDeviceCommand.musical("enqueue", track = track.single() as kotlinx.serialization.json.JsonObject, first = false), permit)
        assertEquals("ok", added.status)
        // Legacy enqueue(false) is deliberately absolute, nonstarting append, not new High.
        // The rejected speculative collection must have consumed NO live keys.
        assertEquals(501, added.snapshot.queue.size)
        assertEquals(501L, added.snapshot.queue.last().key)
        assertEquals(original.queue, added.snapshot.queue.dropLast(1))
        assertEquals(original.occurrence, added.snapshot.occurrence)
        assertEquals(original.queue, added.snapshot.queue.filterNot { it.key == 501L })
        val metadata = requireNotNull(added.queueIntentState)
        assertEquals("legacy", metadata.origins.last().intent)
        assertEquals(listOf(501L), metadata.highKeys)
        assertSilent()
    }

    @Test fun longDerivedCommandResolvesThroughActualServiceWithoutManualEntryIdOverflow() = runBlocking {
        val occurrence = "derived-" + "x".repeat(1600)
        val context = PlaybackContext("derived", "rules", "Original rules", 5, "original-fence")
        fun track(id: String) = """{"kind":"track","id":"same-song","name":"Duplicate","artists":[],"durationMs":120000,"hasAudio":true,"occurrenceId":"$id","selectionToken":"returned-replacement"}"""
        derivedResponse = """{"context":{"kind":"derived","id":"rules","name":"Original rules","revision":5,"fence":"original-fence"},"items":[${track("first")},${track(occurrence)}],"total":2,"limit":2,"truncated":false,"revision":5,"fence":"original-fence","startOccurrenceId":"$occurrence","notice":null}"""
        shadowOf(controller!!.get()).setThrowInStartForeground(
            android.app.ForegroundServiceStartNotAllowedException("Synthetic background block"))
        val result = endpoint.execute(PlaybackDeviceCommand.musical("playCollection", context = context,
            occurrenceId = occurrence, selectionToken = "original-signed-click"), authority())
        assertEquals("blocked", result.status) // Resolution/import succeeded; Android start did not.
        assertEquals(listOf("first", occurrence), result.snapshot.queue.map { it.occurrenceId })
        assertTrue(result.snapshot.queue.all { it.entryId.isEmpty() && it.targetId == "rules" && it.playbackContext == context })
        assertEquals(occurrence, result.snapshot.queue[result.snapshot.position].occurrenceId)
        assertEquals(1, player.currentMediaItemIndex)
        assertEquals(result.snapshot, endpoint.exportSnapshot())
        val request = requireNotNull(web.takeRequest(1, TimeUnit.SECONDS))
        assertEquals("/api/me/playlists/rules/playback", request.path)
        assertTrue(request.body.readUtf8().contains("original-signed-click"))
        assertFalse(result.snapshot.toJson().toString().contains("returned-replacement"))
        assertEquals(1, preparationCount)
        assertSilent()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun incomingCloseKeepsRealServiceAliveForSingleAtomicAckThenDrivesDestructionCallback() = runTest {
        val gate = CompletableDeferred<Unit>()
        var attempts = 0
        val lifecycle = mutableListOf<Boolean>()
        val service = controller!!.get()
        val rawPlayer = player
        val directives = listOf(
            PlaybackDirective("final-close", 1, "command", command = PlaybackDeviceCommand.transport("close").json),
            PlaybackDirective("must-not-resume", 1, "command", command = PlaybackDeviceCommand.transport("resume").json),
        )
        val owner = installOwner(backgroundScope, { testScheduler.currentTime }, "phone",
            initial = snapshot().paused(), directives = directives, onPoll = { state, acks ->
                attempts++
                assertEquals(listOf(PlaybackAck("final-close", 1, "ok")), acks)
                assertEquals(PlaybackSnapshot(), state)
                assertEquals(PlaybackSnapshot(), endpoint.exportSnapshot())
                assertEquals(0, rawPlayer.mediaItemCount)
                assertFalse(rawPlayer.playWhenReady)
                assertFalse(shadowOf(service).isStoppedBySelf)
                assertTrue(shadowOf(service.getSystemService(android.app.NotificationManager::class.java)).allNotifications.isEmpty())
                assertTrue(lifecycle.isEmpty())
                gate.await()
            })
        PlaybackService.onServiceActiveChanged = { active -> lifecycle += active; owner.setServiceActive(active) }
        runCurrent()
        assertEquals(1, attempts)
        assertTrue(lifecycle.isEmpty())
        assertFalse(shadowOf(service).isStoppedBySelf)
        owner.remoteCommand(PlaybackDeviceCommand.transport("resume")) { false } // Rejected stale tap cannot disarm cleanup.
        gate.complete(Unit); runCurrent()
        assertEquals(listOf(false), lifecycle)
        assertFalse(owner.state.value.connected)
        assertTrue(shadowOf(service).isStoppedBySelf)
        controller!!.destroy(); controller = null // Drive Android lifecycle, not only ShadowService's stop bit.
        assertEquals(listOf(false), lifecycle)
        assertEquals(PlaybackSnapshot(), endpoint.exportSnapshot())
        assertEquals(1, attempts) // Neither returned nor remaining original directives were executed.
        owner.close()
    }

    @Test fun localCloseDisposesAfterUnknownPublicationFailureWithoutReplay() = assertCloseFailureDisposes("failure")
    @Test fun localCloseDisposesAfterBoundedPublicationTimeoutWithoutReplay() = assertCloseFailureDisposes("timeout")
    @Test fun localCloseDisposesOnCoordinatorCancellationEvenAfterRequestEpochInvalidation() = assertCloseFailureDisposes("cancel")

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun assertCloseFailureDisposes(mode: String) = runTest {
        var attempts = 0
        val lifecycle = mutableListOf<Boolean>()
        val service = controller!!.get()
        val owner = installOwner(backgroundScope, { testScheduler.currentTime }, "phone", onPoll = { state, acks ->
            attempts++
            assertEquals(PlaybackSnapshot(), state)
            assertTrue(acks.isEmpty()) // A local Close has no delivery ACK to invent.
            if (mode == "failure") error("Synthetic unknown final publication")
            kotlinx.coroutines.awaitCancellation()
        })
        PlaybackService.onServiceActiveChanged = { active -> lifecycle += active; owner.setServiceActive(active) }
        runCurrent()
        owner.remoteCommand(PlaybackDeviceCommand.transport("close")); runCurrent()
        if (mode == "timeout") { testScheduler.advanceTimeBy(4001); runCurrent() }
        if (mode == "cancel") { owner.close(); runCurrent() }
        assertEquals(1, attempts)
        assertEquals(listOf(false), lifecycle)
        assertTrue(shadowOf(service).isStoppedBySelf)
        controller!!.destroy(); controller = null
        assertEquals(listOf(false), lifecycle)
        assertEquals(PlaybackSnapshot(), endpoint.exportSnapshot())
        owner.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun newerAcceptedPlayDisarmsOldCloseCompletionAndHeldTimerBeforeWaitingForTransport() = runTest {
        val gate = CompletableDeferred<Unit>()
        var finalAttempts = 0
        var closing = true
        val lifecycle = mutableListOf<Boolean>()
        val owner = installOwner(backgroundScope, { testScheduler.currentTime }, "phone", onPoll = { state, _ ->
            if (closing && state?.queue?.isEmpty() == true) { finalAttempts++; gate.await() }
        })
        PlaybackService.onServiceActiveChanged = { active -> lifecycle += active; owner.setServiceActive(active) }
        runCurrent()
        owner.remoteCommand(PlaybackDeviceCommand.transport("close")); runCurrent()
        assertEquals(1, finalAttempts)
        val heldTimer = PlaybackService::class.java.getDeclaredField("pendingCloseDisposal").let {
            it.isAccessible = true; it.get(controller!!.get()) as Runnable
        }
        val replacement = snapshot().paused()
        owner.localAction { permit -> endpoint.activate(replacement, permit) }
        runCurrent() // New explicit intent is accepted, but still waits for final publication.
        closing = false; gate.complete(Unit); runCurrent()
        assertEquals(replacement, endpoint.exportSnapshot())
        heldTimer.run() // Deliver the OLD timer even after a newer queue has been imported.
        shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS)
        assertTrue(lifecycle.isEmpty())
        assertFalse(shadowOf(controller!!.get()).isStoppedBySelf)
        assertEquals(replacement, endpoint.exportSnapshot())
        assertEquals(1, finalAttempts)
        assertSilent()
        owner.close()
    }

    @Test fun replacementBecomingStaleAfterSubmissionReturnsCloseCleanupObligation() = assertQueuedReplacementCleanup("stale")
    @Test fun rejectedQueuedLocalActionReturnsCloseCleanupObligation() = assertQueuedReplacementCleanup("rejected-local")
    @Test fun rejectedQueuedServiceResumeReturnsCloseCleanupObligation() = assertQueuedReplacementCleanup("rejected-resume")
    @Test fun replacementCancelledBeforeCoroutineStartsReturnsCloseCleanupObligation() = assertQueuedReplacementCleanup("cancel-before-start")

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun assertQueuedReplacementCleanup(mode: String) = runTest {
        val gate = CompletableDeferred<Unit>()
        var closing = true
        var finalAttempts = 0
        val lifecycle = mutableListOf<Boolean>()
        val service = controller!!.get()
        val owner = installOwner(backgroundScope, { testScheduler.currentTime }, "phone", onPoll = { state, _ ->
            if (closing && state?.queue?.isEmpty() == true) { finalAttempts++; gate.await() }
        })
        PlaybackService.onServiceActiveChanged = { active -> lifecycle += active; owner.setServiceActive(active) }
        runCurrent()
        owner.remoteCommand(PlaybackDeviceCommand.transport("close")); runCurrent()
        var current = true
        when (mode) {
            "stale" -> owner.remoteCommand(PlaybackDeviceCommand.transport("resume")) { current }
            "rejected-resume" -> owner.remoteCommand(PlaybackDeviceCommand.transport("resume"))
            else -> owner.localAction { dev.spofify.mobile.feature.devices.PlaybackEndpointResult("rejected", endpoint.exportSnapshot()) }
        }
        if (mode == "cancel-before-start") owner.close()
        else runCurrent() // Replacement is queued behind the suspended final publication.
        current = false; closing = false; gate.complete(Unit); runCurrent()
        assertEquals(1, finalAttempts)
        assertEquals(listOf(false), lifecycle)
        assertTrue(shadowOf(service).isStoppedBySelf)
        controller!!.destroy(); controller = null
        assertEquals(listOf(false), lifecycle)
        assertEquals(PlaybackSnapshot(), endpoint.exportSnapshot())
        shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS)
        assertEquals(listOf(false), lifecycle) // Past the former fail-safe: cleanup is one-shot.
        owner.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun queuedReplacementRetainsBoundedWatchdogAndCannotExecuteAfterForcedDisposal() = runTest {
        val gate = CompletableDeferred<Unit>()
        var attempted = false
        var finalAttempts = 0
        val lifecycle = mutableListOf<Boolean>()
        val service = controller!!.get()
        val owner = installOwner(backgroundScope, { testScheduler.currentTime }, "phone", onPoll = { _, _ ->
            finalAttempts++; gate.await()
        })
        PlaybackService.onServiceActiveChanged = { active -> lifecycle += active; owner.setServiceActive(active) }
        runCurrent()
        owner.remoteCommand(PlaybackDeviceCommand.transport("close")); runCurrent()
        owner.localAction { permit -> attempted = true; endpoint.activate(snapshot().paused(), permit) }
        runCurrent()
        // Advance the service watchdog independently of coroutine publication timing. A
        // stalled transport must not make an uninstalled replacement hold disposal forever.
        shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.SECONDS); runCurrent()
        assertEquals(listOf(false), lifecycle)
        assertTrue(shadowOf(service).isStoppedBySelf)
        gate.complete(Unit); runCurrent()
        assertFalse(attempted)
        assertEquals(1, finalAttempts)
        controller!!.destroy(); controller = null
        owner.close()
    }

    @Test fun unclaimedCloseResultHasBoundedServiceDisposalEvenAfterSafetyRequestInvalidation() = runBlocking {
        val service = controller!!.get()
        val lifecycle = mutableListOf<Boolean>()
        PlaybackService.onServiceActiveChanged = { lifecycle += it }
        val result = endpoint.execute(PlaybackDeviceCommand.transport("close"), authority())
        assertEquals(PlaybackSnapshot(), result.snapshot)
        assertFalse(shadowOf(service).isStoppedBySelf)
        endpoint.suspendOutput() // Safety cancellation is NOT a newer accepted Play.
        shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS)
        assertEquals(listOf(false), lifecycle)
        assertTrue(shadowOf(service).isStoppedBySelf)
        result.finalClose!!.dispose() // Completion and fail-safe are one-shot.
        controller!!.destroy(); controller = null
        assertEquals(listOf(false), lifecycle)
    }

    @Test fun staleCloseCompletionAndTimerCannotDestroyReplacementAuthenticatedService() = runBlocking {
        val oldService = controller!!.get()
        val closed = endpoint.execute(PlaybackDeviceCommand.transport("close"), authority())
        val heldTimer = PlaybackService::class.java.getDeclaredField("pendingCloseDisposal").let {
            it.isAccessible = true; it.get(oldService) as Runnable
        }
        controller!!.destroy(); controller = null
        login() // New authentication generation and a new actual service instance.
        controller = Robolectric.buildService(PlaybackService::class.java).create()
        val replacement = snapshot().paused()
        assertEquals("ok", endpoint.activate(replacement, authority()).status)
        closed.finalClose!!.dispose(); heldTimer.run()
        assertFalse(shadowOf(controller!!.get()).isStoppedBySelf)
        assertEquals(replacement, endpoint.exportSnapshot())
        assertSilent()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun capturedLocalTransportUsesExistingNextAndPreviousRestartIntent() = runTest {
        val owner = installOwner(backgroundScope, { testScheduler.currentTime }, "phone")
        runCurrent()
        val connection = connectedPlayback()
        shadowOf(controller!!.get()).setThrowInStartForeground(
            android.app.ForegroundServiceStartNotAllowedException("Synthetic background block"))
        try {
            connection.connect()
            // Empty output retains a generic cancellation key for contextual queue actions,
            // but has no transport identity and cannot initialize music from a skip.
            shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
            assertNotNull(connection.state.value.interactionKey)
            assertNull(connection.state.value.transportInteractionKey)
            assertFalse(connection.state.value.canSkipNext)
            assertFalse(connection.state.value.canSkipPrevious)
            assertFalse(connection.next(PlaybackInteractionKey()))
            assertFalse(connection.previous(PlaybackInteractionKey()))
            assertEquals(0, player.mediaItemCount)
            // The same paused prepared intent as legacy transport: an explicit skip tries
            // to start via Android's real foreground gate; this fixture blocks before audio.
            for ((forward, position, elapsed, expected) in listOf(
                TransportCase(true, 1, 0.0, 32L),
                // Reaching terminal removes Next capability during the intended seek.
                // Still reach startOwned's real Android blocker, not stale rejection.
                TransportCase(true, 2, 0.0, 45L),
                TransportCase(false, 1, 1.0, 91L),
                TransportCase(false, 1, 65.536, 7L),
                TransportCase(false, 0, 1.0, 91L),
            )) {
                val prepared = snapshot().paused().copy(position = position, elapsed = elapsed, occurrence = null)
                owner.localAction { endpoint.activate(prepared, it) }; runCurrent()
                shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
                val state = connection.state.value
                val key = requireNotNull(state.transportInteractionKey)
                assertTrue(if (forward) state.canSkipNext else state.canSkipPrevious)
                assertTrue(if (forward) connection.next(key) else connection.previous(key))
                runCurrent()
                val result = endpoint.exportSnapshot()
                assertEquals(expected, result.queue[result.position].key)
                assertEquals("blocked", result.error)
                assertEquals(Player.STATE_IDLE, player.playbackState)
                assertFalse(player.playWhenReady)
            }
            assertEquals(loginCount, web.requestCount) // No prepare/fetch/audio/history HTTP.
        } finally { connection.release(); owner.close() }
        assertSilent()
    }

    private data class TransportCase(val forward: Boolean, val position: Int, val elapsed: Double, val expected: Long)

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun capturedLocalTransportDoesNotOutliveOriginatingConnectionReleaseOrReconnect() = runTest {
        var gate: CompletableDeferred<Unit>? = null
        var pollEntered: CompletableDeferred<Unit>? = null
        var pollFinished: CompletableDeferred<Unit>? = null
        val owner = installOwner(backgroundScope, { testScheduler.currentTime }, "phone",
            onPoll = { _, _ ->
                val waiting = gate
                if (waiting != null) {
                    pollEntered!!.complete(Unit)
                    try { waiting.await() } finally { pollFinished!!.complete(Unit) }
                }
            })
        runCurrent()
        shadowOf(controller!!.get()).setThrowInStartForeground(
            android.app.ForegroundServiceStartNotAllowedException("Synthetic background block"))
        try {
            for (forward in listOf(true, false)) for (reconnect in listOf(false, true)) {
                owner.localAction { endpoint.activate(snapshot().paused(), it) }; runCurrent()
                val connection = connectedPlayback()
                try {
                    connection.connect()
                    shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
                    val key = requireNotNull(connection.state.value.transportInteractionKey)
                    val rawKey = requireNotNull(connection.state.value.interactionKey)
                    val before = endpoint.exportSnapshot()
                    val queueEpoch = PlaybackRequests.queueEpoch
                    gate = CompletableDeferred(); pollEntered = CompletableDeferred(); pollFinished = CompletableDeferred()
                    owner.retry(); runCurrent()
                    assertTrue("poll owns coordinator transport before dispatch", pollEntered!!.isCompleted)
                    assertTrue(if (forward) connection.next(key) else connection.previous(key))
                    connection.release()
                    if (reconnect) {
                        // Rebind the SAME controls object to the SAME service; the service key
                        // is intentionally unchanged, so only the originating lifetime fences it.
                        connectedPlayback(connection)
                        connection.connect()
                    }
                    assertSame(rawKey, PlaybackService.interactionKey())
                    assertEquals("release must not stop or edit service playback", before, endpoint.exportSnapshot())
                    gate!!.complete(Unit); gate = null; runCurrent()
                    assertTrue("owning poll is fully drained", pollFinished!!.isCompleted)
                    assertEquals("stale direction=$forward reconnect=$reconnect must not seek/start", before, endpoint.exportSnapshot())
                    assertEquals(queueEpoch, PlaybackRequests.queueEpoch)
                    assertEquals(Player.STATE_IDLE, player.playbackState)
                    assertFalse(player.playWhenReady); assertFalse(player.isLoading)
                    assertEquals(loginCount, web.requestCount)
                } finally {
                    gate?.complete(Unit); gate = null
                    connection.release(); runCurrent()
                }
            }
        } finally { gate?.complete(Unit); owner.close() }
        assertSilent()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun pointerDownTransportKeyCannotReviveBeforeSubmissionAfterReleaseAndReconnect() = runTest {
        val owner = installOwner(backgroundScope, { testScheduler.currentTime }, "phone")
        runCurrent()
        shadowOf(controller!!.get()).setThrowInStartForeground(
            android.app.ForegroundServiceStartNotAllowedException("Synthetic background block"))
        try {
            for (forward in listOf(true, false)) for (deferred in listOf(false, true)) {
                owner.localAction { endpoint.activate(snapshot().paused(), it) }; runCurrent()
                val connection = connectedPlayback()
                val publish = PlaybackConnection::class.java.getDeclaredMethod("publish", java.lang.Boolean.TYPE).apply {
                    isAccessible = true
                }
                try {
                    val rawBeforeObservation = requireNotNull(PlaybackService.interactionKey())
                    publish.invoke(connection, false)
                    assertSame("generic local publication does not require UI observation", rawBeforeObservation, connection.state.value.interactionKey)
                    assertTrue(connection.state.value.canMoveUpcomingAbsolutely)
                    assertNull(connection.state.value.transportInteractionKey)
                    assertFalse(connection.state.value.canSkipNext)
                    assertFalse(connection.state.value.canSkipPrevious)
                    connection.connect()
                    shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
                    val state = connection.state.value
                    val held = requireNotNull(state.transportInteractionKey)
                    val rawKey = requireNotNull(state.interactionKey)
                    val before = endpoint.exportSnapshot()
                    val bindingField = PlaybackConnection::class.java.getDeclaredField("controller").apply { isAccessible = true }
                    val binding = bindingField.get(connection)
                    shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
                    assertSame("progress keeps transport projection stable", held, connection.state.value.transportInteractionKey)
                    if (deferred) {
                        // The injected controller has not refreshed the UI queue cache. Stage
                        // from the actual prepared fixture, not that empty display projection.
                        // Do not deliver it: this test owns/cancels the pending request below.
                        val preparedItems = before.queue.map(PlaybackSnapshotCodec::libraryItem)
                        assertTrue(preparedItems.isNotEmpty())
                        connection.play(preparedItems, client)
                        assertNotNull("deferred release requires an actually pending explicit tap",
                            PlaybackConnection::class.java.getDeclaredField("pendingPlay").apply {
                                isAccessible = true
                            }.get(connection))
                    }
                    connection.release()
                    assertNull(connection.state.value.transportInteractionKey)
                    if (deferred) {
                        assertSame("pending intent retains the controller", binding, bindingField.get(connection))
                        assertSame("deferred release preserves generic identity immediately", rawKey, connection.state.value.interactionKey)
                        assertTrue(connection.state.value.canMoveUpcomingAbsolutely)
                        publish.invoke(connection, false)
                        assertSame("deferred publications retain generic queue behavior", rawKey, connection.state.value.interactionKey)
                        assertTrue(connection.state.value.canMoveUpcomingAbsolutely)
                        assertNull(connection.state.value.transportInteractionKey)
                        assertFalse(connection.state.value.canSkipNext)
                        assertFalse(connection.state.value.canSkipPrevious)
                        connection.connect() // Cancels deferred release without replacing controller.
                        assertSame(binding, bindingField.get(connection))
                        PlaybackConnection::class.java.getDeclaredField("pendingPlay").apply {
                            isAccessible = true; set(connection, null)
                        } // The posted delivery now has no fixture request to execute.
                    } else {
                        connectedPlayback(connection)
                        connection.connect()
                        assertNotSame(binding, bindingField.get(connection))
                    }
                    assertSame("connection lifetime must not invalidate other controllers' generic key", rawKey, PlaybackService.interactionKey())
                    assertFalse(if (forward) connection.next(held) else connection.previous(held))
                    runCurrent()
                    assertEquals("unsampled pre-submission lifecycle ABA does not seek/start", before, endpoint.exportSnapshot())
                    shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
                    val fresh = requireNotNull(connection.state.value.transportInteractionKey)
                    assertNotSame(held, fresh)
                    assertSame(rawKey, connection.state.value.interactionKey)
                    assertFalse("generic keys are not a transport fallback", connection.next(rawKey))
                    assertTrue(if (forward) connection.next(fresh) else connection.previous(fresh))
                    runCurrent()
                    val after = endpoint.exportSnapshot()
                    assertEquals(if (forward) 32L else 7L, after.queue[after.position].key)
                    assertEquals("blocked", after.error) // Fresh transport reaches the normal start gate.
                    assertFalse(player.playWhenReady)
                } finally { connection.release() }
            }
        } finally { owner.close() }
        assertSilent()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun capturedLocalTransportRejectsUnsampledSelectedAvailabilityAbaBeforeDispatch() = runTest {
        var available = true
        val owner = installOwner(backgroundScope, { testScheduler.currentTime }, "phone",
            viewTransform = { view -> view.copy(devices = view.devices.map {
                if (it.deviceId == "phone") it.copy(available = available) else it
            }) })
        runCurrent()
        val connection = connectedPlayback()
        try {
            connection.connect()
            for (forward in listOf(true, false)) {
                owner.localAction { endpoint.activate(snapshot().paused(), it) }; runCurrent()
                shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
                val key = requireNotNull(connection.state.value.transportInteractionKey)
                val rawKey = requireNotNull(connection.state.value.interactionKey)
                val before = endpoint.exportSnapshot()
                val queueEpoch = PlaybackRequests.queueEpoch
                val authority = requireNotNull(owner.currentAuthority())
                val version = owner.interactionVersion
                // No connection/UI publication between accepted false and true states.
                available = false; owner.retry(); runCurrent()
                assertFalse(owner.state.value.devices.first { it.deviceId == "phone" }.available)
                available = true; owner.retry(); runCurrent()
                assertTrue(owner.state.value.devices.first { it.deviceId == "phone" }.available)
                val renewed = requireNotNull(owner.currentAuthority())
                assertEquals(authority.epoch, renewed.epoch)
                assertEquals(authority.generation, renewed.generation)
                assertEquals(version.first, owner.interactionVersion.first)
                assertEquals(version.second, owner.interactionVersion.second)
                assertTrue(owner.interactionVersion.third > version.third)
                assertSame("UI still holds the original pointer-down sample", key, connection.state.value.transportInteractionKey)
                assertFalse(if (forward) connection.next(key) else connection.previous(key))
                runCurrent()
                assertSame("transport availability must not invalidate generic queue receipts", rawKey, PlaybackService.interactionKey())
                assertEquals(before, endpoint.exportSnapshot())
                assertEquals(queueEpoch, PlaybackRequests.queueEpoch)
                assertEquals(Player.STATE_IDLE, player.playbackState)
                assertFalse(player.playWhenReady); assertFalse(player.isLoading)
                shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
                assertSame("pure availability ABA must retain the musical interaction key", rawKey, connection.state.value.interactionKey)
                assertNotSame("availability loss/regain retires the held transport key", key, requireNotNull(connection.state.value.transportInteractionKey))
                assertTrue(connection.state.value.canSkipNext); assertTrue(connection.state.value.canSkipPrevious)
            }
        } finally { connection.release(); owner.close() }
        assertSilent()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun transportKeysRejectUnsampledSameOutputSelectionWithoutInvalidatingGenericKeys() = runTest {
        shadowOf(controller!!.get()).setThrowInStartForeground(
            android.app.ForegroundServiceStartNotAllowedException("Synthetic background block"))
        for (output in listOf("phone", "pc")) for (forward in listOf(true, false)) {
            val commands = mutableListOf<PlaybackDeviceCommand>()
            val prepared = snapshot().paused()
            // installOwner's transfer returns the exact same view: no owner/epoch/queue
            // change can accidentally supply the cancellation this regression requires.
            val owner = installOwner(backgroundScope, { testScheduler.currentTime }, output,
                initial = if (output == "pc") prepared else null, command = commands::add)
            runCurrent()
            if (output == "phone") { owner.localAction { endpoint.activate(prepared, it) }; runCurrent() }
            val connection = connectedPlayback()
            try {
                connection.connect()
                shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
                val held = requireNotNull(connection.state.value.transportInteractionKey)
                val generic = requireNotNull(connection.state.value.interactionKey)
                val before = endpoint.exportSnapshot()
                val version = owner.interactionVersion
                val selection = owner.transportSelectionVersion
                owner.select(output)
                assertTrue(owner.state.value.selectionUnconfirmed)
                runCurrent() // Complete selection without any connection/UI publication.
                assertFalse(owner.state.value.selectionUnconfirmed)
                assertEquals(output, owner.state.value.activeDeviceId)
                assertEquals(version, owner.interactionVersion)
                assertEquals(selection + 1, owner.transportSelectionVersion)
                assertSame("UI still holds the pointer-down sample", held, connection.state.value.transportInteractionKey)
                assertFalse(if (forward) connection.next(held) else connection.previous(held))
                runCurrent()
                assertEquals(before, endpoint.exportSnapshot())
                assertTrue(commands.isEmpty())
                shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
                assertSame("selection fencing is transport-only, not generic receipt identity", generic, connection.state.value.interactionKey)
                val fresh = requireNotNull(connection.state.value.transportInteractionKey)
                assertNotSame(held, fresh)
                assertTrue(if (forward) connection.next(fresh) else connection.previous(fresh))
                runCurrent()
                if (output == "phone") {
                    val after = endpoint.exportSnapshot()
                    assertEquals(if (forward) 32L else 7L, after.queue[after.position].key)
                    assertEquals("blocked", after.error)
                } else {
                    assertEquals(listOf(PlaybackDeviceCommand.transport(if (forward) "next" else "previous")), commands)
                    assertEquals(before, endpoint.exportSnapshot())
                }
            } finally { connection.release(); owner.close() }
        }
        assertSilent()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun localLoadingAndReadyPresentationKeepSemanticAndTransportKeysWhenCapabilitiesDoNotChange() = runTest {
        val owner = installOwner(backgroundScope, { testScheduler.currentTime }, "phone")
        runCurrent()
        owner.localAction { endpoint.activate(snapshot().paused(), it) }; runCurrent()
        val connection = connectedPlayback()
        val service = requireNotNull(controller).get()
        val outputField = PlaybackService::class.java.getDeclaredField("player").apply { isAccessible = true }
        val real = player
        var state = Player.STATE_IDLE
        var loading = false
        // Only output presentation is controlled; real queue/timeline/commands remain intact.
        val output = java.lang.reflect.Proxy.newProxyInstance(ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java)) { _, method, args ->
            when (method.name) {
                "getPlaybackState" -> state
                "isLoading" -> loading
                else -> try { method.invoke(real, *(args ?: emptyArray())) }
                    catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
            }
        } as ExoPlayer
        try {
            connection.connect()
            shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
            val generic = requireNotNull(connection.state.value.interactionKey)
            val transport = requireNotNull(connection.state.value.transportInteractionKey)
            val before = endpoint.exportSnapshot()
            val revision = requireNotNull(endpoint.exportQueueIntentState()).revision
            outputField.set(service, output)
            for (next in listOf(Player.STATE_BUFFERING, Player.STATE_READY, Player.STATE_BUFFERING, Player.STATE_READY, Player.STATE_IDLE)) {
                state = next; loading = next == Player.STATE_BUFFERING
                assertEquals(next, player.playbackState); assertEquals(loading, player.isLoading)
                assertEquals(loading, endpoint.exportSnapshot().waiting)
                assertSame("loading/ready churn is not a semantic edit", generic, PlaybackService.interactionKey())
                assertEquals(revision, endpoint.exportQueueIntentState()!!.revision)
                assertFalse(player.playWhenReady)
            }
            assertEquals(before, endpoint.exportSnapshot())
            shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
            assertSame(generic, connection.state.value.interactionKey)
            assertSame("presentation alone does not change directional capability", transport, connection.state.value.transportInteractionKey)
        } finally { outputField.set(service, real); connection.release(); owner.close() }
        assertSilent()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun localNextCapabilityUsesRepeatWrappingAndSemanticRepeatAbaInvalidatesBothKeysBeforeDispatch() = runTest {
        val owner = installOwner(backgroundScope, { testScheduler.currentTime }, "phone")
        runCurrent()
        val connection = connectedPlayback()
        try {
            connection.connect()
            val terminal = snapshot().paused().copy(position = 3, occurrence = null)
            for ((mode, available) in listOf("off" to false, "one" to false, "all" to true)) {
                owner.localAction { endpoint.activate(terminal.copy(repeat = mode), it) }; runCurrent()
                shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
                val state = connection.state.value
                assertTrue(state.upcoming.isEmpty())
                assertEquals(available, state.canSkipNext)
                assertTrue(state.canSkipPrevious)
                if (!available) {
                    assertFalse(connection.next(requireNotNull(state.transportInteractionKey)))
                    runCurrent(); assertEquals(terminal.copy(repeat = mode), endpoint.exportSnapshot())
                }
            }
            val held = requireNotNull(connection.state.value.transportInteractionKey)
            val rawKey = requireNotNull(connection.state.value.interactionKey)
            val before = endpoint.exportSnapshot()
            val revision = requireNotNull(endpoint.exportQueueIntentState()).revision
            val queueEpoch = PlaybackRequests.queueEpoch
            assertTrue(connection.next(held)) // Waiting for serialized coordinator dispatch.
            val permit = requireNotNull(owner.currentAuthority())
            endpoint.execute(PlaybackDeviceCommand.transport("repeat"), permit) // all -> one: semantic edit AND capability lost
            val oneKey = requireNotNull(PlaybackService.interactionKey())
            assertNotSame(rawKey, oneKey)
            endpoint.execute(PlaybackDeviceCommand.transport("repeat"), permit) // one -> off: another semantic edit
            val offKey = requireNotNull(PlaybackService.interactionKey())
            assertNotSame(oneKey, offKey)
            endpoint.execute(PlaybackDeviceCommand.transport("repeat"), permit) // off -> all: equal state, newer semantic identity
            assertNotSame(offKey, PlaybackService.interactionKey())
            assertEquals(revision + 3, endpoint.exportQueueIntentState()!!.revision)
            assertEquals(queueEpoch, PlaybackRequests.queueEpoch)
            // Deliver service events but no UI publication is needed to invalidate held work.
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            val after = endpoint.exportSnapshot()
            runCurrent()
            assertEquals(after, endpoint.exportSnapshot())
            assertEquals("Repeat ABA restores the exact musical state, not its captured identity", before, after)
            assertNotSame("Repeat is semantic: a held musical intent must not borrow the new binding", rawKey, PlaybackService.interactionKey())
            assertFalse(connection.next(held))
            shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
            assertNotSame(held, requireNotNull(connection.state.value.transportInteractionKey))
            assertNotSame(rawKey, requireNotNull(connection.state.value.interactionKey))
            assertTrue(connection.state.value.canSkipNext) // Repeat-all wrapping was genuinely regained.
        } finally { connection.release(); owner.close() }
        assertSilent()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun capturedLocalTransportRechecksQueueAuthorityAndCurrentAbaBeforeExecution() = runTest {
        val owner = installOwner(backgroundScope, { testScheduler.currentTime }, "phone")
        runCurrent()
        val connection = connectedPlayback()
        shadowOf(controller!!.get()).setThrowInStartForeground(
            android.app.ForegroundServiceStartNotAllowedException("Synthetic background block"))
        try {
            connection.connect()
            for (race in listOf("queue", "current-aba", "authority", "reauth")) {
                owner.localAction { endpoint.activate(snapshot().paused(), it) }; runCurrent()
                shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
                val key = requireNotNull(connection.state.value.transportInteractionKey)
                assertTrue(connection.previous(key))
                val permit = requireNotNull(owner.currentAuthority())
                when (race) {
                    "queue" -> endpoint.execute(PlaybackDeviceCommand.queue("remove", 32), permit)
                    "current-aba" -> {
                        endpoint.execute(PlaybackDeviceCommand.queue("jump", 32), permit)
                        endpoint.execute(PlaybackDeviceCommand.transport("previous"), permit)
                    }
                    "authority" -> endpoint.suspendOutput()
                    "reauth" -> login()
                }
                shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
                val after = endpoint.exportSnapshot()
                runCurrent()
                assertEquals("stale $race must not execute Previous/restart", after, endpoint.exportSnapshot())
                assertFalse(connection.previous(key))
            }
        } finally { connection.release(); owner.close() }
        assertSilent()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun capturedRemoteDirectionsSubmitExactlyOneSemanticPreparedCommandAndReleaseFailsClosed() = runTest {
        for (forward in listOf(true, false)) {
            val commands = mutableListOf<PlaybackDeviceCommand>()
            val owner = installOwner(backgroundScope, { testScheduler.currentTime }, "pc",
                initial = snapshot().paused(), command = commands::add)
            runCurrent()
            val connection = connectedPlayback()
            try {
                connection.connect()
                shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
                val state = connection.state.value
                val key = requireNotNull(state.transportInteractionKey)
                assertTrue(state.canSkipNext); assertTrue(state.canSkipPrevious)
                assertTrue(if (forward) connection.next(key) else connection.previous(key))
                runCurrent()
                assertEquals(listOf(PlaybackDeviceCommand.transport(if (forward) "next" else "previous")), commands)
                assertEquals(0, player.mediaItemCount) // No remote-to-local fallback/import.
                connection.release()
                assertFalse(connection.next(key)); assertFalse(connection.previous(key))
                assertFalse(connection.state.value.canSkipNext); assertFalse(connection.state.value.canSkipPrevious)
                runCurrent(); assertEquals(1, commands.size)
            } finally { connection.release(); owner.close() }
        }
        assertSilent()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun genericRemoteKeyWithoutUiObservationDoesNotGrantMiniTransport() = runTest {
        val commands = mutableListOf<PlaybackDeviceCommand>()
        val owner = installOwner(backgroundScope, { testScheduler.currentTime }, "pc",
            initial = snapshot().paused(), command = commands::add)
        runCurrent()
        val connection = connectedPlayback() // Bound controller, deliberately not observing.
        fun key(method: String): PlaybackInteractionKey? = PlaybackConnection::class.java.getDeclaredMethod(method).let {
            it.isAccessible = true; it.invoke(connection) as PlaybackInteractionKey?
        }
        try {
            val generic = requireNotNull(key("currentRemoteInteractionKey"))
            assertNull(key("currentRemoteTransportKey"))
            assertFalse(connection.next(generic)); assertFalse(connection.previous(generic))
            assertSame("transport gating does not invalidate the generic queue identity", generic, key("currentRemoteInteractionKey"))
            runCurrent(); assertTrue(commands.isEmpty())
            connection.connect()
            shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
            val held = requireNotNull(connection.state.value.transportInteractionKey)
            connection.release()
            assertNull(key("currentRemoteTransportKey"))
            assertNotNull("generic remote actions retain their observation-independent contract", key("currentRemoteInteractionKey"))
            assertFalse(connection.next(held)); assertFalse(connection.previous(held))
            runCurrent(); assertTrue(commands.isEmpty())
        } finally { connection.release(); owner.close() }
        assertSilent()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun heldRemoteTransportSurvivesProgressBufferingAndBenignRenewalButNeverPostsOffMain() = runTest {
        val initial = snapshot().paused()
        var heartbeat = 0L
        var now = 0L
        val commands = mutableListOf<PlaybackDeviceCommand>()
        val owner = installOwner(backgroundScope, { now }, "pc", initial = initial,
            viewTransform = { view ->
                heartbeat++
                view.copy(stateSeq = heartbeat, leaseMs = 8000 - heartbeat % 3,
                    state = initial.copy(elapsed = initial.elapsed + heartbeat / 10.0,
                        waiting = heartbeat % 2 == 0L, loading = heartbeat % 3 == 0L))
            }, command = commands::add)
        runCurrent()
        val connection = connectedPlayback()
        try {
            connection.connect()
            shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
            val held = requireNotNull(connection.state.value.transportInteractionKey)
            var offMainAccepted = true
            Thread { offMainAccepted = connection.next(held) }.also { it.start(); it.join(1000); assertFalse(it.isAlive) }
            assertFalse(offMainAccepted)
            repeat(12) {
                now += 1000; owner.retry(); runCurrent()
                shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
                assertSame(held, connection.state.value.transportInteractionKey)
                assertTrue(connection.state.value.canSkipNext)
                assertTrue(connection.state.value.canSkipPrevious)
            }
            assertTrue(commands.isEmpty()) // No deferred off-main replay.
            assertTrue(connection.next(held)); runCurrent()
            assertEquals(listOf(PlaybackDeviceCommand.transport("next")), commands)
            assertEquals(0, player.mediaItemCount)
        } finally { connection.release(); owner.close() }
        assertSilent()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun capturedRemoteTransportRechecksCapabilityQueueOutputAndSessionAfterWait() = runTest {
        val commands = mutableListOf<PlaybackDeviceCommand>()
        var remote = snapshot().paused().copy(position = 3, repeat = "all", occurrence = null)
        var epoch = 1L
        var available = true
        var gate: CompletableDeferred<Unit>? = null
        var pollEntered: CompletableDeferred<Unit>? = null
        val owner = installOwner(backgroundScope, { testScheduler.currentTime }, "pc", initial = remote,
            onPoll = { _, _ -> pollEntered?.complete(Unit); gate?.await() },
            viewTransform = { view -> view.copy(state = remote, epoch = epoch,
                devices = view.devices.map { if (it.deviceId == "pc") it.copy(available = available) else it }) },
            command = commands::add)
        runCurrent()
        val connection = connectedPlayback()
        try {
            connection.connect()
            suspend fun race(change: () -> Unit) {
                shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
                assertTrue("each race starts with the same connected fixture owner", owner.state.value.connected)
                val key = requireNotNull(connection.state.value.transportInteractionKey)
                gate = CompletableDeferred()
                pollEntered = CompletableDeferred()
                owner.retry(); runCurrent()
                assertTrue("the poll must own transport before the captured submission", pollEntered!!.isCompleted)
                assertTrue(connection.next(key))
                val dispatchTime = testScheduler.currentTime
                change()
                assertEquals("fixture mutation must not advance the held poll's timeout", dispatchTime, testScheduler.currentTime)
                gate!!.complete(Unit); gate = null; runCurrent()
                assertTrue("a stale-key rejection must not be a disconnected-poll false positive", owner.state.value.connected)
                assertTrue(commands.isEmpty())
                assertEquals(0, player.mediaItemCount)
                assertFalse(connection.next(key))
            }
            race { remote = remote.copy(repeat = "off") } // Same queue/current, capability removed.
            remote = remote.copy(repeat = "all"); owner.retry(); runCurrent()
            race { remote = remote.copy(queue = remote.queue.reversed()) }
            race { epoch++ }
            // Login uses real OkHttp/MockWebServer I/O. Do not suspend this runTest body:
            // auto-advancing its scheduler while the real response is in flight can expire
            // the gated poll's 4s protocol timeout and disconnect the NEXT race's owner.
            // A non-suspending mutation callback enforces ownership of that virtual clock.
            race { runBlocking { login() } }
            race { available = false }
        } finally { gate?.complete(Unit); connection.release(); owner.close() }
        assertSilent()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun timedOutHeldRemotePollRevokesTransportIdentityWithoutReplayingCapturedCommand() = runTest {
        val commands = mutableListOf<PlaybackDeviceCommand>()
        val gate = CompletableDeferred<Unit>()
        val pollEntered = CompletableDeferred<Unit>()
        val pollFinished = CompletableDeferred<Unit>()
        val owner = installOwner(backgroundScope, { testScheduler.currentTime }, "pc", initial = snapshot().paused(),
            onPoll = { _, _ ->
                pollEntered.complete(Unit)
                try { gate.await() } finally { pollFinished.complete(Unit) }
            }, command = commands::add)
        runCurrent() // Registration is synchronous; only explicit retry below enters onPoll.
        val connection = connectedPlayback()
        try {
            connection.connect()
            shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
            val key = requireNotNull(connection.state.value.transportInteractionKey)
            owner.retry(); runCurrent()
            assertTrue(pollEntered.isCompleted)
            assertFalse(pollFinished.isCompleted)
            assertTrue(connection.next(key))
            // Exact production request deadline, not a wall-clock wait or idle heuristic.
            // This is the failure mode the real-I/O race fixture above must NOT trigger.
            testScheduler.advanceTimeBy(4000); runCurrent()
            assertTrue("timeout cancellation drains the gate's owning poll", pollFinished.isCompleted)
            assertFalse(owner.state.value.connected)
            assertTrue(commands.isEmpty())
            shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
            assertNull(connection.state.value.transportInteractionKey)
            assertFalse(connection.state.value.canSkipNext)
            assertFalse(connection.state.value.canSkipPrevious)
            gate.complete(Unit); runCurrent()
            assertFalse(connection.next(key))
            assertTrue("completing an abandoned gate cannot replay stale transport", commands.isEmpty())
            assertEquals(0, player.mediaItemCount)
        } finally { gate.complete(Unit); connection.release(); owner.close() }
        assertSilent()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun capturedRemoteNextRejectsUnsampledQueueCurrentAndCapabilityLossRegainAba() = runTest {
        val initial = snapshot().paused().copy(position = 3, repeat = "all", occurrence = null)
        val commands = mutableListOf<PlaybackDeviceCommand>()
        val responses = ArrayDeque<PlaybackSnapshot>()
        var sequence = 0L
        var gate: CompletableDeferred<Unit>? = null
        val owner = installOwner(backgroundScope, { testScheduler.currentTime }, "pc", initial = initial,
            onPoll = { _, _ -> gate?.await() },
            viewTransform = { it.copy(state = responses.removeFirstOrNull() ?: initial, stateSeq = ++sequence) },
            command = commands::add)
        runCurrent()
        val connection = connectedPlayback()
        try {
            connection.connect()
            shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
            for (changed in listOf(
                initial.copy(queue = initial.queue.reversed()),
                initial.copy(position = 0),
                initial.copy(repeat = "off"), // Next disappears then returns, same queue/current.
            )) {
                val held = requireNotNull(connection.state.value.transportInteractionKey)
                val generic = requireNotNull(connection.state.value.interactionKey)
                val genericVersion = owner.interactionVersion
                val capabilityVersion = owner.transportCapabilityVersion
                assertTrue(connection.state.value.canSkipNext)
                gate = CompletableDeferred()
                owner.retry(); runCurrent()
                owner.retry(); runCurrent()
                assertTrue(connection.next(held))
                responses.addLast(changed); responses.addLast(initial)
                gate!!.complete(Unit); gate = null
                // Both accepted publications precede dispatch; no UI sample observes B.
                runCurrent()
                assertEquals(initial, owner.state.value.view!!.state)
                assertTrue(responses.isEmpty()); assertTrue(commands.isEmpty())
                shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
                assertNotSame(held, connection.state.value.transportInteractionKey)
                assertFalse(connection.next(held))
                assertTrue(connection.state.value.canSkipNext)
                if (changed.repeat == "off") {
                    assertEquals("capability-only ABA leaves generic binding version unchanged", genericVersion, owner.interactionVersion)
                    assertSame("generic remote queue receipts survive capability-only publications", generic, connection.state.value.interactionKey)
                    assertTrue(owner.transportCapabilityVersion > capabilityVersion)
                }
            }
            assertEquals(0, player.mediaItemCount)
        } finally { gate?.complete(Unit); connection.release(); owner.close() }
        assertSilent()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun guardedAbsoluteDropCommitsOnceAndProgressNoopAndCancelDoNotEdit() = runTest {
        val owner = installOwner(backgroundScope, { testScheduler.currentTime }, "phone")
        runCurrent()
        val imported = snapshot().paused()
        owner.localAction { permit -> endpoint.activate(imported, permit) }
        runCurrent()
        val connection = connectedPlayback()
        try {
            connection.connect()
            shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
            val state = connection.state.value
            val key = requireNotNull(state.interactionKey)
            assertTrue(state.canMoveUpcomingAbsolutely)
            assertSame(key, PlaybackService.interactionKey())
            shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
            assertSame("elapsed/duration/buffering polling does not replace the key", key, connection.state.value.interactionKey)
            val before = endpoint.exportSnapshot()
            val media = physicalMedia().associateBy { PlaybackMedia.occurrence(it) }
            val epoch = PlaybackRequests.queueEpoch
            val upcoming = state.upcoming
            assertEquals(2, upcoming.size)
            assertEquals(upcoming[0].item.id, upcoming[1].item.id)
            // Holding/previewing/cancelling has no API mutation; invalid/same-index drops also do none.
            connection.moveUpcoming(key, upcoming[0].occurrenceId, 0)
            connection.moveUpcoming(key, upcoming[0].occurrenceId, -1)
            connection.moveUpcoming(key, upcoming[0].occurrenceId, 2)
            runCurrent()
            assertEquals(epoch, PlaybackRequests.queueEpoch)
            assertEquals(before, endpoint.exportSnapshot())
            connection.moveUpcoming(key, upcoming[1].occurrenceId, 0)
            runCurrent()
            val moved = endpoint.exportSnapshot()
            assertEquals(epoch + 1, PlaybackRequests.queueEpoch)
            assertEquals(listOf(91L, 7L, 45L, 32L), moved.queue.map { it.key })
            assertFalse(moved.shuffle)
            assertCurrentPreserved(before, moved)
            assertEquals(before.context, moved.context)
            physicalMedia().forEach { assertSame(media.getValue(PlaybackMedia.occurrence(it)), it) }
            assertEquals(Player.STATE_IDLE, player.playbackState)
            assertFalse(player.playWhenReady)
            assertFalse(player.isLoading)
            assertNotSame(key, PlaybackService.interactionKey())
            // A second delivery of the old drop cannot move a different duplicate back.
            connection.moveUpcoming(key, upcoming[0].occurrenceId, 0)
            runCurrent()
            assertEquals(moved, endpoint.exportSnapshot())
            assertEquals(epoch + 1, PlaybackRequests.queueEpoch)
        } finally { connection.release(); owner.close() }
        assertSilent()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun guardedDropRechecksAtSerializedDispatchAfterQueueOrAuthorityReplacement() = runTest {
        val owner = installOwner(backgroundScope, { testScheduler.currentTime }, "phone")
        runCurrent()
        owner.localAction { endpoint.activate(snapshot().paused(), it) }
        runCurrent()
        val connection = connectedPlayback()
        try {
            connection.connect()
            shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
            val key = requireNotNull(connection.state.value.interactionKey)
            val future = connection.state.value.upcoming
            connection.moveUpcoming(key, future[1].occurrenceId, 0)
            // The connection check and service submission have happened, but the coordinator
            // callback is queued on its serial dispatcher. Change the authoritative queue now.
            val permit = requireNotNull(owner.currentAuthority())
            assertEquals("ok", endpoint.execute(PlaybackDeviceCommand.queue("remove", 32), permit).status)
            val replacement = endpoint.exportSnapshot()
            val epoch = PlaybackRequests.queueEpoch
            runCurrent()
            assertEquals(replacement, endpoint.exportSnapshot())
            assertEquals(epoch, PlaybackRequests.queueEpoch)

            owner.localAction { endpoint.activate(snapshot().paused(), it) }
            runCurrent()
            shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
            val nextKey = requireNotNull(connection.state.value.interactionKey)
            connection.moveUpcoming(nextKey, connection.state.value.upcoming[1].occurrenceId, 0)
            // Identical IDs and order after an output-authority replacement are still stale.
            endpoint.suspendOutput()
            val suspended = endpoint.exportSnapshot()
            val suspendedEpoch = PlaybackRequests.queueEpoch
            assertNull(PlaybackService.interactionKey())
            runCurrent()
            assertEquals(suspended, endpoint.exportSnapshot())
            assertEquals(suspendedEpoch, PlaybackRequests.queueEpoch)
            assertNull(PlaybackService.interactionKey())
        } finally { connection.release(); owner.close() }
        assertSilent()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun guardedLocalDropRejectsUnsampledCurrentAbaTransition() = runTest {
        val owner = installOwner(backgroundScope, { testScheduler.currentTime }, "phone")
        runCurrent()
        owner.localAction { endpoint.activate(snapshot().paused(), it) }
        runCurrent()
        val connection = connectedPlayback()
        try {
            connection.connect()
            shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
            val state = connection.state.value
            val key = requireNotNull(state.interactionKey)
            connection.moveUpcoming(key, state.upcoming.last().occurrenceId, 0)
            val permit = requireNotNull(owner.currentAuthority())
            shadowOf(controller!!.get()).setThrowInStartForeground(
                android.app.ForegroundServiceStartNotAllowedException("Synthetic background block"))
            endpoint.execute(PlaybackDeviceCommand.queue("jump", 32), permit)
            endpoint.execute(PlaybackDeviceCommand.transport("previous"), permit)
            val afterTransition = endpoint.exportSnapshot()
            assertEquals(7L, afterTransition.queue[afterTransition.position].key)
            val epoch = PlaybackRequests.queueEpoch
            // Deliver service transition events, but do not publish a fresh UI state first.
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            runCurrent()
            assertEquals(afterTransition, endpoint.exportSnapshot())
            assertEquals(epoch, PlaybackRequests.queueEpoch)
            assertNotSame(key, PlaybackService.interactionKey())
        } finally { connection.release(); owner.close() }
        assertSilent()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun remoteQueueHasStableSwipeIdentityButNeverAbsoluteDragCapability() = runTest {
        val commands = mutableListOf<PlaybackDeviceCommand>()
        val remote = snapshot().paused().let { state -> state.copy(queue = state.queue + listOf(
            state.queue.last().copy(key = 46, order = 12, occurrenceId = "extra-46"),
            state.queue.last().copy(key = 47, order = 13, occurrenceId = "extra-47"),
        )) }
        val owner = installOwner(backgroundScope, { testScheduler.currentTime }, "pc",
            initial = remote, command = commands::add)
        runCurrent()
        val connection = connectedPlayback()
        try {
            connection.connect()
            shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
            val state = connection.state.value
            val key = requireNotNull(state.interactionKey)
            assertFalse(state.canMoveUpcomingAbsolutely)
            shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
            assertSame(key, connection.state.value.interactionKey)
            connection.moveUpcoming(key, state.upcoming[3].occurrenceId, 1)
            runCurrent()
            assertTrue("unrepresentable remote absolute move is rejected", commands.isEmpty())
            connection.moveUpcoming(key, state.upcoming[1].occurrenceId, 0)
            runCurrent()
            assertEquals(1, commands.size)
            assertEquals(PlaybackDeviceCommand.queue("move", 45, "next"), commands.single())
            connection.moveUpcoming(state.upcoming[1].occurrenceId, 0)
            runCurrent()
            assertEquals("an unconfirmed remote write is not replayed by legacy input", 1, commands.size)
            connection.release()
            assertNull(connection.state.value.interactionKey)
            assertFalse(connection.state.value.canMoveUpcomingAbsolutely)
        } finally { connection.release(); owner.close() }
        assertSilent()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun guardedRemoteMoveRechecksQueueOwnerAndSessionAfterTransportWait() = runTest {
        val commands = mutableListOf<PlaybackDeviceCommand>()
        var remote = snapshot().paused()
        var epoch = 1L
        var gate: CompletableDeferred<Unit>? = null
        val owner = installOwner(backgroundScope, { testScheduler.currentTime }, "pc", initial = remote,
            onPoll = { _, _ -> gate?.await() },
            viewTransform = { it.copy(state = remote, epoch = epoch) }, command = commands::add)
        runCurrent()
        val connection = connectedPlayback()
        try {
            connection.connect()
            shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
            suspend fun race(replace: suspend () -> Unit) {
                val state = connection.state.value
                val key = requireNotNull(state.interactionKey)
                gate = CompletableDeferred()
                owner.retry(); runCurrent() // An in-flight poll owns the transport mutex.
                connection.moveUpcoming(key, state.upcoming.last().occurrenceId, 0)
                replace()
                gate!!.complete(Unit); gate = null
                runCurrent()
                shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
                assertTrue("stale held action must not dispatch a wire write", commands.isEmpty())
            }
            race { remote = remote.copy(queue = remote.queue.take(2) + remote.queue.takeLast(2).reversed()) }
            // Same IDs/order, fresh authority incarnation is still a stale gesture.
            race { epoch++ }
            // Same account and visible queue, new authenticated Session object is also stale.
            race { login() }
        } finally { gate?.complete(Unit); connection.release(); owner.close() }
        assertSilent()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun guardedRemoteMoveRejectsUnsampledQueueAndCurrentAbaPublications() = runTest {
        val initial = snapshot().paused()
        val commands = mutableListOf<PlaybackDeviceCommand>()
        val responses = ArrayDeque<PlaybackSnapshot>()
        var sequence = 0L
        var gate: CompletableDeferred<Unit>? = null
        val owner = installOwner(backgroundScope, { testScheduler.currentTime }, "pc", initial = initial,
            onPoll = { _, _ -> gate?.await() },
            viewTransform = { it.copy(state = responses.removeFirstOrNull() ?: initial, stateSeq = ++sequence) },
            command = commands::add)
        runCurrent()
        val connection = connectedPlayback()
        try {
            connection.connect()
            shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
            val changes = listOf(
                initial.copy(queue = initial.queue.take(2) + initial.queue.takeLast(2).reversed()),
                initial.copy(position = 0, occurrence = null),
            )
            for (changed in changes) {
                val state = connection.state.value
                val key = requireNotNull(state.interactionKey)
                gate = CompletableDeferred()
                owner.retry(); runCurrent() // Poll B is waiting for its response.
                owner.retry(); runCurrent() // Poll A queues before the guarded move.
                connection.moveUpcoming(key, state.upcoming.last().occurrenceId, 0)
                responses.addLast(changed)
                responses.addLast(initial)
                gate!!.complete(Unit); gate = null
                // No main-looper/UI sampling between the two accepted publications and drop.
                runCurrent()
                assertEquals(initial, owner.state.value.view!!.state)
                assertTrue(responses.isEmpty())
                assertTrue("an intervening queue/current change must cancel the held key", commands.isEmpty())
                shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
                assertNotSame(key, connection.state.value.interactionKey)
            }
        } finally { gate?.complete(Unit); connection.release(); owner.close() }
        assertSilent()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun heldInteractionSurvivesManySameOwnerLeaseRenewalsAndRemoteProgressHeartbeats() = runTest {
        var now = 0L
        var heartbeat = 0L
        val initial = snapshot().paused()
        fun renewed(view: PlaybackDevicesView): PlaybackDevicesView {
            heartbeat++
            return view.copy(leaseMs = 8000 - heartbeat % 3, stateSeq = heartbeat,
                stateAgeMs = heartbeat * 11,
                state = initial.copy(elapsed = 65.536 + heartbeat / 10.0, waiting = heartbeat % 2 == 0L))
        }
        var owner = installOwner(backgroundScope, { now }, "phone", viewTransform = ::renewed)
        runCurrent()
        owner.localAction { endpoint.activate(initial, it) }
        runCurrent()
        val connection = connectedPlayback()
        try {
            connection.connect()
            shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
            val local = connection.state.value
            val localKey = requireNotNull(local.interactionKey)
            val epoch = PlaybackRequests.queueEpoch
            repeat(12) {
                now += 1000
                owner.retry(); runCurrent()
                shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
                assertSame("benign owner lease renewal $it", localKey, connection.state.value.interactionKey)
                assertTrue(connection.state.value.canMoveUpcomingAbsolutely)
                assertEquals(epoch, PlaybackRequests.queueEpoch)
            }
            // The original held key still commits after twelve renewals, not a recaptured key.
            connection.moveUpcoming(localKey, local.upcoming[1].occurrenceId, 0)
            runCurrent()
            assertEquals(epoch + 1, PlaybackRequests.queueEpoch)
            assertCurrentPreserved(initial, endpoint.exportSnapshot())

            owner.close()
            owner = installOwner(backgroundScope, { now }, "pc", initial = initial, viewTransform = ::renewed)
            runCurrent()
            shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
            val remoteKey = requireNotNull(connection.state.value.interactionKey)
            val originalPosition = connection.state.value.positionMs
            repeat(12) {
                now += 1000
                owner.retry(); runCurrent()
                shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
                assertSame("foreign-owner progress and lease renewal $it", remoteKey, connection.state.value.interactionKey)
                assertFalse(connection.state.value.canMoveUpcomingAbsolutely)
            }
            assertTrue(connection.state.value.positionMs > originalPosition)
        } finally { connection.release(); owner.close() }
        assertSilent()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun unavailableRemoteEndpointDoesNotGrantAnInteractionKey() = runTest {
        val owner = installOwner(backgroundScope, { testScheduler.currentTime }, "pc", initial = snapshot().paused(),
            viewTransform = { view -> view.copy(devices = view.devices.map { it.copy(available = false) }) })
        runCurrent()
        val connection = connectedPlayback()
        try {
            connection.connect()
            shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
            assertNotNull(connection.state.value.remoteSnapshot)
            assertNull(connection.state.value.interactionKey)
            assertFalse(connection.state.value.canMoveUpcomingAbsolutely)
        } finally { connection.release(); owner.close() }
        assertSilent()
    }

    @Test fun interactionKeyInvalidatesOnSameAccountReauthAndSilentServiceRecreation() = runBlocking {
        endpoint.activate(snapshot().paused(), authority())
        val first = requireNotNull(PlaybackService.interactionKey())
        login()
        assertNull(PlaybackService.interactionKey())
        assertFalse(first === PlaybackService.interactionKey())
        controller!!.destroy()
        controller = Robolectric.buildService(PlaybackService::class.java).create()
        assertNull(PlaybackService.interactionKey())
        assertSilent()
    }

    @Test fun moveRemoveClearAndCloseCommandsAddressDuplicateOccurrencesNotTrackIds() = runBlocking {
        val imported = snapshot().paused()
        val permit = authority()
        assertEquals("ok", endpoint.activate(imported, permit).status)
        for (key in listOf(91L, 7L, 999L)) {
            assertEquals("rejected", endpoint.execute(PlaybackDeviceCommand.queue("remove", key), permit).status)
            assertEquals(imported, endpoint.exportSnapshot())
        }
        val moved = endpoint.execute(PlaybackDeviceCommand.queue("move", 45, "next"), permit)
        assertEquals("ok", moved.status)
        assertFalse(moved.snapshot.shuffle)
        val originals = imported.queue.associateBy { it.key }
        assertEquals(listOf(91L, 7L, 45L, 32L).mapIndexed { index, key -> originals.getValue(key).copy(order = index.toLong()) }, moved.snapshot.queue)
        assertCurrentPreserved(imported, moved.snapshot)
        val removed = endpoint.execute(PlaybackDeviceCommand.queue("remove", 45), permit)
        assertEquals("ok", removed.status)
        assertEquals(moved.snapshot.queue.filterNot { it.key == 45L }, removed.snapshot.queue)
        assertCurrentPreserved(imported, removed.snapshot)
        val cleared = endpoint.execute(PlaybackDeviceCommand.transport("clear"), permit)
        assertEquals("ok", cleared.status)
        assertEquals(listOf(91L, 7L), cleared.snapshot.queue.map { it.key })
        assertEquals(1, cleared.snapshot.position)
        assertCurrentPreserved(imported, cleared.snapshot)
        assertSilent()
        val closed = endpoint.execute(PlaybackDeviceCommand.transport("close"), permit)
        assertEquals("ok", closed.status)
        assertTrue(closed.snapshot.queue.isEmpty())
        assertEquals(-1, closed.snapshot.position)
        assertNull(closed.snapshot.occurrence)
        assertEquals(0, player.mediaItemCount)
        assertFalse(shadowOf(controller!!.get()).isStoppedBySelf)
        assertEquals(PlaybackSnapshot(), closed.snapshot)
        assertSilent()
        assertEquals(closed.snapshot, endpoint.exportSnapshot())
        closed.finalClose!!.dispose()
        controller!!.destroy(); controller = null
        assertEquals(PlaybackSnapshot(), endpoint.exportSnapshot())
    }
}
