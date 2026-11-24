package dev.spofify.mobile.core.personal

/** Assemble only complete snapshots. A partial response never reaches the account reducer. */
class PersonalCacheReads(private val api: PersonalLibraryApi) {
    suspend fun lists(): List<PersonalPlaylistSummary> = pages { api.playlists(PAGE, it) }
        .also { check(it.distinctBy { p -> p.id }.size == it.size) }

    suspend fun history(): List<PersonalHistoryEntry> {
        val limit = AccountPersonalReplica.RECENT_LIMIT
        val page = api.history(limit, 0)
        check(page.limit == limit && page.offset == 0 && page.items.size.toLong() == minOf(page.total, limit.toLong()))
        check(page.items.distinctBy { RecentKey(it.id, it.origin.orEmpty()) }.size == page.items.size)
        return page.items
    }

    suspend fun detail(id: String): Pair<PersonalPlaylistSummary, List<PersonalManualEntry>> {
        val summary = api.playlist(id)
        val rows = when (summary.kind) {
            PersonalPlaylistKind.MANUAL -> pages(summary.trackCount) { api.manualEntries(id, PAGE, it) }
            PersonalPlaylistKind.DERIVED -> derived(summary)
        }
        check(rows.distinctBy { it.id }.size == rows.size)
        check(rows.zipWithNext().all { (a, b) -> a.position < b.position })
        val confirmed = api.playlist(id)
        check(confirmed.kind == summary.kind && confirmed.revision == summary.revision &&
            confirmed.trackCount == rows.size.toLong() && (summary.kind != PersonalPlaylistKind.DERIVED ||
                confirmed.evaluationState == PersonalEvaluationState.READY))
        return confirmed to rows
    }

    private suspend fun derived(summary: PersonalPlaylistSummary): List<PersonalManualEntry> {
        val total = requireNotNull(summary.trackCount)
        check(total in 0..MAX_ROWS)
        var cursor: String? = null
        var fence: String? = null
        val cursors = hashSetOf<String>()
        val result = mutableListOf<PersonalManualEntry>()
        do {
            val page = api.derivedEntries(summary.id, PAGE, cursor)
            check(page.complete && page.revision == summary.revision && page.total == total &&
                page.fence.isNotEmpty() && (fence == null || fence == page.fence))
            fence = page.fence
            result += page.items.map { PersonalManualEntry(it.occurrenceId, it.position, it.track) }
            cursor = page.nextCursor
            check(result.size <= total && (cursor == null || page.items.isNotEmpty() && cursors.add(cursor)))
        } while (cursor != null)
        check(result.size.toLong() == total)
        return result
    }

    private suspend fun <T> pages(expected: Long? = null, read: suspend (Int) -> PersonalPage<T>): List<T> {
        var total = expected
        val result = mutableListOf<T>()
        do {
            val page = read(result.size)
            check(page.offset == result.size && page.limit == PAGE && page.total in 0..MAX_ROWS &&
                (total == null || total == page.total))
            total = page.total
            check(page.items.size.toLong() == minOf(PAGE.toLong(), total - result.size))
            result += page.items
        } while (result.size < requireNotNull(total))
        return result
    }

    private companion object { const val PAGE = 100; const val MAX_ROWS = 100_000L }
}
