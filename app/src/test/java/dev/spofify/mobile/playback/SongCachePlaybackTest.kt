package dev.spofify.mobile.playback

import android.app.ActivityManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import dev.spofify.mobile.AppGraph
import dev.spofify.mobile.LoginStore
import dev.spofify.mobile.RememberedLogin
import dev.spofify.mobile.SpofifyApplication
import dev.spofify.mobile.core.*
import dev.spofify.mobile.playback.cache.SongCache
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import dev.spofify.mobile.feature.devices.PlaybackCoordinator
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Portable service/AppGraph blackbox regressions, not native/acoustic or real-broker evidence.
 * Distinct WAV bytes pass through the production authenticated DataSource and unchanged
 * DefaultMediaSourceFactory. The sole player substitution is a bounded PCM-consuming renderer.
 * Network evidence is the exact authenticated request trace plus real consumed PCM markers;
 * scheduled response sizes are NOT transmitted-byte measurements (especially on cancellation).
 * Actual DataSource-read bandwidth accounting belongs to the focused storage helper tests.
 * No private cache/plan/player shape, mocked cache hit, SilenceMediaSource or fake endpoint.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SpofifyApplication::class,
    shadows = [SongCachePlaybackMedia::class, SongCachePlaybackMedia.LoaderRelease::class], instrumentedPackages = ["androidx.media3.exoplayer"])
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(UnstableApi::class)
class SongCachePlaybackTest {
    private var timeoutDiagnostics: (() -> String)? = null

    private class Sessions(initial: StoredSession? = null) : SessionStore {
        @Volatile private var saved = initial
        override fun read() = saved
        override fun write(value: StoredSession?) { saved = value }
    }
    private class Logins : LoginStore {
        override fun read(): RememberedLogin? = null
        override fun write(value: RememberedLogin?) = Unit
    }


    private inner class Journey(remote: Boolean = false, private val limits: SongCache.Limits = SongCache.Limits(),
        sampleRate: Int = 8000, longCurrent: Boolean = false, longFutureB: Boolean = false,
        normalTrackSeconds: Int = 12, openCacheWrite: ((File) -> OutputStream)? = null) : AutoCloseable {
        val app = ApplicationProvider.getApplicationContext<SpofifyApplication>()
        private val endpoint = Media3PlaybackEndpoint(app)
        val room = SongCacheProtocolRoom(remote, sampleRate, longCurrent, longFutureB, normalTrackSeconds)
        val web = MockWebServer().apply { dispatcher = room; start(InetAddress.getByName("127.0.0.1"), 0) }
        val server = ServerAddress.parse("http://127.0.0.1:${web.port}")
        val sessions = Sessions()
        private val graphs = mutableListOf<AppGraph>()
        var graph: AppGraph
            private set
        var client: SpofifyClient
            private set
        private var service: ServiceController<PlaybackService>? = null
        private var controls: PlaybackConnection? = null
        private var coordinator: PlaybackCoordinator? = null
        var controller: MediaController? = null
            private set
        lateinit var session: MediaSession
            private set
        init {
            app.graph.clear()
            app.getSharedPreferences("connection", 0).edit().clear().commit()
            SongCachePlaybackMedia.reset()
            graph = install(AppGraph(app, sessions, Logins(), audioCacheLimits = limits))
            if (openCacheWrite != null) {
                graph.songCache.close()
                graph.songCache.awaitStorageForTest()
                AppGraph::class.java.getDeclaredField("songCache").apply {
                    isAccessible = true; set(graph, SongCache(app.cacheDir, limits, openWrite = openCacheWrite))
                }
            }
            client = graph.configure(server)
            runBlocking { client.login("listener", "synthetic-password") }
            installCoordinator()
            timeoutDiagnostics = ::diagnostics
        }
        private fun install(value: AppGraph): AppGraph {
            SpofifyApplication::class.java.getDeclaredField("graph").apply { isAccessible = true; set(app, value) }
            graphs += value
            return value
        }
        private fun installCoordinator() {
            if (graph.playbackCoordinator === coordinator && coordinator != null) return
            graph.playbackCoordinator?.close()
            val scope = AppGraph::class.java.getDeclaredField("coordinationScope").apply { isAccessible = true }.get(graph) as CoroutineScope
            val owner = PlaybackCoordinator(client, endpoint, scope,
                deviceIdentity = { "phone" to "Phone" },
                openTransport = { _, _, id, _ -> room.open(requireNotNull(client.validatedSessionIdentity()), id) })
            coordinator = owner
            AppGraph::class.java.getDeclaredField("playbackCoordinator").apply { isAccessible = true; set(graph, owner) }
            PlaybackService.onServiceActiveChanged = { active ->
                if (graph.playbackCoordinator === owner) owner.setServiceActive(active)
            }
        }
        fun startService() {
            installCoordinator()
            val before = SongCachePlaybackMedia.factories.get()
            val built = Robolectric.buildService(PlaybackService::class.java).create().also { service = it }
            val actual = built.get()
            shadowOf(actual.getSystemService(ActivityManager::class.java)).setProcesses(listOf(
                ActivityManager.RunningAppProcessInfo().apply {
                    pid = android.os.Process.myPid(); importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                }))
            shadowOf(actual.getSystemService(AudioManager::class.java)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            await("current socket admission and initial stopped setup") {
                val state = graph.playbackCoordinator!!.state.value
                state.connected && (state.selectedDeviceId == "remote" || room.activationAcks.get() > 0 && state.snapshot?.transfer == null)
            }
            assertEquals(before + 1, SongCachePlaybackMedia.factories.get())
            val who = MediaSession.ControllerInfo.createTestOnlyControllerInfo(actual.packageName, 100,
                android.os.Process.myUid(), 0, 0, true, Bundle.EMPTY, true)
            session = requireNotNull(actual.onGetSession(who))
            val future = MediaController.Builder(actual, session.token).buildAsync()
            await("real controller connection") { future.isDone }
            controller = future.get(1, TimeUnit.SECONDS)
        }
        fun stopService() {
            releaseBorrowedRecoveryControls()
            controller?.release(); controller = null
            service?.destroy(); service = null
            pump(80)
        }
        fun recreateGraph(): SpofifyClient {
            stopService()
            val saved = requireNotNull(sessions.read())
            graph.configure(server)
            graph.songCache.awaitStorageForTest()
            graph = install(AppGraph(app, Sessions(saved), Logins(), audioCacheLimits = limits))
            coordinator = null
            client = requireNotNull(graph.client)
            return client
        }
        /** One-way presentation of the current canonical server state, never an upload/import. */
        fun snapshot(): PlaybackSnapshot = graph.playbackCoordinator?.state?.value?.snapshot?.displayProjection() ?: PlaybackSnapshot()
        fun observation() = endpoint.observe()
        fun upcoming() = acceptedControls().state.value.upcoming
        fun interactionKey() = acceptedControls().state.value.interactionKey
        private fun acceptedControls(): PlaybackConnection = controls ?: PlaybackConnection(app).also { connection ->
            val binding = requireNotNull(controller)
            PlaybackConnection::class.java.getDeclaredField("controller").apply { isAccessible = true; set(connection, binding) }
            val listener = PlaybackConnection::class.java.getDeclaredField("listener").let {
                it.isAccessible = true; it.get(connection) as Player.Listener
            }
            binding.addListener(listener)
            connection.bindExplicitPlayOrigin {
                val source = client; val login = source.currentSession(); val lifetime = service
                val current: () -> Boolean = { service === lifetime && lifetime != null && controller === binding &&
                    graph.client === source && login != null && source.currentSession() === login }
                current
            }
            connection.connect()
            controls = connection
        }
        fun releaseBorrowedRecoveryControls() {
            val connection = controls ?: return
            val field = PlaybackConnection::class.java.getDeclaredField("controller").apply { isAccessible = true }
            val listener = PlaybackConnection::class.java.getDeclaredField("listener").apply { isAccessible = true }.get(connection) as Player.Listener
            controller?.removeListener(listener)
            field.set(connection, null)
            connection.release()
            controls = null
        }
        private fun submit(action: PlaybackListAction, ids: List<String>, start: Int = 0,
            onResult: (PlaybackActionResult) -> Unit) {
            val items = ids.mapIndexed { index, id -> track(id, "$id-$index").copy(durationMs = room.durationMillis(id)) }
            val context = room.catalog(items)
            val selection = if (action == PlaybackListAction.PLAY_NOW)
                LibraryPlaybackSelection.Track(LibraryPlaybackTrackRef(items[start].id, entryId = items[start].entryId), context)
            else LibraryPlaybackSelection.Collection(context)
            val connection = acceptedControls()
            connection.queueIntent(selection, client, action, connection.state.value.interactionKey,
                graph.playbackCoordinator!!.captureBinding(), onResult = onResult)
        }
        fun play(vararg ids: String) = playAt(0, *ids)
        fun playAt(start: Int, vararg ids: String) {
            var receipt: PlaybackActionResult? = null
            submit(PlaybackListAction.PLAY_NOW, ids.toList(), start) { receipt = it }
            await("actual started ${ids[start]} PCM through production source") {
                controller!!.isPlaying && controller!!.currentPosition >= 50 && consumed(ids[start]) > 0 && receipt != null
            }
            assertNull(controller!!.playerError)
            awaitLocalPublication("initial Play")
        }
        fun awaitLocalPublication(label: String) {
            await("canonical state and actual observation/controller projection after $label") {
                val owner = graph.playbackCoordinator!!.state.value
                val value = owner.snapshot
                val actual = observation()
                value != null && owner.connected && value.transfer == null && value.selectedConnectionId == owner.connectionId &&
                    value.playback.hasReportedPlayback && value.playback.isPlaying == actual.isPlaying &&
                    controller?.isPlaying == actual.isPlaying && controller?.currentMediaItem?.mediaId == value.state.queue.getOrNull(value.state.position)?.id
            }
        }
        fun pauseThroughAcceptedControls() { assertTrue(observation().isPlaying); acceptedControls().toggle() }
        fun resumePreparedThroughAcceptedControls() { assertFalse(observation().isPlaying); acceptedControls().toggle() }
        @Suppress("UNCHECKED_CAST")
        fun pendingReadForReadOnlyProbe(): () -> Boolean =
            PlaybackService::class.java.getDeclaredField("preparingRead").apply { isAccessible = true }
                .get(requireNotNull(service).get()) as () -> Boolean
        fun seekThroughAcceptedControls(positionMs: Long) = acceptedControls().seekTo(positionMs)
        fun selectThroughAcceptedControls(index: Int) = acceptedControls().select(index)
        fun remove(occurrenceId: String) = acceptedControls().removeUpcoming(requireNotNull(interactionKey()), occurrenceId) { true }
        fun move(occurrenceId: String, index: Int) = acceptedControls().moveUpcoming(requireNotNull(interactionKey()), occurrenceId, index)
        fun reconnect() { graph.playbackCoordinator!!.retry() }
        fun pause() {
            awaitLocalPublication("before Pause")
            controller!!.pause()
            await("actual paused output") { !controller!!.isPlaying && !observation().isPlaying }
            awaitLocalPublication("Pause")
        }
        fun preparePaused(id: String) {
            room.preparePaused(track(id, "$id-paused-probe").copy(durationMs = room.durationMillis(id)))
            await("targeted silent preparation retains exact $id") {
                snapshot().queue.map { it.id } == listOf(id) && !observation().isPlaying &&
                    controller!!.currentMediaItem?.mediaId == id && !controller!!.playWhenReady && room.snapshot.transfer == null
            }
            awaitLocalPublication("paused $id setup")
        }
        fun resume() {
            awaitLocalPublication("before deliberate Resume")
            val id = requireNotNull(controller!!.currentMediaItem).mediaId
            val pcm = consumed(id); val position = controller!!.currentPosition
            controller!!.play()
            await("deliberate Resume starts exact $id and consumes new PCM") {
                controller!!.isPlaying && controller!!.currentPosition > position + 50 && consumed(id) > pcm
            }
            awaitLocalPublication("deliberate Resume")
            assertNull(controller!!.playerError)
        }
        fun setFocusResponse(response: Int) {
            shadowOf(requireNotNull(service).get().getSystemService(AudioManager::class.java)).setNextFocusRequestResponse(response)
        }
        fun awaitUnpinnedComplete(count: Int) = await("$count whole cache entries with pins released") {
            assertBounded().let { it.entries == count && it.complete == count && it.pinned == 0 }
        }
        fun admitEToFullCache() {
            awaitUnpinnedComplete(3)
            room.delayedTrack = "E"
            // A fresh explicit queue edit may demand bounded cache work, never audible
            // authority. Production must guard parked speculation separately from its retired lease.
            action(PlaybackListAction.QUEUE_HIGH, "E")
            awaitFetched("E")
            await("E evicts one whole entry and owns its incomplete reservation") {
                assertBounded().let { it.entries == 3 && it.complete == 2 && it.pinned == 1 }
            }
            awaitUnpinnedComplete(3)
            room.delayedTrack = null
            assertEquals(0L, consumed("E")); assertFalse(controller!!.isPlaying)
            awaitLocalPublication("silent E admission")
        }
        fun assertFreshCacheHit(id: String) {
            val before = room.audio.filter { it.id == id }
            assertTrue(before.any { it.method == "GET" })
            val pcm = consumed(id)
            preparePaused(id); resume()
            assertTrue(consumed(id) > pcm)
            assertEquals("Fresh $id uses retained PCM without GET/HEAD", before, room.audio.filter { it.id == id })
        }
        fun assertFreshCacheMiss(id: String) {
            val gets = room.audio.count { it.id == id && it.method == "GET" }; val pcm = consumed(id)
            preparePaused(id); resume()
            assertTrue(consumed(id) > pcm)
            assertEquals(gets + 1, room.audio.count { it.id == id && it.method == "GET" })
        }
        fun repeat(mode: Int, wire: String) {
            awaitLocalPublication("before repeat $wire")
            controller!!.repeatMode = mode
            await("canonical absolute repeat $wire") { snapshot().repeat == wire }
            awaitLocalPublication("repeat $wire")
        }
        fun repeatOne() { repeat(Player.REPEAT_MODE_ALL, "all"); repeat(Player.REPEAT_MODE_ONE, "one") }
        fun action(action: PlaybackListAction, vararg ids: String) {
            awaitLocalPublication("before $action")
            var result: PlaybackActionResult? = null
            submit(action, ids.toList()) { result = it }
            await("terminal $action receipt") { result != null }
            val detail = requireNotNull(result!!.serverResult)
            assertEquals("accepted", detail.outcome); assertEquals(ids.size, detail.acceptedCount)
            assertEquals("unchanged", detail.execution)
            awaitLocalPublication(action.toString())
        }
        var playNowResult: PlaybackActionResult? = null
            private set
        fun beginPlayNow(id: String) {
            awaitLocalPublication("before Play now")
            playNowResult = null
            submit(PlaybackListAction.PLAY_NOW, listOf(id)) { playNowResult = it }
        }
        private fun diagnostics(): String = "canonical=${snapshot()}; observation=${observation()}; " +
            "owner=${graph.playbackCoordinator?.state?.value?.connected}; " +
            "failure=${controller?.playerError?.let(::playbackFailureDiagnostic)}; " +
            "inventory=${runCatching { graph.songCache.inventory() }.getOrNull()}; " +
            "audio=${room.audio.takeLast(32).map { it.method + ":" + it.id + ":" + it.range }}"
        fun awaitFetched(vararg ids: String) = await("permitted window ${ids.toList()}") { room.fetched().containsAll(ids.toList()) }
        fun assertBounded(): SongCache.Inventory = graph.songCache.inventory().also {
            assertTrue("Owned bytes exceed fixture budget: $it", it.ownedBytes <= limits.maxBytes)
            assertTrue("Unique entries exceed fixture budget: $it", it.entries <= limits.maxTracks)
            assertFalse(it.bypassed); assertFalse(it.cleanupFailed)
        }
        fun focusRequest() = shadowOf(requireNotNull(service).get().getSystemService(AudioManager::class.java)).lastAudioFocusRequest
        fun assertForegroundStopped() { assertTrue(shadowOf(requireNotNull(service).get()).isForegroundStopped) }
        fun foregroundNotificationId() = shadowOf(requireNotNull(service).get()).lastForegroundNotificationId
        fun assertDormantServiceCannotOpenCache() {
            val factories = SongCachePlaybackMedia.factories.get(); val admissions = room.admissions.get()
            val dormant = Robolectric.buildService(PlaybackService::class.java).create()
            try {
                pump(100)
                assertEquals(factories, SongCachePlaybackMedia.factories.get()); assertEquals(admissions, room.admissions.get())
                assertEquals(0, shadowOf(dormant.get()).lastForegroundNotificationId)
                assertTrue(snapshot().queue.isEmpty())
            } finally { dormant.destroy() }
        }
        fun assertHealthy() {
            assertTrue("Unexpected/unauthenticated requests: ${room.unexpected}", room.unexpected.isEmpty())
            assertTrue(room.mutations.none { it in setOf("Select", "Deselect") })
            assertEquals("Online history is server-owned; no client POST", 0, room.history.get())
            assertNull(controller?.playerError); assertEquals("", snapshot().error); assertBounded()
        }
        override fun close() {
            var failure: Throwable? = null
            fun cleanup(block: () -> Unit) { try { block() } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            } }
            cleanup { stopService() }
            graphs.asReversed().forEach { retired -> cleanup { retired.clear() } }
            graphs.asReversed().forEach { retired -> cleanup { retired.songCache.awaitStorageForTest() } }
            cleanup { pump(100) }; cleanup { web.shutdown() }
            SongCachePlaybackMedia.reset(); timeoutDiagnostics = null
            failure?.let { throw it }
        }
    }

    private fun verifyForkTemp() {
        val actual = requireNotNull(System.getProperty("java.io.tmpdir"))
        System.getenv("SPOFIFY_TEST_TMPDIR")?.let { expected -> assertEquals(expected, actual) }
    }

    @Test fun socketLossStopsWarmCacheAndReconnectCannotAutoplayOrReplay() {
        Journey(longCurrent = true).use { j ->
            j.startService(); j.play("A", "B")
            j.awaitFetched("B")
            await("whole next B retained through the real authenticated source") { j.assertBounded().complete == 1 }
            val starts = SongCachePlaybackMedia.starts.get()
            j.room.disconnect()
            await("socket loss synchronously drains actual cached/network audio") {
                !j.graph.playbackCoordinator!!.state.value.connected && !j.observation().isPlaying
            }
            val bytes = j.room.audio.toList()
            val samples = SongCachePlaybackMedia.samples.get()
            pump(6500)
            assertFalse(j.observation().isPlaying)
            assertEquals(samples, SongCachePlaybackMedia.samples.get())
            assertEquals(bytes, j.room.audio.toList())
            j.reconnect()
            await("fresh socket rejoins retained stopped room") {
                j.graph.playbackCoordinator!!.state.value.connected && j.room.snapshot.transfer == null
            }
            pump(150)
            assertEquals(starts, SongCachePlaybackMedia.starts.get())
            assertFalse(j.observation().isPlaying)
            assertEquals(bytes, j.room.audio.toList())
            j.play("B")
            assertTrue(consumed("B") > 0)
            assertEquals("Explicit reauthenticated execution reuses retained B, never passive reconnect", bytes.filter { it.id == "B" },
                j.room.audio.filter { it.id == "B" })
            j.assertHealthy()
        }
    }

    @Test fun expiredFiniteLeaseStopsActualOutputEvenWithWholeWarmAudio() {
        Journey().use { j ->
            j.startService(); j.play("A")
            await("whole current A retained") { j.assertBounded().complete == 1 }
            j.room.renewalsEnabled = false
            pump(8100) // Fixed protocol lifetime, not an extended retry/acceptance deadline.
            assertFalse(j.observation().isPlaying)
            val samples = SongCachePlaybackMedia.samples.get()
            pump(150)
            assertEquals(samples, SongCachePlaybackMedia.samples.get())
            assertEquals(0, j.room.history.get())
        }
    }

    @Test fun sameAccountReauthenticationCannotAuthorizeOldCachedOutputOrAutoplay() {
        Journey().use { j ->
            j.startService(); j.play("A")
            await("whole current A retained") { j.assertBounded().complete == 1 }
            val starts = SongCachePlaybackMedia.starts.get()
            runBlocking { j.client.login("listener", "synthetic-password") }
            await("new auth incarnation stops the old native executor") { !j.observation().isPlaying }
            val samples = SongCachePlaybackMedia.samples.get()
            pump(2200)
            assertEquals(samples, SongCachePlaybackMedia.samples.get())
            assertEquals(starts, SongCachePlaybackMedia.starts.get())
            assertFalse(j.observation().isPlaying)
            assertEquals(0, j.room.history.get())
        }
    }

    @Test fun quietParkedSelectionDoesNotEmitRecurringProgressLeaseOrRestartAudio() {
        Journey().use { j ->
            j.startService(); j.play("A"); j.pause()
            val starts = SongCachePlaybackMedia.starts.get()
            val requests = j.room.audio.toList()
            val reports = j.room.reports.get()
            pump(2200)
            assertEquals(reports, j.room.reports.get())
            assertEquals(starts, SongCachePlaybackMedia.starts.get())
            assertEquals(requests, j.room.audio.toList())
            assertFalse(j.observation().isPlaying)
            j.assertHealthy()
        }
    }

    @Test fun freshPlayAndOutOfBufferRangeConsumeActualAuthenticatedWav() {
        Journey(longCurrent = true).use { j ->
            j.startService()
            j.play("A")
            val pcm = consumed("A")
            j.controller!!.seekTo(90_000)
            await("real seek reopens authenticated HTTP range and consumes WAV samples") {
                j.controller!!.isPlaying && j.controller!!.currentPosition > 90_050 && consumed("A") > pcm &&
                    j.room.audio.any { it.id == "A" && it.range != null }
            }
            j.pause()
            j.resume()
            j.assertHealthy()
        }
    }


    @Test fun preparedResumeUsesAuthenticatedLoaderWithoutApplicationThreadAccess() {
        verifyForkTemp()
        val owned = requireNotNull(System.getenv("SPOFIFY_RUN_ROOT"))
        assertEquals("$owned/home", System.getProperty("user.home"))
        assertEquals("$owned/tmp", System.getProperty("java.io.tmpdir"))
        Journey().use { j ->
            j.startService(); j.play("Z"); j.pause(); j.preparePaused("A")
            val prepared = j.snapshot()
            val sameClient = j.client; val sameSession = requireNotNull(sameClient.currentSession())
            val sameController = requireNotNull(j.controller)
            val failures = CopyOnWriteArrayList<String>()
            val terminal = CopyOnWriteArrayList<PlaybackException>()
            val listener = object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) { terminal += error }
            }
            sameController.addListener(listener)
            SongCachePlaybackMedia.dataSourceFailure = { error, thread ->
                if (failures.size < 4) failures += thread.name + ":" + error.javaClass.name +
                    error.stackTrace.take(24).joinToString { it.className + "." + it.methodName }
            }
            val pcm = consumed("A")
            try {
                j.resumePreparedThroughAcceptedControls()
                await("prepared Resume consumes authenticated audio without application-thread-only worker reads") {
                    terminal.isNotEmpty() || sameController.isPlaying && sameController.currentPosition >= 50 && consumed("A") > pcm
                }
                assertTrue("Worker authorization must not consult Media3: $failures", failures.isEmpty())
                assertTrue(terminal.isEmpty()); assertTrue(consumed("A") > pcm)
                j.awaitLocalPublication("prepared Resume")
                assertEquals(prepared.queue, j.snapshot().queue)
                assertEquals(prepared.position, j.snapshot().position)
                assertEquals(prepared.occurrence?.eventId, j.snapshot().occurrence?.eventId)
                assertSame(sameClient, j.graph.client); assertSame(sameSession, sameClient.currentSession())
                assertSame(sameController, j.controller)
                j.assertHealthy()
            } finally {
                SongCachePlaybackMedia.dataSourceFailure = null
                sameController.removeListener(listener); j.releaseBorrowedRecoveryControls()
            }
        }
    }

    @Test fun pendingResumePreparationGuardIsWorkerSafeAndPauseCannotRearmItsLateRead() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val closed = CountDownLatch(1); val loaderReleased = CountDownLatch(1)
        val held = AtomicBoolean(); val source = AtomicReference<DataSource>()
        Journey().use { j ->
            try {
                j.startService(); j.play("Z"); j.pause(); j.preparePaused("A")
                SongCachePlaybackMedia.transferListener = object : TransferListener {
                    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
                    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
                    override fun onTransferEnd(actual: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                        if (actual === source.get()) closed.countDown()
                    }
                    override fun onBytesTransferred(actual: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                        if (dataSpec.key != "A" || !held.compareAndSet(false, true)) return
                        source.set(actual); entered.countDown()
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                        while (release.count != 0L) {
                            val left = deadline - System.nanoTime(); check(left > 0) { "Held Resume read was not released" }
                            try { release.await(left, TimeUnit.NANOSECONDS) } catch (_: InterruptedException) { }
                        }
                    }
                }
                j.resumePreparedThroughAcceptedControls()
                await("actual Resume loader is held before readiness") { entered.count == 0L }
                val stillCurrent = j.pendingReadForReadOnlyProbe()
                fun validOnWorker(): Boolean {
                    val task = FutureTask<Boolean> { stillCurrent() }; val worker = Thread(task, "resume-preparation-probe")
                    worker.start()
                    try { return task.get(2, TimeUnit.SECONDS) } finally { worker.join(2000); assertFalse(worker.isAlive) }
                }
                assertTrue("Silent preparation's denial guard is worker-safe without inventing audible authority", validOnWorker())
                val before = j.snapshot()
                SongCachePlaybackMedia.onLoaderRelease = { { loaderReleased.countDown() } }
                j.controller!!.pause()
                await("Pause revokes pending native start before held read returns") { !validOnWorker() && !j.observation().isPlaying }
                release.countDown()
                await("original held load/executor stop") { closed.count == 0L && loaderReleased.count == 0L }
                assertFalse(validOnWorker()); assertFalse(j.controller!!.isPlaying)
                assertEquals(before.queue, j.snapshot().queue)
                assertEquals(before.occurrence?.eventId, j.snapshot().occurrence?.eventId)
            } finally {
                release.countDown(); SongCachePlaybackMedia.transferListener = null; SongCachePlaybackMedia.onLoaderRelease = null
                j.releaseBorrowedRecoveryControls()
            }
        }
    }

    @Test fun malformedAudioIsAParsingFailureNotAConnectionDiagnosisAndNeverStarts() {
        Journey(longCurrent = true).use { j ->
            j.startService()
            j.play("A")
            val errors = CopyOnWriteArrayList<PlaybackException>()
            j.controller!!.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) { errors += error }
            })
            val bPcm = consumed("B")
            j.room.malformedAudio = true
            j.beginPlayNow("B")
            await("actual production extractor rejects malformed HTTP audio") { errors.isNotEmpty() }
            assertTrue(playbackFailureDiagnostic(errors.last()),
                playbackFailureDiagnostic(errors.last()).startsWith("category=parsing "))
            assertEquals(bPcm, consumed("B"))
            assertFalse(j.controller!!.isPlaying)
            assertFalse(j.snapshot().playing)
            val samples = SongCachePlaybackMedia.samples.get()
            pump(200)
            assertEquals("A rejected source must not replay itself", samples, SongCachePlaybackMedia.samples.get())
            j.room.malformedAudio = false
            // The same cached B object remains invalid; a backend response change is not
            // permission to invalidate media or replay it. Choose a new valid source explicitly.
            j.play("C")
            j.assertHealthy()
        }
    }

    @Test fun recentPlayNowHttpReadHeldAcrossPauseDoesNotBecomeConnectionError() =
        assertPlayNowReadAcrossPause(warm = false)

    @Test fun recentPlayNowCacheReadHeldAcrossPauseDoesNotBecomeConnectionError() =
        assertPlayNowReadAcrossPause(warm = true)

    private fun assertPlayNowReadAcrossPause(warm: Boolean) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sourceClosed = CountDownLatch(1)
        val releaseRequested = CountDownLatch(1)
        val loaderReleased = CountDownLatch(1)
        val loaderReleases = AtomicInteger()
        val held = AtomicBoolean()
        val heldSource = AtomicReference<DataSource>()
        val network = AtomicReference<Boolean>()
        Journey(longCurrent = true).use { j ->
            try {
                j.startService()
                if (warm) {
                    j.play("A", "B")
                    await("B is a whole cached object before the recent-row action") { j.assertBounded().complete == 1 }
                } else j.play("A")
                val errors = CopyOnWriteArrayList<PlaybackException>()
                j.controller!!.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) { errors += error }
                })
                val requests = j.room.audio.filter { it.id == "B" }
                SongCachePlaybackMedia.transferListener = object : TransferListener {
                    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
                    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
                    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                        if (source === heldSource.get()) sourceClosed.countDown()
                    }
                    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                        if (dataSpec.key != "B" || !held.compareAndSet(false, true)) return
                        heldSource.set(source)
                        network.set(isNetwork)
                        entered.countDown()
                        // Intentionally outlive Loader cancellation. Release the actual read only
                        // after Pause, so the production post-read authorization guard executes.
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                        while (release.count != 0L) {
                            val remaining = deadline - System.nanoTime()
                            check(remaining > 0) { "Held authorized read was not released" }
                            try { release.await(remaining, TimeUnit.NANOSECONDS) }
                            catch (_: InterruptedException) { /* Cancellation is the race under test. */ }
                        }
                    }
                }
                j.beginPlayNow("B")
                await("real ${if (warm) "file" else "HTTP"} B read is held") { entered.count == 0L }
                assertEquals(!warm, network.get())
                // B is now the sole active period; arm only after its real read is held,
                // excluding the preceding A period's already-requested release.
                SongCachePlaybackMedia.onLoaderRelease = {
                    loaderReleases.incrementAndGet()
                    releaseRequested.countDown()
                    val completed = { loaderReleased.countDown() }
                    completed
                }
                val beforePause = j.snapshot()
                assertEquals("B", beforePause.queue[beforePause.position].id)
                assertNotEquals(Player.STATE_IDLE, j.session.player.playbackState)
                j.controller!!.pause() // Do not wait behind the deliberately held Play start.
                await("service output has executed Pause/stop while the original read is held") {
                    // Read the service's player, not optimistic MediaController transport flags.
                    j.session.player.playbackState == Player.STATE_IDLE && !j.session.player.playWhenReady &&
                        !j.session.player.isPlaying && !j.snapshot().playing &&
                        releaseRequested.count == 0L
                }
                assertEquals(1L, release.count)
                assertEquals(1L, sourceClosed.count)
                assertEquals(1L, loaderReleased.count)
                assertEquals("Only the held B loader is being released", 1, loaderReleases.get())
                release.countDown()
                await("original held source closed and Loader executor released before Resume") {
                    // The release callback runs on the original executor AFTER its load exits.
                    // onLoadCanceled alone cannot prove that when released=true.
                    sourceClosed.count == 0L && loaderReleased.count == 0L
                }
                assertEquals("The completed release belongs to the held B loader", 1, loaderReleases.get())
                SongCachePlaybackMedia.transferListener = null
                SongCachePlaybackMedia.onLoaderRelease = null
                assertTrue("Intentional Pause must not emit a media/connection error", errors.isEmpty())
                assertNull(j.controller!!.playerError)
                val paused = j.snapshot()
                assertFalse(paused.playing)
                assertEquals(beforePause.queue, paused.queue)
                assertEquals(beforePause.position, paused.position)
                assertEquals(beforePause.occurrence?.eventId, paused.occurrence?.eventId)
                assertEquals("", paused.error)
                j.awaitLocalPublication("held read cancelled by Pause")
                if (warm) j.room.audioFailureStatus = 503 // Audio unavailable; coordination stays healthy.
                j.resume()
                if (warm) {
                    assertEquals("Whole warm B Resume needs no new GET/HEAD", requests, j.room.audio.filter { it.id == "B" })
                }
                assertTrue("No late cancelled-read error may leak after Resume", errors.isEmpty())
                j.assertHealthy()
            } finally {
                release.countDown()
                SongCachePlaybackMedia.transferListener = null
                SongCachePlaybackMedia.onLoaderRelease = null
            }
        }
    }

    @Test fun genuineAudioHttpFailureWithHealthyCoordinationRemainsAnError() {
        Journey(longCurrent = true).use { j ->
            j.startService()
            j.play("A")
            val logStart = org.robolectric.shadows.ShadowLog.getLogsForTag("SpofifyPlayback").size
            j.room.audioFailureStatus = 404
            j.beginPlayNow("B")
            await("actual HTTP404 is retained as a sanitized load diagnostic and failed start") {
                j.snapshot().error == "media" &&
                    org.robolectric.shadows.ShadowLog.getLogsForTag("SpofifyPlayback").drop(logStart)
                        .any { it.msg.contains("http=404") }
            }
            assertTrue(j.room.audio.any { it.id == "B" && it.method == "GET" })
            assertFalse(j.controller!!.isPlaying)
            assertFalse(j.snapshot().playing)
            assertEquals(0L, consumed("B"))
            assertTrue(j.graph.playbackCoordinator!!.state.value.connected)
            await("failed Play now has its definitive terminal receipt before another command") { j.playNowResult != null }
            j.awaitLocalPublication("definitively failed HTTP Play now")
            j.room.audioFailureStatus = null
            j.play("C") // One fresh explicit command, never automatic retry/skip of missing B.
            j.assertHealthy()
        }
    }

    @Test fun prefetchedBIsActuallyConsumedAsBWithoutAnotherBodyOrFreshnessHead() {
        Journey(limits = SongCache.Limits(maxTracks = 8, maxBytes = 4 * 1024 * 1024L), longCurrent = true).use { j ->
            j.startService()
            j.play("A", "B", "C", "D", "E", "F", "G")
            j.awaitFetched("A", "B", "C", "D", "E", "F")
            // A is legitimately partial/pinned while its normal forward buffer is full.
            // All five future whole objects must therefore come from speculative work.
            await("five complete future objects plus the legitimate partial current entry") {
                j.assertBounded().let { it.complete == 5 && it.entries == 6 }
            }
            assertEquals(setOf("A", "B", "C", "D", "E", "F"), j.room.fetched())
            val future = listOf("B", "C", "D", "E", "F")
            future.forEach { assertEquals("Speculation never renders $it", 0L, consumed(it)) }
            val before = j.room.audio.toList()
            future.forEach { id ->
                val scheduled = j.room.scheduledBodies(id)
                assertTrue("Cold $id requested an authenticated nonempty body", scheduled > 0)
                val pcm = consumed(id)
                // A one-item replacement builds a fresh production MediaSource; seeking Next
                // could otherwise pass by playing an already-buffered old period in memory.
                j.play(id)
                await("fresh source really plays retained exact $id PCM and advances") {
                    j.controller!!.currentMediaItem?.mediaId == id && j.controller!!.isPlaying &&
                        j.controller!!.currentPosition > 50 && consumed(id) > pcm
                }
                assertEquals("Warm $id must not schedule another body", scheduled, j.room.scheduledBodies(id))
                assertEquals("No fresh GET/HEAD on whole cached $id", before.filter { it.id == id }, j.room.audio.filter { it.id == id })
            }
            assertEquals(setOf("A", "B", "C", "D", "E", "F"), j.room.fetched())
            assertNotEquals(SongCacheProtocolRoom.marker("A"), SongCacheProtocolRoom.marker("B"))
            j.assertHealthy()
        }
    }

    @Test fun fiveOccurrenceStepsDeduplicateWithoutScanningFartherAndProgressDoesNotRestartWork() {
        Journey(limits = SongCache.Limits(maxTracks = 8, maxBytes = 4 * 1024 * 1024L), longCurrent = true).use { j ->
            j.startService()
            j.play("A", "B", "B", "C", "D", "E", "F")
            j.awaitFetched("A", "B", "C", "D", "E")
            await("four complete future IDs plus partial current within six occurrence steps") {
                j.assertBounded().let { it.complete == 4 && it.entries == 5 }
            }
            assertEquals(setOf("A", "B", "C", "D", "E"), j.room.fetched())
            assertEquals(1, j.room.audio.count { it.id == "B" && it.method == "GET" })
            val queue = j.snapshot().queue
            assertEquals(2, queue.count { it.id == "B" })
            assertEquals(2, queue.filter { it.id == "B" }.map { it.key }.distinct().size)
            val requests = j.room.audio.toList()
            val starts = SongCachePlaybackMedia.starts.get()
            val event = j.snapshot().occurrence?.eventId
            val position = j.controller!!.currentPosition
            val polls = j.room.reports.get()
            pump(2200) // More than two ordinary coordinator heartbeat intervals.
            assertTrue(j.controller!!.currentPosition > position + 100)
            assertTrue(j.room.reports.get() > polls)
            assertEquals("Progress/heartbeats cannot mint downloads", requests, j.room.audio.toList())
            assertEquals(starts, SongCachePlaybackMedia.starts.get())
            assertEquals(event, j.snapshot().occurrence?.eventId)
            assertTrue("Only the actual A occurrence may earn history", j.room.history.get() <= 1)
            // Only after the unchanged-queue/history/heartbeat oracles, replace each period
            // with a fresh production source to prove all four future objects are whole hits.
            listOf("B", "C", "D", "E").forEach { id ->
                val pcm = consumed(id)
                j.play(id)
                await("fresh source consumes retained distinct $id after duplicate-window warming") {
                    j.controller!!.currentMediaItem?.mediaId == id && j.controller!!.isPlaying &&
                        j.controller!!.currentPosition > 50 && consumed(id) > pcm
                }
                assertEquals("Whole cached $id needs no further GET/HEAD", requests.filter { it.id == id }, j.room.audio.filter { it.id == id })
            }
            assertEquals(setOf("A", "B", "C", "D", "E"), j.room.fetched())
            assertEquals(1, j.room.audio.count { it.id == "B" && it.method == "GET" })
            j.assertHealthy()
        }
    }

    @Test fun ordinaryPlayerLoadingAndWorkerRequestsStayWithinThenCurrentPlusFiveEvenForShortTracks() {
        Journey(limits = SongCache.Limits(maxTracks = 20, maxBytes = 512 * 1024L),
            normalTrackSeconds = 1).use { j ->
            j.startService()
            val queue = listOf("A", "B", "C", "D", "E", "F", "G", "H", "I")
            data class RequestedAtCurrent(val method: String, val id: String, val current: String?)
            val current = AtomicReference<String?>(null)
            val transitions = CopyOnWriteArrayList<String>()
            val trace = CopyOnWriteArrayList<RequestedAtCurrent>()
            val listener = object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                    current.set(mediaItem?.mediaId)
                    synchronized(transitions) {
                        if (transitions.size == 32) transitions.removeAt(0)
                        transitions += "${mediaItem?.mediaId}:$reason"
                    }
                }
            }
            // Main-thread callbacks publish immutable identity through the atomic reference.
            // The authenticated server dispatcher never reads a Player from its own thread.
            j.session.player.addListener(listener)
            j.room.onAudioRequest = { request ->
                synchronized(trace) {
                    if (trace.size == 64) trace.removeAt(0)
                    trace += RequestedAtCurrent(request.method, request.id, current.get())
                }
            }
            val previousDiagnostics = timeoutDiagnostics
            timeoutDiagnostics = { "${previousDiagnostics?.invoke()}; trace=$trace; transitions=$transitions" }
            try {
                j.play(*queue.toTypedArray())
                // Do not stop at first READY: allow ordinary forward loading and natural
                // current transitions to exercise the entire nine-track queue. Under a
                // correct bound, I becomes eligible only once actual current reaches D.
                j.awaitFetched(*queue.toTypedArray())
                val gets = trace.filter { it.method == "GET" }
                assertEquals("The bounded trace must retain every authenticated GET", j.room.audio.count { it.method == "GET" }, gets.size)
                val violations = gets.filter { request ->
                    val at = request.current?.let(queue::indexOf) ?: -1
                    at < 0 || request.id !in queue.subList(at, minOf(queue.size, at + 6))
                }
                assertTrue("Audio GET escaped then-current + five: $violations; trace=$trace; transitions=$transitions",
                    violations.isEmpty())
                assertTrue("A must have produced real distinct PCM, not just prepared metadata", consumed("A") > 0)
                assertTrue("Natural queue progression must retain healthy actual output", j.controller!!.isPlaying)
                assertTrue("Observe initial current through the real public transition callback", transitions.any { it.startsWith("A:") })
                j.assertHealthy()
            } finally {
                timeoutDiagnostics = previousDiagnostics
                j.room.onAudioRequest = null
                j.session.player.removeListener(listener)
            }
        }
    }

    @Test fun selectedRemoteControllerNeverOpensAudioOrAcquiresOutput() {
        Journey(remote = true).use { j ->
            assertTrue(j.room.audio.isEmpty()) // Login/probe/browse is not a cache trigger.
            j.startService()
            val focus = j.focusRequest()
            val starts = SongCachePlaybackMedia.starts.get()
            val reports = j.room.reports.get()
            pump(2200)
            assertEquals("Remote controller must not publish executor facts", reports, j.room.reports.get())
            assertEquals("remote", j.graph.playbackCoordinator!!.state.value.selectedDeviceId)
            assertTrue(j.room.audio.isEmpty())
            assertEquals(0L, SongCachePlaybackMedia.samples.get())
            assertEquals(starts, SongCachePlaybackMedia.starts.get())
            assertEquals(focus, j.focusRequest())
            assertEquals(0, j.room.history.get())
            assertEquals(1, j.room.admissions.get())
            assertEquals(0, j.foregroundNotificationId())
            assertTrue(j.room.mutations.isEmpty())
            assertEquals("R", j.snapshot().queue.single().id)
            assertFalse(j.observation().isPlaying)
            j.assertHealthy()
        }
    }

    @Test fun productionPauseCompletesBeforeHeldSpeculativeWriteIsReleased() =
        assertPauseDuringSpeculativeDisk(holdClose = false)

    @Test fun productionPauseCompletesBeforeHeldSpeculativeCloseCommitIsReleased() =
        assertPauseDuringSpeculativeDisk(holdClose = true)

    private fun assertPauseDuringSpeculativeDisk(holdClose: Boolean) {
        val arm = AtomicBoolean()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val timedOut = AtomicBoolean()
        fun hold() {
            entered.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var interrupted = false
            try {
                while (release.count != 0L) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) {
                        timedOut.set(true)
                        throw IOException("Synthetic storage hold timed out")
                    }
                    try { release.await(remaining, TimeUnit.NANOSECONDS) }
                    catch (_: InterruptedException) { interrupted = true }
                }
            } finally { if (interrupted) Thread.currentThread().interrupt() }
        }
        Journey(longCurrent = true, openCacheWrite = { file ->
            val held = arm.compareAndSet(true, false)
            val output = FileOutputStream(file)
            object : OutputStream() {
                override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    if (held && !holdClose) hold()
                    output.write(bytes, offset, length)
                }
                override fun close() {
                    if (held && holdClose) hold()
                    output.close()
                }
            }
        }).use { j ->
            try {
                j.room.onAudioRequest = { if (it.id == "B") arm.set(true) }
                j.startService()
                j.play("A", "B", "C")
                await("speculative B reaches the held disk seam") { entered.count == 0L }
                assertTrue("Production A consumes its exact PCM marker", consumed("A") > 0)
                val before = j.snapshot()
                // Real MediaController -> service cancellation -> player Pause. A posted main
                // marker must also run before disk release, not merely enqueue the command.
                val mainMarker = AtomicBoolean()
                val start = System.nanoTime()
                j.controller!!.pause()
                android.os.Handler(Looper.getMainLooper()).post { mainMarker.set(true) }
                await("Pause and following main-thread control complete with disk still held") {
                    mainMarker.get() && !j.controller!!.isPlaying && !j.controller!!.playWhenReady &&
                        !j.snapshot().playing
                }
                assertTrue("Pause/control must not wait for storage", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2))
                assertFalse("The disk hold must not time out to unblock controls", timedOut.get())
                assertEquals(1L, release.count)
                assertEquals(before.queue, j.snapshot().queue)
                assertEquals(before.position, j.snapshot().position)
                assertEquals(0L, consumed("B"))
                assertFalse(j.room.fetched().contains("C"))
                // Resume is also a real successful-play promotion callback on the service
                // thread. Its demand source and exact A PCM must progress before releasing B.
                val resumedAt = System.nanoTime()
                j.resume()
                assertTrue("Successful Resume/promotion must not wait for storage",
                    System.nanoTime() - resumedAt < TimeUnit.SECONDS.toNanos(2))
                assertFalse(timedOut.get())
                assertEquals(1L, release.count)
                j.controller!!.pause()
                await("second Pause still completes before disk release") { !j.controller!!.playWhenReady }
                assertFalse(timedOut.get())
            } finally {
                release.countDown()
                j.room.onAudioRequest = null
            }
            await("cancelled speculative writer eventually releases its pin") {
                j.graph.songCache.inventory().pinned <= 1 // Long current A may still own a reader/writer.
            }
            j.assertHealthy()
        }
    }

    @Test fun pauseCancelsHeldSpeculationAndPassiveHeartbeatsCannotFetchTheRemainingWindow() {
        Journey().use { j ->
            j.room.delayedTrack = "B"
            j.startService()
            j.play("A", "B", "C", "D")
            j.awaitFetched("B")
            assertTrue("A makes real progress while future B is held", consumed("A") > 0)
            j.controller!!.pause()
            await("actual stopped paused receiver") { !j.controller!!.isPlaying && !j.controller!!.playWhenReady }
            val stopped = j.snapshot()
            val starts = SongCachePlaybackMedia.starts.get()
            pump(2200) // Also lets the cancelled finite slow response finish on the server.
            assertEquals(setOf("A", "B"), j.room.fetched())
            assertEquals(1, j.room.audio.count { it.id == "B" && it.method == "GET" })
            assertEquals(starts, SongCachePlaybackMedia.starts.get())
            assertEquals(0L, consumed("B"))
            val after = j.snapshot()
            assertEquals(stopped.queue, after.queue)
            assertEquals(stopped.position, after.position)
            assertEquals(stopped.occurrence, after.occurrence)
            assertFalse(after.playing)
            j.assertHealthy()
        }
    }

    @Test fun explicitPausedHighLowAndNextReplanWithoutStartingPlayerTakingFocusOrEarningHistory() {
        Journey().use { j ->
            j.startService()
            j.play("A", "B")
            j.awaitFetched("B")
            j.pause()
            val before = j.snapshot()
            val renderer = listOf(SongCachePlaybackMedia.starts.get(), SongCachePlaybackMedia.enables.get(), SongCachePlaybackMedia.resets.get())
            val focus = j.focusRequest()
            val history = j.room.history.get()
            j.action(PlaybackListAction.QUEUE_HIGH, "H")
            j.awaitFetched("H")
            j.action(PlaybackListAction.QUEUE_LOW, "L")
            j.awaitFetched("L")
            j.action(PlaybackListAction.PLAY_NEXT, "N")
            j.awaitFetched("N")
            assertEquals(listOf("N", "H", "B", "L"), j.upcoming().map { it.item.id })
            val after = j.snapshot()
            assertEquals(before.queue[before.position].key, after.queue[after.position].key)
            assertEquals(before.occurrence, after.occurrence)
            assertFalse(after.playing)
            assertEquals(renderer, listOf(SongCachePlaybackMedia.starts.get(), SongCachePlaybackMedia.enables.get(), SongCachePlaybackMedia.resets.get()))
            assertEquals(focus, j.focusRequest())
            j.assertForegroundStopped()
            assertEquals(history, j.room.history.get())
            assertEquals(0L, consumed("H") + consumed("L") + consumed("N"))
            j.assertHealthy()
        }
    }

    @Test fun actualHighLowNextWindowUsesRepeatOneAndDoesNotScanBeyondFiveSteps() {
        Journey().use { j ->
            j.startService()
            j.play("A")
            j.pause()
            j.repeatOne()
            j.action(PlaybackListAction.QUEUE_LOW, "B", "C", "D", "E", "F", "G")
            j.action(PlaybackListAction.QUEUE_HIGH, "H")
            j.action(PlaybackListAction.PLAY_NEXT, "N")
            pump(150)
            assertEquals("Repeat-one predicts only current A, not physical future rows", setOf("A"), j.room.fetched())
            assertEquals(listOf("N", "H", "B", "C", "D", "E", "F", "G"),
                j.upcoming().map { it.item.id })
            val starts = SongCachePlaybackMedia.starts.get()
            val occurrence = j.snapshot().occurrence
            j.repeat(Player.REPEAT_MODE_OFF, "off")
            pump(150)
            j.awaitFetched("N", "H", "B", "C", "D")
            assertEquals(setOf("A", "N", "H", "B", "C", "D"), j.room.fetched())
            assertEquals(starts, SongCachePlaybackMedia.starts.get())
            assertEquals(occurrence, j.snapshot().occurrence)
            assertFalse(j.controller!!.isPlaying)
            j.assertForegroundStopped()
            j.assertHealthy()
        }
    }


    @Test fun shuffleExactRemovalAndAnchoredMoveUseServerAcceptedTraversal() {
        Journey().use { j ->
            j.startService(); j.play("A"); j.pause(); j.repeatOne()
            j.action(PlaybackListAction.QUEUE_LOW, "B", "C", "D", "E", "F", "G", "H", "I")
            j.controller!!.shuffleModeEnabled = true
            await("server accepted shuffled future") { j.snapshot().shuffle }
            j.awaitLocalPublication("shuffle")
            val shuffled = j.upcoming().toList()
            assertEquals(8, shuffled.size)
            val removed = shuffled[1]
            j.remove(removed.occurrenceId)
            await("exact server-key removal") { j.upcoming().none { it.occurrenceId == removed.occurrenceId } }
            j.awaitLocalPublication("remove")
            val remaining = shuffled.filterNot { it.occurrenceId == removed.occurrenceId }
            assertEquals(remaining.map { it.occurrenceId }, j.upcoming().map { it.occurrenceId })
            val moved = remaining.last()
            j.move(moved.occurrenceId, 0)
            await("anchored move exits shuffle") {
                !j.snapshot().shuffle && j.upcoming().firstOrNull()?.occurrenceId == moved.occurrenceId
            }
            j.awaitLocalPublication("anchored move")
            val traversal = j.upcoming()
            assertEquals(listOf(moved.occurrenceId) + remaining.dropLast(1).map { it.occurrenceId },
                traversal.map { it.occurrenceId })
            val occurrence = j.snapshot().occurrence
            j.repeat(Player.REPEAT_MODE_OFF, "off")
            val expected = setOf("A") + traversal.take(5).map { it.item.id }
            await("actual server-reordered five-occurrence window") { j.room.fetched().containsAll(expected) }
            assertEquals(expected, j.room.fetched())
            assertEquals(occurrence, j.snapshot().occurrence)
            assertFalse(j.controller!!.isPlaying); j.assertForegroundStopped()
            j.assertHealthy()
        }
    }

    @Test fun repeatAllWrapsFiveOccurrenceStepsFromTheActualLastCurrent() {
        Journey().use { j ->
            j.startService()
            j.playAt(7, "A", "B", "C", "D", "E", "F", "G", "H")
            pump(150)
            assertEquals("Repeat-off ends at the last accepted current", setOf("H"), j.room.fetched())
            j.pause()
            j.repeat(Player.REPEAT_MODE_ALL, "all")
            j.awaitFetched("A", "B", "C", "D", "E")
            pump(150)
            assertEquals(setOf("H", "A", "B", "C", "D", "E"), j.room.fetched())
            assertEquals("H", j.controller!!.currentMediaItem?.mediaId)
            assertEquals(0L, consumed("A") + consumed("B") + consumed("C") + consumed("D") + consumed("E"))
            j.assertHealthy()
        }
    }

    @Test fun fullThreeTrackCacheAdmitsANewFutureTrackWhileCurrentPcmContinuesAndTheNewTrackReallyHits() {
        assertEquals(100, SongCache.MAX_TRACKS)
        assertEquals(1_000_000_000L, SongCache.MAX_BYTES)
        val limits = SongCache.Limits(maxTracks = 3, maxBytes = 100_000)
        Journey(limits = limits, sampleRate = 1000).use { j ->
            j.startService()
            j.play("A", "B", "C")
            j.awaitFetched("A", "B", "C")
            await("three real complete retained audio bodies") { j.assertBounded().complete == 3 }
            val before = j.assertBounded()
            assertEquals(3, before.entries)
            assertTrue(before.ownedBytes > 0)
            val position = j.controller!!.currentPosition
            val pcm = consumed("A")
            val event = j.snapshot().occurrence?.eventId
            val starts = SongCachePlaybackMedia.starts.get()
            j.action(PlaybackListAction.QUEUE_LOW, "D")
            j.awaitFetched("D")
            await("full cache evicts/adopts D without stopping actual A output") {
                j.assertBounded().complete == 3 && j.controller!!.isPlaying &&
                    j.controller!!.currentPosition > position + 100 && consumed("A") > pcm
            }
            assertEquals(starts, SongCachePlaybackMedia.starts.get())
            assertEquals(event, j.snapshot().occurrence?.eventId)
            assertTrue(j.controller!!.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
            assertNotNull(j.interactionKey())
            assertNull(j.controller!!.playerError)
            val retainedD = j.room.audio.filter { it.id == "D" }
            assertTrue(retainedD.any { it.method == "GET" && it.scheduledBodyBytes > 0 })
            // This is the admission/eviction oracle, not merely an inventory assertion:
            // at capacity, D becomes actual exact output without any further body/HEAD.
            val dBefore = consumed("D")
            j.play("D")
            await("newly admitted D really outputs its own retained PCM") { consumed("D") > dBefore }
            assertEquals(retainedD, j.room.audio.filter { it.id == "D" })
            assertEquals(3, j.assertBounded().entries)
            j.assertHealthy()
        }
    }

    @Test fun currentLargerThanTheEntireCacheStreamsExactPcmUncachedAndNextRemainsEnabled() {
        val limits = SongCache.Limits(maxTracks = 3, maxBytes = 48_000)
        Journey(limits = limits).use { j ->
            j.startService()
            j.play("A", "B") // Each authenticated WAV is 192,044 bytes, above the entire budget.
            val position = j.controller!!.currentPosition
            val pcm = consumed("A")
            await("overlarge uncached current still makes actual PCM progress") {
                j.assertBounded()
                j.controller!!.currentPosition > position + 100 && consumed("A") > pcm
            }
            assertEquals(0, j.assertBounded().complete)
            assertTrue(j.room.scheduledBodies("A") > limits.maxBytes)
            assertTrue(j.controller!!.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
            assertTrue(j.controller!!.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
            assertNotNull(j.interactionKey())
            val bBefore = consumed("B")
            j.controller!!.seekToNextMediaItem()
            await("Next after oversized bypass really starts distinct uncached B") {
                j.assertBounded()
                j.controller!!.currentMediaItem?.mediaId == "B" && j.controller!!.isPlaying &&
                    j.controller!!.currentPosition > 50 && consumed("B") > bBefore
            }
            assertEquals(0, j.assertBounded().complete)
            assertTrue(j.room.scheduledBodies("B") > limits.maxBytes)
            assertTrue(j.controller!!.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
            j.assertHealthy()
        }
    }

    @Test fun successfulNewCurrentPromotesItsRetainedTrackBeforeTheNextFifoEviction() {
        Journey(limits = SongCache.Limits(maxTracks = 3, maxBytes = 700_000), longCurrent = true).use { j ->
            j.startService()
            // A cannot fit and streams uncached. Only sequential speculative B/C/D are admitted,
            // establishing FIFO B,C,D without a current-reader pin masking the victim choice.
            j.play("A", "B", "C", "D")
            j.awaitFetched("B", "C", "D")
            j.awaitUnpinnedComplete(3)
            assertEquals(setOf("A", "B", "C", "D"), j.room.fetched())
            assertEquals(0L, consumed("B") + consumed("C") + consumed("D"))
            val bRequests = j.room.audio.filter { it.id == "B" }
            j.play("B")
            assertEquals("Actual new current B uses retained audio", bRequests, j.room.audio.filter { it.id == "B" })
            assertTrue(consumed("B") > 0)
            j.pause()
            j.awaitUnpinnedComplete(3)
            // Successful B must have moved behind C,D. With every pin released, E must
            // evict C, not protect an old B merely because it is still an active reader.
            j.admitEToFullCache()
            j.assertFreshCacheHit("B")
            j.pause()
            j.assertFreshCacheMiss("C")
            j.assertHealthy()
        }
    }

    @Test fun actualResumePromotesWithoutANewHistoryEventBeforeTheNextFifoEviction() {
        Journey(limits = SongCache.Limits(maxTracks = 3, maxBytes = 700_000), longCurrent = true).use { j ->
            j.startService()
            j.play("A", "B")
            j.awaitFetched("B")
            j.awaitUnpinnedComplete(1)
            j.play("B")
            // These admissions happen AFTER the initial B start: FIFO is now B,C,D, so
            // only the upcoming successful Resume (not the first start) can save B from E.
            j.action(PlaybackListAction.QUEUE_HIGH, "C")
            j.awaitFetched("C")
            await("whole B,C after the original successful B start") { j.assertBounded().complete == 2 }
            j.action(PlaybackListAction.QUEUE_LOW, "D")
            j.awaitFetched("D")
            await("whole B,C,D before Pause/Resume") { j.assertBounded().complete == 3 }
            j.pause()
            j.awaitUnpinnedComplete(3)
            val event = requireNotNull(j.snapshot().occurrence).eventId
            val bRequests = j.room.audio.filter { it.id == "B" }
            val pcm = consumed("B")
            j.resume()
            assertTrue(consumed("B") > pcm)
            assertEquals("Resume does not require another body or freshness HEAD", bRequests, j.room.audio.filter { it.id == "B" })
            assertEquals("Promotion is independent of a newly minted/qualified history event", event,
                j.snapshot().occurrence?.eventId)
            j.pause()
            j.awaitUnpinnedComplete(3)
            assertEquals(event, j.snapshot().occurrence?.eventId)
            j.admitEToFullCache()
            j.assertFreshCacheHit("B")
            j.pause()
            j.assertFreshCacheMiss("C")
            j.assertHealthy()
        }
    }

    @Test fun focusDeniedPreparedStartNeverPromotesOrProtectsTheOldestRetainedTrack() {
        Journey(limits = SongCache.Limits(maxTracks = 3, maxBytes = 700_000), longCurrent = true).use { j ->
            j.startService()
            j.play("A", "B", "C", "D")
            j.awaitFetched("B", "C", "D")
            j.awaitUnpinnedComplete(3) // FIFO B,C,D; none has ever reached local output.
            j.pause()
            val starts = SongCachePlaybackMedia.starts.get()
            val pcm = consumed("B")
            val bRequests = j.room.audio.filter { it.id == "B" }
            j.preparePaused("B")
            j.setFocusResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
            j.controller!!.play()
            await("real explicit B start is blocked by denied audio focus") {
                j.snapshot().error == "blocked" && !j.controller!!.isPlaying
            }
            j.awaitLocalPublication("focus-denied B start")
            assertEquals(starts, SongCachePlaybackMedia.starts.get())
            assertEquals(pcm, consumed("B"))
            assertEquals(bRequests, j.room.audio.filter { it.id == "B" })
            assertFalse("Failed setup cannot invent actual playing", j.observation().isPlaying)
            assertEquals("Server-selected event is not a client history write", 0, j.room.history.get())
            j.assertForegroundStopped()
            j.awaitUnpinnedComplete(3)
            // A failed B start cannot promote B or leave a successful-play protection pin.
            // Therefore E evicts B, while C remains a whole retained object.
            j.admitEToFullCache()
            j.setFocusResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            j.assertFreshCacheHit("C")
            j.pause()
            j.assertFreshCacheMiss("B")
            j.assertHealthy()
        }
    }

    @Test fun actualSeekRebufferingAndReturningPlayingCallbacksDoNotPromoteTheCurrentAgain() {
        Journey(limits = SongCache.Limits(maxTracks = 3, maxBytes = 4 * 1024 * 1024L),
            longCurrent = true, longFutureB = true).use { j ->
            j.startService()
            j.play("A", "B")
            j.awaitFetched("B")
            await("long B is whole while long A remains a legitimate partial current") {
                j.assertBounded().let { it.entries == 2 && it.complete == 1 }
            }
            val bRequests = j.room.audio.filter { it.id == "B" }
            j.play("B")
            await("replacing A discards only its abandoned partial and plays whole cached B") {
                j.assertBounded().let { it.entries == 1 && it.complete == 1 }
            }
            assertEquals(bRequests, j.room.audio.filter { it.id == "B" })
            // C and D are admitted after B's last successful positive start. Thus FIFO is
            // B,C,D: only an erroneous promotion from a seek/rebuffer callback could save B.
            j.action(PlaybackListAction.QUEUE_HIGH, "C")
            j.awaitFetched("C")
            await("C retained after the successful B start") { j.assertBounded().let { it.entries == 2 && it.complete == 2 } }
            j.action(PlaybackListAction.QUEUE_LOW, "D")
            j.awaitFetched("D")
            await("B,C,D are whole before real seek/rebuffer cycles") { j.assertBounded().let { it.entries == 3 && it.complete == 3 } }
            val event = requireNotNull(j.snapshot().occurrence).eventId
            val focus = j.focusRequest()
            val buffering = AtomicInteger()
            val returningPlaying = AtomicInteger()
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_BUFFERING) buffering.incrementAndGet()
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) returningPlaying.incrementAndGet()
                }
            }
            // Observe genuine callbacks from the public production session player. Never
            // invoke listeners, fake readiness, or issue Resume/start commands between seeks.
            j.session.player.addListener(listener)
            try {
                fun seekOnce(target: Long) {
                    j.awaitLocalPublication("before real seek to $target")
                    val beforeBuffering = buffering.get()
                    val beforePlaying = returningPlaying.get()
                    val pcm = consumed("B")
                    j.controller!!.seekTo(target) // One submission only; no retry on refusal.
                    await("real seek to $target buffers, returns READY/isPlaying and consumes B PCM") {
                        buffering.get() > beforeBuffering && returningPlaying.get() > beforePlaying &&
                            j.session.player.playbackState == Player.STATE_READY && j.session.player.isPlaying &&
                            j.controller!!.isPlaying && j.controller!!.currentPosition > target + 50 && consumed("B") > pcm
                    }
                    j.awaitLocalPublication("real seek to $target")
                    assertEquals(event, j.snapshot().occurrence?.eventId)
                    assertEquals("Seeking cannot perform another explicit focus/start request", focus, j.focusRequest())
                    assertEquals("Both seeks read the retained B object, not another body/HEAD", bRequests,
                        j.room.audio.filter { it.id == "B" })
                }
                seekOnce(90_000) // Outside the first forward buffer of the 120-second period.
                seekOnce(5_000) // Back outside the now-loaded suffix.
                assertTrue("Both genuine seek operations must enter BUFFERING", buffering.get() >= 2)
                assertTrue("Both genuine seek operations must return to actual playing", returningPlaying.get() >= 2)
            } finally { j.session.player.removeListener(listener) }
            j.pause()
            j.awaitUnpinnedComplete(3)
            assertEquals(event, j.snapshot().occurrence?.eventId)
            j.admitEToFullCache()
            j.assertFreshCacheHit("C") // Probe kept C before a B miss can change the FIFO again.
            j.pause()
            j.assertFreshCacheMiss("B")
            j.assertHealthy()
        }
    }

    @Test fun sameAccountGraphAndServiceRecreationKeepsBytesDormantUntilSuccessfulSessionValidation() {
        Journey().use { j ->
            j.startService()
            j.play("A", "B")
            j.awaitFetched("B")
            await("A and prefetched B are complete before disposing the graph") { j.assertBounded().complete == 2 }
            val scheduledBytes = j.room.scheduledBodies("B")
            val requests = j.room.audio.toList()
            val restored = j.recreateGraph()
            assertNull(restored.currentSession())
            j.assertDormantServiceCannotOpenCache()
            assertTrue(j.snapshot().queue.isEmpty())
            pump(150)
            assertEquals(requests, j.room.audio.toList())
            j.room.restoreStatus = 503
            val failed = runCatching { runBlocking { j.graph.restore() } }
            assertTrue("Offline/server-unavailable restore must not authorize cached content", failed.isFailure)
            assertNull(restored.currentSession())
            pump(150)
            assertEquals(requests, j.room.audio.toList())
            assertTrue(j.snapshot().queue.isEmpty())
            j.room.restoreStatus = 200
            assertNotNull(runBlocking { j.graph.restore() })
            assertEquals("Validation alone cannot restore a queue/start/prefetch", requests, j.room.audio.toList())
            j.startService()
            val consumedBefore = consumed("B")
            j.play("B") // New explicit authority/queue, not offline startup autoplay.
            await("reopened retained exact B output") { consumed("B") > consumedBefore }
            assertEquals(scheduledBytes, j.room.scheduledBodies("B"))
            assertEquals(requests.filter { it.id == "B" }, j.room.audio.filter { it.id == "B" })
            j.assertHealthy()
        }
    }

    private fun track(id: String, entry: String) = LibraryItem("track", id, "Synthetic $id", hasAudio = true,
        entryId = entry, durationMs = 12_000)
    private fun consumed(id: String) = SongCachePlaybackMedia.bytes(SongCacheProtocolRoom.marker(id))
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.MILLISECONDS)
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Timed out: $label; audio samples=${SongCachePlaybackMedia.samples.get()}; " +
            runCatching { timeoutDiagnostics?.invoke().orEmpty() }.getOrDefault("diagnostics unavailable"))
    }
    private fun pump(ms: Long) {
        repeat((ms / 5).toInt()) {
            shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.MILLISECONDS)
            Thread.sleep(5)
        }
    }
}
