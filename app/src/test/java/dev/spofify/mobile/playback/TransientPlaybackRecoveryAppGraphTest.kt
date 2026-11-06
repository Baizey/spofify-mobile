package dev.spofify.mobile.playback

import android.app.ActivityManager
import android.media.AudioManager
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
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
import dev.spofify.mobile.feature.devices.PlaybackConnectionRecovery
import dev.spofify.mobile.feature.devices.PlaybackCoordinator
import dev.spofify.mobile.testing.RealBrokerIntegration
import dev.spofify.mobile.ui.theme.AppTypography
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
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

/** BUG-103: actual SpofifyApp controls and VM-bound origins, not generic remoteCommand Resume.
 * One persistent pinned Go broker per journey; no room reset, endpoint override or fake permit.
 * PreparedTransportMedia supplies synthetic source/PCM only: this is not HTTP audio/range,
 * installed Android, native render, acoustic, background or hardware acceptance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SpofifyApplication::class, qualifiers = "w400dp-h850dp-mdpi",
    shadows = [PreparedTransportMedia::class], instrumentedPackages = ["androidx.media3.exoplayer"])
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(UnstableApi::class)
@Category(RealBrokerIntegration::class)
class TransientPlaybackRecoveryAppGraphTest {
    @get:Rule val compose = createComposeRule()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private data class RendererCounts(val enables: Long, val resets: Long, val starts: Long)
    private fun renderers() = RendererCounts(PreparedTransportMedia.enables.get(),
        PreparedTransportMedia.resets.get(), PreparedTransportMedia.starts.get())

    @Test fun acceptedOrdinaryPlayingHeartbeatDropThenFreshActualMiniPlayResumesExactRetainedAOnce() =
        journey(queueHighFirst = false)

    @Test fun acceptedOrdinaryPlayingHeartbeatDropThenFreshActualMoreHighStaysPausedBeforeMiniPlay() =
        journey(queueHighFirst = true)

    @Test fun shortHeldOrdinaryHeartbeatContinuesOnlyOriginalPlayingPermission() =
        journey(queueHighFirst = false, shortHold = true)

    @Test fun moreOpenedBeforeHeartbeatLossCannotBorrowRecoveredBinding() =
        journey(queueHighFirst = false, heldMore = true)

    private fun journey(queueHighFirst: Boolean, shortHold: Boolean = false, heldMore: Boolean = false) {
        val app = ApplicationProvider.getApplicationContext<SpofifyApplication>()
        val models = ViewModelStore()
        val shown = mutableStateOf<LibraryViewModel?>(null)
        val trace = mutableListOf<String>()
        fun record(phase: String) { if (trace.size < 32) trace += phase }
        var graph: AppGraph? = null
        var graphClient: SpofifyClient? = null
        var observerClient: SpofifyClient? = null
        var service: ServiceController<PlaybackService>? = null
        var coordinator: PlaybackCoordinator? = null
        var connection: PlaybackConnection? = null
        var controller: MediaController? = null
        var failure: Throwable? = null
        var diagnostic: () -> String = { "setupIncomplete=true" }
        val broker = PreparedTransportBrokerProcess.start()
        try {
            app.graph.clear()
            val activeGraph = runBlocking { broker.fixture.authenticatedGraph(app) }.also { graph = it }
            val client = requireNotNull(activeGraph.client).also { graphClient = it }
            val authenticatedSession = requireNotNull(client.currentSession())
            SpofifyApplication::class.java.getDeclaredField("graph").apply {
                isAccessible = true; set(app, activeGraph)
            }
            val observer = runBlocking { broker.fixture.authenticatedClient() }.also { observerClient = it }
            val api = PlaybackDevicesApi(observer)
            runBlocking { api.probeQueueIntents() }
            await("SETUP: real broker startup quarantine drained", 12_000) {
                runBlocking { api.view() }.quarantineMs == 0L
            }
            record("broker-authenticated-startup-drained")

            val interceptions = PreparedTransportMedia.interceptions.get()
            val serviceController = Robolectric.buildService(PlaybackService::class.java).also { service = it }
            serviceController.create()
            val actual = serviceController.get()
            assertEquals("SETUP: production ExoPlayer builder must use the existing PCM seam",
                interceptions + 1, PreparedTransportMedia.interceptions.get())
            shadowOf(actual.getSystemService(ActivityManager::class.java)).setProcesses(listOf(
                ActivityManager.RunningAppProcessInfo().apply {
                    pid = android.os.Process.myPid()
                    importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                }))
            shadowOf(actual.getSystemService(AudioManager::class.java))
                .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            val owner = requireNotNull(activeGraph.playbackCoordinator).also { coordinator = it }
            await("SETUP: production service registers with real broker") { owner.state.value.connected }
            val raw = field(actual, "player") as ExoPlayer
            val session = field(actual, "session") as MediaSession
            val future = MediaController.Builder(actual, session.token).buildAsync()
            try {
                await("SETUP: actual service session/controller connects") { future.isDone }
                controller = future.get(1, TimeUnit.SECONDS)
            } catch (caught: Throwable) {
                future.cancel(true)
                throw caught
            }
            val media = requireNotNull(controller)
            // Same MediaController wiring seam as ExplicitQueueDefaultDeviceAppGraphTest.
            // Neither endpoint nor coordinator registration/admission is replaced.
            val connected = PlaybackConnection(actual).also { connection = it }
            PlaybackConnection::class.java.getDeclaredField("controller").apply {
                isAccessible = true; set(connected, media)
            }
            media.addListener(field(connected, "listener") as Player.Listener)
            connected.connect()
            val receipts = mutableListOf<PlaybackActionResult>()
            val controls = object : PlaybackControls by connected {
                override fun queueIntent(selection: QueueIntentSelection, client: SpofifyClient,
                    action: PlaybackListAction, expectedKey: PlaybackInteractionKey?, expectedBinding: QueueIntentBinding?,
                    isCurrent: () -> Boolean, onResult: (PlaybackActionResult) -> Unit) {
                    connected.queueIntent(selection, client, action, expectedKey, expectedBinding, isCurrent) {
                        receipts += it; onResult(it)
                    }
                }
            }
            // Delegation forwards bindExplicitPlayOrigin unchanged; no test-origin { true }.
            val model = LibraryViewModel(app, activeGraph, controls).also { models.put("subject", it) }
            await("SETUP: authenticated actual model") { model.state.value.session != null && !model.state.value.busy }
            val lifecycle = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
            shown.value = model
            compose.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                    MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                        shown.value?.let { SpofifyApp(it) }
                    }
                }
            }
            var samplesAtFault = PreparedTransportMedia.samples.get()
            diagnostic = {
                val state = owner.state.value
                val projected = connected.state.value
                val local = PlaybackService.exportLocalSnapshot()
                val view = state.view
                // Fixed booleans/enums/numbers only: never serialize a view, identity or payload.
                "connected=${state.connected},recovery=${state.recovery},canQueueIntent=${state.canQueueIntent}," +
                    "isPlaying=${raw.isPlaying},samplesDelta=${PreparedTransportMedia.samples.get() - samplesAtFault}," +
                    "playerFeedback=${!((projected.error ?: projected.notice).isNullOrBlank())}," +
                    "hostFeedback=${!model.state.value.notice.isNullOrBlank()}," +
                    "coordinatorFeedback=${state.message.isNotBlank()},pending=${field(owner, "pendingCommand") != null}," +
                    "localQueueCount=${local.queue.size},serverQueueCount=${view?.state?.queue?.size}," +
                    "exactQueueEqual=${view?.state?.queue == local.queue}," +
                    "metadataEqual=${view?.queueIntents?.state == PlaybackService.exportLocalQueueIntentState()}," +
                    "serverPlaying=${view?.state?.playing},projectedCanQueueIntent=${projected.canQueueIntent}," +
                    "publishedBindingCurrent=${projected.queueIntentBinding == state.queueIntentBinding}," +
                    "publishedKeyCurrent=${projected.interactionKey === PlaybackService.interactionKey()}," +
                    "preparing=${model.state.value.preparing},settled=${settled(owner)}"
            }
            fun search(name: String) {
                compose.runOnIdle { model.switchTab(Tab.SEARCH); model.search(name) }
                compose.waitUntil(8_000) {
                    compose.onAllNodesWithContentDescription("More actions for $name").fetchSemanticsNodes().isNotEmpty()
                }
            }
            fun more(name: String, action: String) {
                compose.onNodeWithContentDescription("More actions for $name").performClick()
                compose.onNodeWithText(action).performScrollTo().performClick()
            }
            fun initialAction(name: String, label: String) {
                search(name)
                // Setup only: terminal coordinator settlement can precede the real
                // Connection publisher. Let its current key/binding reach the host BEFORE
                // opening this fresh menu. Never refresh a captured menu or retry a refusal.
                // In particular, do NOT put this admission-readiness wait on post-fault High.
                await("SETUP: current Connection key/binding published before More $label", details = diagnostic) {
                    val state = owner.state.value
                    val projected = connected.state.value
                    val local = PlaybackService.exportLocalSnapshot()
                    state.connected && state.recovery == PlaybackConnectionRecovery.NONE && settled(owner) &&
                        !model.state.value.preparing &&
                        (local.queue.isEmpty() && receipts.isEmpty() ||
                            state.canQueueIntent && projected.canQueueIntent && projected.queueIntentBinding != null &&
                            projected.queueIntentBinding == state.queueIntentBinding &&
                            projected.interactionKey === PlaybackService.interactionKey() && projected.interactionKey != null)
                }
                val count = receipts.size
                val queueCount = PlaybackService.exportLocalSnapshot().queue.size
                if (count == 0) {
                    // Explicit public VM track selection establishes exactly A. Actual catalog
                    // More Play now correctly expands its album context (six fixture tracks),
                    // so it is not a single-row setup oracle. Recovery below still uses ONLY
                    // actual fresh mini/More controls, never a replacement helper.
                    compose.runOnIdle { model.queueIntentFrom(client,
                        LibraryItem("track", broker.fixture.tracks.getValue(name), name, hasAudio = true),
                        PlaybackListAction.PLAY_NOW) }
                } else more(name, label)
                await("SETUP: initial $label has a terminal receipt", details = diagnostic) { receipts.size == count + 1 }
                val receipt = receipts.last()
                val result = receipt.queueIntentResult
                val installed = PlaybackService.exportLocalSnapshot()
                record("setup-$name-status=${receipt.status}-outcome=${result?.outcome ?: "absent"}" +
                    "-acceptedCount=${result?.acceptedCount ?: 0}-queueCount=${installed.queue.size}")
                assertNotNull("SETUP: actual More action must have a terminal queue receipt; ${diagnostic()}", result)
                val accepted = requireNotNull(result)
                assertEquals("SETUP: actual More $label must genuinely accept its requested operation; ${diagnostic()}",
                    if (count == 0) "playing" else "queued", accepted.outcome)
                assertEquals("SETUP: actual More $label accepts exactly one row", 1, accepted.acceptedCount)
                assertEquals("SETUP: actual More $label has truthful playback outcome",
                    if (count == 0) "started" else "unchanged", accepted.playback)
                assertEquals("SETUP: actual More $label installs exactly one occurrence", queueCount + 1, installed.queue.size)
                await("SETUP: accepted queue publication and ACK settle", details = diagnostic) { settled(owner) }
            }
            val initialSamples = PreparedTransportMedia.samples.get()
            initialAction("A", "Play now")
            await("SETUP: A really plays with position and consumed PCM") {
                raw.isPlaying && raw.currentPosition > 50 && PreparedTransportMedia.samples.get() > initialSamples
            }
            assertTrue("SETUP: foreground allowed", (field(actual, "foreground") as PlaybackForegroundNotification).foregroundConfirmed)
            assertTrue("SETUP: focus granted", (field(actual, "audioFocus") as PlaybackAudioFocus).isGranted)
            initialAction("H1", "Queue High")
            initialAction("C1", "Queue Low")
            initialAction("C2", "Queue Low")
            // Resolve the row before injecting the fault, but open NO menu/capture NO held key.
            // The recovery More action below is a genuinely fresh host capture.
            if (queueHighFirst || heldMore) search("B1")
            await("SETUP: full playing queue and terminal metadata publication settle", details = diagnostic) {
                val snapshot = PlaybackService.exportLocalSnapshot()
                val view = owner.state.value.view
                raw.isPlaying && settled(owner) && snapshot.queue.size == 4 && view != null &&
                    view.state?.queue == snapshot.queue && view.state?.playing == true &&
                    view.queueIntents?.state == PlaybackService.exportLocalQueueIntentState()
            }
            val before = PlaybackService.exportLocalSnapshot()
            val metadata = requireNotNull(PlaybackService.exportLocalQueueIntentState())
            val binding = requireNotNull(owner.state.value.view?.queueIntents?.binding)
            val deviceId = owner.state.value.deviceId
            val epoch = requireNotNull(owner.state.value.view).epoch
            assertTrue("SETUP: exact requested A/High/Low queue", before.queue.map { it.id } ==
                listOf("A", "H1", "C1", "C2").map { broker.fixture.tracks.getValue(it) })
            assertEquals(0, before.position)
            assertNotNull("SETUP: real current history event", before.occurrence)
            assertTrue("SETUP: pending H1", metadata.highKeys == listOf(before.queue[1].key))
            assertTrue("SETUP: requested origins", metadata.origins.map { it.intent } == listOf("now", "high", "low", "low"))
            assertTrue("SETUP: this registered phone is the owner", deviceId == owner.state.value.activeDeviceId)
            val independentBefore = runBlocking { api.view() }
            assertTrue("SETUP: independent authenticated observer confirms exact queue", independentBefore.state?.queue == before.queue)
            assertTrue("SETUP: independent observer confirms settled owner", independentBefore.activeDeviceId == deviceId &&
                independentBefore.epoch == epoch && independentBefore.commands.none { it.status == "pending" })
            val attempts = runBlocking { broker.metrics() }
            samplesAtFault = PreparedTransportMedia.samples.get()
            record("A-pcm-position-high-low-terminal-settled")

            // Despite its name this selector matches the next ordinary playing POST poll.
            // No musical command follows arm; read-only settled barriers plus actual request
            // witness distinguish this from lost queue/activation/command ACK responses.
            assertTrue("SETUP: no unresolved action at fault arm", settled(owner))
            if (heldMore) compose.onNodeWithContentDescription("More actions for B1").performClick()
            val renderersBeforeFault = renderers()
            val armedAt = System.nanoTime()
            runBlocking { broker.armQueueAdmissionResponse("queue-start", if (shortHold) "hold" else "drop") }
            await("SETUP: proxy fully received real Go HTTP 200 before dropping it") {
                runBlocking { broker.pauseResponseStatus() }?.get("accepted")?.jsonPrimitive?.boolean == true
            }
            val witness = requireNotNull(runBlocking { broker.pauseResponseStatus() })
            assertEquals(200, witness.getValue("status").jsonPrimitive.int)
            assertEquals("poll", witness.getValue("requestAction").jsonPrimitive.content)
            assertFalse(witness.getValue("requestHasCommand").jsonPrimitive.boolean)
            assertTrue(witness.getValue("requestPlaying").jsonPrimitive.boolean)
            assertEquals(0, witness.getValue("requestAckCount").jsonPrimitive.int)
            assertEquals(0, witness.getValue("responseDirectiveCount").jsonPrimitive.int)
            assertEquals(epoch, witness.getValue("requestEpoch").jsonPrimitive.long)
            assertEquals(epoch, witness.getValue("responseEpoch").jsonPrimitive.long)
            val acceptedSequence = witness.getValue("stateSeq").jsonPrimitive.long
            record("ordinary-playing-poll-accepted200-seq=$acceptedSequence-no-command-no-ack-${if (shortHold) "held" else "dropped"}")
            val independentAccepted = runBlocking { api.view() }
            assertTrue("Real observer confirms accepted publication sequence", independentAccepted.stateSeq >= acceptedSequence)
            assertTrue("Real observer confirms unchanged owner/epoch", independentAccepted.activeDeviceId == deviceId && independentAccepted.epoch == epoch)
            assertTrue("Real observer confirms queue/metadata", independentAccepted.state?.queue == before.queue &&
                independentAccepted.queueIntents?.state == metadata)
            assertTrue("No command uncertainty can excuse this ordinary heartbeat case",
                independentAccepted.commands.none { it.status == "pending" } && independentAccepted.transfer == null)

            if (shortHold) {
                // Bound the entire arm-to-release interval below the production 4s request
                // deadline, not merely below the fixture's much longer emergency hold limit.
                assertTrue("Short hold released before request deadline", System.nanoTime() - armedAt < TimeUnit.SECONDS.toNanos(3))
                runBlocking { broker.releasePauseResponse() }
                await("Real held response consumed while original playback continues") {
                    (owner.state.value.view?.stateSeq ?: 0) >= acceptedSequence &&
                        raw.isPlaying && PreparedTransportMedia.samples.get() > samplesAtFault && settled(owner)
                }
                assertEquals("Short response delay does not create a new start", renderersBeforeFault, renderers())
                assertIdentity(before, PlaybackService.exportLocalSnapshot())
                assertTrue(PlaybackService.exportLocalQueueIntentState() == metadata)
                assertNoRecoveryWrites(attempts, runBlocking { broker.metrics() })
                record("short-hold-original-output-continues-no-new-start")
                return
            }

            await("Dropped response conservatively fences local output") {
                !raw.isPlaying && !connected.state.value.isPlaying && !connected.state.value.playWhenReady &&
                    raw.playbackState == Player.STATE_IDLE && field(actual, "authority") == null
            }
            val frozen = PlaybackService.exportLocalSnapshot()
            val frozenPosition = raw.currentPosition
            val silentSamples = PreparedTransportMedia.samples.get()
            val silentRenderers = renderers()
            assertIdentity(before, frozen)
            assertTrue("Stop retains elapsed progress rather than resetting", frozen.elapsed >= before.elapsed && frozen.elapsed > 0)
            assertTrue("Stop retains already observed history progress", requireNotNull(frozen.occurrence).progress >= requireNotNull(before.occurrence).progress)
            assertTrue("No metadata edit on suspension", PlaybackService.exportLocalQueueIntentState() == metadata)
            record("local-fenced-idle-retained")
            await("Fresh successful own poll recovers same owner/epoch without an explicit retry", 6_000) {
                val state = owner.state.value
                state.connected && state.recovery == PlaybackConnectionRecovery.NONE &&
                    state.view?.epoch == epoch && state.activeDeviceId == deviceId &&
                    field(owner, "uncertainConnection") == false && settled(owner)
            }
            record("fresh-own-poll-connected-same-epoch")
            fun silent() {
                assertFalse("Healthy observations never resume", raw.isPlaying)
                assertFalse("No optimistic Playing projection", connected.state.value.isPlaying)
                assertEquals("No passive PCM", silentSamples, PreparedTransportMedia.samples.get())
                assertEquals("No passive position advance", frozenPosition, raw.currentPosition)
                assertEquals("No passive prepare/seek/start", silentRenderers, renderers())
                val current = PlaybackService.exportLocalSnapshot()
                assertIdentity(frozen, current)
                assertEquals("Retained exact elapsed", frozen.elapsed, current.elapsed, 0.0)
                assertTrue("Retained full history/progress/reported state", frozen.occurrence == current.occurrence)
                assertTrue("Retained exact metadata", PlaybackService.exportLocalQueueIntentState() == metadata)
                assertTrue("No focus after suspension", !(field(actual, "audioFocus") as PlaybackAudioFocus).isGranted)
                assertTrue("No foreground after suspension", !(field(actual, "foreground") as PlaybackForegroundNotification).foregroundConfirmed)
            }
            // At least two completed successful own polls AND two real heartbeat periods.
            // Read-only reference barrier: no forcing refresh/retry, permit or activatedEpoch.
            var lastView = owner.state.value.view
            var successfulPolls = 0
            val quietSince = System.nanoTime()
            await("Two healthy same-owner heartbeats stay silent", 6_000) {
                silent()
                val state = owner.state.value
                assertTrue("Recovery must not change selected registration", state.deviceId == deviceId && state.activeDeviceId == deviceId)
                assertEquals("Recovery must not change epoch", epoch, requireNotNull(state.view).epoch)
                if (state.connected && state.recovery == PlaybackConnectionRecovery.NONE && state.view !== lastView) {
                    successfulPolls++; lastView = state.view
                }
                successfulPolls >= 2 && System.nanoTime() - quietSince >= TimeUnit.MILLISECONDS.toNanos(2_100)
            }
            assertTrue("Accepted semantic binding is unchanged by reconnection", owner.state.value.view?.queueIntents?.binding == binding)
            assertNoRecoveryWrites(attempts, runBlocking { broker.metrics() })
            record("two-healthy-heartbeats-flat-pcm-position")

            if (heldMore) {
                // A menu may close when its captured lifetime retires. If retained visually,
                // dispatch its ORIGINAL action once; never reopen/refresh its captured binding.
                if (compose.onAllNodesWithText("Queue High").fetchSemanticsNodes().isNotEmpty())
                    compose.onNodeWithText("Queue High").performScrollTo().performClick()
                val observedAt = System.nanoTime()
                await("Old More dispatch settles without adopting fresh readiness") {
                    silent()
                    System.nanoTime() - observedAt >= TimeUnit.SECONDS.toNanos(1) && settled(owner) && !model.state.value.preparing
                }
                assertNoRecoveryWrites(attempts, runBlocking { broker.metrics() })
                record("old-more-remains-retired-after-healthy-polls")
                return
            }

            var resumeSnapshot = frozen
            var resumeMetadata = metadata
            if (queueHighFirst) {
                val count = receipts.size
                more("B1", "Queue High")
                await("Fresh actual More High must return a bounded truthful result; ${diagnostic()}") { receipts.size == count + 1 }
                val receipt = receipts.last()
                val result = receipt.queueIntentResult
                if (result == null || result.outcome != "queued") {
                    // A real remaining blocker may legitimately refuse elsewhere. Here the
                    // same-owner healthy/no-pending preconditions were independently proved.
                    // Capability-upgrade advice or a no-op is product RED, never accepted as recovery.
                    val feedback = receipt.userMessage
                    val visible = compose.onAllNodesWithText(feedback).fetchSemanticsNodes().isNotEmpty()
                    fail("BUG-103 fresh actual More High refused: status=${receipt.status}," +
                        "nonemptyFeedback=${feedback.isNotBlank()},hostFeedbackPresent=$visible," +
                        "unsupported=${receipt.status == PlaybackActionStatus.UNSUPPORTED}; ${diagnostic()}")
                }
                val accepted = requireNotNull(result)
                await("Fresh High terminal publication settles") { settled(owner) }
                val queued = PlaybackService.exportLocalSnapshot()
                val queuedMetadata = requireNotNull(PlaybackService.exportLocalQueueIntentState())
                accepted.validated(queued, queuedMetadata, "ok")
                assertEquals(1, accepted.acceptedCount)
                assertEquals("high", accepted.placement)
                assertEquals("unchanged", accepted.playback)
                assertNull(accepted.playbackKey)
                assertTrue("High FIFO queue is A/H1/B1/C1/C2", queued.queue.map { it.id } ==
                    listOf("A", "H1", "B1", "C1", "C2").map { broker.fixture.tracks.getValue(it) })
                assertTrue("All old keys, baseline order and track metadata survive High",
                    queued.queue.filter { it.key != queued.queue[2].key } == frozen.queue)
                assertSameCurrent(frozen, queued)
                assertEquals(frozen.elapsed, queued.elapsed, 0.0)
                assertTrue("High preserves full history including progress/reported", frozen.occurrence == queued.occurrence)
                assertTrue("High appends pending membership", queuedMetadata.highKeys == metadata.highKeys + queued.queue[2].key)
                assertTrue("High preserves old requested origins", queuedMetadata.origins.filter { it.key != queued.queue[2].key } == metadata.origins)
                assertEquals("high", queuedMetadata.origins.single { it.key == queued.queue[2].key }.intent)
                assertTrue("Same service receiver incarnation", metadata.receiverId == queuedMetadata.receiverId)
                assertTrue("High commits one newer semantic revision", queuedMetadata.revision > metadata.revision)
                assertEquals(metadata.provenance, queuedMetadata.provenance)
                assertFalse("Unchanged-current High cannot start A", raw.isPlaying)
                assertFalse(queued.playing)
                assertTrue("Broker confirms High paused", owner.state.value.view?.state?.playing == false)
                assertTrue("Queue-only recovery does not request focus", !(field(actual, "audioFocus") as PlaybackAudioFocus).isGranted)
                assertTrue("Queue-only recovery does not promote foreground", !(field(actual, "foreground") as PlaybackForegroundNotification).foregroundConfirmed)
                assertEquals(silentSamples, PreparedTransportMedia.samples.get())
                assertEquals(frozenPosition, raw.currentPosition)
                assertEquals("Queue-only recovery cannot prepare, seek or start", silentRenderers, renderers())
                assertNoRecoveryWrites(attempts, runBlocking { broker.metrics() })
                resumeSnapshot = queued; resumeMetadata = queuedMetadata
                record("fresh-more-high-queued-one-paused-unchanged")
            }
            await("Actual mini projects paused prepared A") { !connected.state.value.playWhenReady }
            val resumeSequence = requireNotNull(owner.state.value.view).stateSeq
            compose.onNodeWithContentDescription("Play").assertIsEnabled().performClick()
            // Exactly ONE fresh actual mini tap; no direct resume/replace helper or click loop.
            await("BUG-103 fresh actual mini Play must progress retained A once", 6_000, diagnostic) {
                raw.isPlaying && connected.state.value.isPlaying && raw.currentPosition > frozenPosition + 50 &&
                    PreparedTransportMedia.samples.get() > silentSamples
            }
            val resumed = PlaybackService.exportLocalSnapshot()
            assertIdentity(resumeSnapshot, resumed)
            assertTrue("Resume keeps history progress monotonic", requireNotNull(resumed.occurrence).progress >= requireNotNull(resumeSnapshot.occurrence).progress)
            assertTrue("Resume preserves priority metadata exactly", PlaybackService.exportLocalQueueIntentState() == resumeMetadata)
            assertEquals("Exactly one new renderer start", silentRenderers.starts + 1, PreparedTransportMedia.starts.get())
            await("A new resumed playing owner publication settles") {
                val view = owner.state.value.view
                view != null && view.stateSeq > resumeSequence && view.state?.playing == true && settled(owner)
            }
            assertEquals("No replayed start during terminal publication", silentRenderers.starts + 1, PreparedTransportMedia.starts.get())
            assertTrue("Client/session/owner/service/player identities survive recovery",
                activeGraph.client === client && client.currentSession() === authenticatedSession &&
                    activeGraph.playbackCoordinator === owner && field(actual, "player") === raw &&
                    owner.state.value.deviceId == deviceId && owner.state.value.activeDeviceId == deviceId)
            assertEquals(epoch, requireNotNull(owner.state.value.view).epoch)
            val finalView = runBlocking { api.view() }
            assertTrue("Observer confirms recovered exact playing queue", finalView.state?.playing == true && finalView.state?.queue == resumed.queue)
            assertTrue("Observer confirms original owner/epoch/receiver", finalView.activeDeviceId == deviceId &&
                finalView.epoch == epoch && finalView.queueIntents?.state == resumeMetadata)
            assertNoRecoveryWrites(attempts, runBlocking { broker.metrics() })
            assertTrue("Same-event Resume cannot duplicate history POST", runBlocking { broker.metrics() }
                .getValue("historyWrites").jsonPrimitive.long <= 1L)
            record("fresh-mini-play-one-start-real-progress-pcm")
        } catch (caught: Throwable) {
            failure = caught
            System.err.println("BUG-103 phases=${trace.joinToString(",")}; ${diagnostic()}")
            throw caught
        } finally {
            cleanup(failure,
                { compose.runOnIdle { shown.value = null } },
                { models.clear() },
                { connection?.release() ?: controller?.release() },
                { graph?.clear() },
                { service?.destroy() },
                { coordinator?.close()?.let { job -> await("Owned coordinator disposal completes", 5_000) { job.isCompleted } } },
                { shadowOf(Looper.getMainLooper()).idle() },
                { observerClient?.let(::closeClient) },
                { graphClient?.let(::closeClient) },
                { broker.close() })
        }
    }

    /** No test writes these internals. Empty outgoing ACK buffers alone are insufficient:
     * require terminal command/queue/admission AND accepted matching server metadata too. */
    private fun settled(owner: PlaybackCoordinator): Boolean {
        val view = owner.state.value.view ?: return false
        return field(owner, "pendingCommand") == null && field(owner, "queueExecution") == null &&
            field(owner, "playAdmission") == null && (field(owner, "acks") as Collection<*>).isEmpty() &&
            field(owner, "ackState") == null && field(owner, "ackQueueIntentState") == null &&
            view.commands.none { it.status == "pending" } && view.transfer == null && view.quarantineMs == 0L
    }

    private fun assertSameCurrent(expected: PlaybackSnapshot, actual: PlaybackSnapshot) {
        assertEquals("Exact current index", expected.position, actual.position)
        assertTrue("Exact current occurrence", expected.queue[expected.position] == actual.queue[actual.position])
        assertTrue("Same history event/key/track", expected.occurrence?.let { old -> actual.occurrence?.let {
            it.eventId == old.eventId && it.key == old.key && it.trackId == old.trackId && (!old.reported || it.reported)
        } } == true)
        assertTrue("Retained context", expected.context == actual.context)
        assertEquals(expected.shuffle, actual.shuffle)
        assertEquals(expected.repeat, actual.repeat)
        assertEquals(expected.truncated, actual.truncated)
    }

    private fun assertIdentity(expected: PlaybackSnapshot, actual: PlaybackSnapshot) {
        assertTrue("Exact queue keys, traversal, baseline order and all metadata", expected.queue == actual.queue)
        assertSameCurrent(expected, actual)
    }

    private fun assertNoRecoveryWrites(before: JsonObject, after: JsonObject) {
        for (key in listOf("registrations", "claims", "transfers", "collectionPlaybackReads"))
            assertEquals("No recovery $key", before.getValue(key), after.getValue(key))
    }

    private fun await(label: String, timeout: Long = 6_000, details: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout)
        while (true) {
            if (condition()) return
            if (System.nanoTime() >= deadline) fail("$label; ${details()}")
            shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
            Thread.sleep(5)
        }
    }

    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(target)

    private fun closeClient(client: SpofifyClient) {
        client.httpClient.dispatcher.cancelAll()
        client.clearSession()
        client.httpClient.connectionPool.evictAll()
        client.httpClient.dispatcher.executorService.shutdown()
        assertTrue("Owned HTTP executor closes", client.httpClient.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS))
    }

    /** Always attempt every owned disposal, retaining the primary RED and cleanup failures. */
    private fun cleanup(primary: Throwable?, vararg actions: () -> Unit) {
        var failure = primary
        actions.forEach { action ->
            try { action() } catch (caught: Throwable) {
                if (failure == null) failure = caught else failure!!.addSuppressed(caught)
            }
        }
        if (primary == null) failure?.let { throw it }
    }
}
