package dev.spofify.mobile

import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.spofify.mobile.core.PlaybackDevicesApi
import dev.spofify.mobile.core.PlaybackDevicesView
import dev.spofify.mobile.core.PlaybackSnapshot
import dev.spofify.mobile.core.SessionStore
import dev.spofify.mobile.core.SpofifyClient
import dev.spofify.mobile.core.StoredSession
import dev.spofify.mobile.feature.devices.PlaybackCoordinator
import dev.spofify.mobile.playback.PersistentGoBroker
import dev.spofify.mobile.playback.PlaybackConnection
import dev.spofify.mobile.playback.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import java.util.concurrent.TimeUnit

/**
 * Actual MainActivity -> SpofifyApp -> LibraryViewModel -> PlaybackConnection, with the
 * application graph/coordinator/Media3 service and one persistent, pinned loopback Go room.
 * There is no fake playback implementation, coordinator, player view or sheet-only mount.
 *
 * Robolectric does not run Android's service manager: its supported bindService mapping
 * supplies the REAL PlaybackService.onBind binder to the production MediaController builder.
 * Controller construction/disconnection/rebinding are not replaced or injected. The empty
 * service is explicitly held live across Activity transitions; this does NOT establish native
 * service-manager/FGS lifetime, task disposal, process death, audio or rendered-app acceptance.
 * No expanded-player claim: the production empty dock correctly has no expandable song.
 * Session persistence here is an in-memory SessionStore adapter; Keystore is not under test.
 *
 * Requires SPOFIFY_TEST_GO_BROKER_BINARY; accepts SPOFIFY_TEST_GO_BROKER_RUNTIME_ROOT. No clock
 * injection: network waits use real Dispatchers.IO, and broker facts come from actual HTTP.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SpofifyApplication::class, qualifiers = "w400dp-h850dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(UnstableApi::class)
@org.junit.experimental.categories.Category(dev.spofify.mobile.testing.RealBrokerIntegration::class)
class RegistrationHostLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()

    /** Observe transient real-player starts/preparation too, not just a later idle snapshot. */
    private class OutputProbe : Player.Listener {
        var playRequests = 0
        var playingEvents = 0
        var nonIdleStates = 0
        var nonemptyTimelines = 0
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) playRequests++
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) playingEvents++ }
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_IDLE) nonIdleStates++
        }
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (!timeline.isEmpty) nonemptyTimelines++
        }
    }
    private val outputProbe = OutputProbe()

    private class Sessions(@Volatile var value: StoredSession? = null) : SessionStore {
        override fun read() = value
        override fun write(value: StoredSession?) { this.value = value }
    }
    private class Logins : LoginStore {
        override fun read(): RememberedLogin? = null
        override fun write(value: RememberedLogin?) = Unit
    }

    @Test(timeout = 55_000)
    fun pickerBackBackgroundActualActivityRecreationAndControllerRebindKeepTheLiveRegistration() {
        // Initialize the Android application before starting Go's real startup quarantine.
        val app = ApplicationProvider.getApplicationContext<SpofifyApplication>()
        val previousGraph = app.graph
        val saved = Sessions()
        var graph: AppGraph? = null
        var client: SpofifyClient? = null
        var observer: SpofifyClient? = null
        var service: ServiceController<PlaybackService>? = null
        var activity: ActivityScenario<MainActivity>? = null
        var coordinator: PlaybackCoordinator? = null
        PersistentGoBroker().use { broker ->
            try {
                compose.runOnIdle {
                    previousGraph.clear()
                    val next = AppGraph(app, saved, Logins())
                    installGraph(app, next)
                    graph = next
                    client = next.configure(broker.server)
                    coordinator = requireNotNull(next.playbackCoordinator)
                }
                val currentGraph = requireNotNull(graph)
                val currentClient = requireNotNull(client)
                val owner = requireNotNull(coordinator)
                io { broker.login(currentClient) }
                val watching = SpofifyClient(broker.server, Sessions(requireNotNull(saved.value)))
                observer = watching
                assertNotNull(io { watching.restore() })
                val api = PlaybackDevicesApi(watching)
                compose.runOnIdle {
                    val created = Robolectric.buildService(PlaybackService::class.java)
                    service = created
                    created.create()
                    val actualService = created.get()
                    val component = ComponentName(app, PlaybackService::class.java)
                    val binder = requireNotNull(actualService.onBind(
                        Intent(MediaSessionService.SERVICE_INTERFACE).setComponent(component)))
                    // Platform plumbing only: use the real service binder and production
                    // PlaybackConnection.connect(), NOT a prebuilt/injected controller.
                    shadowOf(app).setComponentNameAndServiceForBindService(component, binder)
                }
                await("actual Media3 service registers through production coordinator") {
                    owner.state.value.connected && owner.state.value.deviceId.isNotEmpty()
                }
                val p = owner.state.value.deviceId
                val first = io { api.view() }
                assertEquals(setOf(p), ids(first))
                // Fail honestly if setup missed the startup scenario, instead of accepting a
                // pristine idle grant as evidence for the no-passive-acquisition regression.
                assertEquals("Host fixture must register during startup quarantine", "", first.activeDeviceId)
                val name = first.devices.single().name
                val q = io { api.register(name) }.deviceId
                assertNotEquals("Q is a genuine distinct same-name endpoint", p, q)
                compose.runOnIdle { owner.retry() } // real scoped liveness poll, never selection
                await("production view includes both exact same-name rows") {
                    owner.state.value.view?.let { ids(it) == setOf(p, q) } == true
                }

                val actualService = requireNotNull(service).get()
                val player = compose.runOnIdle {
                    (field(actualService, "player") as ExoPlayer).also { it.addListener(outputProbe) }
                }
                val session = compose.runOnIdle { field(actualService, "session") as MediaSession }
                val receiver = compose.runOnIdle {
                    requireNotNull(PlaybackService.exportLocalQueueIntentState()).receiverId
                }
                val baseline = io { api.view() }
                assertEquals(setOf(p, q), ids(baseline))
                assertEquals("", baseline.activeDeviceId)
                assertTrue(baseline.commands.isEmpty())
                assertNull(baseline.transfer)

                activity = ActivityScenario.launch(MainActivity::class.java)
                val firstActivity = actualActivity(requireNotNull(activity))
                val model = actualModel(requireNotNull(activity))
                assertSame(currentGraph, model.graph)
                assertTrue("MainActivity must use production PlaybackConnection", model.playback is PlaybackConnection)
                val connection = model.playback as PlaybackConnection
                await("production component-token controller connects to actual service binder") {
                    model.state.value.session != null && !model.state.value.restoring &&
                        controller(connection)?.isConnected == true
                }
                val initialController = compose.runOnIdle { requireNotNull(controller(connection)) }

                repeat(3) {
                    assertStable(app, currentGraph, currentClient, owner, api, watching, actualService,
                        player, session, receiver, p, q, baseline.epoch)
                    openAndCheckPicker(name)
                    dismissPickerWithSystemBack()
                    compose.onNodeWithText("Playback devices").assertDoesNotExist()
                    compose.onNodeWithText("No song selected").assertIsDisplayed()
                    compose.onNodeWithContentDescription("Play").assertIsNotEnabled()
                    compose.onNodeWithTag("primary-navigation").assertIsDisplayed()
                }

                // Real Activity lifecycle callbacks, not direct calls to a sheet/composable.
                requireNotNull(activity).moveToState(Lifecycle.State.CREATED)
                assertStable(app, currentGraph, currentClient, owner, api, watching, actualService,
                    player, session, receiver, p, q, baseline.epoch)
                requireNotNull(activity).moveToState(Lifecycle.State.RESUMED)
                compose.waitForIdle()
                openAndCheckPicker(name)
                dismissPickerWithSystemBack()

                // Actual ActivityScenario recreation keeps Android's retained ViewModel store.
                // Unlike a fake "recreate" helper this destroys/recreates MainActivity itself.
                requireNotNull(activity).recreate()
                val recreated = actualActivity(requireNotNull(activity))
                assertNotSame(firstActivity, recreated)
                assertSame("Configuration recreation retains the real ViewModel", model, actualModel(requireNotNull(activity)))
                assertSame(connection, actualModel(requireNotNull(activity)).playback)
                await("recreated MainActivity attaches to the same live controller") {
                    controller(connection)?.isConnected == true
                }
                assertSame(initialController, compose.runOnIdle { controller(connection) })
                assertStable(app, currentGraph, currentClient, owner, api, watching, actualService,
                    player, session, receiver, p, q, baseline.epoch)
                openAndCheckPicker(name)
                dismissPickerWithSystemBack()

                // Deliberately release only the real UI controller, while this exact service
                // remains live. Rebinding must not change registration or receiver incarnation.
                compose.runOnIdle { connection.release() }
                await("controller-only detach completes") { controller(connection) == null }
                assertStable(app, currentGraph, currentClient, owner, api, watching, actualService,
                    player, session, receiver, p, q, baseline.epoch)
                compose.runOnIdle { connection.connect() }
                await("production controller reconnects without replacing the service") {
                    controller(connection)?.isConnected == true
                }
                assertNotSame(initialController, compose.runOnIdle { controller(connection) })
                assertStable(app, currentGraph, currentClient, owner, api, watching, actualService,
                    player, session, receiver, p, q, baseline.epoch)
                openAndCheckPicker(name)
                dismissPickerWithSystemBack()

                // A fully disposed Activity/ViewModel is distinct from configuration recreation.
                // The application graph and explicitly held live service remain the same owners.
                activity!!.close()
                activity = null
                assertStable(app, currentGraph, currentClient, owner, api, watching, actualService,
                    player, session, receiver, p, q, baseline.epoch)
                activity = ActivityScenario.launch(MainActivity::class.java)
                val successor = actualModel(requireNotNull(activity))
                assertNotSame(model, successor)
                assertNotSame(connection, successor.playback)
                assertTrue(successor.playback is PlaybackConnection)
                await("new real host/controller attaches to the still-live service") {
                    successor.state.value.session != null && !successor.state.value.restoring &&
                        controller(successor.playback as PlaybackConnection)?.isConnected == true
                }
                assertStable(app, currentGraph, currentClient, owner, api, watching, actualService,
                    player, session, receiver, p, q, baseline.epoch)
                openAndCheckPicker(name)
                dismissPickerWithSystemBack()
                assertStable(app, currentGraph, currentClient, owner, api, watching, actualService,
                    player, session, receiver, p, q, baseline.epoch)

                // Cleanup Q by its exact known ID, not its name or a guessed "this phone" row.
                io { api.leave(q) }
                assertEquals(setOf(p), ids(io { api.view() }))
            } finally {
                // Close the actual host before disposing its manually held service/graph.
                // The real broker remains alive while lifecycle-owned exact-ID cleanup runs.
                activity?.close()
                var disposal: kotlinx.coroutines.Job? = null
                compose.runOnIdle {
                    service?.get()?.let { (field(it, "player") as? Player)?.removeListener(outputProbe) }
                    service?.destroy()
                    disposal = coordinator?.close()
                }
                disposal?.let { pending -> await("owned registration cleanup settles") { pending.isCompleted } }
                compose.runOnIdle {
                    graph?.clear()
                    installGraph(app, previousGraph)
                }
                observer?.let(::closeHttp)
                client?.let(::closeHttp)
            }
        }
    }

    private fun openAndCheckPicker(name: String) {
        compose.onNodeWithContentDescription("Playback devices").assertIsDisplayed().performClick()
        compose.onNodeWithText("Playback devices").assertIsDisplayed()
        compose.onNodeWithTag("Playback device list").assertIsDisplayed()
        val rows = compose.onAllNodesWithContentDescription("Select output on $name")
        rows.assertCountEquals(2)
        val status = rows.fetchSemanticsNodes().map { it.config[SemanticsProperties.StateDescription] }
        assertEquals("Only exact local registration is labelled this device", 1, status.count { "This device" in it })
        assertEquals("Genuine same-name Q remains its own row", 1, status.count { "This device" !in it })
        compose.onAllNodes(hasText(name) and hasAnyAncestor(hasTestTag("Playback device list"))).assertCountEquals(2)
        compose.onAllNodesWithTag("Pending output").assertCountEquals(0)
        compose.onNodeWithText("View only", substring = true).assertDoesNotExist()
    }

    private fun dismissPickerWithSystemBack() {
        val dialog = compose.runOnIdle { ShadowDialog.getLatestDialog() as androidx.activity.ComponentDialog }
        assertTrue(dialog.isShowing)
        // This is the real modal dialog's system Back dispatcher, not onDismiss invocation.
        compose.runOnIdle { dialog.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun assertStable(
        app: SpofifyApplication, graph: AppGraph, client: SpofifyClient, owner: PlaybackCoordinator,
        api: PlaybackDevicesApi, observer: SpofifyClient, service: PlaybackService, player: ExoPlayer,
        session: MediaSession, receiver: String, p: String, q: String, epoch: Long,
    ) {
        // Q's independent real heartbeat keeps the peer live. A normal production retry pumps
        // P's scoped poll under Robolectric's paused looper; neither helper selects output or
        // fabricates a view. Registration churn, even with equal names, remains visible in GET.
        io { api.poll(q, epoch) }
        compose.runOnIdle { owner.retry() }
        await("same exact production registration and peer view remain usable") {
            owner.state.value.deviceId == p && owner.state.value.view?.let { ids(it) == setOf(p, q) } == true
        }
        val view = io { api.view() }
        assertEquals(setOf(p, q), ids(view))
        assertEquals(epoch, view.epoch)
        assertEquals("", view.activeDeviceId)
        assertEquals(0L, view.leaseMs)
        assertNull(view.transfer)
        assertTrue("No retained transfer/command outcome from presentation or reattachment", view.commands.isEmpty())
        assertTrue(view.directives.isEmpty())
        assertNull("No owner snapshot was acquired or published", view.state)
        assertTrue("No listening history record", io { observer.recent() }.items.isEmpty())
        compose.runOnIdle {
            assertSame(graph, app.graph)
            assertSame(client, graph.client)
            assertSame(owner, graph.playbackCoordinator)
            assertEquals(p, owner.state.value.deviceId)
            assertEquals(setOf(p, q), owner.state.value.devices.map { it.deviceId }.toSet())
            assertNull(owner.state.value.pendingSelection)
            assertNull(owner.currentAuthority())
            assertSame(player, field(service, "player"))
            assertSame(session, field(service, "session"))
            assertEquals(receiver, requireNotNull(PlaybackService.exportLocalQueueIntentState()).receiverId)
            assertEquals(PlaybackSnapshot(), PlaybackService.exportLocalSnapshot())
            assertEquals(0, player.mediaItemCount)
            assertEquals(Player.STATE_IDLE, player.playbackState)
            assertFalse(player.playWhenReady)
            assertFalse(player.isPlaying)
            assertEquals("No transient playWhenReady start", 0, outputProbe.playRequests)
            assertEquals("No transient audible-player state", 0, outputProbe.playingEvents)
            assertEquals("No transient preparation/state transition", 0, outputProbe.nonIdleStates)
            assertEquals("No transient media installation", 0, outputProbe.nonemptyTimelines)
            assertTrue((field(service, "history") as Map<*, *>).isEmpty())
            assertTrue((field(service, "historyNonces") as Map<*, *>).isEmpty())
        }
    }

    private fun actualModel(scenario: ActivityScenario<MainActivity>): LibraryViewModel {
        lateinit var model: LibraryViewModel
        scenario.onActivity { model = ViewModelProvider(it)[LibraryViewModel::class.java] }
        return model
    }
    private fun actualActivity(scenario: ActivityScenario<MainActivity>): MainActivity {
        lateinit var activity: MainActivity
        scenario.onActivity { activity = it }
        return activity
    }
    private fun controller(connection: PlaybackConnection) = field(connection, "controller") as MediaController?
    private fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name).let {
        it.isAccessible = true
        it.get(instance)
    }
    private fun installGraph(app: SpofifyApplication, graph: AppGraph) {
        SpofifyApplication::class.java.getDeclaredField("graph").apply { isAccessible = true; set(app, graph) }
    }
    private fun ids(view: PlaybackDevicesView) = view.devices.map { it.deviceId }.toSet()
    private fun await(label: String, condition: () -> Boolean) {
        compose.waitUntil(timeoutMillis = 8_000) {
            compose.waitForIdle()
            condition()
        }
        assertTrue(label, condition())
    }
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { withTimeout(5_000) { block() } }
    private fun closeHttp(client: SpofifyClient) {
        client.httpClient.dispatcher.cancelAll()
        client.httpClient.connectionPool.evictAll()
        client.httpClient.dispatcher.executorService.shutdown()
        check(client.httpClient.dispatcher.executorService.awaitTermination(2, TimeUnit.SECONDS)) {
            "Owned host-test HTTP client did not stop"
        }
    }
}
