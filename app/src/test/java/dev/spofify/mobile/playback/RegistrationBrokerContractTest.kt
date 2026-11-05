package dev.spofify.mobile.playback

import dev.spofify.mobile.core.ApiException
import dev.spofify.mobile.core.PlaybackAck
import dev.spofify.mobile.core.PlaybackDevicesApi
import dev.spofify.mobile.core.PlaybackDevicesView
import dev.spofify.mobile.core.PlaybackDirective
import dev.spofify.mobile.core.PlaybackSnapshot
import dev.spofify.mobile.core.QueueIntentState
import dev.spofify.mobile.core.SessionStore
import dev.spofify.mobile.core.SpofifyClient
import dev.spofify.mobile.core.StoredSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Differential contract for lifecycle fixtures against actual Go b6eb54ff, not a room model.
 * SPOFIFY_TEST_GO_BROKER_BINARY is mandatory when selected; the helper verifies its hash.
 * Each scenario owns one server which survives every client/registration transition.
 *
 * Uses wall time deliberately: approximately 8s startup + 38s expiry across the scenarios,
 * normally < 60s in total. Poll intervals are sampling, never evidence that expiry happened.
 * Run with a preflighted, owned test-JVM java.io.tmpdir; the helper owns server scratch/cleanup.
 * Empty synthetic snapshots need no catalog, audio, provider or acquisition API.
 * These are broker facts / host simulations, NOT Android, audible-output or crash-window fixes.
 */
@org.junit.experimental.categories.Category(dev.spofify.mobile.testing.RealBrokerIntegration::class)
class RegistrationBrokerContractTest {
    private class Sessions(var value: StoredSession? = null) : SessionStore {
        override fun read() = value
        override fun write(value: StoredSession?) { this.value = value }
    }

    private class Peer(broker: PersistentGoBroker, saved: StoredSession? = null) : AutoCloseable {
        val sessions = Sessions(saved)
        val client = SpofifyClient(broker.server, sessions)
        val api = PlaybackDevicesApi(client)
        override fun close() {
            client.httpClient.dispatcher.cancelAll()
            client.httpClient.connectionPool.evictAll()
            client.httpClient.dispatcher.executorService.shutdown()
            check(client.httpClient.dispatcher.executorService.awaitTermination(2, TimeUnit.SECONDS)) {
                "Owned contract client did not stop"
            }
        }
    }

    @Test(timeout = 7_000)
    fun freshRegistrationSameCookieAndNewLoginHaveExactSessionBoundDisposal() = runBlocking {
        PersistentGoBroker().use { broker ->
            Peer(broker).use { original ->
                broker.login(original.client)
                val persisted = requireNotNull(original.sessions.value)
                val p = original.api.register("Same phone").deviceId
                Peer(broker, persisted).use { restored ->
                    assertNotNull(restored.client.restore())
                    // DIAGNOSTIC: discard/rebuild the client, not the room or session. A fresh
                    // registration still allocates a second ID; cookie/name are not idempotency.
                    val successor = restored.api.register("Same phone").deviceId
                    assertNotEquals(p, successor)
                    Peer(broker).use { newLogin ->
                        broker.login(newLogin.client)
                        val nextSession = requireNotNull(newLogin.sessions.value)
                        assertEquals(persisted.session.accountId, nextSession.session.accountId)
                        assertEquals(persisted.session.username, nextSession.session.username)
                        assertEquals(persisted.session.role, nextSession.session.role)
                        // Do not print either synthetic cookie even on a failing assertion.
                        assertFalse("A new login must have a different cookie", persisted.cookie == nextSession.cookie)
                        val q = newLogin.api.register("Same phone").deviceId
                        assertEquals(setOf(p, successor, q), ids(newLogin.api.view()))
                        assertEquals(3, newLogin.api.view().devices.count { it.name == "Same phone" })

                        val epoch = newLogin.api.view().epoch
                        failure(404) { newLogin.api.poll(p, epoch) }
                        failure(404) { newLogin.api.leave(p) }
                        failure(404) { newLogin.api.leave(successor) }
                        failure(404) { restored.api.leave(q) }
                        assertEquals(setOf(p, successor, q), ids(newLogin.api.view()))

                        // Same persisted session may dispose its KNOWN exact old registration;
                        // this must not delete its successor or a genuine same-name endpoint Q.
                        restored.api.poll(p, epoch)
                        restored.api.leave(p)
                        assertEquals(setOf(successor, q), ids(newLogin.api.view()))
                        failure(404) { restored.api.leave(p) }
                        assertEquals(setOf(successor, q), ids(newLogin.api.view()))

                        // Real logout revokes that session, unlike merely constructing a client.
                        // GET through Q request-drives session verification and removes only P's
                        // remaining row. Equal account values never lend Q the old authority.
                        original.client.logout()
                        assertEquals(setOf(q), ids(newLogin.api.view()))
                        failure(404) { newLogin.api.poll(successor, newLogin.api.view().epoch) }
                        failure(404) { newLogin.api.leave(successor) }
                        Peer(broker, persisted).use { revoked -> assertNull(revoked.client.restore()) }
                        newLogin.api.leave(q)
                        assertTrue(newLogin.api.view().devices.isEmpty())
                    }
                }
            }
        }
    }

    @Test(timeout = 15_000)
    fun startupNeverElectsAndSelectionNeedsOnceOnlyDirectivesAndActualTerminalState() = runBlocking {
        PersistentGoBroker().use { broker ->
            Peer(broker).use { a ->
                broker.login(a.client)
                assertNotNull(a.api.probeQueueIntents().queueIntents)
                val initial = a.api.register("Same phone", 1)
                val aId = initial.deviceId
                assertUnselected(initial)
                assertTrue("Must actually observe process-start quarantine", initial.quarantineMs in 1..8_000)
                Peer(broker, requireNotNull(a.sessions.value)).use { b ->
                    assertNotNull(b.client.restore())
                    assertNotNull(b.api.probeQueueIntents().queueIntents)
                    val bId = b.api.register("Same phone", 1).deviceId
                    assertNotEquals(aId, bId)
                    val drained = until("startup quarantine drains without passive election", afterSeconds(10), {
                        val av = a.api.poll(aId, initial.epoch)
                        assertUnselected(av)
                        val bv = b.api.poll(bId, initial.epoch)
                        assertUnselected(bv)
                        bv
                    }) { it.quarantineMs == 0L }
                    assertEquals(setOf(aId, bId), ids(drained))
                    assertNull(drained.state)
                    // Registering AFTER the startup deadline does not make this room pristine.
                    val late = b.api.register("Same phone", 1)
                    assertUnselected(late)
                    assertEquals(0L, late.quarantineMs)
                    b.api.leave(late.deviceId)

                    val admitted = a.api.transfer(aId, drained.epoch, "select_a", aId, 1)
                    assertUnselectedExceptTransfer(admitted)
                    assertEquals("preparing", admitted.transfer?.phase)
                    assertOutcome(admitted, "select_a", "pending")
                    val prepare = only(a.api.poll(aId, admitted.epoch), "prepare", "select_a")
                    assertTrue(a.api.view().directives.isEmpty())
                    assertTrue("Prepare is delivered once, not retried", a.api.poll(aId, prepare.epoch).directives.isEmpty())
                    val activation = a.api.poll(aId, prepare.epoch, acks = listOf(ack(prepare)))
                    val activate = only(activation, "activate", "select_a")
                    assertEquals(aId, activation.activeDeviceId)
                    assertEquals(prepare.epoch + 1, activation.epoch)
                    assertOutcome(activation, "select_a", "pending")
                    assertNull(activation.queueIntents?.binding)
                    assertTrue("Activate is also delivered once", a.api.poll(aId, activate.epoch).directives.isEmpty())

                    // Delivery / active-ID display is NOT a terminal execution acknowledgement.
                    val noState = failure(409) {
                        a.api.poll(aId, activate.epoch, acks = listOf(ack(activate)))
                    }
                    assertOutcome(safeView(noState), "select_a", "pending")
                    val paused = PlaybackSnapshot()
                    val metadataA = metadata("service_a", "legacy-reset")
                    failure(409) {
                        a.api.poll(aId, activate.epoch, 1, paused,
                            listOf(ack(activate, "blocked")), metadataA)
                    }
                    val owned = a.api.poll(aId, activate.epoch, 1, paused, listOf(ack(activate)), metadataA)
                    assertOutcome(owned, "select_a", "ok")
                    assertNull(owned.transfer)
                    assertEquals("service_a", owned.queueIntents?.binding?.receiverId)
                    val repeated = a.api.poll(aId, owned.epoch, 2, paused, listOf(ack(activate)), metadataA)
                    assertEquals(owned.epoch, repeated.epoch)
                    assertOutcome(repeated, "select_a", "ok")
                    assertTrue(repeated.directives.isEmpty())
                    failure(409) {
                        a.api.poll(aId, owned.epoch, 3, paused, emptyList(), metadata("rotated_a", "legacy-reset"))
                    }
                    assertEquals(2L, a.api.view().stateSeq)

                    // DIAGNOSTIC: same actual receiver reregisters as B while orphan A retains
                    // published metadata. The broker permits selecting B, but will not accept
                    // A's retained receiver incarnation under B. Do NOT rotate IDs to hide this
                    // unresolved lifecycle window, or call pending selection successful playback.
                    val selectingB = b.api.transfer(bId, owned.epoch, "select_b", bId, 1)
                    assertOutcome(selectingB, "select_b", "pending")
                    assertEquals(aId, selectingB.transfer?.sourceDeviceId)
                    assertEquals(bId, selectingB.transfer?.targetDeviceId)
                    val preparingB = b.api.poll(bId, selectingB.epoch)
                    val prepareB = only(preparingB, "prepare", "select_b")
                    assertEquals(metadataA, preparingB.queueIntents?.directives?.single()?.state)
                    b.api.poll(bId, prepareB.epoch, acks = listOf(ack(prepareB)))
                    val release = only(a.api.poll(aId, prepareB.epoch), "release", "select_b")
                    failure(409) { a.api.poll(aId, release.epoch, acks = listOf(ack(release))) }
                    val released = a.api.poll(aId, release.epoch, 3, paused, listOf(ack(release)), metadataA)
                    assertEquals(bId, released.activeDeviceId)
                    assertEquals(0L, released.quarantineMs) // exact successful stop ACK skips drain
                    val activatingB = b.api.poll(bId, released.epoch)
                    val activateB = only(activatingB, "activate", "select_b")
                    assertEquals(metadataA, activatingB.queueIntents?.directives?.single()?.state)
                    val collision = failure(409) {
                        b.api.poll(bId, activateB.epoch, 1, paused, listOf(ack(activateB)), metadataA)
                    }
                    val rejectedPublication = safeView(collision)
                    assertEquals(setOf(aId, bId), ids(rejectedPublication))
                    assertEquals(bId, rejectedPublication.activeDeviceId)
                    assertEquals("activating", rejectedPublication.transfer?.phase)
                    assertOutcome(rejectedPublication, "select_b", "pending")
                    assertEquals(0L, rejectedPublication.stateSeq)
                    assertEquals(metadataA, rejectedPublication.queueIntents?.state)
                    assertNull(rejectedPublication.queueIntents?.binding)
                    assertTrue(b.api.poll(bId, activateB.epoch).directives.isEmpty())
                    // Exact disposal ends this diagnostic; no invented successful replacement.
                    b.api.leave(bId)
                    val disposed = a.api.view()
                    assertEquals(setOf(aId), ids(disposed))
                    assertEquals("", disposed.activeDeviceId)
                    assertEquals(0L, disposed.quarantineMs)
                    assertOutcome(disposed, "select_b", "cancelled")
                    a.api.leave(aId)
                }
            }
        }
    }

    @Test(timeout = 43_000)
    fun abruptLossDiagnosticUsesRealAvailabilityLeaseDrainAndRequestDrivenRegistrationExpiry() = runBlocking {
        PersistentGoBroker().use { broker ->
            Peer(broker).use { peer ->
                broker.login(peer.client)
                val api = peer.api
                val a = api.register("Same phone")
                val aId = a.deviceId
                val bId = api.register("Same phone").deviceId
                assertUnselected(a)
                assertTrue(a.quarantineMs > 0)
                until("actual startup drain", afterSeconds(10), {
                    val av = api.poll(aId, a.epoch)
                    assertUnselected(av)
                    api.poll(bId, a.epoch).also(::assertUnselected)
                }) { it.quarantineMs == 0L }
                val selection = api.transfer(aId, a.epoch, "own_before_loss", aId, 1)
                val prepare = only(api.poll(aId, selection.epoch), "prepare", "own_before_loss")
                val activated = api.poll(aId, prepare.epoch, acks = listOf(ack(prepare)))
                val activate = only(activated, "activate", "own_before_loss")
                val heartbeatStart = System.nanoTime()
                val lastHeartbeat = api.poll(aId, activate.epoch, 1, PlaybackSnapshot(), listOf(ack(activate)))
                val heartbeatEnd = System.nanoTime()
                assertOutcome(lastHeartbeat, "own_before_loss", "ok")
                assertEquals(aId, lastHeartbeat.activeDeviceId)
                assertTrue(lastHeartbeat.leaseMs in 1..8_000)

                // DIAGNOSTIC: A now disappears without leave. No later successful A heartbeat,
                // no server reset and no fake clock. Every observation below hits actual Go.
                var firstUnavailableAt = 0L
                until("A becomes unavailable at the five-second boundary", heartbeatEnd + seconds(7), {
                    api.poll(bId, lastHeartbeat.epoch)
                    val observationStart = System.nanoTime()
                    api.view().also { view ->
                        val observationEnd = System.nanoTime()
                        val available = view.devices.single { it.deviceId == aId }.available
                        assertEquals(setOf(aId, bId), ids(view))
                        // Acceptance is bracketed by the last poll's send/receive times. Outside
                        // that bracket, test the exact 5s rule, not just a generous timeout.
                        if (observationEnd < heartbeatStart + seconds(5)) assertTrue(available)
                        if (observationStart > heartbeatEnd + seconds(5)) assertFalse(available)
                        if (!available) firstUnavailableAt = observationEnd
                    }
                }) { !it.devices.single { row -> row.deviceId == aId }.available }
                assertTrue("A must not become unavailable before five seconds", firstUnavailableAt >= heartbeatStart + seconds(5))
                assertTrue("Five-second availability plus bounded polling slack", firstUnavailableAt <= heartbeatEnd + millis(6_500))
                val unavailableOwner = api.view()
                assertEquals("Unavailable remote is still selected until fenced", aId, unavailableOwner.activeDeviceId)
                assertTrue(unavailableOwner.leaseMs > 0)

                // Explicit B selection fences unavailable A, retaining its last eight-second
                // lease. Acceptance is waiting, not ownership, and B remains a separate row.
                val waiting = api.transfer(bId, unavailableOwner.epoch, "after_loss", bId, 1)
                assertEquals("", waiting.activeDeviceId)
                assertEquals(0L, waiting.leaseMs)
                assertTrue(waiting.quarantineMs in 1..8_000)
                assertEquals("waiting", waiting.transfer?.phase)
                assertOutcome(waiting, "after_loss", "pending")
                failure(409) { api.poll(aId, lastHeartbeat.epoch, 2, PlaybackSnapshot()) }
                val drain = until("GET observes eight-second lease drain but cannot advance selection", heartbeatEnd + seconds(10), {
                    val observationStart = System.nanoTime()
                    api.view().also { view ->
                        val observationEnd = System.nanoTime()
                        assertEquals("", view.activeDeviceId)
                        assertEquals("waiting", view.transfer?.phase)
                        assertTrue(view.directives.isEmpty())
                        assertOutcome(view, "after_loss", "pending")
                        if (observationEnd < heartbeatStart + seconds(8)) assertTrue(view.quarantineMs > 0)
                        if (observationStart >= heartbeatEnd + seconds(8)) assertEquals(0L, view.quarantineMs)
                    }
                }) { it.quarantineMs == 0L }
                val drainedAt = System.nanoTime()
                assertTrue("Lease cannot drain before its actual eight seconds", drainedAt >= heartbeatStart + seconds(8))
                assertTrue("Eight-second lease plus bounded polling slack", drainedAt <= heartbeatEnd + millis(9_500))
                assertEquals(setOf(aId, bId), ids(drain))
                val recoveryPrepare = only(api.poll(bId, drain.epoch), "prepare", "after_loss")
                val recovery = api.poll(bId, recoveryPrepare.epoch, acks = listOf(ack(recoveryPrepare)))
                val recoveryActivate = only(recovery, "activate", "after_loss")
                assertFalse(requireNotNull(recoveryActivate.state).playing)
                val terminal = api.poll(bId, recoveryActivate.epoch, 1, PlaybackSnapshot(), listOf(ack(recoveryActivate)))
                assertOutcome(terminal, "after_loss", "ok")
                assertEquals(bId, terminal.activeDeviceId)
                assertFalse(requireNotNull(terminal.state).playing)

                // A is an honest retained orphan until >=30s after its last heartbeat, not
                // cosmetically deduplicated by name. Keep only B alive; GET/poll request-drive
                // actual pruning. A fixed local sleep would not establish server acceptance time.
                var lastRetainedAt = System.nanoTime()
                val expired = until("request-driven pruning removes only orphan A after thirty seconds", heartbeatEnd + seconds(33), {
                    val observationStart = System.nanoTime()
                    val observed = api.poll(bId, terminal.epoch)
                    val observedAt = System.nanoTime()
                    if (observationStart >= heartbeatEnd + seconds(30)) {
                        assertFalse("A request after the latest possible TTL must prune A", aId in ids(observed))
                    }
                    assertEquals(bId, observed.activeDeviceId)
                    assertNull(observed.transfer)
                    assertFalse(requireNotNull(observed.state).playing)
                    if (aId in ids(observed)) {
                        lastRetainedAt = observedAt
                        assertFalse(observed.devices.single { it.deviceId == aId }.available)
                        assertEquals(setOf(aId, bId), ids(observed))
                    } else {
                        assertTrue("Registration must survive at least thirty seconds", observedAt >= heartbeatStart + seconds(30))
                    }
                    observed
                }) { aId !in ids(it) }
                assertTrue("Observe retention close to expiry, not merely one late missing row", lastRetainedAt >= heartbeatStart + seconds(29))
                assertEquals(setOf(bId), ids(expired))
                assertTrue(expired.devices.single().available)
                failure(404) { api.poll(aId, expired.epoch) }
                failure(404) { api.leave(aId) }
                assertEquals(setOf(bId), ids(api.view()))
                api.leave(bId)
                assertTrue(api.view().devices.isEmpty())
            }
        }
    }

    private fun metadata(receiver: String, provenance: String) =
        QueueIntentState(receiver, 1, provenance, emptyList(), emptyList())

    private fun ack(directive: PlaybackDirective, status: String = "ok") =
        PlaybackAck(directive.deliveryId, directive.epoch, status)

    private fun only(view: PlaybackDevicesView, type: String, command: String): PlaybackDirective {
        assertEquals("Expected exact one delivery: ${facts(view)}", 1, view.directives.size)
        return view.directives.single().also {
            assertEquals(type, it.type)
            assertEquals(command, it.commandId)
            assertEquals(view.epoch, it.epoch)
        }
    }

    private fun ids(view: PlaybackDevicesView) = view.devices.map { it.deviceId }.toSet()

    private fun assertOutcome(view: PlaybackDevicesView, command: String, status: String) {
        assertEquals("Command $command: ${facts(view)}", status, view.commands.single { it.commandId == command }.status)
    }

    private fun assertUnselected(view: PlaybackDevicesView) {
        assertUnselectedExceptTransfer(view)
        assertNull(view.transfer)
    }

    private fun assertUnselectedExceptTransfer(view: PlaybackDevicesView) {
        assertEquals("", view.activeDeviceId)
        assertEquals(0L, view.leaseMs)
        assertTrue(view.directives.isEmpty())
    }

    private suspend fun failure(status: Int, request: suspend () -> Unit): ApiException {
        try { request() } catch (error: ApiException) {
            assertEquals(status, error.status)
            return error
        }
        throw AssertionError("Expected HTTP $status from real broker")
    }

    private fun safeView(error: ApiException): PlaybackDevicesView =
        PlaybackDevicesView.fromJson(requireNotNull(error.responseBody) { "Missing safe broker conflict view" }).also {
            assertTrue(it.directives.isEmpty())
        }

    private fun seconds(value: Long) = TimeUnit.SECONDS.toNanos(value)
    private fun millis(value: Long) = TimeUnit.MILLISECONDS.toNanos(value)
    private fun afterSeconds(value: Long) = System.nanoTime() + seconds(value)

    private suspend fun until(
        label: String,
        deadline: Long,
        observe: suspend () -> PlaybackDevicesView,
        satisfied: (PlaybackDevicesView) -> Boolean,
    ): PlaybackDevicesView {
        var last: PlaybackDevicesView? = null
        while (System.nanoTime() < deadline) {
            val view = observe()
            last = view
            if (satisfied(view)) return view
            delay(100)
        }
        throw AssertionError("$label: deadline exceeded; ${last?.let(::facts) ?: "no observation"}")
    }

    // Only opaque synthetic IDs/statuses, never cookies, session data, paths or musical metadata.
    private fun facts(view: PlaybackDevicesView) =
        "epoch=${view.epoch}, active=${view.activeDeviceId}, lease=${view.leaseMs}, drain=${view.quarantineMs}, " +
            "rows=${view.devices.map { it.deviceId to it.available }}, transfer=${view.transfer?.phase}, " +
            "outcomes=${view.commands.map { it.commandId to it.status }}"
}
