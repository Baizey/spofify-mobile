package dev.spofify.mobile.core.personal

/** One requested playlist on one socket binding. A checked terminal part completes the wire
 * transfer, but the summary still determines whether it carries a current evaluation. */
class PlaylistTransfer(val id: String, val version: Long) {
    private val rows = ArrayList<PersonalManualEntry>()
    private val occurrences = HashSet<String>()
    private var head: PlaylistPart? = null
    private var resets = 0

    fun command() = PersonalIntent("syncPlaylist", playlistId = id, known = head?.token,
        offset = rows.size.takeIf { it > 0 })

    /** Returns the validated ordered wire rows, or null if another part is needed. */
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
        val expected = part.summary.trackCount
        val end = rows.size.toLong() + part.rows.size
        require(end <= MAX_ROWS && when {
            // No materialization: require the exact terminal status envelope, not a partial result.
            expected == null -> part.offset == 0 && end == 0L && !part.more
            part.more -> part.rows.isNotEmpty() && end < expected
            else -> end == expected
        })
        var position = rows.lastOrNull()?.position ?: -1
        for (row in part.rows) {
            require(validPlaylistRow(row) && occurrences.add(row.id) && row.position > position)
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

internal enum class PlaylistProjection { CURRENT, STORED, UNMATERIALIZED }

/** Go projects countState from whether a materialization exists and whether its evaluation is ready. */
internal fun PersonalPlaylistSummary.projection(): PlaylistProjection? {
    if (id.isBlank() || revision <= 0) return null
    val countsKnown = trackCount != null && availableCount != null && availableCount in 0..trackCount
    if (kind == PersonalPlaylistKind.MANUAL) {
        return PlaylistProjection.CURRENT.takeIf { countState == PersonalCountState.EXACT && countsKnown }
    }
    val evaluation = evaluationState ?: return null
    return when (countState) {
        PersonalCountState.EXACT -> PlaylistProjection.CURRENT.takeIf { evaluation == PersonalEvaluationState.READY && countsKnown }
        PersonalCountState.STALE -> PlaylistProjection.STORED.takeIf { evaluation != PersonalEvaluationState.READY && countsKnown }
        PersonalCountState.PENDING -> PlaylistProjection.UNMATERIALIZED.takeIf {
            evaluation != PersonalEvaluationState.READY && trackCount == null && availableCount == null
        }
    }
}

internal fun validPlaylistSummary(summary: PersonalPlaylistSummary) = summary.projection() != null

private fun validPlaylistRow(row: PersonalManualEntry) = row.id.isNotBlank() && row.id.length <= 4096 && validPlaylistTrack(row.track)

internal fun validPlaylistTrack(track: PersonalTrack) = track.kind == "track" && track.id.isNotBlank() && track.durationMs >= 0
