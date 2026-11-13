package dev.spofify.mobile.playback

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import dev.spofify.mobile.AppGraph
import dev.spofify.mobile.LoginStore
import dev.spofify.mobile.OfflineEligibility
import dev.spofify.mobile.OfflineEligibilityStore
import dev.spofify.mobile.RememberedLogin
import dev.spofify.mobile.SpofifyApplication
import dev.spofify.mobile.core.*
import dev.spofify.mobile.feature.devices.*
import dev.spofify.mobile.feature.player.NativeMiniPlayer
import dev.spofify.mobile.feature.player.NativeNowPlaying
import dev.spofify.mobile.testing.RealBrokerIntegration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Real current broker + core OkHttp transport + application coordinator/executor.
 * The native endpoint is deliberately fake. Injected observations prove protocol
 * separation, NOT Media3 decoding, HTTP audio delivery, acoustics, focus or FGS.
 */
@Category(RealBrokerIntegration::class)
class LibraryPlaybackBrokerIntegrationTest {
    private class ObservedEndpoint : NativePlaybackEndpoint {
        override var onObservation: ((String, Boolean) -> Unit)? = null
        var facts = LibraryPlaybackObservation()
        var execution: LibraryPlaybackInstruction? = null
        var playingExecutions = 0
        var prepares = 0
        var stops = 0
        var releaseProof = true
        override suspend fun prepare(instruction: LibraryPlaybackInstruction, isCurrent: () -> Boolean): String {
            prepares++
            assertTrue(isCurrent())
            assertFalse(facts.isPlaying)
            return "ok"
        }
        override fun release(): Boolean { suspendOutput(); return releaseProof }
        override suspend fun execute(instruction: LibraryPlaybackInstruction, authority: LibraryPlaybackAuthority): String {
            assertTrue(authority.isValid())
            execution = instruction
            if (instruction.desiredPlaying) playingExecutions++
            // Successful application/ACK never invents observed playing or duration.
            return "ok"
        }
        override fun observe() = facts
        override fun suspendOutput() { stops++; facts = facts.copy(isPlaying = false) }
        fun measured(elapsed: Double, playing: Boolean) {
            facts = LibraryPlaybackObservation(elapsed, 24.0, playing)
            onObservation?.invoke(requireNotNull(execution).id, false)
        }
    }

    private class Trace {
        val sent = mutableListOf<LibraryPlaybackClientMessage>()
        var opens = 0
        suspend fun open(client: SpofifyClient, scope: CoroutineScope, name: String, id: String, capable: Boolean): LibraryPlaybackTransport {
            val actual = client.openLibraryPlayback(scope, name, id, capable)
            opens++
            return object : LibraryPlaybackTransport by actual {
                override fun send(message: LibraryPlaybackClientMessage): LibraryPlaybackSend? {
                    val receipt = actual.send(message)
                    if (receipt != null) sent += message
                    return receipt
                }
            }
        }
        val commands get() = sent.filterIsInstance<LibraryPlaybackClientMessage.Command>()
    }

    private class Journey(val scope: CoroutineScope) {
        val owners = mutableListOf<PlaybackCoordinator>()
        val clients = mutableListOf<SpofifyClient>()
        fun owner(client: SpofifyClient, endpoint: ObservedEndpoint, device: String, trace: Trace = Trace()): PlaybackCoordinator {
            if (client !in clients) clients += client
            return PlaybackCoordinator(client, endpoint, scope,
                deviceIdentity = { device to "Synthetic Android" }, mediaEventUptimeMs = { 0L },
                openTransport = { owner, name, id, capable -> trace.open(client, owner, name, id, capable) })
                .also { owners += it; it.retry() }
        }
        suspend fun close() {
            owners.forEach { it.close() }
            scope.coroutineContext[Job]!!.cancelAndJoin()
            clients.forEach {
                it.clearSession()
                it.httpClient.connectionPool.evictAll()
                it.httpClient.dispatcher.executorService.shutdown()
                check(it.httpClient.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    private fun journey(block: suspend Journey.(LibraryPlaybackBroker) -> Unit) {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            runBlocking(dispatcher) {
                LibraryPlaybackBroker().use { broker ->
                    val journey = Journey(CoroutineScope(dispatcher + SupervisorJob()))
                    try { withTimeout(90_000) { journey.block(broker) } }
                    finally { withContext(NonCancellable) { journey.close() } }
                }
            }
        }
    }

    private suspend fun await(label: String, condition: () -> Boolean) {
        withTimeout(15_000) { while (!condition()) delay(20) }
        assertTrue(label, condition())
    }
    private fun PlaybackCoordinator.snapshot() = requireNotNull(state.value.snapshot)
    private suspend fun command(owner: PlaybackCoordinator, value: LibraryPlaybackCommand): LibraryPlaybackResult {
        val result = CompletableDeferred<LibraryPlaybackResult?>()
        assertTrue(owner.submit(value, expectedStateVersion = null, onResult = { result.complete(it) }))
        val final = requireNotNull(withTimeout(15_000) { result.await() }) { "Connection ended or command timed out" }
        assertEquals("accepted", final.outcome)
        assertTrue(final.final)
        return final
    }

    @Test fun canonicalDuplicateCollectionExactConnectionObservedPauseResumeAndReconnect() = journey { broker ->
        val client = broker.client()
        clients += client
        val api = PersonalCollectionsApi(client)
        var playlist = api.createPlaylist("Canonical duplicate playlist")
        val order = listOf(broker.tracks[0], broker.tracks[1], broker.tracks[0], broker.tracks[2])
        order.forEach { playlist = api.addEntry(playlist.id, it.first, playlist.revision) }
        val entries = api.entries(playlist.id).items
        assertEquals(4, entries.size)
        val firstEndpoint = ObservedEndpoint(); val siblingEndpoint = ObservedEndpoint()
        val firstTrace = Trace(); val siblingTrace = Trace()
        val first = owner(client, firstEndpoint, "same-logical-device", firstTrace)
        await("first selection completed") { first.state.value.connected && first.state.value.isSelectedConnection && first.snapshot().transfer == null }
        val sibling = owner(client, siblingEndpoint, "same-logical-device", siblingTrace)
        await("sibling connected") { sibling.state.value.connected && sibling.snapshot().transfer == null }
        assertEquals(first.state.value.connectionId, sibling.snapshot().selectedConnectionId)
        assertNotEquals(first.state.value.connectionId, sibling.state.value.connectionId)
        assertEquals(1, sibling.snapshot().devices.size)
        assertFalse(sibling.state.value.isSelectedConnection)

        // Only IDs and exact manual entry identity go over the production codec.
        // Forged display context must not become canonical metadata.
        val selection = LibraryPlaybackSelection.Track(
            LibraryPlaybackTrackRef(order[2].first, entryId = entries[2].id),
            LibraryPlaybackContext("playlist", playlist.id, "FORGED DISPLAY NAME"))
        val receipt = command(sibling, LibraryPlaybackCommand.QueueIntent("now", selection))
        assertEquals(4, receipt.acceptedCount)
        await("canonical queue and intent") { sibling.snapshot().state.queue.size == 4 && sibling.snapshot().state.desiredPlaying }
        val canonical = sibling.snapshot().state
        assertEquals(order.map { it.first }, canonical.queue.map { it.id })
        assertEquals(order.map { it.second }, canonical.queue.map { it.name })
        assertEquals(entries.map { it.id }, canonical.queue.map { it.entryId })
        assertEquals(4, canonical.queue.map { it.key }.distinct().size)
        assertEquals(2, canonical.position)
        assertEquals(playlist.name, canonical.context!!.name)
        assertFalse(canonical.truncated)
        await("executor stopped observation published") { sibling.snapshot().playback.hasReportedPlayback }
        assertFalse(sibling.snapshot().playback.isPlaying)
        assertEquals(0, siblingEndpoint.playingExecutions)
        assertEquals(1, firstEndpoint.playingExecutions)
        assertEquals(0, siblingEndpoint.prepares)
        assertTrue(siblingTrace.sent.none { it is LibraryPlaybackClientMessage.Ack || it is LibraryPlaybackClientMessage.Report })

        firstEndpoint.measured(1.5, true)
        await("real socket carries explicitly injected observation") { sibling.snapshot().playback.isPlaying && sibling.snapshot().playback.elapsed == 1.5 }
        command(sibling, LibraryPlaybackCommand.Pause)
        await("pause intent and stopped facts") { !sibling.snapshot().state.desiredPlaying && !sibling.snapshot().playback.isPlaying }
        val parkedOutput = sibling.snapshot().outputVersion
        val prepareCount = firstEndpoint.prepares
        command(sibling, LibraryPlaybackCommand.Resume)
        await("resume requires fresh setup") { sibling.snapshot().outputVersion > parkedOutput && sibling.snapshot().state.desiredPlaying }
        assertTrue(firstEndpoint.prepares > prepareCount)
        assertFalse(sibling.snapshot().playback.isPlaying) // resume/ACK still is not observation
        firstEndpoint.measured(2.0, true)
        await("resume observation") { sibling.snapshot().playback.isPlaying && sibling.snapshot().playback.elapsed == 2.0 }
        command(sibling, LibraryPlaybackCommand.Pause)
        await("parked before passive reconnect") { !sibling.snapshot().state.desiredPlaying && sibling.snapshot().transfer == null }
        val oldConnection = sibling.state.value.connectionId
        val commandCount = siblingTrace.commands.size
        val queueKeys = sibling.snapshot().state.queue.map { it.key }
        sibling.setServiceActive(false)
        sibling.retry()
        await("fresh ticket/connection without command replay") { sibling.state.value.connected && sibling.state.value.connectionId != oldConnection }
        assertEquals(2, siblingTrace.opens)
        assertEquals(commandCount, siblingTrace.commands.size)
        assertEquals(queueKeys, sibling.snapshot().state.queue.map { it.key })
        assertFalse(sibling.snapshot().state.desiredPlaying)
        assertEquals(0, siblingEndpoint.playingExecutions)
    }

    @Test fun uncertainSourceReleaseDrainsBeforeTargetAndAuthenticationFencesOldRoom() = journey { broker ->
        val client = broker.client()
        val sourceEndpoint = ObservedEndpoint(); val targetEndpoint = ObservedEndpoint()
        val source = owner(client, sourceEndpoint, "source")
        await("source selected") { source.state.value.connected && source.state.value.isSelectedConnection && source.snapshot().transfer == null }
        val target = owner(client, targetEndpoint, "target")
        await("target admitted") { target.state.value.connected && target.snapshot().devices.size == 2 }
        command(source, LibraryPlaybackCommand.QueueIntent("now", LibraryPlaybackSelection.Collection(
            LibraryPlaybackContext("album", broker.albumId, "forged"))))
        sourceEndpoint.measured(1.1, true)
        await("source observation") { target.snapshot().playback.isPlaying }
        val event = source.snapshot().state.occurrence!!.eventId
        val output = source.snapshot().outputVersion
        // Real application stop + socket close, WITHOUT release ACK, is NOT server stop proof.
        source.setServiceActive(false)
        assertFalse(sourceEndpoint.facts.isPlaying)
        assertFalse(source.state.value.connected)
        await("server explicitly drains uncertain old lease") { target.snapshot().transfer?.phase == "draining" }
        assertTrue(target.snapshot().drainUntilMs > 0)
        assertEquals(0, targetEndpoint.playingExecutions)
        assertEquals(output, target.snapshot().outputVersion)
        delay(250)
        assertEquals(0, targetEndpoint.playingExecutions)
        await("target activates only after finite drain") { target.state.value.isSelectedConnection && target.snapshot().transfer == null && targetEndpoint.playingExecutions == 1 }
        assertEquals(event, target.snapshot().state.occurrence!!.eventId)
        assertTrue(target.snapshot().outputVersion > output)
        assertEquals(broker.tracks.map { it.first }, target.snapshot().state.queue.map { it.id })
        command(target, LibraryPlaybackCommand.Pause)
        val witness = requireNotNull(target.captureBinding())
        client.clearSession()
        await("authentication loss fences actual socket") { !target.state.value.connected }
        assertFalse(target.isCurrent(witness))
        assertFalse(target.submit(LibraryPlaybackCommand.Resume))
        assertFalse(targetEndpoint.facts.isPlaying)
        val otherClient = broker.client(1)
        val other = owner(otherClient, ObservedEndpoint(), "target")
        await("foreign account has its own room") { other.state.value.connected && other.snapshot().transfer == null }
        assertNotEquals(witness.sessionId, other.snapshot().sessionId)
        assertTrue(other.snapshot().state.queue.isEmpty())
        assertNull(other.snapshot().state.occurrence)
        assertFalse(other.snapshot().state.desiredPlaying)
    }
}

/** Actual AppGraph -> current WS coordinator -> Media3PlaybackEndpoint -> PlaybackService
 * -> real ExoPlayer start/isPlaying/clock/renderer progress. Only the source and PCM sink
 * are substituted by PreparedTransportMedia. This is NOT authenticated audio/range,
 * decoder/acoustic, installed Android, rendered UI, hardware or background/FGS proof.
 *
 * The other connection is explicitly incapable/controller-only, not a fake audible
 * executor. Transfer proves real source stop and paused selection, then actual service
 * resumption. The singleton service is never presented as two independent outputs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SpofifyApplication::class, qualifiers = "w400dp-h850dp-mdpi",
    shadows = [PreparedTransportMedia::class], instrumentedPackages = ["androidx.media3.exoplayer"])
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Category(RealBrokerIntegration::class)
class LibraryPlaybackServiceBrokerIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private enum class ServiceCase { LIFECYCLE, CONTROLS, PENDING_START, QUEUE_EDITS, RENDERER_RETRY, ENDED, REMOTE_OUTPUT }

    private class Sessions : SessionStore {
        @Volatile private var value: StoredSession? = null
        override fun read() = value
        override fun write(value: StoredSession?) { this.value = value }
    }
    private class Logins : LoginStore {
        override fun read(): RememberedLogin? = null
        override fun write(value: RememberedLogin?) = Unit
    }
    private class Eligibility : OfflineEligibilityStore {
        private var value: OfflineEligibility? = null
        override fun read() = value
        override fun write(value: OfflineEligibility?) { this.value = value }
    }
    private data class Lifetime(
        val service: ServiceController<PlaybackService>, val raw: ExoPlayer,
        val controller: MediaController, val connection: PlaybackConnection, val owner: PlaybackCoordinator,
    )

    /** Real second socket with no media capability. ACKs only actual empty/stopped setup;
     * never manufactures playing/progress reports or authority for an audible endpoint. */
    private class Observer(val transport: LibraryPlaybackTransport, scope: CoroutineScope,
        private val sourceStopped: () -> Boolean) {
        var connection = ""
        var snapshot: LibraryPlaybackSnapshot? = null
        var failure = false
        val results = mutableMapOf<String, LibraryPlaybackResult>()
        private val collector = scope.launch {
            try {
                transport.events.collect { event ->
                    check(event is LibraryPlaybackEvent.Message) { "Observer socket ended" }
                    val message = event.message
                    when (message.type) {
                        "snapshot" -> { connection = requireNotNull(message.connectionId); snapshot = message.snapshot }
                        "state" -> snapshot = message.snapshot
                        "playback" -> message.playback?.let { facts ->
                            val old = snapshot
                            if (old != null && facts.stateVersion == old.stateVersion && facts.outputVersion == old.outputVersion)
                                snapshot = old.copy(playback = facts)
                        }
                        "instruction" -> {
                            val instruction = requireNotNull(message.instruction)
                            check(instruction.type in setOf("prepare", "release", "activate", "execute"))
                            check(instruction.track == null && !instruction.desiredPlaying)
                            if (instruction.type == "activate") check(sourceStopped()) { "Source not stopped before target activation" }
                            check(transport.send(LibraryPlaybackClientMessage.Ack(instruction.id,
                                instruction.outputVersion, instruction.eventId, "ok")) != null)
                        }
                        "result" -> message.result?.let { if (it.final) results[it.id] = it }
                        "error" -> error("Observer protocol error")
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { failure = true }
        }
        fun command(value: LibraryPlaybackCommand): String = requireNotNull(
            transport.send(LibraryPlaybackClientMessage.Command(value))).commandId!!
        fun close() { transport.close(); collector.cancel() }
    }

    /** A SECOND real host ExoPlayer, not a second singleton service. The production
     * coordinator/transport/executor still owns all authority. No browser, focus,
     * FGS, authenticated range or acoustic claim follows from this host-only sink. */
    private class HostOutput(context: android.content.Context, private val client: SpofifyClient) : NativePlaybackEndpoint {
        val player = ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(context)).build()
        override var onObservation: ((String, Boolean) -> Unit)? = null
        private var generation = 0L
        private var listener: Player.Listener? = null
        private fun stop() {
            generation++
            listener?.let(player::removeListener); listener = null
            player.pause(); player.stop()
        }
        override fun suspendOutput() = stop()
        override fun release(): Boolean { stop(); return !player.isPlaying && !player.playWhenReady }
        override fun observe() = LibraryPlaybackObservation(
            player.currentPosition.coerceAtLeast(0) / 1000.0,
            if (player.duration == C.TIME_UNSET) 0.0 else player.duration.coerceAtLeast(0) / 1000.0,
            player.isPlaying, player.playbackState == Player.STATE_BUFFERING, player.isLoading,
            if (player.playerError == null) "" else "media")
        override suspend fun prepare(instruction: LibraryPlaybackInstruction, isCurrent: () -> Boolean): String =
            apply(instruction, isCurrent, playing = false)
        override suspend fun execute(instruction: LibraryPlaybackInstruction, authority: LibraryPlaybackAuthority): String =
            apply(instruction, authority::isValid, instruction.desiredPlaying)
        private suspend fun apply(instruction: LibraryPlaybackInstruction, current: () -> Boolean, playing: Boolean): String {
            stop()
            val token = generation
            fun valid() = token == generation && current()
            if (!valid()) return "rejected"
            val track = instruction.track
            if (track == null) { player.clearMediaItems(); return if (playing) "rejected" else "ok" }
            player.setMediaItem(MediaItem.Builder().setMediaId(track.id)
                .setUri(client.audioUrl(LibraryItem("track", track.id, track.name))).build(), (instruction.elapsed * 1000).toLong())
            val installed = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    if (valid()) onObservation?.invoke(instruction.id, player.playbackState == Player.STATE_ENDED)
                }
            }
            listener = installed; player.addListener(installed)
            var completed = false
            try {
                player.prepare()
                val ready = withTimeoutOrNull(3000) {
                    while (valid() && player.playerError == null && player.playbackState != Player.STATE_READY) delay(10)
                    valid() && player.playerError == null && player.playbackState == Player.STATE_READY
                } == true
                if (!valid()) return "rejected"
                if (!ready) return "media"
                if (playing) {
                    player.play()
                    val started = withTimeoutOrNull(500) {
                        while (valid() && !player.isPlaying && player.playerError == null) delay(10)
                        valid() && player.isPlaying && player.playerError == null
                    } == true
                    if (!started) return if (valid()) "media" else "rejected"
                }
                completed = true
                return "ok"
            } finally {
                if (token == generation && (!completed || !playing)) { player.pause(); player.stop() }
            }
        }
        fun close() { stop(); player.release() }
    }

    private var deadline = 0L
    private fun pump() {
        check(System.nanoTime() < deadline) { "Bounded service broker journey expired" }
        shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
        Thread.sleep(5)
    }
    private fun await(description: String, condition: () -> Boolean) {
        val end = minOf(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(15))
        while (!condition() && System.nanoTime() < end) pump()
        assertTrue(description, condition())
    }
    private fun settleStopped(description: String, raw: ExoPlayer) {
        await(description) { !raw.isPlaying && !raw.playWhenReady }
        val samples = PreparedTransportMedia.samples.get()
        val position = raw.currentPosition
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200)
        while (System.nanoTime() < end) pump()
        assertFalse(raw.isPlaying)
        assertFalse(raw.playWhenReady)
        assertEquals("Stopped renderer must not consume started PCM", samples, PreparedTransportMedia.samples.get())
        assertEquals("Stopped Media3 clock must not progress", position, raw.currentPosition)
    }
    private fun PlaybackCoordinator.snapshot() = requireNotNull(state.value.snapshot)
    private fun submit(owner: PlaybackCoordinator, command: LibraryPlaybackCommand): LibraryPlaybackResult {
        var done = false
        var outcome: LibraryPlaybackResult? = null
        assertTrue(owner.submit(command, expectedStateVersion = null, onResult = { outcome = it; done = true }))
        await("terminal current-WS command result") { done }
        return requireNotNull(outcome).also {
            assertEquals("accepted", it.outcome); assertTrue(it.final)
            await("accepted structural version observed") { owner.snapshot().stateVersion >= it.stateVersion }
        }
    }
    private fun field(service: PlaybackService, name: String): Any? = PlaybackService::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(service)

    @Test fun actualServiceProgressStopTransferReconnectSameCookieReplacementAndAccountFence() = serviceJourney(ServiceCase.LIFECYCLE)
    @Test fun realMiniFullPostedNotificationAndHeadsetIngressControlCurrentWsMediaOnce() = serviceJourney(ServiceCase.CONTROLS)
    @Test fun pauseDuringActualFirstBufferingCancelsStartAndLateReadinessCannotReplay() = serviceJourney(ServiceCase.PENDING_START)
    @Test fun pausedA42CompleteLowAppendAndStructuralEditsPreserveActualMediaAndPriority() = serviceJourney(ServiceCase.QUEUE_EDITS)
    @Test fun actualRendererFailureThenFreshCurrentProtocolRetryProgressesSameOccurrence() = serviceJourney(ServiceCase.RENDERER_RETRY)
    @Test fun actualEndedEventAdvancesCanonicalTraversalAndExplicitJumpCreatesFreshOccurrence() = serviceJourney(ServiceCase.ENDED)
    @Test fun miniFullRemoteControlsAndBidirectionalTransferUseTwoActualFencedMedia3Outputs() = serviceJourney(ServiceCase.REMOTE_OUTPUT)

    private fun serviceJourney(case: ServiceCase) {
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        val app = ApplicationProvider.getApplicationContext<SpofifyApplication>()
        val clients = mutableListOf<SpofifyClient>()
        val lifetimes = mutableListOf<Lifetime>()
        val destroyed = mutableSetOf<ServiceController<PlaybackService>>()
        val remoteOwners = mutableListOf<PlaybackCoordinator>()
        val remoteOutputs = mutableListOf<HostOutput>()
        val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var graph: AppGraph? = null
        var observer: Observer? = null
        var primary: Throwable? = null
        PreparedTransportMedia.holdReady.set(false)
        PreparedTransportMedia.failNextRender.set(false)
        LibraryPlaybackBroker().use { broker ->
            try {
                app.graph.clear()
                val sessions = Sessions()
                val activeGraph = AppGraph(app, sessions, Logins(), offlineEligibilityStore = Eligibility()).also { graph = it }
                SpofifyApplication::class.java.getDeclaredField("graph").apply { isAccessible = true; set(app, activeGraph) }
                val shown = mutableStateOf<PlaybackConnection?>(null)
                val full = mutableStateOf(false)
                compose.setContent {
                    shown.value?.let { connection ->
                        val state by connection.state.collectAsState()
                        MaterialTheme {
                            if (full.value) NativeNowPlaying(state, connection, null, false, false, {}, {}, {}, {},
                                artwork = { _, modifier -> Box(modifier) })
                            else NativeMiniPlayer(state, connection, {}, artwork = { _, modifier -> Box(modifier) })
                        }
                    }
                }
                val firstClient = activeGraph.configure(broker.server).also { clients += it }
                runBlocking(Dispatchers.IO) { broker.authenticate(firstClient) }
                val saved = requireNotNull(sessions.read())
                // The independent observer keeps this exact authenticated account room alive
                // through app service/socket/graph replacement, without another login.
                val observerSessions = Sessions().apply { write(saved) }
                val observerClient = SpofifyClient(broker.server, observerSessions).also { clients += it }
                assertNotNull(runBlocking(Dispatchers.IO) { observerClient.restore() })
                assertTrue("Observer restore must use the same immutable HTTP session cookie",
                    observerSessions.read()?.cookie == saved.cookie)

                fun createLifetime(): Lifetime {
                    val interceptions = PreparedTransportMedia.interceptions.get()
                    val service = Robolectric.buildService(PlaybackService::class.java).create()
                    val actual = service.get()
                    assertEquals(interceptions + 1, PreparedTransportMedia.interceptions.get())
                    shadowOf(actual.getSystemService(ActivityManager::class.java)).setProcesses(listOf(
                        ActivityManager.RunningAppProcessInfo().apply {
                            pid = android.os.Process.myPid()
                            importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                        }))
                    shadowOf(actual.getSystemService(AudioManager::class.java))
                        .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                    val owner = requireNotNull(activeGraph.playbackCoordinator)
                    val raw = field(actual, "player") as ExoPlayer
                    val session = field(actual, "session") as MediaSession
                    val future = MediaController.Builder(actual, session.token).buildAsync()
                    await("genuine MediaSession controller connection") { future.isDone }
                    val controller = future.get(1, TimeUnit.SECONDS)
                    val connection = PlaybackConnection(actual)
                    // Existing host binding seam: genuine MediaSession/controller/listener,
                    // not a fake Player or a replacement production endpoint.
                    PlaybackConnection::class.java.getDeclaredField("controller").apply { isAccessible = true; set(connection, controller) }
                    val listener = PlaybackConnection::class.java.getDeclaredField("listener").let {
                        it.isAccessible = true; it.get(connection) as Player.Listener
                    }
                    controller.addListener(listener)
                    connection.connect()
                    shown.value = connection
                    return Lifetime(service, raw, controller, connection, owner).also { lifetimes += it }
                }
                fun progress(life: Lifetime, samples: Long, starts: Long) {
                    val initialPosition = life.raw.currentPosition
                    await("real ExoPlayer READY/isPlaying, clock advancement and started PCM consumption") {
                        life.raw.playbackState == Player.STATE_READY && life.raw.isPlaying &&
                            life.raw.currentPosition >= initialPosition + 100 &&
                            PreparedTransportMedia.samples.get() > samples && PreparedTransportMedia.starts.get() > starts
                    }
                    await("broker scalar facts originate in real service observation") {
                        life.owner.snapshot().playback.hasReportedPlayback && life.owner.snapshot().playback.isPlaying
                    }
                }
                fun resume(life: Lifetime) {
                    val samples = PreparedTransportMedia.samples.get()
                    val starts = PreparedTransportMedia.starts.get()
                    life.controller.play() // actual MediaSession -> coordinated service transport
                    progress(life, samples, starts)
                }
                fun paused(life: Lifetime) {
                    await("Pause reaches canonical desired pause and actual stopped facts") {
                        !life.owner.snapshot().state.desiredPlaying && !life.owner.snapshot().playback.isPlaying
                    }
                    settleStopped("actual service pause drains output", life.raw)
                }
                fun pause(life: Lifetime) { life.controller.pause(); paused(life) }
                fun posted(life: Lifetime, title: String): PendingIntent = shadowOf(
                    life.service.get().getSystemService(NotificationManager::class.java)).allNotifications.single()
                    .actions.single { it.title.toString() == title }.actionIntent
                fun deliver(life: Lifetime, token: PendingIntent) {
                    while (shadowOf(life.service.get()).nextStartedService != null) { }
                    token.send()
                    life.service.get().onStartCommand(requireNotNull(shadowOf(life.service.get()).nextStartedService), 0, 1)
                }
                fun renderer() = listOf(PreparedTransportMedia.enables.get(), PreparedTransportMedia.resets.get(), PreparedTransportMedia.starts.get())
                fun history(): JsonObject = runBlocking(Dispatchers.IO) {
                    observerClient.httpClient.newCall(Request.Builder().url(broker.server.url("api", "me", "history")).build()).execute().use {
                        check(it.code == 200)
                        val source = requireNotNull(it.body).source()
                        check(!source.request(131073)) { "Bounded synthetic history read exceeded" }
                        Json.parseToJsonElement(source.readUtf8()).jsonObject
                    }
                }

                val first = createLifetime()
                await("first automatic exact-connection selection completes") {
                    first.owner.state.value.connected && first.owner.state.value.isSelectedConnection && first.owner.snapshot().transfer == null
                }
                assertSame("AppGraph must retain the one account coordinator", first.owner, activeGraph.playbackCoordinator)
                val observerTransport = runBlocking(Dispatchers.IO) {
                    observerClient.openLibraryPlayback(observerScope, "Controller only", "host-controller-only", false)
                }
                val remote = Observer(observerTransport, observerScope) {
                    lifetimes.all { !it.raw.isPlaying && !it.raw.playWhenReady }
                }.also { observer = it }
                await("independent same-cookie WS controller admitted") { remote.snapshot?.devices?.size == 2 }
                assertFalse(remote.failure)
                assertEquals(first.owner.state.value.connectionId, remote.snapshot!!.selectedConnectionId)
                assertNotEquals(first.owner.state.value.connectionId, remote.connection)

                val samples = PreparedTransportMedia.samples.get()
                val starts = PreparedTransportMedia.starts.get()
                val play = LibraryPlaybackCommand.QueueIntent("now", LibraryPlaybackSelection.Collection(
                    LibraryPlaybackContext("album", broker.albumId, "untrusted display")))
                if (case == ServiceCase.PENDING_START) {
                    PreparedTransportMedia.holdReady.set(true)
                    assertTrue(first.owner.submit(play, expectedStateVersion = null))
                    await("actual first source is BUFFERING without started PCM") {
                        first.raw.playbackState == Player.STATE_BUFFERING && !first.raw.isPlaying
                    }
                    assertEquals(samples, PreparedTransportMedia.samples.get())
                    assertEquals(starts, PreparedTransportMedia.starts.get())
                    first.controller.pause()
                    await("explicit Pause synchronously cancels native first-start work") {
                        !first.raw.isPlaying && !first.raw.playWhenReady && !first.owner.snapshot().state.desiredPlaying
                    }
                    PreparedTransportMedia.holdReady.set(false)
                    settleStopped("late source readiness cannot replay canceled first Play", first.raw)
                    assertEquals(samples, PreparedTransportMedia.samples.get())
                    assertEquals(starts, PreparedTransportMedia.starts.get())
                    assertEquals(0, history().getValue("total").jsonPrimitive.int)
                    await("stopped setup finishes without implicit start") { first.owner.snapshot().transfer == null }
                    resume(first)
                    return@use
                }
                submit(first.owner, play)
                progress(first, samples, starts)
                assertEquals(broker.tracks.map { it.first }, first.owner.snapshot().state.queue.map { it.id })
                await("independent controller observes actual source progress") {
                    remote.snapshot?.playback?.let { it.hasReportedPlayback && it.isPlaying && it.elapsed >= 1.0 } == true
                }
                val initialKeys = first.owner.snapshot().state.queue.map { it.key }
                val initialEvent = first.owner.snapshot().state.occurrence!!.eventId
                if (case == ServiceCase.CONTROLS) {
                    repeat(2) { cycle ->
                        compose.runOnIdle { full.value = cycle == 1 }
                        await("mini/full projects the actual selected service") { first.connection.state.value.isPlaying }
                        compose.onNodeWithContentDescription("Pause").performClick()
                        paused(first)
                        val beforeSamples = PreparedTransportMedia.samples.get()
                        val beforeStarts = PreparedTransportMedia.starts.get()
                        compose.onNodeWithContentDescription("Play").performClick()
                        progress(first, beforeSamples, beforeStarts)
                        assertEquals(initialKeys, first.owner.snapshot().state.queue.map { it.key })
                        assertEquals(initialEvent, first.owner.snapshot().state.occurrence!!.eventId)
                    }
                    deliver(first, posted(first, "Pause")); paused(first)
                    val resumeToken = posted(first, "Resume")
                    val beforeSamples = PreparedTransportMedia.samples.get()
                    val beforeStarts = PreparedTransportMedia.starts.get()
                    deliver(first, resumeToken)
                    progress(first, beforeSamples, beforeStarts)
                    first.controller.stop(); paused(first)
                    // Genuine connected-controller callback ingress; NOT legacy framework
                    // routing/hardware attribution. Duplicate/repeat/up cannot defer music.
                    val session = field(first.service.get(), "session") as MediaSession
                    val who = session.connectedControllers.first {
                        it.uid == android.os.Process.myUid() && it.packageName == first.service.get().packageName && !session.isMediaNotificationController(it)
                    }
                    val impl = MediaSession::class.java.getDeclaredMethod("getImpl").apply { isAccessible = true }.invoke(session)
                    val callback = impl.javaClass.getDeclaredField("callback").apply { isAccessible = true }.get(impl) as MediaSession.Callback
                    shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS)
                    val time = android.os.SystemClock.uptimeMillis()
                    val down = KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK, 0)
                    fun ingress(event: KeyEvent) { android.os.Handler(Looper.getMainLooper()).post {
                        assertTrue(callback.onMediaButtonEvent(session, who,
                            Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, event)))
                    } }
                    val headsetSamples = PreparedTransportMedia.samples.get()
                    val headsetStarts = PreparedTransportMedia.starts.get()
                    ingress(down); ingress(down)
                    ingress(KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK, 1))
                    ingress(KeyEvent(time, time, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK, 0))
                    progress(first, headsetSamples, headsetStarts)
                    ingress(down)
                    repeat(25) { pump() }
                    assertTrue(first.raw.isPlaying)
                    assertEquals(headsetStarts + 1, PreparedTransportMedia.starts.get())
                    assertEquals(initialKeys, first.owner.snapshot().state.queue.map { it.key })
                    assertEquals(initialEvent, first.owner.snapshot().state.occurrence!!.eventId)
                    return@use
                }
                if (case == ServiceCase.RENDERER_RETRY) {
                    val failures = PreparedTransportMedia.rendererFailures.get()
                    PreparedTransportMedia.failNextRender.set(true)
                    await("real renderer failure reaches current-WS stopped media facts") {
                        PreparedTransportMedia.rendererFailures.get() == failures + 1 && first.raw.playerError != null &&
                            !first.raw.isPlaying && first.owner.snapshot().playback.error == "media" && !first.owner.snapshot().state.desiredPlaying
                    }
                    val retrySamples = PreparedTransportMedia.samples.get()
                    val retryStarts = PreparedTransportMedia.starts.get()
                    submit(first.owner, LibraryPlaybackCommand.Retry)
                    progress(first, retrySamples, retryStarts)
                    assertNull(first.raw.playerError)
                    assertEquals(initialKeys, first.owner.snapshot().state.queue.map { it.key })
                    assertEquals(initialEvent, first.owner.snapshot().state.occurrence!!.eventId)
                    assertEquals(failures + 1, PreparedTransportMedia.rendererFailures.get())
                    return@use
                }
                if (case == ServiceCase.ENDED) {
                    assertTrue(first.raw.duration > 1000)
                    first.controller.seekTo(first.raw.duration - 100)
                    await("actual media end, not an optimistic timer, advances server traversal once") {
                        first.owner.snapshot().state.position == 1 && first.owner.snapshot().state.occurrence?.eventId != initialEvent &&
                            first.raw.currentMediaItem?.mediaId == broker.tracks[1].first && first.raw.isPlaying && first.raw.currentPosition >= 100
                    }
                    val nextEvent = first.owner.snapshot().state.occurrence!!.eventId
                    val jumpSamples = PreparedTransportMedia.samples.get()
                    val jumpStarts = PreparedTransportMedia.starts.get()
                    submit(first.owner, LibraryPlaybackCommand.Jump(initialKeys.first()))
                    progress(first, jumpSamples, jumpStarts)
                    assertEquals(initialKeys, first.owner.snapshot().state.queue.map { it.key })
                    assertEquals(0, first.owner.snapshot().state.position)
                    assertNotEquals(initialEvent, first.owner.snapshot().state.occurrence!!.eventId)
                    assertNotEquals(nextEvent, first.owner.snapshot().state.occurrence!!.eventId)
                    return@use
                }
                if (case == ServiceCase.REMOTE_OUTPUT) {
                    val output = HostOutput(app, observerClient).also { remoteOutputs += it }
                    val remoteOwner = PlaybackCoordinator(observerClient, output, observerScope,
                        deviceIdentity = { "host-real-media3" to "Host Media3" }, mediaEventUptimeMs = { android.os.SystemClock.uptimeMillis() })
                        .also { remoteOwners += it; it.retry() }
                    await("second actual host player has a distinct authenticated WS connection") {
                        remoteOwner.state.value.connected && remoteOwner.state.value.connectionId != first.owner.state.value.connectionId
                    }
                    assertFalse(output.player.isPlaying)
                    submit(first.owner, LibraryPlaybackCommand.QueueIntent("high", LibraryPlaybackSelection.Track(LibraryPlaybackTrackRef(broker.tracks[1].first))))
                    submit(first.owner, LibraryPlaybackCommand.QueueIntent("low", LibraryPlaybackSelection.Track(LibraryPlaybackTrackRef(broker.tracks[2].first))))
                    val music = first.owner.snapshot().state
                    val handoffSamples = PreparedTransportMedia.samples.get()
                    submit(first.owner, LibraryPlaybackCommand.Select(remoteOwner.state.value.deviceId))
                    await("remote actual ExoPlayer starts only as exact selected connection") {
                        remoteOwner.state.value.isSelectedConnection && remoteOwner.snapshot().transfer == null && output.player.isPlaying &&
                            output.player.currentPosition >= 100 && PreparedTransportMedia.samples.get() > handoffSamples
                    }
                    fun musicPreserved(state: LibraryPlaybackState) {
                        assertEquals(music.queue, state.queue); assertEquals(music.highKeys, state.highKeys)
                        assertEquals(music.context, state.context); assertEquals(music.shuffle, state.shuffle); assertEquals(music.repeat, state.repeat)
                        assertEquals(initialEvent, state.occurrence!!.eventId)
                    }
                    fun quietSource(raw: ExoPlayer) {
                        val position = raw.currentPosition
                        repeat(25) { pump(); assertFalse(raw.isPlaying); assertFalse(raw.playWhenReady) }
                        assertEquals(position, raw.currentPosition)
                    }
                    quietSource(first.raw)
                    musicPreserved(remoteOwner.snapshot().state)
                    repeat(2) { cycle ->
                        compose.runOnIdle { full.value = cycle == 1 }
                        await("phone UI observes remote executor rather than idle local player") { first.connection.state.value.isPlaying }
                        compose.onNodeWithContentDescription("Pause").performClick()
                        await("mini/full Pause stops remote actual ExoPlayer") { !output.player.isPlaying && !output.player.playWhenReady && !remoteOwner.snapshot().state.desiredPlaying }
                        val position = output.player.currentPosition
                        val beforeSamples = PreparedTransportMedia.samples.get()
                        compose.onNodeWithContentDescription("Play").performClick()
                        await("mini/full Resume produces actual remote clock and PCM progress") {
                            output.player.isPlaying && output.player.currentPosition > position + 100 && PreparedTransportMedia.samples.get() > beforeSamples
                        }
                        quietSource(first.raw)
                        musicPreserved(remoteOwner.snapshot().state)
                    }
                    val backSamples = PreparedTransportMedia.samples.get()
                    val backStarts = PreparedTransportMedia.starts.get()
                    submit(first.owner, LibraryPlaybackCommand.Select(first.owner.state.value.deviceId))
                    progress(first, backSamples, backStarts)
                    assertTrue(first.owner.state.value.isSelectedConnection)
                    quietSource(output.player)
                    musicPreserved(first.owner.snapshot().state)
                    pause(first)
                    val pausedStarts = PreparedTransportMedia.starts.get()
                    submit(first.owner, LibraryPlaybackCommand.Select(remoteOwner.state.value.deviceId))
                    await("paused return selects remote without implicit sound") { remoteOwner.state.value.isSelectedConnection && remoteOwner.snapshot().transfer == null }
                    quietSource(output.player); quietSource(first.raw)
                    assertEquals(pausedStarts, PreparedTransportMedia.starts.get())
                    musicPreserved(remoteOwner.snapshot().state)
                    return@use
                }
                pause(first)
                if (case == ServiceCase.QUEUE_EDITS) {
                    submit(first.owner, LibraryPlaybackCommand.Seek(42.0))
                    await("parked absolute seek observes actual 42-second Media3 position") { first.raw.currentPosition == 42000L }
                    submit(first.owner, LibraryPlaybackCommand.QueueIntent("high", LibraryPlaybackSelection.Track(
                        LibraryPlaybackTrackRef(broker.tracks[1].first))))
                    val api = PersonalCollectionsApi(firstClient)
                    var low = runBlocking(Dispatchers.IO) { api.createPlaylist("Complete Low pair") }
                    for (track in broker.tracks.drop(1)) low = runBlocking(Dispatchers.IO) { api.addEntry(low.id, track.first, low.revision) }
                    val before = first.owner.snapshot().state
                    val rendererBefore = renderer()
                    val append = submit(first.owner, LibraryPlaybackCommand.QueueIntent("low", LibraryPlaybackSelection.Collection(
                        LibraryPlaybackContext("playlist", low.id, "untrusted display"))))
                    assertEquals(2, append.acceptedCount)
                    assertEquals("low", append.placement)
                    assertEquals("unchanged", append.execution)
                    val after = first.owner.snapshot().state
                    assertEquals(listOf(0, 1, 1, 2, 1, 2).map { broker.tracks[it].first }, after.queue.map { it.id })
                    assertEquals(before.queue, after.queue.take(4))
                    assertEquals(before.highKeys, after.highKeys)
                    assertEquals(initialEvent, after.occurrence!!.eventId)
                    assertFalse(after.desiredPlaying)
                    assertEquals(42000L, first.raw.currentPosition)
                    assertEquals(rendererBefore, renderer())
                    // Current-protocol structural corpus: no client queue upload or old
                    // negotiated sidecars. All future-only changes leave native music intact.
                    submit(first.owner, LibraryPlaybackCommand.QueueIntent("next", LibraryPlaybackSelection.Track(LibraryPlaybackTrackRef(broker.tracks[2].first))))
                    assertEquals("next", first.owner.snapshot().state.queue[1].intent)
                    submit(first.owner, LibraryPlaybackCommand.SetShuffle(true))
                    submit(first.owner, LibraryPlaybackCommand.SetShuffle(false))
                    for (repeat in listOf("one", "all", "off")) submit(first.owner, LibraryPlaybackCommand.SetRepeat(repeat))
                    val upcoming = first.owner.snapshot().state.queue.drop(1)
                    submit(first.owner, LibraryPlaybackCommand.Move(upcoming.last().key, null))
                    submit(first.owner, LibraryPlaybackCommand.Remove(upcoming.first().key))
                    submit(first.owner, LibraryPlaybackCommand.Rename("Renamed synthetic phone"))
                    submit(first.owner, LibraryPlaybackCommand.Clear)
                    assertEquals(1, first.owner.snapshot().state.queue.size)
                    assertEquals(initialEvent, first.owner.snapshot().state.occurrence!!.eventId)
                    assertEquals(rendererBefore, renderer())
                    settleStopped("Low/mode/move/remove/rename cannot autoplay or seek current", first.raw)
                    assertEquals(42000L, first.raw.currentPosition)
                    resume(first)
                    assertEquals(initialEvent, first.owner.snapshot().state.occurrence!!.eventId)
                    return@use
                }
                submit(first.owner, LibraryPlaybackCommand.QueueIntent("high", LibraryPlaybackSelection.Track(LibraryPlaybackTrackRef(broker.tracks[1].first))))
                submit(first.owner, LibraryPlaybackCommand.QueueIntent("low", LibraryPlaybackSelection.Track(LibraryPlaybackTrackRef(broker.tracks[2].first))))
                val musicalBaseline = first.owner.snapshot().state
                fun retainedMusic(state: LibraryPlaybackState) {
                    assertEquals(musicalBaseline.queue, state.queue); assertEquals(musicalBaseline.highKeys, state.highKeys)
                    assertEquals(musicalBaseline.context, state.context); assertEquals(musicalBaseline.shuffle, state.shuffle); assertEquals(musicalBaseline.repeat, state.repeat)
                    assertEquals(initialEvent, state.occurrence!!.eventId)
                }
                await("actual progression qualifies exactly one backend-owned history record") {
                    first.owner.snapshot().state.occurrence?.reported == true && history().getValue("total").jsonPrimitive.int == 1
                }
                fun oneEarnedHistory() {
                    val page = history()
                    assertEquals(1, page.getValue("total").jsonPrimitive.int)
                    val entry = page.getValue("items").jsonArray.single().jsonObject
                    assertEquals(initialEvent, entry.getValue("id").jsonPrimitive.content)
                    assertEquals(broker.tracks[0].first, entry.getValue("track").jsonObject.getValue("id").jsonPrimitive.content)
                }
                oneEarnedHistory()
                val staleResume = posted(first, "Resume")
                val oldFocus = shadowOf(first.service.get().getSystemService(AudioManager::class.java)).lastAudioFocusRequest?.listener
                resume(first)

                // Incapable target is selectable but cannot play. Its real empty activation
                // is ACKed only after the actual source player has stopped.
                submit(first.owner, LibraryPlaybackCommand.Select("host-controller-only"))
                await("transfer to exact incapable connection finishes stopped") {
                    !remote.failure && remote.snapshot?.let { it.selectedConnectionId == remote.connection &&
                        it.transfer == null && !it.state.desiredPlaying } == true
                }
                settleStopped("real source release before incapable activation", first.raw)
                deliver(first, staleResume)
                app.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
                oldFocus?.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
                settleStopped("stale posted Resume/noisy/late focus cannot replay after output replacement", first.raw)
                oneEarnedHistory()
                retainedMusic(remote.snapshot!!.state)
                assertFalse(first.owner.state.value.isSelectedConnection)
                val back = remote.command(LibraryPlaybackCommand.Select(first.owner.state.value.deviceId))
                await("explicit transfer back completes without implicit resume") {
                    remote.results[back]?.outcome == "accepted" && first.owner.state.value.isSelectedConnection &&
                        first.owner.snapshot().transfer == null && !first.owner.snapshot().state.desiredPlaying
                }
                settleStopped("selected stopped setup cannot autoplay", first.raw)
                resume(first)
                pause(first)

                val oldConnection = first.owner.state.value.connectionId
                val logicalDevice = first.owner.state.value.deviceId
                val keys = first.owner.snapshot().state.queue.map { it.key }
                val event = first.owner.snapshot().state.occurrence!!.eventId
                val room = first.owner.snapshot().sessionId
                val startsBeforeReconnect = PreparedTransportMedia.starts.get()
                first.owner.setServiceActive(false)
                settleStopped("socket loss immediately stops the real native endpoint", first.raw)
                first.owner.retry()
                await("explicit reconnect gets a new exact socket, not old connection authority") {
                    first.owner.state.value.connected && first.owner.state.value.connectionId != oldConnection && first.owner.snapshot().transfer == null
                }
                assertEquals(room, first.owner.snapshot().sessionId)
                assertEquals(keys, first.owner.snapshot().state.queue.map { it.key })
                assertFalse(first.owner.snapshot().state.desiredPlaying)
                settleStopped("passive reconnect does not replay music", first.raw)
                assertEquals(startsBeforeReconnect, PreparedTransportMedia.starts.get())

                // Real graph.configure retires client/coordinator/service; restore reuses
                // the retained cookie, with the observer preserving the actual server room.
                val firstWitness = requireNotNull(first.owner.captureBinding())
                first.connection.release()
                first.controller.release()
                first.service.destroy(); destroyed += first.service
                val replacement = activeGraph.configure(broker.server).also { clients += it }
                assertTrue("Configure must retain the stored HTTP session, not authenticate afresh", sessions.read()?.cookie == saved.cookie)
                assertNotNull(runBlocking(Dispatchers.IO) { activeGraph.restore() })
                assertTrue("Restored successor must still use the original cookie", sessions.read()?.cookie == saved.cookie)
                assertFalse(first.owner.isCurrent(firstWitness))
                assertFalse(first.owner.submit(LibraryPlaybackCommand.Resume))
                assertTrue("Restored successor retains the immutable HTTP session without printing its CSRF", saved.session == replacement.currentSession())
                val second = createLifetime()
                await("replacement service's genuine new coordinator/socket admission") {
                    second.owner.state.value.connected && second.owner.snapshot().transfer == null
                }
                assertNotSame(first.owner, second.owner)
                assertEquals(logicalDevice, second.owner.state.value.deviceId)
                assertNotEquals(firstWitness.connectionId, second.owner.state.value.connectionId)
                assertEquals(room, second.owner.snapshot().sessionId)
                assertEquals(keys, second.owner.snapshot().state.queue.map { it.key })
                assertEquals(event, second.owner.snapshot().state.occurrence!!.eventId)
                retainedMusic(second.owner.snapshot().state)
                oneEarnedHistory()
                settleStopped("same-cookie graph/service replacement remains paused", second.raw)
                assertEquals(startsBeforeReconnect, PreparedTransportMedia.starts.get())
                val selectReplacement = remote.command(LibraryPlaybackCommand.Select(second.owner.state.value.deviceId))
                await("replacement exact connection selected") {
                    remote.results[selectReplacement]?.outcome == "accepted" && second.owner.state.value.isSelectedConnection &&
                        second.owner.snapshot().transfer == null
                }
                resume(second)
                val retiredWitness = requireNotNull(second.owner.captureBinding())
                val otherClient = activeGraph.configure(broker.server).also { clients += it }
                assertFalse("Configure synchronously revokes old service sound", second.raw.isPlaying)
                assertFalse(second.owner.isCurrent(retiredWitness))
                second.connection.release(); second.controller.release(); second.service.destroy(); destroyed += second.service
                runBlocking(Dispatchers.IO) { broker.authenticate(otherClient, 1) }
                val other = createLifetime()
                val startsBeforeOtherAdmission = PreparedTransportMedia.starts.get()
                await("new account admission/selection completes without imported old music") {
                    other.owner.state.value.connected && other.owner.state.value.isSelectedConnection && other.owner.snapshot().transfer == null
                }
                assertNotEquals(room, other.owner.snapshot().sessionId)
                assertTrue(other.owner.snapshot().state.queue.isEmpty())
                assertNull(other.owner.snapshot().state.occurrence)
                assertFalse(other.owner.snapshot().state.desiredPlaying)
                settleStopped("account switch cannot resurrect retired output", other.raw)
                assertEquals(startsBeforeOtherAdmission, PreparedTransportMedia.starts.get())
                assertFalse(second.owner.submit(LibraryPlaybackCommand.Resume))
                assertEquals(room, remote.snapshot!!.sessionId)
                assertEquals(keys, remote.snapshot!!.state.queue.map { it.key })
                oneEarnedHistory()

                val otherSamples = PreparedTransportMedia.samples.get()
                val otherStarts = PreparedTransportMedia.starts.get()
                submit(other.owner, LibraryPlaybackCommand.QueueIntent("now", LibraryPlaybackSelection.Track(
                    LibraryPlaybackTrackRef(broker.tracks[2].first))))
                progress(other, otherSamples, otherStarts)
                assertEquals(listOf(broker.tracks[2].first), other.owner.snapshot().state.queue.map { it.id })
                otherClient.clearSession()
                await("authentication loss fences WS and stops real service output") {
                    !other.owner.state.value.connected && !other.raw.isPlaying && !other.raw.playWhenReady
                }
                settleStopped("retired account cannot keep consuming PCM", other.raw)
                assertFalse(other.owner.submit(LibraryPlaybackCommand.Resume))
                assertFalse(remote.failure)
            } catch (failure: Throwable) { primary = failure; throw failure }
            finally {
                var cleanupFailure: Throwable? = null
                fun cleanup(action: () -> Unit) {
                    try { action() } catch (failure: Throwable) {
                        if (primary != null) primary!!.addSuppressed(failure)
                        else if (cleanupFailure == null) cleanupFailure = failure else cleanupFailure!!.addSuppressed(failure)
                    }
                }
                PreparedTransportMedia.holdReady.set(false)
                PreparedTransportMedia.failNextRender.set(false)
                cleanup { remoteOwners.forEach { it.close() }; observer?.close(); observerScope.cancel() }
                remoteOutputs.forEach { output -> cleanup { output.close() } }
                lifetimes.asReversed().forEach { life ->
                    cleanup { life.connection.release() }
                    cleanup { life.controller.release() }
                    if (life.service !in destroyed) cleanup { life.service.destroy() }
                }
                cleanup { graph?.clear() }
                clients.forEach { client -> cleanup {
                    client.clearSession(); client.httpClient.dispatcher.cancelAll()
                    client.httpClient.connectionPool.evictAll()
                    client.httpClient.dispatcher.executorService.shutdown()
                    check(client.httpClient.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS))
                } }
                cleanupFailure?.let { throw it }
            }
        }
    }
}
