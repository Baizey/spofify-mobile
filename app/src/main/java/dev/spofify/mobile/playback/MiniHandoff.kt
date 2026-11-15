package dev.spofify.mobile.playback

import dev.spofify.mobile.core.playback.PlaybackConnectionState
import dev.spofify.mobile.core.playback.PlaybackTrack
import dev.spofify.mobile.core.playback.PlaybackView

internal const val MINI_HANDOFF_TIMEOUT_MS = 15_000L

/** Presentation lease only: none of these fields is evidence of actual playback. */
internal data class MiniHandoffSource(
    val sessionId: String,
    val connectionId: String,
    val selectedDeviceId: String,
    val selectedConnectionId: String,
    val outputVersion: Long,
    val eventId: String,
    val key: Long,
)

internal fun miniHandoffSource(view: PlaybackView): MiniHandoffSource? {
    if (view.connection != PlaybackConnectionState.READY) return null
    val snapshot = view.snapshot ?: return null
    val current = view.current ?: return null
    val occurrence = snapshot.state.occurrence ?: return null
    if (view.sessionId.isEmpty() || view.connectionId.isEmpty() || snapshot.sessionId != view.sessionId ||
        occurrence.key != current.key || occurrence.trackId != current.id || occurrence.eventId.isEmpty()) return null
    return MiniHandoffSource(view.sessionId, view.connectionId, snapshot.selectedDeviceId,
        snapshot.selectedConnectionId, snapshot.outputVersion, occurrence.eventId, current.key)
}

/** A genuine immediate neighbour and its immutable source snapshot, captured before pointer-down. */
internal data class MiniSwipeSelection(
    val source: MiniHandoffSource,
    val sourceStateVersion: Long,
    val incoming: PlaybackTrack,
) {
    fun isCurrent(view: PlaybackView): Boolean =
        miniHandoffSource(view) == source && view.snapshot?.stateVersion == sourceStateVersion
}

internal fun miniSwipeSelection(view: PlaybackView, step: Int): MiniSwipeSelection? {
    if (step != -1 && step != 1) return null
    val source = miniHandoffSource(view) ?: return null
    val snapshot = view.snapshot ?: return null
    val incoming = view.queue.getOrNull(snapshot.state.position + step) ?: return null
    return MiniSwipeSelection(source, snapshot.stateVersion, incoming)
}

internal data class MiniHandoff(
    val source: MiniHandoffSource,
    val incoming: PlaybackTrack,
    val commandId: String,
    val startedAtMs: Long,
)

/** Queue-only updates never retarget the captured identity. A changed actual current always wins. */
internal fun retainedMiniHandoff(pending: MiniHandoff?, view: PlaybackView, nowMs: Long): MiniHandoff? {
    if (pending == null || miniHandoffSource(view) != pending.source || pending.commandId.isEmpty()) return null
    val elapsed = nowMs - pending.startedAtMs
    if (elapsed < 0 || elapsed >= MINI_HANDOFF_TIMEOUT_MS) return null
    val result = view.latestResult
    if (result?.id == pending.commandId && (result.outcome == "rejected" ||
            result.execution in setOf("uncertain", "blocked", "media", "superseded"))) return null
    return pending
}

internal fun miniPresentedTrack(view: PlaybackView, pending: MiniHandoff?, nowMs: Long): PlaybackTrack? =
    retainedMiniHandoff(pending, view, nowMs)?.incoming ?: view.current
