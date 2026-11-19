package dev.spofify.mobile.core.personal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

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
    val offset: Int = 0,
)

@Serializable
data class PersonalUpdate(val snapshot: PersonalSnapshot? = null, val part: PersonalPart? = null, val change: PersonalChange? = null)

@Serializable
data class PersonalSnapshot(val playlists: List<PersonalPlaylistSummary>, val recent: List<PersonalHistoryEntry>)

@Serializable
data class PersonalPart(
    val summary: PersonalPlaylistSummary, val token: String, val offset: Int,
    val rows: List<PersonalManualEntry>, val more: Boolean,
)

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

data class ReplicaPlaylist(val token: String, val rows: List<PersonalManualEntry>)
data class PersonalReplicaView(
    val connected: Boolean = false,
    val playlists: List<PersonalPlaylistSummary> = emptyList(),
    val recent: List<PersonalHistoryEntry> = emptyList(),
    val details: Map<String, ReplicaPlaylist> = emptyMap(),
)

/** State belongs to the account, not to the socket lifetime. No transport call, waiting for an
 * acknowledgment, or deferred command queue. The socket generation owns dispatch of returned sync intents. */
class AccountPersonalReplica {
    private val lock = Any()
    private val mutable = MutableStateFlow(PersonalReplicaView())
    val view = mutable.asStateFlow()
    private val watches = mutableMapOf<String, Int>()
    private val building = mutableMapOf<String, ReplicaPlaylist>()
    private val confirmed = mutableSetOf<String>()

    fun watch(id: String): PersonalIntent? = synchronized(lock) {
        val count = watches.getOrDefault(id, 0)
        watches[id] = count + 1
        val v = mutable.value
        if (count == 0 && v.connected && v.playlists.any { it.id == id } && id !in confirmed) {
            sync(id, v.details[id]?.token)
        } else null
    }

    fun unwatch(id: String) = synchronized(lock) {
        val count = watches[id] ?: return@synchronized
        if (count == 1) { watches.remove(id); building.remove(id) } else watches[id] = count - 1
    }

    fun disconnected() = synchronized(lock) {
        building.clear(); confirmed.clear()
        mutable.value = mutable.value.copy(connected = false)
    }

    fun clear() = synchronized(lock) {
        watches.clear(); building.clear(); confirmed.clear()
        mutable.value = PersonalReplicaView()
    }

    fun receive(update: PersonalUpdate): List<PersonalIntent> = synchronized(lock) {
        require(listOf(update.snapshot, update.part, update.change).count { it != null } == 1)
        val v = mutable.value
        update.snapshot?.let { snap ->
            val ids = snap.playlists.mapTo(HashSet()) { it.id }
            require(ids.size == snap.playlists.size)
            building.clear(); confirmed.clear()
            mutable.value = PersonalReplicaView(true, sorted(snap.playlists), snap.recent, v.details.filterKeys { it in ids })
            return@synchronized watches.keys.filter { it in ids }.map { sync(it, v.details[it]?.token) }
        }
        if (!v.connected) return@synchronized emptyList()
        update.part?.let { part ->
            val id = part.summary.id
            if (id !in watches || v.playlists.none { it.id == id }) return@synchronized emptyList()
            require(part.token.isNotEmpty() && part.offset >= 0 && (!part.more || part.rows.isNotEmpty()))
            val old = building[id]
            if (part.offset > 0 && (old?.token != part.token || old.rows.size != part.offset)) {
                building.remove(id)
                return@synchronized listOf(sync(id))
            }
            val rows = (if (part.offset == 0) emptyList() else requireNotNull(old).rows) + part.rows
            if (part.more) {
                building[id] = ReplicaPlaylist(part.token, rows)
                return@synchronized listOf(sync(id, part.token, rows.size))
            }
            require(rows.mapTo(HashSet()) { it.id }.size == rows.size)
            building.remove(id); confirmed.add(id)
            mutable.value = v.copy(playlists = replace(v.playlists, part.summary), details = v.details + (id to ReplicaPlaylist(part.token, rows)))
            return@synchronized emptyList()
        }
        val delta = requireNotNull(update.change)
        var next = v
        val jobs = mutableListOf<PersonalIntent>()
        delta.recent?.let { edit ->
            val removed = edit.removed.toHashSet()
            val items = next.recent.filterNot { RecentKey(it.id, it.origin.orEmpty()) in removed }.toMutableList()
            for ((index, item) in edit.inserted) items.add(index, item)
            next = next.copy(recent = items)
        }
        delta.deleted?.let { id ->
            confirmed.remove(id); building.remove(id)
            next = next.copy(playlists = next.playlists.filterNot { it.id == id }, details = next.details - id)
        }
        delta.playlist?.let { summary ->
            val id = summary.id
            next = next.copy(playlists = replace(next.playlists, summary))
            val old = next.details[id]
            val rows = old?.let { applyEntries(it.rows, delta.entries) }
            val repair = building.remove(id) != null || delta.rowsInvalid || rows == null
            if (old != null && rows != null) next = next.copy(details = next.details + (id to ReplicaPlaylist("", rows)))
            if (repair) { confirmed.remove(id); if (id in watches) jobs += sync(id) }
        }
        mutable.value = next
        jobs
    }

    private fun applyEntries(original: List<PersonalManualEntry>, edit: EntryChange?): List<PersonalManualEntry>? {
        if (edit == null) return original
        val rows = original.toMutableList()
        edit.added?.let { row -> if (rows.any { it.id == row.id }) return null else rows.add(row) }
        edit.removed?.let { id -> if (!rows.removeAll { it.id == id }) return null }
        edit.moved?.let { id ->
            val from = rows.indexOfFirst { it.id == id }
            if (from < 0) return null
            val row = rows.removeAt(from)
            val after = edit.after.orEmpty()
            val to = if (after.isEmpty()) 0 else rows.indexOfFirst { it.id == after } + 1
            if (to == 0 && after.isNotEmpty()) return null
            rows.add(to, row)
        }
        return rows
    }

    private fun replace(rows: List<PersonalPlaylistSummary>, p: PersonalPlaylistSummary) = sorted(rows.filterNot { it.id == p.id } + p)
    private fun sorted(rows: List<PersonalPlaylistSummary>) = rows.sortedWith(compareByDescending<PersonalPlaylistSummary> { it.updatedAt }.thenBy { it.id })
    private fun sync(id: String, known: String? = null, offset: Int = 0) =
        PersonalIntent("syncPlaylist", playlistId = id, known = known?.takeUnless(String::isEmpty), offset = offset)
}
