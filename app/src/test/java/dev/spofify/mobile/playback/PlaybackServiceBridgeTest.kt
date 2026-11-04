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
        derivedResponse = """{"context":{"kind":"derived","id":"rules","name":"Original rules","revision":5,"fence":"original-fence"},"items":[${track("occ-1", "returned-token-1")},${track("occ-2", "returned-token-2")}],"total":2,"limit":1000,"truncated":false,"revision":5,"fence":"original-fence","startOccurrenceId":"occ-2","notice":null}"""
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
        command: (PlaybackDeviceCommand) -> Unit = {}): PlaybackCoordinator {
        graph.playbackCoordinator!!.close()
        val view = PlaybackDevicesView("phone", listOf(PlaybackDevice("phone", "Phone", true), PlaybackDevice("pc", "PC", true)),
            output, 1, 8000, state = initial, stateSeq = 0, stateAgeMs = 0, transfer = null, directives = directives, commands = emptyList())
        val api = object : PlaybackDevicesProtocol {
            override suspend fun register(name: String) = view
            override suspend fun view() = view
            override suspend fun poll(deviceId: String, epoch: Long, stateSeq: Long?, state: PlaybackSnapshot?, acks: List<PlaybackAck>): PlaybackDevicesView {
                state?.let(published::add); onPoll(state, acks); return view
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

    private fun connectedPlayback(): PlaybackConnection {
        val service = controller!!.get()
        val mediaSession = PlaybackService::class.java.getDeclaredField("session").let {
            it.isAccessible = true; it.get(service) as androidx.media3.session.MediaSession
        }
        val future = androidx.media3.session.MediaController.Builder(service, mediaSession.token).buildAsync()
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        val mediaController = future.get(1, TimeUnit.SECONDS)
        return PlaybackConnection(service).also { connection ->
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

    @Test fun retainedNotificationTokenCannotBeUpgradedIntoANewPlaybackGeneration() = runBlocking {
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
        assertEquals(before, graph.playbackCoordinator!!.state.value)
        // The fresh token reaches the actual coordinator (still nonparticipating in setup),
        // proving that silence above came from the stale-token fence rather than a dead route.
        fresh.send()
        service.onStartCommand(requireNotNull(shadowOf(service).nextStartedService), 0, 2)
        assertNotEquals(before.message, graph.playbackCoordinator!!.state.value.message)
        assertSilent()
    }

    @Test fun retainedNotificationTokenCannotTargetAReplacementQueueInTheSameClientGeneration() = runBlocking {
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
        assertEquals(before, graph.playbackCoordinator!!.state.value)
        fresh.send()
        service.onStartCommand(requireNotNull(shadowOf(service).nextStartedService), 0, 2)
        assertNotEquals(before.message, graph.playbackCoordinator!!.state.value.message)
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
        derivedResponse = """{"context":{"kind":"derived","id":"rules","name":"Original rules","revision":5,"fence":"original-fence"},"items":[$items],"total":500,"limit":1000,"truncated":false,"revision":5,"fence":"original-fence","startOccurrenceId":"${occurrence(0)}","notice":null}"""
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
        assertEquals(501L, added.snapshot.queue.last().key) // Rejected speculative allocation consumed NO live keys.
        assertEquals(original.occurrence, added.snapshot.occurrence)
        assertEquals(original.queue, added.snapshot.queue.take(500))
        assertSilent()
    }

    @Test fun longDerivedCommandResolvesThroughActualServiceWithoutManualEntryIdOverflow() = runBlocking {
        val occurrence = "derived-" + "x".repeat(1600)
        val context = PlaybackContext("derived", "rules", "Original rules", 5, "original-fence")
        fun track(id: String) = """{"kind":"track","id":"same-song","name":"Duplicate","artists":[],"durationMs":120000,"hasAudio":true,"occurrenceId":"$id","selectionToken":"returned-replacement"}"""
        derivedResponse = """{"context":{"kind":"derived","id":"rules","name":"Original rules","revision":5,"fence":"original-fence"},"items":[${track("first")},${track(occurrence)}],"total":2,"limit":1000,"truncated":false,"revision":5,"fence":"original-fence","startOccurrenceId":"$occurrence","notice":null}"""
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
