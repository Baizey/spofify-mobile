package dev.spofify.mobile.playback

import dev.spofify.mobile.core.playback.AudioFacts
import dev.spofify.mobile.core.playback.PlaybackConnectionState
import dev.spofify.mobile.core.playback.PlaybackController
import dev.spofify.mobile.core.playback.PlaybackView

/** Logical device grouping is not executor identity; only this exact live socket may own local sound. */
internal fun PlaybackView.isLocalSelectedConnection(): Boolean =
    connection == PlaybackConnectionState.READY && connectionId.isNotEmpty() &&
        snapshot?.selectedConnectionId == connectionId

internal fun PlaybackController.isLocalSelectedConnection(): Boolean = state.value.isLocalSelectedConnection()

internal fun PlaybackView.isLocalTransferTarget(): Boolean =
    connection == PlaybackConnectionState.READY && connectionId.isNotEmpty() &&
        snapshot?.transfer?.targetConnectionId == connectionId

/** Pending target execution may legitimately arrive before the selected-connection publication. */
internal fun PlaybackView.isLocalOutputConnection(): Boolean = isLocalSelectedConnection() || isLocalTransferTarget()

/** Intent and native playWhenReady retain service lifetime; neither is presented as proof of sound. */
internal fun retainLocalPlaybackService(
    view: PlaybackView,
    facts: AudioFacts,
    localPlayWhenReady: Boolean = false,
): Boolean {
    if (view.connection != PlaybackConnectionState.READY || view.connectionId.isEmpty()) return false
    val pendingNativePlay = localPlayWhenReady && !facts.ended
    val nativeWork = facts.isPlaying || facts.isBuffering || facts.isLoading || pendingNativePlay
    if (view.isLocalTransferTarget()) return true
    if (view.isLocalSelectedConnection()) return view.desiredPlaying || nativeWork
    return view.snapshot?.transfer?.sourceConnectionId == view.connectionId && nativeWork
}

/**
 * Reattach a genuinely playing/pending local executor without pausing it. A
 * successful start does not need a replayed execute or unchecked native play().
 * Paused preparation can finish normally and request its service before play.
 */
internal fun handlePlaybackServiceLoss(
    view: PlaybackView,
    facts: AudioFacts,
    localPlayWhenReady: Boolean,
    hasPlaybackAuthority: Boolean,
    restartLocalService: () -> Boolean,
    pauseNative: () -> Unit,
    reportLocalBlocked: () -> Unit,
) {
    if (!view.isLocalOutputConnection()) return
    // ExoPlayer can retain PWR after natural end. That is not pending sound or
    // a reason to launch an FGS that has nothing to promote into foreground.
    val nativePlaybackPending = facts.isPlaying || localPlayWhenReady && !facts.ended
    if (!hasPlaybackAuthority) {
        if (nativePlaybackPending) pauseNative()
        return
    }
    if (nativePlaybackPending && !restartLocalService()) reportLocalBlocked()
}

/** Only actual platform refusal retires a live local operation as blocked. */
internal fun handlePlaybackServiceStartRefusal(
    view: PlaybackView,
    hasPlaybackAuthority: Boolean,
    pauseNative: () -> Unit,
    reportLocalBlocked: () -> Unit,
) {
    if (!view.isLocalOutputConnection()) return
    if (hasPlaybackAuthority) reportLocalBlocked() else pauseNative()
}
