package dev.spofify.mobile.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class PlaybackContinuationApiTest {
    private val phone = "a".repeat(32)
    private val own = PlaybackRegistrationDevice(phone, "receiver", "registered-generation")
    private fun view(registration: PlaybackRegistrationDevice = own, epoch: Long = 9, selected: Boolean = false) =
        PlaybackDevicesView(phone, listOf(PlaybackDevice(phone, "Phone", true)), if (selected) phone else "", epoch,
            if (selected) 8000 else 0, state = null, stateSeq = 0, stateAgeMs = 0, transfer = null,
            directives = emptyList(), commands = emptyList(),
            registration = PlaybackRegistrationView(1, "room-cas", "admission-token", listOf(registration)),
            leadership = PlaybackLeadership(1, 7, "installation", phone))
    private fun response(view: PlaybackDevicesView = view(), status: Int = 200) = MockResponse()
        .setResponseCode(status).setHeader("Content-Type", "application/json").setBody(view.toJson().toString())
    private suspend fun connected(web: MockWebServer): PlaybackDevicesApi {
        val client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), MemorySessionStore())
        web.enqueue(loginResponse()); client.login("synthetic", "password"); web.takeRequest()
        return PlaybackDevicesApi(client).also { api ->
            web.enqueue(response()); api.probeQueueIntents(); web.takeRequest()
            web.enqueue(response()); api.register("Phone", null, phone, own.receiverId); web.takeRequest()
        }
    }

    @Test fun conditionalGrantCarriesOnlyExactCapturedAuthorityAndAuthenticatedControlHeaders() = runBlocking {
        MockWebServer().use { web ->
            web.start()
            val api = connected(web)
            web.enqueue(response(view(epoch = 10, selected = true)))
            val accepted = api.continueReceiver(own, 9, 7)
            assertEquals(10L, accepted.epoch)
            val request = web.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/me/player", request.path)
            assertEquals("last-requested-v1", request.getHeader("X-Spofify-Playback-Leadership"))
            assertEquals("installation-v1", request.getHeader("X-Spofify-Playback-Registration"))
            assertEquals("spofify_session=cookie-secret", request.getHeader("Cookie"))
            assertEquals("csrf-secret", request.getHeader("X-CSRF-Token"))
            assertEquals("http://127.0.0.1:${web.port}", request.getHeader("Origin"))
            val raw = request.body.readUtf8()
            assertEquals(buildJsonObject {
                put("action", "continue"); put("deviceId", phone); put("registrationGeneration", own.registrationGeneration)
                put("epoch", 9); put("expectedLeadRevision", 7)
            }, PlaybackRequestContract.fromRawJson(raw))
            assertNull(accepted.publicationAck)
        }
    }

    @Test fun observationCannotReplaceCapturedIssuerAndUnknownActionHasNoTakeoverFallbackOrRetry() = runBlocking {
        MockWebServer().use { web ->
            web.start()
            val api = connected(web)
            val successor = PlaybackRegistrationDevice(phone, "successor", "successor-generation")
            web.enqueue(response(view(successor))); api.view(); web.takeRequest()
            val before = web.requestCount
            try { api.continueReceiver(successor, 9, 7); fail("GET borrowed a successor's generation") }
            catch (_: IllegalStateException) { }
            assertEquals(before, web.requestCount)
            web.enqueue(response(view(successor), 409))
            try { api.continueReceiver(own, 9, 7); fail() } catch (error: ApiException) { assertEquals(409, error.status) }
            assertEquals(JsonPrimitive(own.registrationGeneration), Json.parseToJsonElement(web.takeRequest().body.readUtf8()).jsonObject["registrationGeneration"])
            web.enqueue(response(status = 400))
            try { api.continueReceiver(own, 9, 7); fail() } catch (error: ApiException) { assertEquals(400, error.status) }
            assertEquals("continue", Json.parseToJsonElement(web.takeRequest().body.readUtf8()).jsonObject.getValue("action").jsonPrimitive.content)
            val unsupported = web.requestCount
            try { api.continueReceiver(own, 9, 7); fail() } catch (_: IllegalStateException) { }
            assertEquals(unsupported, web.requestCount)
        }
    }

    @Test fun strictContinuationEnvelopeRejectsPublicationSelectionAndMalformedAuthority() {
        val raw = """{"action":"continue","deviceId":"$phone","registrationGeneration":"generation","epoch":9,"expectedLeadRevision":7}"""
        PlaybackRequestContract.fromRawJson(raw)
        // Server omits an absent acceptance receipt; emitting explicit null would
        // violate our deliberately strict positive inbound wire-number contract.
        val encoded = view().toJson()
        assertFalse(encoded.getValue("leadership").jsonObject.containsKey("acceptedRevision"))
        assertEquals(view().leadership, PlaybackDevicesView.fromJson(encoded).leadership)
        for (bad in listOf(
            raw.replace("\"expectedLeadRevision\":7", "\"expectedLeadRevision\":0"),
            raw.replace("\"expectedLeadRevision\":7", "\"expectedLeadRevision\":\"7\""),
            raw.replace("\"expectedLeadRevision\":7", "\"expectedLeadRevision\":null"),
            raw.replace("\"epoch\":9", "\"epoch\":9,\"epoch\":10"),
            raw.replace("\"registrationGeneration\":\"generation\",", ""),
            raw.dropLast(1) + ",\"state\":null}",
            raw.dropLast(1) + ",\"acks\":[]}",
            raw.dropLast(1) + ",\"commandId\":\"replay\"}",
            raw.dropLast(1) + ",\"requireUnselected\":true}",
        )) {
            try { PlaybackRequestContract.fromRawJson(bad); fail("Invalid continuation envelope admitted") }
            catch (_: IllegalArgumentException) { }
            catch (_: NoSuchElementException) { }
        }
    }
}
