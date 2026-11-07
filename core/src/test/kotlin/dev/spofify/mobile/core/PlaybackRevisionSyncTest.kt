package dev.spofify.mobile.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class PlaybackRevisionSyncTest {
    private fun state(count: Int = 1) = PlaybackSnapshot(queue = (1..count).map {
        PlaybackTrack("track$it", "Song", emptyList(), "Album", "album", 180.0, key = it.toLong(), order = it.toLong())
    }, position = 0, duration = 180.0, occurrence = PlaybackOccurrence(1, "track1", "event", 0.0, false))
    private fun view(state: PlaybackSnapshot? = state()) = PlaybackDevicesView("phone", listOf(PlaybackDevice("phone", "Phone", true)),
        "phone", 2, 8000, 0, state, 1, 0, null, emptyList(), emptyList())
    private fun ref(revision: Long) = buildJsonObject { put("version", 1); put("incarnation", "room"); put("revision", revision) }
    private fun snapshot(revision: Long, state: PlaybackSnapshot? = state()) = buildJsonObject {
        put("kind", "snapshot"); put("sync", ref(revision)); put("snapshot", view(state).toJson())
    }
    private fun status(revision: Long, state: PlaybackSnapshot = state(), seq: Long = 2, lease: Long = 8000, active: String = "phone") = buildJsonObject {
        put("kind", "status"); put("sync", ref(revision))
        put("live", buildJsonObject { PlaybackSyncLive.from(state).toJson().forEach { (k,v) -> put(k,v) }; put("stateSeq", seq); put("stateAgeMs", 0) })
        put("control", buildJsonObject {
            put("activeDeviceId", active); put("epoch", 2); put("leaseMs", lease); put("quarantineMs", 0)
            put("availability", buildJsonArray { add(buildJsonObject { put("deviceId", "phone"); put("available", true) }) })
            put("transfer", JsonNull); put("directives", JsonArray(emptyList())); put("commands", buildJsonArray {
                add(buildJsonObject { put("commandId", "command"); put("status", "blocked") })
            })
        })
    }
    private fun rejected(block: () -> Unit) { try { block(); fail("Invalid sync accepted") } catch (_: IllegalArgumentException) {} }

    @Test fun strictServerWireEnvelopeAndCompactRequestRejectUnknownDuplicateNullAndRange() {
        val sync = PlaybackRevisionSync(); sync.probe(PlaybackRawJson.parse(snapshot(4).toString()))
        val live = PlaybackSyncLive.from(state()).toJson()
        val request = buildJsonObject { put("action", "poll"); put("deviceId", "phone"); put("epoch", 2); put("stateSeq", 3)
            put("base", PlaybackSyncReference("room",4).toJson()); put("live", live); put("acks", JsonArray(emptyList())) }
        assertEquals(request, PlaybackRequestContract.fromRawJson(request.toString()))
        for (bad in listOf(
            request.toString().replace("\"revision\":4", "\"revision\":0"),
            request.toString().replace("\"currentKey\":1", "\"currentKey\":1,\"currentKey\":1"),
            request.toString().replace("\"elapsed\":0.0", "\"elapsed\":null"),
            request.toString().replace("\"live\":{", "\"live\":{\"queue\":[],"),
            request.toString().replace("\"playing\":false", "\"Playing\":false"),
            request.toString().replace("\"reported\":false", "\"reported\":null"),
            request.toString().replace("\"revision\":4", "\"revision\":9007199254740992"),
        )) rejected { PlaybackRequestContract.fromRawJson(bad) }
        rejected { sync.response(JsonObject(status(4) + ("unused" to JsonNull)), false, false) }
        rejected { sync.snapshot(JsonObject(snapshot(4) + ("sync" to JsonObject(ref(4) + ("version" to JsonPrimitive(2))))), false, false) }
        rejected { sync.response(JsonObject(status(4) + ("live" to buildJsonObject { put("elapsed", 2) })), false, false) }
    }

    @Test fun snapshotIsCommittedOnlyAfterApplyAndCommitsItsOwnNewerReference() {
        val sync = PlaybackRevisionSync()
        val initial = sync.probe(snapshot(4)); sync.accept(initial)
        sync.response(status(5), false, false); assertTrue(sync.needsSnapshot)
        val fetched = sync.snapshot(snapshot(6), false, false)
        assertTrue(sync.needsSnapshot) // Fetch alone is not application.
        rejected { sync.snapshot(JsonObject(snapshot(7) + ("snapshot" to JsonNull)), false, false) }
        assertTrue(sync.needsSnapshot)
        sync.accept(fetched); assertFalse(sync.needsSnapshot)
        val tick = sync.response(status(6, state().copy(elapsed = 19.0, playing = true, error = "media"), lease = 0, active = ""), false, false)
        assertEquals(19.0, tick.state!!.elapsed, 0.0); assertEquals("media", tick.state!!.error)
        assertEquals(0, tick.leaseMs); assertEquals("", tick.activeDeviceId); assertEquals("blocked", tick.commands.single().status)
        assertFalse(sync.needsSnapshot)
        assertNull(tick.publicationAck) // Observation must never confer upload base.
    }

    private class CountingQueue(private val source: List<PlaybackTrack>) : AbstractList<PlaybackTrack>() {
        var reads = 0
        override val size get() = source.size
        override fun get(index: Int): PlaybackTrack { reads++; return source[index] }
    }
    @Test fun quietBidirectionalBytesAndQueueWorkAreIndependentOfQueueLength() {
        val uploadSizes = mutableListOf<Int>(); val downloadSizes = mutableListOf<Int>()
        for (count in listOf(1,40,1000,5000)) {
            val s = state(count); val queue = CountingQueue(s.queue)
            val full = view(s.copy(queue = queue)).validated().also { it.syncSnapshotReference = PlaybackSyncReference("room",4) }
            val sync = PlaybackRevisionSync(); sync.probe(snapshot(4, s)); sync.accept(full)
            val tick = status(4, s)
            queue.reads = 0
            repeat(5) {
                val result = sync.response(tick, false, false).validated()
                assertSame(queue, result.state!!.queue)
            }
            assertEquals("Only current-key lookups, no queue export/validation/iteration",5,queue.reads)
            val upload = buildJsonObject { put("action","poll"); put("deviceId","phone"); put("epoch",2); put("stateSeq",2)
                put("base",PlaybackSyncReference("room",4).toJson()); put("live",PlaybackSyncLive.from(s).toJson()); put("acks",JsonArray(emptyList())) }
            val up = upload.toString().toByteArray().size; val down = tick.toString().toByteArray().size
            uploadSizes += up; downloadSizes += down
            val fullBytes = snapshot(4,s).toString().toByteArray().size
            assertTrue("The complete $count-entry fixture fits the independent full-body budget", fullBytes < MAX_PLAYBACK_BODY_BYTES)
            println("revision-v1 queue=$count uploadBytes=$up downloadBytes=$down fullSnapshotBytes=$fullBytes queueReadsPerPoll=1 fullExportsPerPoll=0")
        }
        assertEquals(1,uploadSizes.distinct().size); assertEquals(1,downloadSizes.distinct().size)
    }

    @Test fun quietNegotiatedReceiverReusesValidatedSidecarAndDoesNotRevalidateOrigins() {
        val s = state(5000); val queue = CountingQueue(s.queue)
        var originReads = 0
        val origins = object : AbstractList<QueueIntentOrigin>() {
            override val size = s.queue.size
            override fun get(index: Int): QueueIntentOrigin { originReads++; return QueueIntentOrigin(index + 1L, "low") }
        }
        val metadata = QueueIntentState("receiver",9,"tracked",origins,emptyList())
        PlaybackPublication.validatePoll("phone",2,1,s,emptyList(),metadata)
        val binding = QueueIntentBinding("phone","receiver",9,1,"event")
        val extension = QueueIntentsView(1,listOf(QueueIntentReceiver("phone",1)),binding,metadata,emptyList(),emptyList())
        val full = view(s.copy(queue = queue)).copy(queueIntents = extension).validated().also { it.syncSnapshotReference = PlaybackSyncReference("room",4) }
        val sync = PlaybackRevisionSync()
        sync.probe(JsonObject(snapshot(4,s) + ("snapshot" to full.toJson()))); sync.accept(full)
        val quiet = status(4,s)
        val qc = JsonObject(playbackJson.encodeToJsonElement(QueueIntentsView.serializer(),extension).jsonObject.filterKeys { it != "state" })
        val wire = JsonObject(quiet + ("control" to JsonObject(quiet.getValue("control").jsonObject + ("queueIntents" to qc))))
        queue.reads = 0; originReads = 0
        repeat(5) {
            val tick = sync.response(PlaybackRawJson.parse(wire.toString()),true,false).validated()
            assertSame(metadata,tick.queueIntents!!.state); assertEquals(binding,tick.queueIntents!!.binding)
        }
        assertEquals(0,originReads); assertEquals(10,queue.reads) // Current live + current binding only.
        val badBinding = JsonObject(qc + ("binding" to binding.copy(revision = 10).toJson()))
        rejected { sync.response(JsonObject(wire + ("control" to JsonObject(wire.getValue("control").jsonObject + ("queueIntents" to badBinding)))),true,false) }
    }

    @Test fun malformedAdvertisedVersionFailsClosedBeforeFallbackRegistrationOrExtendedWrites() = runBlocking {
        for (version in listOf("null", "true", "false", "\"1\"", "\"2\"", "1.0", "2.5", "1e0", "0", "-1", "9007199254740992")) {
            val server = MockWebServer(); server.start()
            try {
                val client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${server.port}"), MemorySessionStore())
                server.enqueue(loginResponse()); client.login("synthetic", "password"); server.takeRequest()
                val api = PlaybackDevicesApi(client)
                val malformed = snapshot(4).toString().replace("\"version\":1,\"incarnation\"", "\"version\":$version,\"incarnation\"")
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(malformed))
                // A permissive fallback would consume this and proceed to registration.
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(view().toJson().toString()))
                try {
                    api.register("Phone", null, "a".repeat(32), "receiver")
                    fail("Malformed advertised version $version admitted registration")
                } catch (failure: Exception) {
                    assertTrue(failure is IllegalArgumentException || failure is ApiException)
                }
                assertFalse(api.revisionSyncSupported)
                try {
                    api.pollLive("phone", 2, 2, PlaybackSyncReference("room",4), PlaybackSyncLive.from(state()))
                    fail("Malformed probe authorized extended writes")
                } catch (_: IllegalStateException) { }
                val probe = server.takeRequest()
                assertEquals("GET",probe.method); assertEquals("revision-v1",probe.getHeader("X-Spofify-Playback-Sync"))
                assertNull("No fallback GET or POST for version $version", server.takeRequest(30, java.util.concurrent.TimeUnit.MILLISECONDS))
                assertEquals(2,server.requestCount) // Login + read probe only.
            } finally { server.shutdown() }
        }
    }

    @Test fun probeUsesIndependentHeadersAndLegacyOrUnknownSupportNeverSendsNewFields() = runBlocking {
        for (mode in listOf("legacy", "unknown", "unknown-max-safe", "supported")) {
            val server = MockWebServer(); server.start()
            try {
                val client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${server.port}"), MemorySessionStore())
                server.enqueue(loginResponse()); client.login("synthetic","password"); server.takeRequest()
                fun enqueue(json: JsonObject) = server.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody(json.toString()))
                val api = PlaybackDevicesApi(client)
                enqueue(when(mode) { "legacy" -> view().toJson(); "unknown", "unknown-max-safe" -> JsonObject(snapshot(4) + ("sync" to JsonObject(ref(4) + ("version" to JsonPrimitive(if (mode == "unknown") 2L else 9007199254740991L))))); else -> snapshot(4) })
                if(mode.startsWith("unknown")) enqueue(view().toJson())
                val probe = api.probeQueueIntents()
                val request = server.takeRequest()
                assertEquals("GET",request.method)
                assertEquals("revision-v1",request.getHeader("X-Spofify-Playback-Sync"))
                assertEquals("queue-intents-v1",request.getHeader("X-Spofify-Playback-Extensions"))
                assertEquals("installation-v1",request.getHeader("X-Spofify-Playback-Registration"))
                assertEquals("last-requested-v1",request.getHeader("X-Spofify-Playback-Leadership"))
                if(mode.startsWith("unknown")) assertNull(server.takeRequest().getHeader("X-Spofify-Playback-Sync"))
                assertEquals(mode == "supported",api.revisionSyncSupported)
                if(mode == "supported") {
                    api.acceptSyncSnapshot(probe); enqueue(status(4)); api.pollLive("phone",2,2,PlaybackSyncReference("room",4),PlaybackSyncLive.from(state()))
                    val sent = server.takeRequest(); assertEquals("revision-v1",sent.getHeader("X-Spofify-Playback-Sync"))
                    val body = Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
                    assertTrue("live" in body); assertFalse("state" in body); assertFalse("queueIntentState" in body)
                } else {
                    enqueue(view().toJson()); api.poll("phone",2,2,state())
                    val sent = server.takeRequest(); assertNull(sent.getHeader("X-Spofify-Playback-Sync"))
                    val body = Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
                    assertTrue("state" in body); assertFalse("live" in body); assertFalse("base" in body)
                }
            } finally { server.shutdown() }
        }
    }
}
