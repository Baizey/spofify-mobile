package dev.spofify.mobile.core.offline

import dev.spofify.mobile.core.auth.AccountIdentity
import dev.spofify.mobile.core.content.LyricsState
import dev.spofify.mobile.core.content.TrackContentApi
import dev.spofify.mobile.core.content.TrackContentException
import dev.spofify.mobile.core.content.TrackContentFailure
import dev.spofify.mobile.core.content.TrackLyrics
import dev.spofify.mobile.core.content.TrackWaveform
import dev.spofify.mobile.core.content.WaveformState
import dev.spofify.mobile.core.library.LibraryApi
import dev.spofify.mobile.core.library.LibraryException
import dev.spofify.mobile.core.library.MusicItem
import dev.spofify.mobile.core.library.MusicKind
import dev.spofify.mobile.core.library.MusicPage
import dev.spofify.mobile.core.library.projectMusicItem
import dev.spofify.mobile.core.library.validMusicEntityId
import dev.spofify.mobile.core.library.validSearchQuery
import dev.spofify.mobile.core.personal.PersonalTrack
import dev.spofify.mobile.core.search.MAX_RETAINED_SEARCH_RESULTS
import dev.spofify.mobile.core.search.SearchApi
import dev.spofify.mobile.core.search.SearchErrorCode
import dev.spofify.mobile.core.search.SearchException
import dev.spofify.mobile.core.search.SearchPage
import java.io.File
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Account/origin scoped, exclusively local read APIs. The commit gate serializes final
 * replacements with authenticated-incarnation retirement. [availableTrackIds] is a stable snapshot from
 * the shared local audio inventory (manual downloads and/or auto-cache), not a network lookup.
 * Metadata does not authorize playback. Offline callers use only the read methods; remember methods
 * consume successful online observations, never local plays or receipts.
 */
class LocalMusicCatalog internal constructor(
    root: File,
    identity: AccountIdentity,
    private val availableTrackIds: suspend () -> Set<String>,
    private val clock: Clock,
    commit: (() -> Unit) -> Boolean = { it(); true },
) : LibraryApi, SearchApi {
    constructor(root: File, identity: AccountIdentity, availableTrackIds: suspend () -> Set<String>) :
        this(root, identity, availableTrackIds, Clock.systemUTC())

    constructor(root: File, identity: AccountIdentity, commit: (() -> Unit) -> Boolean,
        availableTrackIds: suspend () -> Set<String>) : this(root, identity, availableTrackIds, Clock.systemUTC(), commit)

    private val storage = LocalCatalogStorage(root, identity, commit)
    private val mutex = Mutex()
    private var tracks: CatalogTrackData? = null
    private var indexedAvailable: Set<String> = emptySet()
    private var index: LocalCatalogIndex? = null
    private var revision = 0L
    private val searches = linkedMapOf<String, RetainedSearch>()

    /** Batch ingestion for cache fills / completed downloads. Keep either or both real projections. */
    suspend fun rememberTracks(observations: List<LocalTrackMetadata>) = local {
        require(observations.size <= MAX_TRACKS)
        if (observations.isEmpty()) return@local
        val current = tracks()
        val updated = LinkedHashMap(current.tracks)
        for (observation in observations) when (observation) {
            is LocalTrackMetadata.Cached -> {
                validMusicEntityId(observation.id)
                if (observation.albumId.isNotEmpty()) validMusicEntityId(observation.albumId)
                require(observation.title.length <= 2048 && observation.album.length <= 2048 && observation.durationMs >= 0)
                require(observation.artists.size <= 64 && observation.artists.all { it.name.length <= 512 })
                observation.artists.mapNotNull { it.id }.filter(String::isNotEmpty).forEach(::validMusicEntityId)
                val previous = updated[observation.id] ?: CatalogTrackRecord()
                // A legacy title-only scan must never overwrite previously observed credits.
                if (previous.cached != null && observation.artists.isEmpty() && observation.albumId.isEmpty()) continue
                updated[observation.id] = previous.copy(cached = observation.copy(artists = observation.artists.toList()))
            }
            is LocalTrackMetadata.Library -> {
                val item = observation.item
                val wire = item.catalogWire()
                val previous = updated[item.id]
                if (previous?.library != null && projectMusicItem(previous.library) == item) continue
                updated.remove(item.id)
                updated[item.id] = (previous ?: CatalogTrackRecord()).copy(library = wire)
            }
            is LocalTrackMetadata.Personal -> {
                val track = observation.track.catalogCopy()
                val previous = updated[track.id]
                if (previous?.personal == track) continue
                updated.remove(track.id)
                updated[track.id] = (previous ?: CatalogTrackRecord()).copy(personal = track)
            }
        }
        if (updated.size > MAX_TRACKS) {
            val available = availableTrackIds()
            val priority = updated.keys.partition { it !in available }.let { (notLocal, local) -> notLocal + local }
            priority.take(updated.size - MAX_TRACKS).forEach(updated::remove)
        }
        if (updated != current.tracks) {
            val next = current.copy(tracks = updated)
            currentCoroutineContext().ensureActive()
            storage.saveTracks(next)
            tracks = next
            invalidateIndex()
        }
    }

    /** One local inventory snapshot for synchronous player-looper selection; no per-track disk queries. */
    suspend fun availableTracks(): List<MusicItem> = withIndex { index ->
        index.albumTracks.values.flatten() + index.tracks.values.filter { it.albumId.isEmpty() }
    }

    /** Only genuine PersonalTrack records, unchanged. Never synthesize one from a MusicItem. */
    suspend fun personalTrack(id: String): PersonalTrack? = withIndex { it.personal[validMusicEntityId(id)] }

    /** Pass null only for a confirmed online miss, not on failure or cancellation. */
    suspend fun rememberArtwork(kind: MusicKind, id: String, bytes: ByteArray?) = local {
        storage.saveArtwork(kind, validMusicEntityId(id), bytes)
    }

    suspend fun artworkState(kind: MusicKind, id: String): LocalArtwork = local {
        storage.artwork(kind, validMusicEntityId(id))
    }

    override suspend fun artwork(kind: MusicKind, id: String): ByteArray? =
        (artworkState(kind, id) as? LocalArtwork.Available)?.bytes

    /** The current display resource does not authorize an offline text copy. Retain only a miss. */
    suspend fun rememberLyrics(observation: TrackLyrics) = local {
        val id = validMusicEntityId(observation.trackId)
        val current = tracks()
        val miss = if (observation.state == LyricsState.NOT_FOUND) {
            require(OfflineValidation.text(observation.recordingVersion, 512) &&
                OfflineValidation.text(observation.sourceGeneration, 512))
            CatalogLyricMiss(observation.recordingVersion, observation.sourceGeneration, observation.reason)
        } else null
        if (current.lyricMisses[id] == miss) return@local
        val updated = LinkedHashMap(current.lyricMisses)
        updated.remove(id)
        miss?.let { updated[id] = it }
        if (updated.size > MAX_LYRIC_MISSES) updated.remove(updated.keys.first())
        val next = current.copy(lyricMisses = updated)
        storage.saveTracks(next)
        tracks = next
    }

    /** Use the authenticated-owner lifetime, not the offline-mode toggle, for [active]. */
    fun trackContent(active: StateFlow<Boolean>, localLyrics: LocalLyricsSource? = null): TrackContentApi =
        object : TrackContentApi {
            override val active = active
            override val isCurrent: Boolean get() = active.value

            override suspend fun waveform(trackId: String): TrackWaveform {
                requireCurrent()
                validMusicEntityId(trackId)
                return TrackWaveform(trackId, WaveformState.UNAVAILABLE, "", null, "")
            }

            override suspend fun lyrics(trackId: String): TrackLyrics {
                requireCurrent()
                validMusicEntityId(trackId)
                val unavailable = TrackLyrics(trackId, LyricsState.UNAVAILABLE, "", "")
                val result = withContext(Dispatchers.IO) {
                    if (trackId !in availableTrackIds()) return@withContext unavailable
                    val sourced = localLyrics?.lyrics(trackId)
                    if (sourced != null) {
                        if (sourced.trackId != trackId) throw TrackContentException(TrackContentFailure.INVALID_RESPONSE)
                        return@withContext if (trackId in availableTrackIds()) sourced else unavailable
                    }
                    local { tracks().lyricMisses[trackId]?.lyrics(trackId) } ?: unavailable
                }
                requireCurrent()
                return result
            }

            private fun requireCurrent() {
                if (!isCurrent) throw CancellationException("Local catalog owner retired")
            }
        }

    override suspend fun detail(kind: MusicKind, id: String): MusicItem = withIndex {
        it.detail(kind, validMusicEntityId(id)) ?: throw LibraryException(404)
    }

    override suspend fun artistAlbums(id: String, offset: Int): MusicPage = withIndex {
        page(it.artistAlbums[validMusicEntityId(id)].orEmpty(), offset, PAGE_SIZE, it.revision)
    }

    override suspend fun albumTracks(id: String, offset: Int): MusicPage = withIndex {
        page(it.albumTracks[validMusicEntityId(id)].orEmpty(), offset, PAGE_SIZE, it.revision)
    }

    override suspend fun search(kind: MusicKind, query: String, offset: Int): MusicPage {
        require(validQuery(query))
        validOffset(offset)
        return withIndex { page(it.ranked(query, kind), offset, LIBRARY_SEARCH_PAGE_SIZE, it.revision) }
    }

    override suspend fun search(query: String, kind: MusicKind?, limit: Int): SearchPage {
        if (!validQuery(query) || limit !in 1..50) throw SearchException(SearchErrorCode.INVALID_SEARCH_REQUEST)
        return withIndex {
            val now = clock.instant()
            val results = it.ranked(query, kind).take(MAX_RETAINED_SEARCH_RESULTS)
            val key = UUID.randomUUID().toString()
            val snapshot = RetainedSearch(results, limit, now.plusSeconds(SEARCH_LIFETIME_SECONDS))
            if (results.size > limit) {
                searches.values.removeAll { search -> now >= search.expiresAt }
                if (searches.size == MAX_SEARCHES) searches.remove(searches.keys.first())
                searches[key] = snapshot
            }
            snapshot.page(key, 0)
        }
    }

    override suspend fun next(cursor: String): SearchPage = withIndex {
        // Includes the page boundary so retries are idempotent; snapshots are bounded and owner-local.
        if (cursor.length !in 38..40 || cursor[36] != ':') throw SearchException(SearchErrorCode.INVALID_SEARCH_REQUEST)
        val key = cursor.substring(0, 36)
        val offset = cursor.substring(37).toIntOrNull()?.takeIf { it > 0 }
            ?: throw SearchException(SearchErrorCode.INVALID_SEARCH_REQUEST)
        val snapshot = searches[key] ?: throw SearchException(SearchErrorCode.SEARCH_EXPIRED)
        if (clock.instant() >= snapshot.expiresAt) {
            searches.remove(key)
            throw SearchException(SearchErrorCode.SEARCH_EXPIRED)
        }
        if (offset >= snapshot.items.size || offset % snapshot.limit != 0)
            throw SearchException(SearchErrorCode.INVALID_SEARCH_REQUEST)
        snapshot.page(key, offset)
    }

    private data class RetainedSearch(val items: List<MusicItem>, val limit: Int, val expiresAt: Instant) {
        fun page(key: String, offset: Int): SearchPage {
            val end = minOf(items.size, offset + limit)
            return SearchPage(items.subList(offset, end).toList(), items.size,
                if (end < items.size) "$key:$end" else null, expiresAt)
        }
    }

    private suspend fun <T> withIndex(read: (LocalCatalogIndex) -> T): T = local {
        val available = availableTrackIds()
        val data = tracks()
        val relevant = data.tracks.keys.filterTo(hashSetOf(), available::contains)
        if (index == null || relevant != indexedAvailable) {
            index = LocalCatalogIndex(++revision, relevant, data)
            indexedAvailable = relevant
            searches.clear()
        }
        read(requireNotNull(index))
    }

    private fun invalidateIndex() {
        index = null
        searches.clear()
    }

    private fun tracks() = tracks ?: storage.loadTracks().also { tracks = it }

    private suspend fun <T> local(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block() }
    }

    private companion object {
        const val MAX_TRACKS = 10_000
        const val MAX_LYRIC_MISSES = 2_000
        const val MAX_SEARCHES = 16
        const val SEARCH_LIFETIME_SECONDS = 300L
        const val PAGE_SIZE = 40
        const val LIBRARY_SEARCH_PAGE_SIZE = 10

        // Reject large pasted input before allocating its UTF-8 representation in validSearchQuery.
        fun validQuery(query: String) = query.length <= 400 && query.isNotBlank() && validSearchQuery(query)
        fun validOffset(offset: Int) = require(offset in 0..1_000_000)

        fun page(items: List<MusicItem>, offset: Int, limit: Int, revision: Long): MusicPage {
            validOffset(offset)
            val start = minOf(offset, items.size)
            val end = minOf(start + limit, items.size)
            return MusicPage(items.subList(start, end).toList(), items.size.toLong(), offset, limit, revision)
        }
    }
}
