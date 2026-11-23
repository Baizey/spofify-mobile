package dev.spofify.mobile.playback

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.util.Log
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dev.spofify.mobile.core.auth.AccountIdentity
import dev.spofify.mobile.core.auth.OnlineGrant
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
    private val context: Context,
    private val sessions: SessionRepository,
    private val identity: AccountIdentity,
    private val cache: AutomaticMusicCache,
    private val onSystemPause: () -> Unit,
    private val requestPlaybackStart: suspend () -> Boolean,
    private val canContinuePlayback: () -> Boolean,
    private val onPlayerChanged: (ExoPlayer) -> Unit = {},
) : AudioExecutor, Closeable {
    private var audioLoadScope: AudioLoadScope? = null
    private val attrs = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
    private inner class Deck(focus: Boolean) {
        @Volatile var source: AudioSourceOwner? = null
        var sourceGrant: OnlineGrant? = null
        var localSource = false
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(SessionMusicSourceFactory(sessions, identity, cache) { source }
                .setLoadErrorHandlingPolicy(AudioLoadRecovery()))
            .setAudioAttributes(attrs, focus).setHandleAudioBecomingNoisy(focus)
            .setWakeMode(C.WAKE_MODE_LOCAL).build()
    }
    private var primary = Deck(true)
    private var source: AudioSourceOwner?
        get() = primary.source
        set(value) { primary.source = value }
    val player: ExoPlayer get() = primary.player
    private val mutableObservations = MutableSharedFlow<AudioObservation>(
        extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val observations = mutableObservations.asSharedFlow()
    private var binding: AudioBinding? = null
    private var generation = 0L
    private var preparation: NativePreparation? = null
    private var admittingStart: Long? = null
    private var confirmingStart: Long? = null
    private var endedBinding: AudioBinding? = null
    // A late EOF after stopped setup is not a new traversal, even after its readiness waiter completes.
    private var traversalBinding: AudioBinding? = null
    private var terminalError = ""
    private var released = false
    private var playbackAuthority: (() -> Boolean)? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (released || player !== this@Media3AudioExecutor.player) return
            if ((!player.isPlaying && player.playbackState != Player.STATE_ENDED || !player.playWhenReady ||
                    player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) &&
                preview?.deck?.player?.playWhenReady == true) discardPreview()
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
            if (released || binding == null || !hasPlaybackAuthority() || player.playWhenReady) return
            if (!playWhenReady && (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY ||
                    reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS && confirmingStart != generation)) onSystemPause()
        }
    }

    init { player.addListener(listener) }

    override suspend fun prepare(binding: AudioBinding, track: PlaybackTrack?, elapsed: Double): AudioResult =
        load(binding, track, elapsed)

    override suspend fun execute(
        binding: AudioBinding, track: PlaybackTrack?, elapsed: Double,
        desiredPlaying: Boolean, isAuthorized: () -> Boolean,
    ): AudioResult {
        return executeOn(binding, track, elapsed, desiredPlaying, false, isAuthorized)
    }

    /** Choose the source for new work, without invalidating the currently prepared media. */
    fun port(localOnly: () -> Boolean, current: () -> Boolean): AudioExecutor = object : AudioExecutor {
        override val observations get() = this@Media3AudioExecutor.observations
        override fun facts() = this@Media3AudioExecutor.facts()
        override suspend fun prepare(binding: AudioBinding, track: PlaybackTrack?, elapsed: Double) =
            if (!current()) AudioResult.REJECTED else load(binding, track, elapsed, localOnly = localOnly(), isAuthorized = current)
        override suspend fun execute(binding: AudioBinding, track: PlaybackTrack?, elapsed: Double,
            desiredPlaying: Boolean, isAuthorized: () -> Boolean) =
            executeOn(binding, track, elapsed, desiredPlaying, localOnly()) { current() && isAuthorized() }
        override fun pause() { if (current()) this@Media3AudioExecutor.pause() }
        override fun armCrossfade(binding: AudioBinding, successor: () -> PlaybackTrack?) {
            if (current() && this@Media3AudioExecutor.binding == binding) arm(localOnly, successor)
        }
        override fun recheckCrossfade() {
            if (current() && preview?.let { it.row.key != successor?.invoke()?.key } == true) discardPreview()
        }
        override fun promoteCrossfade(from: AudioBinding, next: AudioBinding, track: PlaybackTrack, authorized: () -> Boolean) =
            current() && promote(from, next, track) { current() && authorized() }
    }

    private suspend fun executeOn(binding: AudioBinding, track: PlaybackTrack?, elapsed: Double,
        desiredPlaying: Boolean, localOnly: Boolean, isAuthorized: () -> Boolean): AudioResult {
        checkThread()
        if (!isAuthorized()) return AudioResult.REJECTED
        val result = load(binding, track, elapsed, playRequested = desiredPlaying, localOnly = localOnly, isAuthorized = isAuthorized)
        if (result != AudioResult.OK) return result
        if (this.binding != binding) return AudioResult.REJECTED
        if (!isAuthorized()) { pause(retireMedia = true); return AudioResult.REJECTED }
        // A known terminal position cannot start sound or justify foreground admission.
        if (track != null && desiredPlaying && player.playbackState != Player.STATE_ENDED) {
            val nativeGeneration = generation
            playbackAuthority = {
                !released && this.binding == binding && generation == nativeGeneration && isAuthorized()
            }
            admittingStart = nativeGeneration
            val admitted = try {
                requestPlaybackStart()
            } catch (cancelled: CancellationException) {
                if (!released && generation == nativeGeneration) pause(retireMedia = true)
                throw cancelled
            } finally {
                if (admittingStart == nativeGeneration) admittingStart = null
            }
            // Admission may suspend while Media3 connects and publishes the notification. A pause,
            // newer execute or retired grant must not be turned into a blocked successor or sound.
            val current = { !released && this.binding == binding && generation == nativeGeneration }
            if (!current()) return AudioResult.REJECTED
            if (!hasPlaybackAuthority()) { pause(retireMedia = true); return AudioResult.REJECTED }
            if (player.playerError != null || player.playbackState == Player.STATE_IDLE) return stopFailedStart(AudioResult.MEDIA, "admission-player")
            if (player.playbackState == Player.STATE_ENDED) {
                mutableObservations.tryEmit(AudioObservation(binding, facts()))
                return AudioResult.OK
            }
            if (!admitted || !canContinuePlayback()) return stopFailedStart(AudioResult.BLOCKED, "admission")
            val positionBeforePlay = player.currentPosition
            confirmingStart = nativeGeneration
            val start = try {
                audioLoadScope?.playing = true
                player.play()
                awaitNativePlaybackStart({ current() && hasPlaybackAuthority() }) {
                    when {
                        player.playerError != null || player.playbackState == Player.STATE_IDLE -> NativeStartStatus.FAILED
                        endedBinding == binding -> NativeStartStatus.ENDED
                        !player.playWhenReady -> NativeStartStatus.REFUSED
                        player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE -> NativeStartStatus.SUPPRESSED
                        player.isPlaying && player.currentPosition > positionBeforePlay -> NativeStartStatus.ADVANCING
                        else -> NativeStartStatus.WAITING
                    }
                }
            } catch (cancelled: CancellationException) {
                if (current()) pause()
                throw cancelled
            } finally {
                if (confirmingStart == nativeGeneration) confirmingStart = null
            }
            if (!current()) return AudioResult.REJECTED
            if (start == AudioResult.REJECTED || !hasPlaybackAuthority()) {
                pause(retireMedia = true)
                return AudioResult.REJECTED
            }
            if (start != AudioResult.OK) return stopFailedStart(start, "native-start")
        }
        mutableObservations.tryEmit(AudioObservation(binding, facts()))
        return AudioResult.OK
    }

    private suspend fun load(
        next: AudioBinding, track: PlaybackTrack?, elapsed: Double,
        playRequested: Boolean = false, localOnly: Boolean = false, isAuthorized: () -> Boolean = { true },
    ): AudioResult {
        checkThread()
        pause()
        binding = next
        traversalBinding = next.takeIf { playRequested }
        endedBinding = null
        terminalError = ""
        val token = generation
        try {
            if (track == null) {
                retireSource()
                player.stop()
                player.clearMediaItems()
                return AudioResult.OK
            }
            require(elapsed.isFinite() && elapsed >= 0)
            val owner = AudioLoadScope(track.id, authorized = isAuthorized)
            audioLoadScope = owner
            val position = (elapsed * 1000).toLong()
            val wait = NativePreparation(token, next, track.id, position, playRequested)
            preparation = wait
            val replaceNetwork = source?.usingNetwork == true &&
                (cache.hasStoredAudio(track.id) || primary.sourceGrant !== sessions.onlineGrant.value)
            // Local ranges also capture the previous operation's storage pin authority.
            if (localOnly || primary.localSource || replaceNetwork || player.currentMediaItem?.mediaId != track.id ||
                player.playbackState in setOf(Player.STATE_IDLE, Player.STATE_ENDED) ||
                player.playerError != null || source?.bind(owner) != true) {
                retireSource()
                val installed = AudioSourceOwner(token.toString(), track.id)
                check(installed.bind(owner))
                source = installed
                install(primary, track, installed, owner, localOnly, position)
            } else if (playRequested || elapsed != player.currentPosition / 1000.0) {
                // In-place stop: don't round-trip an already exact native millisecond through a seek.
                player.seekTo(position)
            }
            if (!wait.matches(generation, binding, player.currentMediaItem?.mediaId)) return AudioResult.REJECTED
            wait.outcome(player.playbackState, player.playerError != null, player.duration)?.let(wait.result::complete)
            val outcome = owner.awaitPreparation(wait.result)
            if (token != generation || binding != next) return AudioResult.REJECTED
            return if (outcome == AudioResult.MEDIA) stopFailedStart(outcome, "prepare") else outcome
        } catch (cancelled: CancellationException) {
            if (!released && token == generation) pause()
            throw cancelled
        } catch (_: Exception) {
            return if (!released && token == generation) stopFailedStart(AudioResult.MEDIA, "prepare-exception") else AudioResult.REJECTED
        } finally {
            if (token == generation) preparation = null
        }
    }

    private fun install(deck: Deck, track: PlaybackTrack, installed: AudioSourceOwner, owner: AudioLoadScope,
        localOnly: Boolean, position: Long) {
        val metadata = MediaMetadata.Builder().setTitle(track.name)
            .setArtist(track.artists.joinToString(", ") { it.name }).setAlbumTitle(track.albumName).build()
        val uri = Uri.Builder().scheme("spofify").authority("audio").appendPath(track.id).build()
        val item = MediaItem.Builder().setMediaId(track.id).setUri(uri).setCustomCacheKey(installed.key)
            .setMimeType("application/octet-stream").setMediaMetadata(metadata).build()
        deck.sourceGrant = if (localOnly) null else sessions.onlineGrant.value
        deck.localSource = localOnly
        if (localOnly) deck.player.setMediaSource(localMusicSource(item,
            LocalMusicRanges(installed, owner::isCurrent, cache::sourceChoice)), position)
        else deck.player.setMediaItem(item, position)
        deck.player.prepare()
    }

    // Only the primary deck can publish facts or acquire focus. The secondary shares its focus
    // and foreground lifetime, and cannot run beyond that binding except by explicit EOF promotion.
    private class Gate(private var authorized: () -> Boolean) {
        @Synchronized fun allows() = authorized()
        @Synchronized fun bind(next: () -> Boolean) { authorized = next }
    }
    private inner class Preview(val deck: Deck, val row: PlaybackTrack, val gate: Gate, val load: AudioLoadScope, val local: Boolean) {
        var span = 0L
    }
    private val handler = android.os.Handler(player.applicationLooper)
    private var enabled = false
    private var successor: (() -> PlaybackTrack?)? = null
    private var localPreview: () -> Boolean = { false }
    private var preview: Preview? = null
    private var attempted: Long? = null
    private val tick = Runnable { updateFade() }

    fun setCrossfadeEnabled(value: Boolean) {
        if (released || enabled == value) return
        enabled = value
        attempted = null
        handler.removeCallbacks(tick)
        if (!value) discardPreview() else if (successor != null) handler.post(tick)
    }

    private fun arm(local: () -> Boolean, resolve: () -> PlaybackTrack?) {
        disarm()
        localPreview = local
        successor = resolve
        if (enabled) handler.post(tick)
    }

    private fun disarm() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(tickTimeout)
        successor = null
        attempted = null
        discardPreview()
    }

    fun recheckSourceMode() {
        if (preview?.let { !it.local && localPreview() } == true) {
            // An isolated advance must use verified storage, even if a network-backed deck
            // was being prepared before the socket fell. Never disturb the primary period.
            discardPreview()
            attempted = null
        }
    }

    private fun discardPreview() {
        val p = preview ?: return
        preview = null
        p.gate.bind { false }
        p.deck.player.volume = 0f
        p.deck.player.pause()
        player.volume = 1f
        p.load.job.cancel()
        p.deck.source?.job?.cancel()
        p.deck.player.release()
    }

    private fun updateFade() {
        val resolve = successor ?: return
        if (!enabled || !hasPlaybackAuthority()) { disarm(); return }
        recheckSourceMode()
        val left = if (player.duration > 0) player.duration - player.currentPosition else Long.MAX_VALUE
        if (left > CrossfadeWindow.PREPARE_MS) {
            discardPreview()
            handler.postDelayed(tick, 500)
            return
        }
        val row = resolve()
        if (preview?.row?.key != row?.key) discardPreview()
        if (row == null || row.key != attempted) attempted = null
        val ended = player.playbackState == Player.STATE_ENDED
        if (!ended && !player.isPlaying) discardPreview()
        if (row != null && player.isPlaying && left > 500 && preview == null && attempted == null) {
            attempted = row.key
            val outgoingLoad = audioLoadScope ?: return
            val gate = Gate(outgoingLoad::isCurrent)
            val load = AudioLoadScope(row.id, authorized = gate::allows)
            try {
                val deck = Deck(false)
                val local = localPreview()
                preview = Preview(deck, row, gate, load, local)
                deck.player.volume = 0f
                val installed = AudioSourceOwner("fade-$generation-${row.key}", row.id)
                check(installed.bind(load))
                deck.source = installed
                install(deck, row, installed, load, local, 0)
            } catch (_: Exception) { discardPreview(); load.job.cancel() }
        }
        preview?.let { p ->
            val incoming = p.deck.player
            val span = CrossfadeWindow.length(player.duration, incoming.duration)
            when {
                incoming.playerError != null || incoming.playbackState == Player.STATE_ENDED -> discardPreview()
                ended -> { // Give the actual native end observation one looper turn to land.
                    handler.postDelayed(tickTimeout, 250)
                    return
                }
                p.span > 0 && (!incoming.isPlaying || !canContinuePlayback()) -> discardPreview()
                span >= 400 && left in 1..span && incoming.playbackState == Player.STATE_READY -> {
                    if (p.span == 0L) {
                        // Late preparation or a seek straight into the tail: ordinary EOF instead.
                        if (span - left > 300 || !canContinuePlayback()) discardPreview() else {
                            p.span = left
                            p.load.playing = true
                            incoming.play()
                            Log.i("SpofifyPlayer", "Crossfade overlap started: ${p.span} ms")
                        }
                    }
                    if (preview === p) {
                        val (outGain, inGain) = CrossfadeWindow.gains(left, p.span)
                        player.volume = outGain
                        incoming.volume = inGain
                    }
                }
            }
        }
        if (!ended) handler.postDelayed(tick, if (preview == null) 200 else 40)
    }
    private val tickTimeout = Runnable { if (player.playbackState == Player.STATE_ENDED) discardPreview() }

    private fun promote(from: AudioBinding, next: AudioBinding, row: PlaybackTrack, authorized: () -> Boolean): Boolean {
        val p = preview ?: return false
        if (!enabled || !authorized() || !p.local && localPreview() || endedBinding != from || binding != from || row.key != p.row.key ||
            row.id != p.row.id || p.span <= 0 || !p.deck.player.isPlaying ||
            !player.playWhenReady || player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE ||
            !canContinuePlayback()) return false
        p.gate.bind(authorized)
        preview = null
        handler.removeCallbacks(tickTimeout)
        disarm()
        val old = primary
        old.player.removeListener(listener)
        old.source?.job?.cancel()
        audioLoadScope?.job?.cancel()
        generation++
        primary = p.deck
        audioLoadScope = p.load
        binding = next
        traversalBinding = next
        endedBinding = null
        playbackAuthority = authorized
        // Mute across the focus handoff. Disable/release the old focus owner before the new
        // playback looper receives its ordered focus and gain commands.
        player.volume = 0f
        player.addListener(listener)
        onPlayerChanged(player)
        old.player.release()
        val continuePlaying = hasPlaybackAuthority() && canContinuePlayback()
        if (!continuePlaying) pause()
        player.setHandleAudioBecomingNoisy(true)
        player.setAudioAttributes(attrs, true)
        player.volume = 1f // If revoked, the preceding pause is processed before this gain command.
        if (!continuePlaying) return false
        Log.i("SpofifyPlayer", "Crossfade promoted at native EOF: ${player.currentPosition} ms")
        return true
    }

    private fun retireSource() {
        val old = source
        source = null
        old?.job?.cancel()
    }

    override fun pause() = pause(retireMedia = false)

    private fun pause(retireMedia: Boolean) {
        checkThread()
        val pending = preparation
        val starting = pending != null || admittingStart != null || confirmingStart != null
        // Revoke callbacks before any synchronous Player listener can sample the old start.
        preparation = null
        admittingStart = null
        confirmingStart = null
        playbackAuthority = null
        traversalBinding = null
        generation++
        player.pause()
        disarm()
        source?.unbind()
        audioLoadScope?.job?.cancel()
        audioLoadScope = null
        if (starting || retireMedia) {
            // Ordinary prepare I/O is tied to the installed source, not the retry scope.
            // Keep reusable buffers only after the operation has left startup.
            retireSource()
            player.stop()
        }
        pending?.result?.cancel()
    }

    /** Source handoff on the player looper; even an in-buffer seek cannot inherit retired bytes. */
    fun releaseOutput() {
        if (binding == null && source == null) return
        pause()
        binding = null
        endedBinding = null
        terminalError = ""
        retireSource()
        player.stop()
        player.clearMediaItems()
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
            duration = duration, isPlaying = player.isPlaying && confirmingStart != generation,
            isBuffering = player.playbackState == Player.STATE_BUFFERING, isLoading = player.isLoading,
            error = terminalError.ifEmpty { if (player.playerError != null) "media" else "" },
            ended = binding != null && endedBinding == binding,
            stoppedByRefusal = terminalError == "blocked" && playbackAuthority == null && !player.playWhenReady && !player.isPlaying)
    }

    private fun stopFailedStart(result: AudioResult, stage: String): AudioResult {
        Log.w("SpofifyPlayer", "Playback $stage failed: result=$result state=${player.playbackState} " +
            "playWhenReady=${player.playWhenReady} suppression=${player.playbackSuppressionReason} " +
            "mediaErrorCode=${player.playerError?.errorCode ?: 0}")
        pause(retireMedia = true)
        terminalError = if (result == AudioResult.BLOCKED) "blocked" else "media"
        return result
    }

    fun backgroundStartBlocked() {
        stopFailedStart(AudioResult.BLOCKED, "service-recovery")
        binding?.let { mutableObservations.tryEmit(AudioObservation(it, facts())) }
    }

    override fun close() {
        if (released) return
        pause()
        released = true
        retireSource()
        player.removeListener(listener)
        player.release()
    }

    private fun checkThread() {
        check(!released && Looper.myLooper() == player.applicationLooper)
    }
}
