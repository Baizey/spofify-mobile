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
import dev.spofify.mobile.core.offline.OfflineDiagnosticEvent
import dev.spofify.mobile.core.playback.AudioFacts
import dev.spofify.mobile.core.playback.PlaybackConnectionState
import dev.spofify.mobile.core.playback.PlaybackView
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

/** App lifetime, shared even when the same account authenticates again before an old reader closes. */
internal class MusicStorage(app: Application, private val scope: CoroutineScope) {
    val ready = MutableStateFlow<MusicBlobStore?>(null)
    val initialized = CompletableDeferred<MusicBlobStore?>()
    fun release(pin: PinnedSong) { scope.launch(Dispatchers.IO) { pin.close() } }
    init { scope.launch(Dispatchers.IO) {
        val diagnostics = playlistDownloadDiagnostics
        val start = diagnostics?.let { SystemClock.elapsedRealtime() }
        diagnostics?.event(OfflineDiagnosticEvent.CACHE_RECOVERY_START)
        ready.value = try { MusicBlobStore(File(app.noBackupFilesDir, "music-blobs-v2")) }
        catch (e: Exception) {
            diagnostics?.event(OfflineDiagnosticEvent.CACHE_RECOVERY_UNAVAILABLE,
                elapsedMs = start?.let { SystemClock.elapsedRealtime() - it }, error = e)
            null
        }
        if (diagnostics != null) ready.value?.let { store ->
            diagnostics.event(OfflineDiagnosticEvent.CACHE_RECOVERY_READY,
                elapsedMs = start?.let { SystemClock.elapsedRealtime() - it }, count = store.songs.value.size)
        }
        initialized.complete(ready.value)
    } }
}

internal fun PlaybackView.readyForMusicCache(facts: AudioFacts, authorized: Boolean): Boolean {
    val binding = facts.binding ?: return false
    val snap = snapshot ?: return false
    return authorized && onlineLocalOutput() && desiredPlaying &&
        facts.isPlaying && !facts.isLoading && !facts.isBuffering && binding.trackId == current?.id &&
        binding.eventId == snap.state.occurrence?.eventId
}

private fun PlaybackView.onlineLocalOutput() =
    localOutput && !offline && !isolatedOutput && !superseded && connection == PlaybackConnectionState.READY

internal fun network(app: Application) = callbackFlow {
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

internal enum class CacheClearState { IDLE, CLEARING, FAILED }

/** One session-bound consumer. Main only projects current facts; all file and transport work is on IO. */
internal class AutomaticMusicCache(app: Application, private val sessions: SessionRepository,
    private val identity: AccountIdentity, private val storage: MusicStorage, private val scope: CoroutineScope) {
    private val owner = MusicOwner(identity.origin, identity.accountId)
    private val prefs = app.getSharedPreferences("music-cache", 0)
    private val prefix = "${identity.origin.length}:${identity.origin}${identity.accountId}:"
    val options = MutableStateFlow(CacheOptions(
        prefs.getInt(prefix + "cap", 10).takeIf { it in CacheOptions.caps } ?: 10,
        prefs.getBoolean(prefix + "metered", false)))
    val usage: StateFlow<MusicStorageUsage?> = flow {
        storage.initialized.await()?.let { emitAll(it.storageUsage(owner)) }
    }.stateIn(scope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), null)
    private val api = OfflineClient(sessions, identity)
    private var lastQueue: dev.spofify.mobile.core.playback.PlaybackQueueState? = null
    private var window: CacheWindow? = null
    private var playing = false
    val link = MutableStateFlow<CacheNetwork?>(null)
    private val suppression = ClearSuppression()
    private val mutableClearState = MutableStateFlow(CacheClearState.IDLE)
    val clearState = mutableClearState.asStateFlow()
    private val plan = MutableStateFlow(PrefetchPlan(options.value.cap, null, null, emptyList()))

    init {
        scope.launch {
            sessions.onlineGrant.collectLatest { grant ->
                if (grant?.identity != identity) return@collectLatest
                try {
                    sessions.withOnlineGrant(grant) {
                        launch(Dispatchers.IO) {
                            val store = storage.initialized.await() ?: return@launch
                            prefetchMusic(plan, owner, store, { sessions.onlineGrant.value === grant }, SystemClock::elapsedRealtime,
                                api::prepareMedia) { media -> sessions.versionedAudio(identity, media) }
                        }
                        network(app).collect { link.value = it; update() }
                    }
                } finally { playing = false; link.value = null; update() }
            }
        }
    }

    fun observe(view: PlaybackView, facts: AudioFacts, authorized: Boolean) {
        val queue = view.snapshot?.state?.takeIf { view.onlineLocalOutput() }
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
        val next = window.plan(options.value, link.value, playing && clearState.value != CacheClearState.CLEARING, suppression)
        if (old.copy(generation = 0) != next) plan.value = next.copy(generation = old.generation + 1)
    }
    fun configure(value: CacheOptions) {
        if (!sessions.isCurrent(identity)) return
        prefs.edit {
            putInt(prefix + "cap", value.cap); putBoolean(prefix + "metered", value.metered)
        }
        options.value = value; update()
    }
    fun clearAutomaticCache() {
        if (!sessions.isCurrent(identity) || clearState.value == CacheClearState.CLEARING) return
        suppression.clear(null)
        mutableClearState.value = CacheClearState.CLEARING
        update()
        scope.launch {
            var result = CacheClearState.IDLE
            try {
                val store = storage.initialized.await() ?: error("Music storage unavailable")
                withContext(Dispatchers.IO) { if (sessions.isCurrent(identity)) store.clearAuto(owner) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { result = CacheClearState.FAILED }
            finally { mutableClearState.value = result; update() }
        }
    }
    fun onlineRanges(allowed: () -> Boolean) = OnlineMusicRanges(storage.initialized, sourceChoice(allowed), allowed)

    // A hint to replace an installed HTTP representation at the next playback command.
    // The loader still pins and validates the file; this is not a playable-availability check.
    fun hasStoredAudio(trackId: String): Boolean = storage.ready.value?.songs?.value?.any {
        it.key.owner == owner && it.key.id == trackId && (it.offline || it.auto)
    } == true

    fun hasPlayableDownload(trackId: String, allowed: () -> Boolean): Boolean =
        storage.ready.value?.pin(SongKey(owner, trackId), downloadOnly = true) {
            allowed() && sessions.isCurrent(identity)
        }?.use { true } == true

    fun sourceChoice(allowed: () -> Boolean = { true }, downloadOnly: Boolean = false) = MusicSourceChoice({ id ->
        storage.ready.value?.pin(SongKey(owner, id), downloadOnly) { allowed() && sessions.isCurrent(identity) }
    }, storage::release)
}
