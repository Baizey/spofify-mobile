package dev.spofify.mobile.core.personal

import java.time.Clock
import java.time.Instant
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
)

@Serializable
data class PersonalUpdate(val snapshot: PersonalSnapshot? = null, val change: PersonalChange? = null)

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
)

data class PersonalReplicaView(
    val connected: Boolean = false,
    val playlists: List<PersonalPlaylistSummary> = emptyList(),
    val recent: List<PersonalHistoryEntry> = emptyList(),
    val details: Map<String, ReplicaPlaylist> = emptyMap(),
    val ready: Boolean = false,
    val historyKnown: Boolean = false,
    val listsKnown: Boolean = false,
)

/** Player-looper reducer. A server delta is already committed. Its resource version
 * invalidates reads started before the delta; only complete reads may replace snapshots. */
class AccountPersonalReplica(private val clock: Clock = Clock.systemUTC(), private val freshnessMs: Long = 300_000) {
    private val mutable = MutableStateFlow(PersonalReplicaView())
    val view = mutable.asStateFlow()
    private val durable = MutableStateFlow(PersonalCacheSnapshot())
    val snapshots = durable.asStateFlow()
    private var connected = false
    private var online = false
    private var ready = false
    private var available = emptySet<String>()
    // WS recent deltas are relative to the initial socket window, not to an HTTP refresh or local event.
    private var socketRecent = emptyList<PersonalHistoryEntry>()
    private val versions = mutableMapOf<Resource, Long>()

    sealed interface Resource {
        data object Lists : Resource
        data object History : Resource
        data class Detail(val id: String) : Resource
    }
    fun version(resource: Resource) = versions[resource] ?: 0L
    private fun changed(resource: Resource) { versions[resource] = version(resource) + 1 }

    fun restore(data: PersonalCacheSnapshot) { check(!ready); ready = true; publish(data) }

    fun availability(online: Boolean, ids: Set<String>) {
        this.online = online
        available = ids
        publish(durable.value)
    }

    fun disconnected() { connected = false; socketRecent = emptyList(); publish(durable.value) }

    fun stale(resource: Resource): Boolean {
        val data = durable.value
        val at = when (resource) {
            Resource.Lists -> data.listsAt
            Resource.History -> data.recentAt
            is Resource.Detail -> data.details[resource.id]?.refreshedAt
        }
        return at == null || clock.millis() - at !in 0 until freshnessMs
    }

    fun replaceLists(version: Long, lists: List<PersonalPlaylistSummary>) {
        if (version != version(Resource.Lists)) return
        val data = durable.value
        val ids = lists.associateBy { it.id }
        check(ids.size == lists.size)
        // Keep old complete details while a changed revision refreshes, but make them stale.
        data.playlists.filterNot { it.id in ids }.forEach { changed(Resource.Detail(it.id)) }
        val details = data.details.filterKeys(ids::containsKey).mapValues { (id, detail) ->
            if (detail.revision == ids.getValue(id).revision) detail else detail.copy(refreshedAt = null)
        }
        publish(data.copy(playlists = lists, listsAt = clock.millis(), details = details))
    }

    fun replaceDetail(version: Long, summary: PersonalPlaylistSummary, rows: List<PersonalManualEntry>) {
        val resource = Resource.Detail(summary.id)
        if (version != version(resource)) return
        val data = durable.value
        if (data.playlists.any { it.id == summary.id && it.revision > summary.revision }) return
        val detail = ReplicaPlaylist(summary.revision, rows, clock.millis())
        if (data.playlists.firstOrNull { it.id == summary.id } != summary) changed(Resource.Lists)
        publish(data.copy(playlists = replace(data.playlists, summary), details = data.details + (summary.id to detail)))
    }

    /** Download manifests supply a complete ordered collection; never replace a network snapshot. */
    fun retainDownload(summary: PersonalPlaylistSummary, rows: List<PersonalManualEntry>) {
        val data = durable.value
        if (summary.id in data.details) return
        val known = data.playlists.any { it.id == summary.id }
        publish(data.copy(playlists = if (known) data.playlists else replace(data.playlists, summary),
            details = data.details + (summary.id to ReplicaPlaylist(summary.revision, rows))))
    }

    fun replaceHistory(version: Long, rows: List<PersonalHistoryEntry>) {
        if (version != version(Resource.History)) return
        publish(mergeServerHistory(durable.value, rows).copy(recentAt = clock.millis()))
    }

    fun recordLocal(entry: PersonalHistoryEntry) {
        val data = durable.value
        if (data.recent.any { key(it) == key(entry) }) return
        publish(data.copy(localPlays = (listOf(entry) + data.localPlays).distinctBy(::key).take(LOCAL_HISTORY_LIMIT)))
    }

    /** Returns the resource to refresh when a confirmed edit cannot update its rows exactly. */
    fun receive(update: PersonalUpdate): Resource.Detail? {
        require((update.snapshot == null) != (update.change == null))
        update.snapshot?.let { snap ->
            require(validRecent(snap.recent))
            connected = true
            socketRecent = snap.recent
            changed(Resource.History)
            replaceHistory(version(Resource.History), socketRecent)
            // The socket playlist list is a bounded prefix. Never install it as the complete list.
            return null
        }
        if (!connected) return null
        val delta = requireNotNull(update.change)
        var next = durable.value
        var repair: Resource.Detail? = null
        delta.recent?.let { edit ->
            val removed = edit.removed.toHashSet()
            val items = socketRecent.filterNot { key(it) in removed }.toMutableList()
            for ((index, item) in edit.inserted) {
                require(index in 0..items.size)
                items.add(index, item)
            }
            require(validRecent(items))
            val before = socketRecent.mapTo(hashSetOf(), ::key)
            val additional = next.recent.filterNot { key(it) in before }
            socketRecent = items
            changed(Resource.History)
            next = mergeServerHistory(next, (items + additional).distinctBy(::key)
                .sortedByDescending { Instant.parse(it.playedAt) }.take(RECENT_LIMIT))
        }
        delta.deleted?.let { id ->
            changed(Resource.Lists); changed(Resource.Detail(id))
            next = next.copy(playlists = next.playlists.filterNot { it.id == id }, details = next.details - id)
        }
        delta.playlist?.let { summary ->
            val id = summary.id
            val resource = Resource.Detail(id)
            if (next.playlists.any { it.id == id && it.revision > summary.revision }) return@let
            changed(Resource.Lists); changed(resource)
            next = next.copy(playlists = replace(next.playlists, summary))
            val old = next.details[id]
            val rows = old?.takeIf { !delta.rowsInvalid && it.revision == summary.revision - 1 }
                ?.let { applyEntries(it.rows, delta.entries) }
            if (rows != null) {
                next = next.copy(details = next.details + (id to old.copy(revision = summary.revision, rows = rows)))
            } else {
                if (old != null) next = next.copy(details = next.details + (id to old.copy(refreshedAt = null)))
                repair = resource
            }
        }
        publish(next)
        return repair
    }

    private fun publish(data: PersonalCacheSnapshot) {
        durable.value = data
        val recent = (data.recent + data.localPlays).distinctBy(::key)
            .sortedByDescending { Instant.parse(it.playedAt) }.take(RECENT_LIMIT)
        fun PersonalTrack.visible() = copy(hasAudio = if (online) hasAudio || id in available else id in available)
        val details = data.details.mapValues { (_, detail) ->
            detail.copy(rows = detail.rows.map { it.copy(track = it.track.visible()) })
        }
        val lists = if (connected) data.playlists else data.playlists.map {
            it.copy(capabilities = PersonalPlaylistCapabilities(false, false, false, it.capabilities.play, false, false, false))
        }
        mutable.value = PersonalReplicaView(connected, lists, recent.map { it.copy(track = it.track.visible()) }, details,
            ready, data.recentAt != null || data.localPlays.isNotEmpty(), data.listsAt != null || data.playlists.isNotEmpty())
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

    private fun replace(rows: List<PersonalPlaylistSummary>, p: PersonalPlaylistSummary) = rows.filterNot { it.id == p.id } + p
    private fun key(entry: PersonalHistoryEntry) = RecentKey(entry.id, entry.origin.orEmpty())
    private fun validRecent(rows: List<PersonalHistoryEntry>) = rows.size <= RECENT_LIMIT &&
        rows.distinctBy(::key).size == rows.size && rows.all { runCatching { Instant.parse(it.playedAt) }.isSuccess }
    private fun mergeServerHistory(data: PersonalCacheSnapshot, rows: List<PersonalHistoryEntry>): PersonalCacheSnapshot {
        val keys = rows.mapTo(hashSetOf(), ::key)
        return data.copy(recent = rows, localPlays = data.localPlays.filterNot { key(it) in keys })
    }

    companion object {
        const val RECENT_LIMIT = 40
        private const val LOCAL_HISTORY_LIMIT = 10_000
    }
}
