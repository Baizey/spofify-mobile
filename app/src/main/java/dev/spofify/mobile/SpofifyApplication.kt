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
    private val logins: LoginStore = EncryptedLoginStore(context)) {
    private val sessions = SessionStoreOwner(store)
    private val restoreMutex = Mutex()
    private val preferences = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
    private val expired = MutableStateFlow(false)
    val sessionExpired = expired.asStateFlow()
    var client: SpofifyClient? = null
        private set
    var imageLoader: ImageLoader? = null
        private set
    var playbackCoordinator: PlaybackCoordinator? = null
        private set
    private var coordinationScope: CoroutineScope? = null
    private var retiringDisposal: Job? = null
    // Process-local rate limit only; never persistent device/session authority.
    private val registrationPacing = PlaybackRegistrationPacing()

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
        sessions.read()?.let { saved ->
            runCatching { configure(ServerAddress.parse(saved.server)) }
                .onFailure { sessions.clear() }
        }
    }

    @Synchronized
    fun configure(server: ServerAddress): SpofifyClient {
        val predecessorDisposal = endCoordination()
        PlaybackService.stopActivePlayback()
        context.stopService(Intent(context, PlaybackService::class.java))
        imageLoader?.shutdown()
        val lease = sessions.acquire() // Retired clients can no longer clear or install persisted state.
        client?.onUnauthorized = null
        client?.httpClient?.dispatcher?.cancelAll()
        client?.clearSession()
        expired.value = false
        val next = SpofifyClient(server, lease)
        next.onUnauthorized = {
            // Invalidate pending continuations synchronously on the auth callback thread.
            // Player mutation remains confined to its main looper below.
            if (client === next && next.currentSession() == null) PlaybackRequests.invalidate()
            // Range/image requests can fail without an Activity. Stop buffered audio too;
            // stopService alone cannot destroy a still-bound MediaSessionService.
            Handler(Looper.getMainLooper()).post {
                if (client === next && next.currentSession() == null) {
                    playbackCoordinator?.authLost()
                    PlaybackService.stopActivePlayback()
                    context.stopService(Intent(context, PlaybackService::class.java))
                    expired.value = true
                }
            }
        }
        client = next
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
        val active = client ?: return@withLock null
        // Reattaching a screen must not null out a session used by background audio.
        active.currentSession()?.let { return@withLock it }
        val restored = active.restore()
        if (client === active) restored else null
    }

    @Synchronized
    fun clear(expected: SpofifyClient? = client) {
        if (client !== expected) return
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
