package dev.spofify.mobile.playback

import android.app.ActivityManager
import android.app.NotificationManager
import android.media.AudioManager
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import dev.spofify.mobile.AppGraph
import dev.spofify.mobile.LibraryViewModel
import dev.spofify.mobile.SpofifyApplication
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.PlaybackDevicesApi
import dev.spofify.mobile.core.PlaybackSnapshot
import dev.spofify.mobile.core.SpofifyClient
import dev.spofify.mobile.feature.devices.PlaybackCoordinator
import dev.spofify.mobile.feature.devices.PlaybackDevicesSheet
import dev.spofify.mobile.feature.player.NativeMiniPlayer
import dev.spofify.mobile.feature.player.NativeNowPlaying
import dev.spofify.mobile.testing.RealBrokerIntegration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

/** Combined landed registration + prepared transport boundary: graceful AppGraph.configure
 * and manual Robolectric service destroy/create, NOT native process death. Real ExoPlayer
 * listeners/clock/start guards consume synthetic PCM; this is NOT acoustic/native approval.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SpofifyApplication::class, qualifiers = "w400dp-h850dp-mdpi",
    shadows = [PreparedTransportMedia::class], instrumentedPackages = ["androidx.media3.exoplayer"])
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(UnstableApi::class)
class PreparedTransportReplacementAppGraphTest {
    @get:Rule val compose = createComposeRule()
    private val trace = ArrayDeque<String>()
    private fun record(value: String) { if (trace.size == 64) trace.removeFirst(); trace.addLast(value) }

    private data class Lifetime(val service: PlaybackService, val raw: ExoPlayer,
        val connection: PlaybackConnection, val model: LibraryViewModel, val owner: PlaybackCoordinator)

    @Test
    @Category(RealBrokerIntegration::class)
    fun combinedLandedRegistrationAndTransportSurviveGracefulGraphAndManualServiceReplacement() {
        val broker = PreparedTransportBrokerProcess.start()
        val app = ApplicationProvider.getApplicationContext<SpofifyApplication>()
        val clients = mutableListOf<SpofifyClient>()
        val stores = mutableListOf<ViewModelStore>()
        val connections = mutableListOf<PlaybackConnection>()
        val controllers = mutableListOf<MediaController>()
        val services = mutableListOf<ServiceController<PlaybackService>>()
        val owners = mutableListOf<PlaybackCoordinator>()
        var graph: AppGraph? = null
        var failure: Throwable? = null
        try {
            app.graph.clear()
            val sessions = PreparedTransportBrokerFixture.MemorySessions()
            val activeGraph = runBlocking { broker.fixture.authenticatedGraph(app, sessions) }.also { graph = it }
            owners += requireNotNull(activeGraph.playbackCoordinator)
            SpofifyApplication::class.java.getDeclaredField("graph").apply { isAccessible = true; set(app, activeGraph) }
            val firstClient = requireNotNull(activeGraph.client).also { clients += it }
            val saved = requireNotNull(sessions.read())
            // Independent client, SAME cookie/session: exactly one initial login. It survives
            // configure and observes actual broker facts, never a retired coordinator display.
            val observerStore = PreparedTransportBrokerFixture.MemorySessions().apply { write(saved) }
            val observer = SpofifyClient(broker.fixture.server, observerStore).also { clients += it }
            assertNotNull(runBlocking { observer.restore() })
            assertTrue("Observer must retain the same cookie", observerStore.read()?.cookie == saved.cookie)
            val observerApi = PlaybackDevicesApi(observer)
            // This independently constructed observer must negotiate sidecars itself;
            // sharing a cookie does not share an API instance's capability negotiation.
            runBlocking { observerApi.probeQueueIntents() }
            // No public history() exists in this client. Use its exact-origin authenticated
            // HTTP transport for the real GET, preserving cookie/redirect/privacy guards.
            fun history(): JsonObject = runBlocking(Dispatchers.IO) {
                observer.httpClient.newCall(Request.Builder().url(observer.server.url("api", "me", "history")).build()).execute().use { response ->
                    check(response.code == 200) { "Real history read failed" }
                    val source = requireNotNull(response.body).source()
                    check(!source.request(32_769)) { "History fixture response exceeds bound" }
                    Json.parseToJsonElement(source.readUtf8()).jsonObject
                }
            }
            assertEquals(0, history().getValue("total").jsonPrimitive.int)
            await("real broker startup drain") { runBlocking { observerApi.view() }.quarantineMs == 0L }
            val shownConnection = mutableStateOf<PlaybackConnection?>(null)
            val full = mutableStateOf(false)
            val picker = mutableStateOf(false)
            compose.setContent {
                shownConnection.value?.let { shown ->
                    val state by shown.state.collectAsState()
                    MaterialTheme {
                        if (full.value) NativeNowPlaying(state, shown, null, false, false, {}, {}, {}, {},
                            artwork = { _, modifier -> Box(modifier) }, onPlaybackDevices = { picker.value = true })
                        else NativeMiniPlayer(state, shown, {}, artwork = { _, modifier -> Box(modifier) },
                            onPlaybackDevices = { picker.value = true })
                        if (picker.value) PlaybackDevicesSheet(requireNotNull(activeGraph.playbackCoordinator)) { picker.value = false }
                    }
                }
            }
            fun createLifetime(): Lifetime {
                val owner = requireNotNull(activeGraph.playbackCoordinator)
                val interceptions = PreparedTransportMedia.interceptions.get()
                val serviceController = Robolectric.buildService(PlaybackService::class.java).create().also { services += it }
                val actual = serviceController.get()
                assertEquals(interceptions + 1, PreparedTransportMedia.interceptions.get())
                shadowOf(actual.getSystemService(ActivityManager::class.java)).setProcesses(listOf(
                    ActivityManager.RunningAppProcessInfo().apply {
                        pid = android.os.Process.myPid(); importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                    }))
                shadowOf(actual.getSystemService(AudioManager::class.java)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                await("genuine service registration") { owner.state.value.connected && owner.state.value.deviceId.isNotEmpty() }
                val raw = field(actual, "player") as ExoPlayer
                raw.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) { record("state=$state pos=${raw.currentPosition}") }
                    override fun onIsPlayingChanged(playing: Boolean) { record("playing=$playing pos=${raw.currentPosition}") }
                })
                val session = field(actual, "session") as MediaSession
                val future = MediaController.Builder(actual, session.token).buildAsync()
                await("real posted MediaController connection") { future.isDone }
                val controller = future.get(1, TimeUnit.SECONDS).also { controllers += it }
                // Same documented connection seam as the passing transport test: genuine
                // session/controller/listener, no fake Player, endpoint or explicit-origin bypass.
                val connection = PlaybackConnection(actual).also { connections += it }
                PlaybackConnection::class.java.getDeclaredField("controller").apply { isAccessible = true; set(connection, controller) }
                val listener = PlaybackConnection::class.java.getDeclaredField("listener").let {
                    it.isAccessible = true; it.get(connection) as Player.Listener
                }
                controller.addListener(listener); connection.connect()
                val store = ViewModelStore().also { stores += it }
                val model = LibraryViewModel(app, activeGraph, connection).also { store.put("replacement", it) }
                shownConnection.value = connection
                val lifetime = Lifetime(actual, raw, connection, model, owner)
                await("authenticated real ViewModel and exact displayed service projection") {
                    model.state.value.session != null && !model.state.value.busy && projected(lifetime)
                }
                return lifetime
            }
            val queue = dev.spofify.mobile.core.PlaybackQueue(listOf(
                LibraryItem("track", broker.fixture.tracks.getValue("A"), "A first", hasAudio = true, entryId = "A-first"),
                LibraryItem("track", broker.fixture.tracks.getValue("A"), "A duplicate", hasAudio = true, entryId = "A-second"),
                LibraryItem("track", broker.fixture.tracks.getValue("C1"), "C1", hasAudio = true, entryId = "C1")), total = 3)
            fun start(life: Lifetime, client: SpofifyClient) {
                val samples = PreparedTransportMedia.samples.get(); val starts = PreparedTransportMedia.starts.get()
                life.model.playPrepared(client, client.currentSession()!!.accountId, queue, null, "A-first")
                await("deliberate model Play reaches actual READY/isPlaying, started PCM reads and position") {
                    life.raw.playbackState == Player.STATE_READY && life.raw.isPlaying && life.raw.currentPosition >= 100 &&
                        PreparedTransportMedia.samples.get() > samples && PreparedTransportMedia.starts.get() > starts && projected(life) &&
                        life.owner.state.value.view?.state?.playing == true && settled(life.owner)
                }
            }
            fun pause(life: Lifetime) {
                await("displayed Pause is bound to the exact live service") { projected(life) && life.connection.state.value.isPlaying }
                val interruption = field(life.service, "queueStartInterruption") as Long
                compose.onNodeWithContentDescription("Pause").performClick()
                await("actual Pause dispatch, drained output and settled publication") {
                    (field(life.service, "queueStartInterruption") as Long) > interruption &&
                        !life.raw.isPlaying && !life.raw.playWhenReady && life.raw.playbackState == Player.STATE_IDLE &&
                        life.owner.state.value.view?.state?.playing == false && projected(life) &&
                        life.owner.javaClass.getDeclaredField("pendingCommand").let { it.isAccessible = true; it.get(life.owner) == null }
                }
            }
            val first = createLifetime()
            start(first, firstClient)
            await("only actual PCM/position progress earns the first history event") {
                val occurrence = PlaybackService.exportLocalSnapshot().occurrence
                first.raw.isPlaying && occurrence != null && occurrence.progress >= 1.0 && occurrence.reported && projected(first)
            }
            await("earned history is one real backend record") { history().getValue("total").jsonPrimitive.int == 1 }
            val earnedEvent = requireNotNull(PlaybackService.exportLocalSnapshot().occurrence).eventId
            fun assertOneEarnedHistory() {
                val page = history()
                assertEquals("Exactly one backend record, not a history POST-attempt count", 1, page.getValue("total").jsonPrimitive.int)
                val item = page.getValue("items").jsonArray.single().jsonObject
                assertEquals(earnedEvent, item.getValue("id").jsonPrimitive.content)
                assertEquals(broker.fixture.tracks.getValue("A"), item.getValue("track").jsonObject.getValue("id").jsonPrimitive.content)
            }
            assertOneEarnedHistory()
            val oldId = first.owner.state.value.deviceId
            val oldReceiver = requireNotNull(PlaybackService.exportLocalQueueIntentState()).receiverId
            pause(first)
            val retained = requireNotNull(runBlocking { observerApi.view() }.state)
            assertEquals(PlaybackService.exportLocalSnapshot().occurrence, retained.occurrence)
            assertEquals(earnedEvent, retained.occurrence?.eventId)
            assertTrue(retained.occurrence?.reported == true)
            val oldToken = shadowOf(first.service.getSystemService(NotificationManager::class.java)).allNotifications.single()
                .actions.single { it.title.toString() == "Resume" }.actionIntent
            val oldFocus = field(first.service, "audioFocus") as PlaybackAudioFocus
            val oldForeground = field(first.service, "foreground") as PlaybackForegroundNotification
            assertTrue(runBlocking { observerApi.view() }.devices.any { it.deviceId == oldId })
            val beforeReplacementMetrics = runBlocking { broker.metrics() }
            val samplesAtOldPause = PreparedTransportMedia.samples.get()

            // This is the real configure replacement path and its landed predecessorDisposal,
            // not graph.clear + constructing an unrelated graph or manually leaving the broker.
            val successorClient = activeGraph.configure(broker.fixture.server).also { clients += it }
            assertNotSame(firstClient, successorClient)
            assertNotSame(first.owner, activeGraph.playbackCoordinator)
            owners += requireNotNull(activeGraph.playbackCoordinator)
            assertFalse(first.raw.isPlaying); assertFalse(first.raw.playWhenReady)
            assertFalse(oldFocus.isGranted); assertFalse(oldForeground.foregroundConfirmed)
            assertTrue("Retiring the old store lease must preserve the saved cookie", sessions.read()?.cookie == saved.cookie)
            assertNotNull(runBlocking { activeGraph.restore() })
            assertTrue("Successor restores the SAME cookie, with no fresh auth login", sessions.read()?.cookie == saved.cookie)
            assertTrue("Restoration retains the exact authenticated session", sessions.read()?.session == saved.session)
            assertEquals(saved.session.accountId, successorClient.currentSession()?.accountId)
            assertEquals(saved.session.accountId, observer.currentSession()?.accountId)
            stores.first().clear(); stores.removeAt(0)
            connections.first().release(); connections.removeAt(0)
            controllers.first().release(); controllers.removeAt(0)
            services.first().destroy(); services.removeAt(0)
            val predecessorDisposal = first.owner.close() // Already closed by configure; observe its actual bounded job.
            await("predecessor disposal completes while the same broker remains alive") { predecessorDisposal?.isCompleted != false }
            await("independent broker observer sees the exact old ID gone") {
                runBlocking { observerApi.view() }.devices.none { it.deviceId == oldId }
            }
            assertFalse(oldFocus.isGranted); assertFalse(oldForeground.foregroundConfirmed)
            val closedState = requireNotNull(runBlocking { observerApi.view() }.state)
            assertFalse(closedState.playing); assertSameMusic(retained, closedState)
            assertEquals(retained.occurrence, closedState.occurrence)

            val second = createLifetime()
            val newId = second.owner.state.value.deviceId
            val newReceiver = requireNotNull(PlaybackService.exportLocalQueueIntentState()).receiverId
            assertNotEquals(oldId, newId); assertNotEquals(oldReceiver, newReceiver)
            val registered = runBlocking { observerApi.view() }
            assertTrue(registered.devices.any { it.deviceId == newId }); assertTrue(registered.devices.none { it.deviceId == oldId })
            assertFalse(second.raw.isPlaying); assertFalse(second.raw.playWhenReady)
            assertFalse((field(second.service, "audioFocus") as PlaybackAudioFocus).isGranted)
            assertFalse((field(second.service, "foreground") as PlaybackForegroundNotification).foregroundConfirmed)
            val passiveMetrics = runBlocking { broker.metrics() }
            assertEquals("Exactly one successor registration, no hidden replacement loop",
                beforeReplacementMetrics.getValue("registrations").jsonPrimitive.long + 1,
                passiveMetrics.getValue("registrations").jsonPrimitive.long)
            for (counter in listOf("claims", "transfers"))
                assertEquals("Graceful replacement must not acquire output", beforeReplacementMetrics.getValue(counter), passiveMetrics.getValue(counter))
            val passiveRenderer = listOf(PreparedTransportMedia.enables.get(), PreparedTransportMedia.resets.get(), PreparedTransportMedia.starts.get())
            assertEquals("Old output cannot consume PCM across graceful replacement", samplesAtOldPause, PreparedTransportMedia.samples.get())
            while (shadowOf(second.service).nextStartedService != null) { }
            oldToken.send()
            second.service.onStartCommand(requireNotNull(shadowOf(second.service).nextStartedService), 0, 1)
            repeat(20) { shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS); Thread.sleep(5) }
            assertFalse("Old posted token cannot start successor", second.raw.isPlaying); assertFalse(second.raw.playWhenReady)
            assertEquals("Neither old output nor passive successor consumes PCM", samplesAtOldPause, PreparedTransportMedia.samples.get())
            assertEquals(passiveRenderer, listOf(PreparedTransportMedia.enables.get(), PreparedTransportMedia.resets.get(), PreparedTransportMedia.starts.get()))
            val afterStaleToken = runBlocking { broker.metrics() }
            for (counter in listOf("registrations", "claims", "transfers")) assertEquals(counter, passiveMetrics.getValue(counter), afterStaleToken.getValue(counter))
            assertEquals(registered.activeDeviceId, runBlocking { observerApi.view() }.activeDeviceId)
            await("successor startup/leave quarantine ends without autoplay") { runBlocking { observerApi.view() }.quarantineMs == 0L }
            assertFalse(second.raw.isPlaying)
            // Open the actual production picker through the mini-player's devices callback.
            // Selecting this newly registered exact row authorizes a paused prepare/activate
            // of retained server music; no fresh catalog Play or direct owner.select shortcut.
            compose.onNodeWithContentDescription("Playback devices").performClick()
            compose.onNodeWithText("Playback devices").assertIsDisplayed()
            val pickerMetrics = runBlocking { broker.metrics() }
            for (counter in listOf("registrations", "claims", "transfers"))
                assertEquals("Opening the actual picker is passive", passiveMetrics.getValue(counter), pickerMetrics.getValue(counter))
            assertFalse(second.raw.isPlaying); assertFalse(second.raw.playWhenReady)
            val successorName = second.owner.state.value.devices.single { it.deviceId == newId }.name
            compose.onNodeWithContentDescription("Select output on $successorName").performClick()
            await("actual picker transfer reaches terminal PAUSED activation with retained occurrence") {
                val view = second.owner.state.value.view
                view?.activeDeviceId == newId && view.state?.playing == false && view.transfer == null &&
                    second.owner.state.value.exclusiveOwner && !second.owner.state.value.selectionUnconfirmed &&
                    second.owner.state.value.pendingSelection == null && !second.raw.isPlaying && !second.raw.playWhenReady &&
                    PlaybackService.exportLocalSnapshot().occurrence?.eventId == earnedEvent && projected(second) && settled(second.owner)
            }
            val activated = PlaybackService.exportLocalSnapshot()
            assertSameMusic(retained, activated)
            assertEquals(retained.occurrence, activated.occurrence)
            assertEquals(retained.elapsed, activated.elapsed, 0.001)
            val pausedTruth = runBlocking { observerApi.view() }
            assertEquals(newId, pausedTruth.activeDeviceId); assertFalse(requireNotNull(pausedTruth.state).playing)
            assertSameMusic(retained, requireNotNull(pausedTruth.state))
            assertEquals(newReceiver, pausedTruth.queueIntents?.state?.receiverId)
            assertFalse((field(second.service, "audioFocus") as PlaybackAudioFocus).isGranted)
            assertFalse((field(second.service, "foreground") as PlaybackForegroundNotification).foregroundConfirmed)
            assertEquals(passiveRenderer.last(), PreparedTransportMedia.starts.get())
            assertOneEarnedHistory()
            // Close presentation only; the production picker transaction above owns selection.
            picker.value = false
            val firstResumeSamples = PreparedTransportMedia.samples.get()
            val firstResumeStarts = PreparedTransportMedia.starts.get()
            val retainedPosition = second.raw.currentPosition
            compose.onNodeWithContentDescription("Play").performClick()
            await("deliberate mini Resume of retained A actually starts PCM and advances from paused handoff position") {
                second.raw.playbackState == Player.STATE_READY && second.raw.isPlaying && second.raw.currentPosition > retainedPosition + 50 &&
                    PreparedTransportMedia.samples.get() > firstResumeSamples && PreparedTransportMedia.starts.get() > firstResumeStarts &&
                    projected(second) && second.owner.state.value.view?.state?.playing == true && settled(second.owner)
            }
            val baseline = PlaybackService.exportLocalSnapshot()
            assertSameMusic(retained, baseline)
            assertOneEarnedHistory()
            val metadata = requireNotNull(PlaybackService.exportLocalQueueIntentState())
            val occurrence = PlaybackMedia.occurrence(second.raw.currentMediaItem)
            assertEquals(newReceiver, metadata.receiverId); assertNotNull(baseline.occurrence)
            val metrics = runBlocking { broker.metrics() }
            repeat(5) { cycle ->
                full.value = cycle % 2 == 1
                pause(second)
                val paused = PlaybackService.exportLocalSnapshot(); val position = second.raw.currentPosition
                assertSameMusic(baseline, paused); assertEquals(metadata, PlaybackService.exportLocalQueueIntentState())
                val samples = PreparedTransportMedia.samples.get(); val starts = PreparedTransportMedia.starts.get()
                compose.onNodeWithContentDescription("Play").performClick()
                await("fresh mini/full Resume $cycle actually progresses on the successor") {
                    second.raw.playbackState == Player.STATE_READY && second.raw.isPlaying && second.raw.currentPosition > position + 50 &&
                        PreparedTransportMedia.samples.get() > samples && PreparedTransportMedia.starts.get() > starts && projected(second) &&
                        second.owner.state.value.view?.state?.playing == true && settled(second.owner)
                }
                assertSameMusic(baseline, PlaybackService.exportLocalSnapshot())
                assertEquals(occurrence, PlaybackMedia.occurrence(second.raw.currentMediaItem))
                assertEquals(metadata, PlaybackService.exportLocalQueueIntentState())
                val truth = runBlocking { observerApi.view() }
                assertEquals(newId, truth.activeDeviceId); assertTrue(truth.devices.none { it.deviceId == oldId })
                assertEquals(metadata, truth.queueIntents?.state)
                val after = runBlocking { broker.metrics() }
                // Baseline is AFTER successor activation/first Resume: handoff may use its
                // documented idempotent report semantics, but these five ordinary Resumes
                // of the already-reported same occurrence cannot manufacture another attempt.
                for (counter in listOf("registrations", "claims", "transfers", "collectionPlaybackReads", "historyWrites"))
                    assertEquals("cycle=$cycle $counter", metrics.getValue(counter), after.getValue(counter))
                assertNull(second.connection.state.value.error); assertNull(second.connection.state.value.notice)
                assertOneEarnedHistory()
            }
        } catch (caught: Throwable) { failure = caught; throw caught }
        finally {
            val actions = buildList<() -> Unit> {
                stores.forEach { store -> add { store.clear() } }
                connections.forEach { connection -> add { connection.release() } }
                controllers.forEach { controller -> add { controller.release() } }
                add { graph?.clear() }
                services.forEach { service -> add { service.destroy() } }
                add {
                    val jobs = owners.mapNotNull { it.close() }
                    await("all coordinator disposal jobs finish before broker shutdown") { jobs.all(Job::isCompleted) }
                }
                clients.forEach { client -> add {
                    client.httpClient.dispatcher.cancelAll(); client.httpClient.connectionPool.evictAll()
                    client.httpClient.dispatcher.executorService.shutdown()
                    check(client.httpClient.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS)) { "Owned HTTP executor did not stop" }
                } }
                add { broker.close() }
            }
            cleanup(failure, *actions.toTypedArray())
        }
    }

    private fun settled(owner: PlaybackCoordinator) = owner.javaClass.getDeclaredField("pendingCommand").let {
        it.isAccessible = true; it.get(owner) == null
    }
    private fun projected(life: Lifetime) = life.connection.state.value.interactionKey === PlaybackService.interactionKey() &&
        life.connection.state.value.isPlaying == life.raw.isPlaying
    private fun assertSameMusic(before: PlaybackSnapshot, after: PlaybackSnapshot) {
        assertEquals(before.queue, after.queue); assertEquals(before.position, after.position)
        assertEquals(before.context, after.context); assertEquals(before.shuffle, after.shuffle); assertEquals(before.repeat, after.repeat)
        assertEquals(before.occurrence?.eventId, after.occurrence?.eventId)
    }
    private fun field(service: PlaybackService, name: String): Any? = PlaybackService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)
    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS); Thread.sleep(5) }
        assertTrue("$description; trace=$trace", condition())
    }
    private fun cleanup(primary: Throwable?, vararg actions: () -> Unit) {
        var failure = primary
        actions.forEach { action -> try { action() } catch (caught: Throwable) { if (failure == null) failure = caught else failure!!.addSuppressed(caught) } }
        if (primary == null) failure?.let { throw it }
    }
}
