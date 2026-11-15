package dev.spofify.mobile.playback

import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.spofify.mobile.SpofifyApplication

/** Media3 owns the foreground notification; the Application owns playback authority and resources. */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private val owner: PlaybackOwner get() = (application as SpofifyApplication).playback
    private var attached: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        setListener(object : MediaSessionService.Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                owner.backgroundStartBlocked(attached)
            }
        })
        synchronizeSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        synchronizeSession()
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        if (!controllerInfo.isTrusted && controllerInfo.uid != android.os.Process.myUid()) return null
        synchronizeSession()
        return attached
    }

    private fun synchronizeSession() {
        val current = owner.current?.mediaSession
        if (attached === current) return
        attached?.let(::removeSession)
        attached = current
        current?.let {
            addSession(it)
            owner.serviceStarted(it)
        }
    }

    override fun onDestroy() {
        val retired = attached
        attached = null
        super.onDestroy()
        owner.serviceStopped(retired)
    }
}
