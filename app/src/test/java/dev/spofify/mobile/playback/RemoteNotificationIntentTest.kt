package dev.spofify.mobile.playback

import android.app.Notification
import android.app.NotificationManager
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import dev.spofify.mobile.AppGraph
import dev.spofify.mobile.LoginStore
import dev.spofify.mobile.RememberedLogin
import dev.spofify.mobile.SpofifyApplication
import dev.spofify.mobile.core.LibraryPlaybackDevice
import dev.spofify.mobile.core.LibraryPlaybackSnapshot
import dev.spofify.mobile.core.LibraryPlaybackTrack
import dev.spofify.mobile.core.LibraryPlaybackOccurrence
import dev.spofify.mobile.core.ServerAddress
import dev.spofify.mobile.core.SessionStore
import dev.spofify.mobile.core.StoredSession
import dev.spofify.mobile.feature.devices.PlaybackCoordinator
import dev.spofify.mobile.feature.devices.PlaybackDevicesState
import kotlinx.coroutines.flow.MutableStateFlow
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

/** Actual service notification callback and posted actions/flags, not a boolean helper test.
 * Synthetic API35 JVM evidence only; no native notification rendering or audio acceptance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SpofifyApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(UnstableApi::class)
class RemoteNotificationIntentTest {
    private lateinit var web: MockWebServer
    private lateinit var graph: AppGraph
    private lateinit var display: MutableStateFlow<PlaybackDevicesState>
    private var controller: ServiceController<PlaybackService>? = null

    @Before fun setUp() = runBlocking {
        System.getenv("SPOFIFY_TEST_TMPDIR")?.let { expected ->
            assertEquals("Verify the forked test JVM's temp directory", expected, System.getProperty("java.io.tmpdir"))
        }
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
        PlaybackService.onServiceActiveChanged = null // No backend device registration/polling.
        client.login("listener", "synthetic-password")
        assertEquals("/api/auth/login", web.takeRequest(1, TimeUnit.SECONDS)?.path)
        controller = Robolectric.buildService(PlaybackService::class.java).create()
        @Suppress("UNCHECKED_CAST")
        val state = PlaybackCoordinator::class.java.getDeclaredField("mutableState").let {
            it.isAccessible = true
            it.get(requireNotNull(graph.playbackCoordinator)) as MutableStateFlow<PlaybackDevicesState>
        }
        display = state
    }

    @After fun tearDown() {
        PlaybackService.onServiceActiveChanged = null
        // Never leave a deliberately invalid fixture snapshot for Media3 release callbacks.
        if (::display.isInitialized) display.value = PlaybackDevicesState()
        try { controller?.destroy() }
        finally { try { if (::graph.isInitialized) graph.clear() } finally { if (::web.isInitialized) web.shutdown() } }
    }

    private val service get() = requireNotNull(controller).get()
    private fun serviceField(name: String): Any = PlaybackService::class.java.getDeclaredField(name).let {
        it.isAccessible = true
        requireNotNull(it.get(service))
    }
    private val session get() = serviceField("session") as MediaSession
    private val notifications get() = shadowOf(service.getSystemService(NotificationManager::class.java)).allNotifications

    private fun remote(): LibraryPlaybackSnapshot {
        val base = testLibrarySnapshot(devices = listOf(
            LibraryPlaybackDevice("phone", "Phone", true, true),
            LibraryPlaybackDevice("speaker", "Speaker", true, true)), selected = "speaker", playing = true)
        return base.copy(state = base.state.copy(
            queue = listOf(LibraryPlaybackTrack("remote-track", "Remote song", emptyList(), "Album", "album",
                120.0, key = 7, order = 0, intent = "now")), position = 0, desiredPlaying = true,
            occurrence = LibraryPlaybackOccurrence(7, "remote-track", "event", 0.0, false)),
            playback = base.playback.copy(eventId = "event"))
    }

    private fun LibraryPlaybackSnapshot.facts(playing: Boolean = playback.isPlaying,
        waiting: Boolean = false, loading: Boolean = false, error: String = "") = copy(
        playback = playback.copy(isPlaying = playing, isBuffering = waiting, isLoading = loading, error = error))

    private fun update(snapshot: LibraryPlaybackSnapshot) {
        // Fixture-only display publication into the real, dormant coordinator. No authority,
        // service/player field or production seam is changed. Bypassing wire admission also
        // lets the defensive invalid-current cases reach the actual notification callback;
        // those cases deliberately do not exercise the validating session projection/tick.
        display.value = PlaybackDevicesState(deviceId = "phone", connectionId = "socket-phone",
            connected = true, snapshot = snapshot)
        // Even Media3 requesting foreground must remain a metadata/action refresh remotely.
        assertNull(service.onUpdateNotificationAsync(session, startInForegroundRequired = true).get(1, TimeUnit.SECONDS))
        assertFalse((serviceField("foreground") as PlaybackForegroundNotification).foregroundConfirmed)
        assertEquals(0, shadowOf(service).lastForegroundNotificationId)
        val output = serviceField("player") as ExoPlayer
        assertEquals(0, output.mediaItemCount)
        assertFalse(output.playWhenReady)
        assertFalse(output.isPlaying)
        assertEquals("Only synthetic login may hit HTTP", 1, web.requestCount)
    }

    private fun assertNotification(activeIntent: Boolean) {
        val notification = notifications.single()
        val command = if (activeIntent) "pause" else "resume"
        val label = if (activeIntent) "Pause" else "Resume"
        assertEquals("Remote song", notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals(listOf("Previous", label, "Next"), notification.actions.map { it.title.toString() })
        assertEquals(listOf("previous", command, "next"), notification.actions.map {
            shadowOf(it.actionIntent).savedIntent.getStringExtra("command")
        })
        assertEquals(activeIntent, notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("android.app.Notification\$MediaStyle", notification.extras.getString(Notification.EXTRA_TEMPLATE))
    }

    @Test fun erroredRemotePlayingReplacesPauseWithResumeAndClearsOngoing() {
        for (error in listOf("blocked", "media")) {
            update(remote())
            assertNotification(activeIntent = true)
            for (buffering in listOf(false, true)) {
                update(remote().facts(error = error, waiting = buffering, loading = buffering))
                assertNotification(activeIntent = false)
            }
        }
    }

    @Test fun validBufferingTowardRemotePlaybackRetainsPauseAndOngoing() {
        for ((waiting, loading) in listOf(true to false, false to true, true to true)) {
            update(remote().facts(waiting = waiting, loading = loading))
            assertNotification(activeIntent = true)
        }
    }

    @Test fun emptyOrInvalidRemoteCurrentRemovesStalePlayingNotification() {
        val snapshot = remote()
        for (absent in listOf(testLibrarySnapshot(playing = true),
            snapshot.copy(state = snapshot.state.copy(position = -1)),
            snapshot.copy(state = snapshot.state.copy(position = snapshot.state.queue.size)))) {
            update(snapshot)
            assertNotification(activeIntent = true)
            update(absent)
            assertTrue("No current occurrence must leave no stale notification", notifications.isEmpty())
        }
    }

    @Test fun pausedRemoteShowsResumeWithoutOngoingEvenWithLoadingFlags() {
        update(remote())
        assertNotification(activeIntent = true)
        for (buffering in listOf(false, true)) {
            update(remote().facts(playing = false, waiting = buffering, loading = buffering))
            assertNotification(activeIntent = false)
        }
    }
}
