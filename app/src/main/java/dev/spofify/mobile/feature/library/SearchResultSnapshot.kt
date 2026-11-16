package dev.spofify.mobile.feature.library

import dev.spofify.mobile.core.library.MusicItem
import dev.spofify.mobile.core.search.MAX_RETAINED_SEARCH_RESULTS
import dev.spofify.mobile.core.search.SearchPage
import java.time.Instant

/** An accepted or staged coherent prefix; never joins pages from different snapshots. */
internal class SearchResultSnapshot private constructor(
    val items: List<MusicItem>,
    val resultCount: Int,
    val expiresAt: Instant,
    val nextCursor: String?,
    private val seenCursors: Set<String>,
) {
    fun append(cursor: String, page: SearchPage): SearchResultSnapshot {
        check(nextCursor == cursor)
        check(resultCount == page.resultCount && expiresAt == page.expiresAt)
        check(page.items.isNotEmpty())
        return accept(items + page.items, page, seenCursors)
    }

    companion object {
        fun first(page: SearchPage): SearchResultSnapshot = accept(page.items.toList(), page, emptySet())

        private fun accept(items: List<MusicItem>, page: SearchPage, priorCursors: Set<String>): SearchResultSnapshot {
            check(page.resultCount in 0..MAX_RETAINED_SEARCH_RESULTS)
            check(items.distinctBy(MusicItem::key).size == items.size)
            check(items.size <= page.resultCount)
            check((page.nextCursor != null) == (items.size < page.resultCount))
            val next = page.nextCursor
            if (next != null) {
                check(page.items.isNotEmpty() && next !in priorCursors)
            }
            return SearchResultSnapshot(items, page.resultCount, page.expiresAt, next,
                if (next == null) priorCursors else priorCursors + next)
        }
    }
}
