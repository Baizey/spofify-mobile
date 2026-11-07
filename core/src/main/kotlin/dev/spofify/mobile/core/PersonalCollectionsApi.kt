package dev.spofify.mobile.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

@Serializable
enum class PlaylistKind { @SerialName("manual") MANUAL, @SerialName("derived") DERIVED }

@Serializable
enum class PlaylistCountState {
    @SerialName("exact") EXACT, @SerialName("unevaluated") UNEVALUATED,
    @SerialName("pending") PENDING, @SerialName("stale") STALE,
}

@Serializable
enum class PlaylistEvaluationState {
    @SerialName("ready") READY, @SerialName("pending") PENDING,
    @SerialName("stale") STALE, @SerialName("error") ERROR,
}

@Serializable
data class PlaylistCapabilities(
    val editMetadata: Boolean = true,
    val editEntries: Boolean = true,
    val editDefinition: Boolean = false,
    val play: Boolean = true,
    val copyToManual: Boolean = false,
    val delete: Boolean = true,
    val addTracks: Boolean = true,
)

/** Maintained counts are display data, never signed playback/copy authority. */
@Serializable
data class PersonalPlaylistSummary(
    val id: String,
    val name: String,
    val description: String = "",
    val kind: PlaylistKind = PlaylistKind.MANUAL,
    val countState: PlaylistCountState = PlaylistCountState.EXACT,
    val trackCount: Long?,
    val availableCount: Long?,
    val capabilities: PlaylistCapabilities = PlaylistCapabilities(),
    val revision: Long,
    val createdAt: String = "",
    val updatedAt: String = "",
    val evaluationState: PlaylistEvaluationState? = null,
    val evaluationErrorCode: String? = null,
) {
    init {
        require(revision > 0)
        when (kind) {
            PlaylistKind.MANUAL -> require(countState == PlaylistCountState.EXACT && trackCount != null &&
                availableCount != null && trackCount >= 0 && availableCount in 0..trackCount)
            PlaylistKind.DERIVED -> {
                val hasCounts = trackCount != null && availableCount != null
                require((trackCount == null) == (availableCount == null))
                if (hasCounts) {
                    val total = requireNotNull(trackCount)
                    require(total >= 0 && requireNotNull(availableCount) in 0..total)
                }
                when (evaluationState) {
                    PlaylistEvaluationState.READY -> require(countState == PlaylistCountState.EXACT && hasCounts)
                    PlaylistEvaluationState.PENDING, PlaylistEvaluationState.STALE, PlaylistEvaluationState.ERROR ->
                        require(if (hasCounts) countState == PlaylistCountState.STALE else countState == PlaylistCountState.PENDING)
                    null -> require(countState == PlaylistCountState.UNEVALUATED && !hasCounts) // Legacy server.
                }
            }
        }
    }
}

@Serializable
data class PersonalPlaylistPage(val items: List<PersonalPlaylistSummary>, val total: Int, val limit: Int = 40, val offset: Int = 0)

@Serializable
data class PersonalPlaylistEntry(val id: String, val position: Int, val track: LibraryItem)

@Serializable
data class PersonalEntriesPage(val items: List<PersonalPlaylistEntry>, val total: Int, val limit: Int = 40, val offset: Int = 0)

/** Created only after reading the complete bounded order between equal summary revisions. */
class ManualPlaylistSnapshot internal constructor(val playlist: PersonalPlaylistSummary, entries: List<PersonalPlaylistEntry>) {
    val summary: PersonalPlaylistSummary get() = playlist
    val entries: List<PersonalPlaylistEntry> = entries.toList()
    val entryIds: List<String> get() = entries.map { it.id }
}

@Serializable
data class PersonalPlaybackContext(
    val kind: String,
    val id: String,
    val name: String,
    val revision: Long? = null,
    val fence: String? = null,
)

@Serializable
data class PersonalPlayback(
    val context: PersonalPlaybackContext,
    val items: List<LibraryItem>,
    val total: Long,
    val limit: Int,
    val truncated: Boolean,
    val revision: Long = 0,
) {
    init {
        require(total == items.size.toLong() && limit == items.size && !truncated) { "Incomplete playback collection" }
    }
    fun toPlaybackQueue() = PlaybackQueue(
        items, total = total.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), limit = limit, truncated = truncated,
        context = LibraryPlaybackContext(context.kind, context.id, context.name),
        revision = revision.takeIf { it > 0 } ?: context.revision ?: 0,
    )
    val queue: PlaybackQueue get() = toPlaybackQueue()
}

/** Account-local listener APIs. Every write is a single transport attempt, including uncertain failures. */
class PersonalCollectionsApi(client: SpofifyClient) {
    private val transport = ApiTransport(client)

    suspend fun playlists(kind: PlaylistKind? = null, limit: Int = 40, offset: Int = 0): PersonalPlaylistPage {
        val query = personalPageQuery(limit, offset).toMutableMap()
        kind?.let { query["kind"] = if (it == PlaylistKind.MANUAL) "manual" else "derived" }
        return personalDecode(transport.get(personalPlaylistsPath, query))
    }

    suspend fun playlist(id: String): PersonalPlaylistSummary = personalDecode(transport.get(personalPlaylistPath(id), emptyMap()))

    suspend fun createPlaylist(name: String, description: String? = null): PersonalPlaylistSummary {
        validatePlaylistMetadata(name, description ?: "")
        return confirmedPlaylistMutation(transport.client, transport.send("POST", personalPlaylistsPath, buildJsonObject {
            put("name", name)
            description?.let { put("description", it) }
        }, emptyMap()))
    }

    suspend fun updatePlaylist(id: String, name: String, description: String, expectedRevision: Long): PersonalPlaylistSummary {
        validatePlaylistMetadata(name, description)
        requirePersonalRevision(expectedRevision)
        return confirmedPlaylistMutation(transport.client, transport.send("PATCH", personalPlaylistPath(id), buildJsonObject {
            put("name", name); put("description", description); put("expectedRevision", expectedRevision)
        }, emptyMap()))
    }

    suspend fun deletePlaylist(id: String, expectedRevision: Long) {
        if (transport.send("DELETE", personalPlaylistPath(id), revisionBody(expectedRevision), emptyMap()) != null) {
            throw incompatiblePersonalResponse()
        }
        transport.client.notifyPersonalChange(PersonalCollection.PLAYLISTS)
    }

    suspend fun entries(id: String, limit: Int = 40, offset: Int = 0): PersonalEntriesPage {
        val page: PersonalEntriesPage = personalDecode(transport.get(personalPlaylistPath(id) + "entries", personalPageQuery(limit, offset)))
        return page.copy(items = page.items.map { it.copy(track = it.track.copy(entryId = it.id)) })
    }

    /** Explicit reorder preparation, never a browse-page shortcut or an automatic retry. */
    suspend fun completeSnapshot(id: String): ManualPlaylistSnapshot {
        val before = playlist(id)
        require(before.kind == PlaylistKind.MANUAL && before.capabilities.editEntries) { "Only manual playlists have editable entries" }
        val count = before.trackCount ?: throw incompatiblePersonalResponse()
        require(count in 0..1000) { "The complete playlist exceeds the reorder bound" }
        val all = mutableListOf<PersonalPlaylistEntry>()
        while (all.size.toLong() < count) {
            val page = entries(id, limit = 100, offset = all.size)
            if (page.total.toLong() != count || page.offset != all.size || page.limit !in 1..100 ||
                page.items.isEmpty() || page.items.size > page.limit || all.size + page.items.size > count) {
                throw snapshotChanged()
            }
            all.addAll(page.items)
        }
        if (all.map { it.id }.toSet().size != all.size) throw snapshotChanged()
        val after = playlist(id)
        if (after.kind != PlaylistKind.MANUAL || after.revision != before.revision || after.trackCount != count) throw snapshotChanged()
        return ManualPlaylistSnapshot(after, all)
    }

    suspend fun addEntry(id: String, trackId: String, expectedRevision: Long): PersonalPlaylistSummary {
        requirePersonalId(trackId)
        requirePersonalRevision(expectedRevision)
        return confirmedPlaylistMutation(transport.client, transport.send("POST", personalPlaylistPath(id) + "entries", buildJsonObject {
            put("trackId", trackId); put("expectedRevision", expectedRevision)
        }, emptyMap()))
    }

    /** Removes exactly one occurrence, never every copy of a track. */
    suspend fun removeEntry(id: String, entryId: String, expectedRevision: Long): PersonalPlaylistSummary =
        confirmedPlaylistMutation(transport.client, transport.send("DELETE", personalPlaylistPath(id) + listOf("entries", requirePersonalId(entryId)),
            revisionBody(expectedRevision), emptyMap()))

    suspend fun reorder(snapshot: ManualPlaylistSnapshot, entryIds: List<String>): PersonalPlaylistSummary {
        val original = snapshot.entryIds
        require(original.size <= 1000 && original.size.toLong() == snapshot.playlist.trackCount &&
            entryIds.size == original.size && entryIds.toSet().size == entryIds.size && entryIds.toSet() == original.toSet()) {
            "Reorder requires an exact permutation of the complete occurrence order"
        }
        requirePersonalRevision(snapshot.playlist.revision)
        return confirmedPlaylistMutation(transport.client, transport.send("PUT", personalPlaylistPath(snapshot.playlist.id) + "order", buildJsonObject {
            putJsonArray("entryIds") { entryIds.forEach { add(JsonPrimitive(it)) } }
            put("expectedRevision", snapshot.playlist.revision)
        }, emptyMap()))
    }

    suspend fun playback(id: String): PersonalPlayback = personalDecode(transport.get(personalPlaylistPath(id) + "playback", emptyMap()))

    suspend fun list(kind: PlaylistKind? = null, limit: Int = 40, offset: Int = 0) = playlists(kind, limit, offset)
    suspend fun detail(id: String) = playlist(id)
    suspend fun create(name: String, description: String? = null) = createPlaylist(name, description)
    suspend fun update(id: String, name: String, description: String, expectedRevision: Long) = updatePlaylist(id, name, description, expectedRevision)
    suspend fun delete(id: String, expectedRevision: Long) = deletePlaylist(id, expectedRevision)
    suspend fun add(id: String, trackId: String, expectedRevision: Long) = addEntry(id, trackId, expectedRevision)
    suspend fun remove(id: String, entryId: String, expectedRevision: Long) = removeEntry(id, entryId, expectedRevision)
    suspend fun loadCompleteOrder(id: String) = completeSnapshot(id)
}

internal val personalPlaylistsPath = listOf("api", "me", "playlists")
internal fun personalPlaylistPath(id: String) = personalPlaylistsPath + requirePersonalId(id)
internal fun requirePersonalId(id: String): String {
    require(id.isNotEmpty() && id != "." && id != ".." && id.none { it.isISOControl() } && id.toByteArray(Charsets.UTF_8).size <= 512) { "Invalid musical identifier" }
    return id
}
internal fun requirePersonalRevision(revision: Long) { require(revision > 0) { "Reload the playlist before changing it" } }
internal fun revisionBody(revision: Long): JsonObject {
    requirePersonalRevision(revision)
    return buildJsonObject { put("expectedRevision", revision) }
}
internal fun personalPageQuery(limit: Int, offset: Int): Map<String, String> {
    require(limit in 1..100 && offset in 0..1_000_000) { "Invalid page" }
    return linkedMapOf("limit" to limit.toString(), "offset" to offset.toString())
}
internal fun validatePlaylistMetadata(name: String, description: String) {
    require(name.isNotBlank() && name.codePointCount(0, name.length) <= 100 && name.none { it.isISOControl() }) { "Invalid playlist name" }
    require(description.codePointCount(0, description.length) <= 500 && description.none { it.isISOControl() && it != '\n' && it != '\t' }) { "Invalid playlist description" }
}
internal inline fun <reified T> personalDecode(value: JsonObject): T = try {
    ApiJson.decodeFromJsonElement<T>(value)
} catch (_: SerializationException) {
    throw incompatiblePersonalResponse()
} catch (_: IllegalArgumentException) {
    throw incompatiblePersonalResponse()
}
/** Publish only after the entire successful mutation acknowledgement has been decoded and validated. */
internal fun confirmedPlaylistMutation(client: SpofifyClient, value: JsonObject?): PersonalPlaylistSummary {
    val summary: PersonalPlaylistSummary = personalDecode(personalRequired(value))
    client.notifyPersonalChange(PersonalCollection.PLAYLISTS)
    return summary
}
internal fun personalRequired(value: JsonObject?): JsonObject = value ?: throw incompatiblePersonalResponse()
internal fun incompatiblePersonalResponse() = ApiException(200, "The server returned an incompatible JSON response")
private fun snapshotChanged() = ApiException(409, "The playlist changed while its order was loading. Reload and review before reordering.", "REVISION_CONFLICT")
