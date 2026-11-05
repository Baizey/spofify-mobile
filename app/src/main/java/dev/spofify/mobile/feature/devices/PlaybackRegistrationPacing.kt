package dev.spofify.mobile.feature.devices

/** Memory-only write pacing, NOT registration identity/recovery or playback authority.
 * An uncertain register may have committed. Graph/service replacement must not reset its
 * rate limit. Thirty seconds limits churn; delayed acceptance can still outlive this interval.
 * No IDs, names, sessions, credentials or server snapshots are stored here or persisted.
 * Access is confined to the coordinator's serial owner dispatcher.
 */
class PlaybackRegistrationPacing {
    private var serial = 0L
    private var notBefore = 0L
    fun mayAttempt(now: Long): Boolean = now >= notBefore
    fun dispatched(now: Long): Long {
        notBefore = now + 30_000
        return ++serial
    }
    fun confirmed(attempt: Long) {
        if (serial == attempt) notBefore = 0
    }
}
