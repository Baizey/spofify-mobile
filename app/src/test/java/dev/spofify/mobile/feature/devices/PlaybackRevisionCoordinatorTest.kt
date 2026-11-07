package dev.spofify.mobile.feature.devices

import dev.spofify.mobile.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackRevisionCoordinatorTest {
    private fun music() = PlaybackSnapshot(queue = listOf(PlaybackTrack("track", "Song", emptyList(), "Album", "album", 180.0, key = 1, order = 0)),
        position = 0, duration = 180.0, occurrence = PlaybackOccurrence(1, "track", "event", 0.0, false))
    private class Endpoint(initial: PlaybackSnapshot) : NativePlaybackEndpoint {
        override val supportsExactHandoff = true
        var snapshot = initial; var fence = 0L; var exports = 0; var starts = 0; var stops = 0
        override fun exportSnapshot(): PlaybackSnapshot { exports++; return snapshot }
        override fun captureRevisionSync() = PlaybackSyncCapture(fence, PlaybackSyncLive.from(snapshot), snapshot, null)
        override fun suspendOutput() { stops++; snapshot = snapshot.paused() }
        override suspend fun prepare(snapshot: PlaybackSnapshot) = true
        override suspend fun activate(snapshot: PlaybackSnapshot, authority: PlaybackAuthority): PlaybackEndpointResult {
            check(authority.isValid()); starts++; this.snapshot = snapshot; fence++
            return PlaybackEndpointResult("ok", snapshot)
        }
        override suspend fun execute(command: PlaybackDeviceCommand, authority: PlaybackAuthority) = PlaybackEndpointResult("ok",snapshot)
        fun mutate() { fence++; snapshot = snapshot.copy(repeat = if (snapshot.repeat == "all") "off" else "all") }
    }
    private class Protocol(initial: PlaybackSnapshot) : PlaybackDevicesProtocol {
        val sync = PlaybackRevisionSync()
        var state = initial; var seq = 0L; var revision = 4L; var epoch = 2L; var active = "phone"; var lease = 8000L
        var fulls = 0; var compacts = 0; var fetches = 0; var accepts = mutableListOf<PlaybackSyncReference>()
        var fetchGate: CompletableDeferred<Unit>? = null; var pollGate: CompletableDeferred<Unit>? = null
        var fetchRevision: Long? = null; var ack = true; var ackSequenceOffset = 0L
        var controlError = ""; var commands = emptyList<PlaybackOutcome>()
        val sentAcks = mutableListOf<List<PlaybackAck>>()
        override val revisionSyncSupported get() = true
        override val syncSnapshotNeeded get() = sync.needsSnapshot
        fun viewValue(directives: List<PlaybackDirective> = emptyList()) = PlaybackDevicesView("phone",listOf(PlaybackDevice("phone","Phone",true)),
            active,epoch,lease,0,state,seq,0,null,directives,commands)
        fun ref(rev: Long) = buildJsonObject { put("version",1); put("incarnation","room"); put("revision",rev) }
        fun envelope(rev: Long = revision) = buildJsonObject { put("kind","snapshot"); put("sync",ref(rev)); put("snapshot",viewValue().toJson()) }
        fun status(publication: Long? = null) = sync.response(buildJsonObject {
            put("kind","status"); put("sync",ref(revision))
            put("live",buildJsonObject { PlaybackSyncLive.from(state).toJson().forEach { (k,v) -> put(k,v) }; put("stateSeq",seq); put("stateAgeMs",0) })
            put("control",buildJsonObject {
                put("activeDeviceId",active); put("epoch",epoch); put("leaseMs",lease); put("quarantineMs",0)
                put("availability",buildJsonArray { add(buildJsonObject { put("deviceId","phone"); put("available",true) }) })
                put("transfer",JsonNull); put("directives",JsonArray(emptyList())); put("commands",JsonArray(commands.map {
                    buildJsonObject { put("commandId",it.commandId); put("status",it.status) }
                })); if(controlError.isNotEmpty()) put("error",controlError)
            })
            if(publication != null && ack) put("publicationAck",buildJsonObject { put("stateSeq",publication + ackSequenceOffset)
                put("base",PlaybackSyncReference("room",revision).toJson()) })
        },false,false)
        override suspend fun probeQueueIntents() = sync.probe(envelope())
        override suspend fun view() = viewValue()
        override suspend fun register(name: String) = viewValue(listOf(PlaybackDirective("activate",epoch,"activate",state = state)))
        override suspend fun fetchSyncSnapshot(): PlaybackDevicesView {
            fetches++; val result = sync.snapshot(envelope(fetchRevision ?: revision),false,false)
            fetchGate?.await(); return result
        }
        override fun acceptSyncSnapshot(view: PlaybackDevicesView) { view.syncSnapshotReference?.let { accepts += it }; sync.accept(view) }
        override suspend fun poll(deviceId: String, epoch: Long, stateSeq: Long?, state: PlaybackSnapshot?, acks: List<PlaybackAck>): PlaybackDevicesView {
            sentAcks += acks
            if(state != null) { fulls++; this.state = state; seq = requireNotNull(stateSeq) }
            val response = status(stateSeq); pollGate?.await(); return response
        }
        override suspend fun pollLive(deviceId: String, epoch: Long, stateSeq: Long, base: PlaybackSyncReference, live: PlaybackSyncLive): PlaybackDevicesView {
            compacts++; require(epoch == this.epoch && deviceId == "phone")
            // Model the server's safe reconciliation after a base/owner transition.
            if(base != PlaybackSyncReference("room",revision) || deviceId != active) return status()
            seq = stateSeq; state = live.applyTo(state)
            val response = status(stateSeq); pollGate?.await(); return response
        }
        override suspend fun rename(deviceId: String,name: String) = viewValue()
        override suspend fun command(deviceId: String,epoch: Long,commandId: String,command: PlaybackDeviceCommand) = viewValue()
        override suspend fun transfer(deviceId: String,epoch: Long,commandId: String,targetDeviceId: String,selectionSeq: Long) = viewValue()
        override suspend fun leave(deviceId: String) {}
    }
    private suspend fun TestScope.tick() { advanceTimeBy(1000); runCurrent() }
    private fun TestScope.start(api: Protocol, endpoint: Endpoint): PlaybackCoordinator =
        PlaybackCoordinator(api,endpoint,backgroundScope,clockMs = { testScheduler.currentTime }).also { it.setServiceActive(true); runCurrent() }

    @Test fun actualCapturedFenceEstablishesAckBaseThenQuietPollsNeverExportQueue() = runTest {
        val api = Protocol(music()); val endpoint = Endpoint(music()); val c = start(api,endpoint)
        tick() // Full terminal activation ACK remains on existing path.
        assertTrue(api.sentAcks.any { it.isNotEmpty() }); assertEquals(0,api.compacts)
        tick() // Fresh local full publication establishes the upload base.
        val exports = endpoint.exports; val fulls = api.fulls
        repeat(4) { endpoint.snapshot = endpoint.snapshot.copy(elapsed = it + 1.0); tick() }
        assertEquals(4,api.compacts); assertEquals(fulls,api.fulls); assertEquals(exports,endpoint.exports)
        assertEquals(1,endpoint.starts); assertTrue(c.state.value.connected)
        c.close()
    }

    @Test fun readObservationAndMissingAckNeverConferAnUploadBase() = runTest {
        val api = Protocol(music()); api.ack = false
        val endpoint = Endpoint(music()); val c = start(api,endpoint)
        repeat(5) { tick() }
        assertTrue(api.fetches > 0); assertTrue(api.fulls >= 4); assertEquals(0,api.compacts)
        c.close()
    }

    @Test fun wrongAcknowledgedSequenceCannotBorrowAnUploadBase() = runTest {
        val api = Protocol(music()); api.ackSequenceOffset = 1
        val endpoint = Endpoint(music()); val c = start(api,endpoint)
        repeat(4) { tick() }
        assertEquals(0,api.compacts)
        api.ackSequenceOffset = 0; tick(); tick()
        assertEquals(1,api.compacts)
        c.close()
    }

    @Test fun localSemanticMutationDuringFullAndCompactFlightForcesFreshFullPublication() = runTest {
        for(compact in listOf(false,true)) {
            val api = Protocol(music()); val endpoint = Endpoint(music()); val c = start(api,endpoint)
            tick()
            if(compact) tick()
            api.pollGate = CompletableDeferred()
            tick(); endpoint.mutate(); api.pollGate!!.complete(Unit); runCurrent(); api.pollGate = null
            val before = api.fulls
            tick(); assertEquals(before + 1,api.fulls)
            c.close()
        }
    }

    @Test fun matchingRevisionControlAndLiveAlwaysApplyAndOwnerLossCannotBorrowOldAck() = runTest {
        val api = Protocol(music()); val endpoint = Endpoint(music()); val c = start(api,endpoint)
        tick(); tick(); tick(); assertTrue(api.compacts > 0)
        endpoint.snapshot = endpoint.snapshot.copy(error = "media", elapsed = 7.0)
        api.commands = listOf(PlaybackOutcome("command","blocked")); tick()
        assertEquals("media",c.state.value.view!!.state!!.error); assertEquals("blocked",c.state.value.view!!.commands.single().status)
        api.active = ""; api.lease = 0
        // Non-owner status after a normal full/compact response is a revocation, not a new grant.
        api.ack = false; tick()
        assertEquals("",c.state.value.activeDeviceId); assertFalse(c.state.value.exclusiveOwner)
        val compactCount = api.compacts; repeat(2) { tick() }; assertEquals(compactCount,api.compacts)
        assertEquals(1,endpoint.starts)
        c.close()
    }

    @Test fun mismatchFetchIsOneAttemptPerCycleAndCommitsFetchedReferenceNotTrigger() = runTest {
        val api = Protocol(music()); val endpoint = Endpoint(music()); val c = start(api,endpoint)
        tick(); tick()
        api.revision = 5; api.fetchRevision = 6
        val before = api.fetches
        tick()
        assertEquals(before + 1,api.fetches)
        assertEquals(PlaybackSyncReference("room",6),api.accepts.last())
        // Continuous revision churn is bounded, never an inner retry loop.
        tick(); assertEquals(before + 2,api.fetches)
        val count = api.fetches
        api.revision = 6; api.fetchRevision = null; tick()
        assertEquals(count,api.fetches)
        assertEquals(1,endpoint.starts)
        c.close()
    }

    @Test fun controlAppliedBeforeDelayedFetchAndLocalPauseDiscardsItsLateResult() = runTest {
        val api = Protocol(music()); val endpoint = Endpoint(music()); val c = start(api,endpoint)
        tick(); tick()
        api.revision = 5; api.fetchGate = CompletableDeferred()
        api.commands = listOf(PlaybackOutcome("new-control","blocked"))
        tick()
        assertEquals("new-control",c.state.value.view!!.commands.single().commandId)
        val accepted = api.accepts.size
        // The queued explicit Pause may perform its OWN later refresh. Distinguish it
        // from the already-captured obsolete GET rather than forbidding fresh work.
        api.fetchRevision = 6
        c.remoteCommand(PlaybackDeviceCommand.transport("pause")); api.fetchGate!!.complete(Unit); runCurrent()
        assertFalse(api.accepts.drop(accepted).contains(PlaybackSyncReference("room",5)))
        assertEquals(PlaybackSyncReference("room",6),api.accepts.last())
        assertFalse(endpoint.snapshot.playing); assertEquals(1,endpoint.starts)
        c.close()
    }
}
