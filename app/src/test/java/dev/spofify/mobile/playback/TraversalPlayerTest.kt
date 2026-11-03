package dev.spofify.mobile.playback

import androidx.media3.common.Player
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class TraversalPlayerTest {
    @Test fun previousUsesExecutionTimePositionAfterEarlierSeekNotAuthorizationTimePosition() {
        val fake = Output()
        var traversals = 0
        val player = TraversalPlayer(fake.player) { traversals++ }
        player.seekTo(10_000)
        player.seekToPrevious()
        assertEquals(1, traversals)
        assertEquals(0L, fake.position)
    }

    @Test fun scrubbingDoesNotResetHistoryAndPreviousUsesPlayersConfiguredThreshold() {
        val fake = Output().apply { threshold = 5_000 }
        var traversals = 0
        val player = TraversalPlayer(fake.player) { traversals++ }
        player.seekTo(4_000)
        player.seekToPrevious()
        player.seekTo(0)
        assertEquals(0, traversals)
        player.seekTo(6_000)
        player.seekToPrevious()
        assertEquals(1, traversals)
    }

    @Test fun endedReplayAndFirstTrackRestartBeginNewTraversals() {
        val fake = Output().apply { state = Player.STATE_ENDED }
        var traversals = 0
        val player = TraversalPlayer(fake.player) { traversals++ }
        player.seekToDefaultPosition()
        player.play()
        assertEquals(1, traversals)
        fake.hasPrevious = false
        player.seekToPrevious()
        assertEquals(2, traversals)
    }

    private class Output {
        var position = 0L
        var threshold = 3_000L
        var state = Player.STATE_READY
        var hasPrevious = true
        val player: Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getCurrentPosition" -> position
                "getMaxSeekToPreviousPosition" -> threshold
                "getPlaybackState" -> state
                "hasPreviousMediaItem" -> hasPrevious
                "seekTo" -> { position = args!!.last() as Long; null }
                "seekToPrevious" -> { position = 0; null }
                "seekToDefaultPosition" -> { position = 0; state = Player.STATE_READY; null }
                "play" -> { state = Player.STATE_READY; null }
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    else -> null
                }
            }
        } as Player
    }
}
