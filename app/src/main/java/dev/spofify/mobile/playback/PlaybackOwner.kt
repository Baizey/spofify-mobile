package dev.spofify.mobile.playback

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import dev.spofify.mobile.MainActivity
import dev.spofify.mobile.core.auth.AccountIdentity
import dev.spofify.mobile.core.auth.AuthState
import dev.spofify.mobile.core.auth.SessionRepository
import dev.spofify.mobile.core.playback.PlaybackClock
import dev.spofify.mobile.core.playback.PlaybackController
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Application lifetime, not Activity lifetime: one authenticated player and one media session. */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackOwner(
    private val app: Application,
    private val sessions: SessionRepository,
    private val scope: CoroutineScope,
) : Application.ActivityLifecycleCallbacks {
    private val mutable = MutableStateFlow<NativePlayer?>(null)
    internal val state = mutable.asStateFlow()
    internal val current: NativePlayer? get() = mutable.value?.takeIf { sessions.isCurrent(it.identity) }
    private var activities = 0
    private var serviceSession: MediaSession? = null

    fun start() {
        app.registerActivityLifecycleCallbacks(this)
        scope.launch {
            sessions.state.collect { auth ->
                val identity = (auth as? AuthState.Authenticated)?.identity
                if (mutable.value?.identity == identity) return@collect
                val retired = mutable.value
                mutable.value = null
                serviceSession = null
                retired?.close()
                app.stopService(Intent(app, PlaybackService::class.java))
                if (identity != null && sessions.isCurrent(identity)) {
                    val player = NativePlayer(app, identity, sessions, scope,
                        requestPlaybackStart = { ensureService(identity) })
                    mutable.value = player
                    if (activities > 0) ensureService(identity)
                    player.connect()
                }
            }
        }
    }

    /** Ordinary HTTP renewal preserves the socket and never replays a musical action. */
    fun foreground() {
        val player = current ?: return
        ensureService(player.identity)
        player.scope.launch {
            try {
                sessions.renew(player.identity)
                if (player.controller.state.value.connection ==
                    dev.spofify.mobile.core.playback.PlaybackConnectionState.DISCONNECTED) player.connect()
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { player.controller.disconnect() }
        }
    }

    internal fun serviceStarted(session: MediaSession) { serviceSession = session }
    internal fun serviceStopped(session: MediaSession?) {
        if (session != null && current?.mediaSession === session) {
            serviceSession = null
            current?.controller?.disconnect()
        }
    }

    internal fun backgroundStartBlocked(session: MediaSession?) {
        current?.takeIf { it.mediaSession === session }?.audio?.backgroundStartBlocked()
    }

    private fun ensureService(identity: AccountIdentity): Boolean {
        val player = current?.takeIf { it.identity == identity } ?: return false
        if (!sessions.isCurrent(identity)) return false
        if (serviceSession === player.mediaSession) return true
        if (activities == 0) return false
        return try {
            app.startService(Intent(app, PlaybackService::class.java))
            true
        } catch (_: IllegalStateException) { false }
    }

    override fun onActivityStarted(activity: Activity) {
        activities++
        current?.let { ensureService(it.identity) }
    }
    override fun onActivityStopped(activity: Activity) { activities = (activities - 1).coerceAtLeast(0) }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

@androidx.annotation.OptIn(UnstableApi::class)
internal class NativePlayer(
    app: Application,
    val identity: AccountIdentity,
    private val sessions: SessionRepository,
    parentScope: CoroutineScope,
    requestPlaybackStart: () -> Boolean,
) {
    val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private var commandTarget: PlaybackController? = null
    val audio = Media3AudioExecutor(app, sessions, identity, { commandTarget?.pause() }, requestPlaybackStart)
    val controller = PlaybackController(sessions.playbackTransport(identity), audio, scope, object : PlaybackClock {
        override fun monotonicMs(): Long = SystemClock.elapsedRealtime()
        override fun wallTimeMs(): Long = System.currentTimeMillis()
    }).also { commandTarget = it }
    private val deviceId: String
    val mediaSession = MediaSession.Builder(app, RoutingPlayer(audio.player, controller))
        .setSessionActivity(PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()

    init {
        val androidId = Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "${android.os.Build.BRAND}:${android.os.Build.MODEL}"
        val profile = "spofify-android-v1\n${identity.origin}\n${identity.accountId}\n$androidId"
        deviceId = "android-" + MessageDigest.getInstance("SHA-256").digest(profile.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        scope.launch {
            while (isActive) {
                delay(60_000)
                if (controller.state.value.connection !=
                    dev.spofify.mobile.core.playback.PlaybackConnectionState.READY) continue
                try { sessions.renew(identity) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { controller.disconnect() }
            }
        }
    }

    fun connect() {
        if (scope.isActive && sessions.isCurrent(identity) &&
            controller.state.value.connection != dev.spofify.mobile.core.playback.PlaybackConnectionState.CLOSED)
            controller.connect("Phone", deviceId)
    }

    suspend fun close() {
        controller.close()
        scope.coroutineContext.job.cancelAndJoin()
        mediaSession.release()
        audio.close()
    }
}
