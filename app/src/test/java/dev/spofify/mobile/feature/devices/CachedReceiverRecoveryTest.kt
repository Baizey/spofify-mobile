package dev.spofify.mobile.feature.devices

import dev.spofify.mobile.core.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Serial coordinator unit tests. The memory endpoint/receipts are fixture inputs,
 * not real server/audio evidence; API and actual service tests cover those boundaries. */
@OptIn(ExperimentalCoroutinesApi::class)
class CachedReceiverRecoveryTest {
    private val phone = "a".repeat(32)
    private val own = PlaybackRegistrationDevice(phone, "receiver", "generation-one")
    private val music = PlaybackSnapshot(queue = listOf(PlaybackTrack("song", "Song", emptyList(), "", "", 100.0, key = 7, order = 0)),
        position = 0, elapsed = 42.25, playing = true, occurrence = PlaybackOccurrence(7, "song", "exact-event", 4.25, false))
    private fun view(epoch: Long = 1, selected: Boolean = true, registration: PlaybackRegistrationDevice? = own) = PlaybackDevicesView(
        phone, if (registration == null) emptyList() else listOf(PlaybackDevice(phone, "Phone", true)), if (selected) phone else "", epoch,
        if (selected) 8000 else 0, state = music.paused(), stateSeq = 0, stateAgeMs = 0, transfer = null,
        directives = emptyList(), commands = emptyList(), registration = PlaybackRegistrationView(1, "room", "token", listOfNotNull(registration)),
        leadership = PlaybackLeadership(1, 1, "installation", phone))
    private inner class Output : NativePlaybackEndpoint {
        override val supportsExactHandoff = true
        var snapshot = PlaybackSnapshot()
        var starts = 0; var stops = 0; var renewals = 0
        var rejectRenewal = false
        var permission: CachedPlaybackPermission? = null
        var authority: PlaybackAuthority? = null
        private var recoveryWitness: Any = Any()
        fun cachedPause() { recoveryWitness = Any(); snapshot = snapshot.paused() }
        fun cachedResume() { recoveryWitness = Any(); snapshot = snapshot.copy(playing = true) }
        override fun retainCachedOutput(permission: CachedPlaybackPermission): Boolean {
            if (!snapshot.playing) return false
            this.permission = permission; return true
        }
        override fun continuingOutputWitness(permission: CachedPlaybackPermission): Any? =
            recoveryWitness.takeIf { this.permission === permission && permission.isValid() }
        override fun renewContinuingOutput(permission: CachedPlaybackPermission, authority: PlaybackAuthority, witness: Any): Boolean {
            if (rejectRenewal || continuingOutputWitness(permission) !== witness || !authority.isValid()) return false
            renewals++; this.authority = authority; return true
        }
        override fun suspendOutput() { stops++; recoveryWitness = Any(); snapshot = snapshot.paused() }
        override fun exportSnapshot() = snapshot
        override suspend fun prepare(snapshot: PlaybackSnapshot) = true
        override suspend fun activate(snapshot: PlaybackSnapshot, authority: PlaybackAuthority): PlaybackEndpointResult {
            starts++; this.snapshot = snapshot; this.authority = authority; return PlaybackEndpointResult("ok", snapshot)
        }
        override suspend fun execute(command: PlaybackDeviceCommand, authority: PlaybackAuthority) = error("No musical replay")
    }
    private inner class Wire : PlaybackDevicesProtocol {
        var current = this@CachedReceiverRecoveryTest.view().copy(registration = null, state = music,
            devices = listOf(PlaybackDevice(phone, "Phone", true), PlaybackDevice("source", "Source", true)),
            directives = listOf(PlaybackDirective("activation", 1, "activate", state = music)),
            transfer = PlaybackTransfer("handoff", "source", phone, "activating"))
        var offline = false
        var grants = 0; var enrollments = 0; var loseGrantReply = false
        var grantStarted: CompletableDeferred<Unit>? = null
        var heldGrant: CompletableDeferred<Unit>? = null
        var heldRead: CompletableDeferred<Unit>? = null
        var afterGrant: ((PlaybackDevicesView) -> PlaybackDevicesView)? = null
        val trace = mutableListOf<String>()
        val publications = mutableListOf<PlaybackSnapshot>()
        override suspend fun view(): PlaybackDevicesView {
            trace += "GET"; if (offline) throw IOException("offline")
            heldRead?.let { withContext(NonCancellable) { it.await() } }
            return current
        }
        override suspend fun register(name: String): PlaybackDevicesView = current
        override suspend fun register(name: String, queueIntentsVersion: Int?, installationId: String?, receiverId: String): PlaybackDevicesView {
            trace += "register"; enrollments++
            if (installationId != null) {
                assertEquals(phone, installationId); assertEquals(own.receiverId, receiverId)
                current = view(current.epoch, false, own.copy(registrationGeneration = "generation-new"))
            }
            return current
        }
        override suspend fun poll(deviceId: String, epoch: Long, stateSeq: Long?, state: PlaybackSnapshot?, acks: List<PlaybackAck>): PlaybackDevicesView {
            if (offline) throw IOException("offline")
            trace += if (state == null) "poll:renewal" else "poll:publication"
            assertTrue("Uncertain ACKs must never replay", acks.isEmpty())
            state?.let(publications::add)
            return current.copy(directives = emptyList())
        }
        override suspend fun continueReceiver(registration: PlaybackRegistrationDevice, epoch: Long, leadRevision: Long): PlaybackDevicesView {
            trace += "continue"; grants++
            assertEquals(current.registration!!.devices.single(), registration)
            assertEquals(current.epoch, epoch); assertEquals(current.leadership!!.revision, leadRevision)
            current = current.copy(activeDeviceId = phone, epoch = epoch + 1, leaseMs = 8000)
            grantStarted?.complete(Unit)
            heldGrant?.let { withContext(NonCancellable) { it.await() } }
            if (loseGrantReply) { loseGrantReply = false; throw IOException("accepted reply lost") }
            return afterGrant?.invoke(current) ?: current
        }
        override suspend fun rename(deviceId: String, name: String) = error("No rename")
        override suspend fun command(deviceId: String, epoch: Long, commandId: String, command: PlaybackDeviceCommand) = error("No command replay")
        override suspend fun transfer(deviceId: String, epoch: Long, commandId: String, targetDeviceId: String, selectionSeq: Long) = error("No passive selection")
        override suspend fun leave(deviceId: String) = Unit
    }
    private fun TestScope.fixture(): Triple<PlaybackCoordinator, Wire, Output> {
        val wire = Wire(); val output = Output()
        val owner = PlaybackCoordinator(wire, output, backgroundScope, clockMs = { testScheduler.currentTime })
        owner.setServiceActive(true); runCurrent()
        assertEquals(1, output.starts)
        // Seed an acknowledged managed receipt into this unit fixture after its
        // fake handoff. Production enrollment/authentication is NOT bypassed in code.
        owner.javaClass.getDeclaredField("ownRegistration").apply { isAccessible = true; set(owner, own) }
        owner.javaClass.getDeclaredField("registrationReceiverId").apply { isAccessible = true; set(owner, own.receiverId) }
        wire.current = view()
        return Triple(owner, wire, output)
    }
    private fun TestScope.outage(owner: PlaybackCoordinator, wire: Wire, output: Output) {
        wire.offline = true; advanceTimeBy(1001); runCurrent()
        assertFalse(owner.state.value.connected); assertTrue(requireNotNull(output.permission).isValid())
        assertFalse(requireNotNull(output.authority).isValid())
        wire.trace.clear(); wire.offline = false
    }

    @Test fun expiredEpochAndExpiredRegistrationRestoreExactOutputWithoutActivationOrOldPublication() = runTest {
        for (expiredRegistration in listOf(false, true)) {
            val (owner, wire, output) = fixture(); outage(owner, wire, output)
            wire.current = view(3, false, if (expiredRegistration) null else own)
            val before = output.snapshot
            advanceTimeBy(1001); runCurrent()
            assertTrue(owner.state.value.connected); assertEquals(1, output.renewals)
            assertEquals(before, output.snapshot); assertEquals(1, output.starts); assertEquals(1, wire.grants)
            assertEquals(if (expiredRegistration) listOf("GET", "register", "continue") else listOf("GET", "continue"), wire.trace)
            assertEquals(if (expiredRegistration) 2 else 1, wire.enrollments)
            assertTrue(requireNotNull(output.authority).isValid())
            owner.close(); runCurrent()
        }
    }

    @Test fun lostGrantReplyIsObservedAndRenewedWithoutReplayingGrantOrMusic() = runTest {
        val (owner, wire, output) = fixture(); outage(owner, wire, output)
        wire.current = view(3, false); wire.loseGrantReply = true
        advanceTimeBy(1001); runCurrent()
        assertFalse(owner.state.value.connected); assertEquals(0, output.renewals)
        advanceTimeBy(1001); runCurrent()
        assertEquals(listOf("GET", "continue", "GET", "poll:renewal"), wire.trace)
        assertTrue(owner.state.value.connected); assertEquals(1, wire.grants); assertEquals(1, output.starts)
        assertEquals(music, output.snapshot)
        owner.close()
    }

    @Test fun changedClearedUnknownAndSuccessorReceiverYieldBeforeEnrollmentOrGrant() = runTest {
        for (kind in listOf("changed", "cleared", "unknown", "receiver")) {
            val (owner, wire, output) = fixture(); outage(owner, wire, output)
            wire.current = when (kind) {
                "changed" -> view(3, false).copy(leadership = PlaybackLeadership(1, 2, "installation", "b".repeat(32)))
                "cleared" -> view(3, false).copy(leadership = PlaybackLeadership(1, 2, "cleared", ""))
                "unknown" -> view(3, false).copy(leadership = null)
                else -> view(3, false, own.copy(receiverId = "successor", registrationGeneration = "successor-generation"))
            }
            advanceTimeBy(1001); runCurrent()
            assertFalse(requireNotNull(output.permission).isValid()); assertFalse(output.snapshot.playing)
            assertEquals(0, wire.grants); assertEquals(1, wire.enrollments); assertEquals(1, output.starts)
            owner.close(); runCurrent()
        }
    }

    @Test fun pausedIntentAndRejectedActualOwnerPermitNeverBecomePassiveStartOrFalseOnlineSuccess() = runTest {
        val (owner, wire, output) = fixture(); outage(owner, wire, output)
        output.snapshot = output.snapshot.paused()
        wire.current = view(3, false); output.rejectRenewal = true
        advanceTimeBy(1001); runCurrent()
        assertFalse(owner.state.value.connected); assertNull(owner.currentAuthority()); assertFalse(output.snapshot.playing)
        output.rejectRenewal = false
        advanceTimeBy(1001); runCurrent()
        assertTrue(owner.state.value.connected); assertFalse(output.snapshot.playing)
        assertEquals(1, wire.grants); assertEquals(1, output.starts); assertEquals(1, output.renewals)
        owner.close()
    }

    @Test fun pauseResumeABADuringLeadershipReadRequiresFreshObservation() = runTest {
        val (owner, wire, output) = fixture(); outage(owner, wire, output)
        wire.current = view(3, false); wire.heldRead = CompletableDeferred()
        advanceTimeBy(1001); runCurrent()
        output.cachedPause(); output.cachedResume() // Same playing Boolean, different action lifetime.
        wire.heldRead!!.complete(Unit); runCurrent()
        assertEquals(listOf("GET"), wire.trace)
        assertEquals(0, wire.grants); assertEquals(0, output.renewals); assertNull(owner.currentAuthority())
        assertTrue(requireNotNull(output.permission).isValid())
        wire.heldRead = null
        advanceTimeBy(1001); runCurrent()
        assertEquals(listOf("GET", "GET", "continue"), wire.trace)
        assertTrue(owner.state.value.connected); assertEquals(1, output.renewals); assertEquals(1, output.starts)
        owner.close()
    }

    @Test fun pauseDuringGrantResponseRequiresFreshPausedObservationAndRenewal() = runTest {
        val (owner, wire, output) = fixture(); outage(owner, wire, output)
        wire.current = view(3, false); wire.heldGrant = CompletableDeferred()
        advanceTimeBy(1001); runCurrent()
        assertEquals(1, wire.grants)
        output.cachedPause(); wire.heldGrant!!.complete(Unit); runCurrent()
        assertFalse(owner.state.value.connected); assertNull(owner.currentAuthority()); assertEquals(0, output.renewals)
        assertTrue(requireNotNull(output.permission).isValid())
        advanceTimeBy(1001); runCurrent()
        assertEquals(listOf("GET", "continue", "GET", "poll:renewal"), wire.trace)
        assertTrue(owner.state.value.connected); assertEquals(1, output.renewals)
        assertFalse(output.snapshot.playing); assertEquals(1, output.starts)
        owner.close()
    }

    @Test fun staleLifecycleAndLeadOrReceiverABAResponseCannotRearmService() = runTest {
        for (kind in listOf("close", "lead-ABA", "receiver-ABA")) {
            val (owner, wire, output) = fixture(); outage(owner, wire, output)
            wire.current = view(3, false)
            if (kind == "close") wire.heldGrant = CompletableDeferred()
            wire.afterGrant = { grant -> when (kind) {
                "lead-ABA" -> grant.copy(leadership = PlaybackLeadership(1, 3, "installation", phone))
                "receiver-ABA" -> grant.copy(registration = grant.registration!!.copy(devices = listOf(own.copy(registrationGeneration = "successor-generation"))))
                else -> grant
            } }
            advanceTimeBy(1001); runCurrent()
            if (kind == "close") { owner.close(); wire.heldGrant!!.complete(Unit); runCurrent() }
            assertEquals(0, output.renewals); assertNull(owner.currentAuthority())
            assertEquals(1, output.starts)
            owner.close(); runCurrent()
        }
    }
}
