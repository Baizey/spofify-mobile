package dev.spofify.mobile.playback

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.core.content.edit
import dev.spofify.mobile.core.auth.AccountIdentity
import dev.spofify.mobile.core.auth.SessionRepository
import dev.spofify.mobile.core.cache.*
import dev.spofify.mobile.core.offline.OfflineClient
import dev.spofify.mobile.core.playback.AudioFacts
import dev.spofify.mobile.core.playback.PlaybackView
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

/** App lifetime, shared even when the same account authenticates again before an old reader closes. */
internal class MusicStorage(app: Application, private val scope: CoroutineScope) {
    val ready = MutableStateFlow<MusicBlobStore?>(null)
    fun release(pin: PinnedSong) { scope.launch(Dispatchers.IO) { pin.close() } }
    init { scope.launch(Dispatchers.IO) {
        ready.value = try { MusicBlobStore(File(app.noBackupFilesDir, "music-blobs")) } catch (_: Exception) { null }
    } }
}

internal fun PlaybackView.readyForMusicCache(facts: AudioFacts, authorized: Boolean): Boolean {
    val binding = facts.binding ?: return false
    val snap = snapshot ?: return false
    return authorized && isLocalSelectedConnection() && snap.transfer == null && desiredPlaying &&
        facts.isPlaying && !facts.isLoading && !facts.isBuffering && binding.trackId == current?.id &&
        binding.eventId == snap.state.occurrence?.eventId && binding.outputVersion == snap.outputVersion
}

private fun network(app: Application) = callbackFlow {
    val cm = app.getSystemService(ConnectivityManager::class.java)
    val callback = object : ConnectivityManager.NetworkCallback() {
        // API 26+ supplies the initial capabilities after onAvailable. Never reuse the old
        // network's metering or race a synchronous getNetworkCapabilities against this callback.
        private var active: Network? = null
        override fun onAvailable(network: Network) { active = network; trySend(null) }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (active == network) trySend(if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                CacheNetwork(network.networkHandle, !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) else null)
        }
        override fun onLost(network: Network) { if (active == network) { active = null; trySend(null) } }
    }
    cm.registerDefaultNetworkCallback(callback)
    awaitClose { cm.unregisterNetworkCallback(callback) }
}.conflate().catch { emit(null) }

/** One session-bound consumer. Main only projects current facts; all file and transport work is on IO. */
internal class AutomaticMusicCache(app: Application, private val sessions: SessionRepository,
    private val identity: AccountIdentity, private val storage: MusicStorage, private val scope: CoroutineScope) {
    private val owner = MusicOwner(identity.origin, identity.accountId)
    private val prefs = app.getSharedPreferences("music-cache", 0)
    private val prefix = "${identity.origin.length}:${identity.origin}${identity.accountId}:"
    val options = MutableStateFlow(CacheOptions(
        prefs.getInt(prefix + "cap", 10).takeIf { it in CacheOptions.caps } ?: 10,
        prefs.getBoolean(prefix + "metered", false)))
    @OptIn(ExperimentalCoroutinesApi::class)
    val songs = storage.ready.filterNotNull().flatMapLatest { store -> store.songs.map { list -> list.filter { it.key.owner == owner } } }
        .stateIn(scope, SharingStarted.WhileSubscribed(), null)
    private val api = OfflineClient(sessions, identity)
    private var lastQueue: dev.spofify.mobile.core.playback.PlaybackQueueState? = null
    private var window: CacheWindow? = null
    private var playing = false
    private var link: CacheNetwork? = null
    private val suppression = ClearSuppression()
    private var clearing = 0
    private val plan = MutableStateFlow(PrefetchPlan(options.value.cap, null, null, emptyList()))

    init {
        scope.launch { network(app).collect { link = it; update() } }
        scope.launch(Dispatchers.IO) {
            val store = storage.ready.filterNotNull().first()
            prefetchMusic(plan, owner, store, { sessions.isCurrent(identity) }, SystemClock::elapsedRealtime,
                api::prepareMedia) { media -> sessions.versionedAudio(identity, media) }
        }
    }

    fun observe(view: PlaybackView, facts: AudioFacts, authorized: Boolean) {
        val queue = view.snapshot?.state?.takeIf { view.isLocalSelectedConnection() && view.snapshot?.transfer == null }
        val old = lastQueue
        val changed = old?.queue !== queue?.queue || old?.position != queue?.position || old?.repeat != queue?.repeat ||
            old?.occurrence?.eventId != queue?.occurrence?.eventId
        if (changed) {
            lastQueue = queue
            window = queue?.cacheWindow()
            suppression.observe(window)
        }
        val ready = queue != null && view.readyForMusicCache(facts, authorized)
        if (!changed && playing == ready) return
        playing = ready
        update()
    }
    private fun update() {
        val old = plan.value
        val next = window.plan(options.value, link, playing && clearing == 0, suppression)
        if (old.copy(generation = 0) != next) plan.value = next.copy(generation = old.generation + 1)
    }
    fun configure(value: CacheOptions) {
        if (!sessions.isCurrent(identity)) return
        prefs.edit {
            putInt(prefix + "cap", value.cap); putBoolean(prefix + "metered", value.metered)
        }
        options.value = value; update()
    }
    fun clear(id: String?) {
        if (!sessions.isCurrent(identity)) return
        suppression.clear(id); clearing++; update()
        scope.launch {
            try { withContext(Dispatchers.IO) { if (sessions.isCurrent(identity)) storage.ready.value?.clearAuto(owner, id) } }
            catch (e: CancellationException) { throw e } catch (_: Exception) { /* Retain actual rows on storage failure. */ }
            finally { clearing--; update() }
        }
    }
    fun sourceChoice() = MusicSourceChoice({ id -> storage.ready.value?.pin(SongKey(owner, id)) { sessions.isCurrent(identity) } }, storage::release)
}
