package dev.spofify.mobile.playback

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.media3.common.Player
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
    private var attachment: Attachment? = null

    private inner class Attachment(
        val session: MediaSession, var recoveryUsed: Boolean, var admission: CompletableDeferred<Boolean>?,
    ) : ServiceConnection {
        var service: PlaybackService? = null
        var recoveryTimeout: Job? = null

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (attachment !== this || service != null) return
            val instance = (binder as? PlaybackService.LocalBinder)?.service
            val p = eligiblePlayer(session)
            // Native work can be retired between bind() and this callback. Never attach whatever
            // account happens to be current; the binding belongs to this exact session and work.
            if (instance == null) { loseAttachment(this); return }
            if (p == null || admission == null && !p.hasPendingNativePlayback()) {
                retireAttachment(this)
                return
            }
            service = instance
            instance.attach(session)
            reconcileServiceFor(p)
            if (attachment !== this) return
            if (Build.VERSION.SDK_INT >= 35) {
                // First play and one-shot playing recovery cannot depend on the state of Media3's
                // asynchronous notification controller for this newly attached player.
                instance.promoteBoundPlayback(session)
            } else {
                // A trusted controller may already have registered this MediaSession with Media3.
                // In that case attach() won't cause a fresh notification-controller callback.
                instance.triggerNotificationUpdate()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) = loseAttachment(this)
        override fun onBindingDied(name: ComponentName) = loseAttachment(this)
        override fun onNullBinding(name: ComponentName) = loseAttachment(this)
    }

    fun start() {
        if (started) return
        started = true
        scope.launch {
            network(app).distinctUntilChanged().collect { hint ->
                if (hint != null) sessions.recoverUnavailable()
            }
        }
        scope.launch {
            sessions.onlineValidation.collectLatest { validation ->
                if (validation == OnlineValidation.UNAVAILABLE) while (isActive) {
                    delay(30_000)
                    sessions.recoverUnavailable()
                }
            }
        }
        scope.launch {
            sessions.state.collect { auth ->
                val identity = (auth as? AuthState.Authenticated)?.identity
                if (mutable.value?.identity == identity) return@collect
                val retired = mutable.value
                mutable.value = null
                val retiredAttachment = attachment
                if (retiredAttachment != null) retireAttachment(retiredAttachment)
                else app.stopService(Intent(app, PlaybackService::class.java))
                retired?.close()
                if (identity != null && sessions.isCurrent(identity)) {
                    val player = NativePlayer(app, identity, sessions, scope, storage,
                        requestPlaybackStart = { admitPlaybackStart(identity) },
                        canContinuePlayback = { canContinuePlayback(identity) }, acceptsNotificationControl = ::acceptsNotificationControl,
                        reconcile = { current?.let(::reconcileServiceFor) },
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
            val isolated = player.view?.let { it.offline || it.superseded } == true
            if (sessions.manualOffline.value == enabled && (enabled || !isolated)) return@withLock
            setMode(player, enabled)
        }
    }

    private suspend fun setMode(player: NativePlayer, enabled: Boolean) {
        // Failure leaves the previous durable policy intact. A committed change must publish
        // even if the screen's caller went away while persistence was running.
        withContext(NonCancellable) {
            sessions.setManualOffline(enabled) { owner ->
                withContext(Dispatchers.Main.immediate) {
                    if (current === player && owner == player.identity) player.applyMode(manual = true)
                }
            }
            if (current === player) player.applyMode(enabled)
        }
    }

    fun foreground() { sessions.recoverUnavailable(); current?.foreground() }

    /** Installation can stop this process. Protect local output and in-flight starts, not remote playback. */
    internal fun blocksAppUpdate(): Boolean {
        val player = current ?: return false
        return attachment?.let { it.session === player.mediaSession && it.admission != null } == true ||
            player.view?.let { it.localOutput && it.wantsPause } == true || player.hasPendingNativePlayback()
    }

    internal fun retainServiceAfterTaskRemoval(): Boolean {
        val p = current ?: return false
        val view = p.view ?: return false
        return retainLocalPlaybackService(view, p.audio.facts(), p.audio.player.playWhenReady)
    }
    internal fun onTaskRemoved(service: PlaybackService) {
        val request = attachment?.takeIf { it.service === service }
        if (request != null && retainServiceAfterTaskRemoval()) return
        if (request == null) service.stopSelf() else retireAttachment(request)
    }

    internal fun serviceStopped(service: PlaybackService) {
        attachment?.takeIf { it.service === service }?.let { loseAttachment(it, destroyed = true) }
    }

    private fun loseAttachment(request: Attachment, destroyed: Boolean = false, retry: Boolean = true) {
        if (attachment !== request) return
        val pending = request.admission
        val canRecover = retry && !request.recoveryUsed && eligiblePlayer(request.session) != null
        val canMoveAdmission = pending != null && !pending.isCompleted && canRecover
        retireAttachment(request, stopService = !destroyed, settleAdmission = !canMoveAdmission)
        // Move the still-authorized ticket, not the execute or any assumed native play state.
        if (pending != null) {
            if (canMoveAdmission && bindService(request.session, afterLoss = true, admission = pending) == null) {
                pending.complete(false)
            }
            return
        }
        val p = current?.takeIf { it.mediaSession === request.session } ?: return
        val view = p.view ?: return
        val facts = p.audio.facts()
        handlePlaybackServiceLoss(view, facts, p.audio.player.playWhenReady,
            p.audio.hasPlaybackAuthority(), { canRecover && recoverPlayingSession(request.session) },
            p.audio::pause, p.audio::backgroundStartBlocked)
    }

    private fun recoverPlayingSession(session: MediaSession): Boolean {
        val restarted = bindService(session, afterLoss = true) ?: return false
        restarted.recoveryTimeout = scope.launch {
            delay(PLAYBACK_SERVICE_ADMISSION_TIMEOUT_MS)
            if (attachment === restarted && restarted.admission == null && restarted.service?.hasMediaForeground() != true) {
                loseAttachment(restarted) // The recovery already used its one attempt.
            }
        }
        return true
    }

    // Capture the native operation as well as the binding; a notification callback is not a
    // command to stop or recover whatever operation is using the same service by delivery time.
    internal fun notificationLossCallback(service: PlaybackService, session: MediaSession): ((retry: Boolean) -> Unit)? {
        val request = attachment?.takeIf { it.service === service && it.session === session } ?: return null
        val p = current?.takeIf { it.mediaSession === session } ?: return null
        val operation = p.audio.facts().binding ?: return null
        return { retry ->
            if (attachment === request && current === p && p.audio.facts().binding == operation &&
                !service.hasMediaForeground() && (request.admission != null || p.hasPendingNativePlayback())) {
                loseAttachment(request, retry = retry)
            }
        }
    }
    private fun NativePlayer.hasPendingNativePlayback(): Boolean =
        hasPendingNativePlayback(audio.facts(), audio.player.playWhenReady)

    private fun localPlayer(session: MediaSession): NativePlayer? =
        current?.takeIf { it.mediaSession === session && it.view?.localOutput == true }

    private fun eligiblePlayer(session: MediaSession): NativePlayer? = localPlayer(session)?.takeIf { it.audio.hasPlaybackAuthority() }

    internal fun ownsLocalNotification(service: PlaybackService, session: MediaSession): Boolean =
        attachment?.let { it.service === service && it.session === session } == true && localPlayer(session) != null

    internal fun acceptsNotificationControl(session: MediaSession, intent: Intent): Boolean {
        val service = attachment?.service ?: return false
        return ownsLocalNotification(service, session) && service.matchesNotificationControl(session, intent)
    }

    private suspend fun admitPlaybackStart(identity: AccountIdentity): Boolean {
        val session = current?.takeIf { it.identity == identity }?.mediaSession ?: return false
        if (eligiblePlayer(session) == null) {
            Log.w("SpofifyPlayer", "Playback admission failed: output is no longer eligible")
            return false
        }
        val request = attachment?.takeIf { it.session === session } ?: bindService(session) ?: run {
            Log.w("SpofifyPlayer", "Playback admission failed: local binding refused")
            return false
        }
        // A new native execute supersedes recovery of the outgoing operation, not its binding.
        request.recoveryTimeout?.cancel()
        request.recoveryTimeout = null
        request.recoveryUsed = false
        // Android 15+ may deny ExoPlayer's focus request unless the app is top or already an FGS.
        // Do not optimistically set playWhenReady just to coax Media3 into foreground.
        if (Build.VERSION.SDK_INT < 35) return true
        val pending = CompletableDeferred<Boolean>()
        val previous = request.admission
        request.admission = pending
        previous?.complete(false)
        var admitted = false
        try {
            request.service?.promoteBoundPlayback(session)
            admitted = awaitPlaybackServiceAdmission(pending) {
                // A recovery may move this exact ticket to a new instance. A retired request or a
                // different play may not consume the old success, even on the same MediaSession.
                attachment?.let { live -> live.session === session && live.admission === pending &&
                    live.service?.hasMediaForeground() == true && eligiblePlayer(session) != null } == true
            }
            if (!admitted) {
                val live = attachment?.takeIf { it.admission === pending }
                val stage = when {
                    live == null -> "retired-binding"
                    pending.isCompleted -> "promotion-refused-or-lost"
                    live.service == null -> "bind-timeout"
                    else -> "promotion-timeout"
                }
                Log.w("SpofifyPlayer", "Playback admission failed: $stage api=${Build.VERSION.SDK_INT}")
            }
            return admitted
        } finally {
            attachment?.takeIf { it.admission === pending }?.let { live ->
                live.admission = null
                if (!admitted) retireAttachment(live)
            }
        }
    }

    private fun canContinuePlayback(identity: AccountIdentity): Boolean {
        val session = current?.takeIf { it.identity == identity }?.mediaSession ?: return false
        val request = attachment?.takeIf { it.session === session } ?: return false
        return eligiblePlayer(session) != null && (Build.VERSION.SDK_INT < 35 || request.service?.hasMediaForeground() == true)
    }

    internal fun notificationAdmission(service: PlaybackService, session: MediaSession): CompletableDeferred<Boolean>? {
        val request = attachment?.takeIf { it.service === service && it.session === session } ?: return null
        val player = eligiblePlayer(session)?.audio?.player ?: return null
        // Keep the paused promotion while a successful acknowledgment is waiting to be consumed.
        return request.admission?.takeIf { player.isReadyForForeground() && (it.isActive || service.hasMediaForeground()) }
    }

    private fun Player.isReadyForForeground(): Boolean = playerError == null &&
        (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING)

    internal fun shouldRunInForeground(service: PlaybackService, session: MediaSession, media3Requested: Boolean): Boolean {
        val p = localPlayer(session) ?: return false
        val view = p.view ?: return false
        val facts = p.audio.facts()
        val playWhenReady = p.audio.player.playWhenReady
        // Media3's notification controller can briefly lag the native play immediately after
        // admission. Keep foreground for actual authorized work, not for the controller's echo.
        if (p.audio.hasPlaybackAuthority() && p.audio.player.isReadyForForeground() &&
            hasPendingNativePlayback(facts, playWhenReady)) return true
        // Media3 owns the grace period on an already foreground output between tracks or after pause.
        if (!media3Requested || !service.hasMediaForeground()) return false
        return !shouldStopPlaybackService(view, facts, playWhenReady)
    }

    private fun bindService(
        session: MediaSession, afterLoss: Boolean = false, admission: CompletableDeferred<Boolean>? = null,
    ): Attachment? {
        attachment?.let(::retireAttachment)
        val request = Attachment(session, afterLoss, admission)
        attachment = request
        // Binding creates no Android foreground-start obligation. On Android 15+ we promote
        // the bound instance immediately with a real notification, before requesting audio focus.
        val bound = try {
            app.bindService(Intent(app, PlaybackService::class.java).setAction(PlaybackService.ATTACH_LOCAL),
                request, Context.BIND_AUTO_CREATE)
        } catch (e: RuntimeException) {
            if (e !is IllegalStateException && e !is SecurityException) throw e
            Log.w("SpofifyPlayer", "Local playback service binding refused: ${e.javaClass.simpleName}")
            false
        }
        if (!bound) {
            attachment = null
            // Even a false bind result can register the connection on the caller side.
            unbind(request)
        }
        return request.takeIf { bound }
    }

    private fun unbind(request: Attachment) {
        try { app.unbindService(request) } catch (_: IllegalArgumentException) { /* Never registered. */ }
    }

    private fun retireAttachment(request: Attachment, stopService: Boolean = true, settleAdmission: Boolean = true) {
        if (attachment !== request) return
        attachment = null // Clear before callbacks or any asynchronous destruction of this instance.
        if (stopService) request.service?.let { service ->
            service.detach(request.session)
            service.stopSelf()
        }
        unbind(request)
        request.recoveryTimeout?.cancel()
        if (settleAdmission) request.admission?.complete(false)
    }
    private fun reconcileServiceFor(p: NativePlayer) {
        val view = p.view ?: return
        val facts = p.audio.facts()
        p.cache.observe(view, facts, p.ownsNativeAudio(facts))
        if (shouldStopPlaybackService(view, facts, p.audio.player.playWhenReady)) stopServiceFor(p)
        attachment?.takeIf { it.session === p.mediaSession }?.let { it.service?.reconcileBoundPlayer(it.session) }
    }
    private fun stopServiceFor(player: NativePlayer) {
        attachment?.takeIf { it.session === player.mediaSession }?.let(::retireAttachment)
    }
}
