package dev.spofify.mobile.playback

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.media.session.MediaSessionManager
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.KeyEvent
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionResult
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter
import org.robolectric.shadows.ShadowMediaSessionManager
import java.net.InetAddress
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit

/** Actual service-created callback and coordinated routing, not an extracted key map.
 * Synthetic API35 JVM evidence only: no physical headset, acoustic output or Android key delivery.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SpofifyApplication::class, shadows = [PlaybackMediaButtonsTest.PlatformTrustShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackMediaButtonsTest {
    @Implements(MediaSessionManager::class)
    class PlatformTrustShadow : ShadowMediaSessionManager() {
        companion object {
            val allowed = mutableSetOf<Pair<String, Int>>()
            @JvmStatic @Resetter fun clearTrust() { allowed.clear() }
        }
        @Implementation(minSdk = 28)
        protected fun isTrustedForMediaControl(user: MediaSessionManager.RemoteUserInfo): Boolean =
            (user.packageName to user.uid) in allowed
    }

    private lateinit var web: MockWebServer
    private lateinit var graph: AppGraph
    private lateinit var client: SpofifyClient
    private var serviceController: ServiceController<PlaybackService>? = null
    private val endpoint = Media3PlaybackEndpoint()
    private val requests = mutableListOf<PlaybackDeviceCommand>()
    private val published = mutableListOf<PlaybackSnapshot>()
    private var remoteSnapshot: PlaybackSnapshot? = null
    private var remotePlaying = false
    private var remoteElapsed = 41.0
    private var remoteDuration = 120.0

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
    // Read the callback installed by the production service, not a separately constructed fake.
    private val callback: MediaSession.Callback get() {
        val impl = MediaSession::class.java.getDeclaredMethod("getImpl").apply { isAccessible = true }.invoke(session)
        return impl.javaClass.getDeclaredField("callback").apply { isAccessible = true }.get(impl) as MediaSession.Callback
    }
    private fun controller(uid: Int = Process.myUid(), trusted: Boolean = false) =
        MediaSession.ControllerInfo.createTestOnlyControllerInfo("synthetic.controller", 100, uid, 0, 0, trusted, Bundle.EMPTY, true)

    private fun prepared() = PlaybackSnapshot(
        queue = listOf(91L to 2L, 7L to 0L, 32L to 1L).map { (key, order) ->
            PlaybackTrack("duplicate-track", "Occurrence $key", emptyList(), "Album", "album", 120.0, key = key, order = order)
        }, position = 1, elapsed = 17.25, duration = 120.0, shuffle = true,
        occurrence = PlaybackOccurrence(7, "duplicate-track", "retained-history", 3.25, false),
    ).validated()

    private fun TestScope.remoteOwner(output: String = "speaker", initial: PlaybackSnapshot? = null): PlaybackCoordinator {
        graph.playbackCoordinator!!.close()
        fun view(outcome: PlaybackOutcome? = null) = PlaybackDevicesView("phone",
            listOf(PlaybackDevice("phone", "Phone", true), PlaybackDevice("speaker", "Speaker", true)),
            output, 1, 8000, state = initial ?: remoteSnapshot ?: prepared().copy(playing = remotePlaying, elapsed = remoteElapsed, duration = remoteDuration),
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
                return view(PlaybackOutcome(commandId, "ok"))
            }
            override suspend fun transfer(deviceId: String, epoch: Long, commandId: String, targetDeviceId: String, selectionSeq: Long) = view()
            override suspend fun leave(deviceId: String) = Unit
        }
        val owner = PlaybackCoordinator(protocol, endpoint, backgroundScope, clockMs = { testScheduler.currentTime },
            mediaEventUptimeMs = { SystemClock.uptimeMillis() })
        AppGraph::class.java.getDeclaredField("playbackCoordinator").apply { isAccessible = true; set(graph, owner) }
        owner.setServiceActive(true)
        runCurrent()
        return owner
    }

    private fun event(code: Int, action: Int = KeyEvent.ACTION_DOWN, repeat: Int = 0): KeyEvent {
        SystemClock.sleep(1) // A genuinely fresh event, never a manufactured future timestamp.
        val now = SystemClock.uptimeMillis()
        return KeyEvent(now, now, action, code, repeat)
    }
    private fun send(event: KeyEvent, who: MediaSession.ControllerInfo = controller()): Boolean =
        callback.onMediaButtonEvent(session, who, Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, event))
    private fun TestScope.assertRemoteCommand(code: Int, type: String, who: MediaSession.ControllerInfo = controller()) {
        val before = endpoint.exportSnapshot()
        val count = requests.size
        assertTrue(send(event(code), who)); runCurrent()
        assertEquals("Exactly one authoritative command for key $code", count + 1, requests.size)
        assertEquals(type, requests.last().type)
        assertEquals("Remote key must not change local queue/elapsed/history", before, endpoint.exportSnapshot())
        assertFalse(output.playWhenReady)
    }

    @Test fun standardKeysRouteOnceThroughSameUidAndTrustedSystemControllersWithoutLocalOutput() = runTest {
        remoteOwner()
        val mappings = listOf(KeyEvent.KEYCODE_MEDIA_PLAY to "resume", KeyEvent.KEYCODE_MEDIA_PAUSE to "pause",
            KeyEvent.KEYCODE_MEDIA_STOP to "pause", KeyEvent.KEYCODE_MEDIA_NEXT to "next",
            KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD to "next", KeyEvent.KEYCODE_MEDIA_PREVIOUS to "previous",
            KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD to "previous", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to "resume",
            KeyEvent.KEYCODE_HEADSETHOOK to "resume")
        for (who in listOf(controller(), controller(Process.myUid() + 1, true))) {
            for ((key, type) in mappings) assertRemoteCommand(key, type, who)
        }
        assertEquals("Only synthetic login may hit HTTP; keys must not refetch a catalog", 1, web.requestCount)
    }

    @Test fun frameworkLegacyCallerWithUnsetMedia3TrustFlagUsesVerifiedPlatformTrust() = runTest {
        remoteOwner()
        val name = "synthetic.system.controller"
        val uid = Process.myUid() + 42
        shadowOf(service.packageManager).installPackage(PackageInfo().apply {
            packageName = name
            applicationInfo = ApplicationInfo().apply { packageName = name; this.uid = uid }
        })
        val framework = MediaSession.ControllerInfo.createTestOnlyControllerInfo(name, 100, uid,
            MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION, 0, false, Bundle.EMPTY, true)
        assertFalse("Pinned Media3 leaves this unset for framework media buttons", framework.isTrusted)
        assertTrue(send(event(KeyEvent.KEYCODE_MEDIA_PLAY), framework)); runCurrent()
        assertTrue("An installed but unapproved caller is not trusted", requests.isEmpty())
        PlatformTrustShadow.allowed += name to uid
        assertRemoteCommand(KeyEvent.KEYCODE_MEDIA_PLAY, "resume", framework)
        val spoofedUid = MediaSession.ControllerInfo.createTestOnlyControllerInfo(name, 100, uid + 1,
            MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION, 0, false, Bundle.EMPTY, true)
        assertTrue(send(event(KeyEvent.KEYCODE_MEDIA_NEXT), spoofedUid)); runCurrent()
        assertEquals("Platform must reject an unmatched package/UID", 1, requests.size)
        assertFalse("Button compatibility must not broaden ordinary connection admission", callback.onConnect(session, framework).isAccepted)
    }

    @Test fun platformTrustedControllerDoesNotRequireAppPackageVisibility() = runTest {
        remoteOwner()
        val name = "platform.trusted.but.not.app.visible"
        val uid = Process.myUid() + 99
        // Deliberately not installed in this app's package-manager shadow. Android 11+ can
        // hide an approved listener from app queries while the platform still knows its UID.
        PlatformTrustShadow.allowed += name to uid
        val framework = MediaSession.ControllerInfo.createTestOnlyControllerInfo(name, 100, uid,
            MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION, 0, false, Bundle.EMPTY, true)
        assertRemoteCommand(KeyEvent.KEYCODE_MEDIA_PLAY, "resume", framework)
    }

    @Test fun unknownLegacyUidCannotBorrowOwnOrPrivilegedPackageIdentity() = runTest {
        remoteOwner()
        for (name in listOf(service.packageName, "android", "synthetic.notification.listener")) {
            PlatformTrustShadow.allowed += name to -1
            val unknown = MediaSession.ControllerInfo.createTestOnlyControllerInfo(name, -1, -1,
                MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION, 0, false, Bundle.EMPTY, false)
            assertTrue(send(event(KeyEvent.KEYCODE_MEDIA_PLAY), unknown)); runCurrent()
        }
        assertTrue(requests.isEmpty())
    }

    @Test fun absentLegacyIdentityNeverBecomesSystemTrust() = runTest {
        remoteOwner()
        val missing = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            MediaSession.ControllerInfo.LEGACY_CONTROLLER_PACKAGE_NAME, -1, -1,
            MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION, 0, false, Bundle.EMPTY, false)
        assertTrue(send(event(KeyEvent.KEYCODE_HEADSETHOOK), missing)); runCurrent()
        assertTrue(requests.isEmpty())
    }

    @Test fun toggleAndHeadsetHookUseAuthoritativeRemotePlayingNotIdleLocalPlayer() = runTest {
        remotePlaying = true
        remoteOwner()
        assertRemoteCommand(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "pause")
        assertRemoteCommand(KeyEvent.KEYCODE_HEADSETHOOK, "pause")
    }

    @Test fun upRepeatAndDuplicateDownCannotSkipMultipleOccurrencesAndNoDelayedHeadsetTapRuns() = runTest {
        remoteOwner()
        for (key in listOf(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_HEADSETHOOK)) {
            val first = event(key)
            val count = requests.size
            assertTrue(send(first)); runCurrent()
            assertTrue(send(first)); runCurrent() // Duplicate delivery of precisely the same down.
            assertTrue(send(KeyEvent(first.downTime, first.eventTime + 1, KeyEvent.ACTION_DOWN, key, 1))); runCurrent()
            assertTrue(send(KeyEvent(first.downTime, first.eventTime + 2, KeyEvent.ACTION_UP, key, 0))); runCurrent()
            shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS); runCurrent()
            assertEquals("One immediate action; no key-up/repeat/deferred Media3 headset action", count + 1, requests.size)
        }
    }

    @Test fun eventAtTheExactSuspensionTimeCannotBeReadmittedAsFreshPlay() = runTest {
        remoteOwner()
        endpoint.suspendOutput()
        val stoppedAt = PlaybackService::class.java.getDeclaredField("lastSuspendedUptime").let {
            it.isAccessible = true; it.getLong(service)
        }
        val before = endpoint.exportSnapshot()
        assertTrue(send(KeyEvent(stoppedAt, stoppedAt, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0)))
        runCurrent()
        assertTrue("Timestamp equality is stale, not permission for a fresh remote start", requests.isEmpty())
        assertEquals(before, endpoint.exportSnapshot())
        assertRemoteCommand(KeyEvent.KEYCODE_MEDIA_PLAY, "resume")
    }

    @Test fun seekKeysAreBoundedAgainstAuthoritativeRemotePositionAndDuration() = runTest {
        remoteElapsed = 119.0
        remoteOwner()
        assertRemoteCommand(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, "seek")
        assertEquals(PlaybackDeviceCommand.seek(120.0).json, requests.last().json)
    }

    @Test fun seekKeysUseKnownTrackDurationWhenSnapshotDurationIsUnknown() = runTest {
        remoteDuration = 0.0
        remoteElapsed = 119.0
        val owner = remoteOwner()
        assertRemoteCommand(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, "seek")
        assertEquals(PlaybackDeviceCommand.seek(120.0).json, requests.last().json)
        remoteElapsed = 130.0 // Clamp the sampled position before applying a relative seek too.
        owner.retry(); runCurrent()
        assertRemoteCommand(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, "seek")
        assertEquals(PlaybackDeviceCommand.seek(120.0).json, requests.last().json)
    }

    @Test fun rewindNeverSeeksBeforeStart() = runTest {
        remoteElapsed = 1.0
        remoteOwner()
        assertRemoteCommand(KeyEvent.KEYCODE_MEDIA_REWIND, "seek")
        assertEquals(PlaybackDeviceCommand.seek(0.0).json, requests.last().json)
    }

    @Test fun volumeUnknownAndMalformedIntentsDelegateOnlyForTrustedControllersWithoutMusicalCommands() = runTest {
        remoteOwner()
        val before = endpoint.exportSnapshot()
        val gain = output.volume
        for (key in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_A)) {
            assertFalse("Unmapped keys must retain platform handling", send(event(key)))
        }
        assertTrue(callback.onMediaButtonEvent(session, controller(), Intent(Intent.ACTION_MEDIA_BUTTON)))
        assertFalse(callback.onMediaButtonEvent(session, controller(), Intent(Intent.ACTION_VIEW).putExtra(Intent.EXTRA_KEY_EVENT, event(KeyEvent.KEYCODE_MEDIA_PLAY))))
        assertTrue(callback.onMediaButtonEvent(session, controller(), Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, "not a KeyEvent")))
        runCurrent()
        assertTrue(requests.isEmpty())
        assertEquals(before, endpoint.exportSnapshot())
        assertEquals(gain, output.volume, 0f)
    }

    @Test fun untrustedControllerIsRejectedAndCannotUseUnknownFallbackOrInjectMedia() = runTest {
        remoteOwner()
        val stranger = controller(Process.myUid() + 1, false)
        assertFalse(callback.onConnect(session, stranger).isAccepted)
        assertNull(service.onGetSession(stranger))
        assertEquals(SessionResult.RESULT_ERROR_PERMISSION_DENIED,
            callback.onPlayerCommandRequest(session, stranger, Player.COMMAND_PLAY_PAUSE))
        for (key in listOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_A)) {
            assertTrue("Reject untrusted callback without delegating to Media3", send(event(key), stranger))
        }
        runCurrent()
        assertTrue(requests.isEmpty())
        assertFalse(output.playWhenReady)
    }

    @Test fun queuedRemoteMediaPlayCannotSurviveCancellationOrSameAccountReauthentication() = runTest {
        remoteOwner()
        val before = endpoint.exportSnapshot()
        assertTrue(send(event(KeyEvent.KEYCODE_MEDIA_PLAY)))
        PlaybackRequests.invalidate() // Before the coordinator's actual serialized dispatch.
        runCurrent()
        assertTrue("Old media intent must not become a fresh remote Play", requests.isEmpty())
        assertEquals(before, endpoint.exportSnapshot())
        assertRemoteCommand(KeyEvent.KEYCODE_MEDIA_PLAY, "resume")
        assertTrue(send(event(KeyEvent.KEYCODE_MEDIA_PLAY)))
        client.login("listener", "synthetic-password")
        runCurrent()
        assertEquals("Same-account values cannot upgrade an old session's Play", 1, requests.size)
    }

    @Test fun mediaPlayAndPauseNeverInitializeOrImportAServerQueue() = runTest {
        assertEquals("ok", endpoint.activate(PlaybackSnapshot(), PlaybackAuthority(23, 4) { true }).status)
        remoteOwner(output = "phone", initial = prepared())
        val before = endpoint.exportSnapshot()
        val queueEpoch = PlaybackRequests.queueEpoch
        assertTrue(send(event(KeyEvent.KEYCODE_MEDIA_PLAY))); runCurrent()
        assertTrue(send(event(KeyEvent.KEYCODE_MEDIA_PAUSE))); runCurrent()
        assertEquals("No prepared item: even a server snapshot is not permission to restore it", before, endpoint.exportSnapshot())
        assertEquals("Rejected system transport must not even import a paused queue", queueEpoch, PlaybackRequests.queueEpoch)
        assertTrue(published.isEmpty())
        assertTrue(requests.isEmpty())
        assertFalse(output.playWhenReady)
    }

    @Test fun preparedLocalPlayPreservesPositionQueueAndHistoryAndForegroundDenialStaysBlocked() = runTest {
        val owner = remoteOwner(output = "phone", initial = prepared())
        // Model an already prepared, coordinator-activated local output, not a restored queue.
        owner.remoteCommand(PlaybackDeviceCommand.transport("pause")); runCurrent()
        published.clear()
        val before = endpoint.exportSnapshot()
        val queueEpoch = PlaybackRequests.queueEpoch
        shadowOf(service).setThrowInStartForeground(android.app.ForegroundServiceStartNotAllowedException("Synthetic user-start denial"))
        assertTrue(send(event(KeyEvent.KEYCODE_MEDIA_PLAY))); runCurrent()
        val after = endpoint.exportSnapshot()
        assertEquals(before.queue, after.queue)
        assertEquals(before.position, after.position)
        assertEquals(before.elapsed, after.elapsed, 0.0)
        assertEquals(before.occurrence, after.occurrence)
        assertEquals(queueEpoch, PlaybackRequests.queueEpoch)
        assertEquals("blocked", after.error)
        assertFalse(output.playWhenReady)
        assertEquals(1, published.size)
        assertTrue(requests.isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS); runCurrent()
        assertFalse("No delayed retry after foreground denial", output.playWhenReady)
    }

    @Test fun preparedMediaStartWaitingForReadinessCannotSurviveASameCurrentQueueEdit() = runTest {
        val owner = remoteOwner(output = "phone")
        owner.remoteCommand(PlaybackDeviceCommand.transport("pause")); runCurrent()
        val real = output
        val field = PlaybackService::class.java.getDeclaredField("player").apply { isAccessible = true }
        var state = Player.STATE_IDLE
        var wantsPlay = false
        var plays = 0
        // Timing alone is synthetic; queue/current/position remain the actual service output.
        val controlled = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "Media readiness fixture"
                "prepare" -> { state = Player.STATE_BUFFERING; Unit }
                "getPlaybackState" -> state
                "getPlayWhenReady", "isPlaying" -> wantsPlay
                "getPlayerError" -> null
                "setPlayWhenReady" -> { wantsPlay = args!![0] as Boolean; Unit }
                "play" -> { plays++; wantsPlay = true; Unit }
                "pause" -> { wantsPlay = false; Unit }
                "stop" -> { wantsPlay = false; state = Player.STATE_IDLE; Unit }
                else -> try { method.invoke(real, *(args ?: emptyArray())) }
                    catch (failure: InvocationTargetException) { throw failure.targetException }
            }
        } as ExoPlayer
        shadowOf(service.getSystemService(android.media.AudioManager::class.java))
            .setNextFocusRequestResponse(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        field.set(service, controlled)
        try {
            assertTrue(send(event(KeyEvent.KEYCODE_MEDIA_PLAY))); runCurrent()
            assertEquals("Real media admission must reach asynchronous readiness", Player.STATE_BUFFERING, state)
            val permit = PlaybackService::class.java.getDeclaredField("authority").let {
                it.isAccessible = true; it.get(service) as PlaybackAuthority
            }
            val before = endpoint.exportSnapshot()
            val future = before.queue[before.position + 1]
            assertEquals("ok", endpoint.execute(PlaybackDeviceCommand.queue("remove", future.key), permit).status)
            assertEquals(before.queue[before.position].key, endpoint.exportSnapshot().queue[endpoint.exportSnapshot().position].key)
            state = Player.STATE_READY
            advanceTimeBy(30); runCurrent()
            assertEquals("The held system start must recheck its queue admission after readiness", 0, plays)
            assertFalse(wantsPlay)
            assertEquals(1, web.requestCount)
        } finally { field.set(service, real) }
    }

    @Test fun impossibleAndFutureMediaEventTimesCannotBecomeTransport() = runTest {
        remoteOwner()
        val now = SystemClock.uptimeMillis()
        assertTrue(send(KeyEvent(now + 10, now + 10, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0)))
        assertTrue(send(KeyEvent(now + 1, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0)))
        runCurrent()
        assertTrue(requests.isEmpty())
        assertRemoteCommand(KeyEvent.KEYCODE_MEDIA_PLAY, "resume")
    }

    @Test fun firstDeliveryAfterLocalReplacementCannotTargetTheNewQueue() = runTest {
        remoteOwner()
        val old = event(KeyEvent.KEYCODE_MEDIA_NEXT)
        SystemClock.sleep(1)
        assertEquals("ok", endpoint.activate(prepared().copy(queue = prepared().queue.reversed()), PlaybackAuthority(23, 4) { true }).status)
        val before = endpoint.exportSnapshot()
        assertTrue(send(old)); runCurrent()
        assertTrue(requests.isEmpty())
        assertEquals(before, endpoint.exportSnapshot())
        assertRemoteCommand(KeyEvent.KEYCODE_MEDIA_NEXT, "next")
    }

    @Test fun firstDeliveryAfterSelectedLocalReplacementCannotSeekItsSuccessor() = runTest {
        val owner = remoteOwner(output = "phone")
        owner.remoteCommand(PlaybackDeviceCommand.transport("pause")); runCurrent()
        val old = event(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
        SystemClock.sleep(1)
        assertEquals("ok", endpoint.activate(prepared().copy(queue = prepared().queue.reversed()),
            checkNotNull(owner.currentAuthority())).status)
        val before = endpoint.exportSnapshot()
        assertTrue(send(old)); runCurrent()
        assertEquals(before, endpoint.exportSnapshot())
        assertTrue(send(event(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))); runCurrent()
        assertEquals(before.queue, endpoint.exportSnapshot().queue)
        assertTrue("A fresh local seek must remain usable", endpoint.exportSnapshot().elapsed > before.elapsed)
        assertTrue(requests.isEmpty())
    }

    @Test fun firstDeliveryAfterRemoteReplacementCannotTargetTheNewQueue() = runTest {
        val owner = remoteOwner()
        val old = event(KeyEvent.KEYCODE_MEDIA_PLAY)
        val epochs = PlaybackRequests.epoch to PlaybackRequests.queueEpoch
        SystemClock.sleep(1)
        remoteSnapshot = prepared().copy(queue = prepared().queue.reversed())
        owner.retry(); runCurrent()
        assertEquals("Remote replacement need not change local generations", epochs, PlaybackRequests.epoch to PlaybackRequests.queueEpoch)
        assertTrue(send(old)); runCurrent()
        assertTrue(requests.isEmpty())
        assertRemoteCommand(KeyEvent.KEYCODE_MEDIA_PLAY, "resume")
    }

    @Test fun retainedRemoteNotificationCannotBeUpgradedToAReplacementQueue() = runTest {
        val owner = remoteOwner()
        val make = PlaybackService::class.java.getDeclaredMethod("notificationIntent", String::class.java).apply { isAccessible = true }
        val old = make.invoke(service, "resume") as android.app.PendingIntent
        val epochs = PlaybackRequests.epoch to PlaybackRequests.queueEpoch
        remoteSnapshot = prepared().copy(queue = prepared().queue.reversed())
        owner.retry(); runCurrent()
        assertEquals(epochs, PlaybackRequests.epoch to PlaybackRequests.queueEpoch)
        val fresh = make.invoke(service, "resume") as android.app.PendingIntent
        assertNotEquals("PendingIntent identity, not just extras, must bind the remote queue", old, fresh)
        val before = endpoint.exportSnapshot()
        old.send()
        service.onStartCommand(requireNotNull(shadowOf(service).nextStartedService), 0, 1)
        runCurrent()
        assertTrue(requests.isEmpty())
        assertEquals(before, endpoint.exportSnapshot())
        fresh.send()
        service.onStartCommand(requireNotNull(shadowOf(service).nextStartedService), 0, 2)
        runCurrent()
        assertEquals(listOf("resume"), requests.map { it.type })
        assertEquals(before, endpoint.exportSnapshot())
    }

    @Test fun remoteNotificationRejectsUnsampledAToBToAWithoutMintingAReplacement() = runTest {
        val owner = remoteOwner()
        val make = PlaybackService::class.java.getDeclaredMethod("notificationIntent", String::class.java).apply { isAccessible = true }
        val old = make.invoke(service, "resume") as android.app.PendingIntent
        remoteSnapshot = prepared().copy(queue = prepared().queue.reversed())
        owner.retry(); runCurrent()
        remoteSnapshot = prepared().copy(elapsed = remoteElapsed)
        owner.retry(); runCurrent()
        old.send() // No intervening mint: delivery itself must observe the changed incarnation.
        service.onStartCommand(requireNotNull(shadowOf(service).nextStartedService), 0, 1)
        runCurrent()
        assertTrue(requests.isEmpty())
        val fresh = make.invoke(service, "resume") as android.app.PendingIntent
        fresh.send()
        service.onStartCommand(requireNotNull(shadowOf(service).nextStartedService), 0, 2)
        runCurrent()
        assertEquals(listOf("resume"), requests.map { it.type })
    }

    @Test fun localNotificationRemainsUsableAfterItsResultIsEchoedByCoordinator() = runTest {
        val owner = remoteOwner(output = "phone")
        owner.remoteCommand(PlaybackDeviceCommand.transport("pause")); runCurrent()
        assertEquals("ok", endpoint.activate(prepared().copy(queue = prepared().queue.reversed()),
            checkNotNull(owner.currentAuthority())).status)
        service.onUpdateNotificationAsync(session, false).get(1, TimeUnit.SECONDS)
        val notification = shadowOf(service.getSystemService(android.app.NotificationManager::class.java)).allNotifications.single()
        val posted = notification.actions.single { it.title.toString() == "Next" }.actionIntent
        val before = endpoint.exportSnapshot()
        remoteSnapshot = before
        owner.retry(); runCurrent() // Server echo changes its binding only after local notification.
        shadowOf(service).setThrowInStartForeground(android.app.ForegroundServiceStartNotAllowedException("Synthetic denial"))
        published.clear()
        posted.send()
        service.onStartCommand(requireNotNull(shadowOf(service).nextStartedService), 0, 1)
        runCurrent()
        assertNotEquals("The posted Next must survive its own later result echo", before.position, endpoint.exportSnapshot().position)
        assertEquals(1, published.size)
        assertFalse(output.playWhenReady)
    }

    @Test fun remoteProgressHeartbeatDoesNotRetireAPreparedNotification() = runTest {
        val owner = remoteOwner()
        val make = PlaybackService::class.java.getDeclaredMethod("notificationIntent", String::class.java).apply { isAccessible = true }
        val old = make.invoke(service, "resume") as android.app.PendingIntent
        remoteElapsed += 1.0
        owner.retry(); runCurrent()
        assertEquals(old, make.invoke(service, "resume"))
        old.send()
        service.onStartCommand(requireNotNull(shadowOf(service).nextStartedService), 0, 1)
        runCurrent()
        assertEquals(listOf("resume"), requests.map { it.type })
    }

    @Test fun queuedRemoteRequestCannotSurviveQueueReplacementAndFreshRequestRemainsUsable() = runTest {
        remoteOwner()
        assertTrue(send(event(KeyEvent.KEYCODE_MEDIA_NEXT)))
        val changed = prepared().copy(queue = prepared().queue.reversed(), position = 1)
        assertEquals("ok", endpoint.activate(changed, PlaybackAuthority(23, 4) { true }).status)
        val before = endpoint.exportSnapshot()
        runCurrent()
        assertTrue(requests.isEmpty())
        assertEquals(before, endpoint.exportSnapshot())
        assertRemoteCommand(KeyEvent.KEYCODE_MEDIA_NEXT, "next")
    }

    @Test fun firstDeliveryToSuccessorRejectsAPressFromBeforeServiceCreation() = runTest {
        val old = event(KeyEvent.KEYCODE_MEDIA_PLAY)
        serviceController!!.destroy()
        SystemClock.sleep(1)
        serviceController = Robolectric.buildService(PlaybackService::class.java).create()
        assertTrue(send(old)); runCurrent()
        val admittedDowns = PlaybackService::class.java.getDeclaredField("mediaButtonDowns").let {
            it.isAccessible = true; it.get(service) as Set<*>
        }
        assertTrue("The old press must be rejected at admission, not just fail for an empty queue", admittedDowns.isEmpty())
        remoteOwner()
        assertRemoteCommand(KeyEvent.KEYCODE_MEDIA_PLAY, "resume")
    }

    @Test fun heldMediaRequestCannotBorrowSuccessorServiceIdentity() = runTest {
        remoteOwner()
        assertTrue(send(event(KeyEvent.KEYCODE_MEDIA_PLAY)))
        serviceController!!.destroy()
        serviceController = Robolectric.buildService(PlaybackService::class.java).create()
        runCurrent()
        assertTrue(requests.isEmpty())
        assertEquals(0, output.mediaItemCount)
        assertFalse(output.playWhenReady)
    }

    @Test fun actualMediaControllerPublishesOnlyNarrowTransportCapabilitiesAndRoutesWithoutQueueReplacement() = runTest {
        remoteOwner()
        val future = MediaController.Builder(service, session.token).buildAsync()
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        val media = future.get(1, TimeUnit.SECONDS)
        try {
            val forbidden = listOf(Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS,
                Player.COMMAND_SET_SPEED_AND_PITCH, Player.COMMAND_SET_VOLUME,
                Player.COMMAND_SET_DEVICE_VOLUME, Player.COMMAND_ADJUST_DEVICE_VOLUME)
            forbidden.forEach { assertFalse("Raw mutator must remain unavailable: $it", media.isCommandAvailable(it)) }
            val before = endpoint.exportSnapshot()
            media.play(); shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS); runCurrent()
            assertEquals(listOf("resume"), requests.map { it.type })
            media.pause(); shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS); runCurrent()
            assertEquals(listOf("resume", "pause"), requests.map { it.type })
            assertEquals(before, endpoint.exportSnapshot())
            assertFalse(output.playWhenReady)
            // Media3 always adds RELEASE to a MediaController: it disconnects this observer,
            // not the service-owned output. The wrapper's own RELEASE remains unavailable.
            assertTrue(media.isCommandAvailable(Player.COMMAND_RELEASE))
            assertFalse(session.player.isCommandAvailable(Player.COMMAND_RELEASE))
            media.release()
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS); runCurrent()
            assertEquals("Controller release must not empty/release the service output", before, endpoint.exportSnapshot())
            assertEquals(before.queue.size, output.mediaItemCount)
        } finally { media.release() }
    }
}
