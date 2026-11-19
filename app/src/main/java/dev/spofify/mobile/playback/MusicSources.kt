package dev.spofify.mobile.playback

import dev.spofify.mobile.core.auth.*
import dev.spofify.mobile.core.content.*
import dev.spofify.mobile.core.library.*
import dev.spofify.mobile.core.offline.*
import dev.spofify.mobile.core.personal.*
import dev.spofify.mobile.core.search.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*

/** One logical data generation, independent of navigation destinations and playback ticks. */
internal class MusicSources(val library: LibraryApi, val search: SearchApi, val personal: PersonalLibraryApi,
    val content: TrackContentApi, val local: Boolean)

internal fun onlineMusicSources(sessions: SessionRepository, grant: OnlineGrant, catalog: LocalMusicCatalog, account: LivePersonal): MusicSources {
    val id = grant.identity
    val library = LibraryClient(sessions, id)
    val search = SearchClient(sessions, id)
    val personal = PersonalLibraryClient(sessions, id)
    val content = TrackContentClient(sessions, id)
    suspend fun <T : Any> read(block: suspend () -> T) = sessions.withOnlineGrant(grant) { block() }
    suspend fun retain(block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { /* A cache write cannot fail an online read. */ }
    }
    suspend fun remember(items: List<MusicItem>) = retain {
        catalog.rememberTracks(items.filter { it.kind == MusicKind.TRACK }.map(LocalTrackMetadata::Library))
    }
    val browse = object : LibraryApi {
        override suspend fun search(kind: MusicKind, query: String, offset: Int) = read { library.search(kind, query, offset).also { remember(it.items) } }
        override suspend fun artistAlbums(id: String, offset: Int) = read { library.artistAlbums(id, offset) }
        override suspend fun albumTracks(id: String, offset: Int) = read { library.albumTracks(id, offset).also { remember(it.items) } }
        override suspend fun detail(kind: MusicKind, id: String) = read { library.detail(kind, id).also { remember(listOf(it)) } }
        override suspend fun artwork(kind: MusicKind, id: String): ByteArray? = read {
            // withOnlineGrant excludes null; wrap confirmed misses without turning failures into misses.
            Result.success(library.artwork(kind, id).also { retain { catalog.rememberArtwork(kind, id, it) } })
        }.getOrThrow()
    }
    val ranked = object : SearchApi {
        override suspend fun search(query: String, kind: MusicKind?, limit: Int) = read { search.search(query, kind, limit).also { remember(it.items) } }
        override suspend fun next(cursor: String) = read { search.next(cursor).also { remember(it.items) } }
    }
    val user = object : PersonalLibraryApi, LivePersonal by account {
        private fun useReplica(): Nothing = error("Use the live personal replica for shared state")
        override suspend fun playlists(limit: Int, offset: Int, kind: PersonalPlaylistKind?) = useReplica()
        override suspend fun playlist(id: String) = useReplica()
        override suspend fun manualEntries(id: String, limit: Int, offset: Int) = useReplica()
        override suspend fun derivedEntries(id: String, limit: Int, cursor: String?) = useReplica()
        override suspend fun likes(limit: Int, offset: Int) = read { personal.likes(limit, offset) }
        override suspend fun likeStatus(trackIds: List<String>) = read { personal.likeStatus(trackIds) }
        override suspend fun setLike(trackId: String, liked: Boolean) = read { personal.setLike(trackId, liked) }
        override suspend fun toggleLike(trackId: String, currentlyLiked: Boolean) = read { personal.toggleLike(trackId, currentlyLiked) }
        override suspend fun createManualPlaylist(name: String, description: String) = useReplica()
        override suspend fun renamePlaylist(id: String, name: String, description: String, expectedRevision: Long) = useReplica()
        override suspend fun deletePlaylist(id: String, expectedRevision: Long) = useReplica()
        override suspend fun addTrack(id: String, trackId: String, expectedRevision: Long) = useReplica()
        override suspend fun removeEntry(id: String, entryId: String, expectedRevision: Long) = useReplica()
        override suspend fun reorderEntries(id: String, entryIds: List<String>, expectedRevision: Long) = useReplica()
        override suspend fun recent(limit: Int) = useReplica()
        override suspend fun history(limit: Int, offset: Int) = useReplica()
    }
    val track = object : TrackContentApi {
        override val active = sessions.onlineGrant.map { it === grant }
        override val isCurrent get() = sessions.onlineGrant.value === grant
        override suspend fun waveform(trackId: String) = read { content.waveform(trackId) }
        override suspend fun lyrics(trackId: String) = read { content.lyrics(trackId) } // Display rights only, never persist text.
    }
    return MusicSources(browse, ranked, user, track, false)
}

/** Read-only projection of complete saved memberships, not server mutation or derived-selection authority. */
internal class DevicePersonalLibrary(private val catalog: LocalMusicCatalog,
    private val complete: suspend () -> List<OfflineManifest>) : PersonalLibraryApi {
    private fun OfflineManifest.summary(): PersonalPlaylistSummary {
        val kind = if (collection.playlistKind == OfflinePlaylistKind.MANUAL) PersonalPlaylistKind.MANUAL else PersonalPlaylistKind.DERIVED
        return PersonalPlaylistSummary(collection.reference.id, collection.name, collection.description.orEmpty(), kind,
            PersonalCountState.EXACT, total, recordedAudioTotal, PersonalPlaylistCapabilities(false, false, false,
                items.isNotEmpty(), false, false, false), requireNotNull(collection.revision), "", "")
    }
    suspend fun manifest(id: String) = complete().firstOrNull { it.collection.reference.id == id }
    private suspend fun requireSaved(id: String) = manifest(id) ?: throw PersonalLibraryException(404, PersonalLibraryErrorCode.PLAYLIST_NOT_FOUND)
    override suspend fun playlists(limit: Int, offset: Int, kind: PersonalPlaylistKind?): PersonalPage<PersonalPlaylistSummary> {
        val all = complete().map { it.summary() }.filter { kind == null || it.kind == kind }
        return PersonalPage(all.drop(offset).take(limit), all.size.toLong(), limit, offset)
    }
    override suspend fun playlist(id: String) = requireSaved(id).summary()
    override suspend fun manualEntries(id: String, limit: Int, offset: Int): PersonalPage<PersonalManualEntry> {
        val saved = requireSaved(id)
        require(saved.collection.playlistKind == OfflinePlaylistKind.MANUAL)
        return PersonalPage(saved.items.drop(offset).take(limit).map { PersonalManualEntry(it.occurrenceId, it.position, it.track) }, saved.total, limit, offset)
    }
    override suspend fun derivedEntries(id: String, limit: Int, cursor: String?): PersonalDerivedPage {
        val saved = requireSaved(id)
        require(saved.collection.playlistKind == OfflinePlaylistKind.DERIVED)
        val offset = cursor?.toInt() ?: 0
        val rows = saved.items.drop(offset).take(limit).map { PersonalDerivedEntry(it.occurrenceId, null, it.position, it.track) }
        return PersonalDerivedPage(rows, saved.total, saved.recordedAudioTotal, limit,
            (offset + rows.size).takeIf { it < saved.total }?.toString(), saved.collection.revision, saved.fence, true)
    }
    override suspend fun history(limit: Int, offset: Int) = catalog.history(limit, offset)
    private fun onlineOnly(): Nothing = throw UnsupportedOperationException("Not available from device storage")
    override suspend fun recent(limit: Int) = onlineOnly()
    override suspend fun likes(limit: Int, offset: Int) = onlineOnly()
    override suspend fun likeStatus(trackIds: List<String>) = onlineOnly()
    override suspend fun setLike(trackId: String, liked: Boolean) = onlineOnly()
    override suspend fun toggleLike(trackId: String, currentlyLiked: Boolean) = onlineOnly()
    override suspend fun createManualPlaylist(name: String, description: String) = onlineOnly()
    override suspend fun renamePlaylist(id: String, name: String, description: String, expectedRevision: Long) = onlineOnly()
    override suspend fun deletePlaylist(id: String, expectedRevision: Long) = onlineOnly()
    override suspend fun addTrack(id: String, trackId: String, expectedRevision: Long) = onlineOnly()
    override suspend fun removeEntry(id: String, entryId: String, expectedRevision: Long) = onlineOnly()
    override suspend fun reorderEntries(id: String, entryIds: List<String>, expectedRevision: Long) = onlineOnly()
}
