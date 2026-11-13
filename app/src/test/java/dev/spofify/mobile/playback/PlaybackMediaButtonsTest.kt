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
import dev.spofify.mobile.feature.devices.PlaybackCoordinator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.*
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
    private val endpoint = Media3PlaybackEndpoint(ApplicationProvider.getApplicationContext())
    private val requests = mutableListOf<LibraryPlaybackCommand>()
    private var remoteSnapshot: LibraryPlaybackSnapshot? = null
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


    private lateinit var socket: Socket
    private var structuralVersion = 1L
    private var scalarSequence = 1L
    private class Socket(private val record: (LibraryPlaybackCommand) -> Unit) : LibraryPlaybackTransport {
        override val identity = ValidatedSessionIdentity("http://localhost", "fixture-account", 1)
        override var isCurrent = true
        val incoming = Channel<LibraryPlaybackEvent>(32)
        override val events = incoming.receiveAsFlow()
        private var command = 0L
        override fun send(message: LibraryPlaybackClientMessage): LibraryPlaybackSend? {
            if (!isCurrent) return null
            check(message is LibraryPlaybackClientMessage.Command) { "Remote display must never emit executor facts" }
            record(message.command)
            return LibraryPlaybackSend(0, commandId = (++command).toString())
        }
        override fun close() { isCurrent = false; incoming.close() }
        fun message(value: LibraryPlaybackServerMessage) {
            incoming.trySend(LibraryPlaybackEvent.Message(value)).getOrThrow()
        }
    }
    private val LibraryPlaybackCommand.type: String get() = Json.parseToJsonElement(
        LibraryPlaybackCodec.encode(LibraryPlaybackClientMessage.Command(this), commandId = "1"))
        .jsonObject.getValue("command").jsonObject.getValue("type").jsonPrimitive.content
    private fun physical() = (0 until output.mediaItemCount).map { output.getMediaItemAt(it) } to endpoint.observe()
    private fun prepared(): LibraryPlaybackSnapshot {
        val base = testLibrarySnapshot(devices = listOf(
            LibraryPlaybackDevice("phone", "Phone", true, true),
            LibraryPlaybackDevice("speaker", "Speaker", true, true)), selected = "speaker")
        return base.copy(state = base.state.copy(
            queue = listOf(91L to 2L, 7L to 0L, 32L to 1L).map { (key, order) ->
                LibraryPlaybackTrack("duplicate-track", "Occurrence $key", emptyList(), "Album", "album",
                    120.0, key, order, "now")
            }, position = 1, shuffle = true, desiredPlaying = remotePlaying,
            occurrence = LibraryPlaybackOccurrence(7, "duplicate-track", "retained-event", 3.25, false)),
            playback = base.playback.copy(eventId = "retained-event", isPlaying = remotePlaying,
                elapsed = remoteElapsed, duration = remoteDuration))
    }
    private fun TestScope.remoteOwner(output: String = "speaker",
        initial: LibraryPlaybackSnapshot? = null): PlaybackCoordinator {
        graph.playbackCoordinator!!.close()
        socket = Socket(requests::add)
        val snapshot = (initial ?: remoteSnapshot ?: prepared()).copy(selectedDeviceId = output,
            selectedConnectionId = "socket-$output")
        val owner = PlaybackCoordinator(client, endpoint, backgroundScope,
            clockMs = { testScheduler.currentTime }, mediaEventUptimeMs = { SystemClock.uptimeMillis() },
            openTransport = { _, _, _, _ -> socket }, authenticationIdentity = { client.validatedSessionIdentity() },
            deviceIdentity = { "phone" to "Phone" })
        AppGraph::class.java.getDeclaredField("playbackCoordinator").apply { isAccessible = true; set(graph, owner) }
        owner.setServiceActive(true); runCurrent()
        socket.message(LibraryPlaybackServerMessage("snapshot", "room", "socket-phone", "phone", snapshot = snapshot))
        runCurrent()
        return owner
    }
    private fun TestScope.publishRemote(replacement: Boolean = false) {
        if (replacement) structuralVersion++
        scalarSequence++
        val next = remoteSnapshot ?: prepared()
        socket.message(LibraryPlaybackServerMessage("state", "room", snapshot = next.copy(
            stateVersion = structuralVersion, playback = next.playback.copy(
                stateVersion = structuralVersion, playbackSequence = scalarSequence))))
        runCurrent()
    }
    private fun replacement() = prepared().let { it.copy(state = it.state.copy(queue = it.state.queue.reversed())) }
    private fun event(code: Int, action: Int = KeyEvent.ACTION_DOWN, repeat: Int = 0): KeyEvent {
        SystemClock.sleep(1) // A genuinely fresh event, never a manufactured future timestamp.
        val now = SystemClock.uptimeMillis()
        return KeyEvent(now, now, action, code, repeat)
    }
    private fun send(event: KeyEvent, who: MediaSession.ControllerInfo = controller()): Boolean =
        callback.onMediaButtonEvent(session, who, Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, event))
    private fun TestScope.assertRemoteCommand(code: Int, type: String, who: MediaSession.ControllerInfo = controller()) {
        val before = physical()
        val count = requests.size
        assertTrue(send(event(code), who)); runCurrent()
        assertEquals("Exactly one authoritative command for key $code", count + 1, requests.size)
        assertEquals(type, requests.last().type)
        assertEquals("Remote key must not change local queue/elapsed/history", before, physical())
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
        PlaybackService.suspendLocalOutput()
        val stoppedAt = PlaybackService::class.java.getDeclaredField("lastSuspendedUptime").let {
            it.isAccessible = true; it.getLong(service)
        }
        val before = physical()
        assertTrue(send(KeyEvent(stoppedAt, stoppedAt, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0)))
        runCurrent()
        assertTrue("Timestamp equality is stale, not permission for a fresh remote start", requests.isEmpty())
        assertEquals(before, physical())
        assertRemoteCommand(KeyEvent.KEYCODE_MEDIA_PLAY, "resume")
    }

    @Test fun seekKeysAreBoundedAgainstAuthoritativeRemotePositionAndDuration() = runTest {
        remoteElapsed = 119.0
        remoteOwner()
        assertRemoteCommand(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, "seek")
        assertEquals(LibraryPlaybackCommand.Seek(120.0), requests.last())
    }

    @Test fun seekKeysUseKnownTrackDurationWhenSnapshotDurationIsUnknown() = runTest {
        remoteDuration = 0.0
        remoteElapsed = 119.0
        val owner = remoteOwner()
        assertRemoteCommand(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, "seek")
        assertEquals(LibraryPlaybackCommand.Seek(120.0), requests.last())
        remoteElapsed = 130.0 // Clamp the sampled position before applying a relative seek too.
        publishRemote(replacement = true)
        assertRemoteCommand(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, "seek")
        assertEquals(LibraryPlaybackCommand.Seek(120.0), requests.last())
    }

    @Test fun rewindNeverSeeksBeforeStart() = runTest {
        remoteElapsed = 1.0
        remoteOwner()
        assertRemoteCommand(KeyEvent.KEYCODE_MEDIA_REWIND, "seek")
        assertEquals(LibraryPlaybackCommand.Seek(0.0), requests.last())
    }

    @Test fun volumeUnknownAndMalformedIntentsDelegateOnlyForTrustedControllersWithoutMusicalCommands() = runTest {
        remoteOwner()
        val before = physical()
        val gain = output.volume
        for (key in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_A)) {
            assertFalse("Unmapped keys must retain platform handling", send(event(key)))
        }
        assertTrue(callback.onMediaButtonEvent(session, controller(), Intent(Intent.ACTION_MEDIA_BUTTON)))
        assertFalse(callback.onMediaButtonEvent(session, controller(), Intent(Intent.ACTION_VIEW).putExtra(Intent.EXTRA_KEY_EVENT, event(KeyEvent.KEYCODE_MEDIA_PLAY))))
        assertTrue(callback.onMediaButtonEvent(session, controller(), Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, "not a KeyEvent")))
        runCurrent()
        assertTrue(requests.isEmpty())
        assertEquals(before, physical())
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


    @Test fun authenticationLossFencesHeldMediaPressAndCannotBorrowEqualAccountReauthentication() = runTest {
        remoteOwner()
        val old = event(KeyEvent.KEYCODE_MEDIA_PLAY)
        client.login("listener", "synthetic-password")
        assertTrue(send(old)); runCurrent()
        assertTrue(requests.isEmpty())
        assertFalse(output.playWhenReady)
        assertEquals(0, output.mediaItemCount)
        remoteOwner()
        assertRemoteCommand(KeyEvent.KEYCODE_MEDIA_PLAY, "resume")
    }

    @Test fun systemTransportWithRetainedServerQueueNeverImportsOrStartsLocalMedia() = runTest {
        remoteOwner(output = "phone")
        val before = physical()
        assertRemoteCommand(KeyEvent.KEYCODE_MEDIA_PLAY, "resume")
        assertRemoteCommand(KeyEvent.KEYCODE_MEDIA_PAUSE, "pause")
        assertEquals(before, physical())
        assertEquals(0, output.mediaItemCount)
        assertFalse(output.playWhenReady)
        assertEquals(1, web.requestCount)
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

    @Test fun firstDeliveryAfterRemoteReplacementCannotTargetTheNewQueue() = runTest {
        val owner = remoteOwner()
        val old = event(KeyEvent.KEYCODE_MEDIA_PLAY)
        val epochs = PlaybackRequests.epoch to PlaybackRequests.queueEpoch
        SystemClock.sleep(1)
        remoteSnapshot = replacement()
        publishRemote(replacement = true)
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
        remoteSnapshot = replacement()
        publishRemote(replacement = true)
        assertEquals(epochs, PlaybackRequests.epoch to PlaybackRequests.queueEpoch)
        val fresh = make.invoke(service, "resume") as android.app.PendingIntent
        assertNotEquals("PendingIntent identity, not just extras, must bind the remote queue", old, fresh)
        val before = physical()
        old.send()
        service.onStartCommand(requireNotNull(shadowOf(service).nextStartedService), 0, 1)
        runCurrent()
        assertTrue(requests.isEmpty())
        assertEquals(before, physical())
        fresh.send()
        service.onStartCommand(requireNotNull(shadowOf(service).nextStartedService), 0, 2)
        runCurrent()
        assertEquals(listOf("resume"), requests.map { it.type })
        assertEquals(before, physical())
    }

    @Test fun remoteNotificationRejectsUnsampledAToBToAWithoutMintingAReplacement() = runTest {
        val owner = remoteOwner()
        val make = PlaybackService::class.java.getDeclaredMethod("notificationIntent", String::class.java).apply { isAccessible = true }
        val old = make.invoke(service, "resume") as android.app.PendingIntent
        remoteSnapshot = replacement()
        publishRemote(replacement = true)
        remoteSnapshot = prepared()
        publishRemote(replacement = true)
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

    @Test fun remoteProgressHeartbeatDoesNotRetireAPreparedNotification() = runTest {
        val owner = remoteOwner()
        val make = PlaybackService::class.java.getDeclaredMethod("notificationIntent", String::class.java).apply { isAccessible = true }
        val old = make.invoke(service, "resume") as android.app.PendingIntent
        remoteElapsed += 1.0
        publishRemote()
        assertEquals(old, make.invoke(service, "resume"))
        old.send()
        service.onStartCommand(requireNotNull(shadowOf(service).nextStartedService), 0, 1)
        runCurrent()
        assertEquals(listOf("resume"), requests.map { it.type })
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
            val before = physical()
            media.play(); shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS); runCurrent()
            assertEquals(listOf("resume"), requests.map { it.type })
            media.pause(); shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS); runCurrent()
            assertEquals(listOf("resume", "pause"), requests.map { it.type })
            assertEquals(before, physical())
            assertFalse(output.playWhenReady)
            // Media3 always adds RELEASE to a MediaController: it disconnects this observer,
            // not the service-owned output. The wrapper's own RELEASE remains unavailable.
            assertTrue(media.isCommandAvailable(Player.COMMAND_RELEASE))
            assertFalse(session.player.isCommandAvailable(Player.COMMAND_RELEASE))
            media.release()
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS); runCurrent()
            assertEquals("Controller release must not empty/release the service output", before, physical())
            assertEquals(before.first.size, output.mediaItemCount)
        } finally { media.release() }
    }
}
