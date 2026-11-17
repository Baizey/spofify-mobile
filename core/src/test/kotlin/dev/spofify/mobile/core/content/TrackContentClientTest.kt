package dev.spofify.mobile.core.content

import dev.spofify.mobile.core.auth.*
import dev.spofify.mobile.core.http.OkHttpAuthApi
import dev.spofify.mobile.core.origin.ServerOrigin
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class TrackContentClientTest {
    private class Sessions(var value: StoredSession?) : SessionStore {
        override fun read() = value
        override fun write(value: StoredSession?) { this.value = value }
    }

    private suspend fun fixture(block: suspend (MockWebServer, SessionRepository, AccountIdentity) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            val origin = ServerOrigin.parse(server.url("/").toString())
            val summary = SessionSummary("account", "fixture-user", "user", "fixture-csrf")
            val cookie = "spofify_session=fixture-token; Path=/; HttpOnly; Expires=Tue, 01 Jan 2030 00:00:00 GMT"
            val client = OkHttpAuthApi.defaultClient()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val sessions = SessionRepository(Sessions(StoredSession(origin.value, cookie, summary)), object : LoginStore {
                override fun read(): RememberedLogin? = null
                override fun write(value: RememberedLogin) = Unit
            }, AuthApiFactory { OkHttpAuthApi(it, client) }, scope)
            server.enqueue(json("""{"accountId":"account","username":"fixture-user","role":"user","csrfToken":"fixture-csrf"}"""))
            try {
                sessions.start()
                val state = withTimeout(3000) { sessions.state.first { it is AuthState.Authenticated } } as AuthState.Authenticated
                server.takeRequest(2, TimeUnit.SECONDS)
                block(server, sessions, state.identity)
            } finally {
                scope.cancel()
                scope.coroutineContext.job.join()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdownNow()
            }
        }
    }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json; charset=utf-8").setBody(body)
    private fun waveform(id: String) = """{"version":1,"trackId":"$id","state":"unavailable","sourceGeneration":"","algorithmVersion":"peak-u8-two-pass-v1-half-up"}"""
    private fun lyrics(id: String) = """{"version":1,"trackId":"$id","recordingVersion":"recording","sourceGeneration":"source-disabled-v1","state":"unavailable","lines":[],"offlineTransferAllowed":false}"""

    @Test fun onlyDocumentedGetsEncodeOneTrackSegmentWithoutQueriesOrMutation() = runBlocking { fixture { server, sessions, identity ->
        val api = TrackContentClient(sessions, identity)
        assertTrue(api.isCurrent)
        assertTrue(api.active.first())
        val id = "track/%日本"
        server.enqueue(json(waveform(id)))
        server.enqueue(json(lyrics(id)))
        val wave = api.waveform(id)
        val text = api.lyrics(id)
        assertEquals(WaveformState.UNAVAILABLE, wave.state)
        assertEquals(LyricsState.UNAVAILABLE, text.state)
        for (resource in listOf("waveform", "lyrics")) {
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("GET", request.method)
            assertEquals(listOf("api", "library", "tracks", id, resource), request.requestUrl!!.pathSegments)
            assertNull(request.requestUrl!!.query)
            assertEquals("spofify_session=fixture-token", request.getHeader("Cookie"))
            assertEquals(identity.origin, request.getHeader("Origin"))
            assertEquals("no-store", request.getHeader("Cache-Control"))
            assertNull(request.getHeader("X-CSRF-Token"))
            assertFalse(request.path!!.contains("fixture-token"))
        }
        assertFalse(wave.toString().contains("fixture-token"))
        assertFalse(text.toString().contains("fixture-csrf"))
    } }

    @Test fun bodyCeilingsAndWholeDtosFailWithNoRawErrorsOrPaths() = runBlocking { fixture { server, sessions, identity ->
        val api = TrackContentClient(sessions, identity)
        val inputs = listOf(
            " ".repeat(16 * 1024 + 1) to true,
            " ".repeat(260 * 1024 + 1) to false,
            """{"error":"/private/operational/path fixture-token"}""" to true,
            lyrics("other") to false,
        )
        for ((body, wave) in inputs) {
            server.enqueue(json(body))
            val failure = runCatching { if (wave) api.waveform("one") else api.lyrics("one") }.exceptionOrNull()
            assertTrue(failure is TrackContentException)
            assertEquals(TrackContentFailure.INVALID_RESPONSE, (failure as TrackContentException).failure)
            assertEquals("Track content unavailable", failure.message)
            assertNull(failure.cause)
        }
    } }

    @Test fun missingAndUnavailableAreSanitizedAndNeverAutomaticallyRetried() = runBlocking { fixture { server, sessions, identity ->
        val api = TrackContentClient(sessions, identity)
        for ((status, expected) in listOf(404 to TrackContentFailure.NOT_FOUND, 503 to TrackContentFailure.UNAVAILABLE)) {
            server.enqueue(json("""{"error":"private upstream diagnostic"}""", status))
            val failure = runCatching { api.waveform("one") }.exceptionOrNull() as TrackContentException
            assertEquals(expected, failure.failure)
            assertEquals("Track content unavailable", failure.message)
        }
        assertEquals(3, server.requestCount) // one session validation, two reads
    } }

    @Test fun retiredAccountCannotDispatchAndUnauthorizedRetiresAuthority() = runBlocking { fixture { server, sessions, identity ->
        val retired = TrackContentClient(sessions, identity.copy(incarnation = identity.incarnation + 1))
        assertFalse(retired.isCurrent)
        assertTrue(runCatching { retired.lyrics("one") }.exceptionOrNull() is CancellationException)
        assertEquals(1, server.requestCount)
        val current = TrackContentClient(sessions, identity)
        server.enqueue(json("{}", 401))
        assertTrue(runCatching { current.waveform("one") }.isFailure)
        assertFalse(current.isCurrent)
        assertFalse(current.active.first())
        assertTrue(sessions.state.value is AuthState.SignedOut)
        assertTrue(runCatching { current.lyrics("one") }.exceptionOrNull() is CancellationException)
        assertEquals(2, server.requestCount)
    } }

    @Test fun physicalLyricResponsesPreserveCreditsAndNeverStartAcquisition() = runBlocking { fixture { server, sessions, identity ->
        val api = TrackContentClient(sessions, identity)
        val sidecar = """{"version":1,"trackId":"one","recordingVersion":"recording","sourceGeneration":"local-v1","state":"timed","provenance":"local_lrc","lines":[{"startMs":0,"text":"Saved lyrics"}],"attribution":{"name":"LRCLIB","url":"https://lrclib.net/","ownership":"provider"},"offlineTransferAllowed":false}"""
        val plain = sidecar.replace("\"state\":\"timed\"", "\"state\":\"plain\"")
            .replace("[{\"startMs\":0,\"text\":\"Saved lyrics\"}]", "[]").dropLast(1) + ",\"plainText\":\"Saved plain lyrics\"}"
        val missing = lyrics("one").replace("source-disabled-v1", "local-v1")
            .replace("\"state\":\"unavailable\"", "\"state\":\"not_found\"").dropLast(1) + ",\"reason\":\"no_match\"}"
        val awaiting = lyrics("one").dropLast(1) + ",\"reason\":\"awaiting_processing\"}"
        val cases = listOf(
            sidecar to LyricsState.TIMED,
            sidecar.replace("local_lrc", "embedded") to LyricsState.TIMED,
            (sidecar.dropLast(1) + ",\"storedAt\":\"2019-01-01T00:00:00Z\",\"expiresAt\":\"2020-01-01T00:00:00Z\"}") to LyricsState.TIMED,
            plain to LyricsState.PLAIN,
            missing to LyricsState.NOT_FOUND,
            awaiting to LyricsState.UNAVAILABLE,
        )
        for ((body, expected) in cases) {
            server.enqueue(json(body))
            val result = api.lyrics("one")
            assertEquals(expected, result.state)
            if (expected in setOf(LyricsState.TIMED, LyricsState.PLAIN)) {
                assertEquals("LRCLIB", result.attribution?.name)
                assertFalse(result.offlineTransferAllowed)
            }
            if (expected == LyricsState.PLAIN) assertEquals("Saved plain lyrics", result.plainText)
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("GET", request.method)
            assertEquals("/api/library/tracks/one/lyrics", request.path)
            assertNull(request.requestUrl!!.query)
            assertNull(request.getHeader("X-CSRF-Token"))
        }
        assertEquals(1 + cases.size, server.requestCount)
    } }
}
