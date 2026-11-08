package dev.spofify.mobile

import androidx.media3.datasource.DataSource
import dev.spofify.mobile.core.LibraryItem

/** Process-local read reference built ONLY by the saved-owner adapter for one immutable installed
 * snapshot + exact occurrence/descriptor. The opener must reject replaced/missing generations and
 * retain the verified SavedMediaLease; it is not eligibility or any kind of server authority. */
class OfflineMediaReference internal constructor(
    internal val owner: OfflineLibraryAccess,
    internal val origin: String,
    internal val accountId: String,
    internal val openExactSnapshotOccurrence: suspend () -> DataSource?,
)

/** Immutable presentation of one installed saved snapshot. Availability belongs to the verified
 * retained-media owner, never to file existence, a network URL or the automatic audio cache. */
class OfflineSavedCollection(
    val target: LibraryItem,
    rows: List<LibraryItem>,
    mediaReferences: Map<String, OfflineMediaReference>,
    val complete: Boolean,
) {
    val rows = rows.toList()
    val mediaReferences = mediaReferences.toMap()
    init {
        require(target.id.isNotBlank())
        require(rows.all { it.kind == "track" && !it.entryId.isNullOrBlank() })
        require(rows.map { it.entryId }.distinct().size == rows.size)
        val occurrences = rows.map { it.entryId }.toSet()
        require(mediaReferences.keys.all { it in occurrences })
    }
    /** COMPLETE is owner state, not a claim that metadata-only rows have playable audio. */
    val playableInFull: Boolean get() = complete && mediaReferences.size == rows.size
    val availableEntryIds: Set<String> get() = mediaReferences.keys
    val availableRows: List<LibraryItem> get() = rows.filter { it.entryId in mediaReferences }
}

/** Read-only adapter to the saved-download owner. Implementations must scope every result/read to
 * canonical origin + immutable account and use the saved owner's verified read factory. Retirement
 * fences this adapter only; actual namespace purge belongs to the saved owner's known-auth hooks.
 * No API fallback, partial files or automatic-cache borrowing. */
interface OfflineLibraryAccess {
    val revision: kotlinx.coroutines.flow.StateFlow<Long>
    suspend fun collections(origin: String, accountId: String): List<OfflineSavedCollection>
    fun retire()
}
