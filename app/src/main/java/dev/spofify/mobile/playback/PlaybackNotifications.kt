package dev.spofify.mobile.playback

import android.app.PendingIntent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.PlaybackPendingIntentBuilder
import com.google.common.collect.ImmutableList
import dev.spofify.mobile.R

/** Keep Media3's notification and controls without promising foreground from notification Play. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class PlaybackNotifications(
    private val service: PlaybackService,
    private val controlToken: (MediaSession) -> String,
    private val notificationBuilt: () -> Unit,
    private val artworkLoaded: (MediaSession) -> Unit,
    private val delegate: MediaNotification.Provider = DefaultMediaNotificationProvider.Builder(service).build().apply {
        setSmallIcon(R.drawable.ic_spofify_mark)
    },
) : MediaNotification.Provider by delegate {
    override fun createNotification(
        session: MediaSession,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        callback: MediaNotification.Provider.Callback,
    ): MediaNotification = delegate.createNotification(session, mediaButtonPreferences, object : MediaNotification.ActionFactory by actionFactory {
        override fun createMediaAction(
            mediaSession: MediaSession, icon: IconCompat, title: CharSequence, command: Int,
        ): NotificationCompat.Action = NotificationCompat.Action(icon, title, createMediaActionPendingIntent(mediaSession, command))

        override fun createMediaActionPendingIntent(mediaSession: MediaSession, command: Int): PendingIntent =
            controlIntent(mediaSession, command)

        override fun createNotificationDismissalIntent(mediaSession: MediaSession): PendingIntent =
            controlIntent(mediaSession, Player.COMMAND_STOP, dismissal = true)
    }) {
        // Media3's late artwork callback bypasses onUpdateNotificationAsync's authority/admission
        // gate. Refresh instead: the default provider now has the loaded bitmap in its cache.
        artworkLoaded(session)
    }.also { notificationBuilt() }

    private fun controlIntent(session: MediaSession, command: Int, dismissal: Boolean = false): PendingIntent {
        val token = controlToken(session)
        val extras = Bundle().apply {
            putString(CONTROL_TOKEN, token)
            putString(CONTROL_SESSION, session.id)
            if (dismissal) putBoolean(MediaNotification.NOTIFICATION_DISMISSED_EVENT_KEY, true)
        }
        val intent = PlaybackPendingIntentBuilder.createMediaButtonIntent(service, command, extras, session.id,
            PlaybackService::class.java).apply {
            // Extras are not part of PendingIntent identity. Never rekey an old attachment's
            // button into a valid button for the replacement, or alias dismissal with Stop.
            addCategory("$CONTROL_TOKEN.$token")
            if (dismissal) addCategory(MediaNotification.NOTIFICATION_DISMISSED_EVENT_KEY)
        }
        return PendingIntent.getService(service, command, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val CONTROL_TOKEN = "dev.spofify.mobile.playback.CONTROL_TOKEN"
        const val CONTROL_SESSION = "dev.spofify.mobile.playback.CONTROL_SESSION"
    }
}
