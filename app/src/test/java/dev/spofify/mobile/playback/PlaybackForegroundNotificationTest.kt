package dev.spofify.mobile.playback

import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Looper
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.session.MediaSession
import dev.spofify.mobile.core.ArtistCredit
import dev.spofify.mobile.core.LibraryItem
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlaybackForegroundNotificationTest {
    private lateinit var service: FixtureService
    private lateinit var player: SimpleBasePlayer
    private lateinit var session: MediaSession
    private lateinit var helper: PlaybackForegroundNotification
    private lateinit var activityIntent: PendingIntent
    private val requestedActions = mutableListOf<String>()
    private val track = LibraryItem("track", "fixture-track", "A song", artists = listOf(
        ArtistCredit("artist-1", "First artist"), ArtistCredit("artist-2", "Second artist"),
    ))

    @Before fun setUp() {
        service = Robolectric.buildService(FixtureService::class.java).create().get()
        player = object : SimpleBasePlayer(Looper.getMainLooper()) {
            override fun getState(): State = State.Builder().build()
        }
        activityIntent = PendingIntent.getActivity(service, 0, Intent("fixture.open"), PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(service, player).setSessionActivity(activityIntent).build()
        helper = PlaybackForegroundNotification(service) { action ->
            requestedActions += action
            PendingIntent.getService(service, action.hashCode(), Intent(service, FixtureService::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }

    @After fun tearDown() {
        if (::helper.isInitialized) helper.close()
        if (::session.isInitialized) session.release()
        if (::player.isInitialized) player.release()
    }

    @Test fun promotionUsesActualPublicForegroundCallAndProperMediaStyleNotification() {
        val serviceShadow = shadowOf(service)
        assertFalse(helper.foregroundConfirmed)
        assertTrue(helper.promote(session, track, playing = true))
        assertTrue(helper.foregroundConfirmed)
        assertTrue(serviceShadow.lastForegroundNotificationId > 0)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, service.foregroundServiceType)
        val notification = requireNotNull(serviceShadow.lastForegroundNotification)
        assertEquals("A song", notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals("First artist, Second artist", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(activityIntent, notification.contentIntent)
        assertEquals(android.R.drawable.ic_media_play, notification.smallIcon.resId)
        assertEquals(Notification.CATEGORY_TRANSPORT, notification.category)
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(session.platformToken, notification.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION))
        assertArrayEquals(intArrayOf(0, 1, 2), notification.extras.getIntArray("android.compactActions"))
        assertEquals(listOf("previous", "pause", "next"), requestedActions)
        assertEquals(listOf("previous", "pause", "next"), notification.actions.map { shadowOf(it.actionIntent).savedIntent.action })
        val channel = service.getSystemService(NotificationManager::class.java).getNotificationChannel(notification.channelId)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertFalse(player.playWhenReady)
    }

    @Test fun backgroundStartRejectionNeverConfirmsForegroundOrStartsOutput() {
        shadowOf(service).setThrowInStartForeground(ForegroundServiceStartNotAllowedException("Synthetic background rejection"))
        assertFalse(helper.promote(session, track, playing = true))
        assertFalse(helper.foregroundConfirmed)
        assertEquals(0, shadowOf(service).lastForegroundNotificationId)
        assertFalse(player.playWhenReady)
    }

    @Test fun runtimeFailureClearsPriorConfirmationInsteadOfReportingStaleSuccess() {
        assertTrue(helper.promote(session, track, playing = true))
        shadowOf(service).setThrowInStartForeground(SecurityException("Synthetic permission rejection"))
        assertFalse(helper.promote(session, track, playing = true))
        assertFalse(helper.foregroundConfirmed)
    }

    @Test fun actionConstructionFailureCannotConfirmForeground() {
        val failing = PlaybackForegroundNotification(service) { throw IllegalStateException("No scoped action available") }
        assertFalse(failing.promote(session, track, playing = true))
        assertFalse(failing.foregroundConfirmed)
        assertEquals(0, shadowOf(service).lastForegroundNotificationId)
    }

    @Test fun updatePostsSessionBoundMediaStyleWithoutNotificationPermissionButNeverPromotes() {
        shadowOf(service.application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        helper.update(session, track, playing = false)
        assertFalse(helper.foregroundConfirmed)
        assertEquals(0, shadowOf(service).lastForegroundNotificationId)
        val notifications = shadowOf(service.getSystemService(NotificationManager::class.java)).allNotifications
        assertEquals(1, notifications.size)
        val notification = notifications.single()
        assertEquals(listOf("previous", "resume", "next"), requestedActions)
        assertEquals("Resume", notification.actions[1].title)
        assertSessionBoundMediaStyle(notification)
        assertEquals(0, notification.flags and Notification.FLAG_ONGOING_EVENT)
        assertFalse(player.playWhenReady)
    }

    @Test fun pausedDemotionDetachesForegroundAndRetainsSessionBoundMediaStyleWithoutPermission() {
        shadowOf(service.application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(helper.promote(session, track, playing = true))
        val id = shadowOf(service).lastForegroundNotificationId
        helper.demote(session, track)
        assertFalse(helper.foregroundConfirmed)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertFalse(shadowOf(service).isLastForegroundNotificationAttached)
        val manager = service.getSystemService(NotificationManager::class.java)
        val notification = requireNotNull(shadowOf(manager).getNotification(id))
        assertEquals("Resume", notification.actions[1].title)
        assertSessionBoundMediaStyle(notification)
        assertEquals(0, notification.flags and Notification.FLAG_ONGOING_EVENT)
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    @Test fun emptyOrReleasedSessionDemotionRemovesNotificationAndConfirmation() {
        assertTrue(helper.promote(session, track, playing = true))
        helper.demote(session, null)
        assertRemoved()
        assertTrue(helper.promote(session, track, playing = true))
        helper.demote(null, track)
        assertRemoved()
    }

    @Test fun closeRemovesForegroundAndNotificationAndIsIdempotent() {
        assertTrue(helper.promote(session, track, playing = true))
        helper.close()
        helper.close()
        assertRemoved()
        // Notification ownership never includes session/output ownership.
        assertEquals(player, session.player)
    }

    @Test fun emptyUpdateRemovesRatherThanLeavingStalePlayingNotification() {
        assertTrue(helper.promote(session, track, playing = true))
        helper.update(session, null, playing = false)
        assertRemoved()
    }

    private fun assertSessionBoundMediaStyle(notification: Notification) {
        assertEquals("android.app.Notification\$MediaStyle", notification.extras.getString(Notification.EXTRA_TEMPLATE))
        @Suppress("DEPRECATION")
        val token = notification.extras.getParcelable<android.media.session.MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
        assertEquals(session.platformToken, token)
        assertArrayEquals(intArrayOf(0, 1, 2), notification.extras.getIntArray("android.compactActions"))
    }

    private fun assertRemoved() {
        assertFalse(helper.foregroundConfirmed)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }

    class FixtureService : Service() {
        override fun onBind(intent: Intent?): IBinder? = null
    }
}
