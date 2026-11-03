package dev.spofify.mobile.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** Independent ten-row search categories, never one mixed page split client-side. */
data class CatalogSearch(val artists: LibraryPage, val albums: LibraryPage, val tracks: LibraryPage)

/** Saved listener catalog only: no providers, jobs, provenance, owners or filesystem paths. */
class CatalogApi(client: SpofifyClient) {
    private val transport = ApiTransport(client)

    suspend fun browse(
        kind: String = "albums",
        query: String = "",
        availability: String = "in-library",
        limit: Int = 40,
        offset: Int = 0,
        artistId: String? = null,
        albumId: String? = null,
        releaseGroup: String? = null,
    ): LibraryPage {
        require(kind in setOf("artists", "albums", "tracks", "all")) { "Unsupported library kind" }
        require(availability in setOf("all", "in-library", "complete", "partial", "metadata")) { "Invalid availability filter" }
        require(limit in 1..100) { "Invalid page limit" }
        require(offset in 0..1_000_000) { "Invalid page offset" }
        require(artistId == null || albumId == null) { "Choose one library parent" }
        artistId?.let(::validateCatalogId)
        albumId?.let(::validateCatalogId)
        val child = artistId != null || albumId != null
        val effectiveKind = when {
            albumId != null -> "tracks"
            artistId != null -> "albums"
            else -> kind
        }
        if (releaseGroup != null) require(
            artistId != null && effectiveKind == "albums" && releaseGroup in setOf("albums", "singles", "appearances", "all")
        ) { "Release groups require an artist's albums" }
        if (!child) validateCatalogQuery(query)
        val parameters = linkedMapOf(
            "kind" to effectiveKind,
            "scope" to "all",
            "availability" to if (child) "all" else availability,
            "limit" to limit.toString(),
            "offset" to offset.toString(),
        )
        if (!child && query.isNotEmpty()) parameters["q"] = query
        artistId?.let { parameters["artistId"] = it }
        albumId?.let { parameters["albumId"] = it }
        releaseGroup?.let { parameters["releaseGroup"] = it }
        return decode(transport.get(listOf("api", "library"), parameters))
    }

    /** Details and child lists deliberately do not inherit the outer browse/search filters. */
    suspend fun detail(kind: String, id: String): LibraryItem {
        val plural = when (kind) {
            "artist" -> "artists"
            "album" -> "albums"
            "track" -> "tracks"
            else -> throw IllegalArgumentException("Unsupported library kind")
        }
        validateCatalogId(id)
        return decode<CatalogDetail>(transport.get(listOf("api", "library", plural, id))).item
    }

    suspend fun revision(): Long = decode<CatalogRevision>(transport.get(listOf("api", "library", "revision"))).revision

    suspend fun groupedSearch(
        query: String,
        availability: String = "in-library",
        artistOffset: Int = 0,
        albumOffset: Int = 0,
        trackOffset: Int = 0,
    ): CatalogSearch = coroutineScope {
        // Validate every page before starting any of the independent requests.
        validateCatalogQuery(query)
        require(query.isNotBlank()) { "Enter a search query" }
        require(availability in setOf("all", "in-library", "complete", "partial", "metadata")) { "Invalid availability filter" }
        require(listOf(artistOffset, albumOffset, trackOffset).all { it in 0..1_000_000 }) { "Invalid page offset" }
        val artists = async { browse("artists", query, availability, 10, artistOffset) }
        val albums = async { browse("albums", query, availability, 10, albumOffset) }
        val tracks = async { browse("tracks", query, availability, 10, trackOffset) }
        CatalogSearch(artists.await(), albums.await(), tracks.await())
    }

    private suspend inline fun <reified T> decode(value: JsonObject): T = withContext(Dispatchers.Default) {
        ensureActive()
        try {
            ApiJson.decodeFromJsonElement<T>(value).also { ensureActive() }
        } catch (_: IllegalArgumentException) {
            throw ApiException(200, "The server returned an incompatible JSON response")
        }
    }
}

@Serializable
private data class CatalogDetail(val item: LibraryItem, val revision: Long = 0)

@Serializable
private data class CatalogRevision(val revision: Long)

private fun validateCatalogId(id: String) {
    require(id.isNotEmpty() && id != "." && id != ".." && id.trim() == id &&
        id.toByteArray(Charsets.UTF_8).size <= 512 && id.none { it.isISOControl() } && validUnicode(id)) { "Invalid identifier" }
}

private fun validateCatalogQuery(query: String) {
    require(query.codePointCount(0, query.length) <= 200 && query.toByteArray(Charsets.UTF_8).size <= 1000 &&
        query.none { it.isISOControl() } && validUnicode(query)) { "Search must be at most 200 characters without control characters" }
}

private fun validUnicode(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val character = value[index++]
        if (character.isHighSurrogate()) {
            if (index == value.length || !value[index++].isLowSurrogate()) return false
        } else if (character.isLowSurrogate()) return false
    }
    return true
}
