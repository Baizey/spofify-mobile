package dev.spofify.mobile.playback

import dev.spofify.mobile.core.*
import dev.spofify.mobile.feature.devices.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/** Deterministic lifecycle interleavings, supplementary to mandatory RegistrationBrokerContractTest.
 * Only passive registration/poll/leave is modeled: no invented transfer/ACK/authority behavior.
 * Timing/retention is the pinned broker contract, not a claim about Android process death.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackRegistrationAdversarialTest {
    private class SilentEndpoint : NativePlaybackEndpoint {
        override val supportsExactHandoff = true
        var starts = 0
        var stops = 0
        override fun suspendOutput() { stops++ }
        override fun exportSnapshot() = PlaybackSnapshot()
        override suspend fun prepare(snapshot: PlaybackSnapshot): Boolean { error("Passive lifetime prepared output") }
        override suspend fun activate(snapshot: PlaybackSnapshot, authority: PlaybackAuthority): PlaybackEndpointResult {
            starts++; error("Passive lifetime activated output")
        }
        override suspend fun execute(command: PlaybackDeviceCommand, authority: PlaybackAuthority): PlaybackEndpointResult {
            starts++; error("Passive lifetime executed command")
        }
    }
    private class Room(private val now: () -> Long) : PlaybackDevicesProtocol {
        data class Row(val id: String, val name: String, var seen: Long)
        private val started = now()
        val rows = linkedMapOf<String, Row>()
        val left = mutableListOf<String>()
        var registrations = 0
        var failRegister = false
        var gatePoll: CompletableDeferred<Unit>? = null
        var pollEntered = false
        var gateLeave: CompletableDeferred<Unit>? = null
        private fun prune() { rows.entries.removeAll { now() - it.value.seen >= 30_000 } }
        private fun response(id: String = ""): PlaybackDevicesView {
            prune()
            return PlaybackDevicesView(id, rows.values.map { PlaybackDevice(it.id, it.name, now() - it.seen <= 5000) },
                "", 1, 0, (8000-(now()-started)).coerceAtLeast(0), null, 0, 0, null, emptyList(), emptyList())
        }
        override suspend fun view() = response()
        override suspend fun register(name: String): PlaybackDevicesView {
            val id = "device-${++registrations}"
            rows[id] = Row(id, name, now())
            if (failRegister) throw java.io.IOException("Accepted response lost")
            return response(id)
        }
        override suspend fun poll(deviceId: String, epoch: Long, stateSeq: Long?, state: PlaybackSnapshot?, acks: List<PlaybackAck>): PlaybackDevicesView {
            gatePoll?.let { pollEntered = true; withContext(NonCancellable) { it.await() } }
            prune()
            val row = rows[deviceId] ?: throw ApiException(404, "Missing exact issuer")
            row.seen = now()
            assertNull(state); assertTrue(acks.isEmpty())
            return response(deviceId)
        }
        override suspend fun rename(deviceId: String, name: String) = response(deviceId)
        override suspend fun transfer(deviceId: String, epoch: Long, commandId: String, targetDeviceId: String, selectionSeq: Long): PlaybackDevicesView = error("Passive selection")
        override suspend fun command(deviceId: String, epoch: Long, commandId: String, command: PlaybackDeviceCommand): PlaybackDevicesView = error("Passive command")
        override suspend fun leave(deviceId: String) { gateLeave?.await(); left += deviceId; rows.remove(deviceId) }
    }

    @Test fun cleanupBudgetIncludesMutexWaitAndCannotLeaveSuccessor() = runTest {
        val room = Room { testScheduler.currentTime }
        val endpoint = SilentEndpoint()
        val parent = SupervisorJob()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)
        val owner = PlaybackCoordinator(room, endpoint, scope, clockMs = { testScheduler.currentTime })
        owner.setServiceActive(true); runCurrent()
        val old = owner.state.value.deviceId
        room.gatePoll = CompletableDeferred()
        advanceTimeBy(1000); runCurrent(); assertTrue(room.pollEntered)
        val cleanup = requireNotNull(owner.close())
        parent.cancel(); runCurrent()
        assertNull(owner.currentAuthority())
        assertFalse(cleanup.isCompleted)
        advanceTimeBy(3999); runCurrent(); assertFalse(cleanup.isCompleted)
        advanceTimeBy(1); runCurrent(); assertTrue("Lock wait is INSIDE disposal deadline", cleanup.isCompleted)
        assertTrue(room.left.isEmpty())
        val successor = PlaybackCoordinator(room, SilentEndpoint(), backgroundScope,
            clockMs = { testScheduler.currentTime }, predecessorDisposal = cleanup)
        successor.setServiceActive(true); runCurrent()
        val next = successor.state.value.deviceId
        assertNotEquals(old, next)
        room.gatePoll!!.complete(Unit); runCurrent()
        assertTrue("Timed out old disposal cannot later delete anything", room.left.isEmpty())
        assertTrue(room.rows.containsKey(next))
        successor.close(); runCurrent()
        assertEquals(listOf(next), room.left)
    }

    @Test fun delayedLeaveOrdersSuccessorAndNeverBorrowsItsId() = runTest {
        val room = Room { testScheduler.currentTime }
        val owner = PlaybackCoordinator(room, SilentEndpoint(), backgroundScope, clockMs = { testScheduler.currentTime })
        owner.setServiceActive(true); runCurrent()
        val old = owner.state.value.deviceId
        room.gateLeave = CompletableDeferred()
        owner.setServiceActive(false); owner.setServiceActive(true); runCurrent()
        assertEquals(1, room.registrations)
        assertNull(owner.currentAuthority())
        room.gateLeave!!.complete(Unit); runCurrent()
        assertEquals(listOf(old), room.left)
        assertEquals(2, room.registrations)
        assertNotEquals(old, owner.state.value.deviceId)
        assertEquals(setOf(owner.state.value.deviceId), room.rows.keys)
        owner.close(); runCurrent()
    }

    @Test fun twentySeedsOfOneHundredLifecycleEventsKeepOneKnownRegistrationAndNeverStart() = runTest {
        // Every failure prints a replayable prefix. Removing suffix operations is a trivial
        // shrink; operation values are fully retained, not just an opaque random seed.
        repeat(20) { seed ->
            val random = Random(seed)
            val room = Room { testScheduler.currentTime }
            val endpoint = SilentEndpoint()
            val pacing = PlaybackRegistrationPacing()
            var owner = PlaybackCoordinator(room, endpoint, backgroundScope, clockMs = { testScheduler.currentTime }, registrationPacing = pacing)
            // Establish the room during real-contract startup quarantine. It is no longer
            // pristine even after every row leaves; later registration cannot auto-elect.
            owner.setServiceActive(true); runCurrent()
            var active = true
            val trace = mutableListOf<Int>()
            try {
                repeat(100) {
                    val op = random.nextInt(6); trace += op
                    when (op) {
                        0 -> { owner.setServiceActive(true); active = true }
                        1 -> { owner.setServiceActive(false); active = false }
                        2 -> owner.retry()
                        3 -> { val elapsed = random.nextLong(0, 1000); trace += -(elapsed.toInt()+1); advanceTimeBy(elapsed) }
                        4 -> owner.setServiceActive(active) // repeated UI/service notification is a no-op
                        5 -> {
                            val previous = owner.close()
                            owner = PlaybackCoordinator(room, endpoint, backgroundScope, clockMs = { testScheduler.currentTime },
                                predecessorDisposal = previous, registrationPacing = pacing)
                            if (active) owner.setServiceActive(true)
                        }
                    }
                    runCurrent()
                    assertEquals(0, endpoint.starts)
                    assertNull(owner.currentAuthority())
                    val known = owner.state.value.deviceId
                    assertTrue("Only exact live row remains", room.rows.size <= 1)
                    if (known.isNotEmpty()) assertEquals(setOf(known), room.rows.keys)
                    assertEquals(room.left.size, room.left.toSet().size)
                }
            } catch (error: Throwable) {
                throw AssertionError("seed=$seed operations=$trace (failing replay prefix)", error)
            } finally { owner.close(); runCurrent() }
            assertTrue(room.rows.isEmpty())
        }
    }

    @Test fun uncertainWriteSpacingSurvivesCoordinatorReplacementAndStaleConfirmation() = runTest {
        val pacing = PlaybackRegistrationPacing()
        val room = Room { testScheduler.currentTime }.apply { failRegister = true }
        val first = PlaybackCoordinator(room, SilentEndpoint(), backgroundScope, clockMs = { testScheduler.currentTime }, registrationPacing = pacing)
        first.setServiceActive(true); runCurrent(); assertEquals(1, room.registrations)
        first.close()
        val next = PlaybackCoordinator(room, SilentEndpoint(), backgroundScope, clockMs = { testScheduler.currentTime }, registrationPacing = pacing)
        next.setServiceActive(true)
        repeat(100) { next.retry() }; runCurrent()
        advanceTimeBy(29_999); runCurrent(); assertEquals(1, room.registrations)
        advanceTimeBy(1); runCurrent(); assertEquals(2, room.registrations)
        assertTrue(room.registrations < 16)
        assertNull(next.currentAuthority())
        next.close(); runCurrent()
    }
}
