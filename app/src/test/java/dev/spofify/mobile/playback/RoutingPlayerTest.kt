package dev.spofify.mobile.playback

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.spofify.mobile.core.playback.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
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
            assertEquals("remote-connection", h.controller.state.value.snapshot?.selectedConnectionId)
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
            // Dispatching an intention does not optimistically rewrite authoritative playback.
            assertTrue(h.controller.state.value.desiredPlaying)
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
            replacement.seekToNext()
            replacement.seekToNextMediaItem()
            replacement.seekToPrevious()
            replacement.seekToPreviousMediaItem()
            replacement.seekForward()
            replacement.seekBack()
            replacement.pause()
            replacement.play()
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
            // The server owns clamping to the recording duration; local facts are unchanged.
            assertEquals(0.0, h.controller.state.value.playback!!.elapsed, 0.0)
            h.controllerRequest = false
            h.player.pause()
            assertEquals(listOf("seek", "seek"), h.commands)
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
            h.remotePlaying()
            val song = h.controller.state.value.current!!
            val library = object : LocalPlaybackLibrary {
                override fun resolve(track: PlaybackTrackReference?, context: PlaybackContext?) = LocalPlaybackRows(listOf(song))
                override suspend fun hasAudio(track: PlaybackTrack, isAuthorized: () -> Boolean) = isAuthorized()
            }
            val local = LocalPlaybackController(h.audio, library, backgroundScope, { h.current })
            val target = AppPlayback.Local(local) { h.current }
            val player = RoutingPlayer(h.native, target) { h.controllerRequest }
            assertTrue(target.queueTrack(PlaybackTrackReference(song.id)))
            runCurrent()
            val view = target.presentation as PlayerPresentation.Local
            assertSame(local.state.value, view.queue)
            assertTrue(view.queue.desiredPlaying)
            assertFalse(view.isPlaying) // no native observation of sound
            player.pause() // not a controller request
            assertTrue(local.state.value.desiredPlaying)
            h.controllerRequest = true
            player.pause()
            assertFalse(local.state.value.desiredPlaying)
            player.seekForward()
            assertEquals(10.0, local.state.value.elapsed, 0.0)
            player.seekBack()
            assertEquals(0.0, local.state.value.elapsed, 0.0)
            player.play()
            assertTrue(local.state.value.desiredPlaying)
            player.stop()
            assertFalse(local.state.value.desiredPlaying)
            local.retire()
            h.current = false
            player.play()
            assertFalse(target.queueTrack(PlaybackTrackReference(song.id)))
            assertNull(local.state.value.current)
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
        val audio = object : AudioExecutor {
            override val observations = emptyFlow<AudioObservation>()
            override suspend fun prepare(binding: AudioBinding, track: PlaybackTrack?, elapsed: Double) = AudioResult.OK
            override suspend fun execute(binding: AudioBinding, track: PlaybackTrack?, elapsed: Double,
                desiredPlaying: Boolean, isAuthorized: () -> Boolean) = AudioResult.OK
            override fun pause() = Unit
            override fun facts() = AudioFacts()
        }
        val controller = AccountChannel(PlaybackTransport { _, _ -> connection }, audio, scope.backgroundScope)
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
        val target = AppPlayback.Online(controller) { current }
        val player = RoutingPlayer(native, target) { controllerRequest }
        private val frames get() = sent.map { Json.parseToJsonElement(it).jsonObject }
        val commands: List<String> get() = frames.map { it.getValue("type").jsonPrimitive.content }.filterNot { it == "presence" }
        val seeks: List<Double> get() = frames.filter { it.getValue("type").jsonPrimitive.content == "seek" }
            .map { it.getValue("value").jsonPrimitive.content.toDouble() }

        suspend fun remotePlaying() {
            controller.connect("Phone", "phone")
            scope.runCurrent()
            incoming.send(accountSnapshot("phone-connection", local = false))
            scope.runCurrent()
            assertEquals(PlaybackConnectionState.READY, controller.state.value.connection)
        }

        override fun close() { controller.close() }
    }
}
