package dev.spofify.mobile.playback

import android.app.ActivityManager
import android.media.AudioManager
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import dev.spofify.mobile.*
import dev.spofify.mobile.core.*
import dev.spofify.mobile.testing.RealBrokerIntegration
import dev.spofify.mobile.ui.theme.AppTypography
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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

/** Actual production hosts -> VM -> Connection -> service/coordinator -> pinned Go.
 * Synthetic Media3 position/PCM is host evidence, never installed-Android acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SpofifyApplication::class, qualifiers = "w400dp-h850dp-mdpi",
    shadows = [PreparedTransportMedia::class], instrumentedPackages = ["androidx.media3.exoplayer"])
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(UnstableApi::class)
@Category(RealBrokerIntegration::class)
class ExplicitQueueDefaultDeviceAppGraphTest {
    @get:Rule val compose = createComposeRule()
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private enum class Entry { MORE, SWIPE, ACCESSIBILITY }
    private enum class Fault { CLAIM_DROP, ACTIVATION_DROP, QUEUE_DROP, ROUTE_ABA, CONTENDING_REMOTE }
    private data class Lifetime(val serviceController: ServiceController<PlaybackService>, val raw: ExoPlayer,
        val controller: MediaController, val connection: PlaybackConnection, val model: LibraryViewModel,
        val models: ViewModelStore, val receipts: MutableList<PlaybackActionResult>) {
        fun close() { models.clear(); connection.release(); controller.release(); serviceController.destroy() }
    }

    @Test fun unselectedEmptyActualMoreHighSelectsThisPhoneAndStartsFirstOccurrenceOnce() = journey(Entry.MORE)
    @Test fun unselectedEmptyActualRightSwipeStartsFirstOccurrenceOnce() = journey(Entry.SWIPE)
    @Test fun unselectedEmptyActualAccessibleHighStartsFirstOccurrenceOnce() = journey(Entry.ACCESSIBILITY)
    @Test fun retainedPausedA42HighInsertionStaysSilentThenFreshPlayProgressesExactA() = journey(Entry.MORE, retained = true)

    @Test fun lostAcceptedClaimNeverStagesQueuesOrReplaysHigh() = journey(Entry.MORE, fault = Fault.CLAIM_DROP)
    @Test fun lostAcceptedActivationAckNeverExecutesHigh() = journey(Entry.MORE, fault = Fault.ACTIVATION_DROP)
    @Test fun lostAcceptedQueuePublicationRetainsEffectWithoutReplayOrFalseSuccess() = journey(Entry.MORE, fault = Fault.QUEUE_DROP)
    @Test fun routeABAWhileActivationAckHeldCancelsOriginalHigh() = journey(Entry.MORE, fault = Fault.ROUTE_ABA)
    @Test fun contendingRealRemoteSelectionWinsWhileActivationAckHeld() = journey(Entry.MORE, fault = Fault.CONTENDING_REMOTE)

    @Test fun selectedUnavailableRealRemoteRemainsSelectedWithoutPhoneFallback() = journey(Entry.MORE, unavailableRemote = true)

    @Test fun pristineIdleRegistrationQueuesAndStartsWithoutRedundantClaim() = journey(Entry.MORE, pristine = true)

    private fun journey(entry: Entry, retained: Boolean = false, fault: Fault? = null,
        unavailableRemote: Boolean = false, pristine: Boolean = false) {
        val broker = PreparedTransportBrokerProcess.start()
        val app = ApplicationProvider.getApplicationContext<SpofifyApplication>()
        app.graph.clear()
        val sessions = PreparedTransportBrokerFixture.MemorySessions()
        val graph = runBlocking { broker.fixture.authenticatedGraph(app, sessions) }
        SpofifyApplication::class.java.getDeclaredField("graph").apply { isAccessible = true; set(app, graph) }
        val lives = mutableListOf<Lifetime>()
        val shown = mutableStateOf<LibraryViewModel?>(null)
        try {
            // A real previous registration makes this a non-pristine, unselected room.
            val foreignApi = PlaybackDevicesApi(runBlocking { broker.fixture.authenticatedClient() })
            runBlocking { foreignApi.probeQueueIntents() }
            await("startup drain", 12_000) { runBlocking { foreignApi.view() }.quarantineMs == 0L }
            val foreignId = if (pristine) "" else runBlocking { foreignApi.register("Synthetic previous output") }.deviceId
            fun createLifetime(): Lifetime {
                val service = Robolectric.buildService(PlaybackService::class.java).create()
                val actual = service.get()
                shadowOf(actual.getSystemService(ActivityManager::class.java)).setProcesses(listOf(
                    ActivityManager.RunningAppProcessInfo().apply {
                        pid = android.os.Process.myPid(); importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                    }))
                shadowOf(actual.getSystemService(AudioManager::class.java)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                val owner = requireNotNull(graph.playbackCoordinator)
                await("production registration") { owner.state.value.connected }
                val raw = field(actual, "player") as ExoPlayer
                val session = field(actual, "session") as MediaSession
                val future = MediaController.Builder(actual, session.token).buildAsync()
                await("controller connection") { future.isDone }
                val media = future.get(1, TimeUnit.SECONDS)
                val connected = PlaybackConnection(actual).also {
                    PlaybackConnection::class.java.getDeclaredField("controller").apply { isAccessible = true; set(it, media) }
                    val listener = PlaybackConnection::class.java.getDeclaredField("listener").let { f ->
                        f.isAccessible = true; f.get(it) as Player.Listener
                    }
                    media.addListener(listener); it.connect()
                }
                val receipts = mutableListOf<PlaybackActionResult>()
                val controls = object : PlaybackControls by connected {
                    override fun queueHighFromUnselected(selection: QueueIntentSelection, client: SpofifyClient,
                        origin: PlaybackQueueOrigin, isCurrent: () -> Boolean, onResult: (PlaybackActionResult) -> Unit) {
                        connected.queueHighFromUnselected(selection, client, origin, isCurrent) { receipts += it; onResult(it) }
                    }
                    override fun queueIntent(selection: QueueIntentSelection, client: SpofifyClient,
                        action: PlaybackListAction, expectedKey: PlaybackInteractionKey?, expectedBinding: QueueIntentBinding?,
                        isCurrent: () -> Boolean, onResult: (PlaybackActionResult) -> Unit) {
                        connected.queueIntent(selection, client, action, expectedKey, expectedBinding, isCurrent) {
                            receipts += it; onResult(it)
                        }
                    }
                }
                val models = ViewModelStore()
                val model = LibraryViewModel(app, graph, controls).also { models.put("subject", it) }
                await("authenticated model") { model.state.value.session != null && !model.state.value.busy }
                return Lifetime(service, raw, media, connected, model, models, receipts).also { lives += it }
            }
            fun settleUnselected() {
                val owner = requireNotNull(graph.playbackCoordinator)
                await("unselected room drain", 12_000) {
                    owner.state.value.activeDeviceId.isEmpty() && owner.state.value.view?.transfer == null &&
                        owner.state.value.view?.quarantineMs == 0L && owner.state.value.view?.leaseMs == 0L
                }
                // A real leave advances Go's epoch. Explicitly settle normal recovery BEFORE
                // the musical action: reconciliation display is not admission authority.
                if (owner.state.value.recovery != dev.spofify.mobile.feature.devices.PlaybackConnectionRecovery.NONE) owner.retry()
                await("fresh verified unselected connection", 12_000) {
                    owner.state.value.connected && owner.state.value.recovery == dev.spofify.mobile.feature.devices.PlaybackConnectionRecovery.NONE
                }
            }
            var life = createLifetime()
            if (!unavailableRemote && !pristine) {
                runBlocking { foreignApi.leave(foreignId) }
                settleUnselected()
            }
            var preserved: PlaybackSnapshot? = null
            var preservedMetadata: QueueIntentState? = null
            if (retained) {
                val client = requireNotNull(graph.client)
                fun queue(name: String, action: PlaybackListAction) {
                    val count = life.receipts.size + 1
                    life.model.queueIntentFrom(client, LibraryItem("track", broker.fixture.tracks.getValue(name), name, hasAudio = true), action)
                    await("ordinary selected queue operation") { life.receipts.size == count }
                    assertNotNull(life.receipts.last().queueIntentResult)
                }
                queue("A", PlaybackListAction.PLAY_NOW)
                await("old output actually plays") { life.raw.isPlaying && life.raw.currentPosition > 50 }
                queue("H1", PlaybackListAction.QUEUE_HIGH)
                queue("C1", PlaybackListAction.QUEUE_LOW)
                queue("C2", PlaybackListAction.QUEUE_LOW)
                life.controller.pause()
                await("actual paused output and confirmed publication") {
                    !life.raw.isPlaying && life.raw.playbackState == Player.STATE_IDLE && settled(graph)
                }
                life.controller.seekTo(42_000)
                await("retained paused position confirmed") {
                    PlaybackService.exportLocalSnapshot().elapsed == 42.0 &&
                        graph.playbackCoordinator?.state?.value?.view?.state?.elapsed == 42.0 && settled(graph)
                }
                preserved = PlaybackService.exportLocalSnapshot()
                preservedMetadata = requireNotNull(PlaybackService.exportLocalQueueIntentState())
                val cookie = requireNotNull(sessions.read()).cookie
                val oldOwner = requireNotNull(graph.playbackCoordinator)
                val oldId = oldOwner.state.value.deviceId
                graph.configure(broker.fixture.server)
                assertNotNull(runBlocking { graph.restore() })
                assertEquals(cookie, requireNotNull(sessions.read()).cookie)
                life.close(); lives.remove(life)
                await("actual predecessor disposal") { oldOwner.close()?.isCompleted != false }
                await("old row gone without restarting broker") { runBlocking { foreignApi.view() }.devices.none { it.deviceId == oldId } }
                life = createLifetime()
                settleUnselected()
                assertTrue("successor has not adopted restored display as live preparation", PlaybackService.exportLocalSnapshot().queue.isEmpty())
            }
            val active = life
            val owner = requireNotNull(graph.playbackCoordinator)
            val lifecycle = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
            shown.value = active.model
            compose.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                    MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                        shown.value?.let { SpofifyApp(it) }
                    }
                }
            }
            val name = if (retained) "B1" else "A"
            compose.runOnIdle { active.model.switchTab(Tab.SEARCH); active.model.search(name) }
            compose.waitUntil(8_000) {
                compose.onAllNodesWithContentDescription("More actions for $name").fetchSemanticsNodes().isNotEmpty()
            }
            if (unavailableRemote) {
                runBlocking { foreignApi.poll(foreignId, requireNotNull(owner.state.value.view).epoch) }
                await("selected remote becomes unavailable without losing its lease", 7_000) {
                    owner.state.value.activeDeviceId == foreignId &&
                        owner.state.value.devices.any { it.deviceId == foreignId && !it.available }
                }
                assertNull(active.connection.state.value.unselectedQueueOrigin)
            } else if (!pristine) {
                assertNull(active.connection.state.value.interactionKey)
                assertNull(active.connection.state.value.queueIntentBinding)
                assertNotNull("fresh no-output action has a cancellation-only origin", active.connection.state.value.unselectedQueueOrigin)
            }
            val before = runBlocking { broker.metrics() }
            val starts = PreparedTransportMedia.starts.get()
            val samples = PreparedTransportMedia.samples.get()
            assertFalse(active.raw.isPlaying)
            if (fault != null) runBlocking {
                broker.armQueueAdmissionResponse(when (fault) {
                    Fault.CLAIM_DROP -> "claim"
                    Fault.QUEUE_DROP -> "queue-start"
                    else -> "paused-poll"
                }, if (fault in setOf(Fault.ROUTE_ABA, Fault.CONTENDING_REMOTE)) "hold" else "drop")
            }
            when (entry) {
                Entry.MORE -> {
                    compose.onNodeWithContentDescription("More actions for $name").performClick()
                    compose.onNodeWithText("Queue High").performScrollTo().performClick()
                }
                Entry.SWIPE -> compose.onNode(hasText(name) and SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).performTouchInput {
                    swipe(Offset(width * .3f, height / 2f), Offset(width * .8f, height / 2f), 350)
                }
                Entry.ACCESSIBILITY -> {
                    val action = compose.onNode(hasText(name) and SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
                        .fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == "Queue High" }
                    compose.runOnIdle { assertTrue(action.action()) }
                }
            }
            if (unavailableRemote) {
                await("unavailable selected output refuses truthfully") { active.receipts.isNotEmpty() }
                assertEquals(PlaybackActionStatus.UNAVAILABLE, active.receipts.single().status)
                assertEquals(foreignId, owner.state.value.activeDeviceId)
                assertEquals(starts, PreparedTransportMedia.starts.get()); assertEquals(samples, PreparedTransportMedia.samples.get())
                assertTrue(PlaybackService.exportLocalSnapshot().queue.isEmpty())
                assertEquals(before.getValue("claims"), runBlocking { broker.metrics() }.getValue("claims"))
                return
            }
            if (fault != null) {
                await("proxy witnesses actual accepted response") {
                    runBlocking { broker.pauseResponseStatus() }?.get("accepted")?.jsonPrimitive?.boolean == true
                }
                if (fault in setOf(Fault.ROUTE_ABA, Fault.CONTENDING_REMOTE)) {
                    // This is the first paused owner publication: empty activation ACK,
                    // not a queue receipt. Go accepted it, but the phone has not received it.
                    assertTrue(PlaybackService.exportLocalSnapshot().queue.isEmpty())
                    assertEquals(starts, PreparedTransportMedia.starts.get())
                    assertEquals(samples, PreparedTransportMedia.samples.get())
                    if (fault == Fault.ROUTE_ABA) compose.runOnIdle {
                        active.model.switchTab(Tab.PROFILE)
                        active.model.switchTab(Tab.SEARCH); active.model.search(name)
                    } else runBlocking {
                        val remote = foreignApi.register("Synthetic contender")
                        foreignApi.transfer(remote.deviceId, remote.epoch, java.util.UUID.randomUUID().toString(), remote.deviceId, 1)
                    }
                    runBlocking { broker.releasePauseResponse() }
                }
                await("uncertainty/cancellation gets no successful queue receipt", 15_000) { active.receipts.isNotEmpty() }
                assertTrue(active.receipts.all { it.queueIntentResult == null })
                val committed = PlaybackService.exportLocalSnapshot()
                assertEquals(if (fault == Fault.QUEUE_DROP) 1 else 0, committed.queue.size)
                if (owner.state.value.retryAvailable) owner.retry()
                active.connection.connect()
                repeat(120) { shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS); Thread.sleep(5) }
                assertEquals("No replayed queue effect", committed.queue, PlaybackService.exportLocalSnapshot().queue)
                assertTrue(PreparedTransportMedia.starts.get() <= starts + if (fault == Fault.QUEUE_DROP) 1 else 0)
                assertFalse(active.raw.isPlaying)
                assertEquals(1L, runBlocking { broker.metrics() }.getValue("claims").jsonPrimitive.long - before.getValue("claims").jsonPrimitive.long)
                return
            }
            await("High receives the normal terminal result") { active.receipts.isNotEmpty() }
            val result = requireNotNull(active.receipts.single().queueIntentResult)
            val snapshot = PlaybackService.exportLocalSnapshot()
            val metadata = requireNotNull(PlaybackService.exportLocalQueueIntentState())
            result.validated(snapshot, metadata, "ok")
            assertEquals(1, result.acceptedCount); assertEquals("high", result.placement)
            val after = runBlocking { broker.metrics() }
            assertEquals(if (pristine) 0L else 1L, after.getValue("claims").jsonPrimitive.long - before.getValue("claims").jsonPrimitive.long)
            assertEquals(owner.state.value.deviceId, owner.state.value.activeDeviceId)
            if (!retained) {
                await("first occurrence actually progresses and consumes PCM") {
                    active.raw.isPlaying && active.raw.currentPosition > 50 && PreparedTransportMedia.samples.get() > samples
                }
                assertEquals("started", result.playback)
                assertEquals(snapshot.queue[snapshot.position].key, result.playbackKey)
                assertEquals(broker.fixture.tracks.getValue("A"), snapshot.queue[snapshot.position].id)
                assertEquals(starts + 1, PreparedTransportMedia.starts.get())
            } else {
                val kept = requireNotNull(preserved)
                assertEquals(listOf("A", "H1", "B1", "C1", "C2").map { broker.fixture.tracks.getValue(it) }, snapshot.queue.map { it.id })
                assertEquals(kept.queue[kept.position], snapshot.queue[snapshot.position])
                assertEquals(kept.occurrence, snapshot.occurrence)
                assertEquals(42.0, snapshot.elapsed, 0.0); assertEquals(kept.context, snapshot.context)
                assertEquals("unchanged", result.playback); assertNull(result.playbackKey)
                assertEquals("queued", result.outcome)
                assertEquals(requireNotNull(preservedMetadata).highKeys + snapshot.queue[2].key, metadata.highKeys)
                assertEquals(starts, PreparedTransportMedia.starts.get()); assertEquals(samples, PreparedTransportMedia.samples.get())
                assertFalse(active.raw.isPlaying)
                val service = active.serviceController.get()
                assertFalse((field(service, "audioFocus") as PlaybackAudioFocus).isGranted)
                assertFalse((field(service, "foreground") as PlaybackForegroundNotification).foregroundConfirmed)
                compose.onNodeWithContentDescription("Play").performClick()
                await("fresh deliberate Play progresses exact retained A") {
                    active.raw.isPlaying && active.raw.currentPosition > 42_050 && PreparedTransportMedia.samples.get() > samples
                }
                val progressed = PlaybackService.exportLocalSnapshot()
                assertEquals(snapshot.queue, progressed.queue); assertEquals(snapshot.position, progressed.position)
                assertEquals(snapshot.occurrence?.eventId, progressed.occurrence?.eventId)
                assertEquals(starts + 1, PreparedTransportMedia.starts.get())
                val played = runBlocking { broker.metrics() }
                assertEquals(after.getValue("claims"), played.getValue("claims"))
                assertEquals(after.getValue("collectionPlaybackReads"), played.getValue("collectionPlaybackReads"))
            }
        } finally {
            compose.runOnIdle { shown.value = null }
            lives.reversed().forEach { it.close() }
            graph.clear()
            repeat(20) { shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS); Thread.sleep(5) }
            broker.close()
        }
    }

    private fun settled(graph: AppGraph): Boolean = graph.playbackCoordinator?.let { owner ->
        owner.javaClass.getDeclaredField("pendingCommand").let { it.isAccessible = true; it.get(owner) == null }
    } == true
    private fun await(label: String, timeout: Long = 6_000, condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout)
        while (!condition() && System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS); Thread.sleep(5)
        }
        assertTrue(label, condition())
    }
    private fun field(service: PlaybackService, name: String): Any? = PlaybackService::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(service)
}
