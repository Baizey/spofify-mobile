package dev.spofify.mobile

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.spofify.mobile.core.ServerAddress
import dev.spofify.mobile.core.SpofifyClient
import dev.spofify.mobile.core.Session
import dev.spofify.mobile.core.SessionStore
import dev.spofify.mobile.core.SessionStoreOwner
import dev.spofify.mobile.core.AuthenticationChange
import dev.spofify.mobile.core.AuthenticationChangeKind
import dev.spofify.mobile.playback.cache.SongCache
import dev.spofify.mobile.playback.cache.AutoCacheSettingsStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dev.spofify.mobile.playback.PlaybackService
import dev.spofify.mobile.playback.Media3PlaybackEndpoint
import dev.spofify.mobile.core.PlaybackDevicesApi
import dev.spofify.mobile.feature.devices.PlaybackCoordinator
import dev.spofify.mobile.feature.devices.PlaybackRegistrationPacing
import dev.spofify.mobile.feature.devices.PlaybackDeviceIdentityStore
import dev.spofify.mobile.playback.PlaybackRequests
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SpofifyApplication : Application() {
    lateinit var graph: AppGraph
        private set
    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

class AppGraph(private val context: Context, store: SessionStore = EncryptedSessionStore(context),
    private val logins: LoginStore = EncryptedLoginStore(context),
    audioCacheLimits: SongCache.Limits? = null,
    private val authenticationTimeMillis: () -> Long = System::currentTimeMillis) {
    private val sessions = SessionStoreOwner(store)
    private val restoreMutex = Mutex()
    private val preferences = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
    private val expired = MutableStateFlow(false)
    val sessionExpired = expired.asStateFlow()
    private val reconnectRequired = MutableStateFlow(false)
    val sessionNeedsReconnect = reconnectRequired.asStateFlow()
    @Volatile var client: SpofifyClient? = null
        private set
    var imageLoader: ImageLoader? = null
        private set
    var playbackCoordinator: PlaybackCoordinator? = null
        private set
    private var coordinationScope: CoroutineScope? = null
    private var retiringDisposal: Job? = null
    // Process-local rate limit only; never persistent device/session authority.
    private val registrationPacing = PlaybackRegistrationPacing()

    // Audio bytes confer no authority. Construction is dormant: only a server-validated
    // authentication event can bind the origin/account namespace to a live session.
    internal val songCache = SongCache(context.cacheDir, audioCacheLimits ?: SongCache.Limits(),
        settingsStore = if (audioCacheLimits == null) AutoCacheSettingsStore(context.filesDir) else null)
    @Volatile private var audioCache: SongCache.SessionCache? = null
    private val cacheAuthenticationLock = Any()
    @Volatile private var cacheSource: SpofifyClient? = null
    private var authenticationRevision = -1L
    private var hadValidatedSession = false // Protected by cacheAuthenticationLock.

    // Never borrow the graph monitor: configure/clear hold it while releasing Media3,
    // whose loading thread can synchronously observe cookie expiry during a read.
    private fun authenticationChanged(source: SpofifyClient, change: AuthenticationChange) = synchronized(cacheAuthenticationLock) {
        if (cacheSource !== source || client !== source || change.revision <= authenticationRevision) return@synchronized
        authenticationRevision = change.revision
        when (change.kind) {
            AuthenticationChangeKind.VALIDATED -> {
                val identity = change.identity ?: return@synchronized
                if (source.validatedSessionIdentity() != identity) return@synchronized
                hadValidatedSession = true
                reconnectRequired.value = false
                audioCache = runCatching { songCache.bind(identity.origin, identity.accountId) }.getOrNull()
            }
            AuthenticationChangeKind.DORMANT -> {
                if (hadValidatedSession) reconnectRequired.value = true
                audioCache?.suspend()
                audioCache = null
                songCache.fence()
                // Dormant/local-deadline uncertainty is not logout, but cannot leave
                // buffered output or an old receiver binding live until revalidation.
                revokePlayback(source, signedOut = false)
            }
            AuthenticationChangeKind.RESET -> {
                hadValidatedSession = false
                reconnectRequired.value = false
                // A cold explicit sign-in has no prior runtime authority. Leave dormant
                // bytes alone until its immutable identity has been validated.
                audioCache?.retire()
                audioCache = null
            }
            AuthenticationChangeKind.INVALIDATED, AuthenticationChangeKind.EXPIRED -> {
                hadValidatedSession = false
                reconnectRequired.value = false
                audioCache = null
                songCache.retire()
                if (change.kind == AuthenticationChangeKind.EXPIRED) {
                    source.httpClient.dispatcher.cancelAll()
                    revokePlayback(source)
                }
            }
        }
    }

    private fun revokePlayback(source: SpofifyClient, signedOut: Boolean = true) {
        // Expiry is observed on audio/cache reads as well as API responses. Fence pending
        // starts synchronously; only player mutation is posted. The 401 callback must not
        // cancel its own call before the existing API error classifier sees the response.
        // onUnauthorized runs under the client's auth-generation lock: do not borrow
        // the cache lock here (cache authentication fencing also reads client authority).
        if (client !== source || source.currentSession() != null) return
        PlaybackRequests.invalidate()
        Handler(Looper.getMainLooper()).post {
            if (client === source && source.currentSession() == null) {
                playbackCoordinator?.authLost()
                PlaybackService.stopActivePlayback()
                context.stopService(Intent(context, PlaybackService::class.java))
                if (signedOut) expired.value = true
            }
        }
    }

    internal fun audioCache(source: SpofifyClient, expected: Session): SongCache.SessionCache? =
        if (client === source && source.hasValidSession(expected)) audioCache else null

    private fun endCoordination(): Job? {
        PlaybackService.onServiceActiveChanged = null
        retiringDisposal = playbackCoordinator?.close() ?: retiringDisposal
        playbackCoordinator = null
        coordinationScope?.cancel()
        coordinationScope = null
        return retiringDisposal
    }
    val lastServer: String get() = preferences.getString("server", "") ?: ""
    // Compatibility for the legacy signed-in account UI. Forgetting is not a future save opt-out.
    val rememberLoginEnabled: Boolean get() = true

    @Synchronized
    fun rememberedLogin(): RememberedLogin? {
        val saved = logins.read() ?: return null
        val valid = runCatching { ServerAddress.parse(saved.server).origin == saved.server }.getOrDefault(false) &&
            saved.username.isNotBlank() && saved.password.isNotEmpty()
        if (!valid) { logins.write(null); return null }
        return saved
    }

    @Synchronized
    fun setRememberLogin(enabled: Boolean) {
        if (!enabled) logins.write(null)
        // Retain this compatibility API while the account dialog still calls Forget.
        // A legacy false flag must never suppress the next successful automatic save.
        preferences.edit().remove("remember_login").commit()
    }

    @Synchronized
    fun saveLogin(expected: SpofifyClient, username: String, password: String,
        expectedSession: Session? = expected.currentSession()) {
        check(client === expected && expectedSession != null && expected.currentSession() === expectedSession &&
            username == expectedSession.username) { "The sign-in session changed" }
        logins.write(RememberedLogin(expected.server.origin, username, password))
    }

    init {
        // Ignore even an old false value. Best-effort removal cannot break manual authentication,
        // and must not recreate data already deleted through the former opt-out action.
        runCatching { preferences.edit().remove("remember_login").commit() }
        var unavailable = false
        val saved = try { sessions.read() } catch (_: Exception) { unavailable = true; null }
        val origin = saved?.server ?: if (unavailable) lastServer else ""
        if (origin.isNotEmpty()) runCatching { configure(ServerAddress.parse(origin)) }
        // A transient read/configuration failure must not delete encrypted auth.
        // restore() retries safely, without granting authority from this preference.
    }

    @Synchronized
    fun configure(server: ServerAddress): SpofifyClient {
        client?.onAuthenticationChanged = null
        synchronized(cacheAuthenticationLock) {
            cacheSource = null
            hadValidatedSession = false
            reconnectRequired.value = false
            audioCache?.suspend()
            audioCache = null
            songCache.fence()
            if (lastServer.isNotEmpty() && lastServer != server.origin) songCache.retire()
        }
        val predecessorDisposal = endCoordination()
        PlaybackService.stopActivePlayback()
        context.stopService(Intent(context, PlaybackService::class.java))
        imageLoader?.shutdown()
        val lease = sessions.acquire() // Retired clients can no longer clear or install persisted state.
        client?.onUnauthorized = null
        client?.httpClient?.dispatcher?.cancelAll()
        client?.clearSession()
        expired.value = false
        val next = SpofifyClient(server, lease, authenticationTimeMillis)
        next.onAuthenticationChanged = { authenticationChanged(next, it) }
        // Range/image requests can fail without an Activity or a player request. Stop
        // buffered audio too; stopService alone cannot destroy a still-bound service.
        next.onUnauthorized = { revokePlayback(next) }
        synchronized(cacheAuthenticationLock) {
            client = next
            cacheSource = next
            authenticationRevision = -1L
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val coordinator = PlaybackCoordinator(PlaybackDevicesApi(next), Media3PlaybackEndpoint(), scope,
            identity = { next.currentSession()?.let { PlaybackDeviceIdentityStore(context, next.server, it.accountId) } },
            mediaEventUptimeMs = { android.os.SystemClock.uptimeMillis() },
            predecessorDisposal = predecessorDisposal, registrationPacing = registrationPacing)
        coordinationScope = scope
        playbackCoordinator = coordinator
        PlaybackService.onServiceActiveChanged = { active ->
            if (client === next && playbackCoordinator === coordinator) coordinator.setServiceActive(active)
        }
        // One application-owned coordinator survives screen/activity reattachment. Only local
        // installation/name metadata persists; registration and output authority never do.
        imageLoader = ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { next.httpClient })) }
            .diskCache(null).memoryCache(null).build()
        preferences.edit().putString("server", server.origin).apply()
        return next
    }

    suspend fun restore(): Session? = restoreMutex.withLock {
        val active = client ?: sessions.read()?.let { configure(ServerAddress.parse(it.server)) }
            ?: return@withLock null
        // Reattaching a screen must not null out a session used by background audio.
        active.currentSession()?.let { return@withLock it }
        val restored = active.restore()
        if (client === active) restored else null
    }

    @Synchronized
    fun clear(expected: SpofifyClient? = client) {
        if (client !== expected) return
        client?.onAuthenticationChanged = null
        synchronized(cacheAuthenticationLock) {
            cacheSource = null
            hadValidatedSession = false
            reconnectRequired.value = false
            audioCache = null
            songCache.retire()
        }
        endCoordination()
        PlaybackService.stopActivePlayback()
        context.stopService(Intent(context, PlaybackService::class.java))
        sessions.clear()
        client?.onUnauthorized = null
        client?.httpClient?.dispatcher?.cancelAll()
        client?.clearSession()
        client = null
        imageLoader?.shutdown()
        imageLoader = null
        expired.value = false
    }
}
