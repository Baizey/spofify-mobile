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
import dev.spofify.mobile.feature.devices.PlaybackDeviceIdentityStore
import javax.crypto.spec.SecretKeySpec
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

    /** Small authenticated synthetic control room, not real-broker acceptance.
     * Managed bootstrap directives advance only on actual production receiver ACKs;
     * musical state is published by that receiver, never a fabricated start/cache result.
     */
    private class Room(private val remote: Boolean = false, private val sampleRate: Int = 8000,
        private val longCurrent: Boolean = false, private val longFutureB: Boolean = false,
        private val normalTrackSeconds: Int = 12, private val managedContinuation: Boolean = false) : Dispatcher() {
        /** Bytes scheduled at dispatch, not bytes written to a socket or read by a client.
         * A delayed/cancelled response can schedule its full body but transmit less or nothing.
         */
        data class Audio(val id: String, val method: String, val range: String?, val scheduledBodyBytes: Int)
        val audio = CopyOnWriteArrayList<Audio>()
        @Volatile var onAudioRequest: ((Audio) -> Unit)? = null
        val unexpected = CopyOnWriteArrayList<String>()
        val registrations = AtomicInteger()
        val polls = AtomicInteger()
        val history = AtomicInteger()
        val mutations = CopyOnWriteArrayList<String>()
        @Volatile var restoreStatus = 200
        @Volatile var audioFailureStatus: Int? = null
        @Volatile var malformedAudio = false
        @Volatile var delayedTrack: String? = null
        @Volatile var snapshot = if (remote) PlaybackSnapshot(queue = listOf(
            PlaybackTrack("R", "Remote song", emptyList(), "", "", 12.0, key = 1, order = 0)),
            position = 0, playing = true) else PlaybackSnapshot()
        private var metadata: QueueIntentState? = null
        private var sequence = 0L
        private var phone = ""
        private var receiver = ""
        @Volatile var coordinationOutage = false
        @Volatile var receiverExpired = false
        @Volatile var registrationExpired = false
        @Volatile var coordinationEpoch = 1L
        @Volatile var allowContinuation = false
        val continuations = AtomicInteger()
        @Volatile var continuationReplyGate: CountDownLatch? = null
        @Volatile var postGrantReadGate: CountDownLatch? = null
        val blockedPostGrantReads = AtomicInteger()
        private var registrationGeneration = "registration-generation"
        private var ownerSelected = !managedContinuation
        // Durable selection survives ephemeral leave/disposal of this receiver.
        private var requestedLeadDeviceId = ""
        private var bootstrapTransfer: PlaybackTransfer? = null
        private var bootstrapDirective: PlaybackDirective? = null
        private var bootstrapDelivered = false
        private var bootstrapOutcome: PlaybackOutcome? = null
        val bootstrapClaims = AtomicInteger()
        val bootstrapPrepareAcks = AtomicInteger()
        val bootstrapActivationAcks = AtomicInteger()
        val controlTrace = CopyOnWriteArrayList<String>()
        private fun view(deliver: Boolean = false): PlaybackDevicesView {
            val selected = if (remote) "remote" else if (receiverExpired || !ownerSelected) "" else phone
            val directives = bootstrapDirective?.takeIf { deliver && !bootstrapDelivered }?.let {
                bootstrapDelivered = true; listOf(it)
            }.orEmpty()
            return PlaybackDevicesView(phone,
                (if (phone.isEmpty() || registrationExpired) emptyList() else listOf(PlaybackDevice(phone, "Phone", true))) +
                    if (remote) listOf(PlaybackDevice("remote", "Remote", true)) else emptyList(),
                selected, coordinationEpoch, if (selected.isEmpty()) 0 else 8000, state = snapshot, stateSeq = sequence,
                stateAgeMs = 0, transfer = bootstrapTransfer, directives = directives, commands = listOfNotNull(bootstrapOutcome),
                queueIntents = QueueIntentsView(1,
                    if (phone.isEmpty() || registrationExpired) emptyList() else listOf(QueueIntentReceiver(phone, 1)),
                    if (remote || selected.isEmpty() || registrationExpired || bootstrapTransfer != null) null else metadata?.let {
                        QueueIntentBinding(phone, it.receiverId, it.revision,
                            snapshot.queue.getOrNull(snapshot.position)?.key, snapshot.occurrence?.eventId)
                    }, if (remote) null else metadata, directives.map { QueueIntentDirective(it.deliveryId, metadata) }, emptyList()),
                registration = if (!managedContinuation) null else PlaybackRegistrationView(1, "admission-generation", "admission-token",
                    if (phone.isEmpty() || registrationExpired) emptyList() else listOf(PlaybackRegistrationDevice(phone, receiver, registrationGeneration))),
                leadership = if (!managedContinuation) null else PlaybackLeadership(1, if (requestedLeadDeviceId.isNotEmpty()) 1 else 0,
                    if (requestedLeadDeviceId.isNotEmpty()) "installation" else "unset", requestedLeadDeviceId))
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
                audioFailureStatus?.let {
                    audio += Audio(id, request.method!!, request.getHeader("Range"), 0)
                    return MockResponse().setResponseCode(it)
                }
                if (malformedAudio) return MockResponse().setHeader("Content-Type", "audio/wav").setBody("not an audio container")
                val wav = audioPayload(id)
                val range = request.getHeader("Range")
                val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                if (start !in wav.indices) return MockResponse().setResponseCode(416)
                val count = if (request.method == "HEAD") 0 else wav.size - start
                val observed = Audio(id, request.method!!, range, count)
                audio += observed
                onAudioRequest?.invoke(observed)
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
            if (coordinationOutage) return MockResponse().setResponseCode(503)
            synchronized(this) {
                if (request.method == "GET") {
                    controlTrace += "GET"
                    if (continuations.get() > 0) postGrantReadGate?.let { gate ->
                        blockedPostGrantReads.incrementAndGet()
                        check(gate.await(5, TimeUnit.SECONDS)) { "Held recovery GET was not released" }
                    }
                    return json(view().toJson().toString())
                }
                check(request.getHeader("X-CSRF-Token") == "cache-csrf")
                check(request.getHeader("Origin") == "http://127.0.0.1:${request.requestUrl!!.port}")
                val body = PlaybackRequestContract.fromRawJson(request.body.readUtf8())
                val action = body.getValue("action").jsonPrimitive.content
                if (managedContinuation && action != "register") {
                    check(body.getValue("deviceId").jsonPrimitive.content == phone)
                    check(body.getValue("registrationGeneration").jsonPrimitive.content == registrationGeneration)
                }
                when (action) {
                    "register" -> {
                        controlTrace += "register"
                        registrations.incrementAndGet()
                        if (registrationExpired) registrationGeneration = "renewed-registration-generation"
                        registrationExpired = false
                        phone = if (managedContinuation) {
                            val registration = body.getValue("registration").jsonObject
                            receiver = registration.getValue("receiverId").jsonPrimitive.content
                            registration.getValue("deviceId").jsonPrimitive.content
                        } else "phone-${registrations.get()}"
                        if (managedContinuation) ownerSelected = false // Stable registration is NOT an output grant.
                        if (!remote) { snapshot = PlaybackSnapshot(); metadata = null; sequence = 0 }
                    }
                    "transfer" -> {
                        // Same bounded synthetic handshake as PreparedResumeRoom in the
                        // actual service bridge tests; only REAL endpoint ACKs advance it.
                        check(managedContinuation && !ownerSelected && bootstrapTransfer == null)
                        check(body.getValue("requireUnselected") == JsonPrimitive(true))
                        check(body.getValue("targetDeviceId").jsonPrimitive.content == phone)
                        check(body.getValue("targetRegistrationGeneration").jsonPrimitive.content == registrationGeneration)
                        check(body.getValue("epoch").jsonPrimitive.long == coordinationEpoch)
                        val command = body.getValue("commandId").jsonPrimitive.content
                        bootstrapClaims.incrementAndGet(); requestedLeadDeviceId = phone
                        bootstrapTransfer = PlaybackTransfer(command, "", phone, "preparing")
                        bootstrapOutcome = PlaybackOutcome(command, "pending")
                        bootstrapDirective = PlaybackDirective("prepare-$command", coordinationEpoch, "prepare", command, state = snapshot.paused())
                        bootstrapDelivered = false
                        controlTrace += "bootstrap:claim"
                    }
                    "poll" -> {
                        polls.incrementAndGet()
                        controlTrace += if ("state" in body) "poll:publication" else "poll:renewal"
                        if (managedContinuation) check(body.getValue("epoch").jsonPrimitive.long == coordinationEpoch)
                        val acknowledgements = body["acks"]?.jsonArray.orEmpty()
                        val directive = bootstrapDirective
                        val acknowledgement = directive?.let { d -> acknowledgements.singleOrNull {
                            it.jsonObject.getValue("deliveryId").jsonPrimitive.content == d.deliveryId
                        }?.jsonObject }
                        if (acknowledgement != null && directive != null) {
                            check(bootstrapDelivered)
                            check(acknowledgement.getValue("epoch").jsonPrimitive.long == directive.epoch)
                            check(acknowledgement.getValue("status").jsonPrimitive.content == "ok")
                            if (directive.type == "prepare") {
                                check("state" !in body)
                                bootstrapPrepareAcks.incrementAndGet(); coordinationEpoch++; sequence = 0
                                ownerSelected = true
                                bootstrapTransfer = bootstrapTransfer!!.copy(phase = "activating")
                                bootstrapDirective = PlaybackDirective("activate-${directive.commandId}", coordinationEpoch, "activate",
                                    directive.commandId, state = snapshot.paused())
                                bootstrapDelivered = false
                                controlTrace += "bootstrap:prepare-ack"
                            } else {
                                check(directive.type == "activate" && "state" in body && "queueIntentState" in body)
                                check(!PlaybackSnapshot.fromJson(body.getValue("state").jsonObject).playing)
                                bootstrapActivationAcks.incrementAndGet()
                                bootstrapTransfer = null; bootstrapDirective = null
                                bootstrapOutcome = PlaybackOutcome(directive.commandId, "ok")
                                controlTrace += "bootstrap:activation-ack"
                            }
                        } else if (managedContinuation) check(acknowledgements.isEmpty())
                        body["state"]?.let {
                            check(!remote) { "A controller must never publish local output over selected remote" }
                            check(!managedContinuation || ownerSelected)
                            snapshot = PlaybackSnapshot.fromJson(it.jsonObject)
                            sequence = body.getValue("stateSeq").jsonPrimitive.long
                            body["queueIntentState"]?.let { raw ->
                                metadata = QueueIntentState.fromJson(raw.jsonObject, snapshot)
                                if (managedContinuation) check(metadata!!.receiverId == receiver)
                            }
                        }
                    }
                    "continue" -> {
                        // Synthetic contract fixture only, not a fabricated broker receipt.
                        // Real server admission/lease behavior has its separate Go gate.
                        if (!allowContinuation) return MockResponse().setResponseCode(400)
                        check(managedContinuation && receiverExpired && !registrationExpired)
                        check(body.keys == setOf("action", "deviceId", "registrationGeneration", "epoch", "expectedLeadRevision"))
                        check(body.getValue("deviceId").jsonPrimitive.content == phone)
                        check(body.getValue("registrationGeneration").jsonPrimitive.content == registrationGeneration)
                        check(body.getValue("epoch").jsonPrimitive.long == coordinationEpoch)
                        check(body.getValue("expectedLeadRevision").jsonPrimitive.long == 1L)
                        controlTrace += "continue"
                        continuations.incrementAndGet(); coordinationEpoch++; receiverExpired = false; ownerSelected = true
                        continuationReplyGate?.let { gate ->
                            check(gate.await(5, TimeUnit.SECONDS)) { "Held continuation reply was not released" }
                        }
                    }
                    "leave" -> { phone = ""; metadata = null }
                    else -> mutations += action // A remote transport command is allowed; acquiring output is not.
                }
                return json(view(deliver = action == "poll").toJson().toString())
            }
        }
        fun audioPayload(id: String) = wav(id, sampleRate, (durationMillis(id) / 1000).toInt())
        fun durationMillis(id: String) = if (longCurrent && id == "A" || longFutureB && id == "B") 120_000L else normalTrackSeconds * 1000L
        fun fetched() = audio.filter { it.method == "GET" }.map { it.id }.toSet()
        fun scheduledBodies(id: String) = audio.filter { it.id == id && it.method == "GET" }
            .sumOf { it.scheduledBodyBytes.toLong() }
        companion object {
            private const val SESSION = """{"accountId":"cache-account","username":"listener","role":"user","csrfToken":"cache-csrf"}"""
            // Normally 192,044 bytes/track; selected worker/FIFO tests use 120s A (and the
            // rebuffer variant also 120s B), crossing the progressive checkpoint before EOF.
            // Generated on demand;
            // no binary assets or whole-song cache helper.
            // All A..Z markers are distinct. Each bounded captured slice checks identical
            // 16-bit samples; this is sampled PCM identity, not a whole-stream hash.
            fun marker(id: String) = 0x1000 + id.single().code * 17
            fun wav(id: String, sampleRate: Int = 8000, seconds: Int = 12): ByteArray {
                val size = seconds * sampleRate * 2
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
        sampleRate: Int = 8000, longCurrent: Boolean = false, longFutureB: Boolean = false,
        normalTrackSeconds: Int = 12, managedContinuation: Boolean = false, openCacheWrite: ((File) -> OutputStream)? = null) : AutoCloseable {
        val app = ApplicationProvider.getApplicationContext<SpofifyApplication>()
        val room = Room(remote, sampleRate, longCurrent, longFutureB, normalTrackSeconds, managedContinuation)
        val web = MockWebServer().apply { dispatcher = room; start(InetAddress.getByName("127.0.0.1"), 0) }
        val server = ServerAddress.parse("http://127.0.0.1:${web.port}")
        val sessions = Sessions()
        private val graphs = mutableListOf<AppGraph>()
        val cacheReadTrace = CopyOnWriteArrayList<String>()
        var graph: AppGraph
            private set
        var client: SpofifyClient
            private set
        private var service: ServiceController<PlaybackService>? = null
        private var recoveryControls: PlaybackConnection? = null
        var controller: MediaController? = null
            private set
        lateinit var session: MediaSession
            private set
        init {
            app.graph.clear()
            SongCachePlaybackMedia.reset()
            graph = install(AppGraph(app, sessions, Logins(), audioCacheLimits = limits))
            if (openCacheWrite != null) {
                // Inject only the constructor's existing disk-I/O seam. Playback, authorization,
                // cache policy and scheduling remain production; no private state is asserted.
                graph.songCache.close()
                graph.songCache.awaitStorageForTest()
                AppGraph::class.java.getDeclaredField("songCache").apply {
                    isAccessible = true
                    set(graph, SongCache(app.cacheDir, limits, openWrite = openCacheWrite))
                }
            }
            client = graph.configure(server)
            runBlocking { client.login("listener", "synthetic-password") }
            if (managedContinuation) {
                // Inject only stable-identity storage/key material through its existing test
                // constructor. The real coordinator/API/endpoint/service/audio remain intact.
                graph.playbackCoordinator!!.close()
                val scope = AppGraph::class.java.getDeclaredField("coordinationScope").apply { isAccessible = true }
                    .get(graph) as CoroutineScope
                val identity = PlaybackDeviceIdentityStore(app.getSharedPreferences("continuation-${web.port}", 0),
                    { SecretKeySpec(ByteArray(16) { 7 }, "AES") }, server, "cache-account")
                val coordinator = PlaybackCoordinator(PlaybackDevicesApi(client), Media3PlaybackEndpoint(), scope,
                    identity = { identity })
                AppGraph::class.java.getDeclaredField("playbackCoordinator").apply { isAccessible = true; set(graph, coordinator) }
                PlaybackService.onServiceActiveChanged = { active ->
                    if (graph.playbackCoordinator === coordinator) coordinator.setServiceActive(active)
                }
            }
            timeoutDiagnostics = ::diagnostics
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
            recoveryControls?.release(); recoveryControls = null
            controller?.release(); controller = null
            service?.destroy(); service = null
            pump(80)
        }
        fun recreateGraph(): SpofifyClient {
            stopService()
            val saved = requireNotNull(sessions.read())
            // Retire old runtime authority without logging out/purging its persistent namespace.
            graph.configure(server)
            graph.songCache.awaitStorageForTest()
            graph = install(AppGraph(app, Sessions(saved), Logins(), audioCacheLimits = limits))
            client = requireNotNull(graph.client)
            return client
        }
        fun play(vararg ids: String) = playAt(0, *ids)
        fun playAt(start: Int, vararg ids: String) {
            val items = ids.mapIndexed { index, id -> track(id, "$id-$index").copy(durationMs = room.durationMillis(id)) }
            assertNull(PlaybackService.replacePrepared(items.map { PlaybackMedia.create(it, client) }, client,
                start, false, true))
            await("actual started ${ids[start]} PCM through production source") {
                controller!!.isPlaying && controller!!.currentPosition >= 50 && consumed(ids[start]) > 0
            }
            assertNull(controller!!.playerError)
            awaitLocalPublication("initial Play")
        }
        /** Dispatch mutates Room.snapshot before its HTTP response is accepted. Wait for the
         * production coordinator's accepted view and genuine controller projection instead;
         * never infer admission readiness from the fixture server's in-flight state alone.
         */
        fun awaitLocalPublication(label: String) {
            await("accepted local publication/controller projection after $label") {
                val owner = graph.playbackCoordinator!!.state.value
                val live = PlaybackService.exportLocalSnapshot()
                val accepted = owner.view?.state
                val metadata = PlaybackService.exportLocalQueueIntentState()
                val media = controller
                val repeat = when (live.repeat) {
                    "all" -> Player.REPEAT_MODE_ALL
                    "one" -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
                owner.connected && owner.exclusiveOwner && !owner.selectionUnconfirmed &&
                    owner.activeDeviceId == owner.deviceId && owner.view?.transfer == null &&
                    owner.canQueueIntent && PlaybackService.interactionKey() != null &&
                    metadata != null && owner.view?.queueIntents?.state == metadata &&
                    accepted != null && accepted.queue == live.queue && accepted.position == live.position &&
                    accepted.context == live.context && accepted.shuffle == live.shuffle && accepted.repeat == live.repeat &&
                    accepted.playing == live.playing && accepted.occurrence?.eventId == live.occurrence?.eventId &&
                    media != null && media.isPlaying == live.playing && media.repeatMode == repeat
            }
        }
        /** Same binding-only seam as PlaybackServiceBridgeTest.connectedPlayback.
         * The actual prepared/coordinator transport is production PlaybackConnection;
         * raw controller command admission/Prepare remain untouched and are not fallback. */
        private fun acceptedRecoveryControls(): PlaybackConnection = recoveryControls ?: PlaybackConnection(app).also { controls ->
            val binding = requireNotNull(controller)
            PlaybackConnection::class.java.getDeclaredField("controller").apply { isAccessible = true; set(controls, binding) }
            val listener = PlaybackConnection::class.java.getDeclaredField("listener").let {
                it.isAccessible = true; it.get(controls) as Player.Listener
            }
            binding.addListener(listener)
            controls.bindExplicitPlayOrigin {
                val source = client; val login = source.currentSession()
                val lifetime = service
                val stillCurrent: () -> Boolean = {
                    service === lifetime && lifetime != null && controller === binding && client === source &&
                        graph.client === source && login != null && source.currentSession() === login
                }
                stillCurrent
            }
            controls.connect()
            recoveryControls = controls
        }
        fun pauseThroughAcceptedControls() {
            assertTrue("Pause must not accidentally become Resume", PlaybackService.exportLocalSnapshot().playing)
            acceptedRecoveryControls().toggle()
        }
        fun activeAuthorityForReadOnlyProbe(): dev.spofify.mobile.feature.devices.PlaybackAuthority =
            PlaybackService::class.java.getDeclaredField("authority").apply { isAccessible = true }
                .get(requireNotNull(service).get()) as dev.spofify.mobile.feature.devices.PlaybackAuthority
        fun resumePreparedThroughAcceptedControls() {
            assertFalse(PlaybackService.exportLocalSnapshot().playing)
            acceptedRecoveryControls().toggle()
        }
        fun releaseBorrowedRecoveryControls() {
            val controls = recoveryControls ?: return
            // This connection borrowed Journey's existing controller; Journey still owns it.
            val field = PlaybackConnection::class.java.getDeclaredField("controller").apply { isAccessible = true }
            val listener = PlaybackConnection::class.java.getDeclaredField("listener").apply { isAccessible = true }
                .get(controls) as Player.Listener
            assertSame(controller, field.get(controls))
            controller!!.removeListener(listener)
            field.set(controls, null)
            controls.release()
            recoveryControls = null
        }
        fun seekThroughAcceptedControls(positionMs: Long) = acceptedRecoveryControls().seekTo(positionMs)
        fun selectThroughAcceptedControls(index: Int) = acceptedRecoveryControls().select(index)

        fun pause() {
            awaitLocalPublication("before Pause")
            controller!!.pause()
            await("actual paused output") { !controller!!.isPlaying && !PlaybackService.exportLocalSnapshot().playing }
            awaitLocalPublication("Pause")
        }
        fun preparePaused(id: String) {
            awaitLocalPublication("before paused $id replacement")
            val item = track(id, "$id-paused-probe").copy(durationMs = room.durationMillis(id))
            assertNull(PlaybackService.replacePrepared(listOf(PlaybackMedia.create(item, client)), client,
                0, false, false))
            await("new exact $id source is installed paused") {
                val snapshot = PlaybackService.exportLocalSnapshot()
                snapshot.queue.map { it.id } == listOf(id) && !snapshot.playing &&
                    controller!!.currentMediaItem?.mediaId == id && !controller!!.playWhenReady
            }
            awaitLocalPublication("paused $id replacement")
        }
        /** Recovery tests need a whole cached object, not successful speculation.
         * Prepare silently under the real receiver lease, then drain the EXACT
         * production service DataSource through EOF. No synthetic guard, cache file,
         * manifest edit, alternate session binding or source replacement is involved. */
        fun warmPreparedAudioThroughServiceSource(id: String) {
            require(id != "Z")
            // A pristine connected receiver has no accepted queue/owner permit yet.
            // Establish it via real explicit Play/Pause, never a fabricated lease or
            // an await requiring the publication that the first action must create.
            play("Z")
            val owner = requireNotNull(graph.playbackCoordinator)
            assertEquals(1, room.bootstrapClaims.get())
            assertEquals(1, room.bootstrapPrepareAcks.get())
            assertEquals(1, room.bootstrapActivationAcks.get())
            assertTrue(owner.state.value.exclusiveOwner && owner.state.value.canQueueIntent)
            val accepted = requireNotNull(owner.state.value.view)
            assertNull(accepted.transfer)
            assertEquals("ok", accepted.commands.single().status)
            val registration = requireNotNull(accepted.registration).devices.single { it.deviceId == owner.state.value.deviceId }
            assertEquals(registration.receiverId, PlaybackService.exportLocalQueueIntentState()!!.receiverId)
            assertEquals("cache-account", client.currentSession()!!.accountId)
            assertSame(client, graph.client)
            pauseThroughAcceptedControls()
            await("accepted app Pause stops the bootstrapped output") {
                !controller!!.isPlaying && !PlaybackService.exportLocalSnapshot().playing
            }
            awaitLocalPublication("accepted app Pause before cache setup")
            graph.songCache.awaitStorageForTest()
            preparePaused(id)
            assertFalse(controller!!.playWhenReady)
            assertTrue(requireNotNull(graph.playbackCoordinator!!.currentAuthority()).isValid())
            val factory = requireNotNull(SongCachePlaybackMedia.productionDataSources)
            val spec = DataSpec.Builder().setUri(client.audioUrl(track(id, "$id-warm"))).setKey(id).build()
            val expected = room.audioPayload(id)
            val requests = room.audio.count { it.id == id }
            val sourceInUse = AtomicReference<DataSource?>()
            val task = FutureTask<Unit> {
                val before = graph.songCache.inventory()
                assertFalse(before.bypassed)
                fun drain(phase: String): Boolean {
                    val source = factory.createDataSource()
                    val network = AtomicReference<Boolean>()
                    sourceInUse.set(source)
                    source.addTransferListener(object : TransferListener {
                        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
                        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                            network.set(isNetwork)
                            val http = source as? HttpDataSource
                            fun header(name: String) = source.responseHeaders.entries.firstOrNull { it.key.equals(name, true) }?.value?.singleOrNull()
                            cacheReadTrace += "$phase:start:key=${dataSpec.key}:position=${dataSpec.position}:length=${dataSpec.length}:network=$isNetwork:code=${http?.responseCode}:contentLength=${header("Content-Length")}:range=${header("Content-Range")}"
                        }
                        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) = Unit
                        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) { cacheReadTrace += "$phase:end:key=${dataSpec.key}:network=$isNetwork" }
                    })
                    try {
                        val opened = source.open(spec)
                        cacheReadTrace += "$phase:open:key=$id:returnedLength=$opened"
                        assertEquals(expected.size.toLong(), opened)
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(16 * 1024)
                        var count = 0L
                        while (true) {
                            val read = source.read(buffer, 0, buffer.size)
                            if (read == C.RESULT_END_OF_INPUT) break
                            assertTrue(read > 0)
                            count += read
                            assertTrue(count <= expected.size)
                            digest.update(buffer, 0, read)
                        }
                        cacheReadTrace += "$phase:EOF:key=$id:actualReadBytes=$count"
                        assertEquals(expected.size.toLong(), count)
                        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(expected), digest.digest())
                        return requireNotNull(network.get())
                    } finally { try { source.close() } finally { sourceInUse.compareAndSet(source, null) } }
                }
                assertTrue("First full read must use real authenticated HTTP", drain("warm-http"))
                graph.songCache.awaitStorageForTest()
                val committed = graph.songCache.inventory()
                cacheReadTrace += "warm:commit:before=$before:after=$committed"
                assertEquals(before.complete + 1, committed.complete)
                assertFalse(committed.bypassed)
                assertFalse("Reopen must use the SAME production cache/namespace", drain("warm-reopen"))
                assertEquals(requests + 1, room.audio.count { it.id == id })
            }
            val worker = Thread(task, "receiver-recovery-warm")
            worker.start()
            try {
                await("full production HTTP EOF, commit and file-backed reopen for $id") { task.isDone }
                task.get(1, TimeUnit.SECONDS)
            } finally {
                if (!task.isDone) { task.cancel(true); sourceInUse.get()?.close() }
                worker.join(10_000)
                assertFalse("Owned warm worker must stop", worker.isAlive)
                println("receiver-recovery cache trace=${cacheReadTrace.toList()}")
            }
            // A subsequent actual service start must independently demonstrate the
            // retained file path, not merely reuse these manually consumed bytes.
            SongCachePlaybackMedia.transferListener = object : TransferListener {
                override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
                override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                    cacheReadTrace += "service:start:key=${dataSpec.key}:network=$isNetwork"
                }
                override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) = Unit
                override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
            }
        }

        fun assertCachedReadSurvivesButSecureMissIsDenied(cachedId: String, uncachedId: String) {
            val factory = requireNotNull(SongCachePlaybackMedia.productionDataSources)
            val before = room.audio.toList()
            fun spec(id: String) = DataSpec.Builder().setUri(client.audioUrl(track(id, "$id-recovery-read"))).setKey(id).build()
            val hit = factory.createDataSource()
            try {
                assertEquals(room.audioPayload(cachedId).size.toLong(), hit.open(spec(cachedId)))
                val bytes = ByteArray(64)
                assertEquals(bytes.size, hit.read(bytes, 0, bytes.size))
                assertArrayEquals(room.audioPayload(cachedId).copyOf(bytes.size), bytes)
            } finally { hit.close() }
            val miss = factory.createDataSource()
            try {
                try { miss.open(spec(uncachedId)); fail("Pre-Pause response rearmed a secure cache miss") }
                catch (expected: IOException) { assertEquals("Required music is not cached. Playback stopped.", expected.message) }
            } finally { miss.close() }
            assertEquals("Neither the retained hit nor fenced miss may contact HTTP", before, room.audio.toList())
        }

        fun resume() {
            awaitLocalPublication("before deliberate Resume")
            val id = requireNotNull(controller!!.currentMediaItem).mediaId
            val pcm = consumed(id)
            val position = controller!!.currentPosition
            controller!!.play()
            await("deliberate Resume really starts exact $id and consumes new PCM") {
                controller!!.isPlaying && controller!!.currentPosition > position + 50 && consumed(id) > pcm
            }
            awaitLocalPublication("deliberate Resume")
            assertNull(controller!!.playerError)
        }
        fun setFocusResponse(response: Int) {
            shadowOf(requireNotNull(service).get().getSystemService(AudioManager::class.java))
                .setNextFocusRequestResponse(response)
        }
        fun awaitUnpinnedComplete(count: Int) {
            await("$count complete cache entries with all reader/writer pins released") {
                assertBounded().let { it.entries == count && it.complete == count && it.pinned == 0 }
            }
        }
        fun admitEToFullCache() {
            awaitUnpinnedComplete(3)
            // Hold the body, not an ACK: observe the real writer's reservation/eviction before
            // completion. This avoids mistaking the old three-complete inventory for E's ACK.
            room.delayedTrack = "E"
            action(PlaybackListAction.QUEUE_HIGH, "E")
            awaitFetched("E")
            await("E has evicted one whole entry and owns its incomplete reservation") {
                assertBounded().let { it.entries == 3 && it.complete == 2 && it.pinned == 1 }
            }
            awaitUnpinnedComplete(3)
            room.delayedTrack = null
            assertEquals(0L, consumed("E"))
            assertFalse(controller!!.isPlaying)
        }
        fun assertFreshCacheHit(id: String) {
            val before = room.audio.filter { it.id == id }
            assertTrue("$id must first have had a real authenticated upstream GET", before.any { it.method == "GET" })
            val pcm = consumed(id)
            preparePaused(id) // play=false forces a new source rather than positive-current reuse.
            resume()
            assertTrue(consumed(id) > pcm)
            assertEquals("Fresh $id source must use retained exact PCM without GET/HEAD", before, room.audio.filter { it.id == id })
        }
        fun assertFreshCacheMiss(id: String) {
            val gets = room.audio.count { it.id == id && it.method == "GET" }
            val pcm = consumed(id)
            preparePaused(id)
            resume()
            assertTrue(consumed(id) > pcm)
            assertEquals("Evicted $id must perform one new authenticated GET for actual output", gets + 1,
                room.audio.count { it.id == id && it.method == "GET" })
        }
        fun repeat(mode: Int, wire: String) {
            awaitLocalPublication("before repeat $wire")
            controller!!.repeatMode = mode
            await("actual repeat $wire") { PlaybackService.exportLocalSnapshot().repeat == wire }
            awaitLocalPublication("repeat $wire")
        }
        fun repeatOne() {
            repeat(Player.REPEAT_MODE_ALL, "all")
            repeat(Player.REPEAT_MODE_ONE, "one")
        }
        fun action(action: PlaybackListAction, vararg ids: String) {
            awaitLocalPublication("before $action")
            val key = requireNotNull(PlaybackService.interactionKey())
            var result: PlaybackActionResult? = null
            PlaybackService.performPreparedAction(dev.spofify.mobile.core.PlaybackQueue(ids.mapIndexed { index, id -> track(id, "$id-added-$index") },
                total = ids.size), client, action, key, null, null, null) { result = it }
            await("real $action receipt") { result != null }
            val detail = requireNotNull(result!!.queueIntentResult) { "Missing terminal detail: $result; ${diagnostics()}" }
            assertEquals("queued", detail.outcome)
            assertEquals("unchanged", detail.playback)
            awaitLocalPublication(action.toString())
        }
        var playNowResult: PlaybackActionResult? = null
            private set
        fun beginPlayNow(id: String) {
            awaitLocalPublication("before PLAY_NOW")
            playNowResult = null
            PlaybackService.performPreparedAction(dev.spofify.mobile.core.PlaybackQueue(listOf(track(id, "$id-recent")), total = 1),
                client, PlaybackListAction.PLAY_NOW, requireNotNull(PlaybackService.interactionKey()),
                null, null, null) { playNowResult = it }
        }
        private fun diagnostics(): String {
            val live = PlaybackService.exportLocalSnapshot()
            val owner = graph.playbackCoordinator?.state?.value
            val accepted = owner?.view?.state
            fun summary(value: PlaybackSnapshot?) = value?.let {
                "ids=${it.queue.map { track -> track.id }},at=${it.position},playing=${it.playing},repeat=${it.repeat},shuffle=${it.shuffle},error=${it.error}"
            }
            return "live={${summary(live)}}; accepted={${summary(accepted)}}; " +
                "owner connected=${owner?.connected},exclusive=${owner?.exclusiveOwner},canQueue=${owner?.canQueueIntent}," +
                "unconfirmed=${owner?.selectionUnconfirmed},message=${owner?.message}; " +
                "controller playing=${controller?.isPlaying},repeat=${controller?.repeatMode},shuffle=${controller?.shuffleModeEnabled}," +
                "failure=${controller?.playerError?.let(::playbackFailureDiagnostic)}; " +
                "inventory=${runCatching { graph.songCache.inventory() }.getOrNull()}; " +
                "audio=${room.audio.takeLast(32).map { "${it.method}:${it.id}:${it.range}" }}; " +
                "cacheReadTrace=${cacheReadTrace.takeLast(24)}"
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
            graphs.asReversed().forEach { retired -> cleanup { retired.songCache.awaitStorageForTest() } }
            cleanup { pump(100) }
            cleanup { web.shutdown() }
            SongCachePlaybackMedia.reset()
            timeoutDiagnostics = null
            failure?.let { throw it }
        }
    }

    @Test fun liveSeekRepeatAndSkipKeepActualServiceContinuationArmedAcrossOutage() {
        Journey(managedContinuation = true, normalTrackSeconds = 30).use { j ->
            j.startService()
            j.play("A", "B", "C", "D", "E", "F", "G")
            j.awaitFetched("A", "B", "C", "D", "E", "F")
            await("warm whole current and next objects") { j.assertBounded().complete >= 6 }
            j.controller!!.seekTo(1000)
            j.awaitLocalPublication("live Seek")
            j.repeat(Player.REPEAT_MODE_ALL, "all")
            j.controller!!.seekToNextMediaItem()
            await("real live Skip to B") { j.controller!!.currentMediaItem?.mediaId == "B" && j.controller!!.isPlaying }
            j.awaitLocalPublication("live Skip")
            val before = PlaybackService.exportLocalSnapshot()
            val pcm = consumed("B")
            j.room.coordinationOutage = true
            await("coordinator enters cached-only continuation") { !j.graph.playbackCoordinator!!.state.value.connected }
            // Beyond the old conservative six-second lease, through the real endpoint,
            // service history tick and production cache DataSource, not a fake Output.
            pump(6500)
            val after = PlaybackService.exportLocalSnapshot()
            assertTrue(j.controller!!.isPlaying)
            assertTrue(consumed("B") > pcm)
            assertEquals(before.queue, after.queue)
            assertEquals(before.occurrence?.eventId, after.occurrence?.eventId)
            assertTrue(after.elapsed > before.elapsed)
            assertTrue(j.room.mutations.isEmpty())
        }
    }

    @Test fun sameLeadReadThenGenuineReceiverRenewalRestoresUncachedAudioWithoutRestartOrImport() {
        Journey(managedContinuation = true, normalTrackSeconds = 30).use { j ->
            j.startService()
            j.play("A", "B", "C", "D", "E", "F", "G")
            j.awaitFetched("A", "B", "C", "D", "E", "F")
            await("whole warm A through F") { j.assertBounded().complete >= 6 }
            assertFalse(j.room.fetched().contains("G"))
            val before = PlaybackService.exportLocalSnapshot()
            val starts = SongCachePlaybackMedia.starts.get()
            j.room.coordinationOutage = true
            await("outage retains actual playing output") { !j.graph.playbackCoordinator!!.state.value.connected }
            assertTrue(j.controller!!.isPlaying)
            j.room.controlTrace.clear()
            j.room.coordinationOutage = false
            j.awaitLocalPublication("same-lead genuine renewal")
            val after = PlaybackService.exportLocalSnapshot()
            assertEquals("GET", j.room.controlTrace.first())
            assertEquals("poll:renewal", j.room.controlTrace[1])
            assertEquals(starts, SongCachePlaybackMedia.starts.get())
            assertEquals(before.queue, after.queue)
            assertEquals(before.occurrence?.eventId, after.occurrence?.eventId)
            assertTrue(after.elapsed >= before.elapsed)
            assertEquals(1, j.room.registrations.get())
            assertTrue(j.room.mutations.isEmpty())
            assertFalse(j.room.fetched().contains("G"))
            // G was outside current+next5. A fresh normal live skip must now perform the
            // production exact-origin authenticated read, not remain permanently cache-only.
            j.controller!!.seekToDefaultPosition(6)
            await("first previously uncached G really renders after lease renewal") {
                j.controller!!.currentMediaItem?.mediaId == "G" && j.controller!!.isPlaying && consumed("G") > 0
            }
            assertTrue(j.room.fetched().contains("G"))
            assertNull(j.controller!!.playerError)
        }
    }

    @Test fun failedReceiverValidationRemainsCachedOnlyAndRenewalNeverRestartsPausedOutput() {
        Journey(managedContinuation = true, normalTrackSeconds = 30).use { j ->
            j.startService()
            j.play("A")
            await("whole A") { j.assertBounded().complete == 1 }
            j.room.coordinationOutage = true
            await("cached continuation") { !j.graph.playbackCoordinator!!.state.value.connected }
            j.room.receiverExpired = true
            val polls = j.room.polls.get()
            j.room.coordinationOutage = false
            pump(1300)
            assertFalse(j.graph.playbackCoordinator!!.state.value.connected)
            assertEquals(polls, j.room.polls.get())
            assertTrue(j.controller!!.isPlaying)
            j.controller!!.pause()
            await("explicit cached Pause drains actual output") { !j.controller!!.isPlaying && !PlaybackService.exportLocalSnapshot().playing }
            val paused = PlaybackService.exportLocalSnapshot()
            val starts = SongCachePlaybackMedia.starts.get()
            j.room.receiverExpired = false
            j.awaitLocalPublication("paused genuine receiver renewal")
            assertEquals(starts, SongCachePlaybackMedia.starts.get())
            assertFalse(j.controller!!.isPlaying)
            assertFalse(PlaybackService.exportLocalSnapshot().playing)
            assertEquals(paused.queue, PlaybackService.exportLocalSnapshot().queue)
            assertEquals(paused.occurrence?.eventId, PlaybackService.exportLocalSnapshot().occurrence?.eventId)
            assertEquals(1, j.room.registrations.get())
            assertTrue(j.room.mutations.isEmpty())
        }
    }

    private fun verifyRecoveryForkTemp() {
        val actual = System.getProperty("java.io.tmpdir")
        assertNotNull("Actual forked JVM temp must not be null", actual)
        System.getenv("SPOFIFY_TEST_TMPDIR")?.let { expected -> assertEquals(expected, actual) }
        println("receiver-recovery fork java.io.tmpdir=$actual user.home=${System.getProperty("user.home")}")
    }

    @Test fun expiredReceiverReenrollmentRestoresSecureUncachedReadsAfterLiveTransportWithoutRestart() {
        verifyRecoveryForkTemp()
        Journey(managedContinuation = true, normalTrackSeconds = 30).use { j ->
            j.startService(); j.warmPreparedAudioThroughServiceSource("A")
            val aRequests = j.room.audio.filter { it.id == "A" }
            j.play("A", "B", "C", "D", "E", "F", "G")
            assertTrue(j.cacheReadTrace.contains("service:start:key=A:network=false"))
            assertEquals(aRequests, j.room.audio.filter { it.id == "A" })
            // Exercise the actual service/request authority fence after transport, not
            // merely the coordinator's renewed lease or an early retained permit.
            j.seekThroughAcceptedControls(1000); j.awaitLocalPublication("live Seek before expiry")
            val before = PlaybackService.exportLocalSnapshot()
            val starts = SongCachePlaybackMedia.starts.get()
            assertFalse(j.room.fetched().contains("G"))
            j.room.coordinationOutage = true
            await("cached-only exact service output") { !j.graph.playbackCoordinator!!.state.value.connected }
            j.room.receiverExpired = true; j.room.registrationExpired = true
            j.room.coordinationEpoch = 3; j.room.allowContinuation = true
            j.room.controlTrace.clear(); j.room.coordinationOutage = false
            j.awaitLocalPublication("fresh enrollment and conditional new-epoch grant")
            assertEquals(listOf("GET", "register", "continue"), j.room.controlTrace.take(3))
            assertEquals(1, j.room.continuations.get()); assertEquals(2, j.room.registrations.get())
            val after = PlaybackService.exportLocalSnapshot()
            assertEquals(starts, SongCachePlaybackMedia.starts.get())
            assertEquals(before.queue, after.queue); assertEquals(before.occurrence?.eventId, after.occurrence?.eventId)
            assertTrue(after.elapsed >= before.elapsed); assertTrue(j.room.mutations.isEmpty())
            assertFalse(j.room.fetched().contains("G"))
            j.selectThroughAcceptedControls(after.queue.indexOfFirst { it.id == "G" })
            await("previously uncached G uses secure production HTTP and real PCM after recovery") {
                j.controller!!.currentMediaItem?.mediaId == "G" && j.controller!!.isPlaying && consumed("G") > 0
            }
            assertTrue(j.room.fetched().contains("G")); j.assertHealthy()
        }
    }

    @Test fun expiredReceiverContinuationGrantKeepsExplicitCachedPauseAndExactOccurrence() {
        verifyRecoveryForkTemp()
        Journey(managedContinuation = true, normalTrackSeconds = 30).use { j ->
            j.startService(); j.warmPreparedAudioThroughServiceSource("A")
            val aRequests = j.room.audio.filter { it.id == "A" }
            j.play("A")
            assertTrue(j.cacheReadTrace.contains("service:start:key=A:network=false"))
            assertEquals(aRequests, j.room.audio.filter { it.id == "A" })
            j.room.coordinationOutage = true
            await("cached output before Pause") { !j.graph.playbackCoordinator!!.state.value.connected }
            j.pauseThroughAcceptedControls()
            await("actual cached Pause") { !j.controller!!.isPlaying && !PlaybackService.exportLocalSnapshot().playing }
            val paused = PlaybackService.exportLocalSnapshot()
            val starts = SongCachePlaybackMedia.starts.get()
            j.room.receiverExpired = true; j.room.registrationExpired = true
            j.room.coordinationEpoch = 3; j.room.allowContinuation = true
            j.room.controlTrace.clear(); j.room.coordinationOutage = false
            j.awaitLocalPublication("paused output obtains real authority without starting")
            assertEquals(listOf("GET", "register", "continue"), j.room.controlTrace.take(3))
            assertEquals(1, j.room.continuations.get()); assertEquals(starts, SongCachePlaybackMedia.starts.get())
            val after = PlaybackService.exportLocalSnapshot()
            assertFalse(j.controller!!.isPlaying); assertFalse(after.playing)
            assertEquals(paused.queue, after.queue); assertEquals(paused.occurrence?.eventId, after.occurrence?.eventId)
            assertEquals(paused.elapsed, after.elapsed, 0.05); j.assertHealthy()
        }
    }

    @Test fun heldContinuationResponseAcrossCachedPauseNeedsFreshObservationBeforeSecureReads() {
        verifyRecoveryForkTemp()
        Journey(managedContinuation = true, normalTrackSeconds = 30).use { j ->
            val reply = CountDownLatch(1)
            val freshRead = CountDownLatch(1)
            try {
                j.startService(); j.warmPreparedAudioThroughServiceSource("A")
                j.play("A", "B", "C", "D", "E", "F", "G")
                assertTrue(j.cacheReadTrace.contains("service:start:key=A:network=false"))
                val starts = SongCachePlaybackMedia.starts.get()
                j.room.coordinationOutage = true
                await("cached continuation before held grant") { !j.graph.playbackCoordinator!!.state.value.connected }
                j.room.receiverExpired = true; j.room.registrationExpired = true
                j.room.coordinationEpoch = 3; j.room.allowContinuation = true
                j.room.continuationReplyGate = reply; j.room.postGrantReadGate = freshRead
                j.room.controlTrace.clear(); j.room.coordinationOutage = false
                await("conditional grant accepted but actual HTTP reply held") { j.room.continuations.get() == 1 }
                j.pauseThroughAcceptedControls()
                await("cached controller Pause actually drains output") {
                    !j.controller!!.isPlaying && !PlaybackService.exportLocalSnapshot().playing
                }
                val paused = PlaybackService.exportLocalSnapshot()
                val history = j.room.history.get()
                reply.countDown()
                // Reaching this NEW blocked GET proves the old response settled without
                // switching to ordinary publication. Do not just sample a transient false.
                await("fresh post-Pause leadership GET starts while old grant stays unarmed") {
                    j.room.blockedPostGrantReads.get() == 1
                }
                assertFalse(j.graph.playbackCoordinator!!.state.value.connected)
                assertNull(j.graph.playbackCoordinator!!.currentAuthority())
                j.assertCachedReadSurvivesButSecureMissIsDenied("A", "G")
                assertEquals(starts, SongCachePlaybackMedia.starts.get())
                freshRead.countDown()
                j.awaitLocalPublication("genuinely fresh paused observation and receiver poll")
                assertEquals(listOf("GET", "register", "continue", "GET", "poll:renewal"), j.room.controlTrace.take(5))
                assertEquals(1, j.room.continuations.get())
                assertEquals(starts, SongCachePlaybackMedia.starts.get())
                assertEquals(history, j.room.history.get())
                val after = PlaybackService.exportLocalSnapshot()
                assertFalse(j.controller!!.isPlaying); assertFalse(after.playing)
                assertEquals(paused.queue, after.queue); assertEquals(paused.occurrence?.eventId, after.occurrence?.eventId)
                assertEquals(paused.elapsed, after.elapsed, 0.05); j.assertHealthy()
            } finally { reply.countDown(); freshRead.countDown() }
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
        verifyRecoveryForkTemp()
        val owned = requireNotNull(System.getenv("SPOFIFY_RUN_ROOT"))
        assertEquals("$owned/home", System.getProperty("user.home"))
        assertEquals("$owned/tmp", System.getProperty("java.io.tmpdir"))
        Journey(managedContinuation = true).use { j ->
            j.startService()
            val sameClient = j.client
            val sameSession = requireNotNull(sameClient.currentSession())
            val sameController = requireNotNull(j.controller)
            // Real explicit activation, including terminal prepare/activation ACKs, not a
            // lease-only startup. Existing managed fixture rejects wrong receiver metadata.
            j.play("Z")
            assertEquals(1, j.room.bootstrapClaims.get())
            assertEquals(1, j.room.bootstrapPrepareAcks.get())
            assertEquals(1, j.room.bootstrapActivationAcks.get())
            j.pauseThroughAcceptedControls()
            await("accepted bootstrap Pause") { !sameController.isPlaying && !PlaybackService.exportLocalSnapshot().playing }
            j.awaitLocalPublication("bootstrap Pause")
            j.preparePaused("A")
            val prepared = PlaybackService.exportLocalSnapshot()
            val metadata = requireNotNull(PlaybackService.exportLocalQueueIntentState())
            val state = requireNotNull(j.graph.playbackCoordinator).state.value
            val accepted = requireNotNull(state.view)
            val acceptedSnapshot = requireNotNull(accepted.state)
            assertEquals(1, prepared.queue.size)
            assertEquals(1, sameController.mediaItemCount)
            assertEquals(1, metadata.origins.size)
            assertEquals(prepared.queue, acceptedSnapshot.queue)
            assertEquals(metadata, accepted.queueIntents!!.state)
            assertEquals(prepared.queue.single().key, acceptedSnapshot.queue[acceptedSnapshot.position].key)
            assertEquals("A-paused-probe", prepared.queue.single().entryId)
            assertFalse(prepared.playing); assertFalse(sameController.playWhenReady)
            assertNull(accepted.transfer)
            assertSame(sameClient, j.graph.client); assertSame(sameSession, sameClient.currentSession())
            assertSame(sameController, j.controller); assertTrue(sameController.isConnected)
            println("prepared-resume preconditions sameClient=true sameSession=true sameController=true queue=1 origins=1 accepted=1 selectedOccurrence=true terminalActivation=true")
            // No message/toString/URL/header/payload capture: only causal code locations.
            fun safeCause(error: Throwable): String = generateSequence(error) { it.cause }.take(8).joinToString(" <- ") { cause ->
                cause.javaClass.name + cause.stackTrace.take(48).joinToString(prefix = "[", postfix = "]") { frame ->
                    "${frame.className}.${frame.methodName}(${frame.fileName}:${frame.lineNumber})"
                }
            }
            val failures = CopyOnWriteArrayList<String>()
            val terminal = CopyOnWriteArrayList<String>()
            val listener = object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) { terminal += safeCause(error) }
            }
            sameController.addListener(listener)
            SongCachePlaybackMedia.dataSourceFailure = { error, thread ->
                if (failures.size < 4) failures += "thread=${thread.name} id=${thread.id} cause=${safeCause(error)}"
            }
            val pcm = consumed("A")
            val requests = j.room.audio.count { it.id == "A" }
            try {
                j.resumePreparedThroughAcceptedControls()
                await("one-track prepared Resume reaches real PCM or a terminal causal diagnostic") {
                    terminal.isNotEmpty() || sameController.isPlaying && sameController.currentPosition >= 50 && consumed("A") > pcm
                }
                failures.forEach { println("prepared-resume-loader $it") }
                terminal.forEach { println("prepared-resume-terminal $it") }
                assertTrue("Production loader must not enter application-thread-only state: ${failures.joinToString()}", failures.isEmpty())
                assertTrue("Prepared Resume must consume the authenticated WAV", terminal.isEmpty() && consumed("A") > pcm)
                assertTrue(j.room.audio.count { it.id == "A" } > requests)
                j.awaitLocalPublication("one-track prepared Resume")
                val resumed = PlaybackService.exportLocalSnapshot()
                assertEquals(prepared.queue, resumed.queue)
                assertEquals(prepared.position, resumed.position)
                val priorOccurrence = prepared.occurrence
                if (priorOccurrence != null) assertEquals(priorOccurrence.eventId, resumed.occurrence?.eventId)
                else {
                    // A never-played prepared occurrence gets only its own witnessed restart.
                    val created = requireNotNull(resumed.occurrence)
                    assertEquals(prepared.queue.single().key, created.key)
                    assertEquals(prepared.queue.single().id, created.trackId)
                    assertEquals(created.eventId, PlaybackService.exportLocalSnapshot().occurrence?.eventId)
                }
                assertSame(sameClient, j.graph.client); assertSame(sameSession, sameClient.currentSession())
                assertSame(sameController, j.controller)
                j.assertHealthy()
            } finally {
                SongCachePlaybackMedia.dataSourceFailure = null
                sameController.removeListener(listener)
                j.releaseBorrowedRecoveryControls()
                assertSame(sameController, j.controller)
                assertTrue(sameController.isConnected)
            }
        }
    }

    @Test fun pendingPreparedResumePermitIsWorkerSafeAndCannotBorrowAnotherActionsSemanticSuccessor() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val loaderReleased = CountDownLatch(1)
        val held = AtomicBoolean()
        val heldSource = AtomicReference<DataSource>()
        Journey(managedContinuation = true).use { j ->
            try {
                j.startService(); j.play("Z")
                j.pauseThroughAcceptedControls()
                await("bootstrap Pause") { !j.controller!!.isPlaying && !PlaybackService.exportLocalSnapshot().playing }
                j.awaitLocalPublication("bootstrap Pause")
                j.preparePaused("A")
                SongCachePlaybackMedia.transferListener = object : TransferListener {
                    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
                    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
                    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                        if (source === heldSource.get()) closed.countDown()
                    }
                    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                        if (dataSpec.key != "A" || !held.compareAndSet(false, true)) return
                        check(isNetwork)
                        heldSource.set(source); entered.countDown()
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                        while (release.count != 0L) {
                            val remaining = deadline - System.nanoTime()
                            check(remaining > 0) { "Held Resume read was not released" }
                            try { release.await(remaining, TimeUnit.NANOSECONDS) } catch (_: InterruptedException) { /* Deliberately outlive cancellation. */ }
                        }
                    }
                }
                j.resumePreparedThroughAcceptedControls()
                await("genuine prepared Resume loader is held before readiness") { entered.count == 0L }
                assertFalse(j.controller!!.isPlaying)
                val permit = j.activeAuthorityForReadOnlyProbe()
                fun workerValidity(): Boolean {
                    val task = FutureTask<Boolean> { permit.isValid() }
                    val worker = Thread(task, "prepared-resume-permission-probe")
                    worker.start()
                    try { return task.get(2, TimeUnit.SECONDS) } finally { worker.join(2000); assertFalse(worker.isAlive) }
                }
                assertTrue("Unfinished genuine authority must be readable off-main without Media3", workerValidity())
                val before = PlaybackService.exportLocalSnapshot()
                val receiverAuthority = requireNotNull(j.graph.playbackCoordinator!!.currentAuthority())
                SongCachePlaybackMedia.onLoaderRelease = { { loaderReleased.countDown() } }
                // Another legitimately admitted receiver action, never a fabricated permit or
                // raw controller Play. Three synchronous repeat edits return to the same music
                // before a startup wait can sample it. Its successor cannot refresh our tap.
                runBlocking {
                    repeat(3) {
                        assertEquals("ok", Media3PlaybackEndpoint().execute(PlaybackDeviceCommand.transport("repeat"), receiverAuthority).status)
                    }
                }
                val after = PlaybackService.exportLocalSnapshot()
                assertEquals(before.queue, after.queue); assertEquals(before.position, after.position)
                assertEquals(before.repeat, after.repeat)
                assertTrue("The receiver lease itself remains valid", receiverAuthority.isValid())
                assertFalse("Semantic ABA must retire the original unfinished permit", workerValidity())
                PlaybackService.exportLocalSnapshot() // Republishing cannot resurrect it.
                assertFalse(workerValidity())
                release.countDown()
                await("superseded startup drains its real held loader") { closed.count == 0L && loaderReleased.count == 0L }
                assertFalse(j.controller!!.isPlaying)
                assertFalse(PlaybackService.exportLocalSnapshot().playing)
            } finally {
                release.countDown()
                SongCachePlaybackMedia.transferListener = null
                SongCachePlaybackMedia.onLoaderRelease = null
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
            assertFalse(PlaybackService.exportLocalSnapshot().playing)
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
                val beforePause = PlaybackService.exportLocalSnapshot()
                assertEquals("B", beforePause.queue[beforePause.position].id)
                assertNotEquals(Player.STATE_IDLE, j.session.player.playbackState)
                j.controller!!.pause() // Do not wait behind the deliberately held Play start.
                await("service output has executed Pause/stop while the original read is held") {
                    // Read the service's player, not optimistic MediaController transport flags.
                    j.session.player.playbackState == Player.STATE_IDLE && !j.session.player.playWhenReady &&
                        !j.session.player.isPlaying && !PlaybackService.exportLocalSnapshot().playing &&
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
                val paused = PlaybackService.exportLocalSnapshot()
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
                PlaybackService.exportLocalSnapshot().error == "media" &&
                    org.robolectric.shadows.ShadowLog.getLogsForTag("SpofifyPlayback").drop(logStart)
                        .any { it.msg.contains("http=404") }
            }
            assertTrue(j.room.audio.any { it.id == "B" && it.method == "GET" })
            assertFalse(j.controller!!.isPlaying)
            assertFalse(PlaybackService.exportLocalSnapshot().playing)
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
            assertNotEquals(Room.marker("A"), Room.marker("B"))
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
                val before = PlaybackService.exportLocalSnapshot()
                // Real MediaController -> service cancellation -> player Pause. A posted main
                // marker must also run before disk release, not merely enqueue the command.
                val mainMarker = AtomicBoolean()
                val start = System.nanoTime()
                j.controller!!.pause()
                android.os.Handler(Looper.getMainLooper()).post { mainMarker.set(true) }
                await("Pause and following main-thread control complete with disk still held") {
                    mainMarker.get() && !j.controller!!.isPlaying && !j.controller!!.playWhenReady &&
                        !PlaybackService.exportLocalSnapshot().playing
                }
                assertTrue("Pause/control must not wait for storage", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2))
                assertFalse("The disk hold must not time out to unblock controls", timedOut.get())
                assertEquals(1L, release.count)
                assertEquals(before.queue, PlaybackService.exportLocalSnapshot().queue)
                assertEquals(before.position, PlaybackService.exportLocalSnapshot().position)
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
            j.pause()
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
            j.awaitLocalPublication("shuffle")
            val shuffled = PlaybackService.upcomingEntries().toList()
            assertEquals(8, shuffled.size)
            assertEquals(setOf("A"), j.room.fetched())
            // Remove and Undo preserve this exact occurrence (not merely a track ID).
            val removed = shuffled[1]
            PlaybackService.removeQueued(removed.occurrenceId)
            await("accepted removal") { PlaybackService.upcomingEntries().none { it.occurrenceId == removed.occurrenceId } }
            j.awaitLocalPublication("remove")
            PlaybackService.undoQueuedRemoval()
            await("accepted Undo") { PlaybackService.upcomingEntries().map { it.occurrenceId } == shuffled.map { it.occurrenceId } }
            j.awaitLocalPublication("Undo")
            // Absolute move deliberately exits shuffle, while keeping current A/history intact.
            val moved = shuffled.last()
            PlaybackService.moveQueued(requireNotNull(PlaybackService.interactionKey()), moved.occurrenceId, 0)
            await("accepted absolute reorder exits shuffle") {
                !PlaybackService.exportLocalSnapshot().shuffle && PlaybackService.upcomingEntries().firstOrNull()?.occurrenceId == moved.occurrenceId
            }
            j.awaitLocalPublication("absolute reorder")
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
            j.pause()
            // These admissions happen AFTER the initial B start: FIFO is now B,C,D, so
            // only the upcoming successful Resume (not the first start) can save B from E.
            j.action(PlaybackListAction.QUEUE_HIGH, "C")
            j.awaitFetched("C")
            j.awaitUnpinnedComplete(2)
            j.action(PlaybackListAction.QUEUE_LOW, "D")
            j.awaitFetched("D")
            j.awaitUnpinnedComplete(3)
            val event = requireNotNull(PlaybackService.exportLocalSnapshot().occurrence).eventId
            val bRequests = j.room.audio.filter { it.id == "B" }
            val pcm = consumed("B")
            j.resume()
            assertTrue(consumed("B") > pcm)
            assertEquals("Resume does not require another body or freshness HEAD", bRequests, j.room.audio.filter { it.id == "B" })
            assertEquals("Promotion is independent of a newly minted/qualified history event", event,
                PlaybackService.exportLocalSnapshot().occurrence?.eventId)
            j.pause()
            j.awaitUnpinnedComplete(3)
            assertEquals(event, PlaybackService.exportLocalSnapshot().occurrence?.eventId)
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
                PlaybackService.exportLocalSnapshot().error == "blocked" && !j.controller!!.isPlaying
            }
            j.awaitLocalPublication("focus-denied B start")
            assertEquals(starts, SongCachePlaybackMedia.starts.get())
            assertEquals(pcm, consumed("B"))
            assertEquals(bRequests, j.room.audio.filter { it.id == "B" })
            assertNull("A failed start cannot earn an observed occurrence", PlaybackService.exportLocalSnapshot().occurrence)
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
            val event = requireNotNull(PlaybackService.exportLocalSnapshot().occurrence).eventId
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
                    assertEquals(event, PlaybackService.exportLocalSnapshot().occurrence?.eventId)
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
            assertEquals(event, PlaybackService.exportLocalSnapshot().occurrence?.eventId)
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
