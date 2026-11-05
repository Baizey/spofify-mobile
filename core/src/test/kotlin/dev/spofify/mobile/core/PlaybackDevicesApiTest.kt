package dev.spofify.mobile.core

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class PlaybackDevicesApiTest {
    private fun view(state: PlaybackSnapshot? = null, extra: String = "") = """{"deviceId":"phone","devices":[{"deviceId":"phone","name":"Phone","available":true}],"activeDeviceId":"phone","epoch":2,"leaseMs":8000,"quarantineMs":0,"state":${state?.toJson() ?: "null"},"stateSeq":0,"stateAgeMs":0,"transfer":null,"directives":[],"commands":[]$extra}"""
    private fun response(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun track(key: Long, order: Long) = PlaybackTrack("track/a%", "Song", listOf(PlaybackArtist("artist", "Artist")), "Album", "album", 120.0, entryId = "entry-$key", occurrenceId = "occurrence-$key", key = key, order = order)
    private fun snapshot() = PlaybackSnapshot(queue = listOf(track(8, 1), track(9, 0)), position = 1,
        context = PlaybackContext("playlist", "playlist", "Edited playlist", 7), shuffle = true, repeat = "one", elapsed = 31.2, duration = 120.0, playing = true,
        occurrence = PlaybackOccurrence(9, "track/a%", "history-nonce", 28.5, false))

    @Test fun preparedSnapshotPreservesDuplicatesBaselineOccurrenceAndRejectsUrls() {
        val original = snapshot()
        val wire = original.toJson()
        assertEquals(original, PlaybackSnapshot.fromJson(wire))
        assertEquals(listOf(8L, 9L), original.queue.map { it.key })
        assertEquals(listOf(1L, 0L), original.queue.map { it.order })
        assertFalse(wire.toString().contains("selectionToken"))
        val bad = JsonObject(wire + ("url" to JsonPrimitive("https://attacker/media")))
        try { PlaybackSnapshot.fromJson(bad); fail("Arbitrary URL field accepted") } catch (_: IllegalArgumentException) { }
        try { original.copy(queue = listOf(track(8, 0), track(8, 1))).validated(); fail("Duplicate key accepted") } catch (_: IllegalArgumentException) { }
    }

    @Test fun exactRegistrationSelectionAndAtomicReleasePollBodies() = runBlocking {
        val web = MockWebServer(); web.start()
        try {
            val client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), MemorySessionStore())
            web.enqueue(loginResponse()); client.login("synthetic", "password"); web.takeRequest()
            val api = PlaybackDevicesApi(client)
            web.enqueue(response(view()))
            assertEquals("phone", api.register("Phone").deviceId)
            val registered = web.takeRequest()
            assertEquals("/api/me/player", registered.path)
            assertEquals("POST", registered.method)
            assertEquals("spofify_session=cookie-secret", registered.getHeader("Cookie"))
            assertNotNull(registered.getHeader("X-CSRF-Token"))
            assertEquals("http://127.0.0.1:${web.port}", registered.getHeader("Origin"))
            assertEquals(buildJsonObject { put("action", "register"); put("name", "Phone") }, Json.parseToJsonElement(registered.body.readUtf8()))
            web.enqueue(response(view()))
            api.transfer("phone", 2, "command-1", "phone", 7)
            val transfer = Json.parseToJsonElement(web.takeRequest().body.readUtf8()).jsonObject
            assertEquals(buildJsonObject {
                put("action", "transfer"); put("deviceId", "phone"); put("epoch", 2)
                put("commandId", "command-1"); put("targetDeviceId", "phone"); put("selectionSeq", 7)
            }, transfer)
            assertFalse(transfer.containsKey("requireUnselected"))
            web.enqueue(response(view(snapshot())))
            api.poll("phone", 2, 1, snapshot(), listOf(PlaybackAck("delivery-1", 2, "ok")))
            val poll = Json.parseToJsonElement(web.takeRequest().body.readUtf8()).jsonObject
            assertEquals(snapshot().toJson(), poll["state"])
            assertEquals("delivery-1", poll["acks"]?.jsonArray?.single()?.jsonObject?.get("deliveryId")?.jsonPrimitive?.content)
            assertEquals(4, web.requestCount)
        } finally { web.shutdown() }
    }

    @Test fun guardedClaimUsesExactSelfTrueEnvelopeAndExistingAuthentication() = runBlocking {
        val web = MockWebServer(); web.start()
        try {
            val client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), MemorySessionStore())
            web.enqueue(loginResponse()); client.login("synthetic", "password"); web.takeRequest()
            val api = PlaybackDevicesApi(client)
            web.enqueue(response(view()))
            assertEquals(2L, api.claimUnselected("phone", 2, "first-play", 9).epoch)
            val claim = web.takeRequest()
            assertEquals("POST", claim.method)
            assertEquals("/api/me/player", claim.path)
            assertEquals("spofify_session=cookie-secret", claim.getHeader("Cookie"))
            assertEquals("csrf-secret", claim.getHeader("X-CSRF-Token"))
            assertEquals("http://127.0.0.1:${web.port}", claim.getHeader("Origin"))
            assertEquals(buildJsonObject {
                put("action", "transfer"); put("deviceId", "phone"); put("epoch", 2)
                put("commandId", "first-play"); put("targetDeviceId", "phone")
                put("selectionSeq", 9); put("requireUnselected", true)
            }, Json.parseToJsonElement(claim.body.readUtf8()))
            assertEquals(2, web.requestCount)
        } finally { web.shutdown() }
    }

    @Test fun guardedClaimRejectsInvalidIdentityEpochAndSequenceBeforeNetwork() = runBlocking {
        val web = MockWebServer(); web.start()
        try {
            val api = PlaybackDevicesApi(SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), MemorySessionStore()))
            val invalid = listOf<suspend () -> Unit>(
                { api.claimUnselected("", 1, "claim", 1) },
                { api.claimUnselected("phone/other", 1, "claim", 1) },
                { api.claimUnselected("phone", 0, "claim", 1) },
                { api.claimUnselected("phone", 9007199254740992L, "claim", 1) },
                { api.claimUnselected("phone", 1, "bad command", 1) },
                { api.claimUnselected("phone", 1, "claim", 0) },
                { api.claimUnselected("phone", 1, "claim", 9007199254740992L) },
            )
            for (call in invalid) try { call(); fail("Invalid claim reached transport") } catch (_: IllegalArgumentException) { }
            assertEquals(0, web.requestCount)
        } finally { web.shutdown() }
    }

    @Test fun guardedConflictAndOlderServerRejectionNeverFallBackToOrdinaryTransfer() = runBlocking {
        val web = MockWebServer(); web.start()
        try {
            val api = PlaybackDevicesApi(SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), MemorySessionStore()))
            for (status in listOf(409, 400)) {
                web.enqueue(response(view(extra = ",\"error\":\"conditional selection rejected\""), status))
                try { api.claimUnselected("phone", 2, "claim-$status", 1); fail("Rejected claim succeeded") }
                catch (error: ApiException) {
                    assertEquals(status, error.status)
                    if (status == 409) assertEquals(2L, PlaybackDevicesView.fromJson(requireNotNull(error.responseBody)).epoch)
                    else assertNull(error.responseBody)
                }
                val sent = Json.parseToJsonElement(web.takeRequest().body.readUtf8()).jsonObject
                assertEquals(JsonPrimitive(true), sent["requireUnselected"])
            }
            assertEquals(2, web.requestCount)
        } finally { web.shutdown() }
    }

    @Test fun guardedClaimDeadlineDoesNotReplayOrFallBack() = runBlocking {
        val web = MockWebServer(); web.start()
        try {
            val api = PlaybackDevicesApi(SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), MemorySessionStore()))
            web.enqueue(response(view()).setBodyDelay(6, TimeUnit.SECONDS))
            val start = System.nanoTime()
            try { api.claimUnselected("phone", 2, "claim", 1); fail("Deadline not enforced") }
            catch (_: TimeoutCancellationException) { }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 5500)
            assertEquals(1, web.requestCount)
            assertEquals(JsonPrimitive(true), Json.parseToJsonElement(web.takeRequest().body.readUtf8()).jsonObject["requireUnselected"])
        } finally { web.shutdown() }
    }

    @Test fun unsupportedProtocolGuardFailsClosedWithoutCallingOrdinaryTransfer() = runBlocking {
        var ordinaryTransfers = 0
        val legacy = object : PlaybackDevicesProtocol {
            override suspend fun view(): PlaybackDevicesView = error("Unexpected view")
            override suspend fun register(name: String): PlaybackDevicesView = error("Unexpected register")
            override suspend fun poll(deviceId: String, epoch: Long, stateSeq: Long?, state: PlaybackSnapshot?, acks: List<PlaybackAck>): PlaybackDevicesView = error("Unexpected poll")
            override suspend fun rename(deviceId: String, name: String): PlaybackDevicesView = error("Unexpected rename")
            override suspend fun command(deviceId: String, epoch: Long, commandId: String, command: PlaybackDeviceCommand): PlaybackDevicesView = error("Unexpected command")
            override suspend fun transfer(deviceId: String, epoch: Long, commandId: String, targetDeviceId: String, selectionSeq: Long): PlaybackDevicesView {
                ordinaryTransfers++; error("Ordinary transfer must not implement conditional selection")
            }
            override suspend fun leave(deviceId: String) = Unit
        }
        try { legacy.claimUnselected("phone", 1, "claim", 1); fail("Unsupported claim succeeded") }
        catch (_: UnsupportedOperationException) { }
        assertEquals(0, ordinaryTransfers)
    }

    @Test fun conflictRetainsValidatedViewAndDoesNotReplayCommand() = runBlocking {
        val web = MockWebServer(); web.start()
        try {
            val api = PlaybackDevicesApi(SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), MemorySessionStore()))
            web.enqueue(response(view(extra = ",\"error\":\"stale epoch\""), 409))
            try { api.command("phone", 1, "cmd", PlaybackDeviceCommand.transport("resume")); fail("Conflict succeeded") }
            catch (error: ApiException) { assertEquals(409, error.status); assertNotNull(error.responseBody); assertEquals(2L, PlaybackDevicesView.fromJson(error.responseBody!!).epoch) }
            assertEquals(1, web.requestCount)
        } finally { web.shutdown() }
    }

    @Test fun requestBodyDeadlineIsFourSecondsAndNeverReplays() = runBlocking {
        val web = MockWebServer(); web.start()
        try {
            val api = PlaybackDevicesApi(SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), MemorySessionStore()))
            web.enqueue(response(view()).setBodyDelay(6, TimeUnit.SECONDS))
            val start = System.nanoTime()
            try { api.register("Phone"); fail("Deadline not enforced") } catch (_: TimeoutCancellationException) { }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 5500)
            assertEquals(1, web.requestCount)
        } finally { web.shutdown() }
    }

    @Test fun serverCommandDefaultsAndIntegerNumbersRemainWireCompatible() {
        val collection = Json.parseToJsonElement("""{"type":"playCollection","context":{"kind":"album","id":"a","name":"A"}}""").jsonObject
        assertEquals(collection, PlaybackDeviceCommand.fromJson(collection).json)
        val seek = Json.parseToJsonElement("""{"type":"seek","value":10}""").jsonObject
        assertEquals(seek, PlaybackDeviceCommand.fromJson(seek).json)
        assertTrue(validPlaybackDeviceName("Phone 🎵"))
        try { PlaybackDeviceCommand.musical("enqueue", track = buildJsonObject { }, first = true, context = PlaybackContext("album", "a", "A")); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun legacyIncomingToggleIsStrictButNativeOutgoingTransportRemainsExplicit() {
        val toggle = buildJsonObject { put("type", "toggle") }
        assertEquals(toggle, PlaybackDeviceCommand.fromJson(toggle).json)
        try { PlaybackDeviceCommand.transport("toggle"); fail("Native toggle must remain forbidden") } catch (_: IllegalArgumentException) { }
        try { PlaybackDeviceCommand.fromJson(buildJsonObject { put("type", "toggle"); put("value", 1) }); fail("Extra toggle fields accepted") } catch (_: IllegalArgumentException) { }
        val directiveView = Json.parseToJsonElement(view().replace("\"directives\":[]",
            "\"directives\":[{\"deliveryId\":\"legacy-toggle\",\"epoch\":2,\"type\":\"command\",\"command\":{\"type\":\"toggle\"}}]")).jsonObject
        assertEquals("toggle", PlaybackDevicesView.fromJson(directiveView).directives.single().command?.get("type")?.jsonPrimitive?.content)
    }

    @Test fun musicalCommandDiagnosticStringNeverExposesSelectionAuthority() {
        val current = snapshot().queue.first()
        val track = snapshot().toJson().getValue("queue").jsonArray.first().jsonObject
        val command = PlaybackDeviceCommand.musical("play", track = track,
            context = PlaybackContext("derived", "rules", "Rules", 4, "original-fence"),
            occurrenceId = current.occurrenceId, selectionToken = "original-signed-selection")
        assertFalse(command.toString().contains("original-signed-selection"))
        assertFalse(command.toString().contains("original-fence"))
        assertTrue(command.toString().contains("play"))
        assertEquals("original-signed-selection", command.json["selectionToken"]?.jsonPrimitive?.content)
    }

    @Test fun invalidLeasesUnknownCommandsAndPreparedTokenAreRejected() {
        try { PlaybackDevicesView.fromJson(Json.parseToJsonElement(view().replace("8000", "8001")).jsonObject); fail() } catch (_: IllegalArgumentException) { }
        try { PlaybackDeviceCommand.fromJson(buildJsonObject { put("type", "resume"); put("url", "https://attacker") }); fail() } catch (_: IllegalArgumentException) { }
        val wire = snapshot().toJson()
        val badTrack = JsonObject(wire.getValue("queue").jsonArray[0].jsonObject + ("selectionToken" to JsonPrimitive("secret")))
        val bad = JsonObject(wire + ("queue" to JsonArray(listOf(badTrack, wire.getValue("queue").jsonArray[1]))))
        try { PlaybackSnapshot.fromJson(bad); fail() } catch (_: IllegalArgumentException) { }
    }
}
