package dev.spofify.mobile.playback

import dev.spofify.mobile.core.auth.*
import dev.spofify.mobile.core.cache.*
import dev.spofify.mobile.core.library.LibraryClient
import dev.spofify.mobile.core.library.MusicKind
import dev.spofify.mobile.core.offline.*
import dev.spofify.mobile.core.playback.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** One local account and the existing verified blob inventory. Network jobs capture the exact
 * grant; only a committed manifest contributes membership. No history writes from playback. */
internal class AccountMusic(root: File, private val identity: AccountIdentity, private val sessions: SessionRepository,
    private val storage: MusicStorage, private val cache: AutomaticMusicCache, private val scope: CoroutineScope) {
    private val owner = MusicOwner(identity.origin, identity.accountId)
    private fun storedIds(songs: List<StoredSong>) = songs.filter { it.key.owner == owner && (it.auto || it.offline) }
        .mapTo(hashSetOf()) { it.key.id }
    private val ids = MutableStateFlow<Set<String>>(emptySet())
    val catalog = LocalMusicCatalog(root, identity) { storage.initialized.await()?.songs?.value?.let(::storedIds).orEmpty() }
    private val api = OfflineClient(sessions, identity)
    private val repository = scope.async {
        storage.initialized.await()?.let { OfflinePlaylistRepository(it, owner, api::manifest, api::prepareMedia) { media ->
            sessions.versionedAudio(identity, media)
        } }
    }
    private val ownerActive = sessions.state.map { (it as? AuthState.Authenticated)?.identity == identity }
        .stateIn(scope, SharingStarted.Eagerly, sessions.isCurrent(identity))
    private fun localSources() = MusicSources(catalog, catalog,
        DevicePersonalLibrary(catalog) { repository.await()?.complete().orEmpty() }, catalog.trackContent(ownerActive), true)
    private var dataGrant = sessions.onlineGrant.value?.takeIf { it.identity == identity }
    private fun dataFor(grant: OnlineGrant?) = grant?.let { onlineMusicSources(sessions, it, catalog) } ?: localSources()
    private val mutableSources = MutableStateFlow(dataFor(dataGrant))
    val sources = mutableSources.asStateFlow()
    private val mutableSaved = MutableStateFlow<List<OfflineManifest>>(emptyList())
    val saved = mutableSaved.asStateFlow()
    private val mutableSaving = MutableStateFlow<Set<String>>(emptySet())
    val saving = mutableSaving.asStateFlow()
    private val mutableFailed = MutableStateFlow<Set<String>>(emptySet())
    val failed = mutableFailed.asStateFlow()
    private val metadataVersion = MutableStateFlow(0L)
    private var deviceIndex: DeviceMusicLibrary? = null
    val library = object : LocalPlaybackLibrary {
        override fun resolve(track: PlaybackTrackReference?, context: PlaybackContext?) = deviceIndex?.resolve(track, context)
        override suspend fun hasAudio(track: PlaybackTrack, isAuthorized: () -> Boolean) = isAuthorized() && track.id in ids.value
    }

    private val queue = MutableStateFlow<List<PlaybackTrack>>(emptyList())
    fun observeQueue(view: PlaybackView) { queue.value = view.snapshot?.state?.queue.orEmpty() }

    init {
        scope.launch {
            sessions.onlineGrant.collect { current ->
                val newGrant = current?.takeIf { it.identity == identity }
                if (dataGrant !== newGrant) { dataGrant = newGrant; mutableSources.value = dataFor(newGrant) }
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
        scope.launch { reload() }
        scope.launch {
            combine(ids, saved, metadataVersion, sessions.manualOffline) { _, playlists, _, offline -> playlists to offline }
                .collectLatest { (playlists, offline) ->
                    deviceIndex = null
                    if (offline) {
                        // A save may have committed just as its network grant was revoked. Only the
                        // durable complete set, not a cancelled caller's publication, owns local rows.
                        val complete = repository.await()?.complete().orEmpty()
                        if (complete != playlists) {
                            for (manifest in complete) remember(manifest)
                            mutableSaved.value = complete
                        }
                        deviceIndex = withContext(Dispatchers.Default) {
                            DeviceMusicLibrary(catalog.availableTracks(), complete) { it in ids.value }
                        }
                    }
                }
        }
        scope.launch {
            combine(sessions.onlineGrant, cache.link) { grant, link -> grant to link }.collectLatest { (grant, link) ->
                if (grant?.identity != identity || link == null || link.metered) return@collectLatest
                sessions.withOnlineGrant(grant) {
                    val repo = repository.await() ?: return@withOnlineGrant
                    for (manifest in repo.complete()) refresh(manifest.collection.reference.id, grant, link)
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

    fun save(id: String) {
        val grant = sessions.onlineGrant.value?.takeIf { it.identity == identity } ?: return
        val link = cache.link.value ?: return
        scope.launch { sessions.withOnlineGrant(grant) { refresh(id, grant, link) } }
    }

    private suspend fun reload() {
        val complete = repository.await()?.complete().orEmpty()
        mutableSaved.value = complete
        for (manifest in complete) remember(manifest)
        metadataVersion.value++
    }

    private suspend fun remember(manifest: OfflineManifest) {
        manifest.items.distinctBy { it.track.id }.chunked(500).forEach { chunk ->
            try { catalog.rememberTracks(chunk.map { LocalTrackMetadata.Personal(it.track) }) }
            catch (e: CancellationException) { throw e } catch (_: Exception) { /* Manifest retains its own verified metadata. */ }
        }
    }

    private suspend fun refresh(id: String, grant: OnlineGrant, link: CacheNetwork) = coroutineScope {
        if (id in mutableSaving.value) return@coroutineScope
        mutableSaving.value += id
        mutableFailed.value -= id
        val work = launch(start = CoroutineStart.LAZY) {
            try {
                val repo = checkNotNull(repository.await())
                val manifest = repo.refresh(id, cache.options.value.cap) { sessions.onlineGrant.value === grant && cache.link.value == link }
                mutableSaved.value = (mutableSaved.value.filterNot { it.collection.reference.id == id } + manifest)
                remember(manifest)
                metadataVersion.value++
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                sessions.publishIfCurrent(grant) { mutableFailed.value += id }
            }
        }
        val policy = launch { cache.link.first { it != link }; work.cancel() }
        try {
            work.start(); work.join()
        } finally {
            policy.cancel()
            mutableSaving.value -= id
        }
    }
}
