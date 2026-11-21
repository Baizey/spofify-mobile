package dev.spofify.mobile.playback

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dev.spofify.mobile.SpofifyApplication
import java.util.UUID

/** Media3 owns the foreground notification; the Application owns playback authority and resources. */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private val owner: PlaybackOwner get() = (application as SpofifyApplication).playback
    private class LocalSession(val session: MediaSession) {
        val controlToken: String = UUID.randomUUID().toString()
    }

    private var localSession: LocalSession? = null
    private val localBinder = LocalBinder(this)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val instanceToken = UUID.randomUUID().toString()
    private var legacyForeground = false
    private var notificationBuilds = 0L

    // An exported service can receive this action from another process. The binder has no
    // transaction API; only our in-process ServiceConnection can obtain the instance and attach.
    internal class LocalBinder(val service: PlaybackService) : Binder()

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(PlaybackNotifications(this,
            controlToken = { session -> checkNotNull(localSession?.takeIf { it.session === session }).controlToken },
            notificationBuilt = { notificationBuilds++ },
            artworkLoaded = { session -> if (localSession?.session === session && isSessionAdded(session)) triggerNotificationUpdate() }))
    }

    override fun onBind(intent: Intent?): IBinder? {
        val binder = super.onBind(intent)
        return if (intent?.action == ATTACH_LOCAL) localBinder else binder
    }

    override fun startForegroundService(service: Intent): ComponentName? {
        // Media3 1.11.1 calls this just before promoting an already bound instance. Only a
        // fresh promotion needs a started service; ongoing notification updates need no new start.
        if (service.action == null && service.component == ComponentName(this, PlaybackService::class.java)) {
            if (hasMediaForeground()) return service.component
            service.putExtra(SELF_START_INSTANCE, instanceToken)
        }
        return super.startForegroundService(service)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The exported component must not route a fabricated media action through Media3's
        // session-by-URI shortcut. The token is only embedded in immutable notification intents.
        val control = intent != null && localSession?.let { owner.acceptsNotificationControl(it.session, intent) } == true
        val foreground = hasMediaForeground()
        when {
            intent == null || intent.action != null && !control -> {
                // Rejected ordinary controls and old sticky restarts own no playback lifetime.
                super.onStartCommand(null, flags, startId)
                if (!foreground) stopSelfResult(startId)
            }
            foreground && !control && intent.getStringExtra(SELF_START_INSTANCE) != instanceToken -> {
                // Media3's stale-start cleanup can demote the replacement before its own
                // self-start arrives. It is already foreground, so don't let stale work stop it.
                super.onStartCommand(null, flags, startId)
            }
            else -> {
                // Media3 settles dormant stale self-starts; an unmarked ordinary start must
                // not leave an unowned started service if Media3 found nothing to handle.
                super.onStartCommand(intent, flags, startId)
                if (!control && !intent.hasExtra(SELF_START_INSTANCE)) stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

    internal fun matchesNotificationControl(session: MediaSession, intent: Intent): Boolean {
        val local = localSession?.takeIf { it.session === session } ?: return false
        return intent.action == Intent.ACTION_MEDIA_BUTTON &&
            intent.getStringExtra(PlaybackNotifications.CONTROL_TOKEN) == local.controlToken &&
            intent.getStringExtra(PlaybackNotifications.CONTROL_SESSION) == session.id
    }

    internal fun hasMediaForeground(): Boolean = if (Build.VERSION.SDK_INT >= 29) {
        foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK != 0
    } else legacyForeground

    override fun onUpdateNotificationAsync(session: MediaSession, startInForegroundRequired: Boolean): ListenableFuture<Void?> {
        // Media3 emits notification updates even from a removed controller's disconnect. Its
        // default handling of that update removes the notification of the *whole* service.
        if (localSession?.session !== session || !owner.ownsLocalNotification(this, session)) return Futures.immediateVoidFuture()
        val admission = owner.notificationAdmission(this, session)
        val foreground = admission != null || owner.shouldRunInForeground(this, session, startInForegroundRequired)
        val wasForeground = hasMediaForeground()
        val onLoss = owner.notificationLossCallback(this, session)
        val buildsBeforeUpdate = notificationBuilds
        val result = super.onUpdateNotificationAsync(session, foreground)
        // The pinned manager creates/posts synchronously on this service's player looper.
        val notificationBuilt = notificationBuilds != buildsBeforeUpdate
        Futures.addCallback(result, object : FutureCallback<Void?> {
            override fun onSuccess(value: Void?) {
                // API 26–28 has no platform foreground state query. Media3 also returns a
                // successful future for a skipped update, so require an actual notification.
                if (Build.VERSION.SDK_INT < 29) legacyForeground = foreground && notificationBuilt
                if (admission != null && hasMediaForeground() &&
                    owner.notificationAdmission(this@PlaybackService, session) === admission) admission.complete(true)
                if (!isSessionAdded(session) || wasForeground && !hasMediaForeground()) {
                    mainHandler.post { onLoss?.invoke(true) }
                }
            }

            override fun onFailure(t: Throwable) {
                if (admission != null) {
                    if (owner.notificationAdmission(this@PlaybackService, session) === admission) admission.complete(false)
                } else if (foreground) {
                    // The exact failed update owns the refusal; Media3's listener is posted
                    // separately and no longer knows which operation attempted promotion.
                    mainHandler.post { onLoss?.invoke(false) }
                }
            }
        }, MoreExecutors.directExecutor())
        return result
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Media3's default cleanup calls setPlayWhenReady(false), even for a remote-only controller.
        // App-owned output authority, not local isPlaying at one transition instant, owns retention.
        owner.onTaskRemoved(this)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        if (!controllerInfo.isTrusted && controllerInfo.uid != android.os.Process.myUid()) return null
        // A trusted controller may connect remotely, but only the app's local attachment
        // owns the local media notification (or the lifetime of an active foreground start).
        return owner.current?.takeIf(NativePlayer::hasCurrentOutput)?.mediaSession
    }

    internal fun attach(session: MediaSession) {
        if (localSession?.session === session && isSessionAdded(session)) return
        localSession?.session?.let(::detach)
        // A trusted controller may keep this instance and MediaSession alive after local output
        // is retired. Its successor must not accept this attachment's notification buttons.
        localSession = LocalSession(session)
        addSession(session)
    }

    internal fun detach(session: MediaSession) {
        if (localSession?.session !== session) return
        localSession = null
        if (isSessionAdded(session)) removeSession(session)
        legacyForeground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        // A paused Media3 notification may already be detached from foreground.
        getSystemService(NotificationManager::class.java)
            .cancel(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID)
    }

    override fun onDestroy() {
        val retired = localSession?.session
        localSession = null
        if (retired != null && isSessionAdded(retired)) removeSession(retired)
        super.onDestroy()
        owner.serviceStopped(this)
    }

    internal companion object {
        const val ATTACH_LOCAL = "dev.spofify.mobile.playback.ATTACH_LOCAL"
        private const val SELF_START_INSTANCE = "dev.spofify.mobile.playback.SELF_START_INSTANCE"
    }
}
