package dev.spofify.mobile.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class PlaybackRegistrationApiTest {
    private val id = "0123456789abcdef0123456789abcdef"
    private val other = "fedcba9876543210fedcba9876543210"
    private fun view(generation: String = "reg-old", receiver: String = "receiver-one", managed: Boolean = true): String =
        PlaybackDevicesView(id, listOf(PlaybackDevice(id, "Phone", true), PlaybackDevice(other, "Phone", true)), "", 2, 0,
            state = null, stateSeq = 0, stateAgeMs = 0, transfer = null, directives = emptyList(), commands = emptyList(),
            registration = if (managed) PlaybackRegistrationView(1, "room-cas", "admission-token", listOf(
                PlaybackRegistrationDevice(id, receiver, generation), PlaybackRegistrationDevice(other, "other-receiver", "other-generation"))) else null).toJson().toString()
    private fun response(body: String = view(), code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun RecordedRequest.json() = Json.parseToJsonElement(body.readUtf8()).jsonObject
    private suspend fun connected(web: MockWebServer): PlaybackDevicesApi {
        val client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), MemorySessionStore())
        web.enqueue(loginResponse())
        client.login("synthetic", "password"); web.takeRequest()
        return PlaybackDevicesApi(client)
    }

    @Test fun negotiatedEnrollmentUsesExactIdentityAndCapturedIssuerAndTargetGenerations() = runBlocking {
        val web = MockWebServer(); web.start()
        try {
            val api = connected(web)
            web.enqueue(response()); api.probeQueueIntents()
            val probe = web.takeRequest()
            assertEquals("installation-v1", probe.getHeader("X-Spofify-Playback-Registration"))
            assertEquals("queue-intents-v1", probe.getHeader("X-Spofify-Playback-Extensions"))
            web.enqueue(response()); val registered = api.register("Phone", null, id, "receiver-one")
            assertEquals(id, registered.deviceId)
            assertEquals(2, registered.devices.size) // Equal names are distinct, not deduplicated.
            val request = web.takeRequest()
            assertEquals("spofify_session=cookie-secret", request.getHeader("Cookie"))
            assertEquals("csrf-secret", request.getHeader("X-CSRF-Token"))
            assertEquals("http://127.0.0.1:${web.port}", request.getHeader("Origin"))
            val enrollment = request.json()
            val metadata = enrollment.getValue("registration").jsonObject
            assertEquals(JsonPrimitive(id), metadata["deviceId"])
            assertEquals(JsonPrimitive("receiver-one"), metadata["receiverId"])
            assertEquals(JsonPrimitive("room-cas"), metadata["expectedGeneration"])
            assertEquals(JsonPrimitive("admission-token"), metadata["admissionToken"])
            assertTrue(validPlaybackProtocolId(metadata.getValue("attemptId").jsonPrimitive.content))
            assertFalse(enrollment.containsKey("queueIntentsVersion"))

            // A newer public observation is NOT replacement proof for this old issuer.
            web.enqueue(response(view("reg-new", "receiver-two"))); api.view(); web.takeRequest()
            val beforeStaleEnrollment = web.requestCount
            try { api.register("Phone", null, id, "receiver-one"); fail("Old receiver refreshed CAS to replace successor") }
            catch (_: IllegalStateException) { }
            assertEquals(beforeStaleEnrollment, web.requestCount)
            web.enqueue(response(view("reg-new", "receiver-two"), 409))
            try { api.poll(id, 2); fail("Stale poll accepted") } catch (e: ApiException) { assertEquals(409, e.status) }
            assertEquals(JsonPrimitive("reg-old"), web.takeRequest().json()["registrationGeneration"])
            web.enqueue(response(view("reg-new", "receiver-two"), 409))
            try { api.transfer(id, 2, "select", other, 1, "captured-other-generation"); fail() } catch (_: ApiException) { }
            val transfer = web.takeRequest().json()
            assertEquals(JsonPrimitive("reg-old"), transfer["registrationGeneration"])
            assertEquals(JsonPrimitive("captured-other-generation"), transfer["targetRegistrationGeneration"])
            web.enqueue(response(view("reg-new", "receiver-two"), 409))
            try { api.claimUnselected(id, 2, "explicit-play", 2); fail() } catch (_: ApiException) { }
            val claim = web.takeRequest().json()
            assertEquals(JsonPrimitive("reg-old"), claim["targetRegistrationGeneration"])
            assertEquals(JsonPrimitive(true), claim["requireUnselected"])
            web.enqueue(response(view("reg-new", "receiver-two"), 409))
            try { api.prepareLeave(id).invoke(); } catch (_: Exception) { }
            val cleanup = web.takeRequest()
            assertEquals("installation-v1", cleanup.getHeader("X-Spofify-Playback-Registration"))
            assertEquals(JsonPrimitive("reg-old"), cleanup.json()["registrationGeneration"])
        } finally { web.shutdown() }
    }

    @Test fun lostEnrollmentReplyRetainsExactAttemptButDefinitiveConflictDoesNotReplay() = runBlocking {
        val web = MockWebServer(); web.start()
        try {
            val api = connected(web)
            web.enqueue(response()); api.probeQueueIntents(); web.takeRequest()
            web.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            try { api.register("Phone", null, id, "receiver-one"); fail() } catch (_: java.io.IOException) { }
            val lost = web.takeRequest().json()
            assertEquals(3, web.requestCount) // No transparent mutation retry.
            web.enqueue(response()); api.probeQueueIntents(); web.takeRequest()
            web.enqueue(response()); api.register("Changed draft", null, id, "receiver-one")
            assertEquals(lost, web.takeRequest().json())

            val successor = connected(web)
            web.enqueue(response()); successor.probeQueueIntents(); web.takeRequest()
            web.enqueue(response(code = 409))
            try { successor.register("Phone", null, id, "receiver-two"); fail() } catch (_: ApiException) { }
            val rejected = web.takeRequest().json()
            // The rejected receiver must not use another fresh CAS to replace the still-live predecessor.
            web.enqueue(response()); successor.probeQueueIntents(); web.takeRequest()
            val beforeRejectedRetry = web.requestCount
            try { successor.register("Phone", null, id, "receiver-two"); fail("Rejected receiver stole the live slot") }
            catch (_: IllegalStateException) { }
            assertEquals(beforeRejectedRetry, web.requestCount)
            assertEquals(JsonPrimitive(id), rejected["registration"]!!.jsonObject["deviceId"])
            assertEquals(JsonPrimitive("receiver-two"), rejected["registration"]!!.jsonObject["receiverId"])
            val count = web.requestCount
            assertNull(web.takeRequest(100, java.util.concurrent.TimeUnit.MILLISECONDS))
            assertEquals(count, web.requestCount)
            web.enqueue(response(view("reg-new", "receiver-two"))); successor.probeQueueIntents(); web.takeRequest()
            web.enqueue(response(view("reg-new", "receiver-two"))); successor.register("Phone", null, id, "receiver-two")
            assertNotEquals(rejected["registration"]!!.jsonObject["attemptId"], web.takeRequest().json()["registration"]!!.jsonObject["attemptId"])
        } finally { web.shutdown() }
    }

    @Test fun legacyAndUnknownServersReceiveOnlyBoundedLegacyEnvelopeAndMissingNegotiatedMetadataFailsClosed() = runBlocking {
        for (unknown in listOf(false, true)) {
            val web = MockWebServer(); web.start()
            try {
                val api = connected(web)
                val unsupported = if (unknown) view().replace("\"version\":1", "\"version\":2") else view(managed = false)
                web.enqueue(response(unsupported)); assertNull(api.probeQueueIntents().registration); web.takeRequest()
                web.enqueue(response(view(managed = false))); api.register("Phone", null, id, "receiver-one")
                val legacy = web.takeRequest()
                assertNull(legacy.getHeader("X-Spofify-Playback-Registration"))
                assertEquals(buildJsonObject { put("action", "register"); put("name", "Phone") }, legacy.json())
            } finally { web.shutdown() }
        }
        val web = MockWebServer(); web.start()
        try {
            val api = connected(web)
            web.enqueue(response()); api.probeQueueIntents(); web.takeRequest()
            web.enqueue(response(view(managed = false)))
            try { api.register("Phone", null, id, "receiver-one"); fail() } catch (_: IllegalArgumentException) { }
            assertEquals(3, web.requestCount)
        } finally { web.shutdown() }
    }

    @Test fun registrationShapesAreStrictAndTokensNeverAppearInDiagnostics() {
        val parsed = PlaybackDevicesView.fromRawJson(view(), allowRegistration = true)
        assertFalse(parsed.registration.toString().contains("admission-token"))
        val invalid = listOf(view().replace("\"version\":1", "\"version\":\"1\""),
            view().replace("\"admission-token\"", "17"), view().replace("\"reg-old\"", "null"),
            view().replace("\"receiver-one\"", "\"receiver one\""))
        invalid.forEach { raw -> assertThrows(IllegalArgumentException::class.java) { PlaybackDevicesView.fromRawJson(raw, allowRegistration = true) } }
        assertThrows(IllegalArgumentException::class.java) { PlaybackDevicesView.fromRawJson(view()) }
    }
}
