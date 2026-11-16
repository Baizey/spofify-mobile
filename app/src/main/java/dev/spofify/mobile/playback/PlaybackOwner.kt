package dev.spofify.mobile.playback

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import dev.spofify.mobile.MainActivity
import dev.spofify.mobile.core.auth.AccountIdentity
import dev.spofify.mobile.core.auth.AuthFailure
import dev.spofify.mobile.core.auth.AuthState
import dev.spofify.mobile.core.auth.SessionRepository
import dev.spofify.mobile.core.playback.PlaybackClock
import dev.spofify.mobile.core.playback.PlaybackConnectionState
import dev.spofify.mobile.core.playback.PlaybackController
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

/** The Application owns account/socket lifetime. The service owns only local output presentation. */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackOwner(
    private val app: Application,
    private val sessions: SessionRepository,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow<NativePlayer?>(null)
    internal val state = mutable.asStateFlow()
    internal val current: NativePlayer? get() = mutable.value?.takeIf { sessions.isCurrent(it.identity) }
    private var started = false
    private var serviceSession: MediaSession? = null
    private var startingSession: MediaSession? = null
    private var stoppingSession: MediaSession? = null

    fun start() {
        if (started) return
        started = true
        scope.launch {
            sessions.state.collect { auth ->
                val identity = (auth as? AuthState.Authenticated)?.identity
                if (mutable.value?.identity == identity) return@collect
                val retired = mutable.value
                mutable.value = null
                serviceSession = null
                startingSession = null
                stoppingSession = null
                retired?.close()
                app.stopService(Intent(app, PlaybackService::class.java))
                if (identity != null && sessions.isCurrent(identity)) {
                    val player = NativePlayer(app, identity, sessions, scope,
                        requestPlaybackStart = { ensureService(identity) })
                    mutable.value = player
                    player.scope.launch {
                        player.controller.state.collect { reconcileServiceFor(player) }
                    }
                    player.scope.launch {
                        // A failed native start must cancel its FGS deadline without
                        // waiting for a server round trip. Sample current facts rather
                        // than letting a queued observation retire a newer operation.
                        player.audio.observations.collect { reconcileServiceFor(player) }
                    }
                    player.connect()
                }
            }
        }
    }

    /** Expedite admission/HTTP maintenance, without resetting READY or replaying an intent. */
    fun foreground() { current?.foreground() }

    internal fun retainServiceAfterTaskRemoval(): Boolean {
        val player = current ?: return false
        return retainLocalPlaybackService(player.controller.state.value, player.audio.facts(), player.audio.player.playWhenReady)
    }

    internal fun onTaskRemoved() {
        if (retainServiceAfterTaskRemoval()) return
        val player = current
        if (player == null) app.stopService(Intent(app, PlaybackService::class.java))
        else stopServiceFor(player)
    }

    internal fun serviceStarted(session: MediaSession) {
        val player = current?.takeIf { it.mediaSession === session } ?: return
        serviceSession = session
        startingSession = null
        stoppingSession = null
        reconcileServiceFor(player)
    }

    internal fun serviceStopped(session: MediaSession?) {
        if (session == null) return
        if (serviceSession === session) serviceSession = null
        if (startingSession === session) startingSession = null
        if (stoppingSession === session) stoppingSession = null
        val player = current?.takeIf { it.mediaSession === session } ?: return
        Log.i("SpofifyPlayer", "Local media service stopped")
        handlePlaybackServiceLoss(player.controller.state.value, player.audio.facts(), player.audio.player.playWhenReady,
            hasPlaybackAuthority = player.audio.hasPlaybackAuthority(), restartLocalService = { ensureService(player.identity) },
            pauseNative = player.audio::pause, reportLocalBlocked = player.audio::backgroundStartBlocked)
        // Accepted reattachment leaves native playback untouched. Cleanup is neither
        // a socket failure nor a reason to replay resume/select/execute.
    }

    internal fun backgroundStartBlocked(session: MediaSession?) {
        val player = current?.takeIf { it.mediaSession === session } ?: return
        handlePlaybackServiceStartRefusal(player.controller.state.value, player.audio.hasPlaybackAuthority(),
            pauseNative = player.audio::pause, reportLocalBlocked = player.audio::backgroundStartBlocked)
        reconcileServiceFor(player)
    }

    /** Called only by an authorized desired-playing native execution, immediately before play. */
    private fun ensureService(identity: AccountIdentity): Boolean {
        val player = current?.takeIf { it.identity == identity } ?: return false
        if (!sessions.isCurrent(identity) || !player.controller.state.value.isLocalOutputConnection()) return false
        val session = player.mediaSession
        if (stoppingSession !== session && (serviceSession === session || startingSession === session)) return true
        // Visibility is not authority, nor a reliable predictor of Android's actual
        // foreground-start exemptions. Attempt the local start and handle real refusal.
        return try {
            ContextCompat.startForegroundService(app, Intent(app, PlaybackService::class.java))
            startingSession = session
            stoppingSession = null
            true
        } catch (_: IllegalStateException) {
            Log.w("SpofifyPlayer", "Local playback service start refused")
            false
        } catch (_: SecurityException) {
            Log.w("SpofifyPlayer", "Local playback service start denied")
            false
        }
    }

    private fun reconcileServiceFor(player: NativePlayer) {
        if (current !== player) return
        if (shouldStopPlaybackService(player.controller.state.value, player.audio.facts(), player.audio.player.playWhenReady))
            stopServiceFor(player)
    }

    private fun stopServiceFor(player: NativePlayer) {
        if (current !== player) return
        val session = player.mediaSession
        if ((serviceSession !== session && startingSession !== session) || stoppingSession === session) return
        stoppingSession = session
        app.stopService(Intent(app, PlaybackService::class.java))
    }
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
    val audio = Media3AudioExecutor(app, sessions, identity, {
        commandTarget?.let { if (it.isLocalSelectedConnection()) it.pause() }
    }, requestPlaybackStart)
    val controller = PlaybackController(sessions.playbackTransport(identity), audio, scope, object : PlaybackClock {
        override fun monotonicMs(): Long = SystemClock.elapsedRealtime()
        override fun wallTimeMs(): Long = System.currentTimeMillis()
    }).also { commandTarget = it }
    private var requestSession: MediaSession? = null
    val mediaSession = MediaSession.Builder(app, RoutingPlayer(audio.player, controller) {
        requestSession?.controllerForCurrentRequest != null
    }).setSessionActivity(PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java)
        .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build().also { requestSession = it }

    private val deviceId: String = run {
        val androidId = Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "${android.os.Build.BRAND}:${android.os.Build.MODEL}"
        val profile = "spofify-android-v1\n${identity.origin}\n${identity.accountId}\n$androidId"
        "android-" + MessageDigest.getInstance("SHA-256").digest(profile.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private val reconnect = PlaybackReconnect(scope, controller.state, { sessions.isCurrent(identity) },
        connectFresh = { controller.connect("Phone", deviceId) }, nowMs = SystemClock::elapsedRealtime)
    private val maintenance = PlaybackSessionMaintenance(scope, { sessions.isCurrent(identity) },
        isConnected = { controller.state.value.connection == PlaybackConnectionState.READY },
        renew = { sessions.renew(identity) }, onFailure = { failure ->
            Log.w("SpofifyPlayer", "Session maintenance check failed: ${(failure as? AuthFailure)?.kind ?: "unexpected"}")
        })

    init {
        scope.launch {
            controller.state.map { it.commandFailure }.distinctUntilChanged().filterNotNull().collect { failure ->
                Log.w("SpofifyPlayer", failure.diagnosticText())
            }
        }
    }

    fun connect() {
        if (!scope.isActive || !sessions.isCurrent(identity)) return
        maintenance.start()
        reconnect.expedite()
    }

    fun foreground() {
        connect()
        maintenance.refreshNow()
    }

    suspend fun close() {
        requestSession = null // Release/cleanup must never impersonate an external controller request.
        reconnect.close()
        maintenance.close()
        controller.close()
        scope.coroutineContext.job.cancelAndJoin()
        mediaSession.release()
        audio.close()
    }
}
