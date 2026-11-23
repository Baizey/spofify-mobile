package dev.spofify.mobile.playback

import dev.spofify.mobile.core.playback.AudioFacts
import dev.spofify.mobile.core.playback.PlaybackView
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

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
    if (!view.localOutput || nativePlaybackFailedWithoutWork(facts, localPlayWhenReady)) return false
    val pendingNativePlay = localPlayWhenReady && !facts.ended
    return view.wantsPause || facts.isPlaying || facts.isBuffering || facts.isLoading || pendingNativePlay
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
    (!view.localOutput || nativePlaybackFailedWithoutWork(facts, localPlayWhenReady))

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
    if (!view.localOutput) return
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

/** The mirrored notification controller cannot be allowed to remove the focus prerequisite. */
internal fun usesBoundPlaybackNotification(api: Int, hasAdmission: Boolean, foregroundRequired: Boolean): Boolean =
    api >= 35 && (hasAdmission || foregroundRequired)

/** A rejected control cannot stop the started lifetime used for task-removal cleanup. */
internal fun shouldStopRejectedPlaybackStart(foreground: Boolean, ownsStartedLifetime: Boolean): Boolean =
    !foreground && !ownsStartedLifetime

/** Retire dormant legacy FGS starts without demoting an already owned replacement. */
internal fun shouldDelegateLegacyPlaybackSelfStart(
    api: Int, matchesInstance: Boolean, foreground: Boolean, ownsStartedLifetime: Boolean,
): Boolean = (matchesInstance || !foreground) && !(api >= 35 && ownsStartedLifetime)

/** The platform can acknowledge promotion before a suspended play consumes it. Recheck the live lease. */
internal suspend fun awaitPlaybackServiceAdmission(promoted: Deferred<Boolean>, stillAdmitted: () -> Boolean): Boolean =
    withTimeoutOrNull(PLAYBACK_SERVICE_ADMISSION_TIMEOUT_MS) { promoted.await() } == true && stillAdmitted()
