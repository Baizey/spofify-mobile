package dev.spofify.mobile.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.spofify.mobile.core.playback.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One command target for the account lifetime, regardless of socket or playback mode. */
internal class AppPlayback(val controller: AccountChannel,
    private val localIndexReady: StateFlow<Boolean> = ImmediateIndex, private val current: () -> Boolean) {
    val presentation get() = PlayerPresentation(controller.state.value, localIndexReady.value)

    @Composable fun observe(): PlayerPresentation = observeState().value

    @Composable fun observeState(): State<PlayerPresentation> = key(this) {
        val view = controller.state.collectAsStateWithLifecycle()
        val index = localIndexReady.collectAsStateWithLifecycle()
        remember(view, index) { derivedStateOf(structuralEqualityPolicy()) { PlayerPresentation(view.value, index.value) } }
    }

    private companion object { val ImmediateIndex: StateFlow<Boolean> = MutableStateFlow(true) }

    private inline fun <R> dispatch(command: AccountChannel.() -> R): R? = if (current()) controller.command() else null

    fun pause() { dispatch { pause() } }
    fun resume() { dispatch { resume() } }
    fun toggle() { dispatch { toggle() } }
    fun next() { dispatch { next() } }
    fun previous() { dispatch { previous() } }
    fun seek(seconds: Double) { dispatch { seek(seconds) } }
    fun seek(expected: PlaybackGestureContext, seconds: Double) {
        if (presentation.gestureContext == expected && presentation.ready) seek(seconds)
    }
    fun jump(key: Long) { dispatch { jump(key) } }
    fun remove(key: Long) { dispatch { remove(key) } }
    fun moveUpcoming(key: Long, after: Long?) { dispatch { moveUpcoming(key, after) } }
    fun playTrack(track: PlaybackTrackReference, context: PlaybackContext? = null): Boolean =
        presentation.canLoadTrack && dispatch { playTrack(track, context) } == true
    fun playCollection(context: PlaybackContext): Boolean = presentation.canLoadTrack && dispatch { playCollection(context) } == true
    fun queueCollection(context: PlaybackContext, next: Boolean = false): Boolean =
        presentation.canLoadTrack && dispatch { queueCollection(context, next) } == true
    fun queueTrack(track: PlaybackTrackReference, context: PlaybackContext? = null, next: Boolean = false): Boolean =
        presentation.canLoadTrack && dispatch { queueTrack(track, context, next) } == true
    fun toggleShuffle() { dispatch { toggleShuffle() } }
    fun cycleRepeat() { dispatch { cycleRepeat() } }
    fun setShuffle(enabled: Boolean) { dispatch { setShuffle(enabled) } }
    fun setRepeat(mode: String) { dispatch { setRepeat(mode) } }
}

/** Read a stable projection in the scope that renders it, not in the owning screen. */
@Composable
internal fun <T, R> State<T>.project(select: (T) -> R): State<R> = remember(this, select) {
    derivedStateOf(structuralEqualityPolicy()) { select(value) }
}

internal data class PlaybackGestureContext(
    val session: String, val connection: String, val generation: Long?, val offline: Boolean,
    val localOutput: Boolean, val occurrence: String?, val key: Long?,
)

/** Queue intent and accepted observations are independent; intent never proves sound. */
internal data class PlayerPresentation(val view: PlaybackView, private val localIndexReady: Boolean = true) {
    val queue get() = view.snapshot?.state
    private val actual get() = view.playback?.takeIf {
        ready && it.hasReportedPlayback && it.outputVersion == view.snapshot?.outputVersion && it.eventId == queue?.occurrence?.eventId
    }
    val duration get() = (actual?.duration?.takeIf { it > 0.0 } ?: queue?.current?.duration ?: 0.0).coerceAtLeast(0.0)
    val elapsed get() = (actual?.elapsed ?: queue?.elapsed ?: 0.0).coerceIn(0.0, duration)
    val isPlaying get() = actual?.isPlaying == true
    val wantsPause get() = ready && view.wantsPause
    val onlineReady get() = !view.offline && !view.superseded && view.connection == PlaybackConnectionState.READY
    val ready get() = !view.superseded && (onlineReady || view.offline && view.localOutput)
    // A transport operation on an existing queue doesn't need to resolve a catalog row.
    val canLoadTrack get() = ready && (!view.offline || localIndexReady)
    val error get() = playbackFailureNotice(view.playback?.error)
    val gestureContext get() = PlaybackGestureContext(view.sessionId, view.connectionId, view.snapshot?.outputVersion,
        view.offline, view.localOutput, queue?.occurrence?.eventId, queue?.current?.key)
}
