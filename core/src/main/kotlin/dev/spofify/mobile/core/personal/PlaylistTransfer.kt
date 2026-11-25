package dev.spofify.mobile.core.personal

/** One requested playlist on one socket binding. Only a checked terminal part exposes rows. */
class PlaylistTransfer(val id: String, val version: Long) {
    private val rows = ArrayList<PersonalManualEntry>()
    private val occurrences = HashSet<String>()
    private var head: PlaylistPart? = null
    private var resets = 0

    fun command() = PersonalIntent("syncPlaylist", playlistId = id, known = head?.token,
        offset = rows.size.takeIf { it > 0 })

    /** Returns the complete ordered replacement, or null if the next part is needed. */
    fun accept(part: PlaylistPart): List<PersonalManualEntry>? {
        require(part.summary.id == id && validPlaylistSummary(part.summary) && token.matches(part.token))
        require((part.definition != null) == (part.summary.kind == PersonalPlaylistKind.DERIVED))
        val previous = head
        if (part.offset == 0) {
            // Go sends the latest offset-zero state if the token changed while paging.
            require(previous == null || previous.token != part.token && ++resets <= MAX_RESETS)
            rows.clear()
            occurrences.clear()
        } else {
            require(previous != null && part.offset == rows.size && part.token == previous.token &&
                part.summary == previous.summary && part.definition == previous.definition)
        }
        val expected = part.summary.trackCount ?: 0
        val end = rows.size.toLong() + part.rows.size
        require(end <= MAX_ROWS && if (part.more) part.rows.isNotEmpty() && end < expected else end == expected)
        var position = rows.lastOrNull()?.position ?: -1
        for (row in part.rows) {
            require(row.id.isNotBlank() && row.id.length <= 4096 && occurrences.add(row.id) &&
                row.position > position && validPlaylistTrack(row.track))
            position = row.position
        }
        head = part.copy(rows = emptyList())
        rows.addAll(part.rows)
        return if (part.more) null else rows.toList()
    }

    private companion object {
        val token = Regex("[0-9a-f]{64}")
        const val MAX_RESETS = 2
        const val MAX_ROWS = 250_000L // The disk cache has its own serialized byte limit.
    }
}

internal fun validPlaylistSummary(summary: PersonalPlaylistSummary): Boolean {
    if (summary.id.isBlank() || summary.revision <= 0) return false
    val (total, available) = summary.trackCount to summary.availableCount
    return when (summary.kind) {
        PersonalPlaylistKind.MANUAL -> summary.countState == PersonalCountState.EXACT &&
            total != null && available != null && available in 0..total
        PersonalPlaylistKind.DERIVED -> summary.evaluationState != null &&
            if (summary.countState == PersonalCountState.PENDING) total == null && available == null
            else total != null && available != null && available in 0..total
    }
}

internal fun validPlaylistTrack(track: PersonalTrack) = track.kind == "track" && track.id.isNotBlank() && track.durationMs >= 0
