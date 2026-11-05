package dev.spofify.mobile.playback

import dev.spofify.mobile.core.PlaybackSnapshot
import dev.spofify.mobile.core.QueueIntentState
import dev.spofify.mobile.feature.devices.PlaybackCoordinator

/** Cancellation-only capture for a deliberate High while no output is selected.
 * Not an interaction key, receiver binding, lease or permission to replay an action. */
class PlaybackQueueOrigin internal constructor(
    internal val owner: PlaybackCoordinator,
    internal val snapshot: PlaybackSnapshot,
    internal val metadata: QueueIntentState?,
    internal val isCurrent: () -> Boolean,
) {
    internal var consumed = false
    override fun toString(): String = "PlaybackQueueOrigin(transient)"
}

/** Progress and media flags are not queue identity. History-event/queue/context ABA is. */
internal fun PlaybackSnapshot.queueOriginIdentity(): PlaybackSnapshot = copy(
    elapsed = 0.0, duration = 0.0, playing = false, waiting = false, loading = false, error = "",
    occurrence = occurrence?.copy(progress = 0.0, reported = false),
)
