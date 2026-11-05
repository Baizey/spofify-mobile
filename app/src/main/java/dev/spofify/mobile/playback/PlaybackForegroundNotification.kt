package dev.spofify.mobile.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import dev.spofify.mobile.core.LibraryItem

/**
 * The service's sole notification/foreground owner. Call on the service's main thread.
 *
 * The caller must suppress MediaSessionService's default notification owner and call [promote]
 * BEFORE preparing/starting output, checking its result and [foregroundConfirmed]. Updating a
 * notification or observing process importance is not proof of successful foreground promotion.
 * Android remains responsible for enforcing background-start and foreground-service constraints.
 *
 * [actionIntent] supplies service-owned, immutable, session/authority-fenced intents for the exact
 * command names `previous`, `resume`, `pause`, and `next`. No Media3 action receiver bypasses it.
 * This helper never starts playback, creates a service, or releases the session/output.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class PlaybackForegroundNotification(
    private val service: Service,
    private val actionIntent: (String) -> PendingIntent,
) {
    var foregroundConfirmed: Boolean = false
        private set

    /** True only after the public foreground API actually returns successfully. */
    fun promote(session: MediaSession, track: LibraryItem?, playing: Boolean): Boolean {
        foregroundConfirmed = false
        return try {
            val notification = notification(session, track, playing)
            ServiceCompat.startForeground(
                service,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
            foregroundConfirmed = true
            true
        } catch (_: RuntimeException) {
            // Includes ForegroundServiceStartNotAllowedException, security and invalid state.
            // Never infer success from a posted notification or retry a forbidden background start.
            false
        }
    }

    /** Metadata/action refresh only; this method can never confer foreground/start authority. */
    @android.annotation.SuppressLint("NotificationPermission")
    // Android 13+ exempts media-session notifications from POST_NOTIFICATIONS. notification()
    // ALWAYS binds a real MediaSession token with MediaStyleNotificationHelper.MediaStyle;
    // transport category/actions alone would NOT qualify. Covered for refresh/detach by tests.
    // This exemption does not authorize an FGS start: promote() still uses the platform gate.
    fun update(session: MediaSession, track: LibraryItem?, playing: Boolean, notice: String? = null) {
        if (track == null) {
            demote(session, null)
            return
        }
        try {
            manager().notify(NOTIFICATION_ID, notification(session, track, playing, notice))
        } catch (_: RuntimeException) {
            // A failed metadata update must not crash the service or promote it as a fallback.
        }
    }

    /** Pause keeps a detachable Resume notification; an empty/released session removes it. */
    fun demote(session: MediaSession?, track: LibraryItem?) {
        foregroundConfirmed = false
        val keepPaused = session != null && track != null
        stopForeground(if (keepPaused) ServiceCompat.STOP_FOREGROUND_DETACH else ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (keepPaused) {
            update(requireNotNull(session), requireNotNull(track), playing = false)
        } else {
            cancel()
        }
    }

    fun close() {
        foregroundConfirmed = false
        stopForeground(ServiceCompat.STOP_FOREGROUND_REMOVE)
        cancel()
    }

    private fun notification(session: MediaSession, track: LibraryItem?, playing: Boolean, notice: String? = null): Notification {
        manager().createNotificationChannel(NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(track?.name?.takeIf { it.isNotBlank() } ?: "Spofify")
            .setContentText(notice ?: track?.artists?.joinToString(", ") { it.name }.orEmpty())
            .setContentIntent(session.sessionActivity)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(playing)
            .addAction(android.R.drawable.ic_media_previous, "Previous", actionIntent("previous"))
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Resume",
                actionIntent(if (playing) "pause" else "resume"),
            )
            .addAction(android.R.drawable.ic_media_next, "Next", actionIntent("next"))
            .setStyle(MediaStyleNotificationHelper.MediaStyle(session).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun manager(): NotificationManager = service.getSystemService(NotificationManager::class.java)

    private fun stopForeground(flags: Int) {
        try {
            ServiceCompat.stopForeground(service, flags)
        } catch (_: RuntimeException) {
            // Keep teardown best-effort; callers still see foregroundConfirmed=false.
        }
    }

    private fun cancel() {
        try {
            manager().cancel(NOTIFICATION_ID)
        } catch (_: RuntimeException) {
            // Logout/destroy must continue even if the notification service is unavailable.
        }
    }

    private companion object {
        const val CHANNEL_ID = "spofify.playback"
        const val NOTIFICATION_ID = 1_001
    }
}
