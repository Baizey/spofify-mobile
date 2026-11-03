package dev.spofify.mobile.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dev.spofify.mobile.SpofifyApplication
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.SpofifyClient
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A UI snapshot; the service is the authority, including after activity recreation. */
data class PlaybackState(
    val item: LibraryItem? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val error: String? = null,
    val queue: List<LibraryItem> = emptyList(),
    val queueIndex: Int = -1,
)

/**
 * An application-context-only UI connection. All calls are marshalled to main, and
 * [release] only releases this controller, NEVER the service/player. Call [stop]
 * before replacing graph.client on logout or server change.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackConnection(context: Context) : PlaybackControls {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> handler.post(command); Unit }
    private val mutableState = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var generation = 0
    private var pendingPlay: PlayRequest? = null
    private var releaseAfterPending = false
    private var applyingPlay = false
    private var releaseTimeoutScheduled = false
    private val releaseTimeout = Runnable {
        releaseTimeoutScheduled = false
        if (releaseAfterPending) releaseInternal()
    }
    private var connectionError: String? = null
    private var queueSnapshot: List<LibraryItem> = emptyList()

    private data class PlayRequest(
        val items: List<MediaItem>,
        val startIndex: Int,
        val shuffle: Boolean,
        val client: SpofifyClient,
        val epoch: Long,
    )

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish(refreshQueue = events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                events.contains(Player.EVENT_MEDIA_METADATA_CHANGED))
            maybeReleaseAfterPending()
        }
    }
    private val pollPosition = object : Runnable {
        override fun run() {
            if (controller == null) return
            publish()
            handler.postDelayed(this, 250)
        }
    }

    /** Builds a MediaController asynchronously; safe to call more than once. */
    override fun connect() = onMain {
        cancelDeferredRelease()
        connectInternal()
    }

    private fun cancelDeferredRelease() {
        releaseAfterPending = false
        handler.removeCallbacks(releaseTimeout)
        releaseTimeoutScheduled = false
    }

    private fun connectInternal() {
        if (controller != null || controllerFuture != null) return
        connectionError = null
        val currentGeneration = ++generation
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    if (this@PlaybackConnection.controller !== controller) return
                    handler.removeCallbacks(pollPosition)
                    handler.removeCallbacks(releaseTimeout)
                    releaseTimeoutScheduled = false
                    this@PlaybackConnection.controller = null
                    controllerFuture = null
                    controller.removeListener(listener)
                    controller.release()
                    connectionError = "Playback stopped. Sign in or reconnect to try again."
                    mutableState.value = mutableState.value.copy(
                        isPlaying = false, isBuffering = false, error = connectionError,
                    )
                }
            })
            .buildAsync()
        controllerFuture = future
        future.addListener({
            if (currentGeneration != generation) return@addListener
            controllerFuture = null
            try {
                val connected = future.get()
                controller = connected
                connected.addListener(listener)
                pendingPlay?.let { request ->
                    pendingPlay = null
                    applyPlay(connected, request)
                }
                publish(refreshQueue = true)
                handler.removeCallbacks(pollPosition)
                handler.post(pollPosition)
                maybeReleaseAfterPending()
            } catch (_: Exception) {
                connectionError = "Unable to connect to playback. Sign in and try again."
                mutableState.value = mutableState.value.copy(
                    isPlaying = false, isBuffering = false, error = connectionError,
                )
                // Keep the latest explicit play request for a subsequent connect/play.
                if (releaseAfterPending) releaseInternal()
            }
        }, mainExecutor)
    }

    override fun release() = onMain {
        if ((pendingPlay != null && controller == null && controllerFuture != null) ||
            (controller?.playbackState == Player.STATE_BUFFERING && controller?.playWhenReady == true)) {
            // Do not tear down the binding between an explicit tap and its delivery.
            // Keep only this application-context controller until the service settles.
            releaseAfterPending = true
            maybeReleaseAfterPending()
        } else {
            releaseInternal()
        }
    }

    private fun maybeReleaseAfterPending() {
        if (!releaseAfterPending || applyingPlay || pendingPlay != null) return
        val connected = controller ?: return
        if (connected.playbackState == Player.STATE_READY ||
            connected.playbackState == Player.STATE_ENDED || connected.playerError != null) {
            releaseInternal()
        } else if (!releaseTimeoutScheduled) {
            // Even a very slow network must not retain a detached UI connection forever.
            // Releasing here still never stops service-owned buffering/playback.
            releaseTimeoutScheduled = true
            handler.postDelayed(releaseTimeout, 30_000)
        }
    }

    private fun releaseInternal() {
        ++generation
        handler.removeCallbacks(pollPosition)
        handler.removeCallbacks(releaseTimeout)
        releaseTimeoutScheduled = false
        val connected = controller
        controller = null
        connected?.let {
            it.removeListener(listener)
            it.release()
        }
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        releaseAfterPending = false
    }

    /** Latest explicit play wins if multiple taps arrive while connecting. */
    override fun play(
        items: List<LibraryItem>,
        client: SpofifyClient,
        startTrackId: String?,
        shuffle: Boolean,
        startEntryId: String?,
    ) = onMain {
        val tracks = items.filter { it.kind == "track" && it.playable }
        if (tracks.isEmpty()) {
            mutableState.value = mutableState.value.copy(error = "No playable tracks in this queue.")
            return@onMain
        }
        val request = try {
            PlayRequest(
                tracks.map { PlaybackMedia.create(it, client) },
                playbackStartIndex(tracks, startTrackId, startEntryId),
                shuffle,
                client,
                PlaybackRequests.epoch,
            )
        } catch (_: IllegalArgumentException) {
            mutableState.value = mutableState.value.copy(error = "This queue contains an invalid track.")
            return@onMain
        }
        connectionError = null
        cancelDeferredRelease()
        val connected = controller
        if (connected != null) {
            applyPlay(connected, request)
        } else {
            pendingPlay = request
            mutableState.value = PlaybackState(
                item = tracks[request.startIndex], queue = tracks, queueIndex = request.startIndex,
                isBuffering = true, shuffle = shuffle,
            )
            connectInternal()
        }
    }

    private fun applyPlay(connected: MediaController, request: PlayRequest) {
        // A rotated-out connection can finish binding AFTER a different connection
        // stopped playback or replaced the account. Never replay its stale tap.
        if (request.epoch != PlaybackRequests.epoch ||
            request.client !== (context as SpofifyApplication).graph.client ||
            request.client.currentSession() == null) {
            connectionError = "Playback request cancelled after the session changed."
            publish(refreshQueue = true)
            if (releaseAfterPending) releaseInternal()
            return
        }
        connectionError = null
        applyingPlay = true
        try {
            connected.shuffleModeEnabled = request.shuffle
            connected.setMediaItems(request.items, request.startIndex, 0L)
            connected.prepare()
            connected.play()
        } finally {
            applyingPlay = false
        }
        publish(refreshQueue = true)
        maybeReleaseAfterPending()
    }

    override fun toggle() = withController { connected ->
        if (connected.isPlaying || (connected.playWhenReady &&
                connected.playbackState != Player.STATE_ENDED && connected.playerError == null)) {
            connected.pause()
        } else if (connected.mediaItemCount > 0) {
            if (connected.playbackState == Player.STATE_ENDED) connected.seekToDefaultPosition()
            if (connected.playbackState == Player.STATE_IDLE || connected.playerError != null) connected.prepare()
            connected.play()
        }
    }

    override fun next() = withController { connected ->
        if (connected.hasNextMediaItem()) {
            val failed = connected.playerError != null
            connected.seekToNextMediaItem()
            if (failed) { connected.prepare(); connected.play() }
        }
    }

    override fun previous() = withController { connected ->
        val failed = connected.playerError != null
        connected.seekToPrevious()
        if (failed && connected.mediaItemCount > 0) { connected.prepare(); connected.play() }
    }

    override fun select(index: Int) = withController { connected ->
        if (index in 0 until connected.mediaItemCount) {
            connected.seekToDefaultPosition(index)
            if (connected.playbackState == Player.STATE_IDLE || connected.playerError != null) connected.prepare()
            connected.play()
        }
    }

    override fun seekTo(positionMs: Long) = withController { connected ->
        if (connected.mediaItemCount > 0) {
            val duration = connected.duration
            val target = if (duration != C.TIME_UNSET && duration >= 0) {
                positionMs.coerceIn(0, duration)
            } else positionMs.coerceAtLeast(0)
            connected.seekTo(target)
        }
    }

    override fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    override fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** Explicit logout/server-switch stop, unlike release(). Works before connect completes. */
    override fun stop() {
        // Invalidate synchronously, even if called by an OkHttp auth callback off-main.
        // Every connection checks this epoch before applying a deferred request.
        PlaybackRequests.invalidate()
        onMain {
            pendingPlay = null
            controller?.let { it.stop(); it.clearMediaItems() }
            releaseInternal()
            PlaybackService.stopActivePlayback()
            context.stopService(Intent(context, PlaybackService::class.java))
            connectionError = null
            queueSnapshot = emptyList()
            mutableState.value = PlaybackState()
        }
    }

    private fun withController(action: (MediaController) -> Unit) = onMain {
        val connected = controller ?: return@onMain
        action(connected)
        publish()
    }

    private fun publish(refreshQueue: Boolean = false) {
        val connected = controller ?: return
        if (refreshQueue) {
            queueSnapshot = (0 until connected.mediaItemCount).mapNotNull {
                PlaybackMedia.libraryItem(connected.getMediaItemAt(it))
            }
        }
        val duration = connected.duration
        val currentItem = connected.currentMediaItem?.let(PlaybackMedia::libraryItem)
        mutableState.value = PlaybackState(
            item = currentItem,
            isPlaying = connected.isPlaying,
            isBuffering = connected.playbackState == Player.STATE_BUFFERING,
            positionMs = connected.currentPosition.coerceAtLeast(0),
            durationMs = if (duration == C.TIME_UNSET) currentItem?.durationMs ?: 0 else duration.coerceAtLeast(0),
            shuffle = connected.shuffleModeEnabled,
            repeatMode = connected.repeatMode,
            error = connected.playerError?.let(::playbackErrorMessage) ?: connectionError,
            queue = queueSnapshot,
            queueIndex = if (queueSnapshot.isEmpty()) -1 else connected.currentMediaItemIndex,
        )
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post { action() }
    }
}
