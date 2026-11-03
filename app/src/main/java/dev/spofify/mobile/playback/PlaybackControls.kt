package dev.spofify.mobile.playback

import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.SpofifyClient
import kotlinx.coroutines.flow.StateFlow

/** UI-facing transport boundary; the implementation delegates lifetime to Media3. */
interface PlaybackControls {
    val state: StateFlow<PlaybackState>
    fun connect()
    fun release()
    fun play(items: List<LibraryItem>, client: SpofifyClient, startTrackId: String? = null,
        shuffle: Boolean = false, startEntryId: String? = null)
    fun toggle()
    fun next()
    fun previous()
    fun select(index: Int)
    fun seekTo(positionMs: Long)
    fun toggleShuffle()
    fun cycleRepeat()
    fun stop()
}
