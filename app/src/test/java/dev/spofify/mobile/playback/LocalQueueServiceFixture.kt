package dev.spofify.mobile.playback

import android.app.ForegroundServiceStartNotAllowedException
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import dev.spofify.mobile.*
import dev.spofify.mobile.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Real sole service/local queue and verified reference admission. Deliberate platform start
 * denial keeps these queue-placement tests silent; no fabricated receiver/lease or online queue.
 * Actual saved-byte playback remains in OfflinePlaybackServiceTest (separate owner).
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class LocalQueueServiceFixture : AutoCloseable {
    private val app = ApplicationProvider.getApplicationContext<SpofifyApplication>()
    val web = MockWebServer().apply {
        dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = MockResponse().setResponseCode(500)
        }
        start(InetAddress.getByName("127.0.0.1"), 0)
    }
    val graph: AppGraph
    val eligibility: OfflineEligibility
    private val owner: OfflineLibraryAccess
    private val references = mutableMapOf<String, OfflineMediaReference>()
    private val controller: org.robolectric.android.controller.ServiceController<PlaybackService>
    val service get() = controller.get()
    val output: ExoPlayer get() = PlaybackService::class.java.getDeclaredField("player").let {
        it.isAccessible = true; it.get(service) as ExoPlayer
    }
    init {
        app.graph.clear()
        val original = OfflineEligibility("http://127.0.0.1:${web.port}", "saved-queue-owner", "listener")
        app.getSharedPreferences("connection", 0).edit().clear().putBoolean("offline_mode", true).commit()
        graph = AppGraph(app, object : SessionStore {
            override fun read(): StoredSession? = null
            override fun write(value: StoredSession?) = Unit
        }, object : LoginStore {
            override fun read(): RememberedLogin? = null
            override fun write(value: RememberedLogin?) = Unit
        }, offlineEligibilityStore = object : OfflineEligibilityStore {
            private var saved: OfflineEligibility? = original
            override fun read() = saved
            override fun write(value: OfflineEligibility?) { saved = value }
        })
        eligibility = requireNotNull(graph.localEligibility)
        SpofifyApplication::class.java.getDeclaredField("graph").apply { isAccessible = true; set(app, graph) }
        owner = object : OfflineLibraryAccess {
            override val revision = MutableStateFlow(0L)
            override suspend fun collections(origin: String, accountId: String) = emptyList<OfflineSavedCollection>()
            override fun retire() = Unit
        }
        graph.offlineLibrary = owner
        PlaybackService.onServiceActiveChanged = null
        controller = Robolectric.buildService(PlaybackService::class.java).create()
        shadowOf(service).setThrowInStartForeground(ForegroundServiceStartNotAllowedException("Synthetic queue-only fixture"))
    }
    fun row(name: String, entry: String = name, id: String = name) = LibraryItem(
        "track", id, name, hasAudio = true, durationMs = 120000, entryId = entry)
    private fun admit(rows: List<LibraryItem>): Map<String, OfflineMediaReference> {
        rows.forEach { row -> references.getOrPut(requireNotNull(row.entryId)) {
            OfflineMediaReference(owner, eligibility.origin, eligibility.accountId) {
                error("Silent queue operation must not open saved media")
            }
        } }
        return references.toMap()
    }
    fun replace(rows: List<LibraryItem>, start: Int = 0, shuffle: Boolean = false) {
        assertNull(PlaybackService.replaceLocal(rows, eligibility, admit(rows), start, shuffle,
            LibraryItem("playlist", "saved-context", "Saved context")))
        shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
        assertSilent()
    }
    fun append(rows: List<LibraryItem>, intent: QueueIntentIntent = QueueIntentIntent.HIGH,
        current: () -> Boolean = { true }): String? =
        PlaybackService.enqueueLocal(rows, eligibility, admit(rows), intent, current)
    fun snapshot() = PlaybackService.displayLocalSnapshot()
    fun assertSilent() {
        assertFalse(output.playWhenReady)
        assertFalse(output.isPlaying)
        assertEquals("No API/audio/history/registration traffic", 0, web.requestCount)
    }
    override fun close() {
        PlaybackService.onServiceActiveChanged = null
        try { controller.destroy() } finally {
            try { graph.clear() } finally {
                app.getSharedPreferences("connection", 0).edit().clear().commit()
                web.shutdown()
            }
        }
    }
}
