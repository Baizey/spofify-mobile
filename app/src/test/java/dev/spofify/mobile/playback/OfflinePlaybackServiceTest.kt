package dev.spofify.mobile.playback

import android.app.ActivityManager
import android.content.Intent
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.test.core.app.ApplicationProvider
import dev.spofify.mobile.AppGraph
import dev.spofify.mobile.LoginStore
import dev.spofify.mobile.OfflineEligibility
import dev.spofify.mobile.OfflineEligibilityStore
import dev.spofify.mobile.OfflineLibraryAccess
import dev.spofify.mobile.OfflineMediaReference
import dev.spofify.mobile.OfflineSavedCollection
import dev.spofify.mobile.RememberedLogin
import dev.spofify.mobile.SpofifyApplication
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.PlaybackDeviceCommand
import dev.spofify.mobile.core.PlaybackSnapshot
import dev.spofify.mobile.core.QueueIntentIntent
import dev.spofify.mobile.core.QueueIntentSelection
import dev.spofify.mobile.core.QueueIntentState
import dev.spofify.mobile.core.QueueIntentsView
import dev.spofify.mobile.core.QueueIntentReceiver
import dev.spofify.mobile.core.QueueIntentBinding
import dev.spofify.mobile.core.PlaybackContext
import dev.spofify.mobile.core.PlaybackDevice
import dev.spofify.mobile.core.PlaybackDevicesView
import kotlinx.serialization.json.*
import dev.spofify.mobile.core.SessionStore
import dev.spofify.mobile.core.StoredSession
import dev.spofify.mobile.feature.devices.PlaybackAuthority
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual sole service, default Media3 source/extractor and verified-opener bytes. Only the
 * PCM renderer is substituted, as in existing cache tests; retained-reference fixtures supply
 * real bytes, not replacement MediaSources, readiness, Sessions or receiver leases. These are portable regressions, not installed Android/acoustic acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SpofifyApplication::class,
    shadows = [SongCachePlaybackMedia::class, SongCachePlaybackMedia.LoaderRelease::class],
    instrumentedPackages = ["androidx.media3.exoplayer"])
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(UnstableApi::class)
class OfflinePlaybackServiceTest {
    private lateinit var app: SpofifyApplication
    private lateinit var graph: AppGraph
    private lateinit var eligibility: OfflineEligibility
    private lateinit var web: MockWebServer
    private var service: ServiceController<PlaybackService>? = null
    private var controller: MediaController? = null
    private var mediaSession: MediaSession? = null
    private val opens = AtomicInteger()
    private val reads = AtomicInteger()
    private val savedFactories = AtomicInteger()
    private val sourceExecutor = Executors.newSingleThreadExecutor()
    private val generations = ConcurrentHashMap<String, Any>()
    private val references = mutableMapOf<String, OfflineMediaReference>()
    private val referenceOpens = CopyOnWriteArrayList<String>()
    private lateinit var savedOwner: OfflineLibraryAccess
    private val target = LibraryItem("playlist", "saved-playlist", "Exact saved context")
    private val rows = listOf(track("A", "first"), track("A", "duplicate"), track("B", "last"))
    private val endpoint = Media3PlaybackEndpoint()

    private fun track(id: String, entry: String) = LibraryItem("track", id, "Track $id", durationMs = 12000,
        hasAudio = true, entryId = entry)

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.graph.clear()
        SongCachePlaybackMedia.reset()
        web = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(500)
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        eligibility = OfflineEligibility("http://127.0.0.1:${web.port}", "saved-account", "listener")
        app.getSharedPreferences("connection", 0).edit().clear().putBoolean("offline_mode", true).commit()
        graph = AppGraph(app, object : SessionStore {
            override fun read(): StoredSession? = null
            override fun write(value: StoredSession?) = Unit
        }, object : LoginStore {
            override fun read(): RememberedLogin? = null
            override fun write(value: RememberedLogin?) = Unit
        }, offlineEligibilityStore = object : OfflineEligibilityStore {
            private var saved: OfflineEligibility? = eligibility
            override fun read() = saved
            override fun write(value: OfflineEligibility?) { saved = value }
        })
        // Legacy record migration replaces its object with the protected genuine grant.
        // Bind the fixture to that CURRENT incarnation; production identity guards stay strict.
        eligibility = requireNotNull(graph.localEligibility)
        SpofifyApplication::class.java.getDeclaredField("graph").apply { isAccessible = true; set(app, graph) }
        savedOwner = object : OfflineLibraryAccess {
            override val revision = kotlinx.coroutines.flow.MutableStateFlow(0L)
            override suspend fun collections(origin: String, accountId: String): List<OfflineSavedCollection> =
                listOf(OfflineSavedCollection(target, rows, references.filterKeys { key -> rows.any { it.entryId == key } }, true))
            override fun retire() { generations.clear() }
        }
        graph.offlineLibrary = savedOwner
        installReference("first", wav(0x1111))
        installReference("duplicate", wav(0x4444)) // Same track A, distinct immutable representation.
        installReference("last", wav(0x2222))
        listOf("appended", "queued", "replacement", "late-selection").forEach { installReference(it, wav(0x3333)) }
        val built = Robolectric.buildService(PlaybackService::class.java).create()
        service = built
        val actual = built.get()
        val component = ComponentName(app, PlaybackService::class.java)
        val binder = requireNotNull(actual.onBind(Intent(MediaSessionService.SERVICE_INTERFACE).setComponent(component)))
        shadowOf(app).setComponentNameAndServiceForBindService(component, binder)
        shadowOf(app).setBindServiceCallsOnServiceConnectedDirectly(false)
        shadowOf(actual.getSystemService(ActivityManager::class.java)).setProcesses(listOf(
            ActivityManager.RunningAppProcessInfo().apply {
                pid = android.os.Process.myPid()
                importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            }))
        shadowOf(actual.getSystemService(AudioManager::class.java)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val who = MediaSession.ControllerInfo.createTestOnlyControllerInfo(actual.packageName, 100,
            android.os.Process.myUid(), 0, 0, true, Bundle.EMPTY, true)
        val session = requireNotNull(actual.onGetSession(who))
        mediaSession = session
        val future = MediaController.Builder(actual, session.token).buildAsync()
        await("controller connection") { future.isDone }
        controller = future.get(1, TimeUnit.SECONDS)
    }

    @After fun tearDown() {
        PlaybackService.onServiceActiveChanged = null
        try {
            controller?.release()
            service?.destroy()
            if (::graph.isInitialized) graph.clear()
        } finally {
            SongCachePlaybackMedia.reset()
            sourceExecutor.shutdownNow()
            if (::web.isInitialized) web.shutdown()
        }
    }

    /** Each reference closes over one immutable synthetic snapshot/descriptor generation and
     * its actual bytes. No path, track-ID lookup or substitution occurs in the read hook. */
    private fun installReference(entry: String, content: ByteArray, versionKey: String = entry,
        beforeOpen: () -> Unit = {}): OfflineMediaReference {
        val generation = Any()
        generations[versionKey] = generation
        val reference = OfflineMediaReference(savedOwner, eligibility.origin, eligibility.accountId) {
            assertNotSame("The saved suspend factory must never execute on main", Looper.getMainLooper(), Looper.myLooper())
            savedFactories.incrementAndGet()
            if (generations[versionKey] !== generation) null else {
                val actual = ByteArrayDataSource(content)
                object : DataSource by actual {
                    override fun open(dataSpec: DataSpec): Long {
                        assertEquals("spofify-local", dataSpec.uri.scheme)
                        assertEquals(dataSpec.uri.toString(), dataSpec.key)
                        referenceOpens += versionKey
                        opens.incrementAndGet()
                        beforeOpen()
                        return actual.open(dataSpec)
                    }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (generations[versionKey] !== generation) throw java.io.IOException("Retired synthetic snapshot")
                        reads.incrementAndGet()
                        return actual.read(buffer, offset, length)
                    }
                }
            }
        }
        references[entry] = reference
        return reference
    }

    private fun play(start: Int = 0) {
        assertNull(PlaybackService.replaceLocal(rows, eligibility, references, start, false, target))
        val expected = when (start) { 1 -> 0x4444; 2 -> 0x2222; else -> 0x1111 }
        await("actual saved PCM and playing") { controller!!.isPlaying && SongCachePlaybackMedia.bytes(expected) > 0 }
        assertNull(controller!!.playerError)
        assertNoTraffic()
    }

    private fun command(type: String) {
        assertTrue(PlaybackService.localTransport(PlaybackDeviceCommand.transport(type)))
        if (type in setOf("pause", "close")) {
            assertFalse("Actual service output is drained synchronously", mediaSession!!.player.playWhenReady)
            await("controller observes $type drainage") {
                !controller!!.playWhenReady && controller!!.playbackState == Player.STATE_IDLE &&
                    (type != "close" || controller!!.mediaItemCount == 0)
            }
        } else if (type == "repeat") {
            val actual = mediaSession!!.player.repeatMode
            await("controller observes repeat mode") { controller!!.repeatMode == actual }
        }
    }

    private fun assertNoTraffic() {
        assertEquals("No API, registration, cleanup, audio/ranges or history request is permitted", 0, web.requestCount)
        assertNull(web.takeRequest(10, TimeUnit.MILLISECONDS))
    }

    @Test fun coldStartWithoutLiveSessionOrRememberedLeadershipWaitsForExplicitPlayAndConsumesVerifiedMedia() {
        assertNull(graph.client!!.currentSession())
        assertNull(graph.playbackCoordinator)
        assertSame(eligibility, graph.localEligibility)
        pump(250)
        assertFalse(controller!!.playWhenReady)
        assertEquals(0, controller!!.mediaItemCount)
        assertEquals(0, opens.get())
        assertEquals(0, reads.get())
        assertEquals(0L, SongCachePlaybackMedia.samples.get())
        play(start = 1)
        val state = PlaybackService.displayLocalSnapshot()
        assertEquals(listOf("first", "duplicate", "last"), state.queue.map { it.entryId })
        assertEquals(3, state.queue.map { it.key }.toSet().size)
        assertEquals("duplicate", state.queue[state.position].entryId)
        assertEquals(target.id, PlaybackService.queueContext()?.id)
        assertEquals(target.name, PlaybackService.queueContext()?.name)
        assertTrue(reads.get() > 0)
        assertTrue(PlaybackService.isLocalOnlyQueue())
        assertNoTraffic()
    }

    @Test fun localQueueAppendPlacementShuffleRepeatSeekAndRemovalPreservePausedCurrentAndExactDuplicates() {
        play()
        command("pause")
        val selected = controller!!.currentMediaItem
        val position = controller!!.currentPosition
        val beforeOpens = opens.get()
        val beforeFactories = savedFactories.get()
        val beforeStarts = SongCachePlaybackMedia.starts.get()
        assertNull(PlaybackService.enqueueLocal(listOf(track("C", "appended")), eligibility, references, false) { true })
        assertEquals(listOf("first", "duplicate", "last", "appended"), PlaybackService.displayLocalSnapshot().queue.map { it.entryId })
        assertEquals(selected, controller!!.currentMediaItem)
        assertEquals(position, controller!!.currentPosition)
        assertFalse(controller!!.playWhenReady)
        assertEquals(beforeOpens, opens.get())
        assertEquals(beforeFactories, savedFactories.get())
        assertEquals(beforeStarts, SongCachePlaybackMedia.starts.get())
        command("shuffle")
        val shuffled = PlaybackService.displayLocalSnapshot()
        assertTrue(shuffled.shuffle)
        assertEquals(setOf("first", "duplicate", "last", "appended"), shuffled.queue.map { it.entryId }.toSet())
        command("shuffle")
        assertEquals(listOf("first", "duplicate", "last", "appended"), PlaybackService.displayLocalSnapshot().queue.map { it.entryId })
        command("repeat")
        assertEquals(Player.REPEAT_MODE_ALL, controller!!.repeatMode)
        command("repeat")
        assertEquals(Player.REPEAT_MODE_ONE, controller!!.repeatMode)
        assertTrue(PlaybackService.localTransport(PlaybackDeviceCommand.seek(2.0)))
        assertEquals(2000L, mediaSession!!.player.currentPosition)
        await("controller observes paused seek") { controller!!.currentPosition == 2000L }
        assertEquals(2000L, controller!!.currentPosition)
        assertFalse(controller!!.playWhenReady)
        val duplicate = PlaybackService.upcomingEntries().first { it.item.entryId == "duplicate" }
        val key = requireNotNull(PlaybackService.interactionKey())
        PlaybackService.removeQueued(key, duplicate.occurrenceId) { true }
        assertFalse(PlaybackService.upcomingEntries().any { it.item.entryId == "duplicate" })
        assertEquals("first", PlaybackMedia.libraryItem(controller!!.currentMediaItem!!)?.entryId)
        PlaybackService.undoQueuedRemoval()
        assertEquals(1, PlaybackService.upcomingEntries().count { it.item.entryId == "duplicate" })
        assertFalse(controller!!.playWhenReady)
        assertNoTraffic()
    }

    @Test fun heldSavedLoaderEnqueueAndReorderPreserveTheExactPendingStartEvenDuringSynchronousExports() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        installReference("first", wav(0x1111), beforeOpen = {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
        })
        try {
            assertNull(PlaybackService.replaceLocal(rows, eligibility, references, 0, false, target))
            await("real saved loader held in open") { entered.count == 0L && serviceField("pendingStart") != null }
            val attempt = requireNotNull(serviceField("startAttempt"))
            val media = mediaSession!!.player.currentMediaItem
            val starts = SongCachePlaybackMedia.starts.get()
            var inlineExports = 0
            var editing = true
            val raw = serviceField("player") as Player
            val listener = object : Player.Listener {
                override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                    if (!editing) return
                    inlineExports++
                    PlaybackService.displayLocalSnapshot()
                    assertSame("Inline mutation observers must not reject then revive the start", attempt,
                        serviceField("startAttemptValidity"))
                }
            }
            raw.addListener(listener)
            try {
                val heldKey = requireNotNull(PlaybackService.interactionKey())
                assertNull(PlaybackService.enqueueLocal(listOf(track("C", "queued")), eligibility, references,
                    QueueIntentIntent.HIGH) { true })
                val last = PlaybackService.upcomingEntries().single { it.item.entryId == "last" }
                PlaybackService.moveQueued(requireNotNull(PlaybackService.interactionKey()), last.occurrenceId, 0)
                val duplicate = PlaybackService.upcomingEntries().single { it.item.entryId == "duplicate" }
                PlaybackService.localOccurrence(requireNotNull(PlaybackService.interactionKey()), duplicate.occurrenceId,
                    PlaybackListAction.QUEUE_HIGH, { true }) { assertEquals(PlaybackActionStatus.NO_OP, it.status) }
                val state = PlaybackService.displayLocalSnapshot()
                assertEquals(listOf("first", "last", "queued", "duplicate"), state.queue.map { it.entryId })
                assertNotSame("Old menu/gesture identity still retires", heldKey, PlaybackService.interactionKey())
                assertSame(attempt, serviceField("startAttempt"))
                assertSame(attempt, serviceField("startAttemptValidity"))
                assertEquals(media, mediaSession!!.player.currentMediaItem)
                assertFalse(mediaSession!!.player.isPlaying)
                assertEquals(starts, SongCachePlaybackMedia.starts.get())
                assertTrue("Exercise Media3's synchronous timeline observers", inlineExports > 0)
                editing = false
                release.countDown()
                await("same held start reaches real PCM exactly once") {
                    controller!!.isPlaying && SongCachePlaybackMedia.bytes(0x1111) > 0 && serviceField("pendingStart") == null
                }
                assertSame(attempt, serviceField("startAttempt"))
                assertSame(attempt, serviceField("startAttemptValidity"))
                assertEquals(starts + 1, SongCachePlaybackMedia.starts.get())
                assertEquals(1, referenceOpens.count { it == "first" })
                assertNoTraffic()
            } finally { editing = false; raw.removeListener(listener) }
        } finally { release.countDown() }
    }

    @Test fun upcomingEditCannotRefreshTheHeldStartsOriginalSourceGuard() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        installReference("first", wav(0x1111), beforeOpen = {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
        })
        var live = true
        try {
            assertNull(PlaybackService.replaceLocal(rows, eligibility, references, 0, false, target) { live })
            await("real saved loader held in open") { entered.count == 0L && serviceField("pendingStart") != null }
            live = false
            assertNull(PlaybackService.enqueueLocal(listOf(track("C", "queued")), eligibility, references,
                QueueIntentIntent.HIGH) { true })
            PlaybackService.displayLocalSnapshot()
            await("original source refusal drains its pending start") { serviceField("startAttempt") == null }
            release.countDown()
            pump(150)
            assertFalse(controller!!.playWhenReady)
            assertEquals(Player.STATE_IDLE, controller!!.playbackState)
            assertEquals(0L, SongCachePlaybackMedia.bytes(0x1111))
            assertNoTraffic()
        } finally { release.countDown() }
    }

    @Test fun repeatAbaCannotBeForgivenByAnUpcomingEditDuringHeldLoading() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        installReference("first", wav(0x1111), beforeOpen = {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
        })
        try {
            assertNull(PlaybackService.replaceLocal(rows, eligibility, references, 0, false, target))
            await("real saved loader held in open") { entered.count == 0L && serviceField("pendingStart") != null }
            val attempt = requireNotNull(serviceField("startAttempt"))
            repeat(3) { assertTrue(PlaybackService.localTransport(PlaybackDeviceCommand.transport("repeat"))) }
            assertEquals("off", PlaybackService.displayLocalSnapshot().repeat)
            assertSame(attempt, serviceField("startAttempt"))
            assertNull(serviceField("startAttemptValidity"))
            assertNull(PlaybackService.enqueueLocal(listOf(track("C", "queued")), eligibility, references,
                QueueIntentIntent.HIGH) { true })
            assertNull("An equal final repeat value cannot revive validity", serviceField("startAttemptValidity"))
            release.countDown()
            await("ABA-rejected start drains") { serviceField("startAttempt") == null && controller!!.playbackState == Player.STATE_IDLE }
            assertFalse(controller!!.playWhenReady)
            assertEquals(0L, SongCachePlaybackMedia.bytes(0x1111))
            assertNoTraffic()
        } finally { release.countDown() }
    }

    @Test fun focusLossAfterHeldLoaderQueueEditStillCancelsAndNeverResumesOnGain() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        installReference("first", wav(0x1111), beforeOpen = {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
        })
        try {
            assertNull(PlaybackService.replaceLocal(rows, eligibility, references, 0, false, target))
            await("real saved loader held in open") { entered.count == 0L && serviceField("pendingStart") != null }
            assertNull(PlaybackService.enqueueLocal(listOf(track("C", "queued")), eligibility, references,
                QueueIntentIntent.HIGH) { true })
            val listener = focusListener()
            listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            assertNull(serviceField("startAttemptValidity"))
            release.countDown()
            listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
            await("focus-loss cancellation drains") { serviceField("pendingStart") == null && controller!!.playbackState == Player.STATE_IDLE }
            pump(150)
            assertFalse(controller!!.playWhenReady)
            assertEquals(0L, SongCachePlaybackMedia.bytes(0x1111))
            assertNoTraffic()
        } finally { release.countDown() }
    }

    @Test fun connectionAddsNewHighSelectionsFifoAmongPendingHighWithoutStartingPausedCurrent() {
        play()
        command("pause")
        assertTrue(PlaybackService.localTransport(PlaybackDeviceCommand.seek(2.0)))
        val before = PlaybackService.displayLocalSnapshot()
        val media = mediaSession!!.player.currentMediaItem
        val opened = opens.get()
        val started = SongCachePlaybackMedia.starts.get()
        val connection = PlaybackConnection(app)
        connection.connect()
        await("production connection through real service binder") { connection.state.value.item != null }
        try {
            connection.enqueueLocal(listOf(track("A", "queued")), eligibility, references, QueueIntentIntent.HIGH)
            assertEquals(listOf("first", "queued", "duplicate", "last"), PlaybackService.displayLocalSnapshot().queue.map { it.entryId })
            connection.enqueueLocal(listOf(track("D", "appended")), eligibility, references, QueueIntentIntent.LOW)
            connection.enqueueLocal(listOf(track("E", "replacement")), eligibility, references, QueueIntentIntent.NEXT)
            connection.enqueueLocal(listOf(track("F", "late-selection")), eligibility, references, QueueIntentIntent.HIGH)
            val after = PlaybackService.displayLocalSnapshot()
            assertEquals(listOf("first", "replacement", "queued", "late-selection", "duplicate", "last", "appended"),
                after.queue.map { it.entryId })
            assertEquals(7, after.queue.map { it.key }.toSet().size)
            assertEquals(before.queue[before.position], after.queue[after.position])
            assertEquals(before.elapsed, after.elapsed, 0.0)
            assertEquals(before.error, after.error)
            assertEquals(before.context, after.context)
            assertEquals(before.shuffle, after.shuffle)
            assertEquals(before.repeat, after.repeat)
            assertEquals(media, mediaSession!!.player.currentMediaItem)
            assertFalse(controller!!.playWhenReady)
            assertEquals(opened, opens.get())
            assertEquals(started, SongCachePlaybackMedia.starts.get())
            assertEquals("Track queued", connection.state.value.feedback?.message)
            assertNoTraffic()
        } finally { connection.release() }
    }

    private fun serviceField(name: String): Any? = PlaybackService::class.java.getDeclaredField(name).let {
        it.isAccessible = true; it.get(requireNotNull(service).get())
    }

    @Test fun explicitNextPreviousAndRepeatUseExactLocalOccurrencesAndConsumeTheirRealPcm() {
        play(start = 1)
        command("repeat") // all
        command("repeat") // one: explicit Next must still advance.
        command("next")
        await("B occurrence and its PCM") {
            PlaybackMedia.libraryItem(controller!!.currentMediaItem!!)?.entryId == "last" &&
                controller!!.isPlaying && SongCachePlaybackMedia.bytes(0x2222) > 0
        }
        command("repeat") // off
        command("repeat") // all
        val priorA = SongCachePlaybackMedia.bytes(0x1111)
        command("next")
        await("repeat-all wraps to first exact duplicate") {
            PlaybackMedia.libraryItem(controller!!.currentMediaItem!!)?.entryId == "first" &&
                controller!!.isPlaying && SongCachePlaybackMedia.bytes(0x1111) > priorA
        }
        command("previous")
        await("Previous wraps to B under repeat-all") {
            PlaybackMedia.libraryItem(controller!!.currentMediaItem!!)?.entryId == "last" && controller!!.isPlaying
        }
        assertNoTraffic()
    }

    @Test fun localOccurrencePrioritiesAreGuardedQueueEditsAndChangedCurrentPlayNowStartsOnlyItsExactDuplicate() {
        play()
        command("pause")
        val startCount = SongCachePlaybackMedia.starts.get()
        val opensBefore = opens.get()
        val original = requireNotNull(PlaybackService.interactionKey())
        val b = PlaybackService.upcomingEntries().single { it.item.entryId == "last" }
        var result: PlaybackActionResult? = null
        PlaybackService.localOccurrence(original, b.occurrenceId, PlaybackListAction.QUEUE_HIGH, { true }) { result = it }
        assertEquals(PlaybackActionStatus.ADDED, result?.status)
        assertEquals(listOf("first", "last", "duplicate"), PlaybackService.displayLocalSnapshot().queue.map { it.entryId })
        assertFalse(controller!!.playWhenReady)
        PlaybackService.localOccurrence(original, b.occurrenceId, PlaybackListAction.QUEUE_LOW, { true }) { result = it }
        assertEquals("Held key must not borrow the reordered queue's new identity", PlaybackActionStatus.STALE, result?.status)
        assertEquals(startCount, SongCachePlaybackMedia.starts.get())
        assertEquals(opensBefore, opens.get())
        val duplicate = PlaybackService.upcomingEntries().single { it.item.entryId == "duplicate" }
        result = null
        val beforePcm = SongCachePlaybackMedia.bytes(0x4444)
        PlaybackService.localOccurrence(requireNotNull(PlaybackService.interactionKey()), duplicate.occurrenceId,
            PlaybackListAction.PLAY_NOW, { true }) { result = it }
        await("exact duplicate Play now completed with real output") {
            result != null && controller!!.isPlaying && SongCachePlaybackMedia.bytes(0x4444) > beforePcm
        }
        assertEquals(PlaybackActionStatus.PLAYING, result?.status)
        assertEquals("duplicate", PlaybackMedia.libraryItem(controller!!.currentMediaItem!!)?.entryId)
        assertTrue(controller!!.isPlaying)
        assertNoTraffic()
    }

    @Test fun connectionLocalEnqueueEmitsOnlyQueueFeedbackAndModeChangedRetiresItWithoutCreatingOutput() {
        play()
        command("pause")
        val connection = PlaybackConnection(app)
        connection.connect()
        await("production connection through real service binder") { connection.state.value.item != null }
        try {
            val factories = SongCachePlaybackMedia.factories.get()
            val starts = SongCachePlaybackMedia.starts.get()
            val key = requireNotNull(PlaybackService.interactionKey())
            connection.enqueueLocal(listOf(track("C", "queued")), eligibility, references,
                isCurrent = { PlaybackService.interactionKey() === key })
            assertEquals("Track queued", connection.state.value.feedback?.message)
            assertFalse(controller!!.playWhenReady)
            assertEquals(starts, SongCachePlaybackMedia.starts.get())
            graph.setOfflineMode(false)
            connection.modeChanged()
            assertNull(connection.state.value.feedback)
            assertNull(connection.state.value.error)
            assertFalse(connection.state.value.isPlaying)
            assertEquals(factories, SongCachePlaybackMedia.factories.get())
            assertEquals(starts, SongCachePlaybackMedia.starts.get())
            assertNoTraffic()
        } finally { connection.release() }
    }

    @Test fun modeChangedCancelsPendingLocalPlayWithoutConnectingOrStartingIt() {
        val connection = PlaybackConnection(app)
        val pending = PlaybackConnection::class.java.getDeclaredField("pendingPlay").apply { isAccessible = true }
        try {
            connection.playLocal(rows, eligibility, references, context = target)
            assertNotNull(pending.get(connection))
            val factories = SongCachePlaybackMedia.factories.get()
            graph.setOfflineMode(false)
            connection.modeChanged()
            assertNull(pending.get(connection))
            pump(200)
            assertFalse(controller!!.playWhenReady)
            assertEquals(0, controller!!.mediaItemCount)
            assertNull(connection.state.value.error)
            assertNull(connection.state.value.feedback)
            assertEquals(factories, SongCachePlaybackMedia.factories.get())
            assertEquals(0, opens.get())
            assertNoTraffic()
        } finally { connection.release() }
    }

    @Test fun missingLastRowRejectsEntireReplacementAndAppendRatherThanPlayingAvailablePrefix() {
        play()
        command("pause")
        val before = PlaybackService.displayLocalSnapshot()
        val unavailable = rows + track("missing", "missing-entry")
        assertNotNull(PlaybackService.replaceLocal(unavailable, eligibility, references, 0, false, target))
        assertNotNull(PlaybackService.enqueueLocal(unavailable, eligibility, references, false) { true })
        assertEquals(before, PlaybackService.displayLocalSnapshot())
        assertFalse(controller!!.playWhenReady)
        assertNoTraffic()
    }

    @Test fun badBytesFailThroughRealExtractorWithoutFallbackOrFalsePlaying() {
        installReference("first", byteArrayOf(1, 2, 3, 4))
        assertNull(PlaybackService.replaceLocal(rows.take(1), eligibility, references, 0, false, target))
        await("actual source error or bounded failed start") {
            controller!!.playerError != null || PlaybackService.displayLocalSnapshot().error == "media"
        }
        assertFalse(controller!!.isPlaying)
        await("failed start finishes cleanup") { serviceField("pendingStart") == null }
        val before = PlaybackService.displayLocalSnapshot()
        val opensBefore = opens.get()
        val startsBefore = SongCachePlaybackMedia.starts.get()
        assertNull(PlaybackService.enqueueLocal(listOf(track("C", "queued")), eligibility, references,
            QueueIntentIntent.HIGH) { true })
        val after = PlaybackService.displayLocalSnapshot()
        assertEquals(before.error, after.error)
        assertEquals(before.elapsed, after.elapsed, 0.0)
        assertEquals(before.queue[before.position], after.queue[after.position])
        assertFalse(controller!!.playWhenReady)
        assertEquals(opensBefore, opens.get())
        assertEquals(startsBefore, SongCachePlaybackMedia.starts.get())
        assertEquals(0L, SongCachePlaybackMedia.bytes(0x1111))
        assertNoTraffic()
    }

    @Test fun sameTrackAndEntryAcrossTwoImmutableSavedSnapshotsKeepDistinctExactReferencesAndPcm() {
        val row = track("A", "shared-entry")
        val first = installReference("shared-entry", wav(0x1111), "snapshot-one/shared-entry")
        val second = installReference("shared-entry", wav(0x4444), "snapshot-two/shared-entry")
        assertNotSame(first, second)
        assertNull(PlaybackService.replaceLocal(listOf(row), eligibility, mapOf("shared-entry" to first), 0, false, target))
        await("first immutable snapshot PCM") { controller!!.isPlaying && SongCachePlaybackMedia.bytes(0x1111) > 0 }
        command("pause")
        assertNull(PlaybackService.enqueueLocal(listOf(row), eligibility, mapOf("shared-entry" to second), false) { true })
        val queue = PlaybackService.displayLocalSnapshot().queue
        assertEquals(listOf("A", "A"), queue.map { it.id })
        assertEquals(listOf("shared-entry", "shared-entry"), queue.map { it.entryId })
        assertEquals(2, queue.map { it.key }.toSet().size)
        assertFalse(controller!!.playWhenReady)
        assertFalse(referenceOpens.contains("snapshot-two/shared-entry"))
        assertEquals(0L, SongCachePlaybackMedia.bytes(0x4444))
        command("next")
        await("same track resolves the SECOND exact saved snapshot, not the first track-ID hit") {
            controller!!.isPlaying && SongCachePlaybackMedia.bytes(0x4444) > 0
        }
        assertTrue(referenceOpens.contains("snapshot-two/shared-entry"))
        command("pause")
        val priorFirst = SongCachePlaybackMedia.bytes(0x1111)
        command("previous")
        await("Previous retains the FIRST reference despite identical track and entry IDs") {
            controller!!.isPlaying && SongCachePlaybackMedia.bytes(0x1111) > priorFirst
        }
        assertTrue(referenceOpens.count { it == "snapshot-one/shared-entry" } >= 2)
        assertNoTraffic()
    }

    @Test fun replacedSavedGenerationFailsAtLoaderWithoutResolvingNewBytesByTrackOrEntry() {
        play()
        command("pause")
        val oldReferences = references.toMap()
        installReference("first", wav(0x5555)) // Replaces descriptor in the same immutable-target slot.
        assertNull(onLoader { runBlocking { graph.openOfflineMedia(eligibility, oldReferences.getValue("first")) } })
        val beforeOpens = opens.get()
        // Admission checks references structurally; availability belongs to the suspend owner
        // on the real Media3 loading thread, never a blocking main-thread preflight.
        assertNull(PlaybackService.replaceLocal(rows, eligibility, oldReferences, 0, false, target))
        await("retired exact snapshot fails during actual media open") {
            controller!!.playerError != null || PlaybackService.displayLocalSnapshot().error == "media"
        }
        assertFalse(controller!!.isPlaying)
        assertEquals(beforeOpens, opens.get())
        assertEquals(0L, SongCachePlaybackMedia.bytes(0x5555))
        val failed = PlaybackService.displayLocalSnapshot()
        val starts = SongCachePlaybackMedia.starts.get()
        assertNull(PlaybackService.enqueueLocal(rows.take(1), eligibility, oldReferences, false) { true })
        assertFalse(controller!!.playWhenReady)
        assertEquals(failed.error, PlaybackService.displayLocalSnapshot().error)
        assertEquals(starts, SongCachePlaybackMedia.starts.get())
        assertEquals(0L, SongCachePlaybackMedia.bytes(0x5555))
        assertNoTraffic()
    }

    @Test fun anOldQueueSourceCannotBorrowAFreshLocalGrantEvenForTheSameEligibility() {
        play()
        val media = requireNotNull(mediaSession!!.player.currentMediaItem)
        val oldSpec = DataSpec.Builder().setUri(media.localConfiguration!!.uri).setKey(media.localConfiguration!!.uri.toString()).build()
        val oldSource = requireNotNull(SongCachePlaybackMedia.productionDataSources).createDataSource()
        try {
            assertNull(PlaybackService.replaceLocal(listOf(track("C", "replacement")), eligibility, references, 0, false, target))
            await("replacement consumes C PCM") { controller!!.isPlaying && SongCachePlaybackMedia.bytes(0x3333) > 0 }
            val opened = opens.get()
            try { onLoader { oldSource.open(oldSpec) }; fail("Old queue must not borrow its successor's grant") }
            catch (_: java.io.IOException) { }
            assertEquals(opened, opens.get())
            assertEquals("replacement", PlaybackMedia.libraryItem(controller!!.currentMediaItem!!)?.entryId)
            assertNoTraffic()
        } finally { oldSource.close() }
    }

    @Test fun onlyFreshValidatedOrdinaryOnlinePlayRearmsServiceAfterErasingLocalQueueAndHistory() = runBlocking {
        play()
        val actual = requireNotNull(service).get()
        val cacheField = PlaybackService::class.java.getDeclaredField("audioCache").apply { isAccessible = true }
        val factoryField = PlaybackService::class.java.getDeclaredField("onlineSources").apply { isAccessible = true }
        val playerField = PlaybackService::class.java.getDeclaredField("player").apply { isAccessible = true }
        val solePlayer = requireNotNull(playerField.get(actual))
        val coldFactory = requireNotNull(factoryField.get(actual))
        assertNull("A cold offline service has no automatic-cache binding", cacheField.get(actual))
        graph.setOfflineMode(false)
        assertTrue(PlaybackService.isLocalOnlyQueue())
        await("controller observes mode exit pause") { !controller!!.playWhenReady }
        assertNoTraffic()
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path == "/api/auth/login" && request.method == "POST") MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setHeader("Set-Cookie", "spofify_session=offline-test-cookie; Path=/; HttpOnly; Max-Age=3600")
                    .setBody("""{"accountId":"saved-account","username":"listener","role":"user","csrfToken":"offline-test-csrf"}""")
                else MockResponse().setResponseCode(500)
        }
        val client = requireNotNull(graph.client)
        client.login("listener", "synthetic-password") // Genuine API-authenticated Session, not a fabricated DTO.
        assertEquals("/api/auth/login", web.takeRequest(1, TimeUnit.SECONDS)?.path)
        val genuineSession = requireNotNull(client.currentSession())
        val genuineCache = requireNotNull(graph.audioCache(client, genuineSession))
        assertNull("Login/mode exit alone must not rearm the retained local service", cacheField.get(actual))
        assertSame(coldFactory, factoryField.get(actual))
        assertTrue(PlaybackService.isLocalOnlyQueue())
        assertFalse(controller!!.playWhenReady)
        assertEquals(1, web.requestCount)
        val rearmed = mutableListOf<PlaybackSnapshot>()
        PlaybackService.onServiceActiveChanged = { active -> if (active) {
            assertFalse(PlaybackService.isLocalOnlyQueue())
            rearmed += PlaybackService.exportLocalSnapshot()
        } }
        assertNotNull(PlaybackService.replacePrepared(emptyList(), client, 0, false, true))
        assertTrue(PlaybackService.isLocalOnlyQueue())
        assertTrue(rearmed.isEmpty())
        assertNull("Invalid ordinary Play must not rebind automatic retention", cacheField.get(actual))
        assertNull(PlaybackService.replacePrepared(listOf(PlaybackMedia.create(track("C", "online-selection"), client)),
            client, 0, false, true))
        assertEquals(1, rearmed.size)
        assertEquals(PlaybackSnapshot(), rearmed.single())
        assertSame("Same real service-owned player across local-to-online transition", solePlayer, playerField.get(actual))
        assertSame(genuineSession, client.currentSession())
        assertSame("Fresh ordinary Play binds the graph's genuinely validated cache", genuineCache, cacheField.get(actual))
        assertNotSame(coldFactory, factoryField.get(actual))
        // Inspect the actual existing production MediaSourceFactory's new data source, not
        // merely the service field. It must no longer capture the cold-start null cache.
        val rebound = requireNotNull(SongCachePlaybackMedia.productionDataSources).createDataSource()
        try {
            val capturedCache = rebound.javaClass.getDeclaredField("session").apply { isAccessible = true }.get(rebound)
            assertSame(genuineCache, capturedCache)
        } finally { rebound.close() } // Unopened: no lease, focus, prepare, or audible acceptance.
        assertTrue(PlaybackService.displayLocalSnapshot().queue.none { it.entryId in setOf("first", "duplicate", "last") })
        // No online start/lease success is fabricated here. Existing online authority tests
        // cover admission; this regression observes the exact empty-before-rearm boundary.
    }

    @Test fun ordinaryOnlineCollectionNowUsesGenuineAuthenticatedAdmissionAfterRetiringLocalProvenance() = runBlocking {
        play()
        graph.setOfflineMode(false)
        await("mode exit pauses saved output") { !controller!!.playWhenReady }
        assertNoTraffic()
        val publications = CopyOnWriteArrayList<PlaybackSnapshot>()
        val registrations = AtomicInteger()
        val collectionReads = AtomicInteger()
        val unexpected = CopyOnWriteArrayList<String>()
        web.dispatcher = object : Dispatcher() {
            private var device = ""
            private var snapshot = PlaybackSnapshot()
            private var metadata = QueueIntentState("server-empty", 1, "tracked", emptyList(), emptyList())
            private var sequence = 0L
            private var published = false
            private fun response(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            private fun view() = PlaybackDevicesView(device,
                if (device.isEmpty()) emptyList() else listOf(PlaybackDevice(device, "Synthetic online phone", true)),
                device, 1, if (device.isEmpty()) 0 else 8000, state = snapshot, stateSeq = sequence, stateAgeMs = 0,
                transfer = null, directives = emptyList(), commands = emptyList(), queueIntents = QueueIntentsView(1,
                    if (device.isEmpty()) emptyList() else listOf(QueueIntentReceiver(device, 1)),
                    if (!published || device.isEmpty()) null else QueueIntentBinding(device, metadata.receiverId,
                        metadata.revision, snapshot.queue.getOrNull(snapshot.position)?.key, snapshot.occurrence?.eventId),
                    metadata, emptyList(), emptyList()))
            @Synchronized override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/api/auth/login" && request.method == "POST") return response(
                    """{"accountId":"saved-account","username":"listener","role":"user","csrfToken":"online-csrf"}""")
                    .setHeader("Set-Cookie", "spofify_session=online-cookie; Path=/; HttpOnly; Max-Age=3600")
                assertEquals("spofify_session=online-cookie", request.getHeader("Cookie"))
                if (request.method == "POST") assertEquals("online-csrf", request.getHeader("X-CSRF-Token"))
                if (request.path == "/api/library/albums/online-album/playback") {
                    collectionReads.incrementAndGet()
                    return response("""{"context":{"kind":"album","id":"online-album","name":"Online album"},"items":[{"kind":"track","id":"online-track","name":"Online track","artists":[],"hasAudio":true,"durationMs":12000}],"total":1,"limit":1,"truncated":false}""")
                }
                if (request.path == "/api/library/tracks/online-track/audio") return MockResponse()
                    .setHeader("Content-Type", "audio/wav").setBody(okio.Buffer().write(wav(0x7777)))
                if (request.path != "/api/me/player") {
                    unexpected += "${request.method} ${request.path}"
                    return MockResponse().setResponseCode(500)
                }
                if (request.method == "GET") return response(view().toJson().toString())
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                return when (body.getValue("action").jsonPrimitive.content) {
                    "register" -> {
                        assertEquals(JsonPrimitive(1), body["queueIntentsVersion"])
                        registrations.incrementAndGet()
                        device = "online-phone"
                        response(view().toJson().toString())
                    }
                    "poll" -> {
                        body["state"]?.let {
                            snapshot = PlaybackSnapshot.fromJson(it.jsonObject)
                            metadata = QueueIntentState.fromJson(body.getValue("queueIntentState").jsonObject, snapshot)
                            publications += snapshot
                            published = true
                        }
                        body["stateSeq"]?.let { sequence = it.jsonPrimitive.long }
                        response(view().toJson().toString())
                    }
                    "leave" -> response(view().toJson().toString())
                    else -> { unexpected += "Unexpected player action"; MockResponse().setResponseCode(500) }
                }
            }
        }
        val client = requireNotNull(graph.client)
        client.login("listener", "synthetic-password") // Production API creates and validates the Session.
        val login = requireNotNull(client.currentSession())
        assertTrue(PlaybackService.isLocalOnlyQueue())
        assertNull(serviceField("audioCache"))
        assertEquals(0, registrations.get())
        val solePlayer = requireNotNull(serviceField("player"))
        val receiverCallback = PlaybackService.onServiceActiveChanged
        val rearmed = mutableListOf<PlaybackSnapshot>()
        PlaybackService.onServiceActiveChanged = { active ->
            if (active) {
                assertFalse(PlaybackService.isLocalOnlyQueue())
                rearmed += PlaybackService.exportLocalSnapshot()
                assertEquals("No saved work may be receiver-visible, even inline", PlaybackSnapshot(), rearmed.last())
            }
            receiverCallback?.invoke(active)
        }
        val connection = PlaybackConnection(app)
        connection.connect()
        val bound = PlaybackConnection::class.java.getDeclaredField("controller").apply { isAccessible = true }
        await("real online control binding") { bound.get(connection) != null }
        val selection = QueueIntentSelection.Collection(PlaybackContext("album", "online-album", "Online album"))
        try {
            var low: PlaybackActionResult? = null
            connection.queueIntent(selection, client, PlaybackListAction.QUEUE_LOW, null, null, { true }) { low = it }
            assertEquals(PlaybackActionStatus.UNAVAILABLE, low?.status)
            var stale: PlaybackActionResult? = null
            connection.queueIntent(selection, client, PlaybackListAction.PLAY_NOW, null, null, { false }) { stale = it }
            await("obsolete online Play is refused without rearming") { stale != null }
            assertEquals(PlaybackActionStatus.STALE, stale?.status)
            assertTrue(PlaybackService.isLocalOnlyQueue())
            assertTrue(rearmed.isEmpty())
            var result: PlaybackActionResult? = null
            var delayed: PlaybackActionResult? = null
            connection.queueIntent(selection, client, PlaybackListAction.PLAY_NOW, null, null, { true }) { result = it }
            // A second captured local incarnation cannot borrow the first action's handoff.
            connection.queueIntent(selection, client, PlaybackListAction.PLAY_NOW, null, null, { true }) { delayed = it }
            await("ordinary Now finishes production registration, execution and PCM") {
                result != null && delayed != null && controller!!.isPlaying && SongCachePlaybackMedia.bytes(0x7777) > 0
            }
            assertEquals(PlaybackActionStatus.PLAYING, result?.status)
            assertEquals(PlaybackActionStatus.STALE, delayed?.status)
            assertEquals(1, registrations.get())
            assertEquals(1, collectionReads.get())
            assertEquals(listOf(PlaybackSnapshot()), rearmed)
            assertSame(solePlayer, serviceField("player"))
            assertSame(login, client.currentSession())
            assertSame(graph.audioCache(client, login), serviceField("audioCache"))
            assertEquals(listOf("online-track"), PlaybackService.exportLocalSnapshot().queue.map { it.id })
            assertTrue(publications.isNotEmpty())
            assertTrue(publications.all { state -> state.queue.all { it.id == "online-track" } })
            assertTrue(unexpected.toString(), unexpected.isEmpty())
            assertFalse(PlaybackService.isLocalOnlyQueue())
        } finally { connection.release() }
    }

    @Test fun localCloseStaysStoppedAcrossModeChangesWithoutPassiveSourceReads() {
        play()
        command("close")
        val opened = opens.get()
        val started = SongCachePlaybackMedia.starts.get()
        assertEquals(0, controller!!.mediaItemCount)
        graph.setOfflineMode(false)
        graph.setOfflineMode(true)
        pump(250)
        assertFalse(controller!!.playWhenReady)
        assertEquals(0, controller!!.mediaItemCount)
        assertEquals(opened, opens.get())
        assertEquals(started, SongCachePlaybackMedia.starts.get())
        assertNoTraffic()
    }

    @Test fun modeExitRetainsLocalProvenanceAndBlocksEveryReceiverAdoptionAndRecoveryBridge() = runBlocking {
        play()
        val retained = PlaybackService.displayLocalSnapshot().queue
        graph.setOfflineMode(false)
        pump(250)
        assertTrue(PlaybackService.isLocalOnlyQueue())
        assertFalse(controller!!.playWhenReady)
        assertEquals(retained, PlaybackService.displayLocalSnapshot().queue)
        assertEquals(PlaybackSnapshot(), endpoint.exportSnapshot())
        assertNull(endpoint.exportQueueIntentState())
        assertNull(endpoint.captureRevisionSync())
        val continuation = dev.spofify.mobile.feature.devices.CachedPlaybackPermission { true }
        assertFalse(endpoint.retainCachedOutput(continuation))
        assertNull(endpoint.continuingOutputWitness(continuation))
        // Even a caller presenting a nominally valid ONLINE permit cannot adopt local work.
        val forged = PlaybackAuthority(91, 2) { true }
        assertFalse(endpoint.renewContinuingOutput(continuation, forged, Any()))
        assertEquals("rejected", endpoint.activate(PlaybackSnapshot(), forged).status)
        assertEquals("rejected", endpoint.activatePreparedResume(PlaybackSnapshot(), forged).status)
        assertEquals("rejected", endpoint.execute(PlaybackDeviceCommand.transport("resume"), forged).status)
        assertEquals(retained, PlaybackService.displayLocalSnapshot().queue)
        assertFalse(controller!!.playWhenReady)
        graph.setOfflineMode(true)
        pump(200)
        assertFalse("Mode entry does not resume the retained queue", controller!!.playWhenReady)
        assertNoTraffic()
    }

    @Test fun noisyAndFocusLossPauseNormallyAndGainNeverResumes() {
        play()
        app.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        await("normal noisy pause") { !controller!!.playWhenReady && controller!!.playbackState == Player.STATE_IDLE }
        assertTrue(PlaybackService.isLocalOnlyQueue())
        command("resume")
        await("deliberate resume") { controller!!.isPlaying }
        val listener = focusListener()
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertFalse(mediaSession!!.player.playWhenReady)
        await("controller observes focus-loss drainage") { !controller!!.playWhenReady }
        assertFalse(controller!!.playWhenReady)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        pump(200)
        assertFalse(controller!!.playWhenReady)
        assertEquals(Player.STATE_IDLE, controller!!.playbackState)
        assertNoTraffic()
    }

    @Test fun noisyBeforeDelayedControllerAttachmentCancelsThePendingLocalPlay() {
        delayedBindingInterruption(focus = false)
    }

    @Test fun focusLossBeforeDelayedControllerAttachmentCancelsThePendingLocalPlay() {
        delayedBindingInterruption(focus = true)
    }

    private fun delayedBindingInterruption(focus: Boolean) {
        play()
        val retained = PlaybackService.displayLocalSnapshot().queue
        val connection = PlaybackConnection(app)
        val pending = PlaybackConnection::class.java.getDeclaredField("pendingPlay").apply { isAccessible = true }
        val binding = PlaybackConnection::class.java.getDeclaredField("controller").apply { isAccessible = true }
        try {
            connection.playLocal(listOf(track("C", "late-selection")), eligibility, references, context = target)
            assertNull("Connection binding must still be delayed at the interruption", binding.get(connection))
            assertNotNull("An actual local Play payload must be waiting", pending.get(connection))
            val epoch = PlaybackRequests.epoch
            if (focus) focusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            else {
                // Dispatch the real registered receiver synchronously before the queued bind
                // callback. This controls ordering, not authority, source or Play delivery.
                val receiver = PlaybackService::class.java.getDeclaredField("noisyReceiver").apply { isAccessible = true }
                    .get(requireNotNull(service).get()) as BroadcastReceiver
                receiver.onReceive(app, Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            }
            assertNotEquals("Interruption must fence connections, not only installed starts", epoch, PlaybackRequests.epoch)
            assertNotNull("Exercise late delivery, not merely a pre-cleared UI payload", pending.get(connection))
            assertFalse("Actual output pauses before delayed bind completion", mediaSession!!.player.playWhenReady)
            val beforeOpens = opens.get()
            val beforeStarts = SongCachePlaybackMedia.starts.get()
            // Complete the actual production bind/future/delivery chain using the service's
            // real binder. No controller injection or private delivery invocation.
            await("late real bind delivers and rejects the retained request") {
                binding.get(connection) != null && pending.get(connection) == null
            }
            pump(250)
            assertNull(pending.get(connection))
            assertEquals(retained, PlaybackService.displayLocalSnapshot().queue)
            assertFalse(controller!!.playWhenReady)
            assertEquals(beforeOpens, opens.get())
            assertEquals(beforeStarts, SongCachePlaybackMedia.starts.get())
            assertEquals(0L, SongCachePlaybackMedia.bytes(0x3333))
            assertNoTraffic()
        } finally { connection.release() }
    }

    private fun focusListener(): AudioManager.OnAudioFocusChangeListener {
        val manager = requireNotNull(service).get().getSystemService(AudioManager::class.java)
        val request = requireNotNull(shadowOf(manager).lastAudioFocusRequest?.audioFocusRequest)
        return AudioFocusRequest::class.java.getDeclaredMethod("getOnAudioFocusChangeListener").apply {
            isAccessible = true
        }.invoke(request) as AudioManager.OnAudioFocusChangeListener
    }

    @Test fun logoutReleasesTheActualPlayingServiceBeforeBlockingAdapterRetirement() {
        play()
        val actual = requireNotNull(service).get()
        val player = PlaybackService::class.java.getDeclaredField("player").apply { isAccessible = true }
        assertNotNull(player.get(actual))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val epoch = PlaybackRequests.epoch
        graph.offlineLibrary = object : OfflineLibraryAccess {
            override val revision = savedOwner.revision
            override suspend fun collections(origin: String, accountId: String) = savedOwner.collections(origin, accountId)
            override fun retire() {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                savedOwner.retire()
            }
        }
        val witness = sourceExecutor.submit(Callable {
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                assertNotEquals(epoch, PlaybackRequests.epoch)
                assertNull(graph.localEligibilityState.value)
                assertNull(graph.offlineLibrary)
                assertFalse(graph.localPlaybackIsCurrent(eligibility))
                assertNull("The real audible player must be RELEASED before slow purge begins", player.get(actual))
            } finally { release.countDown() }
        })
        try {
            graph.fenceForLogout()
            try { witness.get(3, TimeUnit.SECONDS) }
            catch (failure: ExecutionException) { throw requireNotNull(failure.cause) }
            pump(100)
            assertFalse(controller!!.playWhenReady)
            assertNoTraffic()
        } finally { release.countDown() }
    }

    @Test fun staleEligibilitySameAccountCannotReplaceAndLogoutFencesAnAlreadyOpenReader() {
        play()
        val otherIncarnation = OfflineEligibility(eligibility.origin, eligibility.accountId, eligibility.username)
        assertNotNull(PlaybackService.replaceLocal(rows, otherIncarnation, references, 0, false, target))
        val media = requireNotNull(mediaSession!!.player.currentMediaItem)
        val source = requireNotNull(SongCachePlaybackMedia.productionDataSources).createDataSource()
        onLoader { source.open(DataSpec.Builder().setUri(media.localConfiguration!!.uri).setKey(media.localConfiguration!!.uri.toString()).build()) }
        assertTrue(onLoader { source.read(ByteArray(8), 0, 8) } > 0)
        graph.fenceForLogout()
        val before = reads.get()
        try {
            onLoader { source.read(ByteArray(8), 0, 8) }
            fail("Retired local eligibility must fence an open reader")
        } catch (_: java.io.IOException) { }
        finally { source.close() }
        assertEquals(before, reads.get())
        pump(200)
        assertFalse(controller!!.playWhenReady)
        assertNotNull(PlaybackService.replaceLocal(rows, eligibility, references, 0, false, target))
        assertNoTraffic()
    }

    private fun <T> onLoader(block: () -> T): T = try {
        sourceExecutor.submit(Callable { block() }).get(5, TimeUnit.SECONDS)
    } catch (failure: ExecutionException) { throw requireNotNull(failure.cause) }

    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.MILLISECONDS)
            if (predicate()) return
            Thread.sleep(5)
        }
        fail("Timed out: $label; state=${controller?.playbackState}; pcm=${SongCachePlaybackMedia.samples.get()}")
    }

    private fun pump(ms: Long) {
        repeat((ms / 5).toInt()) {
            shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.MILLISECONDS)
            Thread.sleep(5)
        }
    }

    private fun wav(marker: Int): ByteArray {
        val size = 12000 * 16 // 12 seconds, 8kHz mono, signed 16-bit PCM.
        return ByteBuffer.allocate(size + 44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(size + 36); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1.toShort()); putShort(1.toShort()); putInt(8000); putInt(16000)
            putShort(2.toShort()); putShort(16.toShort()); put("data".toByteArray()); putInt(size)
            repeat(size / 2) { putShort(marker.toShort()) }
        }.array()
    }
}
