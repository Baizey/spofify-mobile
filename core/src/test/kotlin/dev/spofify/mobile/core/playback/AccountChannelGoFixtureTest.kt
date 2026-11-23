package dev.spofify.mobile.core.playback

import dev.spofify.mobile.core.auth.*
import dev.spofify.mobile.core.http.OkHttpAuthApi
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in literal-wire check against an isolated Go fixture. Native decoder/FGS are not exercised. */
class AccountChannelGoFixtureTest {
    @Test fun kotlinPlaybackUsesRealGoV3AdmissionAndOwnership() = runBlocking {
        val ready = System.getenv("CROSS_NATIVE_READY")?.let {
            playbackJson.parseToJsonElement(Files.readString(Path.of(it))).jsonObject
        }
        val origin = System.getenv("SPOFIFY_V3_FIXTURE_URL") ?: ready?.string("origin")
        val username = System.getenv("SPOFIFY_V3_FIXTURE_USER") ?: ready?.string("username")
        val password = System.getenv("SPOFIFY_V3_FIXTURE_PASSWORD") ?: ready?.string("password")
        val track = System.getenv("SPOFIFY_V3_FIXTURE_TRACK") ?: ready?.getValue("trackIds")?.jsonArray?.first()?.jsonPrimitive?.content
        val device = System.getenv("SPOFIFY_V3_FIXTURE_DEVICE")
        val externalPeer = System.getenv("SPOFIFY_V3_FIXTURE_PEER")
        require((device == null) == (externalPeer == null))
        assumeTrue("Requires an isolated Go server and local fixture account/track", listOf(origin, username, password, track).all { !it.isNullOrBlank() })
        val url = URI(requireNotNull(origin))
        require(url.scheme in setOf("http", "https") && url.host in setOf("localhost", "127.0.0.1", "[::1]") &&
            url.userInfo == null && url.query == null && url.fragment == null && url.path in setOf("", "/"))
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext.job))
        val http = OkHttpAuthApi.defaultClient()
        val sessions = SessionRepository(object : SessionStore {
            override fun read(): StoredSession? = null
            override fun write(value: StoredSession?) = Unit
        }, object : LoginStore {
            override fun read(): RememberedLogin? = null
            override fun write(value: RememberedLogin) = Unit
        }, AuthApiFactory { OkHttpAuthApi(it, http) }, scope)
        val channels = ArrayList<AccountChannel>()
        try {
            sessions.start()
            withTimeout(10_000) { sessions.state.first { it is AuthState.SignedOut } }
            sessions.signIn(origin, requireNotNull(username), requireNotNull(password))
            val identity = withTimeout(10_000) { sessions.onlineGrant.first { it != null } }!!.identity
            val instance = "android-v3-${UUID.randomUUID()}"
            val first = "$instance-a"
            val second = "$instance-b"
            fun phone(name: String, id: String): Pair<AccountChannel, FixtureAudio> {
                val audio = FixtureAudio()
                val channel = AccountChannel(sessions.playbackTransport(identity), audio, scope)
                channels += channel
                channel.connect(name, id)
                return channel to audio
            }
            val reference = PlaybackTrackReference(requireNotNull(track))
            if (device != null) {
                val (mobile, audio) = phone("Fixture Android", device)
                val following = mobile.await { !it.localOutput && it.snapshot?.selectedDeviceId == externalPeer }
                assertTrue(mobile.playTrack(reference))
                val remote = mobile.await { it.isPlaying && it.current?.id == track && it.current?.key?.let(::isPublishedQueueKey) == true }
                assertEquals(0, audio.starts)
                assertEquals(externalPeer, remote.snapshot?.selectedDeviceId)
                assertTrue(mobile.selectDevice(device))
                val local = mobile.await { it.snapshot?.selectedDeviceId == device && it.localOutput && it.isPlaying }
                assertEquals(following.snapshot!!.outputVersion + 1, local.snapshot?.outputVersion)
                assertEquals(remote.snapshot?.state?.occurrence?.eventId, local.snapshot?.state?.occurrence?.eventId)
                assertEquals(1, audio.starts)
                // Keep the real binding alive through several report samples so the peer can
                // verify follower sound is remote. The runner also asserts the wire evidence.
                delay(1_000)
                return@runBlocking
            }
            val (owner, ownerAudio) = phone("Fixture phone A", first)
            val initial = owner.await { it.localOutput && it.snapshot?.selectedDeviceId == first }
            assertTrue("Use a fresh fixture account without a saved queue", initial.queue.isEmpty())
            val (controller, controllerAudio) = phone("Fixture phone B", second)
            controller.await { !it.localOutput && it.snapshot?.selectedDeviceId == first }
            assertTrue(controller.playTrack(reference))
            val playing = owner.await { it.current?.id == track && it.isPlaying }
            var visit = requireNotNull(playing.snapshot?.state?.occurrence).eventId
            controller.await { it.snapshot?.state?.occurrence?.eventId == visit && it.isPlaying }
            val oldBinding = requireNotNull(ownerAudio.facts().binding)
            assertTrue(controller.seek(8.0))
            owner.await { it.isPlaying && it.playback?.let { p -> p.elapsed >= 8 && p.eventId == visit } == true }
            controller.await { it.isPlaying && it.playback?.let { p -> p.elapsed >= 8 && p.eventId == visit } == true }
            assertEquals(2, ownerAudio.starts)
            ownerAudio.lateEof(oldBinding) // A callback from the invalidated pre-Seek binding.
            yield()
            assertEquals(2, ownerAudio.starts)

            System.getenv("CROSS_NATIVE_CONTROL")?.let { control ->
                assertTrue(controller.setRepeat("one"))
                owner.await { it.snapshot?.state?.repeat == "one" }
                val hold = Path.of(control, "hold.out.$first.execute")
                val entered = Path.of(control, "entered.out.$first.execute")
                Files.createFile(hold)
                try {
                    assertTrue(controller.seek(14.0))
                    withTimeout(4_000) { while (!Files.exists(entered)) delay(5) }
                    ownerAudio.finish() // Repeat the same row before its original visit can receive execute.
                    owner.await { it.isPlaying && it.snapshot?.state?.occurrence?.eventId != visit }
                    val repeated = controller.await { it.isPlaying && it.snapshot?.state?.occurrence?.eventId != visit }
                    val newVisit = requireNotNull(repeated.snapshot?.state?.occurrence).eventId
                    assertEquals(3, ownerAudio.starts)
                    assertTrue(requireNotNull(repeated.playback).elapsed < 14.0)
                    Files.delete(entered)
                    owner.await { it.isPlaying && it.snapshot?.state?.occurrence?.eventId == newVisit }
                    visit = newVisit
                } finally {
                    Files.deleteIfExists(entered)
                    Files.deleteIfExists(hold)
                }
                assertTrue(controller.setRepeat("off"))
                owner.await { it.snapshot?.state?.repeat == "off" }
                assertEquals(3, ownerAudio.starts)
            }
            val startsBeforeNext = ownerAudio.starts
            assertTrue(controller.queueTrack(reference))
            controller.await { it.queue.size == 2 && it.queue.all { row -> isPublishedQueueKey(row.key) } }
            assertTrue(controller.next())
            val next = owner.await { it.snapshot?.state?.position == 1 && it.isPlaying }
            val nextVisit = requireNotNull(next.snapshot?.state?.occurrence).eventId
            assertNotEquals(visit, nextVisit)
            controller.await { it.snapshot?.state?.occurrence?.eventId == nextVisit && it.isPlaying }
            assertEquals(startsBeforeNext + 1, ownerAudio.starts)
            assertEquals(0, controllerAudio.starts)
            assertTrue(controller.selectDevice(second))
            val selected = controller.await { it.snapshot?.selectedDeviceId == second && it.localOutput && it.isPlaying }
            owner.await { it.snapshot?.selectedDeviceId == second && !it.localOutput }
            assertFalse(ownerAudio.facts().isPlaying)
            assertEquals(nextVisit, selected.snapshot?.state?.occurrence?.eventId)
            val (replacement, replacementAudio) = phone("Fixture phone B", second)
            val rebound = replacement.await { it.snapshot?.selectedDeviceId == second && it.localOutput && it.isPlaying }
            controller.await { it.superseded && !it.localOutput }
            assertFalse(controllerAudio.facts().isPlaying)
            assertEquals(selected.snapshot?.outputVersion, rebound.snapshot?.outputVersion)
            assertEquals(nextVisit, rebound.snapshot?.state?.occurrence?.eventId)
            assertEquals(1, replacementAudio.starts)
            replacementAudio.finish()
            val stopped = owner.await { !it.desiredPlaying && it.snapshot?.state?.occurrence?.eventId == nextVisit }
            assertFalse(stopped.isPlaying)
            assertEquals(0.0, stopped.playback?.elapsed ?: -1.0, 0.0)
        } finally {
            channels.asReversed().forEach(AccountChannel::close)
            scope.coroutineContext.job.cancelAndJoin()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdownNow()
        }
    }

    private suspend fun AccountChannel.await(predicate: (PlaybackView) -> Boolean) = withTimeout(10_000) { state.first(predicate) }

    private class FixtureAudio : AudioExecutor {
        override val observations = MutableSharedFlow<AudioObservation>(extraBufferCapacity = 16)
        private var latest = AudioFacts()
        var starts = 0
            private set
        override fun facts() = latest
        override fun pause() { latest = latest.copy(isPlaying = false) }
        override suspend fun prepare(binding: AudioBinding, track: PlaybackTrack?, elapsed: Double) = error("Use fenced execution")
        override suspend fun execute(binding: AudioBinding, track: PlaybackTrack?, elapsed: Double,
            desiredPlaying: Boolean, isAuthorized: () -> Boolean): AudioResult {
            if (!isAuthorized()) return AudioResult.REJECTED
            if (desiredPlaying) starts++
            latest = AudioFacts(binding, elapsed, duration = track?.duration ?: 0.0, isPlaying = desiredPlaying)
            return AudioResult.OK
        }
        fun lateEof(binding: AudioBinding) {
            check(observations.tryEmit(AudioObservation(binding, AudioFacts(binding, 42.0, ended = true))))
        }
        fun finish() {
            val binding = requireNotNull(latest.binding)
            latest = latest.copy(isPlaying = false, ended = true, elapsed = latest.duration)
            check(observations.tryEmit(AudioObservation(binding, latest)))
        }
    }
}
