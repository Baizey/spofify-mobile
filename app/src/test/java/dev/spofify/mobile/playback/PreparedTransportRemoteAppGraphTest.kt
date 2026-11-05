package dev.spofify.mobile.playback

import android.media.AudioManager
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import dev.spofify.mobile.LibraryViewModel
import dev.spofify.mobile.SpofifyApplication
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.PlaybackDevicesApi
import dev.spofify.mobile.core.PlaybackDevicesView
import dev.spofify.mobile.core.PlaybackSnapshot
import dev.spofify.mobile.feature.player.NativeMiniPlayer
import dev.spofify.mobile.feature.player.NativeNowPlaying
import dev.spofify.mobile.testing.RealBrokerIntegration
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.InputStream
import java.net.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Opt-in, independently provisioned REAL pinned website receiver companion.
 * Desktop HTMLMediaElement decoding/currentTime is NOT native Android/acoustic approval.
 * No mocked ACK, player execution, registration callback or remote playback state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SpofifyApplication::class, qualifiers = "w400dp-h850dp-mdpi",
    shadows = [PreparedTransportMedia::class], instrumentedPackages = ["androidx.media3.exoplayer"])
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(UnstableApi::class)
@Category(RealBrokerIntegration::class)
class PreparedTransportRemoteAppGraphTest {
    @get:Rule val compose = createComposeRule()
    private var failureDiagnostic: () -> String = { "setup incomplete" }

    @Test fun realMiniAndFullPauseResumeExecuteOnSelectedWebsiteWithoutLocalAudioOrHistory() = journey(false)

    /** The receiver's held HTTP response is NOT the phone's pending action. Go already
     * accepted the atomic paused snapshot + execution ACK. This regression explicitly
     * distinguishes that boundary rather than claiming it reproduces pending admission.
     */
    @Test fun heldAcceptedWebsitePauseAckStillAllowsPhoneToObserveTerminalPauseAndFreshResume() = journey(true)

    @Test fun realBidirectionalHandoffPreservesPriorityCurrentAndHistoryAndExpiresOldLocalRefusal() =
        journey(false, handoff = true)

    private fun journey(holdReceiverResponse: Boolean, handoff: Boolean = false) {
        val pair = WebsiteAndBroker.start()
        val app = ApplicationProvider.getApplicationContext<SpofifyApplication>()
        val models = ViewModelStore()
        var graph: dev.spofify.mobile.AppGraph? = null
        var service: org.robolectric.android.controller.ServiceController<PlaybackService>? = null
        var connection: PlaybackConnection? = null
        var controller: MediaController? = null
        var primary: Throwable? = null
        try {
            // Real browser login + ONE actual DOM Play happen before phone registration.
            val started = pair.startWebsite()
            val browserOwner = started.getValue("measurement").jsonObject
                .getValue("after").jsonObject.getValue("deviceId").jsonPrimitive.content
            app.graph.clear()
            val actualGraph = runBlocking { pair.fixture.authenticatedGraph(app) }.also { graph = it }
            SpofifyApplication::class.java.getDeclaredField("graph").apply { isAccessible = true; set(app, actualGraph) }
            val client = requireNotNull(actualGraph.client)
            val api = PlaybackDevicesApi(client)
            runBlocking { api.probeQueueIntents() }
            service = Robolectric.buildService(PlaybackService::class.java).create()
            val actual = service.get()
            val owner = requireNotNull(actualGraph.playbackCoordinator)
            await("phone service registers without selecting or importing remote output") {
                owner.state.value.connected && owner.state.value.activeDeviceId == browserOwner
            }
            val raw = field(actual, "player") as ExoPlayer
            val activationFacts = ArrayDeque<String>()
            if (handoff) {
                // Read-only ingress observer: forward to the exact AppGraph-created endpoint,
                // never replace state, authority, execution, results, or service start guards.
                val endpointField = owner.javaClass.getDeclaredField("endpoint").apply { isAccessible = true }
                val actualEndpoint = endpointField.get(owner) as dev.spofify.mobile.feature.devices.NativePlaybackEndpoint
                assertTrue(actualEndpoint is Media3PlaybackEndpoint)
                endpointField.set(owner, object : dev.spofify.mobile.feature.devices.NativePlaybackEndpoint by actualEndpoint {
                    override suspend fun activate(snapshot: PlaybackSnapshot, metadata: dev.spofify.mobile.core.QueueIntentState?,
                        authority: dev.spofify.mobile.feature.devices.PlaybackAuthority): dev.spofify.mobile.feature.devices.PlaybackEndpointResult {
                        fun value(name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
                        if (activationFacts.size == 4) activationFacts.removeFirst()
                        activationFacts.addLast("directivePlaying=${owner.state.value.view?.directives?.singleOrNull { it.type == "activate" }?.state?.playing} " +
                            "snapshotPlaying=${snapshot.playing} admission=${value("playAdmission") != null} " +
                            "uncertain=${value("uncertainConnection")} recoverPaused=${value("recoverPaused")} " +
                            "sourcePresent=${!owner.state.value.view?.transfer?.sourceDeviceId.isNullOrEmpty()} authorityValid=${authority.isValid()}")
                        return actualEndpoint.activate(snapshot, metadata, authority)
                    }
                })
            }
            val events = ArrayDeque<String>()
            fun record(value: String) { if (events.size == 32) events.removeFirst(); events.addLast(value) }
            raw.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) { record("state=$state pos=${raw.currentPosition}") }
                override fun onIsPlayingChanged(playing: Boolean) { record("playing=$playing pos=${raw.currentPosition}") }
            })
            failureDiagnostic = {
                val state = owner.state.value
                val local = PlaybackService.exportLocalSnapshot()
                val metadata = state.view?.queueIntents?.state
                val metadataValid = metadata?.let { candidate ->
                    runCatching { candidate.validated(requireNotNull(state.view?.state)) }.isSuccess
                }
                val protocol = runCatching { pair.control("trace") }.getOrNull()
                val handoffStatus = if (handoff) runCatching { pair.control("handoff-status") }.getOrNull() else null
                "rawState=${raw.playbackState} playing=${raw.isPlaying} intent=${raw.playWhenReady} pos=${raw.currentPosition} " +
                    "playerError=${raw.playerError?.errorCode} localQueue=${local.queue.size} localError=${local.error}; " +
                    "activePhone=${state.activeDeviceId == state.deviceId} activeBrowser=${state.activeDeviceId == browserOwner} " +
                    "exclusive=${state.exclusiveOwner} recovery=${state.recovery} selectionPending=${state.pendingSelection != null} " +
                    "epoch=${state.view?.epoch} pendingCommand=${pending(owner) != null} " +
                    "transfer=${state.view?.transfer?.phase} viewPlaying=${state.view?.state?.playing} " +
                    "focus=${(field(actual, "audioFocus") as? PlaybackAudioFocus)?.isGranted} " +
                    "fgs=${(field(actual, "foreground") as? PlaybackForegroundNotification)?.foregroundConfirmed} " +
                    "metadataPresent=${metadata != null} metadataRevision=${metadata?.revision} metadataValid=$metadataValid " +
                    "outcomes=${state.view?.commands?.map { it.status }}; events=$events; activation=$activationFacts; " +
                    "handoff=$handoffStatus; protocol=$protocol"
            }
            val samplesBefore = PreparedTransportMedia.samples.get()
            val session = field(actual, "session") as MediaSession
            val future = MediaController.Builder(actual, session.token).buildAsync()
            await("real MediaController connects") { future.isDone }
            val bound = future.get(1, TimeUnit.SECONDS).also { controller = it }
            val connected = PlaybackConnection(app).also {
                PlaybackConnection::class.java.getDeclaredField("controller").apply { isAccessible = true; set(it, bound) }
                val listener = PlaybackConnection::class.java.getDeclaredField("listener").let { f ->
                    f.isAccessible = true; f.get(it) as Player.Listener
                }
                bound.addListener(listener)
                it.connect()
            }.also { connection = it }
            val model = LibraryViewModel(app, actualGraph, connected).also { models.put("remote-transport", it) }
            await("real ViewModel establishes explicit-origin session and remote projection") {
                model.state.value.session != null && !model.state.value.busy && connected.state.value.item != null &&
                    connected.state.value.remoteSnapshot != null && connected.state.value.isPlaying
            }
            val full = mutableStateOf(false)
            compose.setContent {
                val state by connected.state.collectAsState()
                MaterialTheme {
                    if (full.value) NativeNowPlaying(state, connected, null, false, false, {}, {}, {}, {},
                        artwork = { _, modifier -> Box(modifier) })
                    else NativeMiniPlayer(state, connected, {}, artwork = { _, modifier -> Box(modifier) })
                }
            }
            if (handoff) {
                // Real ViewModel queue intents give the handoff a nonempty pending High
                // and a distinct Low origin. The receiver, not this test, assigns keys.
                for ((name, action) in listOf("H1" to PlaybackListAction.QUEUE_HIGH, "B1" to PlaybackListAction.QUEUE_LOW)) {
                    val count = requireNotNull(runBlocking { api.view() }.state).queue.size
                    model.queueIntentFrom(client,
                        LibraryItem("track", pair.fixture.tracks.getValue(name), "Synthetic $name", hasAudio = true),
                        action, connected.state.value.interactionKey,
                        expectedBinding = connected.state.value.queueIntentBinding)
                    await("real website commits $name priority intent before handoff") {
                        !model.state.value.preparing && owner.state.value.view?.state?.queue?.size == count + 1 &&
                            connected.state.value.remoteSnapshot?.queue?.size == count + 1 && pending(owner) == null
                    }
                }
            }
            val initial = requireNotNull(runBlocking { api.view() }.state)
            val initialBinding = requireNotNull(runBlocking { api.view() }.queueIntents?.binding)
            assertNotNull("Website actually started an occurrence before phone participation", initial.occurrence)
            assertTrue(pair.progress().getValue("progressed").jsonPrimitive.boolean)
            val phoneId = owner.state.value.deviceId
            val metricsBeforeTransport = requireNotNull(pair.control("metrics"))
            fun assertLocalSilent() {
                assertEquals("Remote output must not install a local media item", 0, raw.mediaItemCount)
                assertFalse("No local decoded output", raw.isPlaying)
                assertFalse("No queued local play intent", raw.playWhenReady)
                assertEquals(samplesBefore, PreparedTransportMedia.samples.get())
                val local = PlaybackService.exportLocalSnapshot()
                assertTrue("Remote projection is not a local queue", local.queue.isEmpty())
                assertNull("Remote listening may not create local history", local.occurrence)
            }
            fun assertIdentity(view: PlaybackDevicesView) {
                val state = requireNotNull(view.state)
                assertEquals(initial.queue, state.queue)
                assertEquals(initial.position, state.position)
                assertEquals(initial.context, state.context)
                assertEquals(initial.shuffle, state.shuffle)
                assertEquals(initial.repeat, state.repeat)
                assertEquals(initial.occurrence?.eventId, state.occurrence?.eventId)
                assertEquals(initial.occurrence?.key, state.occurrence?.key)
                assertEquals(initial.occurrence?.trackId, state.occurrence?.trackId)
                assertEquals(initialBinding, view.queueIntents?.binding)
                assertEquals(browserOwner, view.activeDeviceId)
                assertEquals(phoneId, owner.state.value.deviceId)
                assertEquals("Phone displays, rather than imports, the exact website queue",
                    initial.queue, connected.state.value.remoteSnapshot?.queue)
                val metrics = requireNotNull(pair.control("metrics"))
                for (counter in listOf("registrations", "claims", "transfers", "collectionPlaybackReads")) {
                    assertEquals("Transport must not introduce an extra $counter attempt",
                        metricsBeforeTransport.getValue(counter), metrics.getValue(counter))
                }
                // Website progress can legitimately qualify its own history. The global
                // historyWrites counter cannot attribute that to native output; the raw
                // local occurrence/PCM assertions below establish no local history credit.
                assertLocalSilent()
            }
            for (fullHost in listOf(false, true)) {
                compose.runOnIdle { full.value = fullHost }
                val oldCommands = runBlocking { api.view() }.commands.map { it.commandId }.toSet()
                if (holdReceiverResponse) pair.control("arm", buildJsonObject { put("mode", "hold") })
                compose.onNode(hasContentDescription("Pause") and hasClickAction(), useUnmergedTree = true)
                    .assertIsEnabled().performClick()
                await("phone observes terminal REAL receiver Pause and projects paused transport") {
                    owner.state.value.view?.let { v -> v.state?.playing == false &&
                        v.commands.any { it.commandId !in oldCommands && it.status == "ok" } &&
                        pending(owner) == null } == true &&
                        !connected.state.value.isPlaying && !connected.state.value.playWhenReady
                }
                val pausedView = runBlocking { api.view() }
                val paused = requireNotNull(pausedView.state)
                val pauseMeasurement = pair.progress()
                assertFalse("Actual website time cannot progress while paused", pauseMeasurement.getValue("progressed").jsonPrimitive.boolean)
                val before = pauseMeasurement.getValue("before").jsonObject.getValue("audio").jsonObject
                val after = pauseMeasurement.getValue("after").jsonObject.getValue("audio").jsonObject
                assertTrue(after.getValue("paused").jsonPrimitive.boolean)
                assertEquals(before.getValue("currentTime"), after.getValue("currentTime"))
                assertIdentity(pausedView)
                if (holdReceiverResponse) {
                    val witness = pair.control("status")
                    assertEquals(true, witness?.get("accepted")?.jsonPrimitive?.boolean)
                    assertEquals(200, witness?.get("status")?.jsonPrimitive?.int)
                    assertNull("Held receiver ACK response does not prove the phone remains pending", pending(owner))
                    pair.control("release", buildJsonObject {})
                }
                val pauseCommands = pausedView.commands.map { it.commandId }.toSet()
                // The observed failure had Play only in the unmerged tree. Select the
                // concrete production button with its real OnClick and enabled semantics,
                // not a label-only descendant or a direct controls.toggle() substitute.
                compose.onNode(hasContentDescription("Play") and hasClickAction(), useUnmergedTree = true)
                    .assertIsEnabled().performClick()
                await("fresh app Resume gets REAL website terminal execution ACK") {
                    owner.state.value.view?.let { v -> v.state?.playing == true &&
                        v.commands.any { it.commandId !in pauseCommands && it.status == "ok" } &&
                        pending(owner) == null } == true
                }
                val resumed = runBlocking { api.view() }
                assertIdentity(resumed)
                val measurement = pair.progress()
                assertTrue("REAL HTMLMediaElement time must advance after remote Resume, not just desired playing",
                    measurement.getValue("progressed").jsonPrimitive.boolean)
                val state = requireNotNull(resumed.state)
                assertEquals(paused.occurrence?.eventId, state.occurrence?.eventId)
                assertTrue("Resume keeps retained position", state.elapsed >= paused.elapsed)
            }
            if (handoff) {
                // Supported JVM foreground/focus conditions only, never a synthetic start
                // result. The production service still imports/prepares/starts normally.
                shadowOf(actual.getSystemService(android.app.ActivityManager::class.java)).setProcesses(listOf(
                    android.app.ActivityManager.RunningAppProcessInfo().apply {
                        pid = android.os.Process.myPid()
                        importance = android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                    }))
                shadowOf(actual.getSystemService(AudioManager::class.java))
                    .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                compose.runOnIdle { full.value = false }
                val beforeTransfer = runBlocking { api.view() }
                val metadata = requireNotNull(beforeTransfer.queueIntents?.state)
                assertTrue("Handoff must actually carry pending High", metadata.highKeys.isNotEmpty())
                assertTrue(metadata.origins.any { it.intent == "high" })
                assertTrue(metadata.origins.any { it.intent == "low" })
                fun assertHandoffMusic(view: PlaybackDevicesView) {
                    val state = requireNotNull(view.state)
                    assertEquals(initial.queue, state.queue)
                    assertEquals(initial.position, state.position)
                    assertEquals(initial.context, state.context)
                    assertEquals(initial.shuffle, state.shuffle)
                    assertEquals(initial.repeat, state.repeat)
                    assertEquals(initial.occurrence?.eventId, state.occurrence?.eventId)
                    assertEquals(initial.occurrence?.key, state.occurrence?.key)
                    assertEquals(initial.occurrence?.trackId, state.occurrence?.trackId)
                    val actualMetadata = requireNotNull(view.queueIntents?.state)
                    assertEquals(metadata.origins, actualMetadata.origins)
                    assertEquals(metadata.highKeys, actualMetadata.highKeys)
                    assertEquals(metadata.provenance, actualMetadata.provenance)
                    // Receiver incarnation/revision legitimately change at handoff.
                }
                fun assertBrowserPaused() {
                    val measurement = pair.progress()
                    assertFalse("Released browser must not decode/progress alongside phone",
                        measurement.getValue("progressed").jsonPrimitive.boolean)
                    val before = measurement.getValue("before").jsonObject.getValue("audio").jsonObject
                    val after = measurement.getValue("after").jsonObject.getValue("audio").jsonObject
                    assertTrue(after.getValue("paused").jsonPrimitive.boolean)
                    assertEquals(before.getValue("currentTime"), after.getValue("currentTime"))
                }
                fun clickTransport(label: String) = compose.onNode(
                    hasContentDescription(label) and hasClickAction(), useUnmergedTree = true)
                    .assertIsEnabled().performClick()
                fun settledLocalPause() = await("local Pause publication settles without losing occurrence") {
                    !raw.isPlaying && raw.playbackState == Player.STATE_IDLE && !raw.playWhenReady &&
                        owner.state.value.view?.state?.playing == false && pending(owner) == null &&
                        !connected.state.value.isPlaying && !connected.state.value.playWhenReady
                }
                val priorCommands = beforeTransfer.commands.map { it.commandId }.toSet()
                val priorSamples = PreparedTransportMedia.samples.get()
                val priorStarts = PreparedTransportMedia.starts.get()
                pair.control("arm-handoff", buildJsonObject {})
                owner.select(phoneId) // Actual public output action: prepare -> release -> activate.
                await("REAL old-epoch inbox poll crosses successful release; fresh own poll remains held") {
                    pair.control("handoff-status")?.let { witness ->
                        listOf("capturedTransfer", "sourceReleaseHeld", "oldPollHeld", "releaseAccepted", "epochAdvanced",
                            "oldPollForwarded", "oldPollStaleEpoch", "freshPollHeld").all {
                            witness[it]?.jsonPrimitive?.boolean == true
                        } && witness["oldPollStatus"]?.jsonPrimitive?.int == 409 &&
                            witness["freshPollForwarded"]?.jsonPrimitive?.boolean == false
                    } == true && owner.state.value.view?.epoch == beforeTransfer.epoch + 1
                }
                // The authenticated 409 view can synchronize display/epoch ONLY. The
                // actual fresh owner poll is held before upstream dispatch, so no lease,
                // endpoint activation, PCM, focus or foreground start can come from it.
                assertNull(owner.currentAuthority())
                assertFalse(owner.state.value.exclusiveOwner)
                assertTrue(activationFacts.isEmpty())
                assertFalse(raw.isPlaying); assertFalse(raw.playWhenReady)
                assertEquals(Player.STATE_IDLE, raw.playbackState)
                assertEquals(priorSamples, PreparedTransportMedia.samples.get())
                assertEquals(priorStarts, PreparedTransportMedia.starts.get())
                assertFalse((field(actual, "audioFocus") as PlaybackAudioFocus).isGranted)
                assertFalse((field(actual, "foreground") as PlaybackForegroundNotification).foregroundConfirmed)
                pair.control("release-handoff", buildJsonObject {})
                await("real broker accepts website-to-phone activation and raw output progresses") {
                    owner.state.value.view?.let { v -> v.activeDeviceId == phoneId && v.transfer == null &&
                        v.commands.any { it.commandId !in priorCommands && it.status == "ok" } &&
                        v.state?.playing == true } == true && owner.state.value.pendingSelection == null &&
                        connected.state.value.remoteSnapshot == null &&
                        raw.playbackState == Player.STATE_READY && raw.isPlaying &&
                        raw.currentPosition > requireNotNull(beforeTransfer.state).elapsed * 1000 + 50 &&
                        PreparedTransportMedia.samples.get() > priorSamples
                }
                val imported = runBlocking { api.view() }
                assertEquals(phoneId, imported.activeDeviceId)
                assertEquals(phoneId, imported.queueIntents?.binding?.outputDeviceId)
                assertNotEquals("New native receiver is not the persistent website incarnation",
                    metadata.receiverId, imported.queueIntents?.binding?.receiverId)
                assertHandoffMusic(imported)
                assertEquals(initial.queue[initial.position].id, raw.currentMediaItem?.mediaId)
                assertBrowserPaused()
                clickTransport("Pause")
                settledLocalPause()
                val localPaused = PlaybackService.exportLocalSnapshot()
                val pausedPosition = raw.currentPosition
                val pausedSamples = PreparedTransportMedia.samples.get()
                clickTransport("Play")
                await("actual mini Resume progresses the exact imported phone occurrence") {
                    raw.playbackState == Player.STATE_READY && raw.isPlaying && raw.currentPosition > pausedPosition + 50 &&
                        PreparedTransportMedia.samples.get() > pausedSamples &&
                        owner.state.value.view?.state?.playing == true && pending(owner) == null
                }
                assertHandoffMusic(runBlocking { api.view() })
                assertEquals(localPaused.occurrence?.eventId, PlaybackService.exportLocalSnapshot().occurrence?.eventId)
                assertBrowserPaused()

                pair.control("arm", buildJsonObject { put("mode", "hold") })
                clickTransport("Pause")
                await("real broker accepted local Pause while its response is held") {
                    pair.control("status")?.get("accepted")?.jsonPrimitive?.boolean == true &&
                        !raw.isPlaying && raw.playbackState == Player.STATE_IDLE && !connected.state.value.playWhenReady
                }
                assertNotNull("Local Pause is genuinely pending, unlike the receiver-held remote case", pending(owner))
                assertNull(connected.state.value.error)
                assertNull(connected.state.value.notice)
                clickTransport("Play")
                shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
                assertNotNull("Refusal must be observed before held publication can settle", pending(owner))
                val refusal = requireNotNull(connected.state.value.error ?: connected.state.value.notice)
                compose.onNodeWithText(refusal, useUnmergedTree = true).assertIsDisplayed()
                val notifications = shadowOf(actual.getSystemService(android.app.NotificationManager::class.java))
                await("actually posted notification displays refused local Resume guidance") {
                    notifications.allNotifications.singleOrNull()?.extras
                        ?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() == refusal
                }
                assertFalse("Rejected Resume must not replay during held Pause", raw.isPlaying)
                pair.control("release", buildJsonObject {})
                settledLocalPause()
                val settledPosition = raw.currentPosition
                val settledSamples = PreparedTransportMedia.samples.get()
                val settledStarts = PreparedTransportMedia.starts.get()
                repeat(12) {
                    shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
                    Thread.sleep(10)
                    assertFalse("Settled refusal must not replay before a new output choice", raw.isPlaying)
                    assertFalse(raw.playWhenReady); assertEquals(Player.STATE_IDLE, raw.playbackState)
                    assertEquals(settledPosition, raw.currentPosition)
                    assertEquals(settledSamples, PreparedTransportMedia.samples.get())
                    assertEquals(settledStarts, PreparedTransportMedia.starts.get())
                    assertFalse((field(actual, "audioFocus") as PlaybackAudioFocus).isGranted)
                    assertFalse((field(actual, "foreground") as PlaybackForegroundNotification).foregroundConfirmed)
                }
                val beforeReturn = runBlocking { api.view() }
                assertFalse("Releasing the real response cannot replay the refused click", raw.isPlaying)
                assertFalse(raw.playWhenReady)
                assertHandoffMusic(beforeReturn)
                val returnCommands = beforeReturn.commands.map { it.commandId }.toSet()
                owner.select(browserOwner) // Second and only second deliberate transfer.
                await("phone-to-website paused activation reaches real terminal acceptance") {
                    owner.state.value.view?.let { v -> v.activeDeviceId == browserOwner && v.transfer == null &&
                        v.state?.playing == false && v.commands.any { it.commandId !in returnCommands && it.status == "ok" } } == true &&
                        connected.state.value.remoteSnapshot != null && pending(owner) == null &&
                        owner.state.value.pendingSelection == null
                }
                val returned = runBlocking { api.view() }
                assertHandoffMusic(returned)
                assertEquals(browserOwner, returned.queueIntents?.binding?.outputDeviceId)
                assertTrue("Paused return retains the released current position",
                    requireNotNull(returned.state).elapsed >= requireNotNull(beforeReturn.state).elapsed)
                assertBrowserPaused() // Transfer preserves paused intent; it is NOT Resume.
                await("old local refusal expires on output replacement in player and posted notification") {
                    connected.state.value.error != refusal && connected.state.value.notice != refusal &&
                        notifications.allNotifications.singleOrNull()?.extras
                            ?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.let { it.toString() != refusal } == true
                }
                compose.onNodeWithText(refusal, useUnmergedTree = true).assertDoesNotExist()
                assertFalse(raw.isPlaying)
                assertFalse(raw.playWhenReady)
                assertEquals(Player.STATE_IDLE, raw.playbackState)
                assertFalse((field(actual, "audioFocus") as PlaybackAudioFocus).isGranted)
                assertFalse((field(actual, "foreground") as PlaybackForegroundNotification).foregroundConfirmed)
                val samplesAtRelease = PreparedTransportMedia.samples.get()
                val resumedCommands = runBlocking { api.view() }.commands.map { it.commandId }.toSet()
                clickTransport("Play") // Fresh remote action, never deferred rejected local Play.
                await("fresh remote Play receives actual website execution ACK after handoff") {
                    owner.state.value.view?.let { v -> v.activeDeviceId == browserOwner && v.state?.playing == true &&
                        v.commands.any { it.commandId !in resumedCommands && it.status == "ok" } && pending(owner) == null } == true
                }
                assertTrue("Real website media time advances after fresh remote Resume",
                    pair.progress().getValue("progressed").jsonPrimitive.boolean)
                assertHandoffMusic(runBlocking { api.view() })
                assertFalse(raw.isPlaying)
                assertFalse(raw.playWhenReady)
                assertEquals(Player.STATE_IDLE, raw.playbackState)
                assertEquals("Released phone consumes no successor PCM", samplesAtRelease, PreparedTransportMedia.samples.get())
                assertFalse((field(actual, "audioFocus") as PlaybackAudioFocus).isGranted)
                assertFalse((field(actual, "foreground") as PlaybackForegroundNotification).foregroundConfirmed)
                val afterMetrics = requireNotNull(pair.control("metrics"))
                for (counter in listOf("registrations", "claims", "collectionPlaybackReads"))
                    assertEquals("Handoff must not add $counter attempts", metricsBeforeTransport.getValue(counter), afterMetrics.getValue(counter))
                assertEquals("Exactly the two selected output transfers, with no retry/fallback",
                    metricsBeforeTransport.getValue("transfers").jsonPrimitive.long + 2,
                    afterMetrics.getValue("transfers").jsonPrimitive.long)
                assertEquals(phoneId, owner.state.value.deviceId)
            }
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            var cleanupFailure: Throwable? = primary
            listOf<() -> Unit>({ pair.releaseIfHeld() }, { models.clear() },
                { connection?.release() ?: controller?.release() }, { graph?.clear() },
                { service?.destroy() }, { pair.close() }).forEach { cleanup ->
                try { cleanup() } catch (failure: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = failure else cleanupFailure!!.addSuppressed(failure)
                }
            }
            if (primary == null) cleanupFailure?.let { throw it }
        }
    }

    private fun await(description: String, condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition() && System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
            Thread.sleep(5)
        }
        if (!condition()) fail("$description; ${failureDiagnostic()}")
    }
    private fun pending(owner: Any): Any? = owner.javaClass.getDeclaredField("pendingCommand")
        .apply { isAccessible = true }.get(owner)
    private fun field(service: PlaybackService, name: String): Any? = PlaybackService::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(service)

    /** Small private pipe bridge, not a fake player/backend. Each launcher owns its own
     * subreaper and scratch. Java NEVER removes scratch or force-kills browser/broker.
     * Secrets exist only in memory and private stdin; no raw error/handshake in assertions.
     */
    private class WebsiteAndBroker private constructor(private val broker: Child, private val website: Child,
        private var handshake: JsonObject?) : AutoCloseable {
        val fixture = PreparedTransportBrokerFixture.fromHandshakeJson(requireNotNull(handshake).toString())
        private val http = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false).callTimeout(3, TimeUnit.SECONDS).build()
        private var held = false
        fun startWebsite(): JsonObject {
            val request = buildJsonObject { put("op", "start"); put("fixture", requireNotNull(handshake)); put("seconds", 240) }
            handshake = null
            return website.request(request, 55).getValue("result").jsonObject
        }
        fun progress() = website.request(buildJsonObject { put("op", "progress"); put("milliseconds", 600) })
            .getValue("result").jsonObject
        fun control(action: String, body: JsonObject? = null): JsonObject? {
            if (action == "arm") held = true
            val request = Request.Builder().url(fixture.server.origin + "/__fixture/" + action)
                .header("X-Fixture-Token", fixture.controlToken)
                .apply { body?.let { post(it.toString().toRequestBody("application/json".toMediaType())) } }.build()
            val result = try {
                http.newCall(request).execute().use { response ->
                    check(response.code == 200) { "Fixture control refused" }
                    val source = requireNotNull(response.body).source()
                    check(!source.request(16_385)) { "Fixture control response bound exceeded" }
                    Json.parseToJsonElement(source.readUtf8()).let { if (it == JsonNull) null else it.jsonObject }
                }
            } catch (_: Exception) { throw IllegalStateException("Private fixture control failed") }
            if (action == "release") held = false
            return result
        }
        fun releaseIfHeld() { if (held) control("release", buildJsonObject {}) }
        override fun close() {
            var failure: Throwable? = null
            for ((child, stop) in listOf(website to "{\"op\":\"stop\"}", broker to "stop")) {
                try { child.close(stop) } catch (caught: Throwable) {
                    if (failure == null) failure = caught else failure.addSuppressed(caught)
                }
            }
            http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
            check(http.dispatcher.executorService.awaitTermination(3, TimeUnit.SECONDS)) { "Fixture HTTP executor remains" }
            failure?.let { throw it }
        }
        companion object {
            fun start(): WebsiteAndBroker {
                for (name in listOf("SPOFIFY_TEST_GO_BROKER_BINARY", "SPOFIFY_TEST_PLAYWRIGHT_MODULE", "SPOFIFY_TEST_CHROMIUM_PATH"))
                    require(!System.getenv(name).isNullOrBlank()) { "$name is required for selected remote real-broker integration" }
                var directory: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
                var tools: Path? = null
                repeat(8) {
                    directory?.let { candidate ->
                        val path = candidate.resolve("tools/testing/prepared-transport-broker")
                        if (Files.isRegularFile(path.resolve("website_receiver.cjs"))) tools = path
                        directory = candidate.parent
                    }
                }
                val root = requireNotNull(tools) { "Prepared transport receiver tools missing" }
                val broker = Child(ProcessBuilder("python3", "-B", root.resolve("broker_fixture.py").toString(), "--seconds", "300").start(), "fixtureCleanup")
                var website: Child? = null
                try {
                    val raw = broker.read(45)
                    val started = Child(ProcessBuilder("node", root.resolve("website_receiver.cjs").toString()).start(), "receiverCleanup").also { website = it }
                    return WebsiteAndBroker(broker, started, raw)
                } catch (_: Exception) {
                    val failure = IllegalStateException("Private real website/broker startup failed")
                    try { website?.close("{\"op\":\"stop\"}") } catch (e: Exception) { failure.addSuppressed(e) }
                    try { broker.close("stop") } catch (e: Exception) { failure.addSuppressed(e) }
                    throw failure
                }
            }
        }
    }

    private class Child(private val process: Process, private val receiptKey: String) {
        private val readers = Executors.newFixedThreadPool(2) { task -> Thread(task, "private-website-fixture-pipe").apply { isDaemon = true } }
        private val stderr = readers.submit<String> {
            val tail = StringBuilder()
            val bytes = ByteArray(4096)
            while (true) {
                val count = process.errorStream.read(bytes)
                if (count < 0) break
                tail.append(String(bytes, 0, count, Charsets.UTF_8))
                if (tail.length > 16_384) tail.delete(0, tail.length - 16_384)
            }
            tail.toString()
        }
        fun read(seconds: Long): JsonObject = try {
            readers.submit<JsonObject> { Json.parseToJsonElement(boundedLine(process.inputStream)).jsonObject }.get(seconds, TimeUnit.SECONDS)
        } catch (_: Exception) { throw IllegalStateException("Private child response missing, malformed or timed out") }
        fun request(body: JsonObject, seconds: Long = 10): JsonObject {
            process.outputStream.write((body.toString() + "\n").toByteArray(Charsets.UTF_8)); process.outputStream.flush()
            val response = read(seconds)
            check(response["ok"]?.jsonPrimitive?.boolean == true) {
                // Only stable receiver classifications; never raw browser messages or input.
                "Real website receiver operation failed: " + response["error"]?.jsonPrimitive?.content?.takeIf { it.matches(Regex("[A-Z_]+")) }
            }
            return response
        }
        fun close(stop: String) {
            if (process.isAlive) {
                runCatching { process.outputStream.write((stop + "\n").toByteArray(Charsets.UTF_8)); process.outputStream.flush() }
                runCatching { process.outputStream.close() }
            }
            check(process.waitFor(20, TimeUnit.SECONDS)) { "Owned child cleanup unverified; launcher remains, scratch preserved" }
            val tail = stderr.get(2, TimeUnit.SECONDS)
            readers.shutdown()
            check(readers.awaitTermination(2, TimeUnit.SECONDS)) { "Owned pipe readers remain" }
            check(process.exitValue() == 0) { "Owned child exited nonzero; cleanup unverified" }
            check(tail.lineSequence().mapNotNull { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
                .any { it[receiptKey]?.jsonPrimitive?.content == "complete" && it["descendants"]?.jsonPrimitive?.content == "ECHILD" }) {
                "Owned child ECHILD/scratch cleanup receipt missing"
            }
        }
        private fun boundedLine(input: InputStream): String {
            val buffer = ByteArray(32_768)
            var length = 0
            while (length < buffer.size) {
                val next = input.read()
                check(next >= 0) { "Private child pipe ended" }
                if (next == '\n'.code) return String(buffer, 0, length, Charsets.UTF_8)
                buffer[length++] = next.toByte()
            }
            error("Private child response exceeds bound")
        }
    }
}
