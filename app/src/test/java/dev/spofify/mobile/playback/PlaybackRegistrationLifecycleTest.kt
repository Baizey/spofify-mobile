package dev.spofify.mobile.playback

import androidx.test.core.app.ApplicationProvider
import dev.spofify.mobile.*
import dev.spofify.mobile.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

/** Persistent *real* Go room and same persisted cookie across production graph lifetimes.
 * These are host lifecycle simulations, not installed Android process/audio acceptance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SpofifyApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(ExperimentalCoroutinesApi::class)
@org.junit.experimental.categories.Category(dev.spofify.mobile.testing.RealBrokerIntegration::class)
class PlaybackRegistrationLifecycleTest {
    private class Sessions : SessionStore {
        var value: StoredSession? = null
        override fun read() = value
        override fun write(value: StoredSession?) { this.value = value }
    }
    private class Logins : LoginStore {
        override fun read(): RememberedLogin? = null
        override fun write(value: RememberedLogin?) = Unit
    }
    private fun install(app: SpofifyApplication, graph: AppGraph) {
        SpofifyApplication::class.java.getDeclaredField("graph").apply { isAccessible = true; set(app, graph) }
    }
    // Observer HTTP uses wall-clock deadlines; the coordinator dispatcher remains explicit.
    private fun <T> wire(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
    private fun TestScope.await(label: String, predicate: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!predicate() && System.nanoTime() < end) { runCurrent(); Thread.sleep(10) }
        assertTrue(label, predicate())
    }

    @Test fun gracefulGraphReplacementLeavesExactOldIdBeforeSameCookieSuccessorRegisters() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val app = ApplicationProvider.getApplicationContext<SpofifyApplication>()
        app.graph.clear()
        val sessions = Sessions()
        val graph = AppGraph(app, sessions, Logins())
        install(app, graph)
        var service: ServiceController<PlaybackService>? = null
        try { PersistentGoBroker().use { broker ->
            try {
                val first = graph.configure(broker.server)
                broker.login(first)
                val persisted = requireNotNull(sessions.value)
                // Observer uses the SAME cookie but is a separate HTTP client, not a registration.
                val observer = SpofifyClient(broker.server, Sessions().apply { value = persisted })
                observer.restore()
                val api = PlaybackDevicesApi(observer)
                service = Robolectric.buildService(PlaybackService::class.java).create()
                val old = requireNotNull(graph.playbackCoordinator)
                await("Production service must register against actual broker") { old.state.value.deviceId.isNotEmpty() }
                val oldId = old.state.value.deviceId
                assertEquals(setOf(oldId), wire { api.view() }.devices.map { it.deviceId }.toSet())
                assertEquals(PlaybackSnapshot(), PlaybackService.exportLocalSnapshot())

                // Retire coordinator/client while broker + account + login session stay alive.
                val next = graph.configure(broker.server)
                service!!.destroy(); service = null
                assertNotNull(wire { graph.restore() })
                assertNotSame(first, next)
                service = Robolectric.buildService(PlaybackService::class.java).create()
                val successor = requireNotNull(graph.playbackCoordinator)
                await("Successor must finish bounded registration") { successor.state.value.deviceId.isNotEmpty() }
                val nextId = successor.state.value.deviceId
                assertNotEquals(oldId, nextId)
                runCurrent()
                val rows = wire { api.view() }.devices.map { it.deviceId }.toSet()
                assertEquals("Graceful graph replacement must dispose only exact old ID, not leave a duplicate", setOf(nextId), rows)
                assertEquals(PlaybackSnapshot(), PlaybackService.exportLocalSnapshot())
                assertNull(successor.currentAuthority())
            } finally {
                graph.clear(); service?.destroy(); runCurrent()
            }
        } } finally { Dispatchers.resetMain() }
    }

    @Test fun missingRemoteRenameTargetMustNotForgetThisLiveServiceRegistration() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val app = ApplicationProvider.getApplicationContext<SpofifyApplication>()
        app.graph.clear()
        val graph = AppGraph(app, Sessions(), Logins())
        install(app, graph)
        var service: ServiceController<PlaybackService>? = null
        try {
            PersistentGoBroker().use { broker ->
                try {
                    val client = graph.configure(broker.server)
                    broker.login(client)
                    val api = PlaybackDevicesApi(client)
                    service = Robolectric.buildService(PlaybackService::class.java).create()
                    val owner = requireNotNull(graph.playbackCoordinator)
                    await("Live service registers") { owner.state.value.deviceId.isNotEmpty() }
                    val self = owner.state.value.deviceId
                    val receiver = Media3PlaybackEndpoint().exportQueueIntentState()?.receiverId
                    val remote = wire { api.register("Same phone") }
                    wire { api.leave(remote.deviceId) }
                    owner.rename(remote.deviceId, "Renamed missing endpoint")
                    await("Missing remote rename must return a failure") { !owner.state.value.connected }
                    assertEquals("A real 404 for someone else's missing row is NOT loss of our registration", self, owner.state.value.deviceId)
                    assertEquals(receiver, Media3PlaybackEndpoint().exportQueueIntentState()?.receiverId)
                    owner.retry()
                    await("Self registration recovers by polling, not registering") { owner.state.value.connected }
                    assertEquals(setOf(self), wire { api.view() }.devices.map { it.deviceId }.toSet())
                    assertEquals(PlaybackSnapshot(), PlaybackService.exportLocalSnapshot())
                } finally { graph.clear(); service?.destroy(); runCurrent() }
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun acceptedButLostRegistrationIsPacedAcrossGraphReconfigurationButNewProcessResidualRemains() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val app = ApplicationProvider.getApplicationContext<SpofifyApplication>()
        app.graph.clear()
        val sessions = Sessions()
        var graph = AppGraph(app, sessions, Logins())
        install(app, graph)
        var service: ServiceController<PlaybackService>? = null
        try {
            PersistentGoBroker().use { broker -> RegistrationFaultProxy(broker).use { proxy ->
                try {
                    val first = graph.configure(proxy.server)
                    broker.login(first)
                    val persisted = requireNotNull(sessions.value)
                    val dropped = RegistrationFaultProxy.Fault("register", drop = true)
                    proxy.next.set(dropped)
                    service = Robolectric.buildService(PlaybackService::class.java).create()
                    val old = requireNotNull(graph.playbackCoordinator)
                    await("Actual broker accepted register whose reply is discarded") {
                        dropped.reached.count == 0L && proxy.acceptedRegistrations.size == 1 &&
                            old.state.value.recovery == dev.spofify.mobile.feature.devices.PlaybackConnectionRecovery.VERIFICATION_NEEDED
                    }
                    assertTrue(old.state.value.deviceId.isEmpty())
                    val orphan = proxy.acceptedRegistrations.single()
                    graph.configure(proxy.server)
                    service!!.destroy(); service = null
                    assertNotNull(wire { graph.restore() })
                    service = Robolectric.buildService(PlaybackService::class.java).create()
                    val next = requireNotNull(graph.playbackCoordinator)
                    repeat(100) { next.retry(); runCurrent() }
                    advanceTimeBy(8_000); runCurrent()
                    assertEquals("Graph/service/manual Retry must not reset uncertain-write pacing", listOf(orphan), proxy.acceptedRegistrations.toList())
                    assertTrue(next.state.value.deviceId.isEmpty())
                    assertNull(next.currentAuthority())
                    assertFalse(proxy.actions.any { it == "transfer" || it == "command" })

                    // Simulated process loss: a fresh AppGraph has no durable ambiguity record.
                    // Broker and exact persisted cookie survive. The protocol CANNOT recover
                    // the unknown old ID; this deliberately records, not hides, the residual.
                    graph.clear(); service!!.destroy(); service = null; runCurrent()
                    graph = AppGraph(app, Sessions().apply { value = persisted }, Logins())
                    install(app, graph)
                    assertNotNull(wire { graph.restore() })
                    service = Robolectric.buildService(PlaybackService::class.java).create()
                    val fresh = requireNotNull(graph.playbackCoordinator)
                    await("A genuinely new process-local lifetime registers without old authority") { fresh.state.value.deviceId.isNotEmpty() }
                    val observer = PlaybackDevicesApi(requireNotNull(graph.client))
                    assertEquals(setOf(orphan, fresh.state.value.deviceId), wire { observer.view() }.devices.map { it.deviceId }.toSet())
                    assertEquals(PlaybackSnapshot(), PlaybackService.exportLocalSnapshot())
                    assertNull(fresh.currentAuthority())
                } finally { graph.clear(); service?.destroy(); runCurrent() }
            } }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun acceptedHeldLeaveAndLostLeaveAckCannotRaceOrDeleteSameNameSuccessor() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val app = ApplicationProvider.getApplicationContext<SpofifyApplication>()
        app.graph.clear()
        try {
            for (drop in listOf(false, true)) {
                val graph = AppGraph(app, Sessions(), Logins())
                install(app, graph)
                var service: ServiceController<PlaybackService>? = null
                val held = RegistrationFaultProxy.Fault("leave", drop = drop)
                PersistentGoBroker().use { broker -> RegistrationFaultProxy(broker).use { proxy ->
                    try {
                        val client = graph.configure(proxy.server)
                        wire { broker.login(client) }
                        service = Robolectric.buildService(PlaybackService::class.java).create()
                        val old = requireNotNull(graph.playbackCoordinator)
                        await("Initial real registration") { old.state.value.connected }
                        val p = old.state.value.deviceId
                        val sameName = wire { PlaybackDevicesApi(client).view() }.devices.single().name
                        val q = wire { PlaybackDevicesApi(client).register(sameName) }.deviceId
                        proxy.next.set(held)
                        graph.configure(proxy.server)
                        service!!.destroy(); service = null
                        wire { graph.restore() }
                        service = Robolectric.buildService(PlaybackService::class.java).create()
                        val successor = requireNotNull(graph.playbackCoordinator)
                        await("Real broker accepted exact old leave") { held.reached.count == 0L }
                        if (!drop) {
                            repeat(100) { successor.retry(); runCurrent() }
                            assertTrue("Manual retry cannot overtake held cleanup", successor.state.value.deviceId.isEmpty())
                            assertEquals(2, proxy.acceptedRegistrations.size) // P and genuine same-name Q only
                            held.release.countDown()
                        }
                        await("Successor registers after bounded leave completion or lost ACK") { successor.state.value.deviceId.isNotEmpty() }
                        val next = successor.state.value.deviceId
                        assertNotEquals(p, next)
                        assertEquals(listOf(p), proxy.leaves.toList())
                        assertEquals(setOf(q, next), wire { PlaybackDevicesApi(requireNotNull(graph.client)).view() }.devices.map { it.deviceId }.toSet())
                        assertFalse(proxy.actions.any { it == "transfer" || it == "command" })
                        assertEquals(PlaybackSnapshot(), PlaybackService.exportLocalSnapshot())
                        repeat(10) { runCurrent() }
                        assertEquals(next, successor.state.value.deviceId)
                    } finally { held.release.countDown(); graph.clear(); service?.destroy(); runCurrent() }
                } }
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun serverCanAcceptOldRegisterAfterGraphCancellationWithoutGrantingSuccessorAuthority() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val app = ApplicationProvider.getApplicationContext<SpofifyApplication>()
        app.graph.clear()
        val graph = AppGraph(app, Sessions(), Logins())
        install(app, graph)
        var service: ServiceController<PlaybackService>? = null
        val delayed = RegistrationFaultProxy.Fault("register", beforeAcceptance = true)
        try {
            PersistentGoBroker().use { broker -> RegistrationFaultProxy(broker).use { proxy ->
                try {
                    val first = graph.configure(proxy.server)
                    wire { broker.login(first) }
                    proxy.next.set(delayed)
                    service = Robolectric.buildService(PlaybackService::class.java).create()
                    val old = requireNotNull(graph.playbackCoordinator)
                    await("Register dispatched but held before real broker acceptance") { delayed.reached.count == 0L }
                    assertTrue(proxy.acceptedRegistrations.isEmpty())
                    graph.configure(proxy.server)
                    service!!.destroy(); service = null
                    assertNotNull(wire { graph.restore() })
                    service = Robolectric.buildService(PlaybackService::class.java).create()
                    val next = requireNotNull(graph.playbackCoordinator)
                    runCurrent()
                    delayed.release.countDown()
                    await("Actual broker may accept AFTER caller cancellation") { proxy.acceptedRegistrations.size == 1 }
                    repeat(100) { next.retry(); runCurrent() }
                    assertTrue(old.state.value.deviceId.isEmpty())
                    assertTrue(next.state.value.deviceId.isEmpty())
                    assertNull(old.currentAuthority()); assertNull(next.currentAuthority())
                    val observed = wire { PlaybackDevicesApi(requireNotNull(graph.client)).view() }
                    assertEquals(proxy.acceptedRegistrations.toSet(), observed.devices.map { it.deviceId }.toSet())
                    assertFalse(proxy.actions.any { it == "leave" || it == "transfer" || it == "command" })
                    assertEquals(PlaybackSnapshot(), PlaybackService.exportLocalSnapshot())
                    // This is an OPEN protocol residual: a local timeout/disposal is not a
                    // server commit deadline. Never infer expiry from a local fixed sleep.
                } finally { delayed.release.countDown(); graph.clear(); service?.destroy(); runCurrent() }
            } }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun actualBrokerRetainsDistinctSameNameRegistrationsAcrossClientLifetimes() = runBlocking {
        // A contract diagnostic, not a claim that abrupt-death duplicates are fixed.
        PersistentGoBroker().use { broker ->
            val saved = Sessions()
            val first = SpofifyClient(broker.server, saved)
            broker.login(first)
            val one = PlaybackDevicesApi(first).register("Same phone")
            val second = SpofifyClient(broker.server, Sessions().apply { value = saved.value })
            second.restore()
            val api = PlaybackDevicesApi(second)
            val two = api.register("Same phone")
            assertNotEquals(one.deviceId, two.deviceId)
            assertEquals(setOf(one.deviceId, two.deviceId), api.view().devices.map { it.deviceId }.toSet())
            api.leave(one.deviceId)
            assertEquals(listOf(two.deviceId), api.view().devices.map { it.deviceId })
            api.leave(two.deviceId)
            assertTrue(api.view().devices.isEmpty())
        }
    }
}
