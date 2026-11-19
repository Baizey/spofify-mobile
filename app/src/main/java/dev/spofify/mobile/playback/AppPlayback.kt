package dev.spofify.mobile.playback

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.spofify.mobile.core.playback.*

/** One controller lifetime, never a lookup of the *next* controller. An old gesture cannot migrate. */
internal sealed class AppPlayback(private val current: () -> Boolean) {
    class Online(val controller: AccountChannel, current: () -> Boolean) : AppPlayback(current)
    class Local(val controller: LocalPlaybackController, current: () -> Boolean) : AppPlayback(current)

    val presentation: PlayerPresentation get() = when (this) {
        is Online -> PlayerPresentation.Online(controller.state.value)
        is Local -> PlayerPresentation.Local(controller.state.value)
    }

    @Composable fun observe(): PlayerPresentation = when (this) {
        is Online -> PlayerPresentation.Online(controller.state.collectAsStateWithLifecycle().value)
        is Local -> PlayerPresentation.Local(controller.state.collectAsStateWithLifecycle().value)
    }

    private inline fun <R> dispatch(online: AccountChannel.() -> R, local: LocalPlaybackController.() -> R): R? =
        if (!current()) null else when (this) {
            is Online -> controller.online()
            is Local -> controller.local()
        }

    fun pause() { dispatch({ pause() }, { pause() }) }
    fun resume() { dispatch({ resume() }, { resume() }) }
    fun toggle() { dispatch({ toggle() }, { toggle() }) }
    fun next() { dispatch({ next() }, { next() }) }
    fun previous() { dispatch({ previous() }, { previous() }) }
    fun seek(seconds: Double) { dispatch({ seek(seconds) }, { seek(seconds) }) }
    fun jump(key: Long) { dispatch({ jump(key) }, { jump(key) }) }
    fun remove(key: Long) { dispatch({ remove(key) }, { remove(key) }) }
    fun moveUpcoming(key: Long, after: Long?) { dispatch({ moveUpcoming(key, after) }, { moveUpcoming(key, after) }) }
    fun playTrack(track: PlaybackTrackReference, context: PlaybackContext? = null): Boolean =
        dispatch({ playTrack(track, context) }, { playTrack(track, context) }) == true
    fun playCollection(context: PlaybackContext): Boolean =
        dispatch({ playCollection(context) }, { playCollection(context) }) == true
    fun queueCollection(context: PlaybackContext, next: Boolean = false): Boolean =
        dispatch({ queueCollection(context, next) }, { queueCollection(context, next) }) == true
    fun queueTrack(track: PlaybackTrackReference, context: PlaybackContext? = null, next: Boolean = false): Boolean =
        dispatch({ queueTrack(track, context, next) }, { queueTrack(track, context, next) }) == true
    fun toggleShuffle() { dispatch({ toggleShuffle() }, { toggleShuffle() }) }
    fun cycleRepeat() { dispatch({ cycleRepeat() }, { cycleRepeat() }) }
    fun setShuffle(enabled: Boolean) { dispatch({ setShuffle(enabled) }, { setShuffle(enabled) }) }
    fun setRepeat(mode: String) { dispatch({ setRepeat(mode) }, { setRepeat(mode) }) }
}

/** Project sound separately from queue intent. Only the online variant has protocol state. */
internal sealed class PlayerPresentation {
    data class Online(val view: PlaybackView) : PlayerPresentation() {
        override val queue get() = view.snapshot?.state
        private val actual get() = view.playback?.takeIf { it.hasReportedPlayback }
        override val elapsed get() = actual?.elapsed ?: 0.0
        override val duration get() = actual?.duration ?: 0.0
        override val isPlaying get() = view.isPlaying
        override val ready get() = view.connection == PlaybackConnectionState.READY
    }
    data class Local(override val queue: PlaybackQueueState) : PlayerPresentation() {
        override val elapsed get() = queue.elapsed
        override val duration get() = queue.duration
        override val isPlaying get() = queue.isPlaying
        override val ready get() = true
    }
    val online: PlaybackView? get() = (this as? Online)?.view
    val error: String? get() = when (this) {
        is Online -> view.error
        is Local -> queue.error.takeIf { it.isNotEmpty() }
    }
    abstract val queue: PlaybackQueueState?
    abstract val elapsed: Double
    abstract val duration: Double
    abstract val isPlaying: Boolean
    abstract val ready: Boolean
}
