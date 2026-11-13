package dev.spofify.mobile.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LocalPlaybackModelsTest {
    private val context = PlaybackContext("derived", "rules", "Original rules", 7, "original-fence")
    private fun snapshot(length: Int, size: Int = 1000) = PlaybackSnapshot(
        queue = (0 until size).map { index -> PlaybackTrack("duplicate", "Song", emptyList(), "Album", "album", 120.0,
            targetId = context.id, occurrenceId = "x".repeat(length) + "-$index", playbackContext = context,
            key = index + 1L, order = (size - index - 1).toLong()) },
        position = 17, context = context, shuffle = true, elapsed = 31.125,
        occurrence = PlaybackOccurrence(18, "duplicate", "original-event", 17.875, true),
    ).validated()
    private fun rejects(block: () -> Unit) = assertThrows(IllegalArgumentException::class.java) { block() }

    @Test fun longDerivedOccurrencesRoundTripEveryIdentityAndObservationLocally() {
        val state = snapshot(1400)
        assertEquals(state, PlaybackSnapshot.fromJson(state.toJson()))
        assertEquals(1000, state.queue.map { it.occurrenceId }.distinct().size)
        assertEquals((999 downTo 0).map(Int::toLong), state.queue.map { it.order })
        assertEquals("original-event", state.occurrence!!.eventId)
        assertEquals(17.875, state.occurrence.progress, 0.0)
        assertTrue(state.occurrence.reported)
        assertEquals(31.125, state.elapsed, 0.0)
        assertEquals(context, state.context)
    }

    @Test fun localSerializationIsNotAnOnlinePublicationAdmission() {
        val large = snapshot(4080)
        assertTrue(large.queue.all { it.occurrenceId.toByteArray(Charsets.UTF_8).size <= 4096 })
        assertTrue(large.toJson().toString().toByteArray(Charsets.UTF_8).size > 2 * 1024 * 1024)
        assertEquals(large, PlaybackSnapshot.fromJson(large.toJson()))
    }

    @Test fun compactLocalQueuesHaveNoThousandSongCapAndKeepManualDuplicates() {
        for (size in listOf(1001, 5000)) {
            val queue = (1..size).map { PlaybackTrack("same", "Song", emptyList(), "", "", 120.0,
                entryId = "e$it", key = it.toLong(), order = (size - it).toLong()) }
            val state = PlaybackSnapshot(queue = queue, position = 0, elapsed = 42.125,
                occurrence = PlaybackOccurrence(1, "same", "event", 17.0, true))
            val decoded = PlaybackSnapshot.fromJson(state.toJson())
            assertEquals(state, decoded)
            assertEquals(size, decoded.queue.map { it.entryId }.distinct().size)
            assertFalse(decoded.playing)
        }
    }

    @Test fun pausingPreservesExactMusicPositionModesAndHistory() {
        val before = snapshot(1).copy(playing = true, waiting = true, loading = true)
        assertEquals(before.copy(playing = false, waiting = false, loading = false), before.paused())
        assertEquals(before.queue, before.paused().queue)
        assertEquals(before.occurrence, before.paused().occurrence)
    }

    @Test fun invalidLocalIdentityAndObservationShapesReject() {
        val state = snapshot(1)
        val first = state.queue.first()
        rejects { state.copy(position = -1).validated() }
        rejects { state.copy(queue = state.queue + first.copy(order = 1001)).validated() }
        rejects { state.copy(queue = state.queue + first.copy(key = 1001)).validated() }
        rejects { state.copy(occurrence = state.occurrence!!.copy(key = 1)).validated() }
        rejects { state.copy(occurrence = state.occurrence!!.copy(trackId = "other")).validated() }
        rejects { state.copy(occurrence = state.occurrence!!.copy(progress = Double.NaN)).validated() }
        rejects { state.copy(elapsed = Double.POSITIVE_INFINITY).validated() }
        rejects { state.copy(duration = -1.0).validated() }
        rejects { PlaybackSnapshot(queue = listOf(first.copy(key = 9007199254740992L)), position = 0).validated() }
        rejects { PlaybackSnapshot(position = 0).validated() }
    }

    @Test fun localMetadataUsesUtf8ByteBoundsAndRejectsControlsAndMalformedUnicode() {
        val track = PlaybackTrack("曲/opaque🎵", "🎵".repeat(128), listOf(PlaybackArtist("artist/音", "Björk")), "Album", "album", 123.456,
            key = 1, order = 0)
        val state = PlaybackSnapshot(queue = listOf(track), position = 0)
        assertEquals(state, PlaybackSnapshot.fromJson(state.toJson()))
        for (invalid in listOf("🎵".repeat(129), "bad\nname", "\uD800", "\uDC00")) {
            rejects { state.copy(queue = listOf(track.copy(name = invalid))).validated() }
        }
        assertFalse(state.toJson().toString().contains("selectionToken"))
        assertFalse(state.toJson().toString().contains("libraryPath"))
    }

    @Test fun localJsonRejectsTypeConfusionAndPrivateOrProtocolFields() {
        val state = snapshot(1)
        val json = state.toJson()
        for ((key, value) in mapOf(
            "version" to JsonPrimitive("1"), "position" to JsonPrimitive(1.5),
            "elapsed" to JsonPrimitive("1"), "shuffle" to JsonPrimitive("true"),
            "notice" to JsonPrimitive(123), "leaseMs" to JsonPrimitive(8000),
            "queueIntentState" to buildJsonObject {}, "libraryPath" to JsonPrimitive("/private/music"),
        )) rejects { PlaybackSnapshot.fromJson(JsonObject(json + (key to value))) }
        val track = state.queue.first().let { state.copy(queue = listOf(it), position = 0, occurrence = null) }.toJson()
        for (field in listOf("url", "selectionToken", "libraryPath")) {
            val unsafe = JsonObject(track + ("queue" to JsonArray(listOf(JsonObject(
                track.getValue("queue").jsonArray.single().jsonObject + (field to JsonPrimitive("private-value")))))))
            rejects { PlaybackSnapshot.fromJson(unsafe) }
        }
    }

    @Test fun localTransportFactoriesHaveNoMusicalOrReceiverVariants() {
        for (type in listOf("pause", "resume", "next", "previous", "retry", "shuffle", "repeat", "close", "clear")) {
            val command = LocalPlaybackCommand.transport(type)
            assertEquals(type, command.type)
            assertEquals(buildJsonObject { put("type", type) }, command.json)
        }
        for (type in listOf("toggle", "play", "playCollection", "enqueue", "enqueueContext", "queueIntent", "select")) {
            rejects { LocalPlaybackCommand.transport(type) }
        }
        assertEquals(buildJsonObject { put("type", "seek"); put("value", 12.5) }, LocalPlaybackCommand.seek(12.5).json)
        for (number in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 604801.0)) rejects { LocalPlaybackCommand.seek(number) }
        assertEquals(buildJsonObject { put("type", "jump"); put("key", 42) }, LocalPlaybackCommand.queue("jump", 42).json)
        for (direction in listOf("up", "down", "next")) {
            assertEquals(direction, LocalPlaybackCommand.queue("move", 42, direction).json.getValue("direction").jsonPrimitive.content)
        }
        rejects { LocalPlaybackCommand.queue("jump", 42, "up") }
        rejects { LocalPlaybackCommand.queue("move", 42) }
        rejects { LocalPlaybackCommand.queue("remove", 0) }
        rejects { LocalPlaybackCommand.queue("remove", 9007199254740992L) }
    }

    @Test fun localIntentVocabularyStaysExactAndSerializable() {
        val json = Json
        for ((intent, text) in QueueIntentIntent.entries.zip(listOf("low", "high", "next", "now"))) {
            assertEquals(text, intent.wire)
            assertEquals(intent, QueueIntentIntent.fromWire(text))
            assertEquals(JsonPrimitive(text), json.encodeToJsonElement(QueueIntentIntent.serializer(), intent))
        }
        for (text in listOf("HIGH", "enqueue", "", "low ")) rejects { QueueIntentIntent.fromWire(text) }
    }

    @Test fun secureInstallationMetadataValidationNeedsNoRegistrationDto() {
        for (length in 32..64 step 2) assertTrue(validPlaybackInstallationId("a".repeat(length)))
        for (id in listOf("", "a".repeat(31), "a".repeat(33), "a".repeat(65), "A".repeat(32), "g".repeat(32), "0".repeat(31) + "\n")) {
            assertFalse(validPlaybackInstallationId(id))
        }
        assertTrue(validPlaybackDeviceName("🎵".repeat(64)))
        assertFalse(validPlaybackDeviceName("🎵".repeat(65)))
        assertFalse(validPlaybackDeviceName("bad\nname"))
        assertFalse(validPlaybackDeviceName("\uD800"))
    }
}
