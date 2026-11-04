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
