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
    /** Already server-prepared musical intent. Production preserves the optional exact wire
     * envelope; the default keeps existing test/host implementations source-compatible. */
    fun playPrepared(queue: dev.spofify.mobile.core.PlaybackQueue, client: SpofifyClient,
        startTrackId: String? = null, shuffle: Boolean = false, startEntryId: String? = null,
        context: LibraryItem? = null) {
        play(queue.items, client, startTrackId, shuffle, startEntryId)
        setContext(queue.context?.let { LibraryItem(it.kind, it.id, it.name) } ?: context)
    }
    /** Prepared items only: fetching a collection remains an explicit host action. Never autoplays. */
    fun enqueue(items: List<LibraryItem>, client: SpofifyClient, playNext: Boolean = false) {}
    fun removeUpcoming(occurrenceId: String) {}
    /** Zero-based index within the displayed upcoming list. Manual moves exit shuffle. */
    fun moveUpcoming(occurrenceId: String, toIndex: Int) {}
    fun clearUpcoming() {}
    fun undoQueueRemoval() {}
    fun selectOccurrence(occurrenceId: String) {}
    /** Optional display identity, not authority to fetch or play a context. */
    fun setContext(context: LibraryItem?) {}
    fun toggle()
    fun next()
    fun previous()
    fun select(index: Int)
    fun seekTo(positionMs: Long)
    fun toggleShuffle()
    fun cycleRepeat()
    fun stop()
}
