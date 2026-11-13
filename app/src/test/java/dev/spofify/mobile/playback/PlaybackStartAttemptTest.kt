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
import dev.spofify.mobile.core.ServerAddress
import dev.spofify.mobile.core.SessionStore
import dev.spofify.mobile.core.StoredSession
import dev.spofify.mobile.core.LibraryPlaybackInstruction
import dev.spofify.mobile.core.LibraryPlaybackTrack
import dev.spofify.mobile.feature.devices.LibraryPlaybackAuthority
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

/** Actual server-instruction service start coroutine, real auth/focus/foreground, controlled output timing.
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
    private val endpoint = Media3PlaybackEndpoint(ApplicationProvider.getApplicationContext())

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
        PlaybackService.onServiceActiveChanged = null // No playback transport is launched by this fixture.
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


    private suspend fun createFixture(): Fixture =
        Fixture(Robolectric.buildService(PlaybackService::class.java).create()).also {
            fixtures += it
            assertEquals("ok", endpoint.prepare(instruction("fixture-bind", playing = false, track = false).copy(type = "prepare")) { true })
            it.install()
        }

    private fun instruction(id: String = "execute", playing: Boolean = true, track: Boolean = true) =
        LibraryPlaybackInstruction(id, "execute", 23, "event", 17.25, playing,
            track = if (track) LibraryPlaybackTrack("same-track", "First occurrence", emptyList(),
                "Album", "album", 120.0, 7, 0, "now") else null)

    private suspend fun execute(value: LibraryPlaybackInstruction = instruction(),
        current: () -> Boolean = { true }): String =
        endpoint.execute(value, LibraryPlaybackAuthority(current))

    private fun assertSilenced(target: Fixture = fixture) {
        assertTrue("Cancel pending native work before returning its receipt", target.output.pauses > 0)
        assertTrue("Drain buffered output, not merely a presentation flag", target.output.stops > 0)
        assertFalse(target.output.player.isPlaying)
        assertFalse(target.output.player.playWhenReady)
        assertFalse(target.focus.isGranted)
        assertFalse(target.foreground.foregroundConfirmed)
        assertTrue(shadowOf(target.service).isForegroundStopped)
        assertEquals("Neither history POST nor catalog preparation HTTP is permitted", 1, web.requestCount)
    }

    @Test fun prepareIsSilentAndCannotAcquireFocusForegroundOrPlayback() = runTest {
        fixture.output.readyOnPrepare = true
        assertEquals("ok", endpoint.prepare(instruction().copy(type = "prepare")) { true })
        assertEquals(1, fixture.output.prepares)
        assertEquals(0, fixture.output.plays)
        assertFalse(fixture.focus.isGranted)
        assertFalse(fixture.foreground.foregroundConfirmed)
        assertFalse(fixture.output.wantsPlay)
        assertEquals(1, web.requestCount)
    }

    @Test fun retiredOriginBeforePreparationCannotInstallOrReplaceMedia() = runTest {
        assertEquals("rejected", endpoint.prepare(instruction().copy(type = "prepare")) { false })
        assertEquals(0, fixture.real.mediaItemCount)
        assertEquals(0, fixture.output.prepares)
        assertEquals(0, fixture.output.plays)
        assertEquals(1, web.requestCount)
    }

    @Test fun revokedAuthorityCannotInstallOrExecuteEvenStoppedMedia() = runTest {
        assertEquals("rejected", execute(instruction(playing = false)) { false })
        assertEquals(0, fixture.real.mediaItemCount)
        assertEquals(0, fixture.output.prepares)
        assertEquals(0, fixture.output.plays)
    }

    @Test fun callerBecomesFalseWhileAwaitingReadyDrainsOutputFocusAndForeground() = runTest {
        var current = true
        val pending = async(start = CoroutineStart.UNDISPATCHED) { execute(current = { current }) }
        assertEquals(1, fixture.output.prepares)
        assertTrue(fixture.focus.isGranted)
        assertTrue(fixture.foreground.foregroundConfirmed)
        current = false
        advanceTimeBy(20); runCurrent()
        assertEquals("rejected", pending.await())
        assertEquals(0, fixture.output.plays)
        assertSilenced()
    }

    @Test fun callerBecomesFalseAfterRawPlayCannotReturnAPausedLie() = runTest {
        fixture.output.readyOnPrepare = true
        var current = true
        val pending = async(start = CoroutineStart.UNDISPATCHED) { execute(current = { current }) }
        assertEquals(1, fixture.output.plays)
        fixture.output.rawPlaying = true
        current = false
        advanceTimeBy(10); runCurrent()
        assertEquals("rejected", pending.await())
        assertSilenced()
    }

    private fun successor(afterRawPlay: Boolean, cancelOld: Boolean, empty: Boolean = false) = runTest {
        fixture.output.readyOnPrepare = afterRawPlay
        val old = async(start = CoroutineStart.UNDISPATCHED) { execute() }
        if (afterRawPlay) assertEquals(1, fixture.output.plays)
        else assertEquals(0, fixture.output.plays)
        val newer = instruction("successor", playing = false, track = !empty)
        assertEquals("ok", execute(newer))
        val pauses = fixture.output.pauses
        val stops = fixture.output.stops
        if (cancelOld) old.cancelAndJoin() else {
            advanceTimeBy(20); runCurrent()
            assertEquals("rejected", old.await())
        }
        assertEquals("Obsolete completion must not clean its successor", pauses, fixture.output.pauses)
        assertEquals(stops, fixture.output.stops)
        assertEquals(if (empty) 0 else 1, fixture.real.mediaItemCount)
        assertFalse(fixture.output.wantsPlay)
        assertFalse(fixture.focus.isGranted)
    }

    @Test fun replacementBeforeReadinessCancelsOnlyPredecessor() = successor(false, false)
    @Test fun replacementAfterRawPlayCancelsOnlyPredecessor() = successor(true, false)
    @Test fun cancelledOldStartCannotCleanSameTrackSuccessor() = successor(false, true)
    @Test fun cancelledOldPlayCannotCleanPausedSuccessor() = successor(true, true)
    @Test fun tracklessStoppedSuccessorOwnsDemotion() = successor(true, true, empty = true)

    @Test fun oldCompletionCannotSilencePlayingSuccessorUsingSameAuthorityObject() = runTest {
        val permit = LibraryPlaybackAuthority { true }
        val old = async(start = CoroutineStart.UNDISPATCHED) { endpoint.execute(instruction(), permit) }
        fixture.output.readyOnPrepare = true
        fixture.output.playingOnPlay = true
        assertEquals("ok", endpoint.execute(instruction("successor"), permit))
        val pauses = fixture.output.pauses
        val stops = fixture.output.stops
        advanceTimeBy(20); runCurrent()
        assertEquals("rejected", old.await())
        assertEquals(pauses, fixture.output.pauses)
        assertEquals(stops, fixture.output.stops)
        assertTrue(fixture.output.rawPlaying)
        assertTrue(fixture.focus.isGranted)
    }

    @Test fun oldCompletionCannotBorrowSuccessorServiceIdentity() = runTest {
        val previous = fixture
        val old = async(start = CoroutineStart.UNDISPATCHED) { execute() }
        previous.close()
        fixture = createFixture()
        fixture.output.readyOnPrepare = true
        fixture.output.playingOnPlay = true
        assertEquals("ok", execute(instruction("successor-service")))
        advanceTimeBy(20); runCurrent()
        assertEquals("rejected", old.await())
        assertTrue(fixture.output.rawPlaying)
        assertTrue(fixture.focus.isGranted)
    }

    private fun cancellation(afterRawPlay: Boolean) = runTest {
        fixture.output.readyOnPrepare = afterRawPlay
        val pending = async(start = CoroutineStart.UNDISPATCHED) { execute() }
        if (afterRawPlay) fixture.output.rawPlaying = true
        pending.cancelAndJoin()
        assertSilenced()
    }
    @Test fun cancellationWhileAwaitingReadyDrainsOutputFocusAndForeground() = cancellation(false)
    @Test fun cancellationAfterRawPlayDrainsActualOutput() = cancellation(true)

    @Test fun deniedFocusStopsOutputWithoutPreparing() = runTest {
        fixture.audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        assertEquals("blocked", execute())
        assertEquals(0, fixture.output.prepares)
        assertEquals(0, fixture.output.plays)
        assertSilenced()
    }

    @Test fun deniedForegroundStopsWithoutAcquiringFocusOrPreparing() = runTest {
        shadowOf(fixture.service).setThrowInStartForeground(
            ForegroundServiceStartNotAllowedException("Synthetic denial"))
        assertEquals("blocked", execute())
        assertEquals(0, fixture.output.prepares)
        assertEquals(0, fixture.output.plays)
        assertSilenced()
    }

    private fun mediaError(afterRawPlay: Boolean) = runTest {
        fixture.output.readyOnPrepare = afterRawPlay
        val pending = async(start = CoroutineStart.UNDISPATCHED) { execute() }
        fixture.output.error = ExoPlaybackException.createForSource(
            java.io.IOException("Synthetic failure"), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        advanceTimeBy(20); runCurrent()
        assertEquals("media", pending.await())
        assertSilenced()
    }
    @Test fun mediaErrorDuringReadinessCleansOutputFocusAndForeground() = mediaError(false)
    @Test fun mediaErrorAfterRawPlayCleansOutputFocusAndForeground() = mediaError(true)

    @Test fun readinessTimeoutDoesNotKeepFocusOrForeground() = runTest {
        val pending = async(start = CoroutineStart.UNDISPATCHED) { execute() }
        advanceTimeBy(3_000); runCurrent()
        assertEquals("media", pending.await())
        assertEquals(0, fixture.output.plays)
        assertSilenced()
    }

    @Test fun rawPlayTimeoutDoesNotLeavePreparedOutputOrFocus() = runTest {
        fixture.output.readyOnPrepare = true
        val pending = async(start = CoroutineStart.UNDISPATCHED) { execute() }
        advanceTimeBy(500); runCurrent()
        assertEquals("blocked", pending.await())
        assertEquals(1, fixture.output.plays)
        assertSilenced()
    }

    @Test fun thrownPrepareFailureStillCleansOwnedOutputFocusAndForeground() = runTest {
        fixture.output.prepareFailure = IllegalStateException("Synthetic prepare failure")
        assertEquals("media", execute())
        assertSilenced()
    }

    @Test fun thrownPlayFailureStillReleasesOwnedResources() = runTest {
        fixture.output.readyOnPrepare = true
        fixture.output.playFailure = IllegalStateException("Synthetic play failure")
        assertEquals("media", execute())
        assertSilenced()
    }

    @Test fun focusLossCancelsPendingStartAndGainNeverRestarts() = runTest {
        val pending = async(start = CoroutineStart.UNDISPATCHED) { execute() }
        val request = requireNotNull(fixture.audio.lastAudioFocusRequest?.audioFocusRequest)
        val listener = android.media.AudioFocusRequest::class.java.getDeclaredMethod("getOnAudioFocusChangeListener")
            .apply { isAccessible = true }.invoke(request) as AudioManager.OnAudioFocusChangeListener
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        shadowOf(Looper.getMainLooper()).idle()
        advanceTimeBy(20); runCurrent()
        assertEquals("rejected", pending.await())
        assertSilenced()
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        shadowOf(Looper.getMainLooper()).idle()
        advanceTimeBy(3_500); runCurrent()
        assertEquals(0, fixture.output.plays)
        assertFalse(fixture.output.wantsPlay)
    }

    @Test fun releaseCancelsPendingStartBeforeReturningStoppedProof() = runTest {
        fixture.output.readyOnPrepare = true
        val pending = async(start = CoroutineStart.UNDISPATCHED) { execute() }
        fixture.output.rawPlaying = true
        assertTrue(endpoint.release())
        assertSilenced()
        advanceTimeBy(10); runCurrent()
        assertEquals("rejected", pending.await())
    }

    @Test fun keepAlivePlatformRejectionIsBlockedAndReleasesFocusAndForeground() = runTest {
        val context = fixture.service.baseContext
        val wrapper = object : ContextWrapper(context) {
            override fun startService(service: Intent): ComponentName? {
                throw ForegroundServiceStartNotAllowedException("Synthetic keep-alive denial")
            }
        }
        val base = android.content.ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }
        base.set(fixture.service, wrapper)
        try {
            assertEquals("blocked", execute())
            assertEquals(0, fixture.output.prepares)
            assertSilenced()
        } finally { base.set(fixture.service, context) }
    }

    private fun cleanupFailure(pause: Boolean) = runTest {
        // A revoked active operation must drain even if one native cleanup method throws.
        fixture.output.readyOnPrepare = true
        var current = true
        val pending = async(start = CoroutineStart.UNDISPATCHED) { execute(current = { current }) }
        fixture.output.rawPlaying = true
        if (pause) fixture.output.pauseFailure = IllegalStateException("Synthetic pause failure")
        else fixture.output.stopFailure = IllegalStateException("Synthetic stop failure")
        current = false
        advanceTimeBy(10); runCurrent()
        assertEquals("rejected", pending.await())
        assertSilenced()
    }
    @Test fun throwingPauseCleanupStillStopsOutputAndReleasesFocusAndForeground() = cleanupFailure(true)
    @Test fun throwingStopCleanupStillPausesOutputAndReleasesFocusAndForeground() = cleanupFailure(false)

    @Test fun observedPositionDurationAndPlayingComeFromMediaNotInstructionIntent() = runTest {
        assertEquals("ok", execute(instruction(playing = false)))
        val observed = endpoint.observe()
        assertFalse(observed.isPlaying)
        assertEquals(fixture.output.player.currentPosition / 1000.0, observed.elapsed, 0.0)
        assertEquals(1, fixture.output.player.mediaItemCount)
        assertEquals(0, fixture.output.plays)
        assertEquals(1, web.requestCount)
    }
}
