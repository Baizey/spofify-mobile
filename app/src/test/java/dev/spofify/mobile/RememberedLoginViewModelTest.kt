package dev.spofify.mobile

import dev.spofify.mobile.feature.devices.LibraryPlaybackActionBinding
import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import dev.spofify.mobile.core.*
import dev.spofify.mobile.playback.*
import java.util.concurrent.TimeUnit
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
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RememberedLoginViewModelTest {
    private class Sessions : SessionStore {
        var value: StoredSession? = null
        override fun read() = value
        override fun write(value: StoredSession?) { this.value = value }
    }
    private class Logins : LoginStore {
        var value: RememberedLogin? = null
        var failSave = false
        override fun read() = value
        override fun write(value: RememberedLogin?) {
            if (value != null && failSave) throw IllegalStateException("Synthetic encrypted storage failure")
            this.value = value
        }
    }
    private class Output : PlaybackControls {
        override val state = MutableStateFlow(PlaybackState())
        var connects = 0
        override fun connect() { connects++ }
        override fun release() = Unit
        override fun queueIntent(selection: LibraryPlaybackSelection, client: SpofifyClient,
            action: PlaybackListAction, expectedKey: PlaybackInteractionKey?, expectedBinding: LibraryPlaybackActionBinding?,
            shuffle: Boolean?, isCurrent: () -> Boolean, onResult: (PlaybackActionResult) -> Unit) {
            onResult(PlaybackActionResult(PlaybackActionStatus.UNSUPPORTED))
        }
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
    private fun server(loginSucceeds: Boolean = true) = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/auth/login" -> if (loginSucceeds) json("""{"accountId":"id","username":"listener","role":"user","csrfToken":"csrf"}""")
                    .setHeader("Set-Cookie", "spofify_session=synthetic-cookie; Path=/; HttpOnly; Max-Age=3600")
                    else json("""{"error":"invalid credentials"}""").setResponseCode(401)
                else -> json("""{"items":[],"total":0}""")
            }
        }
        start()
    }
    private fun awaitWorker(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("Timed out waiting for HTTP worker", condition())
    }
    private fun TestScope.until(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) { runCurrent(); Thread.sleep(5) }
        runCurrent()
        assertTrue("Timed out waiting for sign-in", condition())
    }

    @Test fun aRetiredLoginContinuationCannotPublishItsSessionOrConnectPlayback() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val web = server()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val logins = Logins()
        val graph = AppGraph(app, Sessions(), logins)
        val output = Output()
        val store = ViewModelStore()
        try {
            val model = LibraryViewModel(app, graph, output)
            store.put("subject", model); runCurrent()
            model.signIn("http://127.0.0.1:${web.port}", "listener", "synthetic-password")
            runCurrent()
            val old = graph.client!!
            // Let the HTTP worker finish, but deliberately do not dispatch the ViewModel continuation.
            awaitWorker { old.currentSession() != null && old.httpClient.dispatcher.runningCallsCount() == 0 }
            val before = model.state.value
            graph.configure(ServerAddress.parse("https://other.example.com"))
            runCurrent()
            assertEquals("A retired continuation must not publish busy/error changes", before, model.state.value)
            assertNull(model.state.value.session)
            assertTrue(model.state.value.busy)
            assertEquals(0, output.connects)
            assertNull(logins.value)
            assertEquals("https://other.example.com", graph.client!!.server.origin)
        } finally { store.clear(); graph.clear(); web.shutdown(); Dispatchers.resetMain() }
    }

    @Test fun aCredentialStorageFailureDoesNotDiscardAnOtherwiseCurrentSuccessfulLogin() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val web = server()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val logins = Logins().apply { failSave = true }
        val graph = AppGraph(app, Sessions(), logins)
        val output = Output()
        val store = ViewModelStore()
        try {
            graph.setRememberLogin(true)
            val model = LibraryViewModel(app, graph, output)
            store.put("subject", model); runCurrent()
            model.signIn("http://127.0.0.1:${web.port}", "listener", "synthetic-password")
            until { !model.state.value.busy && model.state.value.session != null }
            assertNotNull(graph.client!!.currentSession())
            assertEquals(1, output.connects)
            assertNull(logins.value)
            assertFalse(model.state.value.hasSavedLogin)
            assertTrue(model.state.value.notice.orEmpty().contains("Could not save sign-in details securely"))
        } finally { store.clear(); graph.clear(); web.shutdown(); Dispatchers.resetMain() }
    }

    @Test fun failedAuthenticationNeverReplacesPreviouslyRememberedCredentials() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val web = server(loginSucceeds = false)
        val app = ApplicationProvider.getApplicationContext<Application>()
        val logins = Logins().apply { value = RememberedLogin("http://127.0.0.1:${web.port}", "previous", "previous-password") }
        val graph = AppGraph(app, Sessions(), logins)
        val output = Output()
        val store = ViewModelStore()
        try {
            graph.setRememberLogin(true)
            val previous = logins.value
            val model = LibraryViewModel(app, graph, output)
            store.put("subject", model); runCurrent()
            model.signIn("http://127.0.0.1:${web.port}", "listener", "incorrect-password")
            until { !model.state.value.busy && model.state.value.error != null }
            assertNull(model.state.value.session)
            assertSame(previous, logins.value)
            assertEquals(0, output.connects)
        } finally { store.clear(); graph.clear(); web.shutdown(); Dispatchers.resetMain() }
    }
}
