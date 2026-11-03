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

class AppGraph(private val context: Context, store: SessionStore = EncryptedSessionStore(context)) {
    private val sessions = SessionStoreOwner(store)
    private val restoreMutex = Mutex()
    private val preferences = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
    private val expired = MutableStateFlow(false)
    val sessionExpired = expired.asStateFlow()
    var client: SpofifyClient? = null
        private set
    var imageLoader: ImageLoader? = null
        private set
    val lastServer: String get() = preferences.getString("server", "") ?: ""

    init {
        sessions.read()?.let { saved ->
            runCatching { configure(ServerAddress.parse(saved.server)) }
                .onFailure { sessions.clear() }
        }
    }

    fun configure(server: ServerAddress): SpofifyClient {
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
            // Range/image requests can fail without an Activity. Stop buffered audio too;
            // stopService alone cannot destroy a still-bound MediaSessionService.
            Handler(Looper.getMainLooper()).post {
                if (client === next) {
                    PlaybackService.stopActivePlayback()
                    context.stopService(Intent(context, PlaybackService::class.java))
                    expired.value = true
                }
            }
        }
        client = next
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

    fun clear(expected: SpofifyClient? = client) {
        if (client !== expected) return
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
