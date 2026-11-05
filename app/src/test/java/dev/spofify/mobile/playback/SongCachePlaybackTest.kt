package dev.spofify.mobile.playback

import android.app.ActivityManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Looper
import androidx.media3.common.Player
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
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
    shadows = [SongCachePlaybackMedia::class], instrumentedPackages = ["androidx.media3.exoplayer"])
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(UnstableApi::class)
class SongCachePlaybackTest {
    private class Sessions(initial: StoredSession? = null) : SessionStore {
        @Volatile private var saved = initial
        override fun read() = saved
        override fun write(value: StoredSession?) { saved = value }
    }
    private class Logins : LoginStore {
        override fun read(): RememberedLogin? = null
        override fun write(value: RememberedLogin?) = Unit
    }

    /** Small, authenticated control room. It reports only states actually published by the
     * production receiver. No directives/activation/start ACK or cache result are invented.
     */
    private class Room(private val remote: Boolean = false, private val sampleRate: Int = 8000) : Dispatcher() {
        /** Bytes scheduled at dispatch, not bytes written to a socket or read by a client.
         * A delayed/cancelled response can schedule its full body but transmit less or nothing.
         */
        data class Audio(val id: String, val method: String, val range: String?, val scheduledBodyBytes: Int)
        val audio = CopyOnWriteArrayList<Audio>()
        val unexpected = CopyOnWriteArrayList<String>()
        val registrations = AtomicInteger()
        val polls = AtomicInteger()
        val history = AtomicInteger()
        val mutations = CopyOnWriteArrayList<String>()
        @Volatile var restoreStatus = 200
        @Volatile var delayedTrack: String? = null
        @Volatile var snapshot = if (remote) PlaybackSnapshot(queue = listOf(
            PlaybackTrack("R", "Remote song", emptyList(), "", "", 12.0, key = 1, order = 0)),
            position = 0, playing = true) else PlaybackSnapshot()
        private var metadata: QueueIntentState? = null
        private var sequence = 0L
        private var phone = ""
        private fun view(): PlaybackDevicesView {
            val selected = if (remote) "remote" else phone
            return PlaybackDevicesView(phone,
                (if (phone.isEmpty()) emptyList() else listOf(PlaybackDevice(phone, "Phone", true))) +
                    if (remote) listOf(PlaybackDevice("remote", "Remote", true)) else emptyList(),
                selected, 1, if (selected.isEmpty()) 0 else 8000, state = snapshot, stateSeq = sequence,
                stateAgeMs = 0, transfer = null, directives = emptyList(), commands = emptyList(),
                queueIntents = QueueIntentsView(1,
                    if (phone.isEmpty()) emptyList() else listOf(QueueIntentReceiver(phone, 1)),
                    if (remote || phone.isEmpty()) null else metadata?.let {
                        QueueIntentBinding(phone, it.receiverId, it.revision,
                            snapshot.queue.getOrNull(snapshot.position)?.key, snapshot.occurrence?.eventId)
                    }, if (remote) null else metadata, emptyList(), emptyList()))
        }
        private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
        override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.path == "/api/auth/login") return json(SESSION)
                .setHeader("Set-Cookie", "spofify_session=cache-session; Path=/; HttpOnly; Max-Age=3600")
            if (request.getHeader("Cookie") != "spofify_session=cache-session") {
                unexpected += "Unauthenticated request"
                return MockResponse().setResponseCode(401)
            }
            if (request.path == "/api/auth/session") return json(SESSION).setResponseCode(restoreStatus)
            val path = request.requestUrl!!.pathSegments
            if (path.size == 5 && path.take(3) == listOf("api", "library", "tracks") && path[4] == "audio") {
                val id = path[3]
                check(id.length == 1 && id[0] in 'A'..'Z')
                val wav = wav(id, sampleRate)
                val range = request.getHeader("Range")
                val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                if (start !in wav.indices) return MockResponse().setResponseCode(416)
                val count = if (request.method == "HEAD") 0 else wav.size - start
                audio += Audio(id, request.method!!, range, count)
                val response = MockResponse().setHeader("Content-Type", "audio/wav")
                    .setHeader("Cache-Control", "no-store").setHeader("Accept-Ranges", "bytes")
                    .setHeader("Content-Length", wav.size - start)
                if (range != null) response.setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-${wav.lastIndex}/${wav.size}")
                if (request.method != "HEAD") response.setBody(Buffer().write(wav, start, count))
                if (id == delayedTrack) response.setBodyDelay(1200, TimeUnit.MILLISECONDS)
                return response
            }
            if (request.path == "/api/me/history" && request.method == "POST") {
                check(request.getHeader("X-CSRF-Token") == "cache-csrf")
                history.incrementAndGet()
                return MockResponse().setResponseCode(204)
            }
            if (request.path != "/api/me/player") {
                unexpected += "${request.method} ${request.requestUrl!!.encodedPath}"
                return MockResponse().setResponseCode(404)
            }
            synchronized(this) {
                if (request.method == "GET") return json(view().toJson().toString())
                check(request.getHeader("X-CSRF-Token") == "cache-csrf")
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                when (val action = body.getValue("action").jsonPrimitive.content) {
                    "register" -> {
                        phone = "phone-${registrations.incrementAndGet()}"
                        if (!remote) { snapshot = PlaybackSnapshot(); metadata = null; sequence = 0 }
                    }
                    "poll" -> {
                        polls.incrementAndGet()
                        body["state"]?.let {
                            check(!remote) { "A controller must never publish local output over selected remote" }
                            snapshot = PlaybackSnapshot.fromJson(it.jsonObject)
                            sequence = body.getValue("stateSeq").jsonPrimitive.long
                            body["queueIntentState"]?.let { raw -> metadata = QueueIntentState.fromJson(raw.jsonObject, snapshot) }
                        }
                    }
                    "leave" -> { phone = ""; metadata = null }
                    else -> mutations += action // A remote transport command is allowed; acquiring output is not.
                }
                return json(view().toJson().toString())
            }
        }
        fun fetched() = audio.filter { it.method == "GET" }.map { it.id }.toSet()
        fun scheduledBodies(id: String) = audio.filter { it.id == id && it.method == "GET" }
            .sumOf { it.scheduledBodyBytes.toLong() }
        companion object {
            private const val SESSION = """{"accountId":"cache-account","username":"listener","role":"user","csrfToken":"cache-csrf"}"""
            // 192,044 bytes/track, generated on demand; no binary assets/whole-song cache helper.
            // All A..Z markers are distinct. Each bounded captured slice checks identical
            // 16-bit samples; this is sampled PCM identity, not a whole-stream hash.
            fun marker(id: String) = 0x1000 + id.single().code * 17
            fun wav(id: String, sampleRate: Int = 8000): ByteArray {
                val size = 12 * sampleRate * 2
                return ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN).apply {
                    put("RIFF".toByteArray()); putInt(size + 36); put("WAVEfmt ".toByteArray())
                    putInt(16); putShort(1.toShort()); putShort(1.toShort()); putInt(sampleRate); putInt(sampleRate * 2)
                    putShort(2.toShort()); putShort(16.toShort()); put("data".toByteArray()); putInt(size)
                    repeat(size / 2) { putShort(marker(id).toShort()) }
                }.array()
            }
        }
    }

    private inner class Journey(remote: Boolean = false, private val limits: SongCache.Limits = SongCache.Limits(),
        sampleRate: Int = 8000) : AutoCloseable {
        val app = ApplicationProvider.getApplicationContext<SpofifyApplication>()
        val room = Room(remote, sampleRate)
        val web = MockWebServer().apply { dispatcher = room; start(InetAddress.getByName("127.0.0.1"), 0) }
        val server = ServerAddress.parse("http://127.0.0.1:${web.port}")
        val sessions = Sessions()
        private val graphs = mutableListOf<AppGraph>()
        var graph: AppGraph
            private set
        var client: SpofifyClient
            private set
        private var service: ServiceController<PlaybackService>? = null
        var controller: MediaController? = null
            private set
        lateinit var session: MediaSession
            private set
        init {
            app.graph.clear()
            SongCachePlaybackMedia.reset()
            graph = install(AppGraph(app, sessions, Logins(), audioCacheLimits = limits))
            client = graph.configure(server)
            runBlocking { client.login("listener", "synthetic-password") }
        }
        private fun install(value: AppGraph): AppGraph {
            // Application installation only. No service/cache/plan fields are inspected.
            SpofifyApplication::class.java.getDeclaredField("graph").apply { isAccessible = true; set(app, value) }
            graphs += value
            return value
        }
        fun startService() {
            val before = SongCachePlaybackMedia.factories.get()
            val built = Robolectric.buildService(PlaybackService::class.java).create().also { service = it }
            val actual = built.get()
            shadowOf(actual.getSystemService(ActivityManager::class.java)).setProcesses(listOf(
                ActivityManager.RunningAppProcessInfo().apply {
                    pid = android.os.Process.myPid(); importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                }))
            shadowOf(actual.getSystemService(AudioManager::class.java))
                .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            await("production receiver registration") { graph.playbackCoordinator!!.state.value.connected }
            assertEquals(before + 1, SongCachePlaybackMedia.factories.get())
            val who = MediaSession.ControllerInfo.createTestOnlyControllerInfo(actual.packageName, 100,
                android.os.Process.myUid(), 0, 0, true, Bundle.EMPTY, true)
            session = requireNotNull(actual.onGetSession(who))
            val future = MediaController.Builder(actual, session.token).buildAsync()
            await("real controller connection") { future.isDone }
            controller = future.get(1, TimeUnit.SECONDS)
        }
        fun stopService() {
            controller?.release(); controller = null
            service?.destroy(); service = null
            pump(80)
        }
        fun recreateGraph(): SpofifyClient {
            stopService()
            val saved = requireNotNull(sessions.read())
            // Retire old runtime authority without logging out/purging its persistent namespace.
            graph.configure(server)
            graph = install(AppGraph(app, Sessions(saved), Logins(), audioCacheLimits = limits))
            client = requireNotNull(graph.client)
            return client
        }
        fun play(vararg ids: String) = playAt(0, *ids)
        fun playAt(start: Int, vararg ids: String) {
            val items = ids.mapIndexed { index, id -> track(id, "$id-$index") }
            assertNull(PlaybackService.replacePrepared(items.map { PlaybackMedia.create(it, client) }, client,
                start, false, true))
            await("actual started ${ids[start]} PCM through production source") {
                controller!!.isPlaying && controller!!.currentPosition >= 50 && consumed(ids[start]) > 0
            }
            assertNull(controller!!.playerError)
        }
        fun pause() {
            controller!!.pause()
            await("settled actual pause") { !controller!!.isPlaying && !room.snapshot.playing }
        }
        fun repeat(mode: Int, wire: String) {
            controller!!.repeatMode = mode
            await("accepted repeat $wire") { PlaybackService.exportLocalSnapshot().repeat == wire && room.snapshot.repeat == wire }
        }
        fun repeatOne() {
            repeat(Player.REPEAT_MODE_ALL, "all")
            repeat(Player.REPEAT_MODE_ONE, "one")
        }
        fun action(action: PlaybackListAction, vararg ids: String) {
            var result: PlaybackActionResult? = null
            PlaybackService.performPreparedAction(dev.spofify.mobile.core.PlaybackQueue(ids.mapIndexed { index, id -> track(id, "$id-added-$index") },
                total = ids.size), client, action, PlaybackService.interactionKey(), null, null, null) { result = it }
            await("real $action receipt") { result != null }
            val detail = requireNotNull(result!!.queueIntentResult)
            assertEquals("queued", detail.outcome)
            assertEquals("unchanged", detail.playback)
        }
        fun awaitFetched(vararg ids: String) = await("permitted window ${ids.toList()}") { room.fetched().containsAll(ids.toList()) }
        fun assertBounded(): SongCache.Inventory = graph.songCache.inventory().also {
            assertTrue("Owned bytes exceed fixture budget: $it", it.ownedBytes <= limits.maxBytes)
            assertTrue("Unique entries exceed fixture budget: $it", it.entries <= limits.maxTracks)
            assertFalse("Normal cache operation must not become bypassed", it.bypassed)
            assertFalse("Normal fixture cleanup must not fail", it.cleanupFailed)
        }
        fun focusRequest() = shadowOf(requireNotNull(service).get().getSystemService(AudioManager::class.java)).lastAudioFocusRequest
        fun assertForegroundStopped() { assertTrue(shadowOf(requireNotNull(service).get()).isForegroundStopped) }
        fun foregroundNotificationId() = shadowOf(requireNotNull(service).get()).lastForegroundNotificationId
        fun assertDormantServiceCannotOpenCache() {
            val factories = SongCachePlaybackMedia.factories.get()
            val registrations = room.registrations.get()
            val dormant = Robolectric.buildService(PlaybackService::class.java).create()
            try {
                pump(100)
                assertEquals(factories, SongCachePlaybackMedia.factories.get())
                assertEquals(registrations, room.registrations.get())
                assertEquals(0, shadowOf(dormant.get()).lastForegroundNotificationId)
                assertTrue(PlaybackService.exportLocalSnapshot().queue.isEmpty())
            } finally { dormant.destroy() }
        }
        fun assertHealthy() {
            assertTrue("Unexpected/unauthenticated requests: ${room.unexpected}", room.unexpected.isEmpty())
            assertTrue("Caching must not acquire/transfer output", room.mutations.none { it in setOf("transfer", "claim") })
            assertNull(controller?.playerError)
            assertEquals("", PlaybackService.exportLocalSnapshot().error)
            assertBounded()
        }
        override fun close() {
            var failure: Throwable? = null
            fun cleanup(block: () -> Unit) { try { block() } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            } }
            cleanup { stopService() }
            graphs.asReversed().forEach { retired -> cleanup { retired.clear() } }
            cleanup { pump(100) }
            cleanup { web.shutdown() }
            SongCachePlaybackMedia.reset()
            failure?.let { throw it }
        }
    }

    @Test fun prefetchedBIsActuallyConsumedAsBWithoutAnotherBodyOrFreshnessHead() {
        Journey().use { j ->
            j.startService()
            j.play("A", "B", "C", "D", "E", "F", "G")
            j.awaitFetched("A", "B", "C", "D", "E", "F")
            await("current plus permitted five are completely retained") { j.assertBounded().complete == 6 }
            assertEquals(setOf("A", "B", "C", "D", "E", "F"), j.room.fetched())
            assertEquals("Speculation never consumes B in the output renderer", 0L, consumed("B"))
            val before = j.room.audio.toList()
            val scheduledBeforeB = j.room.scheduledBodies("B")
            val bytesB = consumed("B")
            j.controller!!.seekToNextMediaItem()
            await("warm exact B really plays and advances") {
                j.controller!!.currentMediaItem?.mediaId == "B" && j.controller!!.isPlaying &&
                    j.controller!!.currentPosition > 50 && consumed("B") > bytesB
            }
            assertTrue("Cold B must have requested an authenticated nonempty body", scheduledBeforeB > 0)
            assertEquals("Warm B must not schedule another audio body", scheduledBeforeB, j.room.scheduledBodies("B"))
            assertEquals("No HEAD/GET freshness check on cached B", before.filter { it.id == "B" }, j.room.audio.filter { it.id == "B" })
            assertNotEquals(Room.marker("A"), Room.marker("B"))
            j.assertHealthy()
        }
    }

    @Test fun fiveOccurrenceStepsDeduplicateWithoutScanningFartherAndProgressDoesNotRestartWork() {
        Journey().use { j ->
            j.startService()
            j.play("A", "B", "B", "C", "D", "E", "F")
            j.awaitFetched("A", "B", "C", "D", "E")
            await("five unique IDs in six occurrence steps are retained") { j.assertBounded().complete == 5 }
            assertEquals(setOf("A", "B", "C", "D", "E"), j.room.fetched())
            assertEquals(1, j.room.audio.count { it.id == "B" && it.method == "GET" })
            val queue = PlaybackService.exportLocalSnapshot().queue
            assertEquals(2, queue.count { it.id == "B" })
            assertEquals(2, queue.filter { it.id == "B" }.map { it.key }.distinct().size)
            val requests = j.room.audio.toList()
            val starts = SongCachePlaybackMedia.starts.get()
            val event = PlaybackService.exportLocalSnapshot().occurrence?.eventId
            val position = j.controller!!.currentPosition
            val polls = j.room.polls.get()
            pump(2200) // More than two ordinary coordinator heartbeat intervals.
            assertTrue(j.controller!!.currentPosition > position + 100)
            assertTrue(j.room.polls.get() > polls)
            assertEquals("Progress/heartbeats cannot mint downloads", requests, j.room.audio.toList())
            assertEquals(starts, SongCachePlaybackMedia.starts.get())
            assertEquals(event, PlaybackService.exportLocalSnapshot().occurrence?.eventId)
            assertTrue("Only the actual A occurrence may earn history", j.room.history.get() <= 1)
            j.assertHealthy()
        }
    }

    @Test fun selectedRemoteControllerNeverOpensAudioOrAcquiresOutput() {
        Journey(remote = true).use { j ->
            assertTrue(j.room.audio.isEmpty()) // Login/probe/browse is not a cache trigger.
            j.startService()
            val focus = j.focusRequest()
            val starts = SongCachePlaybackMedia.starts.get()
            val polls = j.room.polls.get()
            pump(2200)
            assertTrue(j.room.polls.get() > polls)
            assertEquals("remote", j.graph.playbackCoordinator!!.state.value.activeDeviceId)
            assertTrue(j.room.audio.isEmpty())
            assertEquals(0L, SongCachePlaybackMedia.samples.get())
            assertEquals(starts, SongCachePlaybackMedia.starts.get())
            assertEquals(focus, j.focusRequest())
            assertEquals(0, j.room.history.get())
            assertEquals(1, j.room.registrations.get())
            assertEquals(0, j.foregroundNotificationId())
            assertTrue(j.room.mutations.isEmpty())
            assertTrue(PlaybackService.exportLocalSnapshot().queue.isEmpty())
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
            val stopped = PlaybackService.exportLocalSnapshot()
            val starts = SongCachePlaybackMedia.starts.get()
            pump(2200) // Also lets the cancelled finite slow response finish on the server.
            assertEquals(setOf("A", "B"), j.room.fetched())
            assertEquals(1, j.room.audio.count { it.id == "B" && it.method == "GET" })
            assertEquals(starts, SongCachePlaybackMedia.starts.get())
            assertEquals(0L, consumed("B"))
            val after = PlaybackService.exportLocalSnapshot()
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
            j.controller!!.pause()
            await("pause publication") { !j.controller!!.isPlaying && j.room.snapshot.playing == false }
            val before = PlaybackService.exportLocalSnapshot()
            val renderer = listOf(SongCachePlaybackMedia.starts.get(), SongCachePlaybackMedia.enables.get(), SongCachePlaybackMedia.resets.get())
            val focus = j.focusRequest()
            val history = j.room.history.get()
            j.action(PlaybackListAction.QUEUE_HIGH, "H")
            j.awaitFetched("H")
            j.action(PlaybackListAction.QUEUE_LOW, "L")
            j.awaitFetched("L")
            j.action(PlaybackListAction.PLAY_NEXT, "N")
            j.awaitFetched("N")
            assertEquals(listOf("N", "H", "B", "L"), PlaybackService.upcomingEntries().map { it.item.id })
            val after = PlaybackService.exportLocalSnapshot()
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
                PlaybackService.upcomingEntries().map { it.item.id })
            val starts = SongCachePlaybackMedia.starts.get()
            val occurrence = PlaybackService.exportLocalSnapshot().occurrence
            j.repeat(Player.REPEAT_MODE_OFF, "off")
            j.awaitFetched("N", "H", "B", "C", "D")
            pump(150)
            assertEquals(setOf("A", "N", "H", "B", "C", "D"), j.room.fetched())
            assertEquals(starts, SongCachePlaybackMedia.starts.get())
            assertEquals(occurrence, PlaybackService.exportLocalSnapshot().occurrence)
            assertFalse(j.controller!!.isPlaying)
            j.assertForegroundStopped()
            j.assertHealthy()
        }
    }

    @Test fun shuffleAndOccurrenceReorderRemoveUndoUseTheActualAcceptedTraversal() {
        Journey().use { j ->
            j.startService()
            j.play("A")
            j.pause()
            j.repeatOne()
            j.action(PlaybackListAction.QUEUE_LOW, "B", "C", "D", "E", "F", "G", "H", "I")
            j.controller!!.shuffleModeEnabled = true
            await("actual shuffled future") { PlaybackService.exportLocalSnapshot().shuffle }
            val shuffled = PlaybackService.upcomingEntries().toList()
            assertEquals(8, shuffled.size)
            assertEquals(setOf("A"), j.room.fetched())
            // Remove and Undo preserve this exact occurrence (not merely a track ID).
            val removed = shuffled[1]
            PlaybackService.removeQueued(removed.occurrenceId)
            await("accepted removal") { PlaybackService.upcomingEntries().none { it.occurrenceId == removed.occurrenceId } }
            PlaybackService.undoQueuedRemoval()
            await("accepted Undo") { PlaybackService.upcomingEntries().map { it.occurrenceId } == shuffled.map { it.occurrenceId } }
            // Absolute move deliberately exits shuffle, while keeping current A/history intact.
            val moved = shuffled.last()
            PlaybackService.moveQueued(requireNotNull(PlaybackService.interactionKey()), moved.occurrenceId, 0)
            await("accepted absolute reorder exits shuffle") {
                !PlaybackService.exportLocalSnapshot().shuffle && PlaybackService.upcomingEntries().firstOrNull()?.occurrenceId == moved.occurrenceId
            }
            val traversal = PlaybackService.upcomingEntries()
            assertEquals(listOf(moved.occurrenceId) + shuffled.dropLast(1).map { it.occurrenceId }, traversal.map { it.occurrenceId })
            val expected = setOf("A") + traversal.take(5).map { it.item.id }
            val current = PlaybackService.exportLocalSnapshot().occurrence
            j.repeat(Player.REPEAT_MODE_OFF, "off")
            await("real reordered occurrence window is fetched") { j.room.fetched().containsAll(expected) }
            pump(150)
            assertEquals(expected, j.room.fetched())
            assertEquals(current, PlaybackService.exportLocalSnapshot().occurrence)
            assertFalse(j.controller!!.isPlaying)
            j.assertForegroundStopped()
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
            val event = PlaybackService.exportLocalSnapshot().occurrence?.eventId
            val starts = SongCachePlaybackMedia.starts.get()
            j.action(PlaybackListAction.QUEUE_LOW, "D")
            j.awaitFetched("D")
            await("full cache evicts/adopts D without stopping actual A output") {
                j.assertBounded().complete == 3 && j.controller!!.isPlaying &&
                    j.controller!!.currentPosition > position + 100 && consumed("A") > pcm
            }
            assertEquals(starts, SongCachePlaybackMedia.starts.get())
            assertEquals(event, PlaybackService.exportLocalSnapshot().occurrence?.eventId)
            assertTrue(j.controller!!.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
            assertNotNull(PlaybackService.interactionKey())
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
            assertNotNull(PlaybackService.interactionKey())
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
            assertTrue(PlaybackService.exportLocalSnapshot().queue.isEmpty())
            pump(150)
            assertEquals(requests, j.room.audio.toList())
            j.room.restoreStatus = 503
            val failed = runCatching { runBlocking { j.graph.restore() } }
            assertTrue("Offline/server-unavailable restore must not authorize cached content", failed.isFailure)
            assertNull(restored.currentSession())
            pump(150)
            assertEquals(requests, j.room.audio.toList())
            assertTrue(PlaybackService.exportLocalSnapshot().queue.isEmpty())
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
    private fun consumed(id: String) = SongCachePlaybackMedia.bytes(Room.marker(id))
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.MILLISECONDS)
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Timed out: $label; audio samples=${SongCachePlaybackMedia.samples.get()}")
    }
    private fun pump(ms: Long) {
        repeat((ms / 5).toInt()) {
            shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.MILLISECONDS)
            Thread.sleep(5)
        }
    }
}
