package dev.spofify.mobile.core.library

import dev.spofify.mobile.core.auth.AccountIdentity
import dev.spofify.mobile.core.auth.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Entity names match the documented API, including the web client's grouped Search. */
enum class MusicKind(val plural: String) { ARTIST("artists"), ALBUM("albums"), TRACK("tracks") }

data class Artist(val id: String, val name: String)
data class MusicItem(
    val kind: MusicKind,
    val id: String,
    val name: String,
    val artists: List<Artist>,
    val albumId: String,
    val albumName: String,
    val releaseDate: String,
    val durationMs: Long,
    val trackNumber: Int,
    val hasAudio: Boolean,
    val availableTracks: Long,
    val totalTracks: Long,
) {
    val key: String get() = "${kind.name}:$id"
}

data class MusicPage(val items: List<MusicItem>, val total: Long, val offset: Int, val limit: Int, val revision: Long) {
    val hasMore: Boolean get() = offset.toLong() + items.size < total
}

fun validSearchQuery(value: String): Boolean = value.codePointCount(0, value.length) <= 200 &&
    value.toByteArray().size <= 1000 && value.none(Char::isISOControl) && value.indices.none { index ->
        val character = value[index]
        Character.isSurrogate(character) &&
            !(Character.isHighSurrogate(character) && index + 1 < value.length && Character.isLowSurrogate(value[index + 1])) &&
            !(Character.isLowSurrogate(character) && index > 0 && Character.isHighSurrogate(value[index - 1]))
    }

class LibraryException(val status: Int) : Exception("Music request failed")

interface LibraryApi {
    suspend fun search(kind: MusicKind, query: String, offset: Int = 0): MusicPage
    suspend fun artistAlbums(id: String, offset: Int = 0): MusicPage
    suspend fun albumTracks(id: String, offset: Int = 0): MusicPage
    suspend fun detail(kind: MusicKind, id: String): MusicItem
    suspend fun artwork(kind: MusicKind, id: String): ByteArray?
}

/** No credentials, administrative paths or raw HTTP responses leave this account-bound client. */
class LibraryClient(private val sessions: SessionRepository, private val identity: AccountIdentity) : LibraryApi {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(kind: MusicKind, query: String, offset: Int): MusicPage {
        require(query.isNotBlank() && validSearchQuery(query))
        return page(listOf("kind" to kind.plural, "q" to query.trim()), offset, 10)
    }

    override suspend fun artistAlbums(id: String, offset: Int): MusicPage =
        page(listOf("kind" to "albums", "artistId" to validId(id), "releaseGroup" to "all"), offset, 40)

    override suspend fun albumTracks(id: String, offset: Int): MusicPage =
        page(listOf("kind" to "tracks", "albumId" to validId(id)), offset, 40)

    override suspend fun detail(kind: MusicKind, id: String): MusicItem = withContext(Dispatchers.Default) {
        val bytes = read(listOf("api", "library", kind.plural, validId(id)))
        val detail = json.decodeFromString<WireDetail>(bytes.decodeToString(throwOnInvalidSequence = true))
        val item = project(detail.item)
        require(item.id == id && item.kind == kind && detail.revision >= 0)
        item
    }

    override suspend fun artwork(kind: MusicKind, id: String): ByteArray? {
        val result = sessions.get(identity, listOf("api", "library", kind.plural, validId(id), "artwork"), artwork = true)
        return when (result.status) {
            200 -> result.bytes
            404 -> null
            else -> throw LibraryException(result.status)
        }
    }

    private suspend fun page(filters: List<Pair<String, String>>, offset: Int, limit: Int): MusicPage =
        withContext(Dispatchers.Default) {
            require(offset in 0..1_000_000)
            val query = filters + listOf("scope" to "all", "availability" to "in-library",
                "offset" to offset.toString(), "limit" to limit.toString())
            val body = read(listOf("api", "library"), query)
            val wire = json.decodeFromString<WirePage>(body.decodeToString(throwOnInvalidSequence = true))
            require(wire.total >= 0 && wire.offset == offset && wire.limit == limit && wire.revision >= 0)
            require(wire.items.size <= limit && (wire.items.isEmpty() || offset.toLong() + wire.items.size <= wire.total))
            val items = wire.items.map(::project)
            require(items.distinctBy(MusicItem::key).size == items.size)
            val expected = filters.first { it.first == "kind" }.second
            require(items.all { it.kind.plural == expected })
            MusicPage(items, wire.total, offset, limit, wire.revision)
        }

    private suspend fun read(segments: List<String>, query: List<Pair<String, String>> = emptyList()): ByteArray {
        val response = sessions.get(identity, segments, query)
        if (response.status != 200) throw LibraryException(response.status)
        return response.bytes
    }

    private fun project(wire: WireItem): MusicItem {
        val kind = MusicKind.entries.single { it.plural == wire.kind + "s" }
        require(wire.durationMs >= 0 && wire.trackNumber >= 0)
        require(wire.coverage.inLibrary >= 0 && wire.coverage.tracks >= wire.coverage.inLibrary)
        // The documented admin projection has libraryPath, while /listen has hasAudio.
        // Consume the former here only; operational paths never enter presentation state.
        val audio = wire.hasAudio ?: if (sessions.isAdministrator(identity)) !wire.libraryPath.isNullOrEmpty()
            else throw IllegalArgumentException("Missing audio availability")
        return MusicItem(kind, validId(wire.id), wire.name,
            wire.artists.map { Artist(validId(it.id), it.name) }, wire.albumId, wire.albumName,
            wire.releaseDate, wire.durationMs, wire.trackNumber, audio,
            wire.coverage.inLibrary, wire.coverage.tracks)
    }

    private fun validId(value: String): String = value.also {
        require(it.isNotBlank() && it.toByteArray().size <= 512 && it.none(Char::isISOControl))
    }

    @Serializable private class WireArtist(val id: String, val name: String)
    @Serializable private class WireCoverage(val tracks: Long, val inLibrary: Long)
    @Serializable private class WireItem(
        val kind: String, val id: String, val name: String = "",
        val artists: List<WireArtist> = emptyList(), val albumId: String = "", val albumName: String = "",
        val releaseDate: String = "", val durationMs: Long = 0, val trackNumber: Int = 0,
        val hasAudio: Boolean? = null, val libraryPath: String? = null, val coverage: WireCoverage,
    )
    @Serializable private class WirePage(val items: List<WireItem>, val total: Long, val offset: Int, val limit: Int, val revision: Long)
    @Serializable private class WireDetail(val item: WireItem, val revision: Long)
}
