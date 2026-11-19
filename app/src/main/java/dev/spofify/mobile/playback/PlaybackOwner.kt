package dev.spofify.mobile.playback

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import dev.spofify.mobile.core.auth.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Account/output lifetime stays stable across manual switching and return validation. */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackOwner(private val app: Application, private val sessions: SessionRepository, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow<NativePlayer?>(null)
    internal val state = mutable.asStateFlow()
    internal val current get() = mutable.value?.takeIf { sessions.isCurrent(it.identity) }
    private val storage = MusicStorage(app, scope)
    private val modeChanges = Mutex()
    private var started = false
    private var serviceSession: MediaSession? = null
    private var startingSession: MediaSession? = null
    private var stoppingSession: MediaSession? = null

    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(sessions.manualOffline, sessions.onlineGrant, sessions.state) { offline, grant, auth ->
                (auth as? AuthState.Authenticated)?.identity?.takeIf { !offline && grant == null && auth.notice == AuthNotice.CONNECTION_FAILED }
            }.distinctUntilChanged().collectLatest { account ->
                if (account == null) return@collectLatest
                var backoff = 1_000L
                while (sessions.isCurrent(account) && !sessions.manualOffline.value && sessions.onlineGrant.value == null) {
                    delay(kotlin.random.Random.nextLong(backoff / 2, backoff + 1))
                    if (!sessions.manualOffline.value && sessions.onlineGrant.value == null) sessions.refresh()
                    backoff = (backoff * 2).coerceAtMost(20_000L)
                }
            }
        }
        scope.launch {
            sessions.state.collect { auth ->
                val identity = (auth as? AuthState.Authenticated)?.identity
                if (mutable.value?.identity == identity) return@collect
                val retired = mutable.value
                mutable.value = null
                retired?.close()
                serviceSession = null; startingSession = null; stoppingSession = null
                app.stopService(Intent(app, PlaybackService::class.java))
                if (identity != null && sessions.isCurrent(identity)) {
                    val player = NativePlayer(app, identity, sessions, scope, storage,
                        requestPlaybackStart = { ensureService(identity) }, reconcile = { current?.let(::reconcileServiceFor) },
                        detachService = { current?.let(::stopServiceFor) })
                    mutable.value = player
                    player.start()
                }
            }
        }
    }

    /** The durable command is account-owned, not an online worker or destination coroutine. */
    internal suspend fun switch(identity: AccountIdentity, enabled: Boolean) = withContext(Dispatchers.Main.immediate) {
        modeChanges.withLock {
            val player = current?.takeIf { it.identity == identity } ?: return@withLock
            if (sessions.manualOffline.value == enabled) return@withLock
            if (!enabled) player.retireSource() // no sound, prepared media or queue survives return validation
            try {
                sessions.setManualOffline(enabled) { old ->
                    withContext(Dispatchers.Main.immediate) { if (current === player && old == identity) player.retireSource() }
                }
            } finally { if (current === player) player.synchronize() }
        }
    }

    fun foreground() { current?.foreground() }

    internal fun retainServiceAfterTaskRemoval(): Boolean {
        val p = current ?: return false
        val view = p.view ?: return false
        return when (view) {
            is PlayerPresentation.Online -> retainLocalPlaybackService(view.view, p.audio.facts(), p.audio.player.playWhenReady)
            is PlayerPresentation.Local -> (p.target.value as? AppPlayback.Local)?.controller?.hasPendingStart == true ||
                view.queue.desiredPlaying && p.audio.hasPlaybackAuthority() && !p.audio.facts().ended
        }
    }
    internal fun onTaskRemoved() {
        if (retainServiceAfterTaskRemoval()) return
        val p = current
        if (p == null) app.stopService(Intent(app, PlaybackService::class.java)) else stopServiceFor(p)
    }

    internal fun serviceStarted(session: MediaSession) {
        val p = current?.takeIf { it.mediaSession === session } ?: return
        serviceSession = session; startingSession = null; stoppingSession = null
        reconcileServiceFor(p)
    }
    internal fun serviceStopped(session: MediaSession?) {
        if (session == null) return
        if (serviceSession === session) serviceSession = null
        if (startingSession === session) startingSession = null
        if (stoppingSession === session) stoppingSession = null
        val p = current?.takeIf { it.mediaSession === session } ?: return
        val view = p.view ?: return
        val facts = p.audio.facts()
        if (view is PlayerPresentation.Online) handlePlaybackServiceLoss(view.view, facts, p.audio.player.playWhenReady,
            p.audio.hasPlaybackAuthority(), { ensureService(p.identity) }, p.audio::pause, p.audio::backgroundStartBlocked)
        else if (facts.isPlaying || p.audio.player.playWhenReady && !facts.ended) {
            if (!p.audio.hasPlaybackAuthority()) p.audio.pause() else if (!ensureService(p.identity)) p.audio.backgroundStartBlocked()
        }
    }
    internal fun backgroundStartBlocked(session: MediaSession?) {
        val p = current?.takeIf { session != null && it.mediaSession === session } ?: return
        val view = p.view ?: return
        if (view is PlayerPresentation.Online) handlePlaybackServiceStartRefusal(view.view, p.audio.hasPlaybackAuthority(),
            p.audio::pause, p.audio::backgroundStartBlocked)
        else if (p.audio.hasPlaybackAuthority()) p.audio.backgroundStartBlocked() else p.audio.pause()
        reconcileServiceFor(p)
    }
    private fun ensureService(identity: AccountIdentity): Boolean {
        val p = current?.takeIf { it.identity == identity } ?: return false
        val session = p.mediaSession ?: return false
        val view = p.view ?: return false
        if (!p.audio.hasPlaybackAuthority() || view is PlayerPresentation.Online && !view.view.isLocalOutputConnection()) return false
        if (stoppingSession !== session && (serviceSession === session || startingSession === session)) return true
        return try {
            ContextCompat.startForegroundService(app, Intent(app, PlaybackService::class.java))
            startingSession = session; stoppingSession = null
            true
        } catch (_: IllegalStateException) {
            Log.w("SpofifyPlayer", "Local playback service start refused"); false
        } catch (_: SecurityException) {
            Log.w("SpofifyPlayer", "Local playback service start denied"); false
        }
    }
    private fun reconcileServiceFor(p: NativePlayer) {
        val view = p.view ?: return
        val facts = p.audio.facts()
        if (view is PlayerPresentation.Online) {
            p.cache.observe(view.view, facts, p.audio.hasPlaybackAuthority())
            p.music.observeQueue(view.view)
            if (shouldStopPlaybackService(view.view, facts, p.audio.player.playWhenReady)) stopServiceFor(p)
        } else if (facts.error.isNotEmpty() && !facts.isPlaying && !p.audio.player.playWhenReady) stopServiceFor(p)
    }
    private fun stopServiceFor(player: NativePlayer) {
        val session = player.mediaSession ?: return
        if ((serviceSession !== session && startingSession !== session) || stoppingSession === session) return
        stoppingSession = session
        app.stopService(Intent(app, PlaybackService::class.java))
    }
}
