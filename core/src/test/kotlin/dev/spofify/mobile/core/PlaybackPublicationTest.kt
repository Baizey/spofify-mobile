package dev.spofify.mobile.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackPublicationTest {
    private val context = PlaybackContext("derived", "rules", "Original rules", 7, "original-fence")
    private fun snapshot(length: Int) = PlaybackSnapshot(
        queue = (0 until 1000).map { index -> PlaybackTrack("duplicate", "Song", emptyList(), "Album", "album", 120.0,
            targetId = context.id, occurrenceId = "x".repeat(length) + "-$index", playbackContext = context,
            key = index + 1L, order = (999 - index).toLong()) },
        position = 17, context = context, shuffle = true, elapsed = 31.125,
        occurrence = PlaybackOccurrence(18, "duplicate", "original-event", 17.875, true),
    ).validated()

    @Test fun fullThousandLongDerivedOccurrencesFitWorstPollEnvelopeWithoutLosingIdentity() {
        val state = snapshot(1400).publicationValidated()
        val body = buildJsonObject {
            put("action", "poll"); put("deviceId", "d".repeat(128)); put("epoch", 9007199254740991L)
            put("stateSeq", 9007199254740991L); put("state", state.toJson())
            put("acks", buildJsonArray { repeat(16) { index -> add(buildJsonObject {
                put("deliveryId", index.toString().padStart(128, 'd')); put("epoch", 9007199254740991L); put("status", "rejected")
            }) } })
        }
        assertTrue(body.toString().toByteArray(Charsets.UTF_8).size < 2 * 1024 * 1024)
        assertEquals(state, PlaybackSnapshot.fromJson(state.toJson()))
        assertEquals(1000, state.queue.map { it.occurrenceId }.distinct().size)
        assertEquals((999 downTo 0).map(Int::toLong), state.queue.map { it.order })
        assertEquals("original-event", state.occurrence!!.eventId)
    }

    @Test fun typedCollectionsKeepLegacyCompleteLimitsButRejectEveryPartialShape() {
        val items = (1..3).map { LibraryItem("track", "t$it", "Song", hasAudio = true) }
        assertEquals(items, PlaybackQueue(items, total = 3, limit = 1000).items)
        assertEquals(items, PersonalPlayback(PersonalPlaybackContext("playlist", "manual", "Mix"),
            items, 3, 1000, false).queue.items)
        for ((total, limit, truncated) in listOf(Triple(4, 1000, false), Triple(3, 2, false),
            Triple(3, -1, false), Triple(3, 1000, true), Triple(-1, 1000, false))) {
            assertThrows(IllegalArgumentException::class.java) { PlaybackQueue(items, total, limit, truncated) }
            assertThrows(IllegalArgumentException::class.java) { PersonalPlayback(
                PersonalPlaybackContext("playlist", "manual", "Mix"), items, total.toLong(), limit, truncated) }
        }
        assertTrue(PlaybackQueue(emptyList(), total = 0, limit = 0).items.isEmpty())
        assertTrue(PlaybackQueue(emptyList(), total = 0, limit = 1000).items.isEmpty())
    }

    @Test fun compactLargeQueuePublishesEveryDuplicateOriginHighKeyAndReceipt() {
        for (size in listOf(1001, 5000)) {
            val queue = (1..size).map { PlaybackTrack("same", "Song", emptyList(), "", "", 120.0,
                entryId = "e$it", key = it.toLong(), order = (size - it).toLong()) }
            val state = PlaybackSnapshot(queue = queue, position = 0, elapsed = 42.125,
                occurrence = PlaybackOccurrence(1, "same", "event", 17.0, true))
            val metadata = QueueIntentState("receiver", 2, "tracked", queue.map { QueueIntentOrigin(it.key, "high") },
                queue.drop(1).map { it.key })
            val detail = QueueIntentResult("queued", size, 2, "high", "unchanged", null, "")
            val body = PlaybackPublication.pollBody("phone", 1, 2, state,
                listOf(PlaybackAck("delivery", 1, "ok", detail)), metadata)
            val parsed = PlaybackRawJson.parse(body.toString())
            assertEquals(state, PlaybackSnapshot.fromJson(parsed.getValue("state").jsonObject))
            assertEquals(metadata, QueueIntentState.fromJson(parsed.getValue("queueIntentState").jsonObject, state))
            assertEquals(detail, QueueIntentResult.fromJson(parsed.getValue("acks").jsonArray.single().jsonObject.getValue("queueIntentResult").jsonObject))
            assertEquals(size, state.queue.map { it.entryId }.distinct().size)
            assertFalse(state.playing)
            assertTrue(body.toString().toByteArray().size < MAX_PLAYBACK_BODY_BYTES)
        }
        val compact = PlaybackSnapshot(queue = listOf(snapshot(1).queue.first()), position = 0).toJson().toString()
        assertThrows(IllegalArgumentException::class.java) { PlaybackRawJson.parse(compact.replace("\"queue\":", "\"QUEUE\":[],\"queue\":")) }
        assertThrows(IllegalArgumentException::class.java) { PlaybackRawJson.parse("{\"queue\":" + "[".repeat(17) + "]".repeat(17) + "}") }
        assertThrows(IllegalArgumentException::class.java) { PlaybackRawJson.parse(compact, 16) }
        assertThrows(IllegalArgumentException::class.java) { QueueIntentResult("queued", -1, 2, "high", "unchanged", null, "").validated() }
    }

    @Test fun reserveCoversWorstEscapedContextHistoryScalarAndSixteenAckEnvelopeGrowth() {
        val baseline = PlaybackSnapshot(queue = listOf(snapshot(1).queue.first()), position = 0)
        val expanded = baseline.copy(
            context = PlaybackContext("album", "\\".repeat(512), "\"".repeat(512), 9007199254740991L, "\"".repeat(4096)),
            elapsed = 604800.0, duration = 604800.0, playing = true, waiting = true, loading = true,
            truncated = true, notice = "CLICKED_OCCURRENCE_ONLY", error = "blocked", repeat = "one", shuffle = true,
            occurrence = PlaybackOccurrence(1, "duplicate", "\\".repeat(128), 604800.0, true),
        ).validated()
        val body = buildJsonObject {
            put("action", "poll"); put("deviceId", "d".repeat(128)); put("epoch", 9007199254740991L)
            put("stateSeq", 9007199254740991L); put("state", expanded.toJson())
            put("acks", buildJsonArray { repeat(16) { index -> add(buildJsonObject {
                put("deliveryId", index.toString().padStart(128, 'd')); put("epoch", 9007199254740991L); put("status", "rejected")
            }) } })
        }
        val growth = body.toString().toByteArray().size - baseline.toJson().toString().toByteArray().size
        assertTrue("Worst envelope/state growth=$growth must fit reserved bytes", growth < PLAYBACK_PUBLICATION_RESERVE_BYTES)
        assertEquals(2 * 1024 * 1024, MAX_PLAYBACK_BODY_BYTES)
    }

    @Test fun individuallyValid4096BoundOccurrencesCanExceedAggregateAndMustRejectBeforePublication() {
        val oversized = snapshot(4080)
        assertTrue(oversized.queue.all { it.occurrenceId.toByteArray().size <= 4096 })
        assertTrue(oversized.toJson().toString().toByteArray().size > 2 * 1024 * 1024)
        assertEquals(oversized, PlaybackSnapshot.fromJson(oversized.toJson())) // Incoming wire validity is distinct.
        assertThrows(IllegalArgumentException::class.java) { oversized.publicationValidated() }
    }

    @Test fun derivedPreparedCallbackQueueRejectsAggregateBeforeLosingItsEnvelope() {
        val oversized = snapshot(4080)
        val response = DerivedPlayback(PersonalPlaybackContext("derived", "rules", "Original rules", 7, "original-fence"),
            oversized.queue.map { LibraryItem("track", it.id, it.name, hasAudio = true, entryId = it.occurrenceId) },
            emptyMap(), 1000, 1000, false, 7, "original-fence", oversized.queue[17].occurrenceId, null)
        assertThrows(IllegalArgumentException::class.java) { response.toPlaybackQueue() }
    }
}
