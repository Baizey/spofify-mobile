package dev.spofify.mobile

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import dev.spofify.mobile.core.*
import dev.spofify.mobile.playback.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryViewModelTest {
    private class MemoryStore : SessionStore {
        private var value: StoredSession? = null
        override fun read() = value
        override fun write(value: StoredSession?) { this.value = value }
    }
    private class FakePlayback : PlaybackControls {
        override val state = MutableStateFlow(PlaybackState())
        var plays = 0
        override fun connect() = Unit
        override fun release() = Unit
        override fun play(items: List<LibraryItem>, client: SpofifyClient, startTrackId: String?, shuffle: Boolean, startEntryId: String?) { plays++ }
        override fun toggle() = Unit
        override fun next() = Unit
        override fun previous() = Unit
        override fun select(index: Int) = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun toggleShuffle() = Unit
        override fun cycleRepeat() = Unit
        override fun stop() = Unit
    }
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun TestScope.until(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) { runCurrent(); Thread.sleep(5) }
        runCurrent()
        assertTrue("Timed out waiting for ViewModel state", condition())
    }

    @Test fun expiryDuringPendingLogoutDoesNotReenableSignInOrReplaceItsClient() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val logoutStarted = CountDownLatch(1)
        val releaseLogout = CountDownLatch(1)
        val web = MockWebServer()
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/auth/login" -> json("""{"accountId":"id","username":"listener","role":"user","csrfToken":"csrf"}""")
                    .setHeader("Set-Cookie", "spofify_session=secret; Path=/; HttpOnly; Max-Age=3600")
                "/api/auth/logout" -> {
                    logoutStarted.countDown(); releaseLogout.await(5, TimeUnit.SECONDS)
                    MockResponse().setResponseCode(204)
                }
                else -> json("""{"items":[],"total":0}""")
            }
        }
        web.start()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val graph = AppGraph(app, MemoryStore())
        val store = ViewModelStore()
        try {
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            client.login("listener", "password")
            val model = LibraryViewModel(app, graph, FakePlayback())
            store.put("subject", model)
            until { model.state.value.session != null && !model.state.value.loading }
            model.logout(); runCurrent()
            assertTrue(logoutStarted.await(5, TimeUnit.SECONDS))
            client.onUnauthorized!!.invoke()
            shadowOf(Looper.getMainLooper()).idle(); runCurrent()
            assertTrue(model.state.value.busy)
            assertNull(model.state.value.session)
            model.signIn("https://other.example.com", "other", "password")
            assertSame(client, graph.client)
            releaseLogout.countDown()
            until { !model.state.value.busy }
            assertNull(graph.client)
            assertNull(model.state.value.session)
        } finally {
            releaseLogout.countDown(); store.clear(); graph.clear(); web.shutdown(); Dispatchers.resetMain()
        }
    }

    @Test fun closingThePlayerCancelsAPreparingQueueAndPreventsLateAutoplay() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val queueStarted = CountDownLatch(1)
        val releaseQueue = CountDownLatch(1)
        val web = MockWebServer()
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/auth/login" -> json("""{"accountId":"id","username":"listener","role":"user","csrfToken":"csrf"}""")
                    .setHeader("Set-Cookie", "spofify_session=secret; Path=/; HttpOnly; Max-Age=3600")
                "/api/library/albums/album/playback" -> {
                    queueStarted.countDown(); releaseQueue.await(5, TimeUnit.SECONDS)
                    json("""{"items":[{"kind":"track","id":"song","name":"Song","hasAudio":true}],"total":1}""")
                }
                else -> json("""{"items":[],"total":0}""")
            }
        }
        web.start()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val graph = AppGraph(app, MemoryStore())
        val store = ViewModelStore()
        try {
            graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}")).login("listener", "password")
            val playback = FakePlayback()
            val model = LibraryViewModel(app, graph, playback)
            store.put("subject", model)
            until { model.state.value.session != null && !model.state.value.loading }
            model.play(LibraryItem("album", "album", "Album", coverage = Coverage(inLibrary = 1)))
            runCurrent()
            assertTrue(queueStarted.await(5, TimeUnit.SECONDS))
            model.stopPlayback(); runCurrent()
            releaseQueue.countDown()
            until { !model.state.value.preparing }
            assertEquals(0, playback.plays)
        } finally {
            releaseQueue.countDown(); store.clear(); graph.clear(); web.shutdown(); Dispatchers.resetMain()
        }
    }
}
