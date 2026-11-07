package dev.spofify.mobile

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import dev.spofify.mobile.core.*
import dev.spofify.mobile.playback.*
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RollingReconnectViewModelTest {
    private class Sessions : SessionStore {
        @Volatile var value: StoredSession? = null
        override fun read() = value
        override fun write(value: StoredSession?) { this.value = value }
    }
    private class Output : PlaybackControls {
        override val state = MutableStateFlow(PlaybackState())
        var connects = 0
        var stops = 0
        var plays = 0
        override fun connect() { connects++ }
        override fun release() = Unit
        override fun play(items: List<LibraryItem>, client: SpofifyClient, startTrackId: String?, shuffle: Boolean, startEntryId: String?) { plays++ }
        override fun toggle() = Unit
        override fun next() = Unit
        override fun previous() = Unit
        override fun select(index: Int) = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun toggleShuffle() = Unit
        override fun cycleRepeat() = Unit
        override fun stop() { stops++; PlaybackRequests.invalidate() }
    }
    private fun TestScope.until(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) { runCurrent(); Thread.yield() }
        runCurrent()
        assertTrue("Timed out waiting for the controlled ViewModel boundary", condition())
    }

    @Test fun oneLiveViewModelExposesPasswordFreeRetryAfterDormancyAndRoutesReal401ToLogin() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val day = TimeUnit.DAYS.toMillis(1)
        val now = AtomicLong(System.currentTimeMillis() / 1000 * 1000)
        val serverDeadline = AtomicLong(now.get() + 30 * day)
        val revoked = AtomicBoolean(false)
        val loginPosts = AtomicInteger(); val probes = AtomicInteger(); val logouts = AtomicInteger()
        val summary = """{"accountId":"id","username":"listener","role":"user","csrfToken":"csrf"}"""
        fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
        fun rolling(body: String) = json(body).setHeader("X-Spofify-Session-Mode", "rolling-v1")
            .setHeader("Set-Cookie", "spofify_session=synthetic-cookie; Path=/; HttpOnly; Expires=" +
                DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(serverDeadline.get()).atZone(ZoneOffset.UTC)))
        val web = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/api/auth/login" -> { loginPosts.incrementAndGet(); rolling(summary.dropLast(1) + ",\"sessionMode\":\"rolling-v1\"}") }
                    "/api/auth/session" -> { probes.incrementAndGet(); if (revoked.get()) json("{}", 401) else rolling(summary) }
                    "/api/auth/logout" -> { logouts.incrementAndGet(); MockResponse().setResponseCode(204) }
                    else -> json("""{"items":[],"total":0,"limit":40,"offset":0}""")
                }
            }
            start()
        }
        val app = ApplicationProvider.getApplicationContext<Application>()
        val sessions = Sessions()
        val logins = object : LoginStore {
            override fun read(): RememberedLogin? = null
            override fun write(value: RememberedLogin?) { error("Reconnect must never save/replay a password") }
        }
        val graph = AppGraph(app, sessions, logins, authenticationTimeMillis = now::get)
        val modelStore = ViewModelStore()
        val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
        try {
            val original = client.login("listener", "explicit-only-password")
            val output = Output()
            val model = LibraryViewModel(app, graph, output)
            modelStore.put("same-subject", model)
            until { model.state.value.session === original && !model.state.value.loading }
            val saved = sessions.value
            assertNotNull(graph.audioCache(client, original))
            val localDeadline = serverDeadline.get()
            now.set(localDeadline + 1)
            // A previously lost touch reply can make the server deadline newer
            // than this local record. Only the explicit probe may establish that.
            serverDeadline.set(now.get() + 30 * day)
            assertFalse(client.hasValidSession(original))
            shadowOf(Looper.getMainLooper()).idle()
            until { model.state.value.session == null && model.state.value.canRetryConnection && !model.state.value.busy }
            assertSame(client, graph.client)
            assertTrue(graph.sessionNeedsReconnect.value)
            assertNull(client.validatedSessionIdentity()); assertNull(graph.audioCache(client, original))
            assertSame(saved, sessions.value); assertTrue(output.stops > 0)
            assertEquals(0, probes.get()); assertEquals(0, output.plays)
            // MainActivity already wires SignIn's canRetryConnection button to
            // this action; no screen recreation, logout or password submit.
            model.reconnect()
            until { model.state.value.session != null && !model.state.value.loading && !model.state.value.busy }
            assertEquals(1, probes.get()); assertEquals(1, loginPosts.get()); assertEquals(0, logouts.get())
            assertFalse(model.state.value.canRetryConnection); assertFalse(graph.sessionNeedsReconnect.value)
            val restored = requireNotNull(model.state.value.session)
            assertTrue(client.hasValidSession(restored)); assertSame(client, graph.client)
            assertEquals(0, output.plays)
            // Same existing VM, now with definitive server revocation/idle expiry.
            now.set(serverDeadline.get() + 1); revoked.set(true)
            assertFalse(client.hasValidSession(restored))
            shadowOf(Looper.getMainLooper()).idle()
            until { model.state.value.canRetryConnection && model.state.value.session == null }
            model.reconnect()
            until { probes.get() == 2 && !model.state.value.busy }
            shadowOf(Looper.getMainLooper()).idle(); runCurrent()
            assertNull(model.state.value.session); assertFalse(model.state.value.canRetryConnection)
            assertTrue(model.state.value.error.orEmpty().contains("Sign in again"))
            assertNull(sessions.value); assertNull(client.validatedSessionIdentity())
            assertFalse(graph.sessionNeedsReconnect.value)
            assertEquals(1, loginPosts.get()); assertEquals(0, logouts.get()); assertEquals(0, output.plays)
        } finally {
            modelStore.clear(); graph.clear()
            client.httpClient.dispatcher.cancelAll(); client.httpClient.connectionPool.evictAll(); client.httpClient.dispatcher.executorService.shutdown()
            web.shutdown(); Dispatchers.resetMain()
        }
    }
}
