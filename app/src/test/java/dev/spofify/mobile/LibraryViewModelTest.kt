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
        var enqueues = 0
        val contexts = mutableListOf<LibraryItem?>()
        val listActions = mutableListOf<PlaybackListAction>()
        val actionResults = ArrayDeque<(PlaybackActionResult) -> Unit>()
        val actionCurrents = ArrayDeque<() -> Boolean>()
        override fun queueIntent(selection: LibraryPlaybackSelection, client: SpofifyClient, action: PlaybackListAction,
            expectedKey: PlaybackInteractionKey?, expectedBinding: dev.spofify.mobile.feature.devices.LibraryPlaybackActionBinding?, shuffle: Boolean?, isCurrent: () -> Boolean,
            onResult: (PlaybackActionResult) -> Unit) {
            listActions += action; actionResults.addLast(onResult); actionCurrents.addLast(isCurrent)
        }
        override fun connect() = Unit
        override fun release() = Unit

        override fun setContext(context: LibraryItem?) { contexts += context }
        override fun toggle() = Unit
        override fun next() = Unit
        override fun previous() = Unit
        override fun select(index: Int) = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun toggleShuffle() = Unit
        override fun cycleRepeat() = Unit
        override fun stop() { PlaybackRequests.invalidate() }
    }
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun TestScope.until(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) { runCurrent(); Thread.sleep(5) }
        runCurrent()
        assertTrue("Timed out waiting for ViewModel state", condition())
    }

    @Test fun expiryRetiresPrivateProfileRoutesAndIgnoresLateNavigationBeforeNewSignIn() =
        profileSessionRetirement(expire = true)

    @Test fun accountReplacementCannotRestorePreviousAccountsCreditsStack() =
        profileSessionRetirement(expire = false)

    private fun profileSessionRetirement(expire: Boolean) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val web = MockWebServer()
        var account = "first-account"
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/api/auth/login" -> json("""{"accountId":"$account","username":"$account","role":"user","csrfToken":"csrf"}""")
                    .setHeader("Set-Cookie", "spofify_session=synthetic; Path=/; HttpOnly; Max-Age=3600")
                else -> json("""{"items":[],"total":0}""")
            }
        }
        web.start()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val logins = object : LoginStore {
            private var value: RememberedLogin? = null
            override fun read() = value
            override fun write(value: RememberedLogin?) { this.value = value }
        }
        val graph = AppGraph(app, MemoryStore(), logins)
        val store = ViewModelStore()
        try {
            val origin = "http://127.0.0.1:${web.port}"
            val retired = graph.configure(ServerAddress.parse(origin))
            retired.login("first-account", "synthetic-password")
            val model = LibraryViewModel(app, graph, FakePlayback())
            store.put("subject", model)
            until { model.state.value.session != null && !model.state.value.busy }
            model.switchTab(Tab.SETTINGS)
            assertEquals(Page.Settings, model.state.value.page)
            assertFalse(model.state.value.canGoBack)
            model.open(Page.Profile)
            assertEquals(Page.Profile, model.state.value.page)
            assertTrue(model.state.value.canGoBack)
            model.back()
            assertEquals(Page.Settings, model.state.value.page)
            model.open(Page.Credits)
            assertTrue(model.state.value.canGoBack)
            model.switchTab(Tab.PLAYLISTS)
            model.switchTab(Tab.SETTINGS)
            assertEquals(Page.Credits, model.state.value.page)
            if (expire) {
                retired.clearSession()
                retired.onUnauthorized!!.invoke()
                shadowOf(Looper.getMainLooper()).idle()
                until { model.state.value.session == null }
                assertEquals(Page.Home, model.state.value.page)
                assertFalse(model.state.value.canGoBack)
                assertNull(model.state.value.searchActivationId)
                val signedOut = model.state.value
                // Stale callbacks cannot re-expose Credits/identity or rebuild private stacks.
                model.switchTab(Tab.SETTINGS)
                model.open(Page.Profile)
                model.open(Page.Credits)
                model.back()
                assertEquals(signedOut, model.state.value)
            }
            account = "replacement-account"
            model.signIn(origin, account, "synthetic-password")
            until { model.state.value.session?.accountId == account && !model.state.value.busy }
            assertNotSame(retired, graph.client)
            assertEquals(Page.Home, model.state.value.page)
            model.switchTab(Tab.SETTINGS)
            assertEquals(Page.Settings, model.state.value.page)
            assertFalse(model.state.value.canGoBack)
            model.back()
            assertEquals(Page.Settings, model.state.value.page)
            model.open(Page.Profile)
            assertEquals(Page.Profile, model.state.value.page)
            assertTrue(model.state.value.canGoBack)
            assertEquals(account, model.state.value.session?.username)
            model.back()
            assertEquals(Page.Settings, model.state.value.page)
        } finally {
            store.clear(); graph.clear(); web.shutdown(); Dispatchers.resetMain()
        }
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

}
