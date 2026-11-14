package dev.spofify.mobile.core.http

import dev.spofify.mobile.core.auth.AuthFailure
import dev.spofify.mobile.core.origin.ServerOrigin
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class OkHttpAuthApiTest {
    private val summary = """{"accountId":"fixture-id","username":"fixture-user","role":"user","csrfToken":"fixture-csrf"}"""
    private fun response() = MockResponse().setHeader("Content-Type", "application/json").setBody(summary)
    private fun cookie() = "spofify_session=${UUID.randomUUID()}; Path=/; HttpOnly; Max-Age=3600"

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
            response().setHeader("Set-Cookie", "spofify_session=x; Path=/other; HttpOnly"),
            response().setHeader("Set-Cookie", "spofify_session=x; Path=/; HttpOnly; Domain=localhost"),
            response().setHeader("Set-Cookie", "spofify_session=x; Path=/; HttpOnly; Secure"),
            response().setHeader("Set-Cookie", "spofify_session=x; Path=/"),
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

    @Test fun httpAndHttpsCookieNamesNeverMix() {
        val http = OkHttpAuthApi(ServerOrigin.parse("http://fixture.test"))
        val https = OkHttpAuthApi(ServerOrigin.parse("https://fixture.test"))
        http.validateCookie("spofify_session=x; Path=/; HttpOnly")
        https.validateCookie("__Host-spofify_session=x; Path=/; HttpOnly; Secure")
        assertThrows(AuthFailure::class.java) { http.validateCookie("__Host-spofify_session=x; Path=/; HttpOnly; Secure") }
        assertThrows(AuthFailure::class.java) { https.validateCookie("spofify_session=x; Path=/; HttpOnly") }
    }
}
