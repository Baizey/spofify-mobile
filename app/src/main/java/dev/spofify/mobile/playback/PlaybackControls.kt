package dev.spofify.mobile.playback

import dev.spofify.mobile.feature.devices.LibraryPlaybackActionBinding

import dev.spofify.mobile.OfflineEligibility
import dev.spofify.mobile.OfflineMediaReference
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.QueueIntentIntent
import dev.spofify.mobile.core.SpofifyClient
import kotlinx.coroutines.flow.StateFlow

/** UI controls: server-owned online selections and separately guarded saved-media playback. */
interface PlaybackControls {
    val state: StateFlow<PlaybackState>
    /** Presentation acknowledgement only; never mutates the musical queue. */
    fun consumeFeedback(id: Long) {}
    fun connect()
    fun release()
    /** Presentation/request retirement after the graph's synchronous mode/epoch boundary. */
    fun modeChanged() {}
    /** Capture an immutable host-origin fence for deliberate in-app Play/Resume at gesture
     * time. No screen ownership of audio; existing controls need not implement this hook. */
    fun bindExplicitPlayOrigin(capture: () -> (() -> Boolean)) {}
    /** Deliberate saved selection only. The entire list is used in order, including duplicate
     * entries; callers must explicitly choose Play available before passing a partial selection.
     * Eligibility is an incarnation fence, not a Session/receiver. Unknown controls fail closed. */
    fun playLocal(items: List<LibraryItem>, eligibility: OfflineEligibility,
        mediaReferences: Map<String, OfflineMediaReference>, startEntryId: String? = null, shuffle: Boolean = false, context: LibraryItem? = null,
        isCurrent: () -> Boolean = { true }) {}
    /** Verified local append/immediate-next against an existing local queue. Never starts. */
    fun enqueueLocal(items: List<LibraryItem>, eligibility: OfflineEligibility,
        mediaReferences: Map<String, OfflineMediaReference>, playNext: Boolean = false, isCurrent: () -> Boolean = { true }) {}
    /** Named local priorities; High is FIFO after pending High, Low appends, Next is immediate.
     * Never starts or admits a receiver. Unknown implementations fail closed. */
    fun enqueueLocal(items: List<LibraryItem>, eligibility: OfflineEligibility,
        mediaReferences: Map<String, OfflineMediaReference>, intent: QueueIntentIntent, isCurrent: () -> Boolean = { true }) {}
    /** One identifier-only server transaction. Capture binding before any await. */
    fun queueIntent(selection: dev.spofify.mobile.core.LibraryPlaybackSelection, client: SpofifyClient,
        action: PlaybackListAction, expectedKey: PlaybackInteractionKey?,
        expectedBinding: LibraryPlaybackActionBinding?, shuffle: Boolean? = null,
        isCurrent: () -> Boolean = { true }, onResult: (PlaybackActionResult) -> Unit) {
        onResult(PlaybackActionResult(PlaybackActionStatus.UNSUPPORTED))
    }
    /** Exact upcoming key selection. No duplicate, context refetch or positional jump. */
    fun queueIntentOccurrence(expectedKey: PlaybackInteractionKey, occurrenceId: String,
        action: PlaybackListAction, isCurrent: () -> Boolean = { true },
        onResult: (PlaybackActionResult) -> Unit = {}) {
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
    /** Exact rendered queue binding and owning presentation lifetime; unknown implementations fail closed. */
    fun removeUpcoming(expectedKey: PlaybackInteractionKey, occurrenceId: String, isCurrent: () -> Boolean) {}
    /** Guarded at service/coordinator dispatch. toIndex is zero-based in the captured upcoming
     * traversal. Online moves send one exact anchored command; saved local queues use their
     * own arbitrary upcoming index. Pass the originating row/host lifetime guard.
     * Check state.canMoveUpcomingAbsolutely before enabling drag. Capture expectedKey at
     * gesture start; preview, cancel and same-index drops do not edit. No legacy fallback. */
    fun moveUpcoming(expectedKey: PlaybackInteractionKey, occurrenceId: String, toIndex: Int,
        isCurrent: () -> Boolean = { true }) {}
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
