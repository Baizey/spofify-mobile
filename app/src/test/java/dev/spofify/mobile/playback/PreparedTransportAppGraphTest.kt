package dev.spofify.mobile.playback

import android.media.AudioManager
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import dev.spofify.mobile.AppGraph
import dev.spofify.mobile.LibraryViewModel
import dev.spofify.mobile.LoginStore
import dev.spofify.mobile.RememberedLogin
import dev.spofify.mobile.SpofifyApplication
import dev.spofify.mobile.core.*
import dev.spofify.mobile.feature.player.NativeMiniPlayer
import dev.spofify.mobile.feature.player.NativeNowPlaying
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Public app controls and real ExoPlayer state machine. Initial small loopback fixture is
 * deliberately NOT real-broker conformance evidence; the persistent broker companion supplies
 * that boundary. Synthetic PCM consumption/progress is NOT native/acoustic approval.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SpofifyApplication::class, qualifiers = "w400dp-h850dp-mdpi",
    shadows = [PreparedTransportMedia::class], instrumentedPackages = ["androidx.media3.exoplayer"])
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(UnstableApi::class)
class PreparedTransportAppGraphTest {
    @get:Rule val compose = createComposeRule()
    private val trace = java.util.Collections.synchronizedList(mutableListOf<String>())
    private fun record(value: String) { synchronized(trace) { if (trace.size == 96) trace.removeAt(0); trace += value } }
    private class Sessions : SessionStore {
        private var value: StoredSession? = null
        override fun read() = value
        override fun write(value: StoredSession?) { this.value = value }
    }
    private class Logins : LoginStore {
        override fun read(): RememberedLogin? = null
        override fun write(value: RememberedLogin?) = Unit
    }

    private class Room : Dispatcher() {
        val registrations = AtomicInteger()
        @Volatile var state = PlaybackSnapshot()
        @Volatile var seq = 0L
        @Volatile var registered = false
        @Volatile var holdPause = false
        val pauseAccepted = CountDownLatch(1)
        val releasePause = CountDownLatch(1)
        private fun view() = PlaybackDevicesView(if (registered) "phone" else "",
            if (registered) listOf(PlaybackDevice("phone", "Phone", true)) else emptyList(),
            if (registered) "phone" else "", 1, if (registered) 8000 else 0,
            state = state, stateSeq = seq, stateAgeMs = 0, transfer = null, directives = emptyList(), commands = emptyList())
        private fun response(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
        override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.path == "/api/auth/login") return response(
                """{"accountId":"transport-account","username":"listener","role":"user","csrfToken":"transport-csrf"}""")
                .setHeader("Set-Cookie", "spofify_session=transport-session; Path=/; HttpOnly; Max-Age=3600")
            if (request.path != "/api/me/player") return MockResponse().setResponseCode(404)
            check(request.getHeader("Cookie") == "spofify_session=transport-session")
            if (request.method == "GET") return response(view().toJson().toString())
            check(request.getHeader("X-CSRF-Token") == "transport-csrf")
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            when (body.getValue("action").jsonPrimitive.content) {
                "register" -> { registrations.incrementAndGet(); registered = true }
                "poll" -> body["state"]?.let {
                    state = PlaybackSnapshot.fromJson(it.jsonObject)
                    seq = body.getValue("stateSeq").jsonPrimitive.long
                    if (holdPause && state.queue.isNotEmpty() && !state.playing) {
                        holdPause = false; pauseAccepted.countDown()
                        check(releasePause.await(4, TimeUnit.SECONDS))
                    }
                }
                "leave" -> registered = false
                else -> error("Unexpected player action")
            }
            return response(view().toJson().toString())
        }
    }

    @Test fun settledMiniAndFullPauseResumeProgressTenTimesWithoutReplacingPreparedMusic() = journey(hold = false)
    @Test fun enabledResumeWhilePausePublicationPendingMustGiveActionableFeedbackThenFreshResumeWorks() = journey(hold = true)

    @Test fun fullPlayerPendingResumeRefusalIsVisibleAndFreshResumeWorks() = journey(hold = true, fullHost = true)
    @Test fun actuallyPostedNotificationPendingResumeRefusalIsVisibleAndFreshTokenWorks() = journey(hold = true, notification = true)
    @org.junit.experimental.categories.Category(dev.spofify.mobile.testing.RealBrokerIntegration::class)
    @Test fun persistentRealBrokerSettledCyclesThenPendingPauseResumeUsesActualMiniAndFullControls() = journey(hold = true, realBroker = true)

    @org.junit.experimental.categories.Category(dev.spofify.mobile.testing.RealBrokerIntegration::class)
    @Test fun realBrokerFullPlayerHeldPauseRefusalThenFreshResumeActuallyProgresses() =
        journey(hold = true, realBroker = true, fullHost = true)

    @org.junit.experimental.categories.Category(dev.spofify.mobile.testing.RealBrokerIntegration::class)
    @Test fun realBrokerPostedNotificationHeldPauseRefusalThenFreshResumeActuallyProgresses() =
        journey(hold = true, realBroker = true, notification = true)

    @org.junit.experimental.categories.Category(dev.spofify.mobile.testing.RealBrokerIntegration::class)
    @Test fun realBrokerPausedAAt42LowAppendKeepsHighPendingAndFreshResumeStartsOnlyA() =
        journey(hold = false, realBroker = true, extra = Extra.LOW_COUNTEREXAMPLE)

    @org.junit.experimental.categories.Category(dev.spofify.mobile.testing.RealBrokerIntegration::class)
    @Test fun realBrokerMediaControllerPlayPauseStopAndPostedNotificationResumeActuallyProgress() =
        journey(hold = false, realBroker = true, notification = true, extra = Extra.CONTROLLER)

    @org.junit.experimental.categories.Category(dev.spofify.mobile.testing.RealBrokerIntegration::class)
    @Test fun realBrokerLostAcceptedPauseResponseNeverReplaysOnRetryReconnectNoisyOrLateFocusGain() =
        journey(hold = false, realBroker = true, extra = Extra.DROPPED_PAUSE)

    @org.junit.experimental.categories.Category(dev.spofify.mobile.testing.RealBrokerIntegration::class)
    @Test fun realBrokerFixedSeedBoundedPublicTransportAndQueueOperations() =
        journey(hold = false, realBroker = true, extra = Extra.SEEDED)

    @Test fun pendingResumeRefusalDoesNotSurviveFreshModelPreparedSelectionAndActualProgress() =
        journey(hold = true, extra = Extra.NOTICE_NEW_PLAY)

    @org.junit.experimental.categories.Category(dev.spofify.mobile.testing.RealBrokerIntegration::class)
    @Test fun pendingResumeRefusalExpiresAfterAcceptedPausedLowSuccessorWithoutMaskingQueuedReceipt() =
        journey(hold = true, realBroker = true, fullHost = true, extra = Extra.NOTICE_QUEUE_SUCCESSOR)

    @org.junit.experimental.categories.Category(dev.spofify.mobile.testing.RealBrokerIntegration::class)
    @Test fun realBrokerOneTrackEndedMiniPlayRestartsActualOutputWithItsOwnNewHistoryEvent() =
        journey(hold = false, realBroker = true, extra = Extra.ENDED_RESTART)

    @org.junit.experimental.categories.Category(dev.spofify.mobile.testing.RealBrokerIntegration::class)
    @Test fun realBrokerActualRendererErrorThenFreshMiniRetryProgressesSamePreparedOccurrence() =
        journey(hold = false, realBroker = true, extra = Extra.RENDERER_RETRY)

    @org.junit.experimental.categories.Category(dev.spofify.mobile.testing.RealBrokerIntegration::class)
    @Test fun realBrokerControllerPauseBeforeFirstReadyCancelsStartAndLateReadinessCannotAutoplay() =
        journey(hold = false, realBroker = true, extra = Extra.FIRST_READY_PAUSE)

    @org.junit.experimental.categories.Category(dev.spofify.mobile.testing.RealBrokerIntegration::class)
    @Test fun realBrokerPostedNextWhilePausePendingGetsActionNeutralGuidanceWithoutChangingQueue() =
        journey(hold = true, realBroker = true, notification = true, extra = Extra.NON_PLAY_NOTICE)

    /** Positive callback ingress only: real connected-controller attribution and service
     * callback -> guards/broker/PCM. NOT framework legacy routing, headset hardware or audio. */
    @org.junit.experimental.categories.Category(dev.spofify.mobile.testing.RealBrokerIntegration::class)
    @Test fun realBrokerHeadsetCallbackIngressFromActualConnectedControllerStartsOnceAndDeduplicatesDown() =
        journey(hold = false, realBroker = true, extra = Extra.HEADSET_CALLBACK)

    private enum class Extra { NONE, LOW_COUNTEREXAMPLE, CONTROLLER, DROPPED_PAUSE, SEEDED,
        NOTICE_NEW_PLAY, NOTICE_QUEUE_SUCCESSOR, ENDED_RESTART, RENDERER_RETRY, FIRST_READY_PAUSE, NON_PLAY_NOTICE, HEADSET_CALLBACK }

    private fun journey(hold: Boolean, realBroker: Boolean = false, fullHost: Boolean = false,
        notification: Boolean = false, extra: Extra = Extra.NONE) {
        val broker = if (realBroker) PreparedTransportBrokerProcess.start() else null
        var failure: Throwable? = null
        try { journey(hold, realBroker, broker, fullHost, notification, extra) }
        catch (caught: Throwable) { failure = caught; throw caught }
        finally { cleanup(failure, { broker?.close() }) }
    }

    private fun journey(hold: Boolean, realBroker: Boolean, broker: PreparedTransportBrokerProcess?, fullHost: Boolean,
        notification: Boolean, extra: Extra) {
        val app = ApplicationProvider.getApplicationContext<SpofifyApplication>()
        app.graph.clear()
        val graph = if (broker != null) runBlocking { broker.fixture.authenticatedGraph(app) } else AppGraph(app, Sessions(), Logins())
        SpofifyApplication::class.java.getDeclaredField("graph").apply { isAccessible = true; set(app, graph) }
        val room = Room()
        val web = if (broker == null) MockWebServer().apply { dispatcher = room; start(InetAddress.getByName("127.0.0.1"), 0) } else null
        val models = ViewModelStore()
        var service: org.robolectric.android.controller.ServiceController<PlaybackService>? = null
        var connection: PlaybackConnection? = null
        var mediaController: MediaController? = null
        var failure: Throwable? = null
        try {
            val client = if (broker != null) requireNotNull(graph.client) else graph.configure(ServerAddress.parse("http://127.0.0.1:${requireNotNull(web).port}"))
            if (broker == null) runBlocking { client.login("listener", "synthetic-password") }
            else {
                val api = PlaybackDevicesApi(client)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (runBlocking { api.view() }.quarantineMs > 0 && System.nanoTime() < deadline) Thread.sleep(50)
                assertEquals("Real broker startup drain must end before stable-registration transport test", 0L, runBlocking { api.view() }.quarantineMs)
            }
            val interceptions = PreparedTransportMedia.interceptions.get()
            val samplesBefore = PreparedTransportMedia.samples.get()
            service = Robolectric.buildService(PlaybackService::class.java).create()
            assertEquals("Test source/sink substitution must be installed before investigating transport", interceptions + 1,
                PreparedTransportMedia.interceptions.get())
            val actual = service.get()
            shadowOf(actual.getSystemService(android.app.ActivityManager::class.java)).setProcesses(listOf(
                android.app.ActivityManager.RunningAppProcessInfo().apply {
                    pid = android.os.Process.myPid()
                    importance = android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                }))
            shadowOf(actual.getSystemService(AudioManager::class.java)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            val owner = requireNotNull(graph.playbackCoordinator)
            await("production service registration") { owner.state.value.connected }
            val raw = field(actual, "player") as ExoPlayer
            val discontinuities = AtomicInteger()
            val seeks = AtomicInteger()
            val bufferingTransitions = AtomicInteger()
            raw.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_BUFFERING) bufferingTransitions.incrementAndGet()
                    record("state=$state pos=${raw.currentPosition}")
                }
                override fun onIsPlayingChanged(playing: Boolean) { record("playing=$playing pos=${raw.currentPosition}") }
                override fun onMediaItemTransition(item: androidx.media3.common.MediaItem?, reason: Int) { record("transition=$reason") }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    discontinuities.incrementAndGet()
                    if (reason == Player.DISCONTINUITY_REASON_SEEK) seeks.incrementAndGet()
                    record("discontinuity=$reason ${oldPosition.positionMs}->${newPosition.positionMs}")
                }
            })
            val session = field(actual, "session") as MediaSession
            val future = MediaController.Builder(actual, session.token).buildAsync()
            await("posted MediaController connection") { future.isDone }
            val controller = future.get(1, TimeUnit.SECONDS).also { mediaController = it }
            val connected = PlaybackConnection(actual).also {
                PlaybackConnection::class.java.getDeclaredField("controller").apply { isAccessible = true; set(it, controller) }
                val listener = PlaybackConnection::class.java.getDeclaredField("listener").let { field ->
                    field.isAccessible = true; field.get(it) as Player.Listener
                }
                controller.addListener(listener)
                it.connect()
            }
            connection = connected
            // Observe real immutable service receipts without replacing any execution/admission.
            // Delegation also forwards the real ViewModel's explicit-origin capture unchanged.
            val receipts = mutableListOf<PlaybackActionResult>()
            val observedControls: PlaybackControls = if (extra !in setOf(Extra.LOW_COUNTEREXAMPLE, Extra.NOTICE_QUEUE_SUCCESSOR)) connected else object : PlaybackControls by connected {
                override fun performPreparedAction(queue: dev.spofify.mobile.core.PlaybackQueue, client: SpofifyClient,
                    action: PlaybackListAction, expectedKey: PlaybackInteractionKey?, startTrackId: String?,
                    startEntryId: String?, context: LibraryItem?, isCurrent: () -> Boolean,
                    onResult: (PlaybackActionResult) -> Unit) {
                    connected.performPreparedAction(queue, client, action, expectedKey, startTrackId, startEntryId,
                        context, isCurrent) { result -> receipts += result; onResult(result) }
                }
            }
            val model = LibraryViewModel(app, graph, observedControls).also { models.put("transport", it) }
            await("real ViewModel authenticated origin") { model.state.value.session != null && !model.state.value.busy }
            fun projected() = connected.state.value.interactionKey === PlaybackService.interactionKey() &&
                connected.state.value.isPlaying == raw.isPlaying
            fun actionSettled() = owner.javaClass.getDeclaredField("pendingCommand").let { it.isAccessible = true; it.get(owner) == null }
            val full = mutableStateOf(false)
            compose.setContent {
                val state by connected.state.collectAsState()
                MaterialTheme {
                    if (full.value) NativeNowPlaying(state, connected, null, false, false, {}, {}, {}, {},
                        artwork = { _, modifier -> Box(modifier) })
                    else NativeMiniPlayer(state, connected, {}, artwork = { _, modifier -> Box(modifier) })
                }
            }
            val a = broker?.fixture?.tracks?.getValue("A") ?: "A"
            val c = broker?.fixture?.tracks?.getValue("C1") ?: "C"
            fun track(name: String) = LibraryItem("track", requireNotNull(broker).fixture.tracks.getValue(name),
                "Song $name", hasAudio = true, entryId = "$name-entry")
            val counterexample = extra == Extra.LOW_COUNTEREXAMPLE
            val queue = PlaybackQueue(if (extra == Extra.ENDED_RESTART) listOf(
                LibraryItem("track", a, "Only A", hasAudio = true, entryId = "A-first"))
                else if (counterexample) listOf(track("A"), track("C1"), track("C2")) else listOf(
                LibraryItem("track", a, "Song A", hasAudio = true, entryId = "A-first"),
                LibraryItem("track", a, "Duplicate A", hasAudio = true, entryId = "A-second"),
                LibraryItem("track", c, "Song C", hasAudio = true, entryId = "C")),
                total = if (extra == Extra.ENDED_RESTART) 1 else 3,
                context = LibraryPlaybackContext("playlist", "synthetic", "Synthetic complete context"), revision = 1)
            val initialRendererStarts = PreparedTransportMedia.starts.get()
            if (extra == Extra.FIRST_READY_PAUSE) PreparedTransportMedia.holdReady.set(true)
            model.playPrepared(client, client.currentSession()!!.accountId, queue, null,
                if (counterexample) "A-entry" else "A-first")
            if (extra == Extra.FIRST_READY_PAUSE) {
                await("real initial start is BUFFERING before first READY with production attempt pending") {
                    raw.playbackState == Player.STATE_BUFFERING && !raw.isPlaying && field(actual, "startAttempt") != null
                }
                assertNull("Preparation alone cannot create a history event", PlaybackService.exportLocalSnapshot().occurrence)
                assertEquals(samplesBefore, PreparedTransportMedia.samples.get())
                assertEquals(initialRendererStarts, PreparedTransportMedia.starts.get())
                val interruption = field(actual, "queueStartInterruption") as Long
                controller.pause()
                await("actual controller Pause interrupts held first start and settles its real publication") {
                    (field(actual, "queueStartInterruption") as Long) > interruption &&
                        !raw.isPlaying && !raw.playWhenReady && raw.playbackState == Player.STATE_IDLE &&
                        owner.state.value.view?.state?.playing == false && actionSettled() && projected()
                }
                val paused = PlaybackService.exportLocalSnapshot()
                val cancelledMetrics = runBlocking { requireNotNull(broker).metrics() }
                assertNull(paused.occurrence)
                PreparedTransportMedia.holdReady.set(false)
                repeat(25) {
                    shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.MILLISECONDS); Thread.sleep(5)
                    assertFalse("Late readiness cannot replay cancelled first Play", raw.isPlaying)
                    assertFalse(raw.playWhenReady); assertEquals(Player.STATE_IDLE, raw.playbackState)
                    assertEquals(samplesBefore, PreparedTransportMedia.samples.get())
                    assertEquals(initialRendererStarts, PreparedTransportMedia.starts.get())
                    assertNull(PlaybackService.exportLocalSnapshot().occurrence)
                    assertFalse((field(actual, "audioFocus") as PlaybackAudioFocus).isGranted)
                    assertFalse((field(actual, "foreground") as PlaybackForegroundNotification).foregroundConfirmed)
                }
                PreparedTransportMedia.holdReady.set(true)
                compose.onNodeWithContentDescription("Play").performClick()
                await("fresh admitted mini Play waits for actual readiness without earning history") {
                    raw.playbackState == Player.STATE_BUFFERING && !raw.isPlaying && field(actual, "startAttempt") != null
                }
                // A no-history Resume may reserve its own prospective traversal nonce via
                // the narrow restart receipt. That identity is NOT listening credit. Keep
                // the initial musical-Play null-history oracle above; do not conflate it
                // with this separate explicit Resume's zero-progress, unreported identity.
                val prospective = PlaybackService.exportLocalSnapshot().occurrence
                prospective?.let { assertEquals(0.0, it.progress, 0.0); assertFalse(it.reported) }
                assertEquals(samplesBefore, PreparedTransportMedia.samples.get())
                assertEquals(initialRendererStarts, PreparedTransportMedia.starts.get())
                assertEquals(cancelledMetrics.getValue("historyWrites"),
                    runBlocking { requireNotNull(broker).metrics() }.getValue("historyWrites"))
                PreparedTransportMedia.holdReady.set(false)
                await("fresh mini Play after first-start cancellation genuinely creates output and history") {
                    raw.playbackState == Player.STATE_READY && raw.isPlaying && raw.currentPosition > 50 &&
                        PreparedTransportMedia.samples.get() > samplesBefore && PreparedTransportMedia.starts.get() > initialRendererStarts &&
                        PlaybackService.exportLocalSnapshot().occurrence != null && projected() && actionSettled() &&
                        owner.state.value.view?.state?.playing == true
                }
                val started = PlaybackService.exportLocalSnapshot()
                prospective?.let { assertEquals(it.eventId, started.occurrence?.eventId) }
                assertEquals(paused.queue, started.queue); assertEquals(paused.position, started.position)
                assertEquals(initialRendererStarts + 1, PreparedTransportMedia.starts.get())
                assertNull(connected.state.value.error); assertNull(connected.state.value.notice)
                val after = runBlocking { requireNotNull(broker).metrics() }
                for (counter in listOf("registrations", "claims", "transfers", "collectionPlaybackReads"))
                    assertEquals("Cancelled-start recovery preserves $counter", cancelledMetrics.getValue(counter), after.getValue(counter))
                return
            }
            await("first musical Play must reach real ExoPlayer READY/isPlaying (not playWhenReady)") {
                raw.playbackState == Player.STATE_READY && raw.isPlaying && owner.state.value.view?.state?.playing == true
            }
            await("first musical Play must consume synthetic PCM and progress") { raw.currentPosition >= 100 && PreparedTransportMedia.samples.get() > samplesBefore }
            if (counterexample) {
                // The manual prepared Play above establishes the actual local output. Now a
                // deliberate negotiated Play now supplies truthful now/low provenance for
                // A + context tail C1/C2, rather than relabeling legacy metadata in the oracle.
                val receiptCount = receipts.size
                model.preparedListAction(client, client.currentSession()!!.accountId, queue,
                    null, "A-entry", PlaybackListAction.PLAY_NOW, connected.state.value.interactionKey)
                await("negotiated model Play now installs and actually starts A with its Low context tail") {
                    receipts.size == receiptCount + 1 && !model.state.value.preparing && raw.isPlaying
                }
                val detail = requireNotNull(receipts.last().queueIntentResult)
                assertEquals("playing", detail.outcome); assertEquals("started", detail.playback)
                assertEquals(3, detail.acceptedCount)
            }
            if (extra == Extra.CONTROLLER) {
                // Controller/posted-notification cycles also carry actual High/Low membership
                // alongside the duplicate A occurrences, not just an empty sidecar oracle.
                for ((name, action) in listOf("H1" to PlaybackListAction.QUEUE_HIGH, "B1" to PlaybackListAction.QUEUE_LOW)) {
                    val count = PlaybackService.exportLocalSnapshot().queue.size
                    model.preparedListAction(client, client.currentSession()!!.accountId,
                        PlaybackQueue(listOf(track(name)), total = 1), null, null, action, connected.state.value.interactionKey)
                    await("controller fixture has committed $name through actual model") {
                        !model.state.value.preparing && PlaybackService.exportLocalSnapshot().queue.size == count + 1 &&
                            owner.state.value.view?.state?.queue?.size == count + 1
                    }
                }
            }
            val initial = PlaybackService.exportLocalSnapshot()
            val initialRawOccurrence = PlaybackMedia.occurrence(raw.currentMediaItem)
            assertNotNull("Observed first playback establishes history", initial.occurrence)
            val initialMetadata = requireNotNull(PlaybackService.exportLocalQueueIntentState())
            val initialBrokerMetrics = broker?.let { runBlocking { it.metrics() } }
            fun assertStableBrokerMetrics(before: JsonObject?, label: String, qualifiedHistory: Boolean = false) {
                if (broker == null) return // Small-Room tests have no external broker metrics.
                val baseline = requireNotNull(before)
                // Ten genuinely advancing cycles can cross the production one-second history
                // threshold. Permit only that one earned attempt, never a duplicate Resume
                // write. Production history is best-effort: Pause can cancel its pending IO.
                val after = runBlocking { broker.metrics() }
                for (counter in listOf("registrations", "claims", "transfers", "collectionPlaybackReads")) {
                    assertEquals("$label must not add $counter", baseline.getValue(counter), after.getValue(counter))
                }
                if (!qualifiedHistory) assertEquals("$label must not add historyWrites",
                    baseline.getValue("historyWrites"), after.getValue("historyWrites"))
                else {
                    val writes = after.getValue("historyWrites").jsonPrimitive.long
                    assertTrue("$label cannot duplicate history for the same occurrence", writes in 0L..1L)
                    if (writes == 1L) {
                        val earned = requireNotNull(PlaybackService.exportLocalSnapshot().occurrence)
                        assertEquals(initial.occurrence?.eventId, earned.eventId)
                        assertTrue("Only real accumulated progress qualifies history", earned.progress >= 1.0)
                        assertTrue(earned.reported)
                    }
                }
            }
            fun postedAction(title: String): android.app.PendingIntent =
                shadowOf(actual.getSystemService(android.app.NotificationManager::class.java))
                    .allNotifications.single().actions.single { it.title.toString() == title }.actionIntent
            fun postedResume() = postedAction("Resume")
            fun deliverPosted(posted: android.app.PendingIntent) {
                // Deliver the original actually posted token unchanged; never mint/reflect one.
                while (shadowOf(actual).nextStartedService != null) { }
                posted.send()
                val delivered = requireNotNull(shadowOf(actual).nextStartedService)
                actual.onStartCommand(delivered, 0, 1)
            }
            fun resume() {
                if (!notification) compose.onNodeWithContentDescription("Play").performClick()
                else deliverPosted(postedResume())
            }
            fun settledPause() = await("terminal paused publication and admission settled") {
                !raw.isPlaying && raw.playbackState == Player.STATE_IDLE &&
                    owner.state.value.view?.state?.playing == false &&
                    owner.javaClass.getDeclaredField("pendingCommand").let { it.isAccessible = true; it.get(owner) == null }
            }
            fun assertSilent(snapshot: PlaybackSnapshot, label: String) {
                assertFalse("$label must not autoplay; trace=$trace", raw.isPlaying)
                assertFalse("$label must not queue play intent", raw.playWhenReady)
                assertEquals("$label keeps current occurrence", snapshot.queue, PlaybackService.exportLocalSnapshot().queue)
                assertEquals(snapshot.position, PlaybackService.exportLocalSnapshot().position)
                assertEquals(snapshot.occurrence, PlaybackService.exportLocalSnapshot().occurrence)
                assertFalse("$label releases focus", (field(actual, "audioFocus") as PlaybackAudioFocus).isGranted)
                assertFalse("$label releases foreground ownership",
                    (field(actual, "foreground") as PlaybackForegroundNotification).foregroundConfirmed)
            }
            if (extra in setOf(Extra.ENDED_RESTART, Extra.RENDERER_RETRY, Extra.HEADSET_CALLBACK)) {
                await("positive setup has the exact displayed service projection and settled action") { projected() && actionSettled() }
                when (extra) {
                    Extra.ENDED_RESTART -> {
                        assertEquals(1, raw.mediaItemCount)
                        val duration = raw.duration
                        assertTrue("Synthetic source must expose a real finite duration", duration > 100)
                        connected.seekTo(duration - 20)
                        await("real one-track ExoPlayer reaches STATE_ENDED after genuine guarded seek") {
                            raw.playbackState == Player.STATE_ENDED && !raw.isPlaying && projected() && actionSettled()
                        }
                        val ended = PlaybackService.exportLocalSnapshot()
                        val endedMetadata = requireNotNull(PlaybackService.exportLocalQueueIntentState())
                        assertEquals(initial.queue, ended.queue)
                        assertEquals(initial.occurrence?.eventId, ended.occurrence?.eventId)
                        assertFalse((field(actual, "audioFocus") as PlaybackAudioFocus).isGranted)
                        assertFalse((field(actual, "foreground") as PlaybackForegroundNotification).foregroundConfirmed)
                        val samples = PreparedTransportMedia.samples.get(); val starts = PreparedTransportMedia.starts.get()
                        compose.onNodeWithContentDescription("Play").performClick()
                        await("fresh mini Play restarts ended A with actual READY and advancing started PCM") {
                            raw.playbackState == Player.STATE_READY && raw.isPlaying && raw.currentPosition in 51L..5_000L &&
                                PreparedTransportMedia.samples.get() > samples && PreparedTransportMedia.starts.get() > starts &&
                                owner.state.value.view?.state?.playing == true && projected() && actionSettled()
                        }
                        val restarted = PlaybackService.exportLocalSnapshot()
                        val restartedMetadata = requireNotNull(PlaybackService.exportLocalQueueIntentState())
                        assertEquals(initial.queue, restarted.queue); assertEquals(initial.position, restarted.position)
                        assertEquals(initialRawOccurrence, PlaybackMedia.occurrence(raw.currentMediaItem))
                        assertNotEquals("Ended restart legitimately creates a new history event", ended.occurrence?.eventId, restarted.occurrence?.eventId)
                        assertNotNull(restarted.occurrence)
                        assertEquals(endedMetadata.revision + 1, restartedMetadata.revision)
                        assertEquals(endedMetadata.copy(revision = restartedMetadata.revision), restartedMetadata)
                        assertEquals(starts + 1, PreparedTransportMedia.starts.get())
                    }
                    Extra.RENDERER_RETRY -> {
                        val failures = PreparedTransportMedia.rendererFailures.get()
                        PreparedTransportMedia.failNextRender.set(true)
                        await("actual renderer thread exception reaches real ExoPlayer/service error listeners") {
                            PreparedTransportMedia.rendererFailures.get() == failures + 1 && raw.playerError != null &&
                                !raw.isPlaying && PlaybackService.exportLocalSnapshot().error == "media" && projected()
                        }
                        assertEquals(androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED, raw.playerError?.errorCode)
                        assertNotNull(connected.state.value.error)
                        assertFalse((field(actual, "audioFocus") as PlaybackAudioFocus).isGranted)
                        assertFalse((field(actual, "foreground") as PlaybackForegroundNotification).foregroundConfirmed)
                        val failed = PlaybackService.exportLocalSnapshot()
                        assertEquals(initial.queue, failed.queue); assertEquals(initial.occurrence?.eventId, failed.occurrence?.eventId)
                        val position = raw.currentPosition
                        val samples = PreparedTransportMedia.samples.get(); val starts = PreparedTransportMedia.starts.get()
                        compose.onNodeWithContentDescription("Play").performClick()
                        await("fresh app Retry really recovers failed renderer and progresses retained current") {
                            raw.playerError == null && raw.playbackState == Player.STATE_READY && raw.isPlaying && raw.currentPosition > position + 50 &&
                                PreparedTransportMedia.samples.get() > samples && PreparedTransportMedia.starts.get() > starts &&
                                owner.state.value.view?.state?.playing == true && projected() && actionSettled()
                        }
                        val recovered = PlaybackService.exportLocalSnapshot()
                        assertEquals(initial.queue, recovered.queue); assertEquals(initial.position, recovered.position)
                        assertEquals(initial.occurrence?.eventId, recovered.occurrence?.eventId)
                        assertEquals(initialMetadata, PlaybackService.exportLocalQueueIntentState())
                        assertEquals(starts + 1, PreparedTransportMedia.starts.get())
                        assertEquals(failures + 1, PreparedTransportMedia.rendererFailures.get())
                    }
                    Extra.HEADSET_CALLBACK -> {
                        val interruption = field(actual, "queueStartInterruption") as Long
                        controller.pause()
                        await("headset setup controller Pause actually dispatches and settles") {
                            (field(actual, "queueStartInterruption") as Long) > interruption && !raw.isPlaying &&
                                !raw.playWhenReady && raw.playbackState == Player.STATE_IDLE && projected() && actionSettled() &&
                                owner.state.value.view?.state?.playing == false
                        }
                        val paused = PlaybackService.exportLocalSnapshot(); val position = raw.currentPosition
                        val who = session.connectedControllers.first {
                            it.uid == android.os.Process.myUid() && it.packageName == actual.packageName && !session.isMediaNotificationController(it)
                        }
                        val impl = MediaSession::class.java.getDeclaredMethod("getImpl").apply { isAccessible = true }.invoke(session)
                        val callback = impl.javaClass.getDeclaredField("callback").apply { isAccessible = true }.get(impl) as MediaSession.Callback
                        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS)
                        val time = android.os.SystemClock.uptimeMillis()
                        val down = android.view.KeyEvent(time, time, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_HEADSETHOOK, 0)
                        val handler = android.os.Handler(Looper.getMainLooper())
                        fun ingress(event: android.view.KeyEvent) {
                            handler.post { assertTrue(callback.onMediaButtonEvent(session, who,
                                android.content.Intent(android.content.Intent.ACTION_MEDIA_BUTTON).putExtra(android.content.Intent.EXTRA_KEY_EVENT, event))) }
                        }
                        val samples = PreparedTransportMedia.samples.get(); val starts = PreparedTransportMedia.starts.get()
                        ingress(down); ingress(down)
                        ingress(android.view.KeyEvent(time, time, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_HEADSETHOOK, 1))
                        ingress(android.view.KeyEvent(time, time, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_HEADSETHOOK, 0))
                        await("genuine controller-attributed headset callback starts retained output once") {
                            raw.playbackState == Player.STATE_READY && raw.isPlaying && raw.currentPosition > position + 50 &&
                                PreparedTransportMedia.samples.get() > samples && PreparedTransportMedia.starts.get() == starts + 1 &&
                                projected() && actionSettled() && owner.state.value.view?.state?.playing == true
                        }
                        ingress(down) // Original immutable timestamp: replay after success is still the same delivery.
                        repeat(50) { shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS); Thread.sleep(5) }
                        assertTrue("Duplicate DOWN/repeat/UP cannot defer a pause or skip", raw.isPlaying)
                        assertEquals(starts + 1, PreparedTransportMedia.starts.get())
                        val after = PlaybackService.exportLocalSnapshot()
                        assertEquals(paused.queue, after.queue); assertEquals(paused.position, after.position)
                        assertEquals(paused.occurrence?.eventId, after.occurrence?.eventId)
                        assertEquals(initialMetadata, PlaybackService.exportLocalQueueIntentState())
                    }
                    else -> error("Unexpected positive transport variant")
                }
                assertNull(connected.state.value.error); assertNull(connected.state.value.notice)
                val afterMetrics = runBlocking { requireNotNull(broker).metrics() }
                for (counter in listOf("registrations", "claims", "transfers", "collectionPlaybackReads"))
                    assertEquals("Positive recovery must preserve $counter", requireNotNull(initialBrokerMetrics).getValue(counter), afterMetrics.getValue(counter))
                assertTrue((field(actual, "audioFocus") as PlaybackAudioFocus).isGranted)
                assertTrue((field(actual, "foreground") as PlaybackForegroundNotification).foregroundConfirmed)
                return
            }
            if (extra == Extra.LOW_COUNTEREXAMPLE) {
                compose.onNodeWithContentDescription("Pause").performClick(); settledPause()
                connected.seekTo(42_000)
                await("real paused seek to 42 seconds is published") {
                    raw.currentPosition == 42_000L && owner.state.value.view?.state?.elapsed == 42.0
                }
                fun add(names: List<String>, action: PlaybackListAction) {
                    val count = receipts.size
                    val additions = PlaybackQueue(names.map(::track), total = names.size)
                    model.preparedListAction(client, client.currentSession()!!.accountId, additions, null, null,
                        action, connected.state.value.interactionKey)
                    await("real model queue action terminal receipt $names") { receipts.size == count + 1 && !model.state.value.preparing }
                }
                add(listOf("H1"), PlaybackListAction.QUEUE_HIGH)
                val before = PlaybackService.exportLocalSnapshot()
                val metadataBefore = requireNotNull(PlaybackService.exportLocalQueueIntentState())
                val rendererBefore = rendererCounts()
                val discontinuitiesBefore = discontinuities.get()
                val seeksBefore = seeks.get()
                val bufferingBefore = bufferingTransitions.get()
                val historyBefore = before.occurrence
                val rawOccurrenceBefore = PlaybackMedia.occurrence(raw.currentMediaItem)
                val beforeLowMetrics = runBlocking { requireNotNull(broker).metrics() }
                assertEquals(42.0, before.elapsed, 0.0)
                assertEquals(listOf("A", "H1", "C1", "C2").map { broker!!.fixture.tracks.getValue(it) }, before.queue.map { it.id })
                assertEquals(listOf(before.queue[1].key), metadataBefore.highKeys)
                assertEquals(listOf("now", "high", "low", "low"), metadataBefore.origins.map { it.intent })
                add(listOf("B1", "B2"), PlaybackListAction.QUEUE_LOW)
                val after = PlaybackService.exportLocalSnapshot()
                val metadata = requireNotNull(PlaybackService.exportLocalQueueIntentState())
                val detail = requireNotNull(receipts.last().queueIntentResult)
                assertEquals("queued", detail.outcome); assertEquals(2, detail.acceptedCount)
                assertEquals("low", detail.placement); assertEquals("unchanged", detail.playback)
                assertNull(detail.playbackKey); assertEquals(metadata.revision, detail.commitRevision)
                assertEquals(listOf("A", "H1", "C1", "C2", "B1", "B2").map { broker!!.fixture.tracks.getValue(it) }, after.queue.map { it.id })
                assertEquals(before.queue, after.queue.take(4))
                assertEquals(before.position, after.position); assertEquals(42.0, after.elapsed, 0.0)
                assertEquals(historyBefore, after.occurrence); assertFalse(after.playing)
                assertEquals(before.context, after.context); assertEquals(before.shuffle, after.shuffle); assertEquals(before.repeat, after.repeat)
                assertEquals(metadataBefore.highKeys, metadata.highKeys)
                assertEquals(listOf("now", "high", "low", "low", "low", "low"), metadata.origins.map { it.intent })
                assertEquals("Queue-only Low must not enable/reset/start renderer", rendererBefore, rendererCounts())
                assertEquals("Queue-only Low must not seek", 42_000L, raw.currentPosition)
                assertEquals(discontinuitiesBefore, discontinuities.get()); assertEquals(seeksBefore, seeks.get())
                assertEquals("Queue-only Low must not prepare/buffer", bufferingBefore, bufferingTransitions.get())
                assertEquals(after.queue, owner.state.value.view?.state?.queue)
                assertEquals(metadata, owner.state.value.view?.queueIntents?.state)
                assertStableBrokerMetrics(beforeLowMetrics, "Prepared Low batch with unchanged paused A")
                val samples = PreparedTransportMedia.samples.get()
                resume()
                await("deliberate Resume starts A at retained 42, not pending H1 or appended B") {
                    raw.isPlaying && raw.currentPosition > 42_050 && PreparedTransportMedia.samples.get() > samples
                }
                val resumed = PlaybackService.exportLocalSnapshot()
                assertEquals(after.queue, resumed.queue); assertEquals(after.position, resumed.position)
                assertEquals(after.queue.first().id, resumed.queue[resumed.position].id)
                assertEquals("Actual renderer remains on A", after.queue.first().id, raw.currentMediaItem?.mediaId)
                assertEquals(rawOccurrenceBefore, PlaybackMedia.occurrence(raw.currentMediaItem))
                assertEquals("Exactly one renderer start for the fresh deliberate Resume",
                    rendererBefore[2] + 1, PreparedTransportMedia.starts.get())
                assertEquals(historyBefore?.eventId, resumed.occurrence?.eventId)
                assertEquals("Retained-position Resume must not seek/jump", discontinuitiesBefore, discontinuities.get())
                assertEquals(seeksBefore, seeks.get())
                assertEquals(metadata, PlaybackService.exportLocalQueueIntentState())
                assertStableBrokerMetrics(beforeLowMetrics, "Short retained-position Resume of A after prepared Low batch")
                return
            }
            if (extra == Extra.DROPPED_PAUSE) {
                // First obtain a real token from an earlier, fully settled paused incarnation.
                compose.onNodeWithContentDescription("Pause").performClick(); settledPause()
                val staleResume = postedResume()
                val priorPosition = raw.currentPosition
                val priorSamples = PreparedTransportMedia.samples.get()
                resume()
                await("settled resume actually progresses before lost Pause") {
                    raw.isPlaying && connected.state.value.isPlaying && raw.currentPosition > priorPosition + 50 &&
                        PreparedTransportMedia.samples.get() > priorSamples
                }
                val oldFocus = requireNotNull(shadowOf(actual.getSystemService(AudioManager::class.java)).lastAudioFocusRequest).listener
                runBlocking { requireNotNull(broker).armPauseResponse("drop") }
                compose.onNodeWithContentDescription("Pause").performClick()
                await("dropped response witnesses real accepted Pause HTTP 200") {
                    val status = runBlocking { broker!!.pauseResponseStatus() }
                    status?.get("accepted")?.jsonPrimitive?.boolean == true && status?.get("status")?.jsonPrimitive?.int == 200
                }
                await("lost response is uncertain/disconnected, not a fictitious successful client ACK") {
                    owner.state.value.retryAvailable && owner.state.value.message.isNotEmpty()
                }
                val paused = PlaybackService.exportLocalSnapshot()
                val position = raw.currentPosition
                val rendererBefore = rendererCounts()
                val samples = PreparedTransportMedia.samples.get()
                assertSilent(paused, "accepted-but-lost Pause")
                // No explicit fresh Play is sent in this uncertainty/quarantine window.
                // Only recovery/passive events and the genuinely stale posted Resume are used.
                deliverPosted(staleResume)
                owner.retry()
                connected.connect()
                app.sendBroadcast(android.content.Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
                oldFocus?.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
                // Observe two production one-second heartbeat periods, not only one idle turn.
                repeat(210) {
                    shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
                    Thread.sleep(10)
                    assertSilent(paused, "lost Pause recovery step $it")
                }
                assertEquals(position, raw.currentPosition)
                assertEquals(samples, PreparedTransportMedia.samples.get())
                assertEquals("No retry/reconnect may prepare, seek or start", rendererBefore, rendererCounts())
                return
            }
            if (extra == Extra.SEEDED) {
                seededOperations(model, client, connected, controller, raw, actual, initial, owner, requireNotNull(broker))
                return
            }
            val receiver = room.registrations.get()
            val deviceId = owner.state.value.deviceId
            repeat(if (extra == Extra.NON_PLAY_NOTICE) 1 else if (extra == Extra.CONTROLLER) 6 else if (realBroker) 11 else if (hold) 1 else 10) { cycle ->
                val holdThisPause = hold && (!realBroker || cycle == 10 || extra == Extra.NON_PLAY_NOTICE)
                var refusedFeedback: String? = null
                full.value = fullHost || cycle % 2 == 1
                await("UI sees observed playback") { connected.state.value.isPlaying }
                if (holdThisPause) {
                    if (broker == null) room.holdPause = true else runBlocking { broker.armPauseResponse() }
                }
                if (extra == Extra.CONTROLLER) {
                    if (cycle % 2 == 0) controller.pause() else controller.stop()
                } else compose.onNodeWithContentDescription("Pause").performClick()
                await("Pause drains observed playback and retains prepared queue") { !raw.isPlaying && raw.playbackState == Player.STATE_IDLE }
                if (holdThisPause) {
                    await("broker accepts Pause before held response") {
                        if (broker == null) room.pauseAccepted.count == 0L
                        else runBlocking { broker.pauseResponseStatus() }?.get("accepted")?.jsonPrimitive?.boolean == true
                    }
                    await("actual paused UI projection") { !connected.state.value.playWhenReady }
                    assertNull("No preexisting failure may satisfy rejected-click feedback", connected.state.value.error)
                    assertNull("No preexisting notice may satisfy rejected-click feedback", connected.state.value.notice)
                    if (extra == Extra.NON_PLAY_NOTICE) deliverPosted(postedAction("Next")) else resume()
                    shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
                    if (broker == null) assertEquals("Pause publication must still be intentionally held", 1L, room.releasePause.count)
                    assertNotNull("Pause action must remain pending, not time out",
                        owner.javaClass.getDeclaredField("pendingCommand").let { it.isAccessible = true; it.get(owner) })
                    assertTrue("Refused enabled Resume must expose actionable player feedback; trace=$trace",
                        connected.state.value.error != null || connected.state.value.notice != null)
                    val feedback = requireNotNull(connected.state.value.error ?: connected.state.value.notice)
                    assertEquals("Pending refusal retains action-appropriate coordinator guidance",
                        if (notification) "Confirming playback. Try again shortly." else "Confirming playback. Try Play again.", feedback)
                    refusedFeedback = feedback
                    if (notification) {
                        val posted = shadowOf(actual.getSystemService(android.app.NotificationManager::class.java)).allNotifications.single()
                        assertEquals(feedback, posted.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString())
                    } else compose.onNodeWithText(feedback).assertIsDisplayed()
                    assertFalse("refused click may not start behind unresolved publication", raw.isPlaying)
                    if (broker == null) room.releasePause.countDown() else runBlocking { broker.releasePauseResponse() }
                }
                await("terminal paused state consumed and ordinary action settled") {
                    owner.state.value.view?.state?.playing == false &&
                        owner.javaClass.getDeclaredField("pendingCommand").let { it.isAccessible = true; it.get(owner) == null }
                }
                val paused = PlaybackService.exportLocalSnapshot()
                val position = raw.currentPosition
                val samples = PreparedTransportMedia.samples.get()
                val pausedRendererCounts = rendererCounts()
                if (holdThisPause) {
                    // Settlement itself must not replay the rejected click. Observe queued
                    // callbacks before issuing ANY fresh action (including positive system Play).
                    repeat(25) {
                        shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
                        Thread.sleep(5)
                        assertFalse("Released held response cannot replay Resume", raw.isPlaying)
                        assertFalse(raw.playWhenReady)
                        assertEquals(Player.STATE_IDLE, raw.playbackState)
                        assertEquals(position, raw.currentPosition)
                        assertEquals(samples, PreparedTransportMedia.samples.get())
                        assertEquals(pausedRendererCounts, rendererCounts())
                        assertFalse((field(actual, "audioFocus") as PlaybackAudioFocus).isGranted)
                        assertFalse((field(actual, "foreground") as PlaybackForegroundNotification).foregroundConfirmed)
                    }
                    assertEquals(paused.occurrence, PlaybackService.exportLocalSnapshot().occurrence)
                }
                if (extra == Extra.NOTICE_NEW_PLAY) {
                    val oldFeedback = requireNotNull(refusedFeedback)
                    val newId = broker?.fixture?.tracks?.getValue("B1") ?: "B1"
                    val replacement = PlaybackQueue(listOf(
                        LibraryItem("track", newId, "Fresh deliberate B1", hasAudio = true, entryId = "new-B1")), total = 1)
                    // Fresh genuine ViewModel musical action, not resumePrepared/direct endpoint
                    // and not manual clearing of the old notice/error to manufacture success.
                    model.playPrepared(client, client.currentSession()!!.accountId, replacement, null, "new-B1")
                    await("fresh model selection after refused Resume actually starts new B1 and consumes PCM") {
                        raw.isPlaying && raw.currentMediaItem?.mediaId == newId && raw.currentPosition > 50 &&
                            PreparedTransportMedia.samples.get() > samples && owner.state.value.view?.state?.playing == true
                    }
                    val successor = PlaybackService.exportLocalSnapshot()
                    assertEquals(listOf(newId), successor.queue.map { it.id })
                    assertNotEquals(paused.occurrence?.eventId, successor.occurrence?.eventId)
                    await("fresh admitted musical selection clears obsolete refusal projection") {
                        connected.state.value.error == null && connected.state.value.notice == null
                    }
                    assertNull("New start cannot retain previous transport refusal", PlaybackService.queueNotice())
                    compose.onNodeWithText(oldFeedback).assertDoesNotExist()
                    return
                }
                if (extra == Extra.NOTICE_QUEUE_SUCCESSOR && holdThisPause) {
                    val oldFeedback = requireNotNull(refusedFeedback)
                    val rendererBefore = rendererCounts()
                    val receiptCount = receipts.size
                    val addition = PlaybackQueue(listOf(track("B1")), total = 1)
                    model.preparedListAction(client, client.currentSession()!!.accountId, addition, null, null,
                        PlaybackListAction.QUEUE_LOW, connected.state.value.interactionKey)
                    await("fresh paused Low successor returns actual accepted receipt") {
                        !model.state.value.preparing && receipts.size == receiptCount + 1 &&
                            PlaybackService.exportLocalSnapshot().queue.size == paused.queue.size + 1
                    }
                    val result = receipts.last()
                    val detail = requireNotNull(result.queueIntentResult)
                    assertEquals("queued", detail.outcome); assertEquals(1, detail.acceptedCount)
                    assertEquals("unchanged", detail.playback); assertNull(detail.playbackKey)
                    assertEquals("Track queued", result.userMessage)
                    assertEquals("Old refusal cannot mask a fresh real ViewModel Queued receipt",
                        "Track queued", model.state.value.feedback?.message)
                    assertNull(model.state.value.notice)
                    val successor = PlaybackService.exportLocalSnapshot()
                    assertEquals(paused.queue, successor.queue.dropLast(1))
                    assertEquals(paused.position, successor.position); assertEquals(paused.elapsed, successor.elapsed, 0.0)
                    assertEquals(paused.occurrence, successor.occurrence)
                    assertFalse(raw.isPlaying); assertFalse(raw.playWhenReady)
                    assertEquals(samples, PreparedTransportMedia.samples.get())
                    assertEquals(rendererBefore, rendererCounts())
                    await("semantic queue successor expires obsolete transport refusal") {
                        connected.state.value.error == null && connected.state.value.notice != oldFeedback
                    }
                    assertNotEquals("Getter must expire an old refusal without needing a new start",
                        oldFeedback, PlaybackService.queueNotice())
                    compose.onNodeWithText(oldFeedback).assertDoesNotExist()
                    // Let actual service listeners/connection refresh run after the semantic
                    // successor: no retained old refusal may reappear on a later projection.
                    repeat(25) {
                        shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
                        Thread.sleep(5)
                        assertNotEquals(oldFeedback, PlaybackService.queueNotice())
                        assertNotEquals(oldFeedback, connected.state.value.notice)
                        assertEquals("Track queued", model.state.value.feedback?.message)
                        assertNull(model.state.value.notice)
                    }
                    return
                }
                assertEquals(initial.queue, paused.queue)
                assertEquals(initial.occurrence?.eventId, paused.occurrence?.eventId)
                assertEquals(initial.context, paused.context)
                assertEquals("Pause preserves receiver/revision and High/Low provenance", initialMetadata,
                    PlaybackService.exportLocalQueueIntentState())
                if (broker != null) assertEquals(initialMetadata, owner.state.value.view?.queueIntents?.state)
                val pausedDiscontinuities = discontinuities.get()
                val pausedSeeks = seeks.get()
                if (extra == Extra.CONTROLLER && cycle % 2 == 0) controller.play() else resume()
                await("fresh Resume $cycle must actually start current prepared occurrence") { raw.isPlaying && owner.state.value.view?.state?.playing == true }
                await("fresh Resume $cycle must make synthetic progress") { raw.currentPosition > position + 50 && PreparedTransportMedia.samples.get() > samples }
                assertEquals("Exactly one renderer start belongs to the fresh explicit Resume",
                    pausedRendererCounts[2] + 1, PreparedTransportMedia.starts.get())
                await("fresh successful Resume retires previous refusal error and notice") {
                    connected.state.value.error == null && connected.state.value.notice == null
                }
                if (refusedFeedback != null) {
                    assertNull("Service must retire old refusal after actual successful Resume", PlaybackService.queueNotice())
                    if (!notification) compose.onNodeWithText(requireNotNull(refusedFeedback)).assertDoesNotExist()
                }
                val resumed = PlaybackService.exportLocalSnapshot()
                assertEquals(initial.queue, resumed.queue)
                assertEquals(initial.position, resumed.position)
                assertEquals(initialRawOccurrence, PlaybackMedia.occurrence(raw.currentMediaItem))
                assertEquals(initial.queue[initial.position].id, raw.currentMediaItem?.mediaId)
                assertEquals(initial.occurrence?.eventId, resumed.occurrence?.eventId)
                assertEquals(initial.shuffle, resumed.shuffle); assertEquals(initial.repeat, resumed.repeat)
                assertEquals("Fresh prepared Resume must not induce discontinuity/seek jump; trace=$trace",
                    pausedDiscontinuities, discontinuities.get())
                assertEquals(pausedSeeks, seeks.get())
                assertEquals("Resume preserves receiver/revision, origins and pending High membership",
                    initialMetadata, PlaybackService.exportLocalQueueIntentState())
                if (broker != null) assertEquals(initialMetadata, owner.state.value.view?.queueIntents?.state)
                // Real progress may qualify the one existing occurrence; repeated Resume
                // must never duplicate its history or trigger collection refetches.
                assertStableBrokerMetrics(initialBrokerMetrics, "Settled/held Pause-Resume cycle $cycle", qualifiedHistory = true)
                assertEquals(receiver, room.registrations.get())
                assertEquals("No unexpected reregistration or output takeover", deviceId, owner.state.value.deviceId)
                assertEquals(deviceId, owner.state.value.activeDeviceId)
                assertTrue((field(actual, "audioFocus") as PlaybackAudioFocus).isGranted)
                assertTrue((field(actual, "foreground") as PlaybackForegroundNotification).foregroundConfirmed)
            }
        } catch (caught: Throwable) { failure = caught; throw caught }
        finally {
            cleanup(failure,
                { PreparedTransportMedia.failNextRender.set(false) },
                { room.releasePause.countDown() },
                { models.clear() },
                { connection?.let { it.release() } ?: mediaController?.release() },
                { graph.clear() }, { service?.destroy() },
                { PreparedTransportMedia.holdReady.set(false) },
                { shadowOf(Looper.getMainLooper()).idle() }, { web?.shutdown() })
        }
    }

    /** Small bounded real production composition, NOT a 2,000-step mock-model total.
     * Four reproducible seeds x fifteen operations on one broker/registration/player. The
     * seed and operation-prefix trace make a failing prefix directly reproducible. This is
     * intentionally not lease-expiry/remote-handoff or virtual-time clock-model coverage.
     */
    private fun seededOperations(model: LibraryViewModel, client: SpofifyClient, connected: PlaybackConnection,
        controller: MediaController, raw: ExoPlayer, actual: PlaybackService, initial: PlaybackSnapshot,
        owner: dev.spofify.mobile.feature.devices.PlaybackCoordinator, broker: PreparedTransportBrokerProcess) {
        val deviceId = owner.state.value.deviceId
        val liveTransportProjection = PlaybackConnection::class.java
            .getDeclaredMethod("currentLocalTransportKey", PlaybackInteractionKey::class.java).apply { isAccessible = true }
        var expectedCurrentKey = initial.queue[initial.position].key
        var expectedEventId = initial.occurrence?.eventId
        fun settled() = await("seeded operation settles production action/publication") {
            !model.state.value.preparing &&
                owner.javaClass.getDeclaredField("pendingCommand").let { it.isAccessible = true; it.get(owner) == null } &&
                owner.state.value.view?.state?.playing == raw.isPlaying &&
                connected.state.value.isPlaying == raw.isPlaying &&
                connected.state.value.interactionKey === PlaybackService.interactionKey() &&
                // A generic queue key can already match while the coordinator's completed
                // receipt has advanced the separate transport projection. Wait for the real
                // Connection publisher to expose that current key; never dispatch a sampled
                // private key, retry a refused action, or weaken the guarded Next assertion.
                connected.state.value.transportInteractionKey ===
                    liveTransportProjection.invoke(connected, PlaybackService.interactionKey())
        }
        fun pause(stop: Boolean = false) {
            val interruption = field(actual, "queueStartInterruption") as Long
            if (stop) controller.stop() else controller.pause()
            // Already-paused output is not proof this asynchronously dispatched controller
            // action ran. Otherwise an old queued Pause can arrive during the next Resume.
            await("seeded Pause/Stop crosses the actual service dispatch boundary") {
                (field(actual, "queueStartInterruption") as Long) > interruption
            }
            record("controllerPause stop=$stop interruption=$interruption->${field(actual, "queueStartInterruption")}")
            await("seeded Pause/Stop drains actual output") { !raw.isPlaying && !raw.playWhenReady && raw.playbackState == Player.STATE_IDLE }
            settled()
        }
        fun add(action: PlaybackListAction) {
            val before = PlaybackService.exportLocalSnapshot()
            val rendererBefore = rendererCounts()
            val item = LibraryItem("track", initial.queue.last().id, "Seeded duplicate", hasAudio = true)
            model.preparedListAction(client, client.currentSession()!!.accountId,
                PlaybackQueue(listOf(item), total = 1), null, null, action, connected.state.value.interactionKey)
            await("seeded additive model action commits exactly one occurrence") {
                !model.state.value.preparing && PlaybackService.exportLocalSnapshot().queue.size == before.queue.size + 1
            }
            settled()
            val after = PlaybackService.exportLocalSnapshot()
            assertEquals(before.queue[before.position].key, after.queue[after.position].key)
            assertEquals(before.occurrence?.eventId, after.occurrence?.eventId)
            if (!before.playing) {
                assertFalse("seeded queue-only edit must remain paused", raw.isPlaying)
                assertEquals(before.elapsed, after.elapsed, 0.0)
                assertEquals(before.occurrence, after.occurrence)
                assertEquals(rendererBefore, rendererCounts())
            }
        }
        fun resumeProgress() {
            val position = raw.currentPosition; val samples = PreparedTransportMedia.samples.get()
            val starts = PreparedTransportMedia.starts.get(); val needsStart = !raw.isPlaying
            if (needsStart) connected.toggle()
            await("seeded fresh Resume really progresses current output") {
                raw.playbackState == Player.STATE_READY && raw.isPlaying && raw.currentPosition > position + 50 &&
                    PreparedTransportMedia.samples.get() > samples
            }
            if (needsStart) assertEquals(starts + 1, PreparedTransportMedia.starts.get())
            settled()
            assertNull(connected.state.value.error); assertNull(connected.state.value.notice)
        }
        fun noReplayAfterRelease(label: String) {
            val paused = PlaybackService.exportLocalSnapshot(); val position = raw.currentPosition
            val samples = PreparedTransportMedia.samples.get(); val renderer = rendererCounts()
            repeat(25) {
                shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.MILLISECONDS); Thread.sleep(5)
                assertFalse("$label cannot replay an old start", raw.isPlaying); assertFalse(raw.playWhenReady)
                assertEquals(Player.STATE_IDLE, raw.playbackState); assertEquals(position, raw.currentPosition)
                assertEquals(samples, PreparedTransportMedia.samples.get()); assertEquals(renderer, rendererCounts())
                assertEquals(paused.occurrence, PlaybackService.exportLocalSnapshot().occurrence)
                assertFalse((field(actual, "audioFocus") as PlaybackAudioFocus).isGranted)
                assertFalse((field(actual, "foreground") as PlaybackForegroundNotification).foregroundConfirmed)
            }
        }
        for (seed in listOf(7L, 42L, 104729L, 0x5EEDL)) {
            val random = java.util.Random(seed)
            // Every seed includes actual admitted Next, held broker ACK/refused Play and
            // held renderer readiness cancellation, plus four bounded original repetitions.
            val operations = (0..10).toMutableList().apply { repeat(4) { add(random.nextInt(8)) } }
            java.util.Collections.shuffle(operations, random)
            operations.forEachIndexed { index, operation ->
                record("seed=$seed op=$index action=$operation")
                settled()
                when (operation) {
                    0 -> pause()
                    1 -> resumeProgress()
                    2 -> pause(stop = true)
                    3 -> add(if (random.nextBoolean()) PlaybackListAction.QUEUE_LOW else PlaybackListAction.QUEUE_HIGH)
                    4 -> {
                        val listener = shadowOf(actual.getSystemService(AudioManager::class.java)).lastAudioFocusRequest?.listener
                        if (raw.isPlaying) requireNotNull(listener).onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
                        await("seeded actual focus callback drains output") { !raw.isPlaying && raw.playbackState == Player.STATE_IDLE }
                        settled()
                        listener?.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
                        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
                        assertFalse("seeded late focus gain never resumes", raw.isPlaying)
                    }
                    5 -> {
                        val interruption = field(actual, "queueStartInterruption") as Long
                        actual.sendBroadcast(android.content.Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
                        await("seeded noisy broadcast crosses the actual receiver dispatch boundary") {
                            (field(actual, "queueStartInterruption") as Long) > interruption
                        }
                        record("noisy interruption=$interruption->${field(actual, "queueStartInterruption")}")
                        await("seeded real noisy receiver drains output") { !raw.isPlaying && !raw.playWhenReady && raw.playbackState == Player.STATE_IDLE }
                        settled()
                    }
                    6 -> {
                        val held = requireNotNull(connected.state.value.transportInteractionKey)
                        add(PlaybackListAction.QUEUE_LOW)
                        val before = PlaybackService.exportLocalSnapshot()
                        assertFalse("held transport key must not retarget after genuine queue edit", connected.next(held))
                        assertEquals(before, PlaybackService.exportLocalSnapshot())
                    }
                    7 -> {
                        val paused = !raw.isPlaying
                        val before = PlaybackService.exportLocalSnapshot()
                        connected.connect()
                        repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS); Thread.sleep(5) }
                        if (paused) {
                            assertFalse("idempotent reconnect cannot replay a musical intention", raw.isPlaying)
                            assertEquals(before, PlaybackService.exportLocalSnapshot())
                        }
                    }
                    8 -> {
                        val before = PlaybackService.exportLocalSnapshot()
                        val target = before.queue[before.position + 1] // Predict BEFORE dispatch; never resample the chosen target.
                        val samples = PreparedTransportMedia.samples.get(); val starts = PreparedTransportMedia.starts.get()
                        val shownKey = requireNotNull(connected.state.value.transportInteractionKey)
                        if (!connected.next(shownKey)) {
                            val currentProjection = runCatching {
                                PlaybackConnection::class.java.getDeclaredMethod("currentLocalTransportKey", PlaybackInteractionKey::class.java)
                                    .apply { isAccessible = true }.invoke(connected, PlaybackService.interactionKey()) === shownKey
                            }.getOrNull()
                            fail("Fresh transport key refused Next; seed=$seed op=$index; shownCanNext=${connected.state.value.canSkipNext}; " +
                                "serviceCanNext=${PlaybackService.canSkipPrepared(true)}; projectionCurrent=$currentProjection; " +
                                "rawState=${raw.playbackState} playing=${raw.isPlaying}; trace=$trace")
                        }
                        await("seeded admitted Next really starts predicted prepared occurrence") {
                            val state = PlaybackService.exportLocalSnapshot()
                            raw.playbackState == Player.STATE_READY && raw.isPlaying && raw.currentPosition > 50 &&
                                state.queue[state.position].key == target.key &&
                                state.occurrence?.eventId?.let { it != expectedEventId } == true && PreparedTransportMedia.samples.get() > samples &&
                                PreparedTransportMedia.starts.get() > starts
                        }
                        settled()
                        val after = PlaybackService.exportLocalSnapshot()
                        assertEquals(before.queue, after.queue); assertEquals(before.position + 1, after.position)
                        assertEquals(target, after.queue[after.position]); assertEquals(target.id, raw.currentMediaItem?.mediaId)
                        assertNotEquals(expectedEventId, after.occurrence?.eventId)
                        expectedCurrentKey = target.key
                        expectedEventId = requireNotNull(after.occurrence).eventId
                    }
                    9 -> {
                        resumeProgress()
                        var heldFailure: Throwable? = null
                        try {
                            runBlocking { broker.armPauseResponse() }
                            val interruption = field(actual, "queueStartInterruption") as Long
                            controller.pause()
                            await("seeded held Pause actually dispatches before accepted response") {
                                (field(actual, "queueStartInterruption") as Long) > interruption && !raw.isPlaying &&
                                    !raw.playWhenReady && raw.playbackState == Player.STATE_IDLE
                            }
                            await("seeded proxy holds a real accepted Pause ACK") {
                                runBlocking { broker.pauseResponseStatus() }?.get("accepted")?.jsonPrimitive?.boolean == true
                            }
                            await("seeded pending Pause has the actual paused UI projection") { !connected.state.value.playWhenReady }
                            connected.toggle()
                            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
                            assertNotNull(owner.javaClass.getDeclaredField("pendingCommand").let { it.isAccessible = true; it.get(owner) })
                            assertEquals("Confirming playback. Try Play again.", connected.state.value.notice)
                            assertFalse(raw.isPlaying)
                        } catch (caught: Throwable) { heldFailure = caught; throw caught }
                        finally { cleanup(heldFailure, { runBlocking { broker.releasePauseResponse() } }) }
                        settled(); noReplayAfterRelease("Seeded held Pause ACK release"); resumeProgress()
                    }
                    10 -> {
                        pause()
                        try {
                            PreparedTransportMedia.holdReady.set(true)
                            connected.toggle()
                            await("seeded renderer gate holds a real production start before READY") {
                                raw.playbackState == Player.STATE_BUFFERING && !raw.isPlaying && field(actual, "startAttempt") != null
                            }
                            pause() // Includes the actual controller interruption boundary, not preexisting IDLE.
                        } finally { PreparedTransportMedia.holdReady.set(false) }
                        noReplayAfterRelease("Seeded late renderer readiness"); resumeProgress()
                    }
                }
                val state = PlaybackService.exportLocalSnapshot()
                assertEquals("Only a deliberate admitted Next may change the predicted current", expectedCurrentKey, state.queue[state.position].key)
                assertEquals(expectedEventId, state.occurrence?.eventId)
                assertEquals("generated operations may not reregister", deviceId, owner.state.value.deviceId)
                assertEquals("generated operations may not claim another output", deviceId, owner.state.value.activeDeviceId)
                if (!raw.isPlaying) {
                    assertFalse(raw.playWhenReady)
                    assertFalse((field(actual, "audioFocus") as PlaybackAudioFocus).isGranted)
                    assertFalse((field(actual, "foreground") as PlaybackForegroundNotification).foregroundConfirmed)
                }
            }
        }
    }

    private fun rendererCounts() = listOf(PreparedTransportMedia.enables.get(),
        PreparedTransportMedia.resets.get(), PreparedTransportMedia.starts.get())

    /** All disposals are attempted; a cleanup fault is suppressed onto the primary assertion. */
    private fun cleanup(primary: Throwable?, vararg actions: () -> Unit) {
        var failure = primary
        actions.forEach { action ->
            try { action() } catch (caught: Throwable) {
                if (failure == null) failure = caught else failure!!.addSuppressed(caught)
            }
        }
        if (primary == null) failure?.let { throw it }
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
            Thread.sleep(5)
        }
        if (!condition()) fail(description + "; trace=$trace; snapshot=" + PlaybackService.exportLocalSnapshot().let {
            "playing=${it.playing}, error=${it.error}, count=${it.queue.size}, elapsed=${it.elapsed}"
        })
    }
    private fun field(service: PlaybackService, name: String): Any? = PlaybackService::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(service)
}
