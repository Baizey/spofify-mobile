package dev.spofify.mobile.playback

import dev.spofify.mobile.core.playback.AudioFacts
import dev.spofify.mobile.core.playback.PlaybackConnectionState
import dev.spofify.mobile.core.playback.AccountChannel
import dev.spofify.mobile.core.playback.PlaybackView
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

/** Logical device grouping is not executor identity; only this exact live socket may own local sound. */
internal fun PlaybackView.isLocalSelectedConnection(): Boolean =
    connection == PlaybackConnectionState.READY && connectionId.isNotEmpty() &&
        snapshot?.selectedConnectionId == connectionId

internal fun AccountChannel.isLocalSelectedConnection(): Boolean = state.value.isLocalSelectedConnection()

internal fun PlaybackView.isLocalTransferTarget(): Boolean =
    connection == PlaybackConnectionState.READY && connectionId.isNotEmpty() &&
        snapshot?.transfer?.targetConnectionId == connectionId

/** Pending target execution may legitimately arrive before the selected-connection publication. */
internal fun PlaybackView.isLocalOutputConnection(): Boolean = isLocalSelectedConnection() || isLocalTransferTarget()

/** A terminal native failure cannot keep an unpromotable FGS alive on stale server intent. */
private fun nativePlaybackFailedWithoutWork(facts: AudioFacts, localPlayWhenReady: Boolean): Boolean =
    (facts.error == "media" || (facts.error == "blocked" && !localPlayWhenReady)) &&
        !facts.isPlaying && !facts.isBuffering && !facts.isLoading

/** Intent and native playWhenReady retain service lifetime; neither is presented as proof of sound. */
internal fun retainLocalPlaybackService(
    view: PlaybackView,
    facts: AudioFacts,
    localPlayWhenReady: Boolean = false,
): Boolean {
    if (view.connection != PlaybackConnectionState.READY || view.connectionId.isEmpty()) return false
    if (nativePlaybackFailedWithoutWork(facts, localPlayWhenReady)) return false
    val pendingNativePlay = localPlayWhenReady && !facts.ended
    val nativeWork = facts.isPlaying || facts.isBuffering || facts.isLoading || pendingNativePlay
    if (view.isLocalTransferTarget()) return true
    if (view.isLocalSelectedConnection()) return view.desiredPlaying || nativeWork
    return view.snapshot?.transfer?.sourceConnectionId == view.connectionId && nativeWork
}

/**
 * Reconcile failed starts even while still selected. Ordinary paused local services
 * may remain attached; task removal uses the stricter retention policy separately.
 */
internal fun shouldStopPlaybackService(
    view: PlaybackView,
    facts: AudioFacts,
    localPlayWhenReady: Boolean = false,
): Boolean = !retainLocalPlaybackService(view, facts, localPlayWhenReady) &&
    (!view.isLocalOutputConnection() || nativePlaybackFailedWithoutWork(facts, localPlayWhenReady))

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
    val nativePlaybackPending = hasPendingNativePlayback(facts, localPlayWhenReady)
    if (!hasPlaybackAuthority) {
        if (nativePlaybackPending) pauseNative()
        return
    }
    if (nativePlaybackPending && !restartLocalService()) reportLocalBlocked()
}

internal fun hasPendingNativePlayback(facts: AudioFacts, localPlayWhenReady: Boolean): Boolean =
    facts.isPlaying || localPlayWhenReady && !facts.ended

internal const val PLAYBACK_SERVICE_ADMISSION_TIMEOUT_MS = 5_000L

/** The platform can acknowledge promotion before a suspended play consumes it. Recheck the live lease. */
internal suspend fun awaitPlaybackServiceAdmission(promoted: Deferred<Boolean>, stillAdmitted: () -> Boolean): Boolean =
    withTimeoutOrNull(PLAYBACK_SERVICE_ADMISSION_TIMEOUT_MS) { promoted.await() } == true && stillAdmitted()
