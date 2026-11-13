package dev.spofify.mobile.playback

import android.app.ActivityManager
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import dev.spofify.mobile.*
import dev.spofify.mobile.core.*
import dev.spofify.mobile.feature.devices.LibraryPlaybackAuthority
import dev.spofify.mobile.feature.devices.LibraryPlaybackObservation
import dev.spofify.mobile.feature.devices.PlaybackCoordinator
import dev.spofify.mobile.playback.cache.SongCache
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.FutureTask
import java.io.IOException

/** Real sole service, default source/extractor, exact-origin authenticated WAV and measured PCM.
 * Only the renderer is synthetic: these are not installed Android/acoustic/FGS-policy proofs.
 * Controller snapshots below supply notification presentation only, NEVER instructions/leases.
 * Process-local test permits probe service guards, not server selection or lease acceptance.
 * Server queues, selection, report/ACK leases and caller bindings have separate contract suites.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SpofifyApplication::class,
    shadows = [SongCachePlaybackMedia::class, SongCachePlaybackMedia.LoaderRelease::class],
    instrumentedPackages = ["androidx.media3.exoplayer"])
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackServiceBridgeTest {
    private lateinit var app: SpofifyApplication
    private lateinit var graph: AppGraph
    private lateinit var client: SpofifyClient
    private lateinit var web: MockWebServer
    private var service: ServiceController<PlaybackService>? = null
    private lateinit var scope: CoroutineScope
    private lateinit var presentation: PresentationTransport
    private lateinit var coordinator: PlaybackCoordinator
    private val owner = Any()
    private val operations = mutableListOf<Deferred<String>>()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val loaderReleases = CopyOnWriteArrayList<CountDownLatch>()
    private val gates = CopyOnWriteArrayList<CountDownLatch>()
    private val observations = mutableListOf<Pair<String, LibraryPlaybackObservation>>()
    private var loginCount = 0
    private var stateVersion = 0L
    @Volatile private var audioGate: CountDownLatch? = null
    @Volatile private var heldAudioEntered: CountDownLatch? = null
    @Volatile private var heldTrack: String? = null
    @Volatile private var account = "fixture-account"
    @Volatile private var audioStatus = 200

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

    /** Only explicitly supplied server instructions execute; sends never fabricate acceptance. */
    private class PresentationTransport(override val identity: ValidatedSessionIdentity,
        private val current: () -> Boolean) : LibraryPlaybackTransport {
        val incoming = Channel<LibraryPlaybackEvent>(16)
        override val events = incoming.receiveAsFlow()
        private var closed = false
        private var commands = 0L
        private var sequence = 0L
        val sent = mutableListOf<LibraryPlaybackClientMessage>()
        override val isCurrent get() = !closed && current()
        override fun send(message: LibraryPlaybackClientMessage): LibraryPlaybackSend? {
            if (!isCurrent) return null
            sent += message
            return if (message is LibraryPlaybackClientMessage.Command)
                LibraryPlaybackSend(System.nanoTime(), commandId = (++commands).toString())
            else LibraryPlaybackSend(System.nanoTime(), clientSequence = ++sequence)
        }
        override fun close() { closed = true; incoming.close() }
    }

    private val player: ExoPlayer get() = field("player") as ExoPlayer
    private val focus: PlaybackAudioFocus get() = field("audioFocus") as PlaybackAudioFocus
    private val foreground: PlaybackForegroundNotification get() = field("foreground") as PlaybackForegroundNotification
    private fun field(name: String): Any? = PlaybackService::class.java.getDeclaredField(name).let {
        it.isAccessible = true; it.get(requireNotNull(service).get())
    }

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.graph.clear()
        app.getSharedPreferences("connection", 0).edit().clear().commit()
        SongCachePlaybackMedia.reset()
        SongCachePlaybackMedia.onLoaderRelease = {
            val released = CountDownLatch(1)
            loaderReleases += released
            val signal: () -> Unit = { released.countDown() }
            signal
        }
        web = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    if (request.method == "POST" && request.path == "/api/auth/login") return MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setHeader("Set-Cookie", "spofify_session=synthetic-cookie; Path=/; HttpOnly; Max-Age=3600")
                        .setBody("""{"accountId":"$account","username":"listener","role":"user","csrfToken":"synthetic-csrf"}""")
                    if (request.method == "GET" && request.path?.substringBefore('?')?.endsWith("/audio") == true) {
                        val heldId = heldTrack
                        if (heldId == null || request.path!!.contains(heldId)) audioGate?.let {
                            heldAudioEntered?.countDown()
                            check(it.await(5, TimeUnit.SECONDS)) { "Synthetic audio gate was not released" }
                        }
                        if (audioStatus != 200) return MockResponse().setResponseCode(audioStatus)
                        check(request.getHeader("Cookie") == "spofify_session=synthetic-cookie")
                        check(request.getHeader("Origin") == "http://127.0.0.1:${web.port}")
                        val marker = if (request.path!!.contains("track-b")) 0x2222 else 0x1111
                        val content = wav(marker)
                        val range = request.getHeader("Range")
                        val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
                        if (start >= content.size) return MockResponse().setResponseCode(416)
                        return MockResponse().setResponseCode(if (range == null) 200 else 206)
                            .setHeader("Content-Type", "audio/wav").setHeader("Accept-Ranges", "bytes")
                            .apply { if (range != null) setHeader("Content-Range", "bytes $start-${content.lastIndex}/${content.size}") }
                            .setBody(Buffer().write(content, start, content.size - start))
                    }
                    return MockResponse().setResponseCode(500) // no legacy resolver/history/player route
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        graph = AppGraph(app, MemorySessionStore(), MemoryLoginStore(),
            audioCacheLimits = SongCache.Limits(maxTracks = 10, maxBytes = 4L * 1024 * 1024, attemptMillis = 5_000))
        SpofifyApplication::class.java.getDeclaredField("graph").apply { isAccessible = true; set(app, graph) }
        client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
        graph.playbackCoordinator?.close() // no ticket/socket network in these direct service probes
        PlaybackService.onServiceActiveChanged = null
        login()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val identity = requireNotNull(client.validatedSessionIdentity())
        presentation = PresentationTransport(identity) { client.validatedSessionIdentity() == identity }
        coordinator = PlaybackCoordinator(client, Media3PlaybackEndpoint(app), scope,
            openTransport = { _, _, _, _ -> presentation }, deviceIdentity = { "phone" to "Phone" })
        AppGraph::class.java.getDeclaredField("playbackCoordinator").apply { isAccessible = true; set(graph, coordinator) }
        val initial = display(track())
        presentation.incoming.trySend(LibraryPlaybackEvent.Message(LibraryPlaybackServerMessage(
            "snapshot", "fixture-session", "fixture-connection", "phone", snapshot = initial))).getOrThrow()
        coordinator.retry()
        await("presentation admission") { coordinator.state.value.connected }
        PlaybackService.onServiceActiveChanged = null
        createService()
        assertEquals(Looper.getMainLooper(), player.applicationLooper)
        assertEquals(0, player.mediaItemCount)
        assertStopped()
        assertOnlyAllowedTraffic()
    }

    @After fun tearDown() {
        PlaybackService.onServiceActiveChanged = null
        gates.forEach(CountDownLatch::countDown)
        try {
            operations.forEach { it.cancel() }
            if (::scope.isInitialized) await("operation shutdown") { operations.all { it.isCompleted } }
            if (::coordinator.isInitialized) coordinator.close()
            service?.destroy(); service = null
            if (::graph.isInitialized) graph.clear()
            await("actual loader release completion") { loaderReleases.all { it.count == 0L } }
        } finally {
            if (::scope.isInitialized) scope.cancel()
            SongCachePlaybackMedia.reset()
            if (::web.isInitialized) web.shutdown()
            app.getSharedPreferences("connection", 0).edit().clear().commit()
        }
    }

    private fun createService() {
        service = Robolectric.buildService(PlaybackService::class.java).create()
        val actual = requireNotNull(service).get()
        shadowOf(actual.getSystemService(ActivityManager::class.java)).setProcesses(listOf(
            ActivityManager.RunningAppProcessInfo().apply {
                pid = android.os.Process.myPid(); importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            }))
        shadowOf(actual.getSystemService(AudioManager::class.java)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    }
    private fun login() {
        runBlocking { client.login("listener", "synthetic-password") }
        loginCount++
    }
    private fun track(id: String = "track-a", key: Long = 7) = LibraryPlaybackTrack(id, "Song $key",
        listOf(LibraryPlaybackArtist("First Artist", "artist-a"), LibraryPlaybackArtist("Second Artist", "artist-b")),
        "Album", "album", 123.456789, key, key, "now", entryId = "entry-$key", targetId = "rules",
        occurrenceId = "長い occurrence / ".repeat(30), playbackContext = LibraryPlaybackContext("derived", "rules", "Rules", 19, "fence"))
    private fun instruction(id: String, type: String = "execute", track: LibraryPlaybackTrack? = track(),
        elapsed: Double = 0.0, play: Boolean = false, event: String = "event-a") = LibraryPlaybackInstruction(
        id, type, 2, event, elapsed, play, track = track)
    private fun display(track: LibraryPlaybackTrack) = LibraryPlaybackSnapshot("fixture-session", ++stateVersion, 2,
        LibraryPlaybackState(1, listOf(track), 0, null, false, "off", false, emptyList(), 0.0, 0.0,
            false, false, false, "", false, "", LibraryPlaybackOccurrence(track.key, track.id, "event-a", 0.0, false)),
        LibraryPlaybackStatus(2, stateVersion, stateVersion, "event-a", false, 0.0, 0.0, false, false, false, ""),
        listOf(LibraryPlaybackDevice("phone", "Phone", true, true)), "phone", "fixture-connection", null, 0)
    private fun publishQueue(queue: List<LibraryPlaybackTrack>, selected: String = "fixture-connection") {
        val next = display(queue.first()).let { it.copy(selectedConnectionId = selected, state = it.state.copy(queue = queue)) }
        presentation.incoming.trySend(LibraryPlaybackEvent.Message(LibraryPlaybackServerMessage(
            "state", "fixture-session", snapshot = next))).getOrThrow()
        await("canonical controller publication") { coordinator.state.value.snapshot?.stateVersion == next.stateVersion }
    }
    private fun isCached(id: String): Boolean {
        val cache = graph.audioCache(client, requireNotNull(client.currentSession())) ?: return false
        val entry = cache.pin(id) ?: return false
        return try { cache.file(entry) != null } finally { cache.unpin(entry) }
    }
    private fun cached(id: String): ByteArray? {
        val cache = graph.audioCache(client, requireNotNull(client.currentSession())) ?: return null
        val entry = cache.pin(id) ?: return null
        return try { cache.file(entry)?.readBytes() } finally { cache.unpin(entry) }
    }
    private fun permit(check: () -> Boolean = { true }) = LibraryPlaybackAuthority(check)
    private fun observe(id: String, ended: Boolean, owner: Any = this.owner) {
        observations += id to PlaybackService.observeLibrary(owner)
        if (ended) assertFalse(PlaybackService.observeLibrary(owner).isPlaying)
    }
    private fun launchOperation(block: suspend () -> String): Deferred<String> = scope.async(start = CoroutineStart.UNDISPATCHED) {
        block()
    }.also(operations::add)
    private fun result(block: suspend () -> String): String {
        val job = launchOperation(block)
        await("bounded native operation") { job.isCompleted }
        return runBlocking { job.await() }
    }
    private fun prepare(track: LibraryPlaybackTrack? = track(), owner: Any = this.owner, current: () -> Boolean = { true }): String = result {
        PlaybackService.prepareLibrary(instruction("prepare", "prepare", track), current, owner) { id, ended -> observe(id, ended, owner) }
    }
    private fun execute(value: LibraryPlaybackInstruction, owner: Any = this.owner, authority: LibraryPlaybackAuthority = permit()): String = result {
        PlaybackService.executeLibrary(value, authority, owner) { id, ended -> observe(id, ended, owner) }
    }
    private fun play(owner: Any = this.owner): LibraryPlaybackAuthority {
        assertEquals("ok", prepare(owner = owner))
        val authority = permit()
        assertEquals("ok", execute(instruction("active", play = true), owner, authority))
        await("actual authenticated PCM") { player.isPlaying && SongCachePlaybackMedia.bytes(0x1111) > 0 }
        return authority
    }
    private fun assertStopped() {
        assertFalse(player.playWhenReady)
        assertFalse(player.isPlaying)
        assertEquals(Player.STATE_IDLE, player.playbackState)
        assertFalse(focus.isGranted)
        assertFalse(foreground.foregroundConfirmed)
    }
    private fun assertOnlyAllowedTraffic() {
        assertEquals(loginCount, requests.count { it.method == "POST" && it.path == "/api/auth/login" })
        requests.forEach { request -> assertTrue("Unexpected client operation ${request.method} ${request.path}",
            request.method == "POST" && request.path == "/api/auth/login" ||
                request.method == "GET" && request.path?.substringBefore('?')?.endsWith("/audio") == true) }
    }
    private fun await(label: String, condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < until) {
            shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.MILLISECONDS)
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Timed out: $label")
    }
    private fun pump(ms: Long) {
        repeat((ms / 5).toInt()) { shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.MILLISECONDS); Thread.sleep(5) }
    }
    private fun wav(marker: Int): ByteArray {
        val size = 12000 * 16
        return ByteBuffer.allocate(size + 44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(size + 36); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1.toShort()); putShort(1.toShort()); putInt(8000); putInt(16000)
            putShort(2.toShort()); putShort(16.toShort()); put("data".toByteArray()); putInt(size)
            repeat(size / 2) { putShort(marker.toShort()) }
        }.array()
    }

    @Test fun startupAndServiceRecreationDoNotRestorePrepareOrStartMusic() {
        pump(100)
        assertEquals(1, SongCachePlaybackMedia.factories.get().toInt())
        assertEquals(0, player.mediaItemCount)
        assertEquals(0, requests.count { it.path?.endsWith("/audio") == true })
        service!!.destroy(); service = null
        createService()
        assertEquals(2, SongCachePlaybackMedia.factories.get().toInt())
        assertEquals(0, player.mediaItemCount)
        assertStopped()
        assertOnlyAllowedTraffic()
    }

    @Test fun tracklessPreparationNeedsNoSourceFocusForegroundOrPlayback() {
        assertEquals("ok", prepare(track = null))
        assertEquals(0, player.mediaItemCount)
        assertEquals(0L, SongCachePlaybackMedia.starts.get())
        assertStopped()
        assertEquals(loginCount, requests.size)
    }

    @Test fun preparationLoadsOneCanonicalTrackSilentlyThroughExactOriginAudioAndNeverImportsAQueue() {
        val canonical = track("duplicate/a%")
        assertEquals("ok", prepare(canonical))
        assertEquals(1, player.mediaItemCount)
        val media = requireNotNull(player.currentMediaItem)
        assertEquals(canonical.id, media.mediaId)
        assertEquals("library-${canonical.key}", PlaybackMedia.occurrence(media))
        assertEquals("${client.server.origin}/api/library/tracks/duplicate%2Fa%25/audio", media.localConfiguration!!.uri.toString())
        assertNull(media.mediaMetadata.artworkUri)
        assertNull(PlaybackMedia.libraryItem(media)!!.libraryPath)
        assertEquals(0L, SongCachePlaybackMedia.starts.get())
        assertEquals(0L, SongCachePlaybackMedia.samples.get())
        assertTrue(requests.any { it.path == "/api/library/tracks/duplicate%2Fa%25/audio" })
        assertStopped()
        assertOnlyAllowedTraffic()
    }

    @Test fun observationUsesMedia3DurationAndMillisecondPositionRatherThanInstructionMetadataOrIntent() {
        assertEquals("ok", prepare())
        assertEquals("ok", execute(instruction("paused-seek", elapsed = 9.123456)))
        val actual = PlaybackService.observeLibrary(owner)
        assertEquals(player.currentPosition / 1000.0, actual.elapsed, 0.0)
        assertEquals(9.123, actual.elapsed, 0.001)
        assertEquals(player.duration.coerceAtLeast(0) / 1000.0, actual.duration, 0.0)
        assertNotEquals(track().duration, actual.duration, 0.000001)
        assertFalse(actual.isPlaying)
        assertTrue("Actual source duration was discovered", observations.any { it.second.duration == 12.0 })
        assertStopped()
    }

    @Test fun successfulExecutionRequiresActualPlayingAndConsumesAuthenticatedPcmWithoutClientHistoryPost() {
        play()
        assertTrue(focus.isGranted)
        assertTrue(foreground.foregroundConfirmed)
        val first = PlaybackService.observeLibrary(owner)
        assertTrue(first.isPlaying)
        assertEquals(12.0, first.duration, 0.01)
        val position = first.elapsed
        pump(1300)
        val later = PlaybackService.observeLibrary(owner)
        assertTrue(later.isPlaying)
        assertTrue(later.elapsed > position)
        assertTrue(SongCachePlaybackMedia.bytes(0x1111) > 0)
        assertOnlyAllowedTraffic() // real progression still never POSTs /api/me/history
    }

    @Test fun repeatedSilentAbsoluteExecutionPreservesTheOnePreparedMediaIdentity() {
        assertEquals("ok", prepare())
        val media = player.currentMediaItem
        val token = PlaybackMedia.occurrence(media)
        repeat(3) {
            assertEquals("ok", execute(instruction("paused-$it", elapsed = 2.25)))
            assertSame(media, player.currentMediaItem)
            assertEquals(token, PlaybackMedia.occurrence(player.currentMediaItem))
            assertEquals(1, player.mediaItemCount)
        }
        assertEquals(0L, SongCachePlaybackMedia.starts.get())
        assertStopped()
    }

    @Test fun privatePreparedLineageSurvivesLegitimateMedia3MetadataCopiesWithoutReinstall() {
        assertEquals("ok", prepare())
        val original = requireNotNull(player.currentMediaItem)
        val identity = original.localConfiguration!!.tag
        assertNotNull(identity)
        val copy = original.buildUpon().setMediaMetadata(original.mediaMetadata.buildUpon()
            .setTitle("Updated native presentation").build()).build()
        player.replaceMediaItem(0, copy)
        val installed = requireNotNull(player.currentMediaItem)
        assertSame(identity, installed.localConfiguration!!.tag)
        assertEquals("ok", execute(instruction("metadata-copy", elapsed = 2.25)))
        assertSame(installed, player.currentMediaItem)
        assertSame(identity, field("libraryPreparedMediaIdentity"))
        assertStopped()
        assertOnlyAllowedTraffic()
    }

    @Test fun cachedTrackAndEventCannotSubstituteForActualInstalledNativeIdentity() {
        assertEquals("ok", prepare())
        val original = requireNotNull(player.currentMediaItem)
        val substituted = PlaybackMedia.create(LibraryItem("track", "track-b", "Different native media", hasAudio = true),
            client, "library-7") // Even the cached native key is not sufficient.
        player.setMediaItem(substituted)
        assertEquals(track(), field("libraryPreparedTrack"))
        assertEquals("event-a", field("libraryPreparedEvent"))
        assertEquals("ok", execute(instruction("restore-real-A", elapsed = 1.25)))
        val actual = requireNotNull(player.currentMediaItem)
        assertNotSame(substituted, actual)
        assertEquals(original.mediaId, actual.mediaId)
        assertEquals(original.localConfiguration!!.uri, actual.localConfiguration!!.uri)
        assertEquals("library-7", PlaybackMedia.occurrence(actual))
        assertSame(client, field("libraryPreparedClient"))
        assertSame(client.currentSession(), field("libraryPreparedSession"))
        assertStopped()
        assertOnlyAllowedTraffic()
    }

    @Test fun liveSameEventSeekRetainsMediaFocusAndForegroundWithoutStartingTheRendererAgain() {
        play()
        val media = player.currentMediaItem
        val manager = service!!.get().getSystemService(AudioManager::class.java)
        val focusRequest = shadowOf(manager).lastAudioFocusRequest
        val starts = SongCachePlaybackMedia.starts.get()
        assertEquals("ok", execute(instruction("live-seek", elapsed = 3.25, play = true)))
        await("same-event seek rebuffer completion") { player.isPlaying }
        assertSame(media, player.currentMediaItem)
        assertSame(focusRequest, shadowOf(manager).lastAudioFocusRequest)
        assertEquals(starts, SongCachePlaybackMedia.starts.get())
        assertTrue(focus.isGranted)
        assertTrue(foreground.foregroundConfirmed)
    }

    private fun backgroundProcess() {
        shadowOf(service!!.get().getSystemService(ActivityManager::class.java)).setProcesses(listOf(
            ActivityManager.RunningAppProcessInfo().apply {
                pid = android.os.Process.myPid(); importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
            }))
    }

    @Test fun detachedBackgroundReplacementDrainsOldAudioButRetainsOnlyActualForegroundOwnership() {
        play()
        backgroundProcess()
        PlaybackService.suspendLibraryForReplacement(owner)
        assertFalse(player.playWhenReady)
        assertFalse(player.isPlaying)
        assertFalse(focus.isGranted)
        assertTrue(foreground.foregroundConfirmed)
        assertEquals("ok", execute(instruction("next", track = track("track-b", 8), event = "event-b", play = true)))
        await("authorized next PCM in background") { SongCachePlaybackMedia.bytes(0x2222) > 0 && player.isPlaying }
        assertTrue(foreground.foregroundConfirmed)
        assertTrue(PlaybackService.releaseLibrary(owner))
        assertStopped()
        assertEquals("blocked", execute(instruction("after-release", play = true)))
        assertStopped() // Prior foreground ownership is not a reusable background exemption.
        assertOnlyAllowedTraffic()
    }

    @Test fun admittedNextRetainsForegroundAndFailedOrAbandonedReplacementRevokesIt() {
        play()
        backgroundProcess()
        val admitted = instruction("admitted-next", track = track("track-b", 8), event = "event-b", play = true)
            .copy(planId = "server-plan")
        assertEquals("ok", execute(admitted))
        assertTrue(player.isPlaying)
        shadowOf(service!!.get().getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        assertEquals("blocked", execute(instruction("failed-next", event = "event-c", play = true)))
        assertStopped()
        assertOnlyAllowedTraffic()
    }

    @Test fun authenticationLossDemotesRetainedForegroundBeforeAnySuccessorCanStart() {
        play()
        val actualForeground = foreground
        val actualFocus = focus
        backgroundProcess()
        PlaybackService.suspendLibraryForReplacement(owner)
        assertTrue(actualForeground.foregroundConfirmed)
        client.clearSession()
        pump(150)
        assertFalse(actualForeground.foregroundConfirmed)
        assertFalse(actualFocus.isGranted)
        assertEquals("rejected", execute(instruction("revoked-next", play = true)))
    }

    @Test fun replacementForegroundRetentionExpiresWithoutNewExecution() {
        play()
        backgroundProcess()
        PlaybackService.suspendLibraryForReplacement(owner)
        assertTrue(foreground.foregroundConfirmed)
        pump(3_600)
        assertStopped()
        assertEquals("blocked", execute(instruction("too-late", play = true)))
        assertStopped()
    }

    private fun server(message: LibraryPlaybackServerMessage) {
        presentation.incoming.trySend(LibraryPlaybackEvent.Message(message)).getOrThrow()
        pump(30)
    }

    private fun notificationResume() {
        val method = PlaybackService::class.java.getDeclaredMethod("notificationIntent", String::class.java)
            .apply { isAccessible = true }
        val intent = method.invoke(service!!.get(), "resume") as android.app.PendingIntent
        service!!.get().onStartCommand(shadowOf(intent).savedIntent, 0, 1)
        assertTrue(presentation.sent.last() is LibraryPlaybackClientMessage.Command)
    }

    private fun deliverHeadset(event: android.view.KeyEvent): Boolean {
        val session = field("session") as androidx.media3.session.MediaSession
        val impl = androidx.media3.session.MediaSession::class.java.getDeclaredMethod("getImpl")
            .apply { isAccessible = true }.invoke(session)
        val callback = impl.javaClass.getDeclaredField("callback").apply { isAccessible = true }.get(impl)
            as androidx.media3.session.MediaSession.Callback
        val who = androidx.media3.session.MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            "synthetic.headset", 100, android.os.Process.myUid(), 0, 0, false, android.os.Bundle.EMPTY, true)
        if (field("authenticatedSession") == null)
            assertEquals(androidx.media3.session.SessionResult.RESULT_ERROR_PERMISSION_DENIED,
                callback.onPlayerCommandRequest(session, who, Player.COMMAND_PLAY_PAUSE))
        return callback.onMediaButtonEvent(session, who,
            Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, event))
    }

    private fun acceptResume(id: String = "1", final: Boolean = false, execution: String = "pending") = server(
        LibraryPlaybackServerMessage("result", "fixture-session", result = LibraryPlaybackResult(
            id, "accepted", "", stateVersion + 1, 0, "none", execution, final)))

    private fun activateResume(command: String = "1", suffix: String = "", output: Long = 3): String {
        server(LibraryPlaybackServerMessage("instruction", "fixture-session", instruction =
            instruction("os-prepare$suffix", "prepare").copy(commandId = command, outputVersion = output - 1)))
        await("OS action silent preparation ACK") {
            presentation.sent.any { it is LibraryPlaybackClientMessage.Ack && it.instructionId == "os-prepare$suffix" }
        }
        val basis = presentation.sent.count { it !is LibraryPlaybackClientMessage.Command }.toLong()
        server(LibraryPlaybackServerMessage("instruction", "fixture-session", instruction =
            instruction("os-activate$suffix", "activate", play = true).copy(outputVersion = output, commandId = command,
                lease = LibraryPlaybackLeaseTiming(basis, 8_000))))
        await("OS action actual outcome") {
            presentation.sent.any { it is LibraryPlaybackClientMessage.Ack && it.instructionId == "os-activate$suffix" }
        }
        return presentation.sent.filterIsInstance<LibraryPlaybackClientMessage.Ack>().last().status
    }

    @Test fun freshNotificationResumeUsesOnlyItsAcceptedOutgoingCommandAllowanceInBackground() {
        backgroundProcess()
        notificationResume()
        acceptResume()
        assertEquals("ok", activateResume())
        assertTrue(player.isPlaying)
        assertTrue(focus.isGranted)
        assertTrue(foreground.foregroundConfirmed)
        assertOnlyAllowedTraffic()
    }

    @Test fun freshTrustedHeadsetResumeBindsExactGraphSessionBeforeColdServicePreparation() {
        // Retained server current may be controlled before this service has ever prepared
        // online media (for example after leaving deliberate offline listening).
        PlaybackService::class.java.getDeclaredField("authenticatedSession").apply { isAccessible = true }
            .set(service!!.get(), null)
        assertNull(field("authenticatedSession"))
        assertNotNull(client.currentSession())
        assertEquals(0, player.mediaItemCount)
        backgroundProcess()
        android.os.SystemClock.sleep(1)
        val now = android.os.SystemClock.uptimeMillis()
        val event = android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY, 0)
        assertTrue(deliverHeadset(event))
        assertTrue(presentation.sent.last() is LibraryPlaybackClientMessage.Command)
        acceptResume()
        assertEquals("ok", activateResume())
        assertSame(client, field("client"))
        assertSame(client.currentSession(), field("authenticatedSession"))
        assertTrue(player.isPlaying)
        assertTrue(foreground.foregroundConfirmed)
        assertOnlyAllowedTraffic()
    }

    @Test fun delayedPhysicalHeadsetPressCannotMintFreshAllowanceAtDeliveryTime() {
        backgroundProcess()
        android.os.SystemClock.sleep(1)
        val now = android.os.SystemClock.uptimeMillis()
        val event = android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY, 0)
        pump(5_100)
        assertTrue(deliverHeadset(event))
        assertTrue(presentation.sent.filterIsInstance<LibraryPlaybackClientMessage.Command>().isEmpty())
        assertEquals("blocked", activateResume())
        assertStopped()
    }

    @Test fun staleNotificationAllowanceCannotStartBackgroundOutput() {
        backgroundProcess()
        notificationResume()
        pump(5_100)
        acceptResume()
        assertEquals("blocked", activateResume())
        assertStopped()
    }

    @Test fun remoteCollidingCommandIdCannotBorrowUnacceptedLocalActionAllowance() {
        backgroundProcess()
        notificationResume()
        // A remote socket can also issue command 1. No result accepted for THIS socket.
        assertEquals("blocked", activateResume())
        assertStopped()
    }

    @Test fun supersededAcceptedActionCannotLendAllowanceToRemoteReplacementWithSameId() {
        backgroundProcess()
        notificationResume()
        acceptResume()
        acceptResume(final = true, execution = "superseded")
        acceptResume() // A late/duplicate old accepted update cannot recreate the removed request.
        assertEquals("blocked", activateResume())
        assertStopped()
    }

    @Test fun lateAcceptanceCannotRetroactivelyAuthorizeAnEarlierRemoteSameIdInstruction() {
        backgroundProcess()
        notificationResume()
        assertEquals("blocked", activateResume())
        acceptResume() // The instruction already completed; no media replay on a result.
        pump(100)
        assertStopped()
        assertEquals(0L, SongCachePlaybackMedia.starts.get())
    }

    @Test fun oneAcceptedUserActionCannotAuthorizeASecondBackgroundActivation() {
        backgroundProcess()
        notificationResume()
        acceptResume()
        assertEquals("ok", activateResume())
        server(LibraryPlaybackServerMessage("instruction", "fixture-session", instruction =
            instruction("os-release", "release").copy(outputVersion = 3)))
        assertStopped()
        // Even a repeated accepted update cannot reset the captured action or consume it twice.
        acceptResume()
        assertEquals("blocked", activateResume(suffix = "-again", output = 4))
        assertStopped()
    }

    @Test fun queueOnlyAppendPreservesExactAcceptedBackgroundResumeWhileNativeStartIsPending() {
        // Establish live, non-transfer authority. A native stop without an authoritative
        // stopped ACK does not park it; the next exact Resume therefore uses execute.
        assertEquals("ok", activateResume())
        PlaybackService.suspendLocalOutput()
        assertStopped()
        backgroundProcess()
        val next = track("track-b", 8)
        fun publishCurrent(queued: Boolean = false) {
            val value = display(next)
            server(LibraryPlaybackServerMessage("state", "fixture-session", snapshot = value.copy(
                outputVersion = 3,
                state = value.state.copy(desiredPlaying = true,
                    queue = if (queued) listOf(next, track("track-c", 9).copy(intent = "low")) else listOf(next),
                    occurrence = value.state.occurrence!!.copy(eventId = "event-b")),
                playback = value.playback.copy(outputVersion = 3, eventId = "event-b"))))
        }
        // Canonical current B has advanced, but its targeted work has not reached this
        // executor yet. The fresh Resume is admitted against that exact current/version.
        publishCurrent()
        notificationResume()
        acceptResume()
        publishCurrent()
        val held = CountDownLatch(1).also(gates::add)
        val entered = CountDownLatch(1)
        audioGate = held; heldAudioEntered = entered; heldTrack = "track-b"
        try {
            val basis = presentation.sent.count { it !is LibraryPlaybackClientMessage.Command }.toLong()
            server(LibraryPlaybackServerMessage("instruction", "fixture-session", instruction =
                instruction("pending-resume", track = next, event = "event-b", play = true).copy(
                    outputVersion = 3, commandId = "1", lease = LibraryPlaybackLeaseTiming(basis, 8_000))))
            await("actual accepted Resume still loading B") { entered.count == 0L && field("libraryPending") == true }
            assertTrue(coordinator.submit(LibraryPlaybackCommand.QueueIntent("low",
                LibraryPlaybackSelection.Collection(LibraryPlaybackContext("album", "queued-album", "Queued")))))
            server(LibraryPlaybackServerMessage("result", "fixture-session", result = LibraryPlaybackResult(
                "2", "accepted", "", stateVersion + 1, 1, "low", "unchanged", true)))
            publishCurrent(queued = true) // No transfer, superseded result or replacement instruction.
            held.countDown(); audioGate = null
            await("unchanged Resume completes after queue-only append") {
                presentation.sent.any { it is LibraryPlaybackClientMessage.Ack && it.instructionId == "pending-resume" }
            }
            assertEquals("ok", presentation.sent.filterIsInstance<LibraryPlaybackClientMessage.Ack>()
                .single { it.instructionId == "pending-resume" }.status)
            await("actual B PCM after pending Resume") { player.isPlaying && SongCachePlaybackMedia.bytes(0x2222) > 0 }
            assertTrue(foreground.foregroundConfirmed)
            assertTrue(focus.isGranted)
            assertOnlyAllowedTraffic()
        } finally { held.countDown(); audioGate = null }
    }

    @Test fun newPauseRevokesTheOldAcceptedActionBeforeItsReplacementArrives() {
        backgroundProcess()
        notificationResume()
        acceptResume()
        coordinator.submit(LibraryPlaybackCommand.Pause)
        assertEquals("blocked", activateResume())
        assertStopped()
    }

    @Test fun explicitNewPlayRevokesAnOlderAcceptedResumeAllowanceImmediately() {
        backgroundProcess()
        notificationResume()
        acceptResume()
        assertTrue(coordinator.submit(LibraryPlaybackCommand.QueueIntent("now",
            LibraryPlaybackSelection.Collection(LibraryPlaybackContext("album", "replacement", "Replacement")))))
        assertEquals("blocked", activateResume())
        assertStopped()
    }

    @Test fun trustedActionNeverOverridesActualAndroidFocusDenial() {
        backgroundProcess()
        notificationResume()
        acceptResume()
        shadowOf(service!!.get().getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        assertEquals("blocked", activateResume())
        assertStopped()
    }

    @Test fun trustedActionNeverOverridesActualAndroidPromotionDenial() {
        backgroundProcess()
        notificationResume()
        acceptResume()
        shadowOf(service!!.get()).setThrowInStartForeground(ForegroundServiceStartNotAllowedException("Synthetic denial"))
        assertEquals("blocked", activateResume())
        assertStopped()
    }

    @Test fun deniedForegroundPromotionReportsBlockedAndCannotOpenOrStartMoreAudio() {
        assertEquals("ok", prepare())
        val before = requests.size
        shadowOf(service!!.get()).setThrowInStartForeground(ForegroundServiceStartNotAllowedException("Synthetic denial"))
        assertEquals("blocked", execute(instruction("blocked", play = true)))
        assertEquals("blocked", PlaybackService.observeLibrary(owner).error)
        assertFalse(PlaybackService.observeLibrary(owner).isPlaying)
        assertEquals(0L, SongCachePlaybackMedia.starts.get())
        assertEquals(before, requests.size)
        assertStopped()
    }

    @Test fun deniedFocusReleasesPreviouslyPromotedForegroundAndNeverClaimsAudioStarted() {
        assertEquals("ok", prepare())
        shadowOf(service!!.get().getSystemService(AudioManager::class.java)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        assertEquals("blocked", execute(instruction("focus-denied", play = true)))
        assertEquals("blocked", PlaybackService.observeLibrary(owner).error)
        assertEquals(0L, SongCachePlaybackMedia.starts.get())
        assertStopped()
    }

    @Test fun revokedAuthorityCannotReplaceEvenAPausedTrackOrBorrowTheBoundService() {
        assertEquals("ok", prepare())
        val media = player.currentMediaItem
        val requestsBefore = requests.size
        assertEquals("rejected", execute(instruction("revoked", track = track("track-b", 8)), authority = permit { false }))
        assertSame(media, player.currentMediaItem)
        assertEquals(requestsBefore, requests.size)
        assertStopped()
    }

    @Test fun reauthenticationWithEqualAccountValuesRejectsOldBoundSessionUntilFreshTargetedPreparation() {
        assertEquals("ok", prepare())
        val original = requireNotNull(client.currentSession())
        val identity = client.validatedSessionIdentity()
        val oldPermit = permit { client.validatedSessionIdentity() == identity }
        login()
        assertEquals(original, client.currentSession())
        assertNotSame(original, client.currentSession())
        assertEquals("rejected", execute(instruction("stale-session", track = track("track-b", 8))))
        assertEquals("rejected", execute(instruction("stale-permit"), authority = oldPermit))
        if (!PlaybackService.isActive()) { service?.destroy(); service = null; createService() }
        assertEquals("ok", prepare()) // fresh current session binding, still silent
        assertStopped()
        assertOnlyAllowedTraffic()
    }

    @Test fun changingAccountCannotLendNewCredentialsToAnOldServiceExecution() {
        assertEquals("ok", prepare())
        account = "successor-account"
        login()
        assertEquals("successor-account", client.currentSession()!!.accountId)
        assertEquals("rejected", execute(instruction("wrong-account", track = track("track-b", 8))))
        assertFalse(requests.any { it.path?.contains("track-b") == true })
        assertFalse(PlaybackService.observeLibrary(owner).isPlaying)
        assertOnlyAllowedTraffic()
    }

    @Test fun sharedAudioUnauthorizedFencesAuthenticatedOutputInsteadOfFallingBackToSavedOrOtherCredentials() {
        assertEquals("ok", prepare())
        audioStatus = 401
        val outcome = prepare(track("track-b", 8))
        assertTrue(outcome in setOf("media", "rejected"))
        await("shared authentication revocation") { client.currentSession() == null }
        assertFalse(PlaybackService.observeLibrary(owner).isPlaying)
        assertOnlyAllowedTraffic()
    }

    @Test fun pendingNativeExecutionCannotStartAfterSynchronousSuspensionAndLateAudioResponse() {
        assertEquals("ok", prepare())
        val held = CountDownLatch(1).also(gates::add)
        audioGate = held
        val entered = CountDownLatch(1)
        heldAudioEntered = entered
        heldTrack = "track-b"
        val pending = launchOperation { PlaybackService.executeLibrary(instruction("pending", track = track("track-b", 8), play = true),
            permit(), owner) { id, ended -> observe(id, ended) } }
        try {
            await("actual pending authenticated audio response") { entered.count == 0L && !pending.isCompleted }
            PlaybackService.suspendLibrary(owner)
            assertStopped()
            val starts = SongCachePlaybackMedia.starts.get()
            held.countDown(); audioGate = null
            await("cancelled native outcome") { pending.isCompleted }
            assertEquals("rejected", runBlocking { pending.await() })
            pump(100)
            assertEquals(starts, SongCachePlaybackMedia.starts.get())
            assertEquals(0L, SongCachePlaybackMedia.bytes(0x2222))
            assertTrue(observations.none { it.first == "pending" && it.second.isPlaying })
            assertStopped()
        } finally { held.countDown(); audioGate = null }
    }

    @Test fun sourceReleaseStopsActualOutputAndRetainsPreparedMediaBeforeReturningProof() {
        play()
        val media = player.currentMediaItem
        assertTrue(PlaybackService.releaseLibrary(owner))
        assertStopped()
        assertSame(media, player.currentMediaItem)
        val starts = SongCachePlaybackMedia.starts.get()
        pump(150)
        assertEquals(starts, SongCachePlaybackMedia.starts.get())
        assertFalse(PlaybackService.observeLibrary(owner).isPlaying)
    }

    @Test fun obsoleteEndpointCleanupAndDelayedOperationCannotStopOrSampleItsSuccessor() {
        assertEquals("ok", prepare())
        val held = CountDownLatch(1).also(gates::add)
        audioGate = held
        val entered = CountDownLatch(1)
        heldAudioEntered = entered
        heldTrack = "track-b"
        val pending = launchOperation { PlaybackService.executeLibrary(instruction("old-pending", track = track("track-b", 8), play = true),
            permit(), owner) { id, ended -> observe(id, ended) } }
        val successor = Any()
        try {
            await("old native load pending") { entered.count == 0L && !pending.isCompleted }
            audioGate = null // old dispatcher retains its captured latch; new track may load
            assertEquals("ok", prepare(owner = successor))
            assertEquals("ok", execute(instruction("successor", play = true), successor))
            await("successor actual PCM") { player.isPlaying && SongCachePlaybackMedia.bytes(0x1111) > 0 }
            held.countDown()
            await("old operation exit") { pending.isCompleted }
            assertEquals("rejected", runBlocking { pending.await() })
            PlaybackService.suspendLibrary(owner)
            assertFalse(PlaybackService.releaseLibrary(owner))
            assertEquals(LibraryPlaybackObservation(), PlaybackService.observeLibrary(owner))
            assertEquals("rejected", execute(instruction("old-late"), owner))
            assertTrue(player.isPlaying)
            assertTrue(PlaybackService.observeLibrary(successor).isPlaying)
            assertEquals("track-a", player.currentMediaItem?.mediaId)
            assertTrue(foreground.foregroundConfirmed)
            assertTrue(focus.isGranted)
            assertEquals(0L, SongCachePlaybackMedia.bytes(0x2222))
        } finally { held.countDown(); audioGate = null }
    }

    @Test fun oldEndpointCannotStopReplacementAuthenticatedServiceInstance() {
        assertEquals("ok", prepare())
        service!!.destroy(); service = null
        createService()
        val successor = Any()
        play(successor)
        PlaybackService.suspendLibrary(owner)
        assertFalse(PlaybackService.releaseLibrary(owner))
        assertTrue(PlaybackService.observeLibrary(successor).isPlaying)
        assertEquals("track-a", player.currentMediaItem?.mediaId)
    }

    @Test fun actualFocusLossStopsNativeOutputAndLateGainNeverResumes() {
        play()
        val manager = service!!.get().getSystemService(AudioManager::class.java)
        val listener = requireNotNull(requireNotNull(shadowOf(manager).lastAudioFocusRequest).listener)
        val starts = SongCachePlaybackMedia.starts.get()
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertStopped()
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        pump(150)
        assertEquals(starts, SongCachePlaybackMedia.starts.get())
        assertStopped()
    }

    @Test fun actualNoisyBroadcastCancelsOutputAndNeverLoadsOrStartsAnImplicitSuccessor() {
        play()
        val starts = SongCachePlaybackMedia.starts.get()
        service!!.get().sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY).setPackage(app.packageName))
        await("noisy output cancellation") { !player.playWhenReady && !player.isPlaying }
        assertStopped()
        pump(150)
        assertEquals(starts, SongCachePlaybackMedia.starts.get())
        assertEquals(1, player.mediaItemCount)
        assertOnlyAllowedTraffic()
    }

    @Test fun canonicalNextFiveActuallyPopulateCacheWithoutBecomingPlayerUrlsOrUnboundedTraversal() {
        val queue = listOf(track()) + (1..7).map { track("next-$it", 7L + it) }
        publishQueue(queue)
        assertEquals("ok", prepare())
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        assertEquals("ok", execute(instruction("cache-active", play = true), authority = permit { System.nanoTime() < until }))
        await("all five bounded next tracks cached") { (1..5).all { isCached("next-$it") } }
        (1..5).forEach { assertArrayEquals(wav(0x1111), cached("next-$it")) }
        assertFalse(requests.any { it.path?.contains("next-6") == true || it.path?.contains("next-7") == true })
        assertEquals(1, player.mediaItemCount)
        assertEquals("track-a", player.currentMediaItem?.mediaId)
        @Suppress("UNCHECKED_CAST")
        val allowed = field("allowedAudioUrls") as Set<String>
        assertEquals(setOf(client.audioUrl(LibraryItem("track", "track-a", "", hasAudio = true))), allowed.toSet())

        // Even a completed speculative cache entry cannot be opened as audible media.
        val before = requests.size
        val source = requireNotNull(SongCachePlaybackMedia.productionDataSources).createDataSource()
        val probe = FutureTask<Throwable?>(java.util.concurrent.Callable {
            try {
                source.open(androidx.media3.datasource.DataSpec.Builder()
                    .setUri(client.audioUrl(LibraryItem("track", "next-1", "", hasAudio = true))).setKey("next-1").build())
                null
            } catch (failure: Throwable) { failure }
            finally { source.close() }
        })
        val worker = Thread(probe, "fixture-speculative-audible-denial")
        worker.start()
        try { assertTrue(probe.get(5, TimeUnit.SECONDS) is IOException) }
        finally { worker.join(5_000); assertFalse("Own cache probe stopped", worker.isAlive) }
        assertEquals(before, requests.size)

        assertTrue(PlaybackService.releaseLibrary(owner))
        publishQueue(queue + track("parked-extra", 30))
        pump(150)
        assertFalse(requests.any { it.path?.contains("parked-extra") == true })
        assertStopped() // release alone supplies no retained parked cache lineage
    }

    @Test fun queueChangeCancelsHeldSpeculativeReadAndCannotPublishItsStaleCacheEntry() {
        publishQueue(listOf(track(), track("next-stale", 8)))
        assertEquals("ok", prepare())
        val entered = CountDownLatch(1)
        val held = CountDownLatch(1).also(gates::add)
        heldTrack = "next-stale"; heldAudioEntered = entered; audioGate = held
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        try {
            assertEquals("ok", execute(instruction("cache-stale", play = true), authority = permit { System.nanoTime() < until }))
            await("actual speculative HTTP request held") { entered.count == 0L }
            publishQueue(listOf(track()))
            held.countDown(); audioGate = null
            pump(150)
            assertNull(cached("next-stale"))
            assertTrue(player.isPlaying)
            assertEquals(1, player.mediaItemCount)
            assertOnlyAllowedTraffic()
        } finally { held.countDown(); audioGate = null }
    }

    @Test fun directEndpointBridgeUsesTheExistingSoleServiceRatherThanCreatingAnotherPlayer() {
        val endpoint = Media3PlaybackEndpoint(app)
        val actual = player
        assertEquals("ok", result { endpoint.prepare(instruction("bridge-prepare", "prepare")) { true } })
        assertSame(actual, player)
        assertEquals(1L, SongCachePlaybackMedia.factories.get())
        assertEquals("ok", result { endpoint.execute(instruction("bridge-paused", elapsed = 4.25), permit()) })
        assertEquals(player.currentPosition / 1000.0, endpoint.observe().elapsed, 0.0)
        assertTrue(endpoint.release())
        assertStopped()
    }
}
