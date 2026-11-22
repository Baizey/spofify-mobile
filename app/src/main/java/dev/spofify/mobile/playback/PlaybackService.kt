package dev.spofify.mobile.playback

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ServiceCompat
import androidx.media3.common.Player
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

/** The Application owns playback authority; this service owns promotion of its local attachment. */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private val owner: PlaybackOwner get() = (application as SpofifyApplication).playback
    private inner class LocalSession(val session: MediaSession) {
        val controlToken: String = UUID.randomUUID().toString()
        var started = false
        var foregroundPlayer: Player? = null
            private set
        val boundForeground get() = foregroundPlayer != null
        private var refreshQueued = false
        private val refresh = Runnable {
            refreshQueued = false
            refreshBoundNotification(this)
        }

        fun queueRefresh() {
            if (!refreshQueued) {
                refreshQueued = true
                mainHandler.post(refresh)
            }
        }

        fun clearRefresh() {
            mainHandler.removeCallbacks(refresh)
            refreshQueued = false
        }
        private val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (player === foregroundPlayer && events.containsAny(Player.EVENT_PLAYBACK_STATE_CHANGED,
                        Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_MEDIA_METADATA_CHANGED, Player.EVENT_TIMELINE_CHANGED,
                        Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED)) requestBoundNotificationUpdate(this@LocalSession)
            }
        }

        fun trackForegroundPlayer(next: Player?): Boolean {
            if (foregroundPlayer === next) return false
            val previous = foregroundPlayer
            foregroundPlayer = next // ListenerSet can flush a final unhandled event on removal.
            previous?.removeListener(listener)
            next?.addListener(listener)
            return true
        }
    }

    private var localSession: LocalSession? = null
    private val localBinder = LocalBinder(this)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val instanceToken = UUID.randomUUID().toString()
    private var legacyForeground = false
    private var notificationBuilds = 0L
    private lateinit var notifications: PlaybackNotifications
    private val notificationManager get() = getSystemService(NotificationManager::class.java)

    // An exported service can receive this action from another process. The binder has no
    // transaction API; only our in-process ServiceConnection can obtain the instance and attach.
    internal class LocalBinder(val service: PlaybackService) : Binder()

    override fun onCreate() {
        super.onCreate()
        notifications = PlaybackNotifications(this,
            controlToken = { session -> checkNotNull(localSession?.takeIf { it.session === session }).controlToken },
            notificationBuilt = { notificationBuilds++ },
            artworkLoaded = { session ->
                localSession?.takeIf { it.session === session }?.let { local ->
                    if (local.boundForeground) local.queueRefresh()
                    else if (isSessionAdded(session)) triggerNotificationUpdate()
                }
            })
        setMediaNotificationProvider(notifications)
    }

    override fun onBind(intent: Intent?): IBinder? {
        val binder = super.onBind(intent)
        return if (intent?.action == ATTACH_LOCAL) localBinder else binder
    }

    override fun startForegroundService(service: Intent): ComponentName? {
        // Legacy Media3 promotion calls this just before promoting an already bound instance.
        // Preserve its stale-start cleanup without creating new starts for ongoing updates.
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
        val local = localSession
        val ownsStartedLifetime = local?.started == true
        val stopUnownedStart = shouldStopRejectedPlaybackStart(foreground, ownsStartedLifetime)
        val selfStart = intent?.takeIf { it.action == null }
        val boundStart = selfStart?.getStringExtra(BOUND_START_ATTACHMENT)
        val legacyStart = selfStart?.getStringExtra(SELF_START_INSTANCE)
        when {
            boundStart != null && boundStart == local?.controlToken && ownsStartedLifetime -> {
                // Ordinary self-start, sent only after direct promotion. On task removal Android
                // notifies started services, not a service with only our application binding.
                super.onStartCommand(null, flags, startId)
            }
            control || boundStart == null && legacyStart != null && shouldDelegateLegacyPlaybackSelfStart(
                Build.VERSION.SDK_INT, legacyStart == instanceToken, foreground, ownsStartedLifetime,
            ) -> {
                // Media3 still settles dormant legacy FGS starts whose original instance is gone.
                super.onStartCommand(intent, flags, startId)
            }
            else -> {
                // Rejected ordinary controls, stale starts and sticky restarts own no lifetime.
                super.onStartCommand(null, flags, startId)
                if (stopUnownedStart) stopSelfResult(startId)
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

    @RequiresApi(35)
    internal fun promoteBoundPlayback(session: MediaSession) {
        val local = localSession?.takeIf { it.session === session } ?: return
        val admission = owner.notificationAdmission(this, session)
        if (admission == null && !owner.shouldRunInForeground(this, session, media3Requested = false)) return
        val onLoss = owner.notificationLossCallback(this, session)
        val wasForeground = hasMediaForeground()
        var stage = "notification-build"
        try {
            if (!wasForeground) {
                val media = notifications.buildForBoundForeground(session)
                stage = "foreground-refused"
                // Promote the live bound instance first. No startForegroundService() deadline
                // is created, even on platforms that refuse a media foreground promotion.
                ServiceCompat.startForeground(this, media.notificationId, media.notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            }
            stage = "foreground-not-observed"
            check(hasMediaForeground())
        } catch (e: RuntimeException) {
            Log.w("SpofifyPlayer", "Playback bound promotion failed: $stage api=${Build.VERSION.SDK_INT} ${e.javaClass.simpleName}")
            if (hasMediaForeground()) stopForeground(STOP_FOREGROUND_REMOVE)
            if (admission != null) admission.complete(false) else mainHandler.post { onLoss?.invoke(false) }
            return
        }
        // Android only delivers onTaskRemoved to a started service. This ordinary start has no
        // FGS deadline. Refusal doesn't undo the already live foreground/application binding.
        if (!local.started) {
            try {
                checkNotNull(startService(Intent(this, PlaybackService::class.java)
                    .putExtra(BOUND_START_ATTACHMENT, local.controlToken)))
                local.started = true
            } catch (e: RuntimeException) {
                Log.w("SpofifyPlayer", "Playback task-removal registration refused: ${e.javaClass.simpleName}")
            }
        }
        local.trackForegroundPlayer(session.player)
        if (!wasForeground) Log.i("SpofifyPlayer", "Playback foreground promoted: bound api=${Build.VERSION.SDK_INT}")
        else local.queueRefresh()
        admission?.complete(true)
    }

    internal fun reconcileBoundPlayer(session: MediaSession) {
        val local = localSession?.takeIf { it.session === session && it.boundForeground } ?: return
        // Crossfade can replace MediaSession.player even when no event is sent by the old deck.
        if (local.trackForegroundPlayer(session.player)) requestBoundNotificationUpdate(local)
        else if (!hasMediaForeground()) local.queueRefresh()
    }

    private fun requestBoundNotificationUpdate(local: LocalSession) {
        if (localSession !== local || !local.boundForeground || !owner.ownsLocalNotification(this, local.session)) return
        // Use Media3's suggested pause grace period, but not its notification manager.
        if (isSessionAdded(local.session)) triggerNotificationUpdate()
        else updateLocalNotification(local, media3Requested = false)
    }

    @SuppressLint("NotificationPermission") // Media session notifications are exempt from POST_NOTIFICATIONS.
    private fun refreshBoundNotification(local: LocalSession) {
        val session = local.session
        if (localSession !== local || !local.boundForeground || !owner.ownsLocalNotification(this, session)) return
        if (!hasMediaForeground()) {
            owner.notificationLossCallback(this, session)?.invoke(true)
            return
        }
        try {
            val media = notifications.buildForBoundForeground(session)
            notificationManager.notify(media.notificationId, media.notification)
        } catch (e: RuntimeException) {
            Log.w("SpofifyPlayer", "Playback bound notification refresh failed: ${e.javaClass.simpleName}")
        }
    }

    override fun onUpdateNotificationAsync(session: MediaSession, startInForegroundRequired: Boolean): ListenableFuture<Void?> {
        // Media3 emits notification updates even from a removed controller's disconnect. Its
        // default handling of that update removes the notification of the *whole* service.
        val local = localSession?.takeIf { it.session === session && owner.ownsLocalNotification(this, session) }
            ?: return Futures.immediateVoidFuture()
        return updateLocalNotification(local, startInForegroundRequired)
    }

    private fun updateLocalNotification(local: LocalSession, media3Requested: Boolean): ListenableFuture<Void?> {
        val session = local.session
        val wasForeground = hasMediaForeground()
        val onLoss = owner.notificationLossCallback(this, session)
        val foreground = owner.shouldRunInForeground(this, session, media3Requested)
        if (usesBoundPlaybackNotification(Build.VERSION.SDK_INT, owner.notificationAdmission(this, session) != null, foreground)) {
            // In addition to replacing foreground, Media3 may *remove* the current notification
            // when its mirror is unready. Don't call either path during modern admission/play.
            if (local.boundForeground) {
                local.trackForegroundPlayer(session.player)
                if (wasForeground) local.queueRefresh() else mainHandler.post { onLoss?.invoke(true) }
            }
            return Futures.immediateVoidFuture()
        }
        val leavingBoundForeground = local.boundForeground
        if (leavingBoundForeground) releaseBoundNotification(local)
        val buildsBeforeUpdate = notificationBuilds
        val result = super.onUpdateNotificationAsync(session, foreground)
        // The pinned manager creates/posts synchronously on this service's player looper.
        val notificationBuilt = notificationBuilds != buildsBeforeUpdate
        Futures.addCallback(result, object : FutureCallback<Void?> {
            override fun onSuccess(value: Void?) {
                // API 26–28 has no platform foreground state query. Media3 also returns a
                // successful future for a skipped update, so require an actual notification.
                if (Build.VERSION.SDK_INT < 29) legacyForeground = foreground && notificationBuilt
                if (!isSessionAdded(session) || wasForeground && !hasMediaForeground()) {
                    mainHandler.post { onLoss?.invoke(true) }
                }
            }

            override fun onFailure(t: Throwable) {
                if (leavingBoundForeground && localSession === local && !local.boundForeground) {
                    Log.w("SpofifyPlayer", "Playback paused notification failed: ${t.javaClass.simpleName}")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    notificationManager.cancel(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID)
                } else if (foreground) {
                    Log.w("SpofifyPlayer", "Media3 foreground notification failed: ${t.javaClass.simpleName}")
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

    private fun releaseBoundNotification(local: LocalSession) {
        local.trackForegroundPlayer(null)
        local.clearRefresh()
    }

    internal fun detach(session: MediaSession) {
        val local = localSession?.takeIf { it.session === session } ?: return
        localSession = null
        releaseBoundNotification(local)
        if (isSessionAdded(session)) removeSession(session)
        legacyForeground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        // A paused Media3 notification may already be detached from foreground.
        notificationManager.cancel(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID)
    }

    override fun onDestroy() {
        localSession?.session?.let(::detach)
        super.onDestroy()
        owner.serviceStopped(this)
    }

    internal companion object {
        const val ATTACH_LOCAL = "dev.spofify.mobile.playback.ATTACH_LOCAL"
        private const val SELF_START_INSTANCE = "dev.spofify.mobile.playback.SELF_START_INSTANCE"
        private const val BOUND_START_ATTACHMENT = "dev.spofify.mobile.playback.BOUND_START_ATTACHMENT"
    }
}
