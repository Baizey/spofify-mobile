package dev.spofify.mobile.playback

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.util.Log
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.spofify.mobile.core.auth.AccountIdentity
import dev.spofify.mobile.core.auth.SessionRepository
import dev.spofify.mobile.core.playback.*
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** The sole local audio executor. Every operation and callback runs on the player looper. */
@UnstableApi
internal class Media3AudioExecutor(
    context: Context,
    sessions: SessionRepository,
    identity: AccountIdentity,
    private val onSystemPause: () -> Unit,
    private val requestPlaybackStart: () -> Boolean,
) : AudioExecutor, Closeable {
    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(DataSource.Factory { SessionAudioDataSource(sessions, identity) }))
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .build()
    private val mutableObservations = MutableSharedFlow<AudioObservation>(
        extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val observations = mutableObservations.asSharedFlow()
    private var binding: AudioBinding? = null
    private var generation = 0L
    private var preparation: NativePreparation? = null
    private var endedBinding: AudioBinding? = null
    // A late EOF after stopped setup is not a new traversal, even after its readiness waiter completes.
    private var traversalBinding: AudioBinding? = null
    private var blocked = false
    private var released = false
    private var playbackAuthority: (() -> Boolean)? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (released) return
            val current = binding ?: return
            if (player.currentMediaItem?.mediaId != current.trackId) return
            val pending = preparation?.takeIf { it.matches(generation, current, player.currentMediaItem?.mediaId) }
            val state = player.playbackState
            val hasError = player.playerError != null
            if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && state == Player.STATE_ENDED && !hasError &&
                traversalBinding == current && (pending == null || pending.reportsNativeEnd(state, hasError, player.duration))) endedBinding = current
            pending?.outcome(state, hasError, player.duration)?.let { outcome ->
                if (state == Player.STATE_ENDED) Log.i("SpofifyPlayer",
                    "Native terminal preparation: playRequested=${pending.playRequested}, result=$outcome")
                pending.result.complete(outcome)
            }
            mutableObservations.tryEmit(AudioObservation(current, facts()))
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (released || binding == null) return
            if (!playWhenReady && reason in setOf(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)) onSystemPause()
        }
    }

    init { player.addListener(listener) }

    override suspend fun prepare(binding: AudioBinding, track: PlaybackTrack?, elapsed: Double): AudioResult =
        load(binding, track, elapsed)

    override suspend fun execute(
        binding: AudioBinding, track: PlaybackTrack?, elapsed: Double,
        desiredPlaying: Boolean, isAuthorized: () -> Boolean,
    ): AudioResult {
        checkThread()
        if (!isAuthorized()) { pause(); return AudioResult.REJECTED }
        val result = load(binding, track, elapsed, playRequested = desiredPlaying)
        if (result != AudioResult.OK) return result
        if (this.binding != binding || !isAuthorized()) { pause(); return AudioResult.REJECTED }
        // A known terminal position cannot start sound or justify a new foreground-service deadline.
        if (track != null && desiredPlaying && player.playbackState != Player.STATE_ENDED) {
            val nativeGeneration = generation
            playbackAuthority = {
                !released && this.binding == binding && generation == nativeGeneration && isAuthorized()
            }
            if (!requestPlaybackStart()) {
                pause()
                blocked = true
                return AudioResult.BLOCKED
            }
            player.play()
            if (!player.playWhenReady) {
                pause()
                blocked = true
                return AudioResult.BLOCKED
            }
        }
        mutableObservations.tryEmit(AudioObservation(binding, facts()))
        return AudioResult.OK
    }

    private suspend fun load(next: AudioBinding, track: PlaybackTrack?, elapsed: Double, playRequested: Boolean = false): AudioResult {
        checkThread()
        pause()
        binding = next
        traversalBinding = next.takeIf { playRequested }
        endedBinding = null
        blocked = false
        val token = generation
        try {
            if (track == null) {
                player.stop()
                player.clearMediaItems()
                return AudioResult.OK
            }
            require(elapsed.isFinite() && elapsed >= 0)
            val position = (elapsed * 1000).toLong()
            val wait = NativePreparation(token, next, track.id, position, playRequested)
            preparation = wait
            if (player.currentMediaItem?.mediaId != track.id || player.playbackState in setOf(Player.STATE_IDLE, Player.STATE_ENDED)
                || player.playerError != null) {
                val metadata = MediaMetadata.Builder().setTitle(track.name)
                    .setArtist(track.artists.joinToString(", ") { it.name }).setAlbumTitle(track.albumName).build()
                val uri = Uri.Builder().scheme("spofify").authority("audio").appendPath(track.id).build()
                // IDs are opaque: a suffix such as .mpd must not select a manifest media source.
                player.setMediaItem(MediaItem.Builder().setMediaId(track.id).setUri(uri)
                    .setMimeType("application/octet-stream").setMediaMetadata(metadata).build(), position)
                player.prepare()
            } else player.seekTo(position)
            if (!wait.matches(generation, binding, player.currentMediaItem?.mediaId)) return AudioResult.REJECTED
            wait.outcome(player.playbackState, player.playerError != null, player.duration)?.let(wait.result::complete)
            val outcome = wait.result.await()
            if (token != generation || binding != next) return AudioResult.REJECTED
            if (outcome != AudioResult.OK) pause()
            return outcome
        } catch (cancelled: CancellationException) {
            if (binding == next) pause()
            throw cancelled
        } catch (_: Exception) {
            if (binding == next) pause()
            return AudioResult.MEDIA
        } finally {
            if (token == generation) preparation = null
        }
    }

    override fun pause() {
        checkThread()
        generation++
        playbackAuthority = null
        traversalBinding = null
        val pending = preparation
        preparation = null
        pending?.result?.cancel()
        player.pause()
    }

    /** Service reattachment cannot renew or manufacture the Core-owned execution lease. */
    fun hasPlaybackAuthority(): Boolean {
        if (released) return false
        checkThread()
        return playbackAuthority?.invoke() == true
    }

    override fun facts(): AudioFacts {
        checkThread()
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.div(1000.0) ?: 0.0
        return AudioFacts(binding = binding, elapsed = player.currentPosition.coerceAtLeast(0) / 1000.0,
            duration = duration, isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING, isLoading = player.isLoading,
            error = if (blocked) "blocked" else if (player.playerError != null) "media" else "",
            ended = binding != null && endedBinding == binding)
    }

    /** The service calls this only after an actual platform background-start refusal. */
    fun backgroundStartBlocked() {
        pause()
        blocked = true
        binding?.let { mutableObservations.tryEmit(AudioObservation(it, facts())) }
    }

    override fun close() {
        if (released) return
        pause()
        released = true
        player.removeListener(listener)
        player.release()
    }

    private fun checkThread() {
        check(!released && Looper.myLooper() == player.applicationLooper)
    }
}
