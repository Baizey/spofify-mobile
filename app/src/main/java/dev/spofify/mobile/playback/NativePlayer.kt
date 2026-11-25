package dev.spofify.mobile.playback

import android.annotation.SuppressLint
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import dev.spofify.mobile.MainActivity
import dev.spofify.mobile.core.auth.*
import dev.spofify.mobile.core.playback.*
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** One audio executor, command target and MediaSession for an authenticated account. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class NativePlayer(private val app: Application, val identity: AccountIdentity,
    private val sessions: SessionRepository, parentScope: CoroutineScope, storage: MusicStorage,
    requestPlaybackStart: suspend () -> Boolean, canContinuePlayback: () -> Boolean,
    private val acceptsNotificationControl: (MediaSession, Intent) -> Boolean,
    private val reconcile: () -> Unit, private val detachService: () -> Unit) {
    val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    val cache = AutomaticMusicCache(app, sessions, identity, storage, scope)
    val music = AccountMusic(app, identity, sessions, storage, cache, scope)
    private val mutable = MutableStateFlow<AppPlayback?>(null)
    val target = mutable.asStateFlow()
    private val playbackPrefs = app.getSharedPreferences("native-playback", Application.MODE_PRIVATE)
    private val fade = MutableStateFlow(playbackPrefs.getBoolean("crossfade", false))
    val crossfadeEnabled = fade.asStateFlow()
    fun setCrossfadeEnabled(enabled: Boolean) {
        playbackPrefs.edit { putBoolean("crossfade", enabled) }
        fade.value = enabled
        audio.setCrossfadeEnabled(enabled)
    }
    val audio: Media3AudioExecutor = Media3AudioExecutor(app, sessions, identity, cache, ::systemPause, requestPlaybackStart, canContinuePlayback, scope) { player ->
        mutable.value?.let { selected -> mediaSession?.setPlayer(routed(player, selected)) }
    }.apply { setCrossfadeEnabled(fade.value) }
    var mediaSession: MediaSession? = null
        private set
    private var reconnect: PlaybackReconnect? = null
    private var reconnectGrant: OnlineGrant? = null
    private enum class ReconnectPhase { ADMISSION_OR_UPGRADE, FIRST_SNAPSHOT }
    private class ReconnectDiagnostic {
        var phase = ReconnectPhase.ADMISSION_OR_UPGRADE
        var failure: AuthFailure.Kind? = null
    }
    private var reconnectDiagnostic = ReconnectDiagnostic()
    @Volatile private var retired = false
    private var manual = sessions.manualOffline.value
    private var maintenance: PlaybackSessionMaintenance? = null
    val view get() = target.value?.controller?.state?.value
    fun ownsNativeAudio(facts: AudioFacts = audio.facts()): Boolean = audio.hasPlaybackAuthority() &&
        facts.binding?.let { target.value?.controller?.matchesLocalAudio(it) } == true

    private val deviceId: String by lazy(::registeredDeviceId)
    @SuppressLint("HardwareIds")
    private fun registeredDeviceId(): String {
        val androidId = Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID)
            ?: playbackPrefs.getString("fallback-device-id", null)
            ?: UUID.randomUUID().toString().also { id -> playbackPrefs.edit { putString("fallback-device-id", id) } }
        val profile = "spofify-android-v1\n${identity.origin}\n${identity.accountId}\n$androidId"
        return "android-" + MessageDigest.getInstance("SHA-256").digest(profile.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun start() {
        if (mutable.value != null || retired || !scope.isActive || !sessions.isCurrent(identity)) return
        val current = { !retired && scope.isActive && sessions.isCurrent(identity) }
        val port = audio.port({ sessions.manualOffline.value || view?.let { it.offline || it.isolatedOutput } != false }, current)
        val transport = sessions.playbackTransport(identity)
        val boundTransport = PlaybackTransport { name, device ->
            val grant = reconnectGrant ?: throw CancellationException("No playback admission grant")
            val diagnostic = reconnectDiagnostic
            try {
                sessions.withOnlineGrant(grant) { transport.connect(name, device) }
                    .also { diagnostic.phase = ReconnectPhase.FIRST_SNAPSHOT }
            } catch (failure: AuthFailure) {
                diagnostic.failure = failure.kind
                throw failure
            }
        }
        val controller = AccountChannel(boundTransport, port, scope, object : PlaybackClock {
            override fun monotonicMs() = SystemClock.elapsedRealtime()
            override fun wallTimeMs() = System.currentTimeMillis()
        }, current, library = music.library, recordPlay = music.personal::recordLocal)
        val selected = AppPlayback(controller, music.indexReady) { current() && mutable.value?.controller === controller }
        controller.setOffline(manual)
        music.personal.attach(controller)
        mediaSession = MediaSession.Builder(app, routed(audio.player, selected)).setId(UUID.randomUUID().toString())
            .setCallback(object : MediaSession.Callback {
                override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent): Boolean =
                    // A legacy notification button is accepted only while this exact session has local output.
                    intent.hasExtra(PlaybackNotifications.CONTROL_TOKEN) && !acceptsNotificationControl(session, intent)
            })
            .setSessionActivity(PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()
        mutable.value = selected
        scope.launch {
            combine(sessions.onlineGrant, sessions.manualOffline) { grant, manual -> grant?.takeIf { !manual } }
                .distinctUntilChanged().collect {
                    synchronizeReconnect()
                    if (it?.identity != identity) audio.recheckSourceMode(isolated = true)
                }
        }
        maintenance = PlaybackSessionMaintenance(scope, current,
            { PlayerPresentation(controller.state.value).onlineReady && sessions.onlineGrant.value?.identity == identity },
            { sessions.renew(identity) }, { Log.w("SpofifyPlayer", "Session maintenance check failed") }).also { it.start() }
        scope.launch {
            controller.state.collect { state ->
                music.observePlayback(state, controller.socketGrant)
                audio.recheckSourceMode(isolated = state.offline || state.isolatedOutput || sessions.manualOffline.value || sessions.onlineGrant.value == null)
                reconcile()
            }
        }
        scope.launch { audio.observations.collect { reconcile() } }
    }

    private fun synchronizeReconnect(explicit: Boolean = false) {
        val controller = target.value?.controller ?: return
        val exact = sessions.onlineGrant.value?.takeIf { it.identity == identity && !manual && !sessions.manualOffline.value }
        if (reconnectGrant === exact) {
            if (explicit) reconnect?.start(explicit = true)
            return
        }
        reconnect?.close()
        reconnectGrant = exact
        reconnect = exact?.let { owner ->
            PlaybackReconnect(scope, controller.state,
                { !retired && sessions.isCurrent(identity) && !sessions.manualOffline.value && sessions.onlineGrant.value === owner },
                { reconnectDiagnostic = ReconnectDiagnostic(); controller.connect("Phone", deviceId, owner) }, { stillCurrent ->
                    if (stillCurrent()) {
                        val diagnostic = reconnectDiagnostic
                        Log.w("SpofifyPlayer", "Playback connection attempts exhausted: phase=${diagnostic.phase.name} " +
                            "status=${diagnostic.failure?.name ?: "NOT_ESTABLISHED"}")
                        sessions.confirmServiceAfterSocketFailures(owner)
                    }
                })
                .also { it.start(explicit) }
        }
    }

    /** Call after a durable manual transition, or to request a fresh batch from auto-offline. */
    fun applyMode(manual: Boolean) {
        val controller = target.value?.controller ?: return
        if (manual && this.manual) return
        this.manual = manual
        if (manual) audio.recheckSourceMode(isolated = true)
        controller.setOffline(manual)
        synchronizeReconnect(explicit = !manual)
    }

    private fun routed(player: androidx.media3.common.Player, selected: AppPlayback): RoutingPlayer = RoutingPlayer(player, selected) {
        audio.player === player && target.value === selected && mediaSession?.controllerForCurrentRequest != null
    }

    fun foreground() {
        music.verifyDownloads()
        music.personal.checkFreshness()
        maintenance?.refreshNow()
    }

    private fun systemPause() {
        if (view?.localOutput == true) target.value?.pause()
    }

    /** Retire the entire account only on logout/replacement, never on a socket or policy change. */
    suspend fun close() {
        val old = target.value
        detachService()
        retired = true
        mutable.value = null
        reconnect?.close(); reconnect = null
        maintenance?.close(); maintenance = null
        music.personal.attach(null)
        old?.controller?.close()
        audio.releaseOutput()
        mediaSession?.release(); mediaSession = null
        scope.coroutineContext.job.cancelAndJoin()
        audio.close()
    }
}
