package dev.spofify.mobile.playback

import dev.spofify.mobile.core.auth.*
import dev.spofify.mobile.core.content.*
import dev.spofify.mobile.core.library.*
import dev.spofify.mobile.core.offline.*
import dev.spofify.mobile.core.search.*
import dev.spofify.mobile.feature.library.LibraryReadOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*

/** One logical data generation, independent of navigation destinations and playback ticks. */
internal class MusicSources(val library: LibraryApi, val search: SearchApi, val personal: LivePersonal,
    val content: TrackContentApi, val local: Boolean, val readOwner: LibraryReadOwner? = null)

internal fun SessionRepository.catalogCommit(grant: OnlineGrant): (() -> Unit) -> Boolean = { publish ->
    publishIfCurrent(grant, publish)
}

internal fun onlineMusicSources(sessions: SessionRepository, grant: OnlineGrant, catalog: LocalMusicCatalog, account: LivePersonal): MusicSources {
    val id = grant.identity
    val library = LibraryClient(sessions, id)
    val search = SearchClient(sessions, id)
    val content = TrackContentClient(sessions, id)
    val commit = sessions.catalogCommit(grant)
    suspend fun <T : Any> read(block: suspend () -> T) = sessions.withOnlineGrant(grant) { block() }
    suspend fun retain(block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { /* A cache write cannot fail an online read. */ }
    }
    suspend fun remember(items: List<MusicItem>) = retain {
        catalog.rememberTracks(items.filter { it.kind == MusicKind.TRACK }.map(LocalTrackMetadata::Library), commit)
    }
    val browse = object : LibraryApi {
        override suspend fun search(kind: MusicKind, query: String, offset: Int) = read { library.search(kind, query, offset).also { remember(it.items) } }
        override suspend fun artistAlbums(id: String, offset: Int) = read { library.artistAlbums(id, offset) }
        override suspend fun albumTracks(id: String, offset: Int) = read { library.albumTracks(id, offset).also { remember(it.items) } }
        override suspend fun detail(kind: MusicKind, id: String) = read { library.detail(kind, id).also { remember(listOf(it)) } }
        override suspend fun artwork(kind: MusicKind, id: String): ByteArray? = read {
            // withOnlineGrant excludes null; wrap confirmed misses without turning failures into misses.
            Result.success(library.artwork(kind, id).also { retain { catalog.rememberArtwork(kind, id, it, commit) } })
        }.getOrThrow()
    }
    val ranked = object : SearchApi {
        override suspend fun search(query: String, kind: MusicKind?, limit: Int) = read { search.search(query, kind, limit).also { remember(it.items) } }
        override suspend fun next(cursor: String) = read { search.next(cursor).also { remember(it.items) } }
    }
    val track = object : TrackContentApi {
        override val active = sessions.onlineGrant.map { it === grant }
        override val isCurrent get() = sessions.onlineGrant.value === grant
        override suspend fun waveform(trackId: String) = read { content.waveform(trackId) }
        override suspend fun lyrics(trackId: String) = read { content.lyrics(trackId) } // Display rights only, never persist text.
    }
    return MusicSources(browse, ranked, account, track, false, LibraryReadOwner(sessions, grant))
}
