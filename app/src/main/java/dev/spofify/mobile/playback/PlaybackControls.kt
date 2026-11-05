package dev.spofify.mobile.playback

import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.SpofifyClient
import kotlinx.coroutines.flow.StateFlow

/** UI-facing transport boundary; the implementation delegates lifetime to Media3. */
interface PlaybackControls {
    val state: StateFlow<PlaybackState>
    fun connect()
    fun release()
    /** Capture an immutable host-origin fence for deliberate in-app Play/Resume at gesture
     * time. No screen ownership of audio; existing controls need not implement this hook. */
    fun bindExplicitPlayOrigin(capture: () -> (() -> Boolean)) {}
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
    /** Explicit originating selection. The predicate survives forwarding/binding/admission;
     * it is not a screen-attachment flag or permission to replay after reconnect. Legacy
     * synchronous controls remain source-compatible and check it before delegating. */
    fun playPreparedGuarded(queue: dev.spofify.mobile.core.PlaybackQueue, client: SpofifyClient,
        startTrackId: String? = null, shuffle: Boolean = false, startEntryId: String? = null,
        context: LibraryItem? = null, isCurrent: () -> Boolean, admissionIsCurrent: () -> Boolean = isCurrent) {
        if (admissionIsCurrent() && isCurrent()) playPrepared(queue, client, startTrackId, shuffle, startEntryId, context)
    }
    /** Captured expectedKey must precede asynchronous preparation. Unknown controls fail closed. */
    fun performPreparedAction(queue: dev.spofify.mobile.core.PlaybackQueue, client: SpofifyClient,
        action: PlaybackListAction, expectedKey: PlaybackInteractionKey?, startTrackId: String? = null,
        startEntryId: String? = null, context: LibraryItem? = null, isCurrent: () -> Boolean = { true },
        onResult: (PlaybackActionResult) -> Unit) {
        onResult(PlaybackActionResult(PlaybackActionStatus.UNSUPPORTED))
    }
    /** Contextual selection of one existing upcoming occurrence; never a catalog duplicate. */
    fun contextualOccurrence(expectedKey: PlaybackInteractionKey, occurrenceId: String,
        isCurrent: () -> Boolean = { true }, onResult: (PlaybackActionResult) -> Unit = {}) {
        onResult(PlaybackActionResult(PlaybackActionStatus.UNSUPPORTED))
    }
    /** Explicit promotion of one exact upcoming occurrence, not an arbitrary reorder of the
     * entire future. Capture expectedKey before dispatch; caller guard belongs to the owning
     * queue/sheet, not a lazy row that can disappear because its own action selected it.
     */
    fun playNextOccurrence(expectedKey: PlaybackInteractionKey, occurrenceId: String,
        isCurrent: () -> Boolean = { true }, onResult: (PlaybackActionResult) -> Unit = {}) {
        onResult(PlaybackActionResult(PlaybackActionStatus.UNSUPPORTED))
    }
    /** Prepared items only: fetching a collection remains an explicit host action. Never autoplays. */
    fun enqueue(items: List<LibraryItem>, client: SpofifyClient, playNext: Boolean = false) {}
    fun removeUpcoming(occurrenceId: String) {}
    /** Zero-based index within the displayed upcoming list. Manual moves exit shuffle. */
    fun moveUpcoming(occurrenceId: String, toIndex: Int) {}
    /** Guarded at serialized service/coordinator dispatch. Local: arbitrary upcoming index.
     * Remote: one up/down/Play-next command only; never an absolute drag or a step chain.
     * Check state.canMoveUpcomingAbsolutely before enabling drag. Capture expectedKey at
     * gesture start; preview, cancel and same-index drops do not edit. No legacy fallback. */
    fun moveUpcoming(expectedKey: PlaybackInteractionKey, occurrenceId: String, toIndex: Int) {}
    fun clearUpcoming() {}
    fun undoQueueRemoval() {}
    fun selectOccurrence(occurrenceId: String) {}
    /** Optional display identity, not authority to fetch or play a context. */
    fun setContext(context: LibraryItem?) {}
    fun toggle()
    fun next()
    fun previous()
    /** Prepared-only transport against PlaybackState.transportInteractionKey captured at interaction start. False means
     * unavailable/stale (including unsupported implementations); never fall back to no-arg calls.
     * True admits one guarded submission, not confirmation of remote playback or audible start.
     * UI calls run on main; implementations may conservatively reject other threads. */
    fun next(expectedKey: PlaybackInteractionKey): Boolean = false
    fun previous(expectedKey: PlaybackInteractionKey): Boolean = false
    fun select(index: Int)
    fun seekTo(positionMs: Long)
    fun toggleShuffle()
    fun cycleRepeat()
    fun stop()
}
