package dev.spofify.mobile.playback

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.test.core.app.ApplicationProvider
import dev.spofify.mobile.AppGraph
import dev.spofify.mobile.LoginStore
import dev.spofify.mobile.RememberedLogin
import dev.spofify.mobile.SpofifyApplication
import dev.spofify.mobile.core.PlaybackOccurrence
import dev.spofify.mobile.core.PlaybackSnapshot
import dev.spofify.mobile.core.PlaybackTrack
import dev.spofify.mobile.core.ServerAddress
import dev.spofify.mobile.core.SessionStore
import dev.spofify.mobile.core.StoredSession
import dev.spofify.mobile.feature.devices.PlaybackAuthority
import dev.spofify.mobile.feature.devices.PlaybackEndpointResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Actual private service start coroutine, real auth/focus/foreground, controlled output timing.
 * Synthetic JVM regressions only; these do not establish native or acoustic playback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SpofifyApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackStartAttemptTest {
    private lateinit var web: MockWebServer
    private lateinit var graph: AppGraph
    private lateinit var fixture: Fixture
    private val fixtures = mutableListOf<Fixture>()
    private val endpoint = Media3PlaybackEndpoint()

    @Before fun setUp() = runBlocking {
        System.getenv("SPOFIFY_TEST_TMPDIR")?.let { expected ->
            assertEquals("Check the forked test JVM's temp directory", expected, System.getProperty("java.io.tmpdir"))
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
        val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
        PlaybackService.onServiceActiveChanged = null // Never register/poll a real player backend.
        client.login("listener", "synthetic-password")
        assertEquals("/api/auth/login", web.takeRequest(1, TimeUnit.SECONDS)?.path)
        fixture = createFixture()
    }

    @After fun tearDown() {
        PlaybackService.onServiceActiveChanged = null
        try { fixtures.asReversed().forEach { it.close() } }
        finally {
            try { if (::graph.isInitialized) graph.clear() }
            finally { if (::web.isInitialized) web.shutdown() }
        }
    }

    private fun prepared() = PlaybackSnapshot(
        queue = listOf(PlaybackTrack("same-track", "First occurrence", emptyList(), "Album", "album", 120.0, key = 7, order = 0),
            PlaybackTrack("same-track", "Second occurrence", emptyList(), "Album", "album", 120.0, key = 91, order = 1)),
        position = 0, elapsed = 17.25, duration = 120.0,
        occurrence = PlaybackOccurrence(7, "same-track", "retained-history", 3.25, false),
    ).validated()

    private suspend fun createFixture(): Fixture {
        val next = Fixture(Robolectric.buildService(PlaybackService::class.java).create())
        fixtures += next
        assertEquals("ok", endpoint.activate(prepared(), PlaybackAuthority(23, 4) { true }).status)
        next.install()
        return next
    }

    private class Fixture(val controller: ServiceController<PlaybackService>) {
        val service: PlaybackService = controller.get()
        val real: ExoPlayer = field(service, "player") as ExoPlayer
        val output = ControlledOutput(real)
        val focus get() = field(service, "audioFocus") as PlaybackAudioFocus
        val foreground get() = field(service, "foreground") as PlaybackForegroundNotification
        val audio get() = shadowOf(service.getSystemService(AudioManager::class.java))
        private var closed = false

        fun install() {
            audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            setField(service, "player", output.player)
        }

        fun permit(value: PlaybackAuthority) { setField(service, "explicitBackgroundPermit", value) }
        fun token(): Any = requireNotNull(field(service, "startAttempt"))

        fun close() {
            if (closed) return
            closed = true
            // MediaSession still wraps the real output. Restore it before production teardown;
            // the proxy must neither leak nor release the real player a second time.
            setField(service, "player", real)
            try { output.player.release() } finally { controller.destroy() }
        }
    }

    /** Only timing/output commands are synthetic. All queue, timeline, metadata and position
     * getters/mutations delegate to the real service-created ExoPlayer; prepare never opens HTTP.
     */
    private class ControlledOutput(private val real: ExoPlayer) {
        var readyOnPrepare = false
        var playingOnPlay = false
        var rawPlaying = false
        var wantsPlay = false
        var state = Player.STATE_IDLE
        var prepares = 0
        var plays = 0
        var pauses = 0
        var stops = 0
        var released = false
        var error: ExoPlaybackException? = null
        var prepareFailure: RuntimeException? = null
        var playFailure: RuntimeException? = null
        var pauseFailure: RuntimeException? = null
        var stopFailure: RuntimeException? = null
        val player: ExoPlayer = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "Controlled service output"
                "prepare" -> {
                    prepares++
                    prepareFailure?.let { throw it }
                    state = if (readyOnPrepare) Player.STATE_READY else Player.STATE_BUFFERING
                    Unit
                }
                "play" -> { plays++; playFailure?.let { throw it }; wantsPlay = true; rawPlaying = playingOnPlay; Unit }
                "pause" -> { pauses++; pauseFailure?.let { throw it }; wantsPlay = false; rawPlaying = false; Unit }
                "stop" -> { stops++; stopFailure?.let { throw it }; wantsPlay = false; rawPlaying = false; state = Player.STATE_IDLE; Unit }
                "setPlayWhenReady" -> { wantsPlay = args!![0] as Boolean; if (!wantsPlay) rawPlaying = false; Unit }
                "getPlayWhenReady" -> wantsPlay
                "isPlaying" -> rawPlaying
                "getPlaybackState" -> state
                "getPlayerError" -> error
                "release" -> { released = true; wantsPlay = false; rawPlaying = false; Unit }
                else -> try { method.invoke(real, *(args ?: emptyArray())) }
                    catch (failure: InvocationTargetException) { throw failure.targetException }
            }
        } as ExoPlayer
    }

    private companion object {
        fun field(service: PlaybackService, name: String): Any? = PlaybackService::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(service)
        fun setField(service: PlaybackService, name: String, value: Any?) {
            PlaybackService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
        }
    }

    /** Pass the caller's continuation through the JVM suspend ABI, retaining runTest's scheduler.
     * No extracted replica of startOwned and no runBlocking around its readiness loops.
     */
    private suspend fun start(target: Fixture, permit: PlaybackAuthority, guard: () -> Boolean): PlaybackEndpointResult =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            val method = PlaybackService::class.java.declaredMethods.single { it.name == "startOwned" }
            method.isAccessible = true
            try { method.invoke(target.service, permit, true, guard, continuation) }
            catch (failure: InvocationTargetException) { throw failure.targetException }
        }

    private suspend fun importGuarded(snapshot: PlaybackSnapshot, permit: PlaybackAuthority,
        guard: () -> Boolean): PlaybackEndpointResult = suspendCoroutineUninterceptedOrReturn { continuation ->
        val method = PlaybackService::class.java.declaredMethods.single { it.name == "importSnapshot" }
        method.isAccessible = true
        try { method.invoke(fixture.service, snapshot, permit, guard, continuation) }
        catch (failure: InvocationTargetException) { throw failure.targetException }
    }

    private fun assertPendingOwnership(target: Fixture) {
        assertEquals(1, target.output.prepares)
        assertTrue(target.focus.isGranted)
        assertTrue(target.foreground.foregroundConfirmed)
        assertFalse(shadowOf(target.service).isForegroundStopped)
        assertNotNull(target.token())
    }

    private fun assertSilenced(target: Fixture, result: PlaybackEndpointResult, before: PlaybackSnapshot,
        status: String = "rejected") {
        assertEquals(status, result.status)
        assertTrue("Rejected caller must pause its actual output", target.output.pauses > 0)
        assertTrue("Rejected caller must discard its buffered output", target.output.stops > 0)
        assertFalse("A paused snapshot alone must not hide raw playing output", target.output.player.isPlaying)
        assertFalse(target.output.player.playWhenReady)
        assertFalse(result.snapshot.playing)
        assertEquals("Rejected snapshot must agree with raw output", target.output.player.isPlaying, result.snapshot.playing)
        assertFalse(target.focus.isGranted)
        assertNull(field(target.service, "startAttempt"))
        assertFalse(target.foreground.foregroundConfirmed)
        assertTrue(shadowOf(target.service).isForegroundStopped)
        assertFalse(shadowOf(target.service).isLastForegroundNotificationAttached)
        val notification = shadowOf(target.service.getSystemService(NotificationManager::class.java)).allNotifications.single()
        assertEquals("Resume", notification.actions[1].title)
        assertEquals(0, notification.flags and Notification.FLAG_ONGOING_EVENT)
        assertEquals(before.queue, result.snapshot.queue)
        assertEquals(before.position, result.snapshot.position)
        assertEquals(before.elapsed, result.snapshot.elapsed, 0.0)
        assertEquals(before.occurrence, result.snapshot.occurrence)
        assertEquals("Only synthetic login may reach HTTP", 1, web.requestCount)
    }

    @Test fun callerBecomesFalseWhileAwaitingReadyDrainsOwnedOutputFocusAndForeground() = runTest {
        val permit = PlaybackAuthority(23, 4) { true }
        fixture.permit(permit)
        var current = true
        val before = endpoint.exportSnapshot()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { start(fixture, permit) { current } }
        assertFalse("Hold the actual start coroutine in its READY loop", pending.isCompleted)
        assertPendingOwnership(fixture)
        assertEquals(0, fixture.output.plays)
        val focusRequest = requireNotNull(fixture.audio.lastAudioFocusRequest).audioFocusRequest
        current = false // Change ONLY the caller predicate, not global authority/epoch/focus.
        assertFalse(pending.isCompleted)
        assertTrue(fixture.focus.isGranted)
        advanceTimeBy(20); runCurrent()
        assertTrue(pending.isCompleted)
        assertSilenced(fixture, pending.await(), before)
        assertSame(focusRequest, fixture.audio.lastAbandonedAudioFocusRequest)
    }

    @Test fun callerBecomesFalseAfterRawPlayBeginsCannotReturnAPausedLie() = runTest {
        fixture.output.readyOnPrepare = true
        val permit = PlaybackAuthority(23, 4) { true }
        fixture.permit(permit)
        var current = true
        val before = endpoint.exportSnapshot()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { start(fixture, permit) { current } }
        assertFalse("Hold the actual start coroutine in its isPlaying loop", pending.isCompleted)
        assertPendingOwnership(fixture)
        assertEquals(1, fixture.output.plays)
        assertTrue(fixture.output.player.playWhenReady)
        val focusRequest = requireNotNull(fixture.audio.lastAudioFocusRequest).audioFocusRequest
        fixture.output.rawPlaying = true
        current = false // Raw isPlaying changes before the suspended coroutine observes disposal.
        assertTrue("Regression precondition: raw output really reports playing", fixture.output.player.isPlaying)
        assertFalse(pending.isCompleted)
        advanceTimeBy(10); runCurrent()
        assertTrue(pending.isCompleted)
        assertSilenced(fixture, pending.await(), before)
        assertSame(focusRequest, fixture.audio.lastAbandonedAudioFocusRequest)
    }

    @Test fun oldCompletionCannotSilenceSuccessorStartEvenWithSamePermitAndOccurrence() = runTest {
        val permit = PlaybackAuthority(23, 4) { true }
        fixture.permit(permit)
        var current = true
        val pending = async(start = CoroutineStart.UNDISPATCHED) { start(fixture, permit) { current } }
        assertFalse(pending.isCompleted)
        val oldToken = fixture.token()
        current = false
        fixture.output.readyOnPrepare = true
        fixture.output.playingOnPlay = true
        val successor = start(fixture, permit) { true }
        assertEquals("ok", successor.status)
        val successorToken = fixture.token()
        assertNotSame("Same authority/current occurrence still needs a fresh attempt identity", oldToken, successorToken)
        val focusRequest = requireNotNull(fixture.audio.lastAudioFocusRequest).audioFocusRequest
        val abandoned = fixture.audio.lastAbandonedAudioFocusRequest
        val pauses = fixture.output.pauses
        val stops = fixture.output.stops
        advanceTimeBy(20); runCurrent()
        assertTrue(pending.isCompleted)
        val rejected = pending.await()
        assertEquals("rejected", rejected.status)
        assertTrue("Old result must truthfully expose the now-playing successor", rejected.snapshot.playing)
        assertSuccessor(fixture, successorToken, successor.snapshot, pauses, stops)
        assertSame(focusRequest, requireNotNull(fixture.audio.lastAudioFocusRequest).audioFocusRequest)
        assertSame(abandoned, fixture.audio.lastAbandonedAudioFocusRequest)
    }

    @Test fun oldCompletionCannotSilenceSuccessorImportReusingPermitAndCurrentOccurrence() = runTest {
        val permit = PlaybackAuthority(23, 4) { true }
        fixture.permit(permit)
        var current = true
        val pending = async(start = CoroutineStart.UNDISPATCHED) { start(fixture, permit) { current } }
        assertFalse(pending.isCompleted)
        val oldToken = fixture.token()
        current = false
        fixture.output.readyOnPrepare = true
        fixture.output.playingOnPlay = true
        val replacement = prepared().copy(queue = prepared().queue.map { it.copy(name = "Imported successor") }, playing = true)
        val successor = endpoint.activate(replacement, permit)
        assertEquals("ok", successor.status)
        val successorToken = fixture.token()
        assertNotSame(oldToken, successorToken)
        val pauses = fixture.output.pauses
        val stops = fixture.output.stops
        advanceTimeBy(20); runCurrent()
        assertTrue(pending.isCompleted)
        assertEquals("rejected", pending.await().status)
        assertSuccessor(fixture, successorToken, successor.snapshot, pauses, stops)
    }

    @Test fun oldCompletionCannotStopSuccessorServiceOutputOrBorrowItsAttemptIdentity() = runTest {
        val permit = PlaybackAuthority(23, 4) { true }
        fixture.permit(permit)
        var current = true
        val pending = async(start = CoroutineStart.UNDISPATCHED) { start(fixture, permit) { current } }
        assertFalse(pending.isCompleted)
        val oldToken = fixture.token()
        current = false
        fixture.close() // Restores/releases the real output through normal service cleanup.
        val successorFixture = createFixture()
        successorFixture.permit(permit)
        successorFixture.output.readyOnPrepare = true
        successorFixture.output.playingOnPlay = true
        val successor = start(successorFixture, permit) { true }
        assertEquals("ok", successor.status)
        val successorToken = successorFixture.token()
        assertNotSame(oldToken, successorToken)
        val pauses = successorFixture.output.pauses
        val stops = successorFixture.output.stops
        advanceTimeBy(20); runCurrent()
        assertTrue(pending.isCompleted)
        assertEquals("rejected", pending.await().status)
        assertSuccessor(successorFixture, successorToken, successor.snapshot, pauses, stops)
        assertTrue(fixture.output.released)
    }

    @Test fun cancellationWhileAwaitingReadyDrainsOwnedOutputFocusAndForeground() = runTest {
        val permit = PlaybackAuthority(23, 4) { true }
        fixture.permit(permit)
        val before = endpoint.exportSnapshot()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { start(fixture, permit) { true } }
        assertPendingOwnership(fixture)
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        assertSilenced(fixture, PlaybackEndpointResult("rejected", endpoint.exportSnapshot()), before)
    }

    @Test fun cancellationAfterRawPlayDrainsActualOutputNotJustSnapshot() = runTest {
        fixture.output.readyOnPrepare = true
        val permit = PlaybackAuthority(23, 4) { true }
        fixture.permit(permit)
        val before = endpoint.exportSnapshot()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { start(fixture, permit) { true } }
        assertEquals(1, fixture.output.plays)
        fixture.output.rawPlaying = true
        pending.cancelAndJoin()
        assertSilenced(fixture, PlaybackEndpointResult("rejected", endpoint.exportSnapshot()), before)
    }

    @Test fun cancelledOldStartDoesNotCleanSameValuedSuccessor() = runTest {
        val permit = PlaybackAuthority(23, 4) { true }
        fixture.permit(permit)
        val pending = async(start = CoroutineStart.UNDISPATCHED) { start(fixture, permit) { true } }
        val old = fixture.token()
        fixture.output.readyOnPrepare = true
        fixture.output.playingOnPlay = true
        val equalValuedPermit = PlaybackAuthority(23, 4) { true }
        fixture.permit(equalValuedPermit)
        val successor = start(fixture, equalValuedPermit) { true }
        val token = fixture.token()
        assertNotSame(old, token)
        val pauses = fixture.output.pauses
        val stops = fixture.output.stops
        val abandoned = fixture.audio.lastAbandonedAudioFocusRequest
        pending.cancelAndJoin()
        assertSuccessor(fixture, token, successor.snapshot, pauses, stops)
        assertSame(abandoned, fixture.audio.lastAbandonedAudioFocusRequest)
    }

    @Test fun deniedFocusStopsOwnedOutputAndDemotesForegroundWithoutPreparing() = runTest {
        val permit = PlaybackAuthority(23, 4) { true }
        fixture.permit(permit)
        fixture.audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val before = endpoint.exportSnapshot()
        val result = start(fixture, permit) { true }
        assertSilenced(fixture, result, before, "blocked")
        assertEquals("blocked", result.snapshot.error)
        assertEquals(0, fixture.output.prepares)
        assertEquals(0, fixture.output.plays)
    }

    @Test fun deniedForegroundStopsOutputWithoutAcquiringFocusOrPreparing() = runTest {
        val permit = PlaybackAuthority(23, 4) { true }
        fixture.permit(permit)
        shadowOf(fixture.service).setThrowInStartForeground(
            ForegroundServiceStartNotAllowedException("Synthetic start rejection"))
        val before = endpoint.exportSnapshot()
        val result = start(fixture, permit) { true }
        assertSilenced(fixture, result, before, "blocked")
        assertNull(fixture.audio.lastAudioFocusRequest)
        assertEquals(0, fixture.output.prepares)
    }

    @Test fun mediaErrorDuringReadinessCleansOutputFocusAndForeground() = runTest {
        val permit = PlaybackAuthority(23, 4) { true }
        fixture.permit(permit)
        val before = endpoint.exportSnapshot()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { start(fixture, permit) { true } }
        assertPendingOwnership(fixture)
        fixture.output.error = ExoPlaybackException.createForSource(
            java.io.IOException("Synthetic media failure"), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        advanceTimeBy(20); runCurrent()
        assertSilenced(fixture, pending.await(), before, "media")
    }

    @Test fun mediaErrorAfterRawPlayCleansOutputFocusAndForeground() = runTest {
        fixture.output.readyOnPrepare = true
        val permit = PlaybackAuthority(23, 4) { true }
        fixture.permit(permit)
        val before = endpoint.exportSnapshot()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { start(fixture, permit) { true } }
        assertEquals(1, fixture.output.plays)
        fixture.output.rawPlaying = true
        fixture.output.error = ExoPlaybackException.createForSource(
            java.io.IOException("Synthetic post-play media failure"), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        advanceTimeBy(10); runCurrent()
        assertSilenced(fixture, pending.await(), before, "media")
    }

    @Test fun readinessTimeoutDoesNotKeepFocusOrForeground() = runTest {
        val permit = PlaybackAuthority(23, 4) { true }
        fixture.permit(permit)
        val before = endpoint.exportSnapshot()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { start(fixture, permit) { true } }
        assertPendingOwnership(fixture)
        advanceTimeBy(3_000); runCurrent()
        assertSilenced(fixture, pending.await(), before, "media")
    }

    @Test fun rawPlayTimeoutDoesNotLeavePreparedOutputOrFocus() = runTest {
        fixture.output.readyOnPrepare = true
        val permit = PlaybackAuthority(23, 4) { true }
        fixture.permit(permit)
        val before = endpoint.exportSnapshot()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { start(fixture, permit) { true } }
        assertEquals(1, fixture.output.plays)
        advanceTimeBy(500); runCurrent()
        assertSilenced(fixture, pending.await(), before, "blocked")
    }

    @Test fun thrownPrepareFailureStillCleansOwnedOutputFocusAndForeground() = runTest {
        val permit = PlaybackAuthority(23, 4) { true }
        fixture.permit(permit)
        val before = endpoint.exportSnapshot()
        val failure = IllegalStateException("Synthetic prepare failure")
        fixture.output.prepareFailure = failure
        try {
            start(fixture, permit) { true }
            fail("The original media exception must propagate")
        } catch (actual: IllegalStateException) { assertSame(failure, actual) }
        assertSilenced(fixture, PlaybackEndpointResult("rejected", endpoint.exportSnapshot()), before)
    }

    @Test fun focusLossCancelsPendingStartAndGainNeverRestarts() = runTest {
        val permit = PlaybackAuthority(23, 4) { true }
        fixture.permit(permit)
        val pending = async(start = CoroutineStart.UNDISPATCHED) { start(fixture, permit) { true } }
        assertPendingOwnership(fixture)
        val listener = requireNotNull(requireNotNull(fixture.audio.lastAudioFocusRequest).listener)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        advanceTimeBy(20); runCurrent()
        assertEquals("rejected", pending.await().status)
        assertFalse(fixture.output.wantsPlay)
        assertFalse(fixture.focus.isGranted)
        assertFalse(fixture.foreground.foregroundConfirmed)
        val prepares = fixture.output.prepares
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(fixture.output.wantsPlay)
        assertEquals(prepares, fixture.output.prepares)
        assertEquals(0, fixture.output.plays)
    }

    @Test fun importForwardsNonDefaultGuardAndCleansItsRejectedStart() = runTest {
        val permit = PlaybackAuthority(23, 4) { true }
        fixture.permit(permit)
        var current = true
        val before = prepared()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            importGuarded(before.copy(playing = true), permit) { current }
        }
        assertPendingOwnership(fixture)
        current = false
        advanceTimeBy(20); runCurrent()
        assertSilenced(fixture, pending.await(), before)
    }

    @Test fun pausedSuccessorImportOwnsDemotionAndOldCancellationCannotAlterIt() = runTest {
        assertSilentSuccessorImport(prepared())
    }

    @Test fun emptySuccessorImportOwnsDemotionAndOldCancellationCannotAlterIt() = runTest {
        assertSilentSuccessorImport(PlaybackSnapshot())
    }

    private suspend fun assertSilentSuccessorImport(replacement: PlaybackSnapshot) {
        val permit = PlaybackAuthority(23, 4) { true }
        fixture.permit(permit)
        // UNDISPATCHED enters the real coroutine until its first readiness delay.
        kotlinx.coroutines.coroutineScope {
            val pending = async(start = CoroutineStart.UNDISPATCHED) { start(fixture, permit) { true } }
            assertPendingOwnership(fixture)
            val successor = endpoint.activate(replacement, permit)
            assertEquals("ok", successor.status)
            assertFalse(successor.snapshot.playing)
            assertFalse(fixture.output.rawPlaying)
            assertFalse(fixture.output.wantsPlay)
            assertFalse(fixture.focus.isGranted)
            assertFalse(fixture.foreground.foregroundConfirmed)
            assertTrue(shadowOf(fixture.service).isForegroundStopped)
            val pauses = fixture.output.pauses
            val stops = fixture.output.stops
            val abandoned = fixture.audio.lastAbandonedAudioFocusRequest
            pending.cancelAndJoin()
            assertEquals(pauses, fixture.output.pauses)
            assertEquals(stops, fixture.output.stops)
            assertSame(abandoned, fixture.audio.lastAbandonedAudioFocusRequest)
            assertEquals(successor.snapshot, endpoint.exportSnapshot())
        }
    }

    @Test fun keepAlivePlatformRejectionIsBlockedAndReleasesFocusAndForeground() = runTest {
        val permit = PlaybackAuthority(23, 4) { true }
        fixture.permit(permit)
        val before = endpoint.exportSnapshot()
        val original = fixture.service.baseContext
        val rejecting = object : ContextWrapper(original) {
            override fun startService(service: Intent): ComponentName? {
                throw IllegalStateException("Synthetic background keepalive rejection")
            }
        }
        val base = ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }
        base.set(fixture.service, rejecting)
        try {
            assertSilenced(fixture, start(fixture, permit) { true }, before, "blocked")
            assertEquals(0, fixture.output.prepares)
        } finally { base.set(fixture.service, original) }
    }

    @Test fun thrownPlayFailurePropagatesButStillReleasesOwnedResources() = runTest {
        fixture.output.readyOnPrepare = true
        val permit = PlaybackAuthority(23, 4) { true }
        fixture.permit(permit)
        val before = endpoint.exportSnapshot()
        val failure = IllegalStateException("Synthetic play failure")
        fixture.output.playFailure = failure
        try {
            start(fixture, permit) { true }
            fail("The original play exception must propagate")
        } catch (actual: IllegalStateException) { assertSame(failure, actual) }
        assertSilenced(fixture, PlaybackEndpointResult("rejected", endpoint.exportSnapshot()), before)
    }

    @Test fun throwingPauseCleanupStillStopsOutputAndReleasesFocusAndForeground() = runTest {
        assertCleanupFailure(pause = true)
    }

    @Test fun throwingStopCleanupStillPausesOutputAndReleasesFocusAndForeground() = runTest {
        assertCleanupFailure(pause = false)
    }

    private suspend fun assertCleanupFailure(pause: Boolean) {
        val permit = PlaybackAuthority(23, 4) { true }
        fixture.permit(permit)
        val before = endpoint.exportSnapshot()
        kotlinx.coroutines.coroutineScope {
            val pending = async(start = CoroutineStart.UNDISPATCHED) { start(fixture, permit) { true } }
            assertPendingOwnership(fixture)
            if (pause) fixture.output.pauseFailure = IllegalStateException("Synthetic cleanup pause failure")
            else fixture.output.stopFailure = IllegalStateException("Synthetic cleanup stop failure")
            pending.cancelAndJoin()
            assertSilenced(fixture, PlaybackEndpointResult("rejected", endpoint.exportSnapshot()), before)
        }
    }

    private fun assertSuccessor(target: Fixture, token: Any, snapshot: PlaybackSnapshot, pauses: Int, stops: Int) {
        assertSame("Old completion must not clear a successor's token", token, target.token())
        assertEquals("Old completion must not pause successor output", pauses, target.output.pauses)
        assertEquals("Old completion must not stop successor output", stops, target.output.stops)
        assertTrue(target.output.player.isPlaying)
        assertTrue(target.output.player.playWhenReady)
        assertTrue(target.focus.isGranted)
        assertTrue(target.foreground.foregroundConfirmed)
        assertFalse(shadowOf(target.service).isForegroundStopped)
        assertEquals(snapshot, endpoint.exportSnapshot())
        assertEquals(1, web.requestCount)
        // Do not idleFor/advanceUntilIdle: the service's recurring 100ms history tick can
        // conceal broken start cleanup. Pump only due main messages after the assertions.
        shadowOf(Looper.getMainLooper()).idle()
    }
}
