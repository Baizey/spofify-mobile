package dev.spofify.mobile.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

internal fun libraryTrack(id: String = "曲/opaque🎵", key: Long = 1) = LibraryPlaybackTrack(
    id, "Canonical", emptyList(), "Album", "album", 24.0, key, key, "now",
)
internal fun librarySnapshotFrame(queue: List<LibraryPlaybackTrack> = emptyList()): String {
    val occurrence = queue.firstOrNull()?.let { LibraryPlaybackOccurrence(it.key, it.id, "event", 0.0, false) }
    val state = LibraryPlaybackState(1, queue, if (queue.isEmpty()) -1 else 0, null, false, "off", false,
        emptyList(), 0.0, 0.0, false, false, false, "", false, "", occurrence)
    val playback = LibraryPlaybackStatus(1, 1, 0, occurrence?.eventId ?: "", false, 0.0, 0.0, false, false, false, "")
    val snapshot = LibraryPlaybackSnapshot("session", 1, 1, state, playback,
        listOf(LibraryPlaybackDevice("phone", "Phone", true, true)), "", "", null, 0)
    return Json.encodeToString(LibraryPlaybackServerMessage("snapshot", "session", "connection", "phone", snapshot))
}
internal fun libraryInstructionFrame(type: String = "activate", fields: String = "\"lease\":{\"clientSequence\":1,\"durationMs\":8000}") =
    """{"type":"instruction","sessionId":"session","instruction":{"id":"execution","type":"$type","outputVersion":1,"eventId":"event","elapsed":0,"desiredPlaying":false${if (fields.isEmpty()) "" else ",$fields"}}}"""

class LibraryPlaybackCodecTest {
    private fun reject(raw: String) {
        try { LibraryPlaybackCodec.decodeServer(raw); fail("Invalid frame accepted") }
        catch (e: LibraryPlaybackProtocolException) { assertEquals("Invalid library playback message", e.message) }
    }
    @Test fun exactSnapshotAndScalarPlaybackSchemasRemainDistinct() {
        val snapshot = LibraryPlaybackCodec.decodeServer(librarySnapshotFrame(listOf(libraryTrack()))).snapshot!!
        assertEquals(1, snapshot.state.version)
        assertEquals("曲/opaque🎵", snapshot.state.queue.single().id)
        val status = Json.encodeToString(snapshot.playback)
        val frame = """{"type":"playback","sessionId":"session","playback":$status}"""
        assertEquals(snapshot.playback, LibraryPlaybackCodec.decodeServer(frame).playback)
        reject(frame.replace("\"eventId\":", "\"instructionId\":\"execution\",\"eventId\":"))
        reject(frame.replace("\"stateVersion\":1", "\"stateVersion\":1,\"serverTimeMs\":1"))
    }
    @Test fun strictRecursiveSchemaRejectsDuplicatesUnknownsAliasesNullsAndWrongTypes() {
        val raw = librarySnapshotFrame(listOf(libraryTrack()))
        val invalid = listOf(
            raw + "{}", raw.replace("\"type\":\"snapshot\"", "\"type\":\"snapshot\",\"type\":\"state\""),
            raw.replace("\"name\":\"Canonical\"", "\"name\":\"Canonical\",\"name\":\"Other\""),
            raw.replace("\"duration\":24.0", "\"duration\":24.0,\"libraryPath\":\"/private/secret\""),
            raw.replace("\"stateVersion\":1", "\"StateVersion\":1"),
            raw.replace("\"stateVersion\":1", "\"revision\":1"),
            raw.replace("\"stateVersion\":1", "\"stateVersion\":null"),
            raw.replace("\"stateVersion\":1", "\"stateVersion\":\"1\""),
            raw.replace("\"stateVersion\":1", "\"stateVersion\":1.0"),
            raw.replace("\"stateVersion\":1", "\"stateVersion\":9007199254740992"),
            raw.replace("\"isPlaying\":false", "\"isPlaying\":\"false\""),
            raw.replace("\"duration\":24.0", "\"duration\":NaN"),
            raw.replace("\"duration\":24.0", "\"duration\":1e100"),
            raw.replace("\"artists\":[]", "\"artists\":null"),
            raw.replace("\"albumId\":\"album\"", "\"albumId\":null"),
            raw.replace("\"name\":\"Canonical\"", "\"name\":\"\\uD800\""),
            raw.replace("\"version\":1", "\"version\":2"), raw.replace("\"truncated\":false", "\"truncated\":true"),
            raw.replace("\"key\":1", "\"key\":0"),
        )
        invalid.forEach(::reject)
    }
    @Test fun malformedUtf8AndIndependentInboundAndLogicalStateBytesFailClosed() {
        try { LibraryPlaybackCodec.decodeServer(byteArrayOf(0xc3.toByte(), 0x28)); fail() } catch (_: LibraryPlaybackProtocolException) { }
        reject(" ".repeat(LibraryPlaybackProtocol.INBOUND_BYTES + 1))
        val fullMetadata = libraryTrack().copy(name = "🎵".repeat(128), albumName = "a".repeat(512),
            artists = List(4) { LibraryPlaybackArtist("b".repeat(512)) })
        // All individual fields are valid; only the complete logical state exceeds 2 MiB.
        val oversized = librarySnapshotFrame((1L..700L).map { fullMetadata.copy(key = it, order = it) })
        assertTrue(oversized.toByteArray().size < LibraryPlaybackProtocol.INBOUND_BYTES)
        assertTrue(Json.parseToJsonElement(oversized).jsonObject.getValue("snapshot").jsonObject.getValue("state").toString().toByteArray().size > LibraryPlaybackProtocol.STATE_BYTES)
        reject(oversized)
    }
    @Test fun completeQueuesHaveNoArbitraryTrackCountLimitAndRetainDuplicateTrackOccurrences() {
        val queue = (1L..1101L).map { libraryTrack("duplicate", it) }
        val decoded = LibraryPlaybackCodec.decodeServer(librarySnapshotFrame(queue)).snapshot!!.state
        assertEquals(1101, decoded.queue.size)
        assertEquals(queue.map { it.key }, decoded.queue.map { it.key })
        reject(librarySnapshotFrame(queue.take(2).map { it.copy(key = 1) }))
    }
    @Test fun instructionAndRenewalGrantsHaveDifferentExactShapes() {
        assertEquals(8000L, LibraryPlaybackCodec.decodeServer(libraryInstructionFrame()).instruction!!.lease!!.durationMs)
        val lease = """{"type":"lease","sessionId":"session","lease":{"outputVersion":7,"eventId":"event","clientSequence":2,"durationMs":8000}}"""
        assertEquals(2L, LibraryPlaybackCodec.decodeServer(lease).lease!!.clientSequence)
        reject(lease.replace("\"durationMs\":8000", "\"durationMs\":8000,\"instructionId\":\"execution\""))
        reject(lease.replace("\"durationMs\":8000", "\"durationMs\":8001"))
        reject(lease.replace("\"clientSequence\":2", "\"clientSequence\":0"))
        reject(libraryInstructionFrame("prepare"))
        reject(libraryInstructionFrame("activate", ""))
        assertNull(LibraryPlaybackCodec.decodeServer(libraryInstructionFrame("release", "")).instruction!!.lease)
        reject(libraryInstructionFrame("release", "\"next\":{}"))
    }
    @Test fun nextControlsAreTypedSingleTrackReservationsNotAuthority() {
        val next = Json.encodeToString(LibraryPlaybackNext("reserved-event", "reserved-execution", libraryTrack("🎵".repeat(128))))
        val fields = "\"planId\":\"plan\",\"currentInstructionId\":\"source\",\"next\":$next"
        val stage = LibraryPlaybackCodec.decodeServer(libraryInstructionFrame("stageNext", fields)).instruction!!
        assertNull(stage.lease)
        assertEquals(512, stage.next!!.track.id.toByteArray().size)
        reject(libraryInstructionFrame("cancelNext", fields))
        reject(libraryInstructionFrame("stageNext", fields + ",\"lease\":{\"clientSequence\":1,\"durationMs\":8000}"))
        reject(libraryInstructionFrame("stageNext", "\"planId\":\"plan\",\"currentInstructionId\":\"source\""))
    }
    @Test fun commandsAreIdentifierOnlyStrictVariantsWithRequiredNullableMoveAnchor() {
        val move = LibraryPlaybackCodec.encode(LibraryPlaybackClientMessage.Command(LibraryPlaybackCommand.Move(2, null)), "1")
        assertEquals("""{"type":"command","id":"1","command":{"type":"move","key":2,"afterKey":null}}""", move)
        val selection = LibraryPlaybackSelection.Track(LibraryPlaybackTrackRef("opaque/雪"))
        val track = LibraryPlaybackCodec.encode(LibraryPlaybackClientMessage.Command(LibraryPlaybackCommand.QueueIntent("now", selection)), "2")
        val fields = Json.parseToJsonElement(track).jsonObject.getValue("command").jsonObject.getValue("selection").jsonObject.getValue("track").jsonObject
        assertEquals(setOf("id"), fields.keys)
        assertEquals("opaque/雪", fields.getValue("id").jsonPrimitive.content)
        val collection = LibraryPlaybackSelection.Collection(LibraryPlaybackContext("album", "album", ""))
        val shuffle = LibraryPlaybackCodec.encode(LibraryPlaybackClientMessage.Command(LibraryPlaybackCommand.QueueIntent("now", collection, true)), "3")
        assertTrue(shuffle.contains("\"shuffle\":true"))
        for (bad in listOf(
            LibraryPlaybackCommand.QueueIntent("high", collection, true), LibraryPlaybackCommand.QueueIntent("now", selection, true),
            LibraryPlaybackCommand.Move(2, 2), LibraryPlaybackCommand.Seek(Double.NaN), LibraryPlaybackCommand.SetRepeat("toggle"),
            LibraryPlaybackCommand.QueueIntent("now", LibraryPlaybackSelection.Track(LibraryPlaybackTrackRef("duplicate"), LibraryPlaybackContext("playlist", "list", ""))),
        )) {
            try { LibraryPlaybackCodec.encode(LibraryPlaybackClientMessage.Command(bad), "4"); fail() } catch (_: LibraryPlaybackProtocolException) { }
        }
        for (id in listOf("01", "0", "-1", "9007199254740992")) {
            try { LibraryPlaybackCodec.encode(LibraryPlaybackClientMessage.Command(LibraryPlaybackCommand.Pause), id); fail() } catch (_: LibraryPlaybackProtocolException) { }
        }
    }
    @Test fun reportAndAckSequencesAreExplicitAndEndedPlanCannotAppearOnProgress() {
        val report = LibraryPlaybackClientMessage.Report(7, "event", "execution", 24.0, 24.0, false, false, false, "", true, "plan")
        val encoded = LibraryPlaybackCodec.encode(report, clientSequence = 9)
        assertTrue(encoded.contains("\"clientSequence\":9"))
        assertTrue(encoded.contains("\"planId\":\"plan\""))
        try { LibraryPlaybackCodec.encode(report.copy(ended = false), clientSequence = 10); fail() } catch (_: LibraryPlaybackProtocolException) { }
        try { LibraryPlaybackCodec.encode(report, clientSequence = 0); fail() } catch (_: LibraryPlaybackProtocolException) { }
        assertFalse(LibraryPlaybackTrackRef("track", selectionToken = "signed-secret").toString().contains("signed-secret"))
    }
    @Test fun ticketAndHelloNeverAcceptExtraOrMissingFieldsAndIdentityUsesUtf8Bytes() {
        val ticket = "a".repeat(64)
        assertEquals(ticket, LibraryPlaybackCodec.ticket("""{"ticket":"$ticket"}"""))
        for (raw in listOf("""{"ticket":"$ticket","ticket":"$ticket"}""", """{"ticket":"$ticket","sessionId":"x"}""", """{"ticket":null}""")) {
            try { LibraryPlaybackCodec.ticket(raw); fail() } catch (_: LibraryPlaybackProtocolException) { }
        }
        assertTrue(LibraryPlaybackCodec.hello(ticket, "Phone", "🎵".repeat(32), false).contains("\"canPlay\":false"))
        try { LibraryPlaybackCodec.hello(ticket, "Phone", "🎵".repeat(33), true); fail() } catch (_: LibraryPlaybackProtocolException) { }
    }
}
