package dev.spofify.mobile.core.http

import dev.spofify.mobile.core.auth.AuthFailure
import dev.spofify.mobile.core.origin.ServerOrigin
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.EventListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class OkHttpAuthApiTest {
    private val summary = """{"accountId":"fixture-id","username":"fixture-user","role":"user","csrfToken":"fixture-csrf"}"""
    private fun response() = MockResponse().setHeader("Content-Type", "application/json").setBody(summary)
    private fun cookie() = "spofify_session=${UUID.randomUUID()}; Path=/; HttpOnly; Max-Age=3600; Expires=Tue, 01 Jan 2030 00:00:00 GMT"

    @Test fun exactLoginJsonHeadersAndCookieNegotiation() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(response().setHeader("Set-Cookie", cookie()).setHeader("X-Spofify-Session-Mode", "rolling-v1"))
            val api = OkHttpAuthApi(ServerOrigin.parse(server.url("/").toString()))
            val secret = UUID.randomUUID().toString() + " \t"
            val reply = api.login("fixture-user", secret)
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("POST", request.method)
            assertEquals("/api/auth/login", request.path)
            assertEquals(api.origin.value, request.getHeader("Origin"))
            assertEquals("1", request.getHeader("X-Spofify-Login"))
            assertEquals("rolling-v1", request.getHeader("X-Spofify-Session-Mode"))
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("Cookie"))
            assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals(setOf("username", "password"), body.keys)
            assertEquals("fixture-user", body.getValue("username").jsonPrimitive.content)
            assertEquals(secret, body.getValue("password").jsonPrimitive.content)
            assertEquals("fixture-id", reply.session.accountId)
            assertEquals("rolling-v1", reply.mode)
            assertTrue(reply.cookie!!.contains("expires=", ignoreCase = true))
            assertFalse(reply.cookie!!.contains("Max-Age", ignoreCase = true))
            api.cancel()
        }
    }

    @Test fun sessionAndLogoutUseCookieAndExactCsrfWithNoRenewalOnLogout() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(response())
            server.enqueue(MockResponse().setResponseCode(204).setHeader("Set-Cookie", cookie())
                .setHeader("X-Spofify-Session-Mode", "rolling-v1"))
            val api = OkHttpAuthApi(ServerOrigin.parse(server.url("/").toString()))
            val credential = cookie()
            val reply = api.session(credential)
            assertNull(reply.cookie)
            assertNull(reply.mode)
            api.logout(credential, "fixture-csrf")
            val get = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("GET", get.method)
            assertEquals("/api/auth/session", get.path)
            assertTrue(get.getHeader("Cookie")!!.startsWith("spofify_session="))
            assertFalse(get.getHeader("Cookie")!!.contains(';'))
            assertNull(get.getHeader("X-CSRF-Token"))
            val logout = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("POST", logout.method)
            assertEquals("/api/auth/logout", logout.path)
            assertEquals(api.origin.value, logout.getHeader("Origin"))
            assertEquals("fixture-csrf", logout.getHeader("X-CSRF-Token"))
            assertNull(logout.getHeader("X-Spofify-Session-Mode"))
        }
    }

    @Test fun redirectsAnd503DoNotReplayLogin() = runBlocking {
        for (status in listOf(302, 307, 408, 503)) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse().setResponseCode(status)
                    .setHeader("Location", server.url("/other")).setHeader("Retry-After", "0"))
                val api = OkHttpAuthApi(ServerOrigin.parse(server.url("/").toString()))
                try { api.login("fixture-user", UUID.randomUUID().toString()); fail("Expected rejection") }
                catch (failure: AuthFailure) { assertEquals(AuthFailure.Kind.UNAVAILABLE, failure.kind) }
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun invalidOrMissingCookieAndInvalidJsonCannotAuthenticate() = runBlocking {
        val invalid = listOf(
            response(),
            response().setHeader("Set-Cookie", "spofify_session=x; Path=/other; HttpOnly; Expires=Tue, 01 Jan 2030 00:00:00 GMT"),
            response().setHeader("Set-Cookie", "spofify_session=x; Path=/; HttpOnly; Domain=localhost; Expires=Tue, 01 Jan 2030 00:00:00 GMT"),
            response().setHeader("Set-Cookie", "spofify_session=x; Path=/; HttpOnly; Secure; Expires=Tue, 01 Jan 2030 00:00:00 GMT"),
            response().setHeader("Set-Cookie", "spofify_session=x; Path=/; Expires=Tue, 01 Jan 2030 00:00:00 GMT"),
            response().setHeader("Set-Cookie", cookie()).addHeader("Set-Cookie", cookie()),
            response().setHeader("Set-Cookie", cookie()).setBody("""{"accountId":"fixture-id"}"""),
            response().setHeader("Set-Cookie", cookie()).setBody(summary.replace("\"role\":\"user\"", "\"role\":\"other\"")),
            response().setHeader("Set-Cookie", cookie()).setBody("x".repeat(65 * 1024)),
        )
        for (response in invalid) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(response)
                val api = OkHttpAuthApi(ServerOrigin.parse(server.url("/").toString()))
                try { api.login("fixture-user", UUID.randomUUID().toString()); fail("Expected invalid response") }
                catch (failure: AuthFailure) { assertEquals(AuthFailure.Kind.INVALID_RESPONSE, failure.kind) }
            }
        }
    }

    @Test fun cancellationAfterHeadersOwnsBodyUntilClosed() = runBlocking {
        for (cancelJob in listOf(false, true)) {
            MockWebServer().use { server ->
                server.start()
                val reading = CountDownLatch(1)
                val terminal = CountDownLatch(1)
                val client = OkHttpAuthApi.defaultClient().newBuilder().eventListener(object : EventListener() {
                    override fun responseBodyStart(call: Call) { reading.countDown() }
                    override fun callFailed(call: Call, ioe: IOException) { terminal.countDown() }
                    override fun callEnd(call: Call) { terminal.countDown() }
                }).build()
                val api = OkHttpAuthApi(ServerOrigin.parse(server.url("/").toString()), client)
                // Headers arrive immediately; the body cannot complete within the cancellation deadline.
                server.enqueue(response().setBodyDelay(3, TimeUnit.SECONDS))
                val request = async(Dispatchers.IO) { runCatching { api.session(cookie()) } }
                try {
                    assertTrue("Body read was not entered", reading.await(2, TimeUnit.SECONDS))
                    if (cancelJob) request.cancel() else api.cancel()
                    assertTrue("Retired Call did not terminate promptly", terminal.await(1, TimeUnit.SECONDS))
                    withTimeout(1_000) { request.join() }
                    if (!cancelJob) assertTrue(request.await().exceptionOrNull() is AuthFailure)
                } finally {
                    api.cancel()
                    request.cancelAndJoin()
                }
            }
        }
    }

    @Test fun serverAbsoluteExpirySurvivesConflictingMaxAgeBodyDelayAndClockSkew() = runBlocking {
        // The past date deliberately models a device clock ahead of the server:
        // local time is never a revocation decision and must not rebase Expires.
        for ((date, epoch) in listOf(
            "Tue, 01 Jan 2030 00:00:00 GMT" to Instant.parse("2030-01-01T00:00:00Z").toEpochMilli(),
            "Mon, 01 Jan 2001 00:00:00 GMT" to Instant.parse("2001-01-01T00:00:00Z").toEpochMilli(),
        )) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(response().setHeader("Set-Cookie",
                    "spofify_session=fixture-cookie; Path=/; HttpOnly; Expires=$date; Max-Age=1")
                    .setBodyDelay(100, TimeUnit.MILLISECONDS))
                server.enqueue(response()) // Quiet validation: no renewal.
                val api = OkHttpAuthApi(ServerOrigin.parse(server.url("/").toString()))
                val first = api.login("fixture-user", UUID.randomUUID().toString())
                assertEquals(epoch, Cookie.parse(server.url("/"), first.cookie!!)!!.expiresAt)
                assertFalse(first.cookie!!.contains("max-age", ignoreCase = true))
                val quiet = api.session(first.cookie!!)
                assertNull(quiet.cookie)
                assertEquals(epoch, Cookie.parse(server.url("/"), first.cookie!!)!!.expiresAt)
            }
        }
    }

    @Test fun malformedAbsoluteExpiryNeverBecomesLocallyInventedRenewal() = runBlocking {
        for (attributes in listOf(
            "Max-Age=3600",
            "Expires=not-a-date; Max-Age=3600",
            "Expires=Tue, 01 Jan 2030 00:00:00 GMT; Expires=Mon, 01 Jan 2001 00:00:00 GMT",
            "Expires=Tue, 01 Jan 2030 00:00:00 GMT; Max-Age=not-a-number",
            "Expires=Tue, 01 Jan 2030 00:00:00 GMT; Max-Age=1; Max-Age=2",
        )) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(response().setHeader("Set-Cookie", "spofify_session=x; Path=/; HttpOnly; $attributes"))
                val api = OkHttpAuthApi(ServerOrigin.parse(server.url("/").toString()))
                try { api.session(cookie()); fail("Expected invalid cookie") }
                catch (failure: AuthFailure) { assertEquals(AuthFailure.Kind.INVALID_RESPONSE, failure.kind) }
            }
        }
    }

    @Test fun httpAndHttpsCookieNamesNeverMix() {
        val http = OkHttpAuthApi(ServerOrigin.parse("http://fixture.test"))
        val https = OkHttpAuthApi(ServerOrigin.parse("https://fixture.test"))
        http.validateCookie("spofify_session=x; Path=/; HttpOnly")
        https.validateCookie("__Host-spofify_session=x; Path=/; HttpOnly; Secure")
        assertThrows(AuthFailure::class.java) { http.validateCookie("__Host-spofify_session=x; Path=/; HttpOnly; Secure") }
        assertThrows(AuthFailure::class.java) { https.validateCookie("spofify_session=x; Path=/; HttpOnly") }
    }
}
