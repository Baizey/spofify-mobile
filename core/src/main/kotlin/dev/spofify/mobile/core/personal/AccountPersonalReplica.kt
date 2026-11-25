package dev.spofify.mobile.core.personal

import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class PersonalIntent(
    val type: String,
    val playlistId: String? = null,
    val trackId: String? = null,
    val entryId: String? = null,
    val after: String? = null,
    val name: String? = null,
    val description: String? = null,
    val known: String? = null,
    val offset: Int? = null,
)

@Serializable
data class PersonalUpdate(
    val snapshot: PersonalSnapshot? = null, val change: PersonalChange? = null, val part: PlaylistPart? = null,
    val missing: PlaylistMissing? = null,
) {
    init { require(listOf(snapshot, change, part, missing).count { it != null } == 1) }
}

@Serializable
data class PlaylistMissing(val playlistId: String) {
    init { require(playlistId.isNotBlank()) }
}

@Serializable
data class PlaylistPart(
    val summary: PersonalPlaylistSummary,
    val definition: JsonObject? = null,
    val token: String,
    val offset: Int,
    val rows: List<PersonalManualEntry>,
    val more: Boolean,
)

@Serializable
data class PersonalSnapshot(val playlists: List<PersonalPlaylistSummary>, val recent: List<PersonalHistoryEntry>)

@Serializable
data class PersonalChange(
    val playlist: PersonalPlaylistSummary? = null, val deleted: String? = null,
    val entries: EntryChange? = null, val recent: RecentChange? = null, val rowsInvalid: Boolean = false,
)

@Serializable
data class EntryChange(val added: PersonalManualEntry? = null, val removed: String? = null,
    val moved: String? = null, val after: String? = null)

@Serializable
data class RecentChange(val removed: List<RecentKey> = emptyList(), val inserted: List<RecentInsertion> = emptyList())

@Serializable
data class RecentKey(val id: String, val origin: String = "")

@Serializable
data class RecentInsertion(val index: Int, val item: PersonalHistoryEntry)

@Serializable
data class ReplicaPlaylist(val revision: Long, val rows: List<PersonalManualEntry>, val refreshedAt: Long? = null)

@Serializable
data class PersonalCacheSnapshot(
    val playlists: List<PersonalPlaylistSummary> = emptyList(),
    val recent: List<PersonalHistoryEntry> = emptyList(),
    val localPlays: List<PersonalHistoryEntry> = emptyList(),
    val details: Map<String, ReplicaPlaylist> = emptyMap(),
    val listsAt: Long? = null,
    val recentAt: Long? = null,
    val listsComplete: Boolean = false,
    val deletedPlaylists: Set<String> = emptySet(),
)

data class PersonalReplicaView(
    val connected: Boolean = false,
    val playlists: List<PersonalPlaylistSummary> = emptyList(),
    val recent: List<PersonalHistoryEntry> = emptyList(),
    val details: Map<String, ReplicaPlaylist> = emptyMap(),
    val ready: Boolean = false,
    val historyKnown: Boolean = false,
    val listsKnown: Boolean = false,
    val listsComplete: Boolean = false,
    val deletedPlaylists: Set<String> = emptySet(),
    val sharedPlayablePlaylistIds: Set<String> = emptySet(),
)

/** Player-looper reducer. Only complete scoped reads replace accepted rows; versions fence
 * transfers against intervening confirmed changes. Recent deltas target the sole server window. */
class AccountPersonalReplica(private val clock: Clock = Clock.systemUTC(), private val freshnessMs: Long = 300_000) {
    private val mutable = MutableStateFlow(PersonalReplicaView())
    val view = mutable.asStateFlow()
    private val durable = MutableStateFlow(PersonalCacheSnapshot())
    val snapshots = durable.asStateFlow()
    private var connected = false
    private var confirmed = emptySet<String>()
    private var online = false
    private var ready = false
    private var available = emptySet<String>()
    private val versions = mutableMapOf<String, Long>()
    private val historyOrder = compareByDescending<PersonalHistoryEntry> { Instant.parse(it.playedAt) }
        .thenBy { it.id }.thenBy { it.origin.orEmpty() }
    fun version(id: String) = versions[id] ?: 0L
    private fun changed(id: String) { versions[id] = version(id) + 1 }

    fun restore(data: PersonalCacheSnapshot) { check(!ready); ready = true; publish(data) }

    fun availability(online: Boolean, ids: Set<String>) {
        this.online = online
        available = ids
        publish(durable.value)
    }

    fun disconnected() {
        connected = false
        confirmed = emptySet()
        publish(durable.value)
    }

    private fun stale(at: Long?) = at == null || clock.millis() - at !in 0 until freshnessMs
    fun accountStale() = durable.value.let { stale(it.listsAt) || stale(it.recentAt) }
    fun detailStale(id: String) = stale(durable.value.details[id]?.refreshedAt)
    fun confirmsPlaylist(id: String) = id in confirmed

    fun canPlaySharedPlaylist(id: String): Boolean {
        val data = durable.value
        val summary = data.playlists.firstOrNull { it.id == id } ?: return false
        return sharedPlayable(summary, data.details[id])
    }

    private fun sharedPlayable(summary: PersonalPlaylistSummary, detail: ReplicaPlaylist?): Boolean =
        confirmsPlaylist(summary.id) && summary.capabilities.play && (summary.kind == PersonalPlaylistKind.MANUAL ||
            summary.projection() == PlaylistProjection.CURRENT && detail?.revision == summary.revision && detail.refreshedAt != null)

    /** Accept the summary independently of the rows. A non-ready evaluation invalidates
     * freshness but never evicts last-good rows, including a previously accepted empty result. */
    fun replaceDetail(version: Long, summary: PersonalPlaylistSummary, rows: List<PersonalManualEntry>) {
        val id = summary.id
        if (!confirmsPlaylist(id) || version != version(id)) return
        val data = durable.value
        val prior = data.playlists.firstOrNull { it.id == id } ?: return
        if (summary.revision < prior.revision || summary.kind != prior.kind) return
        val projection = summary.projection() ?: return
        if (rows.size.toLong() != (summary.trackCount ?: 0L)) return
        val old = data.details[id]
        val detail = when (projection) {
            PlaylistProjection.CURRENT -> ReplicaPlaylist(summary.revision, rows, clock.millis())
            PlaylistProjection.STORED -> old?.copy(refreshedAt = null) ?: ReplicaPlaylist(summary.revision, rows)
            PlaylistProjection.UNMATERIALIZED -> old?.copy(refreshedAt = null)
        }
        publish(data.copy(playlists = replace(data.playlists, summary), details =
            if (detail != null) data.details + (id to detail) else data.details))
    }

    /** A manifest supplies offline rows, never overrides authoritative membership or accepted rows. */
    fun retainDownload(summary: PersonalPlaylistSummary, rows: List<PersonalManualEntry>) {
        val data = durable.value
        if (summary.id in data.details || summary.id in data.deletedPlaylists) return
        val current = data.playlists.firstOrNull { it.id == summary.id }
        if (current == null && data.listsComplete) return
        publish(data.copy(playlists = if (current != null) data.playlists else replace(data.playlists, summary),
            details = data.details + (summary.id to ReplicaPlaylist(summary.revision, rows))))
    }

    fun recordLocal(entry: PersonalHistoryEntry) {
        val data = durable.value
        if (data.recent.any { key(it) == key(entry) }) return
        publish(data.copy(localPlays = (listOf(entry) + data.localPlays).distinctBy(::key).take(LOCAL_HISTORY_LIMIT)))
    }

    fun receiveSnapshot(snap: PersonalSnapshot) {
        require(validRecent(snap.recent))
        val ids = snap.playlists.associateBy { it.id }
        require(ids.size == snap.playlists.size && ids.size <= PLAYLIST_QUOTA && snap.playlists.all(::validPlaylistSummary))
        val data = durable.value
        // Go emits only a complete list, including at the account quota.
        val removed = data.playlists.filterNot { it.id in ids }.mapTo(hashSetOf()) { it.id }
        val prior = data.playlists.associateBy { it.id }
        require(snap.playlists.none { it.revision < (prior[it.id]?.revision ?: 0) || it.id in data.deletedPlaylists })
        val invalidated = snap.playlists.filter { it != prior[it.id] }.mapTo(hashSetOf()) { it.id } + removed
        invalidated.forEach(::changed)
        confirmed = ids.keys
        connected = true
        val now = clock.millis()
        publish(mergeServerHistory(data, snap.recent).copy(
            playlists = snap.playlists,
            listsAt = now, recentAt = now, listsComplete = true,
            deletedPlaylists = data.deletedPlaylists + removed,
            details = data.details.filterKeys { it !in removed }.mapValues { (id, detail) ->
                if (id in invalidated) detail.copy(refreshedAt = null) else detail
            },
        ))
    }

    /** A binding-scoped missing response has the same membership effect as a confirmed delete. */
    fun receiveMissing(id: String, version: Long) {
        if (version == version(id) && confirmsPlaylist(id)) publish(delete(durable.value, id))
    }

    private fun delete(data: PersonalCacheSnapshot, id: String): PersonalCacheSnapshot {
        confirmed -= id
        changed(id)
        return data.copy(playlists = data.playlists.filterNot { it.id == id }, details = data.details - id,
            deletedPlaylists = data.deletedPlaylists + id)
    }

    /** Returns the playlist to repair when a confirmed edit cannot update its rows exactly. */
    fun receiveChange(delta: PersonalChange): String? {
        if (!connected) return null
        require(delta.playlist == null || validPlaylistSummary(delta.playlist))
        var next = durable.value
        var repair: String? = null
        delta.recent?.let { edit ->
            val removed = edit.removed.toHashSet()
            require(removed.size == edit.removed.size && next.recent.map(::key).containsAll(removed))
            val items = next.recent.filterNot { key(it) in removed }.toMutableList()
            var last = -1
            for ((index, item) in edit.inserted) {
                require(index in (last + 1)..items.size)
                items.add(index, item)
                last = index
            }
            require(validRecent(items))
            next = mergeServerHistory(next, items)
        }
        delta.deleted?.let { next = delete(next, it) }
        delta.playlist?.let { summary ->
            val id = summary.id
            if (id in next.deletedPlaylists || next.playlists.any { it.id == id && it.revision > summary.revision }) return@let
            confirmed += id
            changed(id)
            next = next.copy(playlists = replace(next.playlists, summary))
            val old = next.details[id]
            val rows = old?.takeIf { !delta.rowsInvalid && summary.projection() == PlaylistProjection.CURRENT &&
                it.refreshedAt != null && it.revision == summary.revision - 1 }
                ?.let { applyEntries(it.rows, delta.entries) }
                ?.takeIf { it.size.toLong() == summary.trackCount }
            if (rows != null) {
                next = next.copy(details = next.details + (id to old.copy(revision = summary.revision, rows = rows)))
            } else {
                if (old != null) next = next.copy(details = next.details + (id to old.copy(refreshedAt = null)))
                repair = id
            }
        }
        publish(next)
        return repair
    }

    private fun publish(data: PersonalCacheSnapshot) {
        durable.value = data
        val recent = (data.recent + data.localPlays).distinctBy(::key).sortedWith(historyOrder).take(RECENT_LIMIT)
        fun PersonalTrack.visible(remote: Boolean) = copy(hasAudio = remote && hasAudio || id in available)
        val shared = data.playlists.filter { sharedPlayable(it, data.details[it.id]) }.mapTo(hashSetOf()) { it.id }
        val details = data.details.mapValues { (id, detail) ->
            detail.copy(rows = detail.rows.map { it.copy(track = it.track.visible(online && id in shared)) })
        }
        val lists = data.playlists.map { summary ->
            val play = if (online) summary.id in shared else summary.capabilities.play
            val capabilities = if (confirmsPlaylist(summary.id)) summary.capabilities.copy(
                play = play, copyToManual = summary.capabilities.copyToManual && summary.id in shared)
            else PersonalPlaylistCapabilities(false, false, false, play, false, false, false)
            summary.copy(capabilities = capabilities)
        }
        mutable.value = PersonalReplicaView(connected, lists, recent.map { it.copy(track = it.track.visible(online)) }, details,
            ready, data.recentAt != null || data.localPlays.isNotEmpty(), data.listsAt != null || data.playlists.isNotEmpty(),
            data.listsComplete, data.deletedPlaylists, shared)
    }

    private fun applyEntries(original: List<PersonalManualEntry>, edit: EntryChange?): List<PersonalManualEntry>? {
        if (edit == null) return original
        if (listOf(edit.added, edit.removed, edit.moved).count { it != null } != 1) return null
        val rows = original.toMutableList()
        edit.added?.let { row ->
            if (!validPlaylistTrack(row.track) || rows.any { it.id == row.id } || row.position <= (rows.lastOrNull()?.position ?: -1)) return null
            rows.add(row)
        }
        // Go compacts all persisted slots on removal, including occurrences not projected to
        // listeners. An ID alone is not an exact position patch; retain and repair the detail.
        if (edit.removed != null) return null
        edit.moved?.let { id ->
            val from = rows.indexOfFirst { it.id == id }
            if (from < 0) return null
            val row = rows.removeAt(from)
            val after = edit.after.orEmpty()
            val to = if (after.isEmpty()) 0 else rows.indexOfFirst { it.id == after } + 1
            if (to == 0 && after.isNotEmpty()) return null
            rows.add(to, row)
            // A move changes the occurrence at each slot; the wire only carries the changed ID.
            return rows.mapIndexed { i, item -> item.copy(position = original[i].position) }
        }
        return rows
    }

    private fun replace(rows: List<PersonalPlaylistSummary>, p: PersonalPlaylistSummary) = rows.filterNot { it.id == p.id } + p
    private fun key(entry: PersonalHistoryEntry) = RecentKey(entry.id, entry.origin.orEmpty())
    private fun validRecent(rows: List<PersonalHistoryEntry>) = rows.size <= RECENT_LIMIT &&
        rows.distinctBy(::key).size == rows.size && rows.all { it.id.isNotBlank() && validPlaylistTrack(it.track) &&
            runCatching { Instant.parse(it.playedAt) }.isSuccess }
    private fun mergeServerHistory(data: PersonalCacheSnapshot, rows: List<PersonalHistoryEntry>): PersonalCacheSnapshot {
        val keys = rows.mapTo(hashSetOf(), ::key)
        return data.copy(recent = rows, localPlays = data.localPlays.filterNot { key(it) in keys })
    }

    companion object {
        const val PLAYLIST_QUOTA = 100
        const val RECENT_LIMIT = 40
        private const val LOCAL_HISTORY_LIMIT = 10_000
    }
}
