package dev.spofify.mobile.core.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackControllerTest {
    @Test fun leaseUsesAckSendTimeNotGrantReceiptTime() = runTest {
        Harness(this).use { h ->
            h.snapshot()
            h.prepare()
            advanceTimeBy(3_000)
            runCurrent()
            h.activate()
            assertTrue(h.audio.actual.isPlaying)
            advanceTimeBy(4_999)
            runCurrent()
            assertTrue(h.audio.actual.isPlaying)
            advanceTimeBy(1)
            runCurrent()
            assertFalse(h.audio.actual.isPlaying)
            assertTrue(h.controller.state.value.error!!.contains("expired"))
        }
    }

    @Test fun missingOrExpiredLeaseBasisNeverStartsAudio() = runTest {
        Harness(this).use { h ->
            h.snapshot()
            h.prepare()
            advanceTimeBy(8_001)
            runCurrent()
            h.activate()
            assertEquals(0, h.audio.executions.size)
            assertEquals("rejected", h.frames("ack").last()["status"]!!.jsonPrimitive.content)
        }
        Harness(this).use { h ->
            h.snapshot()
            h.prepare()
            h.activate(basis = 200)
            assertEquals(0, h.audio.executions.size)
        }
    }

    @Test fun releaseCancelsPendingStartAndLateCompletionCannotAckOrPlay() = runTest {
        Harness(this).use { h ->
            h.snapshot()
            h.prepare()
            val gate = CompletableDeferred<AudioResult>()
            h.audio.gate = gate
            h.activate()
            val pending = h.audio.executions.single()
            assertTrue(pending.authorized())
            h.instruction(PlaybackInstruction("release", "release", 1, "event", 0.0, false))
            assertFalse(pending.authorized())
            val count = h.frames("ack").size
            gate.complete(AudioResult.OK)
            runCurrent()
            assertFalse(h.audio.actual.isPlaying)
            assertEquals(count, h.frames("ack").size)
        }
    }

    @Test fun disconnectFencesAnUncancelablePrepareCompletion() = runTest {
        Harness(this).use { h ->
            h.snapshot()
            val gate = CompletableDeferred<AudioResult>()
            h.audio.prepareGate = gate
            h.prepare()
            h.controller.disconnect()
            gate.complete(AudioResult.OK)
            runCurrent()
            assertTrue(h.frames("ack").isEmpty())
            assertFalse(h.audio.actual.isPlaying)
            assertEquals(PlaybackConnectionState.DISCONNECTED, h.controller.state.value.connection)
        }
    }

    @Test fun stoppedExecutionParksAndOnlyChangedActualFactsAreReported() = runTest {
        Harness(this).use { h ->
            h.snapshot(desiredPlaying = false)
            h.prepare(desiredPlaying = false)
            h.activate(desiredPlaying = false)
            val initialReports = h.frames("report").size
            assertEquals(1, initialReports)
            advanceTimeBy(20_000)
            runCurrent()
            assertEquals(initialReports, h.frames("report").size)
            assertNull(h.controller.state.value.error)
            // A delayed pre-proof grant cannot revive a parked selection.
            h.lease("event", basis = 1)
            assertFalse(h.audio.executions.single().authorized())
            h.audio.actual = h.audio.actual.copy(duration = 12.0, elapsed = 3.0)
            advanceTimeBy(100)
            runCurrent()
            assertEquals(initialReports + 1, h.frames("report").size)
            h.observe(h.audio.actual)
            assertEquals(initialReports + 1, h.frames("report").size)
            assertFalse(h.frames("report").last()["isPlaying"]!!.jsonPrimitive.boolean)
        }
    }

    @Test fun activeSamplerIsTwoSecondsAndNativeProgressDoesNotDuplicateIt() = runTest {
        Harness(this).use { h ->
            h.start()
            val initial = h.frames("report").size
            h.observe(h.audio.actual.copy(elapsed = 1.0))
            assertEquals(initial, h.frames("report").size)
            advanceTimeBy(1_999)
            runCurrent()
            assertEquals(initial, h.frames("report").size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(initial + 1, h.frames("report").size)
            // A meaningful buffering event restarts the two-second report cadence.
            h.observe(h.audio.actual.copy(isBuffering = true))
            val afterEvent = h.frames("report").size
            advanceTimeBy(1_900)
            runCurrent()
            assertEquals(afterEvent, h.frames("report").size)
        }
    }

    @Test fun completedSeekReportsImmediatelyButAnOldExecutionEndIsFenced() = runTest {
        Harness(this).use { h ->
            h.start()
            h.stage("plan")
            val old = h.audio.actual
            val basis = h.frames("report").last()["clientSequence"]!!.jsonPrimitive.long
            h.instruction(PlaybackInstruction("seek-execution", "execute", 1, "event", 5.0, true,
                track = track(), lease = LeaseTiming(basis, 8_000)))
            val reports = h.frames("report").size
            h.observe(h.audio.actual.copy(elapsed = 5.5), seekCompleted = true)
            assertEquals(reports + 1, h.frames("report").size)
            h.observe(old.copy(ended = true, isPlaying = false))
            assertEquals(2, h.audio.executions.size)
            assertTrue(h.frames("report").none { it["ended"]!!.jsonPrimitive.boolean })
        }
    }

    @Test fun parkedExecutionCannotBeResumedByExecuteWithoutFreshPreparation() = runTest {
        Harness(this).use { h ->
            h.snapshot(desiredPlaying = false)
            h.prepare(desiredPlaying = false)
            h.activate(desiredPlaying = false)
            val basis = h.frames("report").last()["clientSequence"]!!.jsonPrimitive.long
            h.instruction(PlaybackInstruction("bad-resume", "execute", 1, "event", 0.0, true,
                track = track(), lease = LeaseTiming(basis, 8_000)))
            assertEquals(1, h.audio.executions.size)
            assertEquals("rejected", h.frames("ack").last()["status"]!!.jsonPrimitive.content)
            assertFalse(h.audio.actual.isPlaying)
        }
    }

    @Test fun mismatchedFactsAndOldEndedCallbacksCannotAdvance() = runTest {
        Harness(this).use { h ->
            h.start()
            h.stage("plan")
            val reports = h.frames("report").size
            val wrong = h.audio.actual.copy(binding = h.audio.actual.binding!!.copy(instructionId = "old"), ended = true)
            h.observe(wrong)
            h.audio.actual = wrong
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(reports, h.frames("report").size)
            assertEquals(1, h.audio.executions.size)
        }
    }

    @Test fun sampledEndedDoesNotConsumePlanButActualEndedDoesExactlyOnce() = runTest {
        Harness(this).use { h ->
            h.start()
            h.stage("plan")
            val source = h.audio.actual.copy(ended = true, isPlaying = false, elapsed = 10.0)
            h.audio.actual = source
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(1, h.audio.executions.size)
            h.observe(source)
            assertEquals(2, h.audio.executions.size)
            val ended = h.frames("report").filter { it["ended"]!!.jsonPrimitive.boolean }
            assertEquals(1, ended.size)
            assertEquals("plan", ended.single()["planId"]!!.jsonPrimitive.content)
            h.observe(source)
            assertEquals(2, h.audio.executions.size)
            val endIndex = h.socket.sent.indexOfFirst { it["type"]!!.jsonPrimitive.content == "report" && it["ended"]!!.jsonPrimitive.boolean }
            val nextIndex = h.socket.sent.indexOfFirst { it["eventId"]?.jsonPrimitive?.content == "next-event" }
            assertTrue(endIndex < nextIndex)
        }
    }

    @Test fun nextConfirmationDeduplicatesWhileOriginalOperationIsPending() = runTest {
        Harness(this).use { h ->
            h.start()
            h.stage("plan")
            val gate = CompletableDeferred<AudioResult>()
            h.audio.gate = gate
            h.observe(h.audio.actual.copy(ended = true, isPlaying = false))
            assertEquals(2, h.audio.executions.size)
            val sourceEnd = h.frames("report").single { it["ended"]!!.jsonPrimitive.boolean }
            val basis = sourceEnd["clientSequence"]!!.jsonPrimitive.long
            h.confirmNext("plan", basis)
            assertEquals(2, h.audio.executions.size)
            assertTrue(h.frames("ack").none { it["instructionId"]!!.jsonPrimitive.content == "next-execution" })
            gate.complete(AudioResult.MEDIA)
            runCurrent()
            val nextAcks = h.frames("ack").filter { it["instructionId"]!!.jsonPrimitive.content == "next-execution" }
            assertTrue(nextAcks.isNotEmpty())
            assertTrue(nextAcks.all { it["status"]!!.jsonPrimitive.content == "media" })
            h.confirmNext("plan", basis)
            assertEquals(2, h.audio.executions.size)
            assertFalse(h.audio.actual.isPlaying)
        }
    }

    @Test fun lateSourceLeaseCannotExtendUnconfirmedNextDeadline() = runTest {
        Harness(this).use { h ->
            h.start()
            h.stage("plan")
            advanceTimeBy(6_000)
            runCurrent()
            h.observe(h.audio.actual.copy(ended = true, isPlaying = false))
            assertEquals(2, h.audio.executions.size)
            val recentSourceBasis = h.frames("report").last { it["eventId"]!!.jsonPrimitive.content == "event" }["clientSequence"]!!.jsonPrimitive.long
            h.lease("event", recentSourceBasis)
            assertTrue(h.audio.actual.isPlaying)
            advanceTimeBy(2_000)
            runCurrent()
            assertFalse(h.audio.actual.isPlaying)
        }
    }

    @Test fun matchingCancelRevokesAdmittedNextButDelayedCancelDoesNotEraseNewPlan() = runTest {
        Harness(this).use { h ->
            h.start()
            h.stage("old-plan")
            h.stage("new-plan")
            h.cancel("old-plan")
            val gate = CompletableDeferred<AudioResult>()
            h.audio.gate = gate
            h.observe(h.audio.actual.copy(ended = true, isPlaying = false))
            assertEquals("next-event", h.audio.executions.last().binding.eventId)
            h.cancel("new-plan")
            assertFalse(h.audio.executions.last().authorized())
            gate.complete(AudioResult.OK)
            runCurrent()
            assertFalse(h.audio.actual.isPlaying)
        }
    }

    @Test fun localCommandDiscardsFuturePlanButDoesNotRevokeAdmittedNext() = runTest {
        Harness(this).use { h ->
            h.start()
            h.stage("plan")
            h.observe(h.audio.actual.copy(ended = true, isPlaying = false))
            val admitted = h.audio.executions.last()
            h.controller.setShuffle(true)
            assertTrue(admitted.authorized())
            assertTrue(h.audio.actual.isPlaying)
        }
        Harness(this).use { h ->
            h.start()
            h.stage("plan")
            h.controller.pause()
            h.observe(h.audio.actual.copy(ended = true, isPlaying = false))
            assertEquals(1, h.audio.executions.size)
            assertNull(h.frames("report").last()["planId"])
        }
    }

    @Test fun playbackAnchorsWaitForExactStructuralVersionAndEvent() = runTest {
        Harness(this).use { h ->
            h.snapshot()
            val future = status(output = 0, version = 2, sequence = 10, event = "future", playing = true)
            h.message(PlaybackServerMessage("playback", "session", playback = future))
            assertFalse(h.controller.state.value.isPlaying)
            h.snapshot(version = 2, event = "future")
            assertTrue(h.controller.state.value.isPlaying)
            h.message(PlaybackServerMessage("playback", "session", playback = future.copy(playbackSequence = 11, eventId = "wrong")))
            assertEquals("future", h.controller.state.value.playback!!.eventId)
            h.message(PlaybackServerMessage("playback", "session", playback = future.copy(playbackSequence = 9, isPlaying = false)))
            assertTrue(h.controller.state.value.isPlaying)
        }
    }

    @Test fun commandIdsAreDecimalAndReconnectNeverReplays() = runTest {
        Harness(this).use { h ->
            h.start()
            assertEquals("1", h.controller.playTrack(PlaybackTrackReference("track")))
            assertEquals("2", h.controller.pause())
            assertEquals(2, h.frames("command").size)
            h.socket.finish()
            runCurrent()
            assertFalse(h.audio.actual.isPlaying)
            assertNull(h.controller.resume())
            val nextSocket = FakeConnection()
            h.transport.socket = nextSocket
            h.controller.connect("Phone", "device")
            runCurrent()
            nextSocket.incoming.send(playbackJson.encodeToString(PlaybackServerMessage("snapshot", "session-2",
                connectionId = "connection-2", deviceId = "device", snapshot = snapshotValue().copy(sessionId = "session-2", selectedConnectionId = "connection-2"))))
            runCurrent()
            assertTrue(nextSocket.sent.isEmpty())
            assertEquals("1", h.controller.resume())
            assertEquals(1, nextSocket.sent.size)
        }
    }

    @Test fun intentAndAcceptedReceiptNeverBecomeActualSound() = runTest {
        Harness(this).use { h ->
            h.snapshot(desiredPlaying = true)
            assertTrue(h.controller.state.value.desiredPlaying)
            assertFalse(h.controller.state.value.isPlaying)
            val id = h.controller.resume()!!
            h.result(id)
            assertFalse(h.controller.state.value.isPlaying)
        }
    }

    @Test fun undoIsLatestOnlyCappedByOriginalActionTimeAndServerDeadline() = runTest {
        Harness(this).use { h ->
            h.snapshot()
            val id = h.controller.remove(2)!!
            advanceTimeBy(3_500)
            runCurrent()
            h.result(id, undo = PlaybackUndoCapability("secret", h.wall + 4_000))
            assertEquals(500L, h.controller.state.value.undo!!.remainingMs)
            assertNull(h.controller.state.value.latestResult!!.undo)
            advanceTimeBy(500)
            runCurrent()
            assertNull(h.controller.state.value.undo)
            assertNull(h.controller.undoRemove())
            val first = h.controller.remove(2)!!
            val latest = h.controller.remove(3)!!
            h.result(first, undo = PlaybackUndoCapability("old", h.wall + 4_000))
            assertNull(h.controller.state.value.undo)
            h.result(latest, undo = PlaybackUndoCapability("latest", h.wall + 200))
            assertEquals(200L, h.controller.state.value.undo!!.remainingMs)
            assertNotNull(h.controller.undoRemove())
            assertNull(h.controller.undoRemove())
            assertEquals("latest", h.frames("command").last()["command"]!!.jsonObject["token"]!!.jsonPrimitive.content)
        }
    }

    @Test fun sameSocketReleasePreservesPreparedMediaAndUsesFreshReleaseBasis() = runTest {
        Harness(this).use { h ->
            h.snapshot(desiredPlaying = false)
            h.prepare(desiredPlaying = false)
            h.instruction(PlaybackInstruction("release", "release", 0, "event", 0.0, false))
            val releaseBasis = h.frames("ack").last()["clientSequence"]!!.jsonPrimitive.long
            h.activate(basis = releaseBasis, desiredPlaying = false)
            assertEquals(1, h.audio.executions.size)
            assertEquals("ok", h.frames("ack").last()["status"]!!.jsonPrimitive.content)
        }
    }

    @Test fun collectionShuffleIsOneAtomicCommandAndExactRowReferencesArePreserved() = runTest {
        Harness(this).use { h ->
            h.snapshot()
            val context = PlaybackContext("derived", "derived-id", "List", 42, "fence")
            h.controller.playCollection(context, shuffle = true)
            val command = h.frames("command").single()["command"]!!.jsonObject
            assertEquals("queueIntent", command["type"]!!.jsonPrimitive.content)
            assertTrue(command["shuffle"]!!.jsonPrimitive.boolean)
            val row = PlaybackTrackReference("track", targetId = "derived-id", occurrenceId = "path", selectionToken = "signed")
            h.controller.playTrack(row, context)
            val selection = h.frames("command").last()["command"]!!.jsonObject["selection"]!!.jsonObject
            assertEquals("path", selection["track"]!!.jsonObject["occurrenceId"]!!.jsonPrimitive.content)
            assertEquals("signed", selection["track"]!!.jsonObject["selectionToken"]!!.jsonPrimitive.content)
            assertEquals(42L, selection["context"]!!.jsonObject["revision"]!!.jsonPrimitive.long)
        }
    }

    private class Harness(private val scope: TestScope) : AutoCloseable {
        val socket = FakeConnection()
        val transport = FakeTransport(socket)
        val audio = FakeAudio()
        val wall get() = 1_700_000_000_000L + scope.testScheduler.currentTime
        val controller = PlaybackController(transport, audio, scope.backgroundScope, object : PlaybackClock {
            override fun monotonicMs() = scope.testScheduler.currentTime
            override fun wallTimeMs() = wall
        })
        private var controlId = 0
        init {
            controller.connect("Phone", "device")
            scope.runCurrent()
        }
        suspend fun start() {
            snapshot()
            prepare()
            activate()
        }
        suspend fun snapshot(version: Long = 1, desiredPlaying: Boolean = true, event: String = "event") {
            val first = controller.state.value.snapshot == null
            message(PlaybackServerMessage(if (first) "snapshot" else "state", "session",
                connectionId = if (first) "connection" else null, deviceId = if (first) "device" else null,
                snapshot = snapshotValue(version, desiredPlaying, event)))
        }
        suspend fun prepare(desiredPlaying: Boolean = true) = instruction(PlaybackInstruction(
            "prepare", "prepare", 0, "event", 0.0, desiredPlaying, track = track()))
        suspend fun activate(basis: Long = 1, desiredPlaying: Boolean = true) = instruction(PlaybackInstruction(
            "execution", "activate", 1, "event", 0.0, desiredPlaying, track = track(), lease = LeaseTiming(basis, 8_000)))
        suspend fun stage(planId: String) = instruction(PlaybackInstruction(
            "stage-${++controlId}", "stageNext", 1, "event", 0.0, false, planId = planId,
            currentInstructionId = "execution", next = NextExecution("next-event", "next-execution", track("next", 2))))
        suspend fun cancel(planId: String) = instruction(PlaybackInstruction(
            "cancel-${++controlId}", "cancelNext", 1, "event", 0.0, false, planId = planId,
            currentInstructionId = "execution"))
        suspend fun confirmNext(planId: String, basis: Long) = instruction(PlaybackInstruction(
            "next-execution", "execute", 1, "next-event", 0.0, true, track = track("next", 2),
            lease = LeaseTiming(basis, 8_000), planId = planId))
        suspend fun instruction(value: PlaybackInstruction) = message(PlaybackServerMessage("instruction", "session", instruction = value))
        suspend fun lease(event: String, basis: Long) = message(PlaybackServerMessage("lease", "session", lease = PlaybackLease(1, event, basis, 8_000)))
        suspend fun result(id: String, undo: PlaybackUndoCapability? = null) = message(PlaybackServerMessage("result", "session",
            result = PlaybackCommandResult(id, "accepted", "", 1, 1, "none", "confirmed", true, undo)))
        suspend fun message(value: PlaybackServerMessage) {
            socket.incoming.send(playbackJson.encodeToString(value))
            scope.runCurrent()
        }
        suspend fun observe(facts: AudioFacts, seekCompleted: Boolean = false) {
            audio.actual = facts
            audio.events.send(AudioObservation(facts.binding!!, facts, seekCompleted))
            scope.runCurrent()
        }
        fun frames(type: String) = socket.sent.filter { it["type"]!!.jsonPrimitive.content == type }
        override fun close() = controller.close()
    }

    private class FakeTransport(var socket: FakeConnection) : PlaybackTransport {
        override suspend fun connect(name: String, deviceId: String): PlaybackConnection = socket
    }

    private class FakeConnection : PlaybackConnection {
        val incoming = Channel<String>(Channel.UNLIMITED)
        val sent = mutableListOf<JsonObject>()
        override val messages = incoming.receiveAsFlow()
        override var isOpen = true
        override fun send(text: String): Boolean {
            if (!isOpen) return false
            sent.add(playbackJson.parseToJsonElement(text).jsonObject)
            return true
        }
        fun finish() {
            isOpen = false
            incoming.close()
        }
        override fun close() = finish()
    }

    private class FakeAudio : AudioExecutor {
        data class Attempt(val binding: AudioBinding, val authorized: () -> Boolean)
        val events = Channel<AudioObservation>(Channel.UNLIMITED)
        override val observations = events.receiveAsFlow()
        val executions = mutableListOf<Attempt>()
        var actual = AudioFacts()
        var gate: CompletableDeferred<AudioResult>? = null
        var prepareGate: CompletableDeferred<AudioResult>? = null
        override fun facts() = actual
        override fun pause() { actual = actual.copy(isPlaying = false, isLoading = false) }
        override suspend fun prepare(binding: AudioBinding, track: PlaybackTrack?, elapsed: Double): AudioResult {
            actual = AudioFacts(binding = binding, elapsed = elapsed, duration = track?.duration ?: 0.0)
            val wait = prepareGate
            return if (wait == null) AudioResult.OK else withContext(NonCancellable) { wait.await() }
        }
        override suspend fun execute(binding: AudioBinding, track: PlaybackTrack?, elapsed: Double, desiredPlaying: Boolean,
            isAuthorized: () -> Boolean): AudioResult {
            executions.add(Attempt(binding, isAuthorized))
            actual = AudioFacts(binding = binding, elapsed = elapsed, duration = track?.duration ?: 0.0)
            // Simulate a native prepare which completes even after coroutine cancellation.
            val wait = gate
            val outcome = if (wait == null) AudioResult.OK else withContext(NonCancellable) { wait.await() }
            if (outcome == AudioResult.OK && isAuthorized() && actual.binding == binding) {
                actual = actual.copy(isPlaying = desiredPlaying)
            }
            return outcome
        }
    }

    companion object {
        private fun track(id: String = "track", key: Long = 1) = PlaybackTrack(id, "Song", emptyList(), "Album", "album", 10.0, key, key, "now")
        private fun status(output: Long = 0, version: Long = 1, sequence: Long = 1, event: String = "event", playing: Boolean = false) =
            PlaybackStatus(output, version, sequence, event, playing, 0.0, 10.0, playing, false, false, "")
        private fun snapshotValue(version: Long = 1, desiredPlaying: Boolean = true, event: String = "event") = PlaybackSnapshot(
            "session", version, 0, PlaybackQueueState(queue = listOf(track()), position = 0, desiredPlaying = desiredPlaying,
                occurrence = PlaybackOccurrence(1, "track", event, 0.0, false)), status(version = version, event = event),
            listOf(PlaybackDevice("device", "Phone", true, true)), "device", "connection", null, 0)
    }
}
