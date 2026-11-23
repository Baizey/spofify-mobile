package dev.spofify.mobile.playback

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.spofify.mobile.core.playback.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
class RoutingPlayerTest {
    @Test fun frameworkCleanupCannotPauseRemoteOutputOrSubmitAnyMusicalCommand() = runTest {
        Harness(this).use { h ->
            h.remotePlaying()
            h.player.setPlayWhenReady(false)
            h.player.pause()
            h.player.stop()
            h.player.seekToDefaultPosition()
            h.player.seekToNext()
            h.player.setShuffleModeEnabled(true)
            assertTrue(h.commands.isEmpty())
            assertTrue(h.nativeCalls.isEmpty())
            assertTrue(h.controller.state.value.desiredPlaying)
            assertTrue(h.controller.state.value.isPlaying)
            assertEquals("remote", h.controller.state.value.snapshot?.selectedDeviceId)
        }
    }

    @Test fun explicitControllerRequestsStillControlRemotePlaybackThroughServerCommands() = runTest {
        Harness(this).use { h ->
            h.remotePlaying()
            h.controllerRequest = true
            h.player.setPlayWhenReady(false)
            h.player.play()
            h.player.seekToNext()
            h.controllerRequest = false
            h.player.setPlayWhenReady(false)
            assertEquals(listOf("pause", "resume", "next"), h.commands)
            assertTrue(h.nativeCalls.isEmpty())
            // Pending transport intents can't present actual playback as proven.
            assertFalse(h.controller.state.value.isPlaying)
        }
    }

    @Suppress("DEPRECATION")
    @Test fun transportProjectionDoesNotDependOnNativeQueueOrSeekability() {
        val nativeOnly = setOf(Player.COMMAND_PREPARE, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS)
        val transport = setOf(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP,
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_WINDOW,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_WINDOW,
            Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        val emptyDeck = mutableSetOf(Player.COMMAND_GET_TIMELINE)
        val preparedDeck = (emptyDeck + nativeOnly + Player.COMMAND_SEEK_FORWARD).toMutableSet()
        for (projected in listOf(emptyDeck, preparedDeck)) {
            projectTransportCommands(add = { projected.add(it) }, remove = { projected.remove(it) })
            assertTrue(projected.containsAll(transport))
            assertTrue(projected.contains(Player.COMMAND_GET_TIMELINE))
            assertTrue(projected.intersect(nativeOnly).isEmpty())
        }
        assertEquals(emptyDeck, preparedDeck)
    }

    @Test fun hardwareCommandFormsRouteOnlyThroughCurrentDeck() = runTest {
        Harness(this).use { h ->
            h.remotePlaying()
            var currentDeck = false
            val old = RoutingPlayer(h.native, h.target) { h.controllerRequest && !currentDeck }
            val replacement = RoutingPlayer(h.native, h.target) { h.controllerRequest && currentDeck }
            h.controllerRequest = true
            currentDeck = true
            old.seekToNext()
            replacement.seekToNext(); h.settleLastCommand()
            replacement.seekToNextMediaItem(); h.settleLastCommand()
            replacement.seekToPrevious(); h.settleLastCommand()
            replacement.seekToPreviousMediaItem(); h.settleLastCommand()
            replacement.seekForward(); h.settleLastCommand()
            replacement.seekBack(); h.settleLastCommand()
            replacement.pause(); h.settleLastCommand()
            replacement.play(); h.settleLastCommand()
            replacement.stop()
            assertEquals(listOf("next", "next", "previous", "previous", "seek", "seek", "pause", "resume", "pause"), h.commands)
            assertEquals(listOf(10.0, 0.0), h.seeks)
            assertEquals(10_000L, replacement.seekForwardIncrement)
            assertEquals(10_000L, replacement.seekBackIncrement)
            assertTrue(h.nativeCalls.isEmpty())
        }
    }

    @Test fun finitePlatformSeeksRemainServerIntentsWhileUnscopedCleanupStaysSilent() = runTest {
        Harness(this).use { h ->
            h.remotePlaying()
            h.controllerRequest = true
            h.player.seekTo(Long.MAX_VALUE)
            h.player.seekTo(-1L)
            assertEquals(listOf("seek", "seek"), h.commands)
            assertEquals(listOf(9_007_199_254_740_991L, 0L), h.seekPositionsMs)
            h.controllerRequest = false
            h.player.pause()
            assertEquals(listOf("seek", "seek"), h.commands)
        }
    }

    @Test fun desktopOrTouchSeekCannotCommitAgainstAnotherVisitOfTheSameSong() = runTest {
        Harness(this).use { h ->
            h.remotePlaying()
            val oldContext = h.target.presentation.gestureContext
            h.repeatVisit()
            h.target.seek(oldContext, 19.0)
            assertTrue(h.commands.isEmpty())
            h.target.seek(h.target.presentation.gestureContext, 19.0)
            assertEquals(listOf(19_000L), h.seekPositionsMs)
        }
    }

    @Test fun cachedCatalogCommandsWaitForTheIndexWithoutBlockingOnlineOrExistingTransport() = runTest {
        Harness(this).use { h ->
            h.indexReady.value = false
            val track = PlaybackTrackReference("track")
            assertTrue(h.target.presentation.ready)
            assertFalse(h.target.presentation.canLoadTrack)
            assertFalse(h.target.playTrack(track))
            assertFalse(h.target.queueTrack(track))
            h.indexReady.value = true
            assertTrue(h.target.playTrack(track))
            runCurrent()
            assertEquals("track", h.target.presentation.queue?.current?.id)
            h.indexReady.value = false
            h.remotePlaying()
            assertTrue(h.target.presentation.canLoadTrack)
            assertTrue(h.target.queueTrack(track))
        }
    }

    @Test fun retiredOnlineTargetRejectsEvenTrustedRequests() = runTest {
        Harness(this).use { h ->
            h.remotePlaying()
            h.controllerRequest = true
            h.current = false
            h.player.play()
            h.player.pause()
            h.player.seekForward()
            assertTrue(h.commands.isEmpty())
            assertTrue(h.nativeCalls.isEmpty())
        }
    }

    @Test fun localRequestsControlOnlyRealLocalQueueAndCannotCrossRetirement() = runTest {
        Harness(this).use { h ->
            h.controller.setOffline(manual = true)
            val target = h.target
            val player = h.player
            assertTrue(target.playTrack(PlaybackTrackReference("track")))
            runCurrent()
            assertTrue(target.presentation.ready)
            assertTrue(target.presentation.view.desiredPlaying)
            assertFalse(target.presentation.isPlaying) // no native observation of sound
            player.pause() // not a controller request
            runCurrent()
            assertTrue(target.presentation.view.desiredPlaying)
            h.controllerRequest = true
            player.pause()
            runCurrent()
            assertFalse(target.presentation.view.desiredPlaying)
            player.seekForward()
            runCurrent()
            assertEquals(10.0, target.presentation.elapsed, 0.0)
            player.seekBack()
            runCurrent()
            assertEquals(0.0, target.presentation.elapsed, 0.0)
            player.play()
            runCurrent()
            assertTrue(target.presentation.view.desiredPlaying)
            player.stop()
            runCurrent()
            assertFalse(target.presentation.view.desiredPlaying)
            h.current = false
            player.play()
            assertFalse(target.queueTrack(PlaybackTrackReference("track")))
            assertTrue(h.commands.isEmpty())
            assertTrue(h.nativeCalls.isEmpty())
        }
    }

    private class Harness(private val scope: TestScope) : AutoCloseable {
        private val incoming = Channel<String>(Channel.UNLIMITED)
        private val sent = mutableListOf<String>()
        val nativeCalls = mutableListOf<String>()
        var controllerRequest = false
        private val connection = object : PlaybackConnection {
            override val messages = incoming.receiveAsFlow()
            override var isOpen = true
            override fun send(text: String): Boolean { sent.add(text); return isOpen }
            override fun close() { isOpen = false; incoming.close() }
        }
        private val audio = object : AudioExecutor {
            private var facts = AudioFacts()
            override val observations = emptyFlow<AudioObservation>()
            override suspend fun prepare(binding: AudioBinding, track: PlaybackTrack?, elapsed: Double) = AudioResult.OK
            override suspend fun execute(binding: AudioBinding, track: PlaybackTrack?, elapsed: Double,
                desiredPlaying: Boolean, isAuthorized: () -> Boolean): AudioResult {
                if (!isAuthorized()) return AudioResult.REJECTED
                facts = AudioFacts(binding, elapsed, track?.duration ?: 0.0)
                return AudioResult.OK
            }
            override fun pause() = Unit
            override fun facts() = facts
        }
        private val library = object : LocalPlaybackLibrary {
            override fun resolve(track: PlaybackTrackReference?, context: PlaybackContext?) = LocalPlaybackRows(listOf(
                PlaybackTrack("track", "Song", emptyList(), "Album", "album", 120.0, 1, 1, "low")))
            override suspend fun hasAudio(track: PlaybackTrack, isAuthorized: () -> Boolean) = isAuthorized()
        }
        private val clock = object : PlaybackClock {
            override fun monotonicMs() = scope.testScheduler.currentTime
            override fun wallTimeMs() = 1_700_000_000_000L + monotonicMs()
        }
        val controller = AccountChannel(PlaybackTransport { _, _, _, _ -> connection }, audio, scope.backgroundScope, clock, library = library)
        val native = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            nativeCalls.add(method.name)
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                else -> null
            }
        } as Player
        var current = true
        val indexReady = MutableStateFlow(true)
        val target = AppPlayback(controller, indexReady) { current }
        val player = RoutingPlayer(native, target) { controllerRequest }
        private val envelopes get() = sent.map { Json.parseToJsonElement(it).jsonObject }
            .filter { it.getValue("type").jsonPrimitive.content == "command" }
        private val actions get() = envelopes.map { it.getValue("action").jsonObject }
        val commands: List<String> get() = actions.map { it.getValue("type").jsonPrimitive.content }
        val seekPositionsMs: List<Long> get() = actions.filter { it.getValue("type").jsonPrimitive.content == "seek" }
            .map { it.getValue("positionMs").jsonPrimitive.content.toLong() }
        val seeks: List<Double> get() = seekPositionsMs.map { it / 1000.0 }

        suspend fun settleLastCommand() {
            val id = envelopes.last().getValue("id")
            // No speculative visit should leak into the next independent hardware command.
            incoming.send("""{"type":"result","id":$id,"status":"stale","revision":1}""")
            scope.runCurrent()
        }

        suspend fun repeatVisit() {
            incoming.send(accountSnapshot("phone-connection", local = false, nowMs = clock.wallTimeMs(), revision = 2, occurrence = "repeat"))
            scope.runCurrent()
        }

        suspend fun remotePlaying() {
            controller.connect("Phone", "phone")
            scope.runCurrent()
            incoming.send(accountSnapshot("phone-connection", local = false, nowMs = clock.wallTimeMs()))
            scope.runCurrent()
            assertEquals(PlaybackConnectionState.READY, controller.state.value.connection)
        }

        override fun close() { controller.close() }
    }
}
