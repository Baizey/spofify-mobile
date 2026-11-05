package dev.spofify.mobile

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import dev.spofify.mobile.core.*
import dev.spofify.mobile.playback.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
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

/** Authentication publication and persistence regressions; no Compose/form behavior lives here. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LoginAuthenticationTest {
    private class Sessions : SessionStore {
        var value: StoredSession? = null
        override fun read() = value
        override fun write(value: StoredSession?) { this.value = value }
    }
    private class Logins : LoginStore {
        var value: RememberedLogin? = null
        var failSave = false
        var successfulWrites = 0
        override fun read() = value
        override fun write(value: RememberedLogin?) {
            if (value != null && failSave) throw IllegalStateException("Synthetic encrypted storage failure")
            this.value = value
            successfulWrites++
        }
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
        override fun stop() { stops++ }
    }
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun login(username: String = "listener") =
        json("""{"accountId":"$username-id","username":"$username","role":"user","csrfToken":"synthetic-csrf"}""")
            .setHeader("Set-Cookie", "spofify_session=synthetic-cookie; Path=/; HttpOnly; Max-Age=3600")

    private inner class Subject {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val sessions = Sessions()
        val logins = Logins()
        val graph = AppGraph(app, sessions, logins)
        val output = Output()
        val models = ViewModelStore()
        private var modelCount = 0
        private val servers = mutableListOf<MockWebServer>()
        fun server(loginResponse: () -> MockResponse = { login() }): MockWebServer = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path?.substringBefore('?')) {
                    "/api/auth/login" -> loginResponse()
                    "/api/auth/logout" -> MockResponse().setResponseCode(204)
                    "/api/library" -> MockResponse().setResponseCode(401)
                    else -> json("""{"items":[],"total":0}""")
                }
            }
            start()
            servers += this
        }
        fun model() = LibraryViewModel(app, graph, output).also { models.put("subject-${modelCount++}", it) }
        fun close() {
            models.clear()
            graph.clear()
            servers.forEach { it.shutdown() }
        }
    }
    private fun origin(web: MockWebServer) = "http://127.0.0.1:${web.port}"
    private fun subjectTest(body: suspend TestScope.(Subject) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val subject = Subject()
            try { body(subject) } finally { subject.close() }
        } finally { Dispatchers.resetMain() }
    }
    private fun awaitWorker(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("Timed out waiting for the HTTP worker without dispatching its continuation", condition())
    }
    private fun TestScope.until(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) { runCurrent(); Thread.sleep(5) }
        runCurrent()
        assertTrue("Timed out waiting for authentication state", condition())
    }

    @Test fun latestSuccessfulTripleWinsOverALaterFailedOriginHintWithoutSubmittingItsPassword() = subjectTest { h ->
        val good = h.server { login("latest-listener") }
        val bad = h.server { MockResponse().setResponseCode(401) }
        val current = h.graph.configure(ServerAddress.parse(origin(good)))
        current.login("latest-listener", " latest synthetic password \t")
        h.graph.saveLogin(current, "latest-listener", " latest synthetic password \t")
        val latest = h.logins.value!!
        val rejected = h.graph.configure(ServerAddress.parse(origin(bad)))
        try { rejected.login("rejected", "incorrect-synthetic-password"); fail("Expected rejection") }
        catch (_: IOException) { }
        assertEquals(origin(bad), h.graph.lastServer)

        val model = h.model()
        // First publication must already pair the saved origin with its own credentials.
        assertEquals(latest.server, model.state.value.server)
        assertSame(latest, model.rememberedLogin)
        runCurrent()
        assertEquals(latest.server, model.state.value.server)
        assertEquals("latest-listener", model.rememberedLogin!!.username)
        assertEquals(" latest synthetic password \t", model.rememberedLogin!!.password)
        assertNull(model.state.value.session)
        assertFalse(model.state.value.busy)
        assertEquals(1, good.requestCount)
        assertEquals(1, bad.requestCount)
        assertEquals(0, h.output.connects)
        assertEquals(0, h.output.plays)
    }

    @Test fun credentialOnlyRestorationIsAutofillNotAnAutomaticPasswordLogin() = subjectTest { h ->
        val web = h.server()
        val saved = RememberedLogin(origin(web), "saved-listener", "saved-synthetic-password")
        h.logins.value = saved
        val model = h.model()
        runCurrent()
        assertEquals(saved.server, model.state.value.server)
        assertSame(saved, model.rememberedLogin)
        assertTrue(model.state.value.hasSavedLogin)
        assertNull(model.state.value.session)
        assertNull(h.graph.client)
        assertEquals(0, web.requestCount)
        assertEquals(0, h.output.connects)
        assertEquals(0, h.output.plays)
        assertEquals(0, h.logins.successfulWrites)
    }

    @Test fun restoredAndAlreadyCurrentSessionsPublishTheirOwnOriginNotTheRememberedLoginOrigin() = subjectTest { h ->
        val web = h.server()
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.method == "GET" && request.path == "/api/auth/session")
                    login("runtime-listener").removeHeader("Set-Cookie")
                else MockResponse().setResponseCode(405)
        }
        val saved = RememberedLogin("https://saved.example", "saved-listener", "saved-synthetic-password")
        h.logins.value = saved
        val client = h.graph.configure(ServerAddress.parse(origin(web)))
        h.sessions.value = StoredSession(origin(web),
            "spofify_session=synthetic-cookie; Path=/; HttpOnly; Max-Age=3600",
            Session("runtime-listener-id", "runtime-listener", "user", "synthetic-csrf"))
        val model = h.model()
        // Before validation, the credential-only form still gets the entire saved A identity.
        assertEquals(saved.server, model.state.value.server)
        assertNull(model.state.value.session)
        assertSame(saved, model.rememberedLogin)
        until { model.state.value.session != null && !model.state.value.busy }
        assertEquals("runtime-listener", model.state.value.session!!.username)
        assertEquals(origin(web), model.state.value.server)
        assertSame(client.currentSession(), model.state.value.session)
        assertSame(saved, model.rememberedLogin)
        assertSame(saved, h.logins.value)
        assertEquals(0, h.logins.successfulWrites)
        assertEquals(1, h.output.connects)
        assertEquals(0, h.output.plays)

        // Reattaching with B already current must also publish B, without revalidation or login.
        val reattached = h.model()
        until { reattached.state.value.session != null && !reattached.state.value.busy }
        assertEquals(origin(web), reattached.state.value.server)
        assertEquals("runtime-listener", reattached.state.value.session!!.username)
        assertSame(client.currentSession(), reattached.state.value.session)
        assertSame(saved, reattached.rememberedLogin)
        assertSame(saved, h.logins.value)
        assertEquals(0, h.logins.successfulWrites)
        assertEquals(0, h.output.plays)
        assertEquals(1, web.requestCount)
        val validation = web.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("GET", validation.method)
        assertEquals("/api/auth/session", validation.path)
        assertEquals(0L, validation.bodySize)
    }

    @Test fun successfulAuthUsesTheAuthenticatedUsernameAndPreservesRawPasswordWithoutOptIn() = subjectTest { h ->
        val web = h.server { login("canonical-listener") }
        val model = h.model()
        runCurrent()
        model.signIn(origin(web), " typed-listener ", " \tsynthetic password \n")
        until { model.state.value.session != null && !model.state.value.busy }
        assertEquals("canonical-listener", model.state.value.session!!.username)
        assertEquals("canonical-listener", h.logins.value!!.username)
        assertEquals(" \tsynthetic password \n", h.logins.value!!.password)
        assertEquals(origin(web), h.logins.value!!.server)
        assertEquals(1, h.logins.successfulWrites)
        assertSame(h.logins.value, model.rememberedLogin)
        assertEquals(1, web.requestCount)
        assertEquals(1, h.output.connects)
        assertEquals(0, h.output.plays)
    }

    @Test fun compatibilityForgetClearsTheRecordButNotAutomaticRememberingInTheViewModel() = subjectTest { h ->
        val web = h.server()
        h.logins.value = RememberedLogin(origin(web), "previous-listener", "previous-synthetic-password")
        val model = h.model()
        runCurrent()
        model.setRememberLogin(false)
        assertTrue(model.state.value.rememberLogin)
        assertFalse(model.state.value.hasSavedLogin)
        assertNull(model.rememberedLogin)
        assertNull(h.logins.value)
        model.signIn(origin(web), "listener", "next-synthetic-password")
        until { model.state.value.session != null && !model.state.value.busy }
        assertTrue(model.state.value.rememberLogin)
        assertTrue(model.state.value.hasSavedLogin)
        assertEquals("next-synthetic-password", h.logins.value!!.password)
        assertSame(h.logins.value, model.rememberedLogin)
        assertEquals(1, web.requestCount)
    }

    @Test fun saveFailureKeepsTheNewSessionAndThePreviousDurableCredentialsDistinct() = subjectTest { h ->
        val web = h.server { login("new-listener") }
        val previous = RememberedLogin("https://previous.example", "previous-listener", "previous-synthetic-password")
        h.logins.value = previous
        h.logins.failSave = true
        val model = h.model()
        runCurrent()
        model.signIn(origin(web), "new-listener", "new-synthetic-password")
        until { model.state.value.session != null && !model.state.value.busy }
        assertEquals("new-listener", model.state.value.session!!.username)
        assertEquals(origin(web), model.state.value.server)
        assertSame(model.state.value.session, h.graph.client!!.currentSession())
        assertEquals(origin(web), h.sessions.value!!.server)
        assertEquals("new-listener", h.sessions.value!!.session.username)
        assertSame(previous, h.logins.value)
        assertSame(previous, h.graph.rememberedLogin())
        assertSame(previous, model.rememberedLogin)
        assertTrue(model.state.value.hasSavedLogin)
        assertNull(model.state.value.error)
        assertTrue(model.state.value.notice.orEmpty().contains("Could not save sign-in details securely"))
        assertFalse(model.state.value.notice.orEmpty().contains("new-synthetic-password"))
        assertEquals(0, h.logins.successfulWrites)
        assertEquals(1, h.output.connects)
        assertEquals(0, h.output.plays)
    }

    @Test fun partialAuthenticationNeverPublishesASessionOrReplacesTheSavedLogin() = subjectTest { h ->
        val web = h.server { login().removeHeader("Set-Cookie") }
        val previous = RememberedLogin("https://previous.example", "previous-listener", "previous-synthetic-password")
        h.logins.value = previous
        val model = h.model()
        runCurrent()
        model.signIn(origin(web), "listener", "new-synthetic-password")
        until { !model.state.value.busy && model.state.value.error != null }
        assertNull(model.state.value.session)
        assertNull(h.graph.client!!.currentSession())
        assertNull(h.sessions.value)
        assertSame(previous, h.logins.value)
        assertSame(previous, model.rememberedLogin)
        assertEquals(0, h.logins.successfulWrites)
        assertEquals(0, h.output.connects)
        assertEquals(0, h.output.plays)
    }

    @Test fun logoutRetainsTheLatestSuccessfulRecordForTheNextExplicitLogin() = subjectTest { h ->
        val web = h.server()
        h.logins.value = RememberedLogin("https://previous.example", "previous-listener", "previous-synthetic-password")
        val model = h.model()
        runCurrent()
        model.signIn(origin(web), "listener", "latest-synthetic-password")
        until { model.state.value.session != null && !model.state.value.busy }
        val latest = h.logins.value!!
        model.logout()
        until { model.state.value.session == null && !model.state.value.busy }
        assertNull(h.sessions.value)
        assertNull(h.graph.client)
        assertSame(latest, h.logins.value)
        assertSame(latest, model.rememberedLogin)
        assertEquals(latest.server, model.state.value.server)
        assertTrue(model.state.value.hasSavedLogin)
        val replacement = h.model()
        runCurrent()
        assertSame(latest, replacement.rememberedLogin)
        assertEquals(latest.server, replacement.state.value.server)
        assertNull(replacement.state.value.session)
        assertEquals(2, web.requestCount) // One explicit login and one logout, never a password restore.
        assertEquals(1, h.output.connects)
        assertEquals(0, h.output.plays)
    }

    @Test fun expiryRetainsTheLatestSuccessfulRecordWithoutPasswordResubmission() = subjectTest { h ->
        val web = h.server()
        val model = h.model()
        runCurrent()
        model.signIn(origin(web), "listener", "latest-synthetic-password")
        until { model.state.value.session != null && !model.state.value.busy }
        val latest = h.logins.value!!
        try { h.graph.client!!.library(); fail("Expected synthetic expiry") }
        catch (_: IOException) { }
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        assertNull(model.state.value.session)
        assertNull(h.sessions.value)
        assertSame(latest, h.logins.value)
        assertSame(latest, model.rememberedLogin)
        assertTrue(model.state.value.hasSavedLogin)
        assertTrue(model.state.value.error.orEmpty().contains("expired"))
        assertTrue(h.output.stops >= 2) // Sign-in invalidation and expiry both stop locally owned output.
        val replacement = h.model()
        runCurrent()
        assertSame(latest, replacement.rememberedLogin)
        assertNull(replacement.state.value.session)
        assertEquals(2, web.requestCount)
        assertEquals(1, h.output.connects)
        assertEquals(0, h.output.plays)
    }

    private fun retiredCompletion(success: Boolean) = subjectTest { h ->
        val oldServer = h.server { if (success) login() else MockResponse().setResponseCode(401) }
        val newServer = h.server { login("new-listener") }
        val model = h.model()
        runCurrent()
        model.signIn(origin(oldServer), "listener", "old-synthetic-password")
        runCurrent()
        val oldClient = h.graph.client!!
        // Finish the HTTP callback, but hold its already queued ViewModel continuation.
        awaitWorker { oldServer.requestCount == 1 && oldClient.httpClient.dispatcher.runningCallsCount() == 0 &&
            (!success || oldClient.currentSession() != null) }
        val before = model.state.value
        assertTrue(before.busy)
        val newClient = h.graph.configure(ServerAddress.parse(origin(newServer)))
        // Blocking here intentionally avoids dispatching the old Main continuation.
        val newSession = runBlocking { newClient.login("new-listener", "new-synthetic-password") }
        h.graph.saveLogin(newClient, "new-listener", "new-synthetic-password")
        val latest = h.logins.value
        val epoch = PlaybackRequests.epoch
        val stops = h.output.stops
        runCurrent()
        assertEquals("A retired completion must not publish even busy/error changes", before, model.state.value)
        assertSame(newClient, h.graph.client)
        assertSame(newSession, newClient.currentSession())
        assertSame(latest, h.logins.value)
        assertEquals(origin(newServer), h.sessions.value!!.server)
        assertEquals(0, h.output.connects)
        assertEquals(stops, h.output.stops)
        assertEquals(0, h.output.plays)
        assertEquals(epoch, PlaybackRequests.epoch)
    }

    @Test fun delayedPriorClientSuccessCannotPublishOrOverwriteTheNewerSession() = retiredCompletion(success = true)
    @Test fun delayedPriorClientFailureCannotPublishBusyErrorOrPlaybackChanges() = retiredCompletion(success = false)

    @Test fun anEqualButNewerSessionOnTheSameClientFencesAnAlreadyQueuedAuthenticationSuccess() = subjectTest { h ->
        val web = h.server()
        val model = h.model()
        runCurrent()
        model.signIn(origin(web), "listener", "old-synthetic-password")
        runCurrent()
        val client = h.graph.client!!
        awaitWorker { client.currentSession() != null && client.httpClient.dispatcher.runningCallsCount() == 0 }
        val before = model.state.value
        val oldSession = client.currentSession()!!
        val newer = runBlocking { client.login("listener", "new-synthetic-password") }
        assertEquals(oldSession, newer)
        assertNotSame(oldSession, newer)
        h.graph.saveLogin(client, "listener", "new-synthetic-password", newer)
        val latest = h.logins.value
        runCurrent()
        assertEquals(before, model.state.value)
        assertSame(newer, client.currentSession())
        assertSame(latest, h.logins.value)
        assertEquals("listener", h.sessions.value!!.session.username)
        assertEquals("new-synthetic-password", latest!!.password)
        assertEquals(0, h.output.connects)
        assertEquals(0, h.output.plays)
    }

    @Test fun disposingTheViewModelFencesAnAlreadyQueuedSuccessWithoutReplacingDurableCredentials() = subjectTest { h ->
        val web = h.server()
        val previous = RememberedLogin("https://previous.example", "previous-listener", "previous-synthetic-password")
        h.logins.value = previous
        val model = h.model()
        runCurrent()
        model.signIn(origin(web), "listener", "new-synthetic-password")
        runCurrent()
        val client = h.graph.client!!
        awaitWorker { client.currentSession() != null && client.httpClient.dispatcher.runningCallsCount() == 0 }
        val before = model.state.value
        h.models.clear()
        runCurrent()
        assertEquals(before, model.state.value)
        assertSame(previous, h.logins.value)
        assertEquals(0, h.logins.successfulWrites)
        assertEquals(0, h.output.connects)
        assertEquals(0, h.output.plays)
    }
}
