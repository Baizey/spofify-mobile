package dev.spofify.mobile.playback

import dev.spofify.mobile.core.*
import dev.spofify.mobile.feature.devices.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryPlaybackExecutorTest {
    private val track = LibraryPlaybackTrack("track/曲", "Song", emptyList(), "Album", "album", 30.0, 1, 0, "now")
    private fun snapshot(output: Long = 1, selected: String = "", transfer: Boolean = true) = LibraryPlaybackSnapshot(
        "session", 1, output,
        LibraryPlaybackState(1, listOf(track), 0, null, false, "off", true, emptyList(), 0.0, 0.0,
            false, false, false, "", false, "", LibraryPlaybackOccurrence(1, track.id, "event", 0.0, false)),
        LibraryPlaybackStatus(output, 1, 0, "event", false, 0.0, 0.0, false, false, false, ""),
        listOf(LibraryPlaybackDevice("phone", "Phone", true, true)), "phone", selected,
        if (transfer) LibraryPlaybackTransfer("", "phone", "", "connection", "preparing") else null, 0,
    )

    private class Endpoint : NativePlaybackEndpoint {
        override var onObservation: ((String, Boolean) -> Unit)? = null
        var prepared = 0
        var executed = mutableListOf<String>()
        var facts = LibraryPlaybackObservation()
        var result = "ok"
        var gate: CompletableDeferred<String>? = null
        var authority: LibraryPlaybackAuthority? = null
        var nativePending = false
        var finishAfterCancellation = false
        var completedNativeCallbacks = 0
        val starts = mutableListOf<String>()
        val cacheLineages = mutableListOf<LibraryPlaybackCacheLineage>()
        override fun parkCacheReads(lineage: LibraryPlaybackCacheLineage) { cacheLineages += lineage }
        var stops = 0
        var replacementDrains = 0
        override fun suspendForReplacement() { replacementDrains++; nativePending = false; facts = facts.copy(isPlaying = false) }
        override suspend fun prepare(instruction: LibraryPlaybackInstruction, isCurrent: () -> Boolean): String {
            prepared++; assertTrue(isCurrent()); return "ok"
        }
        override fun release(): Boolean { suspendOutput(); return true }
        override suspend fun execute(instruction: LibraryPlaybackInstruction, authority: LibraryPlaybackAuthority): String {
            assertTrue(authority.isValid()); this.authority = authority
            executed += instruction.id; nativePending = true
            suspend fun finishNativeOperation(): String {
                val status = gate?.await() ?: result
                completedNativeCallbacks++
                if (!authority.isValid()) return "rejected"
                nativePending = false
                if (status == "ok" && instruction.desiredPlaying) starts += instruction.id
                return status
            }
            return if (finishAfterCancellation) withContext(NonCancellable) { finishNativeOperation() }
                else finishNativeOperation()
        }
        override fun observe() = facts
        override fun suspendOutput() { nativePending = false; stops++; facts = facts.copy(isPlaying = false) }
    }

    private inner class Rig(val scope: TestScope) {
        val endpoint = Endpoint()
        val messages = mutableListOf<Pair<Long, LibraryPlaybackClientMessage>>()
        var sequence = 0L
        var current = true
        var onSend: (LibraryPlaybackClientMessage) -> Unit = {}
        var failSend = false
        val executor = LibraryPlaybackExecutor(endpoint, scope.backgroundScope, { scope.testScheduler.currentTime }, { message ->
            onSend(message)
            if (failSend) null else {
                messages += ++sequence to message
                LibraryPlaybackSend(scope.testScheduler.currentTime * 1_000_000L, clientSequence = sequence)
            }
        }, { current })
        init { executor.connected("session", "connection"); executor.update(snapshot()) }
        fun prepare() {
            executor.instruction(LibraryPlaybackInstruction("prepare", "prepare", 1, "event", 0.0, false, track = track))
            scope.runCurrent()
        }
        fun activate(playing: Boolean = true, basis: Long = sequence, output: Long = 2) {
            executor.instruction(LibraryPlaybackInstruction("active", "activate", output, "event", 0.0, playing,
                track = track, lease = LibraryPlaybackLeaseTiming(basis, 8000)))
            executor.update(snapshot(output, "connection", false))
            scope.runCurrent()
        }
        fun stage(plan: String = "plan") {
            executor.instruction(LibraryPlaybackInstruction("stage", "stageNext", 2, "event", 0.0, false,
                planId = plan, currentInstructionId = "active", next = LibraryPlaybackNext("next-event", "next-execution", track.copy(key = 2))))
        }
        fun acks() = messages.mapNotNull { (it.second as? LibraryPlaybackClientMessage.Ack) }
        fun reports() = messages.mapNotNull { (it.second as? LibraryPlaybackClientMessage.Report) }
    }

    @Test fun startupSnapshotReconnectAndRenewalNeverExecuteMedia() = runTest {
        val r = Rig(this)
        r.executor.update(snapshot(2, "connection", false))
        r.executor.lease(LibraryPlaybackLease(2, "event", 1, 8000))
        advanceTimeBy(1000); runCurrent()
        assertTrue(r.endpoint.executed.isEmpty())
        assertEquals(0, r.endpoint.prepared)
        assertTrue(r.messages.isEmpty())
        r.executor.disconnected(); r.executor.connected("session", "new-connection")
        r.executor.update(snapshot(2, "connection", false)); runCurrent()
        assertTrue(r.endpoint.executed.isEmpty())
    }

    @Test fun logicalDeviceMatchCannotAuthorizeExecutionOnWrongConnection() = runTest {
        val r = Rig(this)
        r.executor.update(snapshot(2, "sibling", false))
        r.executor.instruction(LibraryPlaybackInstruction("prepare", "prepare", 2, "event", 0.0, false, track = track))
        r.executor.instruction(LibraryPlaybackInstruction("active", "activate", 2, "event", 0.0, true,
            track = track, lease = LibraryPlaybackLeaseTiming(1, 8000)))
        runCurrent()
        assertEquals(1, r.endpoint.prepared) // targeted non-grant prepare is allowed before state
        assertTrue(r.endpoint.executed.isEmpty())
        assertEquals(listOf("prepare"), r.acks().map { it.instructionId })
    }

    @Test fun targetedPrepareBeforeTransferStateIsAdmittedSilentlyThenActivatesFromExactBasis() = runTest {
        val r = Rig(this)
        r.executor.update(snapshot(2, "other-connection", false))
        r.executor.instruction(LibraryPlaybackInstruction("switch-prepare", "prepare", 2, "event", 7.0, false, track = track))
        runCurrent()
        assertEquals(1, r.endpoint.prepared)
        assertTrue(r.endpoint.executed.isEmpty())
        val prepareAck = r.sequence
        r.executor.update(snapshot(2, "other-connection", true).copy(stateVersion = 2))
        r.executor.instruction(LibraryPlaybackInstruction("switch-active", "activate", 3, "event", 7.0, true,
            track = track, lease = LibraryPlaybackLeaseTiming(prepareAck, 8000)))
        runCurrent()
        assertEquals(listOf("switch-active"), r.endpoint.executed)
        assertEquals("ok", r.acks().last().status)
    }

    @Test fun parkedSameSocketResumeUsesPrepareBeforeStateAndLatestReleaseAckBasis() = runTest {
        val r = Rig(this); r.prepare(); r.activate(playing = false)
        assertFalse(r.endpoint.authority!!.isValid())
        r.executor.instruction(LibraryPlaybackInstruction("resume-prepare", "prepare", 2, "event", 4.0, false, track = track))
        runCurrent()
        assertEquals(2, r.endpoint.prepared)
        r.executor.update(snapshot(2, "connection", true).copy(stateVersion = 2))
        r.executor.instruction(LibraryPlaybackInstruction("resume-release", "release", 2, "event", 0.0, false))
        val releaseAck = r.sequence
        r.executor.instruction(LibraryPlaybackInstruction("resume-active", "activate", 3, "event", 4.0, true,
            track = track, lease = LibraryPlaybackLeaseTiming(releaseAck, 8000)))
        runCurrent()
        assertEquals(listOf("active", "resume-active"), r.endpoint.executed)
        assertTrue(r.endpoint.authority!!.isValid())
    }

    @Test fun preparationIsSilentAndReportsAreActualFactsNotDesiredPlaying() = runTest {
        val r = Rig(this); r.prepare()
        assertEquals(1, r.endpoint.prepared)
        assertTrue(r.endpoint.executed.isEmpty())
        assertTrue(r.reports().isEmpty())
        r.activate(playing = true)
        assertEquals(listOf("active"), r.endpoint.executed)
        assertEquals("ok", r.acks().last().status)
        assertFalse(r.reports().last().isPlaying)
        assertEquals(0.0, r.reports().last().duration, 0.0)
    }

    @Test fun grantExpiresAtEarlierSendTimeNotGrantReceiptTime() = runTest {
        val r = Rig(this); r.prepare()
        val basis = r.sequence
        advanceTimeBy(7500); runCurrent()
        r.activate(basis = basis)
        assertTrue(r.endpoint.authority!!.isValid())
        val before = r.endpoint.stops
        advanceTimeBy(500); runCurrent()
        assertFalse(r.endpoint.authority!!.isValid())
        assertTrue(r.endpoint.stops > before)
        r.executor.lease(LibraryPlaybackLease(2, "event", r.sequence, 8000))
        assertFalse(r.endpoint.authority!!.isValid())
        assertEquals(listOf("active"), r.endpoint.executed)
    }

    @Test fun missingAndExpiredSendBasisCannotActivate() = runTest {
        for (expired in listOf(false, true)) {
            val r = Rig(this); r.prepare()
            val sequence = r.sequence
            if (expired) { advanceTimeBy(8000); runCurrent() }
            r.activate(basis = if (expired) sequence else 999)
            assertTrue(r.endpoint.executed.isEmpty())
            assertEquals("rejected", r.acks().last().status)
            r.executor.disconnected()
        }
    }

    @Test fun sequenceOwnedByTransportRemainsMonotonicAcrossOutputVersions() = runTest {
        val r = Rig(this); r.prepare(); r.activate()
        val before = r.sequence
        r.executor.update(snapshot(2, "connection", true))
        r.executor.instruction(LibraryPlaybackInstruction("prepare-again", "prepare", 2, "event", 3.0, false, track = track))
        runCurrent()
        val basis = r.sequence
        r.executor.instruction(LibraryPlaybackInstruction("active-again", "activate", 3, "event", 3.0, true,
            track = track, lease = LibraryPlaybackLeaseTiming(basis, 8000)))
        runCurrent()
        assertTrue(r.sequence > before)
        assertEquals((1L..r.sequence).toList(), r.messages.map { it.first })
        assertEquals(listOf("active", "active-again"), r.endpoint.executed)
    }

    @Test fun sourceReleaseAndFailedExecutionStopPendingMediaBeforeAck() = runTest {
        val r = Rig(this); r.prepare(); r.activate()
        r.onSend = { message -> if (message is LibraryPlaybackClientMessage.Ack && message.instructionId == "release") {
            assertFalse(r.endpoint.nativePending); assertFalse(r.endpoint.facts.isPlaying)
        } }
        r.endpoint.nativePending = true
        r.executor.instruction(LibraryPlaybackInstruction("release", "release", 2, "event", 0.0, false))
        assertEquals("ok", r.acks().last().status)
        r.executor.update(snapshot(2, "connection", true))
        r.executor.instruction(LibraryPlaybackInstruction("prepare-new", "prepare", 2, "event", 0.0, false, track = track))
        runCurrent()
        r.endpoint.result = "blocked"
        r.onSend = { message -> if (message is LibraryPlaybackClientMessage.Ack && message.status == "blocked") assertFalse(r.endpoint.nativePending) }
        r.activate(output = 3)
        assertEquals("blocked", r.acks().last().status)
        val count = r.messages.size
        advanceTimeBy(20_000); runCurrent()
        assertEquals(count, r.messages.size) // quiet parked selection
    }

    @Test fun parkedCacheLineageNeedsPriorSuccessfulPlaybackSurvivesOldAudioDeadlineAndRevokesOnOutputLoss() = runTest {
        val initial = Rig(this); initial.prepare(); initial.activate(playing = false)
        assertTrue(initial.endpoint.cacheLineages.isEmpty()) // startup paused state cannot mint lineage
        initial.executor.disconnected()
        val r = Rig(this); r.prepare(); r.activate()
        r.executor.instruction(LibraryPlaybackInstruction("park", "execute", 2, "event", 1.0, false,
            track = track, lease = LibraryPlaybackLeaseTiming(r.sequence, 8000)))
        runCurrent()
        val lineage = r.endpoint.cacheLineages.single()
        assertTrue(lineage.isValid())
        assertFalse(r.endpoint.authority!!.isValid()) // the data token never revives audio
        val starts = r.endpoint.starts.toList()
        advanceTimeBy(60_000); runCurrent()
        assertTrue("Cache lineage is not the retired 8s playback lease", lineage.isValid())
        assertEquals(starts, r.endpoint.starts)
        r.executor.update(snapshot(2, "connection", false).copy(stateVersion = 2))
        assertTrue(lineage.isValid()) // an accepted paused queue edit can derive a bounded data plan
        r.executor.update(snapshot(2, "sibling", false).copy(stateVersion = 3))
        assertFalse(lineage.isValid())
    }

    @Test fun releaseStopsThenReportsFinalObservedElapsedUnderPriorExecutionBeforeExactReleaseAck() = runTest {
        val r = Rig(this); r.prepare(); r.activate()
        r.endpoint.facts = LibraryPlaybackObservation(elapsed = 1.25, duration = 30.0, isPlaying = true)
        r.endpoint.onObservation?.invoke("active", false)
        // Native clock progressed since the last meaningful event/two-second sample.
        r.endpoint.facts = r.endpoint.facts.copy(elapsed = 1.875)
        val before = r.messages.size
        r.executor.instruction(LibraryPlaybackInstruction("release-final", "release", 2, "event", 0.0, false))
        val final = r.messages.drop(before)
        assertEquals(2, final.size)
        val report = final[0].second as LibraryPlaybackClientMessage.Report
        val ack = final[1].second as LibraryPlaybackClientMessage.Ack
        assertEquals("active", report.instructionId)
        assertEquals("event", report.eventId)
        assertEquals(2L, report.outputVersion)
        assertEquals(1.875, report.elapsed, 0.0) // endpoint observation, NOT release.elapsed=0
        assertFalse(report.isPlaying)
        assertFalse(report.ended)
        assertEquals("release-final", ack.instructionId)
        assertEquals("ok", ack.status)
        assertEquals(final[0].first + 1, final[1].first)
        assertFalse(r.reports().any { it.instructionId == "release-final" })
    }

    @Test fun parkedDuplicateReleaseFactsRemainQuietAndExpiredSourceProducesNoFinalReport() = runTest {
        val parked = Rig(this); parked.prepare(); parked.activate(playing = false)
        val reports = parked.reports().size
        parked.executor.instruction(LibraryPlaybackInstruction("parked-release", "release", 2, "event", 0.0, false))
        assertEquals(reports, parked.reports().size)
        assertEquals("parked-release", parked.acks().last().instructionId)
        parked.executor.disconnected()

        val expired = Rig(this); expired.prepare(); expired.activate()
        advanceTimeBy(8000); runCurrent()
        val count = expired.reports().size
        expired.executor.instruction(LibraryPlaybackInstruction("expired-release", "release", 2, "event", 0.0, false))
        assertEquals(count, expired.reports().size)
        expired.current = false
        val messages = expired.messages.size
        expired.executor.instruction(LibraryPlaybackInstruction("auth-lost-release", "release", 2, "event", 0.0, false))
        assertEquals(messages, expired.messages.size)
    }

    @Test fun explicitPauseCancelsPendingNativeStartBeforeRoundtripWithoutFakeProofAndAdmitsAuthoritativePause() = runTest {
        val r = Rig(this); r.prepare(); r.activate()
        val basis = r.sequence
        val lateNative = CompletableDeferred<String>()
        r.endpoint.gate = lateNative
        r.endpoint.finishAfterCancellation = true
        r.executor.instruction(LibraryPlaybackInstruction("pending-start", "execute", 2, "event", 9.0, true,
            track = track, lease = LibraryPlaybackLeaseTiming(basis, 8000)))
        runCurrent()
        val pendingAuthority = r.endpoint.authority!!
        assertTrue(pendingAuthority.isValid())
        assertTrue(r.endpoint.nativePending)
        val sentBeforeStop = r.messages.size
        val callbacksBeforeStop = r.endpoint.completedNativeCallbacks
        val startsBeforeStop = r.endpoint.starts.toList()

        r.executor.interruptForStopIntent() // coordinator invokes BEFORE sending Pause/Close
        assertFalse("Synchronous native-start revocation", pendingAuthority.isValid())
        assertFalse(r.endpoint.nativePending)
        assertEquals("No fabricated ACK/report from local cancellation", sentBeforeStop, r.messages.size)

        lateNative.complete("ok") // native callback outlives coroutine cancellation
        runCurrent()
        assertEquals(callbacksBeforeStop + 1, r.endpoint.completedNativeCallbacks)
        assertEquals("Late callback cannot start audio", startsBeforeStop, r.endpoint.starts)
        assertEquals("No invented completion/stopped proof", sentBeforeStop, r.messages.size)
        assertFalse(r.acks().any { it.instructionId == "pending-start" })

        r.endpoint.gate = null
        r.endpoint.finishAfterCancellation = false
        r.executor.instruction(LibraryPlaybackInstruction("authoritative-pause", "execute", 2, "event", 9.0, false,
            track = track, lease = LibraryPlaybackLeaseTiming(basis, 8000)))
        runCurrent()
        assertEquals(listOf("active", "pending-start", "authoritative-pause"), r.endpoint.executed)
        assertEquals("ok", r.acks().single { it.instructionId == "authoritative-pause" }.status)
        assertFalse(r.acks().any { it.status == "rejected" })
        assertEquals(startsBeforeStop, r.endpoint.starts)
        assertFalse(r.reports().last().isPlaying)
    }

    @Test fun authLossSynchronouslyInvalidatesAuthorityBeforeClosedCallback() = runTest {
        val r = Rig(this); r.prepare(); r.activate()
        val authority = r.endpoint.authority!!
        assertTrue(authority.isValid())
        r.current = false
        assertFalse(authority.isValid())
        val count = r.messages.size
        r.endpoint.onObservation?.invoke("active", false)
        assertEquals(count, r.messages.size)
        r.executor.disconnected()
        assertFalse(authority.isValid())
    }

    @Test fun pendingAdmittedNextDeduplicatesConfirmationFromAdmissionAndAcksOriginalFailureOnce() = runTest {
        val r = Rig(this); r.prepare(); r.activate()
        r.stage()
        val gate = CompletableDeferred<String>(); r.endpoint.gate = gate
        r.endpoint.onObservation?.invoke("active", true)
        runCurrent()
        val endedSequence = r.messages.last { (it.second as? LibraryPlaybackClientMessage.Report)?.ended == true }.first
        assertEquals(listOf("active", "next-execution"), r.endpoint.executed)
        r.executor.discardStagedNext() // ordinary queue/mode sends cannot revoke admitted current
        assertTrue(r.endpoint.authority!!.isValid())
        assertTrue(r.endpoint.nativePending)
        assertEquals("plan", r.reports().last { it.ended }.planId)
        val confirmation = LibraryPlaybackInstruction("next-execution", "execute", 2, "next-event", 0.0, true,
            track = track.copy(key = 2), lease = LibraryPlaybackLeaseTiming(endedSequence, 8000), planId = "plan")
        r.executor.instruction(confirmation); runCurrent()
        assertEquals(2, r.endpoint.executed.size)
        assertFalse(r.acks().any { it.instructionId == "next-execution" })
        gate.complete("media"); runCurrent()
        assertEquals(2, r.endpoint.executed.size)
        val acks = r.acks().filter { it.instructionId == "next-execution" }
        assertEquals(1, acks.size); assertEquals("media", acks.single().status)
        r.executor.instruction(confirmation); runCurrent()
        assertEquals(2, r.endpoint.executed.size)
        assertEquals(1, r.acks().count { it.instructionId == "next-execution" })
    }

    @Test fun lateSourceGrantDoesNotRenewOrRevokeAdmittedNextAndDuplicateEndCannotAdvance() = runTest {
        val r = Rig(this); r.prepare(); r.activate(); r.stage()
        val sourceSequence = r.sequence
        advanceTimeBy(7000); runCurrent()
        r.endpoint.onObservation?.invoke("active", true); runCurrent()
        val nextAuthority = r.endpoint.authority!!
        assertTrue(nextAuthority.isValid())
        r.executor.lease(LibraryPlaybackLease(2, "event", sourceSequence, 8000))
        assertTrue(nextAuthority.isValid())
        r.endpoint.onObservation?.invoke("active", true); runCurrent()
        assertEquals(2, r.endpoint.executed.size)
        advanceTimeBy(1000); runCurrent()
        assertFalse(nextAuthority.isValid())
        assertEquals(2, r.endpoint.executed.size)
    }

    @Test fun staleEndedAfterReplacementAndCanceledPlanCannotLocallyAdvance() = runTest {
        val r = Rig(this); r.prepare(); r.activate(); r.stage("old")
        r.executor.instruction(LibraryPlaybackInstruction("cancel", "cancelNext", 2, "event", 0.0, false,
            planId = "old", currentInstructionId = "active"))
        r.stage("old") // delayed duplicate cannot resurrect canceled authorization
        r.endpoint.onObservation?.invoke("active", true); runCurrent()
        assertEquals(listOf("active"), r.endpoint.executed)
        assertNull(r.reports().last { it.ended }.planId)
        r.executor.disconnected()
        r.endpoint.onObservation?.invoke("active", true)
        assertEquals(1, r.reports().count { it.ended })
    }

    @Test fun liveNewOccurrenceUsesReplacementDrainButReleaseAndExpiryStillFullySuspend() = runTest {
        val r = Rig(this); r.prepare(); r.activate()
        val stops = r.endpoint.stops
        r.executor.instruction(LibraryPlaybackInstruction("new-current", "execute", 2, "event-b", 0.0, true,
            track = track.copy(id = "other", key = 2), lease = LibraryPlaybackLeaseTiming(r.sequence, 8000)))
        runCurrent()
        assertEquals(1, r.endpoint.replacementDrains)
        assertEquals(stops, r.endpoint.stops)
        assertEquals(listOf("active", "new-current"), r.endpoint.starts)
        val permit = r.endpoint.authority!!
        advanceTimeBy(8000); runCurrent()
        assertFalse(permit.isValid())
        assertTrue(r.endpoint.stops > stops)
        val beforeRelease = r.endpoint.stops
        r.executor.instruction(LibraryPlaybackInstruction("release", "release", 2, "event-b", 0.0, false))
        assertTrue(r.endpoint.stops > beforeRelease)
        assertEquals(1, r.endpoint.replacementDrains)
    }

    @Test fun pendingUnprovenReplacementCannotInheritForegroundRetention() = runTest {
        val r = Rig(this); r.prepare()
        r.endpoint.gate = CompletableDeferred()
        r.activate()
        val stops = r.endpoint.stops
        r.executor.instruction(LibraryPlaybackInstruction("replacement", "execute", 2, "event-b", 0.0, true,
            track = track.copy(key = 2), lease = LibraryPlaybackLeaseTiming(1, 8000)))
        runCurrent()
        assertEquals(0, r.endpoint.replacementDrains)
        assertTrue(r.endpoint.stops > stops)
    }

    @Test fun oldInstructionEndedCallbackCannotAdvanceAfterAbsoluteSeekExecution() = runTest {
        val r = Rig(this); r.prepare(); r.activate()
        val stops = r.endpoint.stops
        r.executor.instruction(LibraryPlaybackInstruction("seek-execution", "execute", 2, "event", 12.0, true,
            track = track, lease = LibraryPlaybackLeaseTiming(r.sequence, 8000)))
        runCurrent()
        assertEquals(listOf("active", "seek-execution"), r.endpoint.executed)
        assertEquals("Live same-occurrence execute does not drain the decoder/focus", stops, r.endpoint.stops)
        val before = r.messages.size
        r.endpoint.onObservation?.invoke("active", true)
        assertEquals(before, r.messages.size)
        r.endpoint.onObservation?.invoke("seek-execution", true)
        assertEquals(listOf("seek-execution"), r.reports().filter { it.ended }.map { it.instructionId })
    }

    @Test fun validRenewalUsesReportSendBasisWithoutExecutingAgain() = runTest {
        val r = Rig(this); r.prepare(); r.activate()
        advanceTimeBy(2000); runCurrent()
        val sequence = r.messages.last { it.second is LibraryPlaybackClientMessage.Report }.first
        r.executor.lease(LibraryPlaybackLease(2, "event", sequence, 8000))
        advanceTimeBy(6000); runCurrent()
        assertTrue(r.endpoint.authority!!.isValid())
        assertEquals(listOf("active"), r.endpoint.executed)
        advanceTimeBy(2000); runCurrent()
        assertFalse(r.endpoint.authority!!.isValid())
    }

    @Test fun sendFailureStopsOutputAndCannotAdmitPlannedNext() = runTest {
        val r = Rig(this); r.prepare(); r.activate(); r.stage()
        r.failSend = true
        r.endpoint.onObservation?.invoke("active", true); runCurrent()
        assertEquals(listOf("active"), r.endpoint.executed)
        assertFalse(r.endpoint.authority!!.isValid())
    }
}
