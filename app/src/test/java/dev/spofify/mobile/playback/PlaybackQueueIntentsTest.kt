package dev.spofify.mobile.playback

import dev.spofify.mobile.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Shared Go ordering records, never a second handwritten Kotlin ordering oracle. */
internal object QueueIntentGolden {
    fun records(): JsonArray {
        val file = listOf(File("../core/src/test/resources/queue-intents-v1/queue-intents-v1.json"),
            File("core/src/test/resources/queue-intents-v1/queue-intents-v1.json"))
            .first { it.isFile }
        val bytes = file.readBytes()
        check(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } ==
            "621f5e5eea8632ea732c7369ae03bc442db53ec82e3d0edd7a9052a24547f44b")
        return Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject.getValue("semantics").jsonArray
    }
    fun names(value: JsonObject, field: String) = value.getValue(field).jsonArray.map { it.jsonPrimitive.content }
    fun track(name: String, key: Long) = PlaybackTrack(name, name, emptyList(), "", "", 120.0, key = key, order = key)
}

class PlaybackQueueIntentsTest {
    @Test fun everyFrozenSemanticRecordDrivesTheNativeProspectivePlanner() {
        for (element in QueueIntentGolden.records()) {
            val row = element.jsonObject
            val names = QueueIntentGolden.names(row, "queue")
            val keys = (names + QueueIntentGolden.names(row, "additions")).distinct().mapIndexed { i, name -> name to i + 1L }.toMap()
            val queue = names.map { QueueIntentGolden.track(it, keys.getValue(it)) }
            val position = row.getValue("position").jsonPrimitive.int
            val before = PlaybackSnapshot(queue = queue, position = position,
                elapsed = if (queue.isEmpty()) 0.0 else 42.0, playing = row.getValue("active").jsonPrimitive.boolean)
            val high = QueueIntentGolden.names(row, "high").map { keys.getValue(it) }.toSet()
            val metadata = PlaybackQueueIntents.Metadata(queue.associate { it.key to if (it.key in high) "high" else "low" }, high, "tracked")
            val requested = QueueIntentGolden.names(row, "additions").map { QueueIntentGolden.track(it, keys.getValue(it)) }
            val selected = row["selectionKey"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content?.let { keys.getValue(it) }
            val plan = PlaybackQueueIntents.plan(before, metadata, row.getValue("intent").jsonPrimitive.content,
                requested, selected, queue.maxOfOrNull { it.key } ?: 0)
            val label = row.getValue("name").jsonPrimitive.content
            assertEquals(label, QueueIntentGolden.names(row, "expectedQueue"), plan.snapshot.queue.map { it.name })
            assertEquals(label, QueueIntentGolden.names(row, "expectedHigh"), plan.snapshot.queue.filter { it.key in plan.metadata.high }.map { it.name })
            assertEquals(label, row.getValue("expectedCurrent").jsonPrimitive.content, plan.snapshot.queue.getOrNull(plan.snapshot.position)?.name)
            if (before.queue.getOrNull(before.position)?.key == plan.snapshot.queue.getOrNull(plan.snapshot.position)?.key) {
                assertEquals(label, before.playing, plan.snapshot.playing)
                assertEquals(label, before.elapsed, plan.snapshot.elapsed, 0.0)
                assertEquals(label, before.occurrence, plan.snapshot.occurrence)
            }
        }
    }

    @Test fun revisionIncludesUnsampledABAButExcludesPresentationProgressAndReported() {
        val a = QueueIntentGolden.track("A", 1)
        val snapshot = PlaybackSnapshot(queue = listOf(a), position = 0,
            occurrence = PlaybackOccurrence(1, "A", "event", 1.0, false))
        val metadata = PlaybackQueueIntents.Metadata(mapOf(1L to "low"), emptySet(), "tracked")
        val ledger = PlaybackQueueIntentRevision()
        val first = ledger.observe(snapshot, metadata)
        assertEquals(first, ledger.observe(snapshot.copy(elapsed = 42.0, duration = 300.0, playing = true,
            waiting = true, loading = true, error = "media", occurrence = snapshot.occurrence!!.copy(progress = 50.0, reported = true)), metadata))
        ledger.observe(snapshot.copy(repeat = "all"), metadata)
        assertTrue(ledger.observe(snapshot, metadata) > first)
        val prior = ledger.revision
        ledger.observe(snapshot.copy(occurrence = snapshot.occurrence!!.copy(eventId = "other")), metadata)
        assertTrue(ledger.observe(snapshot, metadata) > prior)
        val independent = PlaybackQueueIntentRevision()
        assertNotEquals(ledger.receiverId, independent.receiverId)
    }

    @Test fun derivedDedupDoesNotRetagOrResumeButManualDuplicatesStayDistinct() {
        val context = PlaybackContext("derived", "rules", "Rules", 1, "fence")
        val current = QueueIntentGolden.track("same", 1).copy(targetId = "rules", occurrenceId = "path", playbackContext = context)
        val before = PlaybackSnapshot(queue = listOf(current), position = 0, elapsed = 42.0, error = "media")
        val metadata = PlaybackQueueIntents.Metadata(mapOf(1L to "low"), emptySet(), "tracked")
        val noop = PlaybackQueueIntents.plan(before, metadata, "high", listOf(current), null, 1)
        assertEquals(0, noop.count); assertEquals(before, noop.snapshot); assertEquals(metadata, noop.metadata)
        val manual = current.copy(targetId = "", occurrenceId = "", playbackContext = null, entryId = "duplicate-entry")
        val added = PlaybackQueueIntents.plan(before, metadata, "high", listOf(manual, manual.copy(entryId = "other-entry")), null, 1)
        assertEquals(2, added.count); assertEquals(3, added.snapshot.queue.map { it.key }.distinct().size)
        assertEquals(42.0, added.snapshot.elapsed, 0.0); assertEquals("media", added.snapshot.error)
    }

    @Test fun combinedPublicationMetadataIsBoundedAndMeasuredAtOneAndOneThousand() {
        for (size in listOf(1, 1000)) {
            val queue = (1..size).map { QueueIntentGolden.track("T$it", it.toLong()) }
            val snapshot = PlaybackSnapshot(queue = queue, position = 0)
            val metadata = PlaybackQueueIntents.Metadata(queue.associate { it.key to "low" }, emptySet(), "tracked")
            val timings = mutableListOf<Long>()
            repeat(35) { iteration ->
                val started = System.nanoTime()
                val state = PlaybackQueueIntents.state("native_perf", 1, snapshot, metadata)
                PlaybackQueueIntentRevision("native_perf").observe(snapshot, metadata)
                if (iteration >= 5) timings.add(System.nanoTime() - started)
                state.validated(snapshot)
            }
            val started = System.nanoTime()
            val state = PlaybackQueueIntents.state("native_perf", 1, snapshot, metadata)
            PlaybackQueueIntents.publication(snapshot, state)
            val bytes = PlaybackPublication.pollBody("phone", 7, 1, snapshot, emptyList(), state)
                .toString().toByteArray(Charsets.UTF_8).size
            assertTrue(bytes < 2 * 1024 * 1024)
            val coldAdmissionMs = (System.nanoTime() - started) / 1e6
            timings.sort()
            // Measure the complete synchronous prospective transaction, not metadata alone:
            // 0 -> 1 and 999 -> 1000 entries, plus its detailed terminal ACK envelope.
            // Audio/resolver/network latency is deliberately NOT part of this CPU measurement.
            val before = snapshot.copy(queue = queue.dropLast(1), position = if (size == 1) -1 else 0)
            val beforeMetadata = PlaybackQueueIntents.Metadata(before.queue.associate { it.key to "low" }, emptySet(), "tracked")
            val transactionTimes = mutableListOf<Long>()
            var detailedBytes = 0
            repeat(35) { iteration ->
                val transactionStart = System.nanoTime()
                val plan = PlaybackQueueIntents.plan(before, beforeMetadata, "high", listOf(queue.last()), null, (size - 1).toLong())
                assertEquals(1, plan.count)
                val committedSnapshot = if (size == 1) plan.snapshot.copy(occurrence = PlaybackOccurrence(
                    plan.snapshot.queue.single().key, plan.snapshot.queue.single().id,
                    "00000000-0000-4000-8000-000000000001", 0.0, false)) else plan.snapshot
                val successor = PlaybackQueueIntents.state("native_perf", 2, committedSnapshot, plan.metadata)
                // For the changed-current first insert, model the valid postcommit Pause
                // receipt; this benchmark does not pretend to perform an audible start.
                val detail = QueueIntentResult("queued", 1, 2, "high", if (size == 1) "superseded" else "unchanged", null, "")
                PlaybackQueueIntents.publication(committedSnapshot, successor)
                detailedBytes = PlaybackPublication.pollBody("phone", 7, 2, committedSnapshot,
                    listOf(PlaybackAck("perf_delivery", 7, "ok", detail)), successor)
                    .toString().toByteArray(Charsets.UTF_8).size
                if (iteration >= 5) transactionTimes.add(System.nanoTime() - transactionStart)
            }
            assertTrue(detailedBytes < 2 * 1024 * 1024)
            transactionTimes.sort()
            println("queue-intents native entries=$size combinedPollBytes=$bytes detailedPollBytes=$detailedBytes metadataMedianMs=${timings[15] / 1e6} metadataP95Ms=${timings[28] / 1e6} coldJointAdmissionMs=$coldAdmissionMs prospectiveTransactionMedianMs=${transactionTimes[15] / 1e6} prospectiveTransactionP95Ms=${transactionTimes[28] / 1e6}")
        }
    }

    @Test fun largeWholeBatchesPreservePausedCurrentAndExactPriorityPlacement() {
        for (size in listOf(1001, 5000)) {
            val before = PlaybackSnapshot(queue = listOf(QueueIntentGolden.track("A", 1), QueueIntentGolden.track("H", 2)),
                position = 0, elapsed = 42.125, loading = true, error = "media",
                occurrence = PlaybackOccurrence(1, "A", "event", 12.0, true))
            val metadata = PlaybackQueueIntents.Metadata(mapOf(1L to "low", 2L to "high"), setOf(2L), "tracked")
            val additions = (0 until size).map { QueueIntentGolden.track("same", it + 1L).copy(entryId = "e$it") }
            for (intent in listOf("low", "high", "next", "now")) {
                val plan = PlaybackQueueIntents.plan(before, metadata, intent, additions, null, 2, startIndex = size - 1)
                assertEquals(size, plan.count)
                assertFalse(plan.limited)
                val projected = if (intent == "now") plan.snapshot.queue else plan.snapshot.queue.filter { it.key > 2 }
                assertEquals(additions.map { it.entryId }, projected.map { it.entryId })
                if (intent == "now") {
                    assertEquals(size - 1, plan.snapshot.position)
                    assertEquals("e${size - 1}", plan.snapshot.queue[plan.snapshot.position].entryId)
                } else {
                    assertEquals(before, plan.snapshot.copy(queue = before.queue))
                    assertEquals(if (intent == "next") size + 1 else 1, plan.snapshot.queue.indexOfFirst { it.id == "H" })
                }
                val state = PlaybackQueueIntents.state("large", 2, plan.snapshot, plan.metadata)
                PlaybackQueueIntents.publication(plan.snapshot, state)
            }
        }
    }

    @Test fun prospectiveOverflowRejectsBeforeAnyLiveStateAndProvenanceOnlyIsAnEdit() {
        val before = PlaybackSnapshot(queue = listOf(QueueIntentGolden.track("A", 1), QueueIntentGolden.track("X", 2)), position = 0)
        val metadata = PlaybackQueueIntents.Metadata(mapOf(1L to "low", 2L to "low"), emptySet(), "tracked")
        val plan = PlaybackQueueIntents.plan(before, metadata, "high", emptyList(), 2, 2)
        assertEquals(before.queue, plan.snapshot.queue); assertEquals(1, plan.count)
        assertEquals("high", plan.metadata.origins[2]); assertEquals(setOf(2L), plan.metadata.high)
        val same = PlaybackQueueIntents.plan(plan.snapshot, plan.metadata, "high", emptyList(), 2, 2)
        assertEquals(0, same.count)
        val long = "a".repeat(512)
        val massive = (1..1000).map { QueueIntentGolden.track("T$it", it.toLong()).copy(name = long,
            albumName = long, artists = List(4) { PlaybackArtist("artist", long) }) }
        val snapshot = PlaybackSnapshot(queue = massive, position = 0)
        val state = PlaybackQueueIntents.state("native_perf", 1, snapshot,
            PlaybackQueueIntents.Metadata(massive.associate { it.key to "low" }, emptySet(), "tracked"))
        assertThrows(IllegalArgumentException::class.java) { PlaybackQueueIntents.publication(snapshot, state) }
        assertEquals(listOf("A", "X"), before.queue.map { it.id })
    }
}
