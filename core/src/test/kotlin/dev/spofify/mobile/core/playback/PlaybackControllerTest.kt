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
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackControllerTest {
    @Test fun rejectionKeepsItsCodeAndHandoffContextAcrossSnapshotsWithoutReplaying() = runTest {
        Harness(this).use { h ->
            h.snapshot()
            val snapshot = h.controller.state.value.snapshot!!
            h.message(PlaybackServerMessage("state", "session", snapshot = snapshot.copy(
                stateVersion = 2, playback = snapshot.playback.copy(stateVersion = 2),
                transfer = PlaybackTransfer("device", "other", "connection", "other-connection", "releasing"))))
            val id = requireNotNull(h.controller.queueTrack(PlaybackTrackReference("track")))
            h.message(PlaybackServerMessage("result", "session", result =
                PlaybackCommandResult(id, "rejected", "capacity", 2, 0, "none", "unchanged", true)))
            val expected = PlaybackCommandFailure("queueIntent", "rejected", "capacity", "unchanged", "releasing")
            assertEquals(expected, h.controller.state.value.commandFailure)
            h.snapshot(version = 3)
            assertEquals(expected, h.controller.state.value.commandFailure)
            assertEquals(1, h.frames("command").size)
            assertNotNull(h.controller.queueTrack(PlaybackTrackReference("another")))
            assertNull(h.controller.state.value.commandFailure)
        }
    }

    @Test fun unknownResultCodeCannotPublishRawServerTextInDiagnostics() = runTest {
        Harness(this).use { h ->
            h.snapshot()
            val id = requireNotNull(h.controller.pause())
            val raw = "private-response-text-not-a-code"
            h.message(PlaybackServerMessage("result", "session", result =
                PlaybackCommandResult(id, "rejected", raw, 1, 0, "none", "unchanged", true)))
            assertEquals("unknown", h.controller.state.value.commandFailure?.code)
            assertEquals("unknown", h.controller.state.value.latestResult?.code)
            assertFalse(h.controller.state.value.error.orEmpty().contains(raw))
        }
    }

    @Test fun deviceNamePolicyAcceptsUnicodeAdmissionAndPreservesRenameTrimming() = runTest {
        val note = "\uD83C\uDFB5"
        val name = note.repeat(64)
        Harness(this, name).use { h ->
            h.start()
            assertEquals(listOf(name to "device"), h.transport.admissions)
            val renamed = note.repeat(60)
            assertNotNull(h.controller.renameDevice("  $renamed  "))
            assertEquals(renamed, h.frames("command").single()["command"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            assertTrue(h.socket.isOpen)
            assertTrue(h.audio.actual.isPlaying)
            assertEquals(1, h.audio.executions.size)
        }
    }

    @Test fun invalidDeviceNamesFailBeforeDisconnectOrCommandSubmission() = runTest {
        Harness(this).use { h ->
            h.start()
            h.stage("retained-plan")
            val before = h.controller.state.value
            val sent = h.socket.sent.size
            val invalid = listOf("", "   ", "a".repeat(65), "Phone\n", "\uD800", "Phone\uDC00", "\uFEFF")
            invalid.forEach { name ->
                assertTrue(runCatching { h.controller.connect(name, "device") }.exceptionOrNull() is IllegalArgumentException)
                assertTrue(runCatching { h.controller.renameDevice(name) }.exceptionOrNull() is IllegalArgumentException)
                assertEquals(before, h.controller.state.value)
                assertEquals(sent, h.socket.sent.size)
                assertTrue(h.socket.isOpen)
                assertTrue(h.audio.actual.isPlaying)
                assertTrue(h.audio.executions.single().authorized())
            }
            assertEquals(1, h.transport.admissions.size)
            h.observe(h.audio.actual.copy(ended = true, isPlaying = false, elapsed = 10.0))
            assertEquals("retained-plan", h.frames("report").single { it["ended"]!!.jsonPrimitive.boolean }["planId"]!!.jsonPrimitive.content)
            assertEquals(2, h.audio.executions.size)
        }
    }

    @Test fun queueUsesIndividualHighAndCollectionLowWithoutStartingAudio() = runTest {
        Harness(this).use { h ->
            h.snapshot()
            assertNotNull(h.controller.queueTrack(PlaybackTrackReference("track")))
            assertNotNull(h.controller.queueCollection(PlaybackContext("album", "album", "Album")))
            assertNotNull(h.controller.queueTrack(PlaybackTrackReference("track"), next = true))
            val intents = h.frames("command").map {
                it["command"]!!.jsonObject["intent"]!!.jsonPrimitive.content
            }
            assertEquals(listOf("high", "low", "next"), intents)
            assertFalse(h.audio.actual.isPlaying)
            assertTrue(h.audio.executions.isEmpty())
        }
    }

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

    @Test fun pauseExecuteConfirmsPhysicalStopInsteadOfRewindingToReportBasis() = runTest {
        for (remote in listOf(false, true)) Harness(this).use { h ->
            h.start()
            h.observe(h.audio.actual.copy(elapsed = 30.0, duration = 180.0), seekCompleted = true)
            h.audio.actual = h.audio.actual.copy(elapsed = 30.4) // not reported yet
            val id = if (remote) "remote-id" else requireNotNull(h.controller.toggle())
            assertEquals(remote, h.audio.actual.isPlaying) // no round trip for a local explicit stop
            val sample = h.frames("report").last()
            if (!remote) {
                assertEquals(listOf("report", "command"), h.socket.sent.takeLast(2).map { it["type"]!!.jsonPrimitive.content })
                assertEquals("execution", sample["instructionId"]!!.jsonPrimitive.content)
                assertEquals("event", sample["eventId"]!!.jsonPrimitive.content)
                assertEquals(1L, sample["outputVersion"]!!.jsonPrimitive.long)
                assertFalse(sample["ended"]!!.jsonPrimitive.boolean)
                assertFalse(sample["isPlaying"]!!.jsonPrimitive.boolean)
                assertEquals(30.4, sample["elapsed"]!!.jsonPrimitive.double, 0.0)
            }
            h.instruction(PlaybackInstruction("stop", "execute", 1, "event", sample["elapsed"]!!.jsonPrimitive.double, false,
                commandId = id, track = track(), lease = LeaseTiming(sample["clientSequence"]!!.jsonPrimitive.long, 8_000)))
            val frames = h.socket.sent.filter { it["instructionId"]?.jsonPrimitive?.content == "stop" }
            assertEquals(listOf("ack", "report"), frames.map { it["type"]!!.jsonPrimitive.content })
            assertEquals("ok", frames.first()["status"]!!.jsonPrimitive.content)
            assertEquals("30.4", frames.last()["elapsed"]!!.jsonPrimitive.content)
            assertFalse(frames.last()["isPlaying"]!!.jsonPrimitive.boolean)
        }
    }

    @Test fun crossedStartAndUnsuccessfulPauseResultNeverReplayAudio() = runTest {
        for (rejected in listOf(true, false)) Harness(this).use { h ->
            h.start()
            val basis = h.frames("report").last()["clientSequence"]!!.jsonPrimitive.long
            val id = requireNotNull(h.controller.pause())
            // Already sent by server when the local user stopped its old execution.
            h.instruction(PlaybackInstruction("crossed", "execute", 1, "next-event", 0.0, true,
                track = track("next", 2), lease = LeaseTiming(basis, 8_000)))
            h.message(PlaybackServerMessage("result", "session", result = PlaybackCommandResult(id,
                if (rejected) "rejected" else "accepted", if (rejected) "capacity" else "", 2, 0, "none",
                if (rejected) "unchanged" else "uncertain", true)))
            assertNotNull(h.controller.state.value.commandFailure)
            assertEquals(1, h.audio.executions.size)
            assertFalse(h.audio.actual.isPlaying)
            assertTrue(h.frames("ack").none { it["instructionId"]!!.jsonPrimitive.content == "crossed" })
            assertEquals(1, h.frames("command").size)
            // A late instruction on the old basis is still ambiguous, even after the result.
            h.instruction(PlaybackInstruction("late", "execute", 1, "next-event", 0.0, true,
                track = track("next", 2), lease = LeaseTiming(basis, 8_000)))
            assertEquals(1, h.audio.executions.size)
            assertFalse(h.audio.actual.isPlaying)
            // A distinct grant based on the stop observation is causally newer.
            val stopBasis = h.frames("report").last()["clientSequence"]!!.jsonPrimitive.long
            h.instruction(PlaybackInstruction("later", "execute", 1, "next-event", 0.0, true,
                track = track("next", 2), lease = LeaseTiming(stopBasis, 8_000)))
            assertTrue(h.audio.actual.isPlaying)
        }
    }

    @Test fun pendingTimeoutIsNotAnOrderingBoundaryForCrossedPlay() = runTest {
        Harness(this).use { h ->
            h.start()
            val id = requireNotNull(h.controller.pause())
            // Keep genuine stopped reports/leases flowing while the Pause result is missing.
            repeat(4) {
                advanceTimeBy(4_000)
                runCurrent()
                h.lease("event", h.frames("report").last()["clientSequence"]!!.jsonPrimitive.long)
            }
            assertEquals(0, h.controller.state.value.pendingCommands)
            val basis = h.frames("report").last()["clientSequence"]!!.jsonPrimitive.long
            val start = PlaybackInstruction("crossed", "execute", 1, "event", 0.0, true,
                track = track(), lease = LeaseTiming(basis, 8_000))
            h.instruction(start)
            assertFalse(h.audio.actual.isPlaying)
            assertEquals(1, h.audio.executions.size)
            h.result(id) // late receipt isn't a replay or UI success
            assertNull(h.controller.state.value.latestResult)
            assertFalse(h.audio.actual.isPlaying)
            h.instruction(start.copy(id = "new-grant"))
            assertTrue(h.audio.actual.isPlaying)
        }
    }

    @Test fun fullCommandBookCannotPreventStopOrLetAnOlderExecutionStart() = runTest {
        Harness(this).use { h ->
            h.start()
            h.stage("discard-me")
            repeat(32) { assertNotNull(h.controller.renameDevice("Phone")) }
            val basis = h.frames("report").last()["clientSequence"]!!.jsonPrimitive.long
            val old = h.audio.actual
            val sent = h.socket.sent.size
            assertNull(h.controller.toggle())
            assertFalse(h.audio.actual.isPlaying)
            assertFalse(h.audio.executions.single().authorized())
            val stop = h.socket.sent.drop(sent).single()
            assertEquals("report", stop["type"]!!.jsonPrimitive.content)
            assertFalse(stop["isPlaying"]!!.jsonPrimitive.boolean)
            h.audio.events.send(AudioObservation(old.binding!!, old)) // delayed before-stop callback
            runCurrent()
            assertEquals(stop, h.socket.sent.last())
            h.result("1") // room again; never replay the unsent Pause
            h.instruction(PlaybackInstruction("older", "execute", 1, "event", 1.0, true,
                track = track(), lease = LeaseTiming(basis, 8_000)))
            assertTrue(h.socket.isOpen)
            assertEquals(1, h.audio.executions.size)
            assertFalse(h.audio.actual.isPlaying)
            assertEquals(32, h.frames("command").size)
            assertTrue(h.frames("ack").none { it["instructionId"]!!.jsonPrimitive.content == "older" })
        }
    }

    @Test fun closedSocketBeforeDisconnectCallbackCannotDelayLocalStop() = runTest {
        Harness(this).use { h ->
            h.start()
            val sent = h.socket.sent.size
            h.socket.finish() // collector and sampler have not run yet
            assertEquals(PlaybackConnectionState.READY, h.controller.state.value.connection)
            assertTrue(h.audio.actual.isPlaying)
            assertNull(h.controller.pause())
            assertFalse(h.audio.actual.isPlaying)
            assertEquals(sent, h.socket.sent.size)
            runCurrent()
            assertEquals(PlaybackConnectionState.DISCONNECTED, h.controller.state.value.connection)
        }
    }

    @Test fun sendFailureDoesNotUndoLocalStopOrReplayAnythingOnFreshConnection() = runTest {
        for (failure in listOf("report", "command")) Harness(this).use { h ->
            h.start()
            val oldAttempt = h.audio.executions.single()
            h.socket.failSend = failure
            val sent = h.socket.sent.size
            assertNull(h.controller.pause())
            assertFalse(h.audio.actual.isPlaying)
            assertFalse(oldAttempt.authorized())
            assertEquals(if (failure == "report") emptyList() else listOf("report"),
                h.socket.sent.drop(sent).map { it["type"]!!.jsonPrimitive.content })
            assertEquals(PlaybackConnectionState.DISCONNECTED, h.controller.state.value.connection)
            h.transport.socket = FakeConnection()
            h.controller.connect("Phone", "device")
            runCurrent()
            h.snapshot()
            assertTrue(h.socket.sent.isEmpty())
            assertFalse(h.audio.actual.isPlaying)
            h.prepare()
            h.activate() // same numeric epoch/sequence and event on fresh socket
            assertTrue(h.audio.actual.isPlaying)
            assertFalse(oldAttempt.authorized())
            assertTrue(h.frames("command").isEmpty())
        }
    }

    @Test fun pauseIssuedWhileAnotherConnectionIsSelectedDoesNotSilenceTransferTarget() = runTest {
        Harness(this).use { h ->
            h.start()
            h.message(PlaybackServerMessage("state", "session", snapshot = snapshotValue(2).copy(outputVersion = 1,
                selectedConnectionId = "other", transfer = PlaybackTransfer("other-device", "device", "other", "connection", "activating"))))
            val reports = h.frames("report").size
            assertNotNull(h.controller.pause())
            assertTrue(h.audio.actual.isPlaying)
            assertTrue(h.audio.executions.single().authorized())
            assertEquals(reports, h.frames("report").size)
        }
    }

    @Test fun localPauseNeverRelabelsAStaleStopSample() = runTest {
        Harness(this).use { h ->
            h.start()
            val actual = h.audio.actual
            h.audio.actual = actual.copy(binding = actual.binding!!.copy(instructionId = "retired"), elapsed = 30.4)
            val frames = h.socket.sent.size
            assertNotNull(h.controller.pause())
            assertFalse(h.audio.actual.isPlaying)
            assertEquals(listOf("command"), h.socket.sent.drop(frames).map { it["type"]!!.jsonPrimitive.content })
        }
    }

    @Test fun localPauseFencesPendingStartBeforeAnyServerResponse() = runTest {
        Harness(this).use { h ->
            h.start()
            val basis = h.frames("report").last()["clientSequence"]!!.jsonPrimitive.long
            val gate = CompletableDeferred<AudioResult>()
            h.audio.gate = gate
            h.instruction(PlaybackInstruction("loading", "execute", 1, "event", 4.0, true,
                track = track(), lease = LeaseTiming(basis, 8_000)))
            val attempt = h.audio.executions.last()
            assertTrue(attempt.authorized())
            val receipts = h.frames("ack").size
            val reports = h.frames("report").size
            assertNotNull(h.controller.pause())
            assertEquals(reports, h.frames("report").size) // no position invented from pending setup
            assertFalse(attempt.authorized())
            gate.complete(AudioResult.OK)
            runCurrent()
            assertFalse(h.audio.actual.isPlaying)
            assertEquals(receipts, h.frames("ack").size)
            advanceTimeBy(2_001)
            runCurrent()
            assertEquals(reports, h.frames("report").size) // sampler cannot turn cancelled setup into history
            assertNull(h.controller.state.value.latestResult)
        }
    }

    @Test fun stoppedSpeculativeStartCannotConfirmOrEmitHistoryAfterPause() = runTest {
        Harness(this).use { h ->
            h.start()
            h.stage("plan")
            val gate = CompletableDeferred<AudioResult>()
            h.audio.gate = gate
            h.observe(h.audio.actual.copy(ended = true, isPlaying = false))
            val basis = h.frames("report").last()["clientSequence"]!!.jsonPrimitive.long
            val attempt = h.audio.executions.last()
            assertEquals("next-event", attempt.binding.eventId)
            assertNotNull(h.controller.pause())
            h.confirmNext("plan", basis)
            gate.complete(AudioResult.OK)
            runCurrent()
            advanceTimeBy(2_001)
            runCurrent()
            assertFalse(attempt.authorized())
            assertFalse(h.audio.actual.isPlaying)
            assertTrue(h.socket.sent.none { it["instructionId"]?.jsonPrimitive?.content == "next-execution" })
        }
    }

    @Test fun stoppedAbsolutePositionAndDifferentOccurrenceDoNotInheritNativeTail() = runTest {
        for ((event, target) in listOf("event" to 31.0, "new-event" to 30.0)) Harness(this).use { h ->
            h.start()
            h.observe(h.audio.actual.copy(elapsed = 30.0), seekCompleted = true)
            val basis = h.frames("report").last()["clientSequence"]!!.jsonPrimitive.long
            h.audio.actual = h.audio.actual.copy(elapsed = 30.4)
            h.instruction(PlaybackInstruction("absolute", "execute", 1, event, target, false,
                track = track(), lease = LeaseTiming(basis, 8_000)))
            assertEquals(target, h.audio.actual.elapsed, 0.0)
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

    @Test fun retiredPlanCapacityDisablesOnlySpeculationAndOrdinaryEndFallbackRecovers() = runTest {
        Harness(this).use { h ->
            h.start()
            val source = h.audio.executions.single()
            val initialAcks = h.frames("ack").size
            repeat(160) { index ->
                val planId = "retired-$index"
                h.stage(planId, reservedNext(planId), instructionId = if (index == 0) "first-stage" else null)
                h.cancel(planId)
            }
            assertEquals(PlaybackConnectionState.READY, h.controller.state.value.connection)
            assertNull(h.controller.state.value.error)
            assertTrue(h.socket.isOpen)
            assertTrue(source.authorized())
            assertTrue(h.audio.actual.isPlaying)
            assertEquals(1, h.audio.executions.size)
            assertEquals(initialAcks, h.frames("ack").size)

            val reports = h.frames("report").size
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(reports + 1, h.frames("report").size)
            val renewalBasis = h.frames("report").last()["clientSequence"]!!.jsonPrimitive.long
            h.lease("event", renewalBasis)
            advanceTimeBy(6_000)
            runCurrent()
            // Past the original lease deadline: normal reports and renewals still work.
            assertTrue(source.authorized())
            assertTrue(h.audio.actual.isPlaying)
            assertTrue(h.socket.isOpen)
            assertNull(h.controller.state.value.error)

            // Fresh control IDs cannot bypass plan fences, even after the instruction
            // receipt cache has evicted the original first-stage delivery.
            h.stage("retired-0", reservedNext("retired-0"), instructionId = "late-first-stage")
            h.stage("retired-0", reservedNext("retired-0"), instructionId = "first-stage")
            h.stage("retired-159", reservedNext("retired-159"), instructionId = "late-unrecorded-stage")
            h.stage("after-capacity", reservedNext("after-capacity"))
            assertEquals(1, h.audio.executions.size)
            val endedFacts = h.audio.actual.copy(ended = true, isPlaying = false, elapsed = 10.0)
            h.observe(endedFacts)
            h.observe(endedFacts)
            val ended = h.frames("report").single { it["ended"]!!.jsonPrimitive.boolean }
            assertNull(ended["planId"])
            assertEquals("execution", ended["instructionId"]!!.jsonPrimitive.content)
            assertEquals(1, h.audio.executions.size)
            assertEquals(PlaybackConnectionState.READY, h.controller.state.value.connection)

            // No speculative admission occurred. The server can choose fresh ordinary
            // execution IDs for end fallback without reconnecting or reloading twice.
            val basis = ended["clientSequence"]!!.jsonPrimitive.long
            h.instruction(PlaybackInstruction("fallback-execution", "execute", 1, "fallback-event", 0.0, true,
                track = track("next", 2), lease = LeaseTiming(basis, 8_000)))
            assertEquals(2, h.audio.executions.size)
            assertEquals("fallback-execution", h.audio.executions.last().binding.instructionId)
            assertTrue(h.audio.actual.isPlaying)
            h.stage("retired-0", reservedNext("retired-0")) // Obsolete source binding.
            assertEquals(2, h.audio.executions.size)

            // The latch is scoped to the old execution, not the socket or output version.
            val next = reservedNext("recovered")
            h.instruction(PlaybackInstruction("recovered-stage", "stageNext", 1, "fallback-event", 0.0, false,
                planId = "recovered", currentInstructionId = "fallback-execution", next = next))
            h.observe(h.audio.actual.copy(ended = true, isPlaying = false, elapsed = 10.0))
            assertEquals(3, h.audio.executions.size)
            assertEquals(next.instructionId, h.audio.executions.last().binding.instructionId)
            assertTrue(h.audio.actual.isPlaying)
            assertEquals("recovered", h.frames("report").last { it["ended"]!!.jsonPrimitive.boolean }["planId"]!!.jsonPrimitive.content)
            assertTrue(h.socket.isOpen)
        }
    }

    @Test fun delayedRetiredPlansCannotReplaceLivePlanAfterInstructionReceiptEviction() = runTest {
        Harness(this).use { h ->
            h.start()
            val retired = reservedNext("retired")
            h.stage("retired", retired, instructionId = "original-retired-stage")
            h.cancel("retired")
            val live = reservedNext("live")
            repeat(140) { h.stage("live", live) }
            // Repeated live staging is idempotent and does not consume tombstones.
            // Both an evicted instruction ID and a different delivery ID remain fenced.
            h.stage("retired", retired, instructionId = "original-retired-stage")
            h.stage("retired", retired, instructionId = "different-retired-stage")
            assertTrue(h.audio.actual.isPlaying)
            assertEquals(1, h.audio.executions.size)
            h.observe(h.audio.actual.copy(ended = true, isPlaying = false, elapsed = 10.0))
            val ended = h.frames("report").single { it["ended"]!!.jsonPrimitive.boolean }
            assertEquals("live", ended["planId"]!!.jsonPrimitive.content)
            assertEquals(2, h.audio.executions.size)
            assertEquals(live.instructionId, h.audio.executions.last().binding.instructionId)
            assertEquals(PlaybackConnectionState.READY, h.controller.state.value.connection)
        }
    }

    @Test fun replacingStageAtCapacityDoesNotAdmitItsReplacementOrStopCurrentAudio() = runTest {
        Harness(this).use { h ->
            h.start()
            // The 129th staging retires the 128th distinct prior plan inside
            // discardStage, after acceptPlan's initial admission checks.
            repeat(129) { index -> h.stage("replacement-$index", reservedNext("replacement-$index")) }
            assertTrue(h.audio.actual.isPlaying)
            assertTrue(h.audio.executions.single().authorized())
            assertTrue(h.socket.isOpen)
            assertNull(h.controller.state.value.error)
            h.observe(h.audio.actual.copy(ended = true, isPlaying = false, elapsed = 10.0))
            assertNull(h.frames("report").single { it["ended"]!!.jsonPrimitive.boolean }["planId"])
            assertEquals(1, h.audio.executions.size)
        }
    }

    @Test fun localStageDiscardAtCapacityStillSendsCommandsAndReportsOrdinaryEnd() = runTest {
        Harness(this).use { h ->
            h.start()
            repeat(129) { index ->
                h.stage("discarded-$index", reservedNext("discarded-$index"))
                val id = h.controller.setShuffle(index % 2 == 0)
                assertNotNull(id)
                h.result(requireNotNull(id))
            }
            assertEquals(129, h.frames("command").size)
            assertTrue(h.audio.actual.isPlaying)
            assertTrue(h.audio.executions.single().authorized())
            assertTrue(h.socket.isOpen)
            h.stage("late-discarded", reservedNext("late-discarded"))
            h.observe(h.audio.actual.copy(ended = true, isPlaying = false, elapsed = 10.0))
            assertNull(h.frames("report").single { it["ended"]!!.jsonPrimitive.boolean }["planId"])
            assertEquals(1, h.audio.executions.size)
            assertEquals(PlaybackConnectionState.READY, h.controller.state.value.connection)
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

    private class Harness(private val scope: TestScope, name: String = "Phone") : AutoCloseable {
        val transport = FakeTransport(FakeConnection())
        val socket get() = transport.socket
        val audio = FakeAudio()
        val wall get() = 1_700_000_000_000L + scope.testScheduler.currentTime
        val controller = PlaybackController(transport, audio, scope.backgroundScope, object : PlaybackClock {
            override fun monotonicMs() = scope.testScheduler.currentTime
            override fun wallTimeMs() = wall
        })
        private var controlId = 0
        init {
            controller.connect(name, "device")
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
        suspend fun stage(
            planId: String,
            next: NextExecution = NextExecution("next-event", "next-execution", track("next", 2)),
            instructionId: String? = null,
        ) = instruction(PlaybackInstruction(
            instructionId ?: "stage-${++controlId}", "stageNext", 1, "event", 0.0, false, planId = planId,
            currentInstructionId = "execution", next = next))
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
        val admissions = mutableListOf<Pair<String, String>>()
        override suspend fun connect(name: String, deviceId: String): PlaybackConnection {
            admissions.add(name to deviceId)
            return socket
        }
    }

    private class FakeConnection : PlaybackConnection {
        val incoming = Channel<String>(Channel.UNLIMITED)
        val sent = mutableListOf<JsonObject>()
        override val messages = incoming.receiveAsFlow()
        override var isOpen = true
        var failSend: String? = null
        override fun send(text: String): Boolean {
            val frame = playbackJson.parseToJsonElement(text).jsonObject
            if (!isOpen || frame["type"]!!.jsonPrimitive.content == failSend) return false
            sent.add(frame)
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
        private fun reservedNext(planId: String) = NextExecution("next-event-$planId", "next-execution-$planId", track("next", 2))
        private fun track(id: String = "track", key: Long = 1) = PlaybackTrack(id, "Song", emptyList(), "Album", "album", 10.0, key, key, "now")
        private fun status(output: Long = 0, version: Long = 1, sequence: Long = 1, event: String = "event", playing: Boolean = false) =
            PlaybackStatus(output, version, sequence, event, playing, 0.0, 10.0, playing, false, false, "")
        private fun snapshotValue(version: Long = 1, desiredPlaying: Boolean = true, event: String = "event") = PlaybackSnapshot(
            "session", version, 0, PlaybackQueueState(queue = listOf(track()), position = 0, desiredPlaying = desiredPlaying,
                occurrence = PlaybackOccurrence(1, "track", event, 0.0, false)), status(version = version, event = event),
            listOf(PlaybackDevice("device", "Phone", true, true)), "device", "connection", null, 0)
    }
}
