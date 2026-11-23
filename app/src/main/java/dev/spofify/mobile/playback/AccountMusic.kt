package dev.spofify.mobile.playback

import android.annotation.SuppressLint
import android.app.Application
import dev.spofify.mobile.core.auth.*
import dev.spofify.mobile.core.cache.*
import dev.spofify.mobile.core.library.LibraryClient
import dev.spofify.mobile.core.library.MusicKind
import dev.spofify.mobile.core.offline.*
import dev.spofify.mobile.core.playback.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** One local account and the existing verified blob inventory. Network jobs capture the exact
 * grant; only a committed manifest contributes membership. No history writes from playback. */
internal class AccountMusic(app: Application, private val identity: AccountIdentity, private val sessions: SessionRepository,
    private val storage: MusicStorage, private val cache: AutomaticMusicCache, private val scope: CoroutineScope) {
    private val owner = MusicOwner(identity.origin, identity.accountId)
    private fun storedIds(songs: List<StoredSong>) = songs.filter { it.key.owner == owner && (it.auto || it.offline) }
        .mapTo(hashSetOf()) { it.key.id }
    private val ids = MutableStateFlow<Set<String>>(emptySet())
    val catalog = LocalMusicCatalog(app.noBackupFilesDir, identity) { storage.initialized.await()?.songs?.value?.let(::storedIds).orEmpty() }
    private val api = OfflineClient(sessions, identity)
    private val repository = scope.async {
        storage.initialized.await()?.let { OfflinePlaylistRepository(it, owner, api::manifest, api::prepareMedia) { media ->
            sessions.versionedAudio(identity, media)
        } }
    }
    private val ownerActive = sessions.state.map { (it as? AuthState.Authenticated)?.identity == identity }
        .stateIn(scope, SharingStarted.Eagerly, sessions.isCurrent(identity))
    private val preferences = app.getSharedPreferences("offline-playlists", 0)
    private val preferenceKey = "${identity.origin.length}:${identity.origin}:${identity.accountId}"
    val offlinePlaylists = PlaylistOfflinePreference(scope,
        read = { withContext(Dispatchers.IO) { preferences.getStringSet(preferenceKey, null)?.toSet() } },
        persist = ::persistOfflinePlaylists,
        readComplete = { repository.await()?.complete().orEmpty() },
        remove = { id, current -> repository.await()?.remove(id, cache.options.value.cap, current) },
        readRetainedIds = { repository.await()?.retainedIds().orEmpty() },
        diagnostics = playlistDownloadDiagnostics,
    )
    private val committedPlaylistIds: Flow<Set<String>> = flow {
        emitAll(repository.await()?.completeIds() ?: flowOf(emptySet()))
    }
    private val playlistProgress = MutableStateFlow<Map<String, OfflinePlaylistProgress>>(emptyMap())
    val offlinePlaylistStatuses = combine(offlinePlaylists.wanted, committedPlaylistIds, playlistProgress, ::playlistOfflineStatuses)
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    // The KTX edit helper discards commit's result; don't publish an unpersisted choice.
    @SuppressLint("UseKtx")
    private suspend fun persistOfflinePlaylists(ids: Set<String>) = withContext(Dispatchers.IO) {
        if (!preferences.edit().putStringSet(preferenceKey, ids).commit()) throw IOException("Offline preference unavailable")
    }

    private val localSources = MusicSources(catalog, catalog,
        DevicePersonalLibrary(catalog, offlinePlaylists::complete), catalog.trackContent(ownerActive), true)
    val personal = AccountPersonalSource()
    private val onlinePlayback = MutableStateFlow(false)
    private val mutableSources = MutableStateFlow(localSources)
    val sources = mutableSources.asStateFlow()
    private val metadataVersion = MutableStateFlow(0L)
    private var deviceIndex: DeviceMusicLibrary? = null
    private val mutableIndexReady = MutableStateFlow(false)
    val indexReady = mutableIndexReady.asStateFlow()
    val library = object : LocalPlaybackLibrary {
        override fun resolve(track: PlaybackTrackReference?, context: PlaybackContext?) = deviceIndex?.resolve(track, context)
        override suspend fun hasAudio(track: PlaybackTrack, isAuthorized: () -> Boolean) = isAuthorized() && track.id in ids.value
    }

    private val queue = MutableStateFlow<List<PlaybackTrack>>(emptyList())
    fun observePlayback(view: PlaybackView) {
        queue.value = view.queue
        onlinePlayback.value = PlayerPresentation(view).onlineReady
    }

    init {
        scope.launch {
            personal.replica.view.filter { it.connected }.map { it.recent }.distinctUntilChanged().collectLatest { rows ->
                try { catalog.rememberHistory(rows) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Keep the previous complete on-device snapshot. */ }
            }
        }
        scope.launch {
            combine(sessions.onlineGrant, onlinePlayback) { grant, online -> grant?.takeIf { online && it.identity == identity } }
                .distinctUntilChanged().collect { grant ->
                    mutableSources.value = if (grant == null) localSources else onlineMusicSources(sessions, grant, catalog, personal)
                }
        }
        scope.launch {
            val store = storage.initialized.await() ?: return@launch
            combine(store.songs, queue) { songs, tracks ->
                val byId = tracks.associateBy { it.id }
                songs.filter { it.key.owner == owner && (it.auto || it.offline) }.map {
                    val row = byId[it.key.id]
                    LocalTrackMetadata.Cached(it.key.id, row?.name?.ifBlank { it.title } ?: it.title,
                        row?.albumId.orEmpty(), row?.albumName.orEmpty(), row?.artists.orEmpty(), ((row?.duration ?: 0.0) * 1000).toLong())
                }
            }.distinctUntilChanged().collect { observations ->
                for (batch in observations.chunked(500)) try { catalog.rememberTracks(batch) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { /* Unusable metadata is not playback authority. */ }
                metadataVersion.value++
            }
        }
        scope.launch {
            storage.initialized.await()?.songs?.map(::storedIds)?.distinctUntilChanged()?.collect { ids.value = it }
        }
        scope.launch {
            for (manifest in offlinePlaylists.complete()) remember(manifest)
            metadataVersion.value++
        }
        scope.launch {
            // Keep this ready before an online owner detaches; a mode change needn't await reindexing.
            combine(ids, offlinePlaylists.wanted, metadataVersion) { _, _, version -> version }
                .collectLatest {
                    val complete = offlinePlaylists.complete()
                    deviceIndex = withContext(Dispatchers.Default) {
                        DeviceMusicLibrary(catalog.availableTracks(), complete) { it in ids.value }
                    }
                    mutableIndexReady.value = true
                }
        }
        scope.launch {
            val diagnostics = playlistDownloadDiagnostics
            var lastPolicy: OfflineDiagnosticEvent? = null
            fun policy(event: OfflineDiagnosticEvent) {
                if (diagnostics != null && event != lastPolicy) {
                    lastPolicy = event
                    diagnostics.event(event)
                }
            }
            combine(sessions.onlineGrant, cache.link) { grant, link -> grant to link }.collectLatest { (grant, link) ->
                if (grant?.identity != identity) {
                    policy(OfflineDiagnosticEvent.POLICY_NO_GRANT)
                    return@collectLatest
                }
                if (link == null) {
                    policy(OfflineDiagnosticEvent.POLICY_NO_LINK)
                    return@collectLatest
                }
                if (diagnostics != null && !repository.isCompleted) policy(OfflineDiagnosticEvent.POLICY_WAIT_STORAGE)
                val repo = repository.await() ?: run {
                    policy(OfflineDiagnosticEvent.POLICY_NO_STORAGE)
                    return@collectLatest
                }
                policy(if (link.metered) OfflineDiagnosticEvent.POLICY_ELIGIBLE_METERED else OfflineDiagnosticEvent.POLICY_ELIGIBLE)
                offlinePlaylists.download { id, wanted, trace ->
                    trace?.step(OfflineDiagnosticStage.GRANT)
                    sessions.withOnlineGrant<Unit>(grant) {
                        // Explicit downloads may use metered data; background updates to an
                        // already saved playlist still wait for an unmetered link.
                        if (link.metered) trace?.step(OfflineDiagnosticStage.METERED_LOOKUP)
                        if (!link.metered || repo.select(id) == null) {
                            val manifest = try {
                                repo.refresh(id, cache.options.value.cap, onProgress = { progress ->
                                    playlistProgress.update { it + (id to progress) }
                                }, trace = trace) { wanted() && sessions.onlineGrant.value === grant && cache.link.value == link }
                            } finally {
                                playlistProgress.update { it - id }
                            }
                            trace?.step(OfflineDiagnosticStage.CATALOG)
                            remember(manifest)
                            metadataVersion.value++
                        } else {
                            trace?.step(OfflineDiagnosticStage.METERED_SKIP)
                        }
                    }
                }
            }
        }
        // AUTO only stores bytes and title. Get actual catalog metadata for those verified IDs;
        // never guess album or artist identities. At most one attempt per ID per grant.
        scope.launch {
            sessions.onlineGrant.collectLatest { grant ->
                if (grant?.identity != identity) return@collectLatest
                sessions.withOnlineGrant(grant) {
                    val seen = hashSetOf<String>()
                    val client = LibraryClient(sessions, identity)
                    ids.collect { available ->
                        val known = catalog.availableTracks().filter { it.albumId.isNotEmpty() && it.artists.isNotEmpty() }.mapTo(hashSetOf()) { it.id }
                        for (id in available - known) if (seen.add(id)) {
                            try { catalog.rememberTracks(listOf(LocalTrackMetadata.Library(client.detail(MusicKind.TRACK, id)))) }
                            catch (e: CancellationException) { throw e } catch (_: Exception) { /* No guessed metadata. */ }
                        }
                    }
                }
            }
        }
    }

    private suspend fun remember(manifest: OfflineManifest) {
        manifest.items.distinctBy { it.track.id }.chunked(500).forEach { chunk ->
            try { catalog.rememberTracks(chunk.map { LocalTrackMetadata.Personal(it.track) }) }
            catch (e: CancellationException) { throw e } catch (_: Exception) { /* Manifest retains its own verified metadata. */ }
        }
    }

}
