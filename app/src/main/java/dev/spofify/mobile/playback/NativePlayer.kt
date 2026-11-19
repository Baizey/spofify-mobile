package dev.spofify.mobile.playback

import android.annotation.SuppressLint
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import dev.spofify.mobile.MainActivity
import dev.spofify.mobile.core.auth.*
import dev.spofify.mobile.core.playback.*
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** One account, one ExoPlayer. A command target, its external session and all controller work are
 * shorter lived. Old remote controls and queued gestures never resolve to the successor's target. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class NativePlayer(private val app: Application, val identity: AccountIdentity,
    private val sessions: SessionRepository, parentScope: CoroutineScope, storage: MusicStorage,
    requestPlaybackStart: () -> Boolean, private val reconcile: () -> Unit,
    private val detachService: () -> Unit) {
    val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    val cache = AutomaticMusicCache(app, sessions, identity, storage, scope)
    val music = AccountMusic(app.noBackupFilesDir, identity, sessions, storage, cache, scope)
    private val mutable = MutableStateFlow<AppPlayback?>(null)
    val target = mutable.asStateFlow()
    val audio = Media3AudioExecutor(app, sessions, identity, cache, ::systemPause, requestPlaybackStart)
    var mediaSession: MediaSession? = null
        private set
    private var life: Job? = null
    private var grant: OnlineGrant? = null
    private var reconnect: PlaybackReconnect? = null
    private var maintenance: PlaybackSessionMaintenance? = null
    val view get() = target.value?.presentation

    private val deviceId: String by lazy(::registeredDeviceId)
    @SuppressLint("HardwareIds")
    private fun registeredDeviceId(): String {
        val androidId = Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "${android.os.Build.BRAND}:${android.os.Build.MODEL}"
        val profile = "spofify-android-v1\n${identity.origin}\n${identity.accountId}\n$androidId"
        return "android-" + MessageDigest.getInstance("SHA-256").digest(profile.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun start() {
        scope.launch {
            combine(sessions.onlineGrant, sessions.manualOffline) { _, _ -> Unit }.collect { synchronize() }
        }
        scope.launch { audio.observations.collect { reconcile() } }
    }

    fun synchronize() {
        if (!scope.isActive || !sessions.isCurrent(identity)) return
        val local = sessions.manualOffline.value
        val online = sessions.onlineGrant.value?.takeIf { it.identity == identity && !local }
        if (local && target.value is AppPlayback.Local || online != null && online === grant) return
        retireSource()
        if (!local && online == null) return // Returning validation does not admit any controller.
        val job = SupervisorJob(scope.coroutineContext.job)
        val mode = CoroutineScope(scope.coroutineContext + job)
        life = job
        grant = online
        val current = { job.isActive && sessions.isCurrent(identity) && (if (local) sessions.manualOffline.value
            else online != null && sessions.onlineGrant.value === online) }
        val port = audio.port(local, current)
        val selected = if (online == null) {
            val controller = LocalPlaybackController(port, music.library, mode, current)
            mode.launch { controller.state.collect { reconcile() } }
            AppPlayback.Local(controller, current)
        } else {
            val transport = sessions.playbackTransport(identity)
            val controller = AccountChannel(PlaybackTransport { name, device -> sessions.withOnlineGrant(online) {
                transport.connect(name, device)
            } }, port, mode, object : PlaybackClock {
                override fun monotonicMs() = SystemClock.elapsedRealtime()
                override fun wallTimeMs() = System.currentTimeMillis()
            }, current)
            music.personal.attach(controller)
            reconnect = PlaybackReconnect(mode, controller.state, current, { controller.connect("Phone", deviceId) }, SystemClock::elapsedRealtime)
            maintenance = PlaybackSessionMaintenance(mode, current,
                { controller.state.value.connection == PlaybackConnectionState.READY }, { sessions.withOnlineGrant(online) { sessions.renew(identity) } },
                { Log.w("SpofifyPlayer", "Session maintenance check failed") })
            mode.launch { controller.state.collect { reconcile() } }
            AppPlayback.Online(controller, current)
        }
        mediaSession = MediaSession.Builder(app, RoutingPlayer(audio.player, selected) {
            target.value === selected && mediaSession?.controllerForCurrentRequest != null
        }).setSessionActivity(PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()
        mutable.value = selected
        maintenance?.start()
        reconnect?.expedite()
    }

    fun foreground() { reconnect?.expedite(); maintenance?.refreshNow() }

    private fun systemPause() {
        val target = mutable.value ?: return
        if (target is AppPlayback.Local || target.presentation.online?.isLocalSelectedConnection() == true) target.pause()
    }

    /** Player looper, no suspend points: revoke before callbacks, close only the exact socket,
     * synchronously stop pending/native output, release the prepared period. Never issue Pause/deselect. */
    fun retireSource() {
        val old = target.value ?: return
        detachService()
        mutable.value = null
        reconnect?.close(); reconnect = null
        maintenance?.close(); maintenance = null
        music.personal.attach(null)
        when (old) {
            is AppPlayback.Online -> old.controller.close()
            is AppPlayback.Local -> old.controller.retire()
        }
        life?.cancel(); life = null
        audio.releaseOutput()
        mediaSession?.release(); mediaSession = null
        grant = null
    }

    suspend fun close() {
        retireSource()
        scope.coroutineContext.job.cancelAndJoin()
        audio.close()
    }
}
