package dev.spofify.mobile.core.search

import dev.spofify.mobile.core.library.MusicItem
import dev.spofify.mobile.core.library.MusicKind
import java.time.Instant

/** The service retains at most this many ranked results per snapshot, not all matches. */
const val MAX_RETAINED_SEARCH_RESULTS = 300

interface SearchApi {
    suspend fun search(query: String, kind: MusicKind? = null, limit: Int = 25): SearchPage
    suspend fun next(cursor: String): SearchPage
}

/** Server-ranked retained snapshot, not a catalog total or playback authority. */
data class SearchPage(
    val items: List<MusicItem>,
    val resultCount: Int,
    val nextCursor: String?,
    val expiresAt: Instant,
)

enum class SearchErrorCode {
    INVALID_SEARCH_REQUEST, SEARCH_EXPIRED, SEARCH_INDEX_NOT_READY, SEARCH_UNAVAILABLE, SEARCH_BUSY,
    INVALID_RESPONSE, CONNECTION_FAILED, HTTP_ERROR,
}

/** Raw server diagnostics, queries, cursors and transport causes never escape this boundary. */
class SearchException(val code: SearchErrorCode, val status: Int = 0) : Exception("Search request failed")
