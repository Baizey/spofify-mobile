@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.spofify.mobile.core

import java.security.SecureRandom
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

@Serializable
enum class RecipeTakeSide { @SerialName("first") FIRST, @SerialName("last") LAST }
@Serializable
enum class RecipeDuplicates { @SerialName("keep") KEEP, @SerialName("unique") UNIQUE }
@Serializable
enum class RecipeSort { @SerialName("sourceOrder") SOURCE_ORDER, @SerialName("title") TITLE }
@Serializable
enum class RecipeAvailability { @SerialName("available") AVAILABLE, @SerialName("unavailable") UNAVAILABLE }

@Serializable
data class RecipeTake(val side: RecipeTakeSide, val count: Int) {
    init { require(count > 0) { "A recipe window must be a positive integer" } }
}

/** Stable slot identity, generated once when a source is added, not when it is reordered. */
fun newRecipeSlotId(): String = "s_" + ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }

@Serializable
@JsonClassDiscriminator("kind")
sealed interface RecipeSource {
    val slotId: String
    val take: RecipeTake?

    @Serializable @SerialName("playlist")
    data class Playlist(override val slotId: String, val playlistId: String, override val take: RecipeTake? = null) : RecipeSource
    @Serializable @SerialName("likes")
    data class Likes(override val slotId: String, override val take: RecipeTake? = null) : RecipeSource
    @Serializable @SerialName("allSaved")
    data class AllSaved(override val slotId: String, override val take: RecipeTake? = null) : RecipeSource
}

@Serializable
@JsonClassDiscriminator("kind")
sealed interface RecipeMembershipSource {
    @Serializable @SerialName("playlist") data class Playlist(val playlistId: String) : RecipeMembershipSource
    @Serializable @SerialName("likes") data object Likes : RecipeMembershipSource
}

/** This is a server-evaluated three-valued expression; clients must not filter loaded rows instead. */
@Serializable
@JsonClassDiscriminator("op")
sealed interface RecipePredicate {
    @Serializable @SerialName("all") data class All(val args: List<RecipePredicate>) : RecipePredicate
    @Serializable @SerialName("any") data class Any(val args: List<RecipePredicate>) : RecipePredicate
    @Serializable @SerialName("not") data class Not(val arg: RecipePredicate) : RecipePredicate
    @Serializable @SerialName("genre") data class Genre(val value: String) : RecipePredicate
    @Serializable @SerialName("genreKnown") data class GenreKnown(val value: Boolean) : RecipePredicate
    @Serializable @SerialName("artist") data class Artist(val artistId: String) : RecipePredicate
    @Serializable @SerialName("album") data class Album(val albumId: String) : RecipePredicate
    @Serializable @SerialName("availability") data class Availability(val value: RecipeAvailability) : RecipePredicate
    @Serializable @SerialName("membership") data class Membership(val source: RecipeMembershipSource) : RecipePredicate
}

@Serializable
data class Recipe(
    val sources: List<RecipeSource>,
    val duplicates: RecipeDuplicates,
    val sort: RecipeSort,
    val version: Int = 1,
    val include: RecipePredicate? = null,
    val exclude: RecipePredicate? = null,
    val take: RecipeTake? = null,
) {
    init {
        require(version == 1 && sources.size in 1..4)
        require(sources.all { it.slotId.matches(Regex("s_[0-9a-f]{32}")) } && sources.map { it.slotId }.toSet().size == sources.size)
        sources.filterIsInstance<RecipeSource.Playlist>().forEach { requireDerivedId(it.playlistId) }
        var leaves = 0
        fun visit(predicate: RecipePredicate, depth: Int) {
            require(depth <= 4) { "Recipe conditions are too deeply nested" }
            when (predicate) {
                is RecipePredicate.All -> { require(predicate.args.size in 1..32); predicate.args.forEach { visit(it, depth + 1) } }
                is RecipePredicate.Any -> { require(predicate.args.size in 1..32); predicate.args.forEach { visit(it, depth + 1) } }
                is RecipePredicate.Not -> visit(predicate.arg, depth + 1)
                else -> {
                    leaves++
                    when (predicate) {
                        is RecipePredicate.Artist -> requirePersonalId(predicate.artistId)
                        is RecipePredicate.Album -> requirePersonalId(predicate.albumId)
                        is RecipePredicate.Membership -> (predicate.source as? RecipeMembershipSource.Playlist)?.let { requireDerivedId(it.playlistId) }
                        is RecipePredicate.Genre -> require(predicate.value.isNotBlank() && ',' !in predicate.value && predicate.value.none { it.isISOControl() })
                        else -> Unit
                    }
                }
            }
        }
        include?.let { visit(it, 1) }; exclude?.let { visit(it, 1) }
        require(leaves <= 32) { "A recipe supports at most 32 condition leaves" }
    }

    fun toJson(): JsonObject = recipeJson.encodeToJsonElement(this) as JsonObject
    companion object {
        fun fromJson(value: JsonObject): Recipe = recipeJson.decodeFromJsonElement(value)
    }
}

private val recipeJson = Json(ApiJson) { ignoreUnknownKeys = false; encodeDefaults = true; explicitNulls = false }

@Serializable
data class DerivedDefinitionResult(val definition: JsonObject, val revision: Long) {
    fun asRecipe(): Recipe = Recipe.fromJson(definition)
}

@Serializable
data class DerivedEvaluationEntry(
    val occurrenceId: String,
    val selectionToken: String?,
    val position: Long,
    val track: LibraryItem,
)

@Serializable
data class DerivedEvaluationPage(
    val items: List<DerivedEvaluationEntry>,
    val total: Long,
    val availableTotal: Long,
    val limit: Int,
    val nextCursor: String?,
    val revision: Long?,
    val fence: String,
    val complete: Boolean,
    val evaluationState: PlaylistEvaluationState? = null,
    val evaluationErrorCode: String? = null,
) {
    /** Legacy complete pages and explicit unsaved previews retain their existing contract. */
    val current: Boolean get() = complete && (evaluationState == null || evaluationState == PlaylistEvaluationState.READY)

    init {
        require(total >= 0 && availableTotal in 0..total && limit in 1..100 && items.size <= limit)
        require(revision == null || revision > 0)
        require(complete == (evaluationState == null || evaluationState == PlaylistEvaluationState.READY))
        if (current) require(fence.isNotEmpty() && (nextCursor == null || nextCursor.isNotEmpty()))
        else require(fence.isEmpty() && nextCursor == null && items.all { it.selectionToken == null })
        require(items.all { it.position >= 0 && it.position < total && it.occurrenceId.isNotEmpty() })
        require(items.map { it.occurrenceId }.toSet().size == items.size)
    }
}

/** The full count is Long and stays outside the shared, Int-count callback queue. */
data class DerivedPlayback(
    val context: PersonalPlaybackContext,
    val items: List<LibraryItem>,
    val selectionTokens: Map<String, String>,
    val total: Long,
    val limit: Int,
    val truncated: Boolean,
    val revision: Long,
    val fence: String,
    val startOccurrenceId: String?,
    val notice: String?,
    internal val preparedSelectionAuthority: PreparedPlaybackAuthority? = null,
) {
    val startIndex: Int? get() = startOccurrenceId?.let { id -> items.indexOfFirst { it.entryId == id }.takeIf { it >= 0 } }
    /** Shared queue counts saturate at Int.MAX_VALUE; the exact Long count remains in this envelope. */
    fun toPlaybackQueue(): PlaybackQueue = toPlaybackQueue(selectionAuthority = preparedSelectionAuthority)

    internal fun toPlaybackQueue(selectionAuthority: PreparedPlaybackAuthority?): PlaybackQueue {
        require(context.kind == "derived" && context.revision == revision && context.fence == fence)
        require(total == items.size.toLong() && limit.toLong() >= total && !truncated && notice == null) { "Incomplete playback collection" }
        val preparedContext = PlaybackContext(context.kind, context.id, context.name, revision, fence)
        val tracks = items.mapIndexed { index, item ->
            require(item.kind == "track" && item.playable && item.durationMs >= 0)
            PlaybackTrack(
                id = item.id, name = item.name, artists = item.artists.map { PlaybackArtist(it.id, it.name) },
                albumName = item.albumName.orEmpty(), albumId = item.albumId.orEmpty(), duration = item.durationMs / 1000.0,
                // Derived occurrence identity is not a manual-playlist entry ID (and has a larger wire bound).
                targetId = context.id, occurrenceId = requireNotNull(item.entryId), playbackContext = preparedContext,
                key = index + 1L, order = index.toLong(),
            )
        }
        val position = if (tracks.isEmpty()) -1 else requireNotNull(startIndex)
        val prepared = PlaybackSnapshot(
            queue = tracks, position = position, context = preparedContext,
            duration = tracks.getOrNull(position)?.duration ?: 0.0,
            truncated = truncated, notice = notice.orEmpty(),
        ).publicationValidated()
        return PlaybackQueue(
            items, total = total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), limit = limit, truncated = truncated,
            context = LibraryPlaybackContext(context.kind, context.id, context.name), revision = revision,
            preparedState = prepared, selectionAuthority = selectionAuthority,
        )
    }
    val queue: PlaybackQueue get() = toPlaybackQueue()

    override fun toString(): String = "DerivedPlayback(total=$total, limit=$limit, truncated=$truncated, [redacted])"
}

/** Opaque signed cursors/selections are passed unchanged, never decoded or reconstructed here. */
class DerivedPlaylistsApi(client: SpofifyClient) {
    private val transport = ApiTransport(client)

    suspend fun definition(id: String): DerivedDefinitionResult = personalDecode(transport.get(derivedPath(id, "definition"), emptyMap()))

    suspend fun updateDefinition(id: String, definition: JsonObject, expectedRevision: Long): PersonalPlaylistSummary {
        requirePersonalRevision(expectedRevision)
        val writeGeneration = transport.client.personalMutationGeneration()
        return confirmedPlaylistMutation(transport.client, transport.send("PUT", derivedPath(id, "definition"), buildJsonObject {
            put("definition", definition); put("expectedRevision", expectedRevision)
        }, emptyMap()), writeGeneration)
    }

    suspend fun create(name: String, description: String? = null, definition: JsonObject): PersonalPlaylistSummary {
        validatePlaylistMetadata(name, description ?: "")
        val writeGeneration = transport.client.personalMutationGeneration()
        return confirmedPlaylistMutation(transport.client, transport.send("POST", personalPlaylistsPath, buildJsonObject {
            put("kind", "derived"); put("name", name)
            description?.let { put("description", it) }
            put("definition", definition)
        }, emptyMap()), writeGeneration)
    }

    /** Normal saved-playlist read: the server owns maintenance and its bounded wait. */
    suspend fun entries(id: String, limit: Int? = 40, cursor: String? = null): DerivedEvaluationPage =
        savedPage(id, "entries", limit, cursor)

    /** Compatibility alias; this read never requests a client-owned evaluation job. */
    suspend fun evaluate(id: String, limit: Int? = 40, cursor: String? = null): DerivedEvaluationPage =
        savedPage(id, "evaluation", limit, cursor)

    private suspend fun savedPage(id: String, action: String, limit: Int?, cursor: String?): DerivedEvaluationPage {
        val query = linkedMapOf<String, String>()
        limit?.let { require(it in 1..100); query["limit"] = it.toString() }
        cursor?.let { requireOpaqueToken(it, 4096); query["cursor"] = it }
        return checkedPage(transport.get(derivedPath(id, action), query), preview = false)
    }

    suspend fun preview(
        definition: JsonObject,
        targetPlaylistId: String? = null,
        expectedRevision: Long? = null,
        limit: Int? = 40,
        cursor: String? = null,
    ): DerivedEvaluationPage {
        require((targetPlaylistId == null) == (expectedRevision == null)) { "Preview target and expected revision must be supplied together" }
        targetPlaylistId?.let { requireDerivedId(it) }
        expectedRevision?.let { requirePersonalRevision(it) }
        limit?.let { require(it in 1..100) }
        cursor?.let { requireOpaqueToken(it, 4096) }
        return checkedPage(personalRequired(transport.send("POST", personalPlaylistsPath + "preview", buildJsonObject {
            put("definition", definition)
            targetPlaylistId?.let { put("targetPlaylistId", it) }
            expectedRevision?.let { put("expectedRevision", it) }
            limit?.let { put("limit", it) }
            cursor?.let { put("cursor", it) }
        }, emptyMap())), preview = true)
    }

    suspend fun playback(id: String, expectedRevision: Long, fence: String, selectionToken: String? = null): DerivedPlayback {
        requirePersonalRevision(expectedRevision)
        requireOpaqueToken(fence, 4096)
        selectionToken?.let { requireOpaqueToken(it, 8192) }
        val preparedSource = PreparedPlaybackSource.capture(transport.client)
        val wire: DerivedPlaybackWire = personalDecode(personalRequired(transport.send("POST", derivedPath(id, "playback"), buildJsonObject {
            put("expectedRevision", expectedRevision); put("fence", fence)
            selectionToken?.let { put("selectionToken", it) }
        }, emptyMap())))
        val items = wire.items.map { raw ->
            val authority: DerivedPlaybackOccurrence = personalDecode(raw)
            DerivedPreparedTrack(authority.occurrenceId, authority.selectionToken, personalDecode<LibraryItem>(raw))
        }
        if (wire.context.kind != "derived" || wire.context.id != id || wire.revision != expectedRevision || wire.fence != fence ||
            wire.context.revision != wire.revision || wire.context.fence != wire.fence || wire.total != items.size.toLong() ||
            wire.limit.toLong() < wire.total || wire.truncated || wire.notice != null ||
            items.map { it.occurrenceId }.toSet().size != items.size ||
            (items.isEmpty() != (wire.startOccurrenceId == null)) ||
            (wire.startOccurrenceId != null && items.none { it.occurrenceId == wire.startOccurrenceId })) throw incompatiblePersonalResponse()
        val tokens = items.associate { item ->
            try { requireOpaqueToken(item.occurrenceId, 4096); requireOpaqueToken(item.selectionToken, 8192) }
            catch (_: IllegalArgumentException) { throw incompatiblePersonalResponse() }
            item.occurrenceId to item.selectionToken
        }
        return DerivedPlayback(wire.context, items.map { it.track.copy(entryId = it.occurrenceId) }, tokens,
            wire.total, wire.limit, wire.truncated, wire.revision, wire.fence, wire.startOccurrenceId, wire.notice,
            preparedSelectionAuthority = preparedSource.bind(selectionToken,
                PlaybackContext(wire.context.kind, wire.context.id, wire.context.name, wire.revision, wire.fence),
                items.firstOrNull { it.occurrenceId == wire.startOccurrenceId }?.track?.id, wire.startOccurrenceId))
    }

    suspend fun copy(id: String, name: String, description: String? = null, expectedRevision: Long, fence: String): PersonalPlaylistSummary {
        validatePlaylistMetadata(name, description ?: "")
        requirePersonalRevision(expectedRevision); requireOpaqueToken(fence, 4096)
        val writeGeneration = transport.client.personalMutationGeneration()
        return confirmedPlaylistMutation(transport.client, transport.send("POST", derivedPath(id, "copy"), buildJsonObject {
            put("name", name); description?.let { put("description", it) }
            put("expectedRevision", expectedRevision); put("fence", fence)
        }, emptyMap()), writeGeneration)
    }

    suspend fun create(name: String, description: String? = null, definition: Recipe) = create(name, description, definition.toJson())
    suspend fun updateDefinition(id: String, definition: Recipe, expectedRevision: Long) = updateDefinition(id, definition.toJson(), expectedRevision)
    suspend fun preview(definition: Recipe, targetPlaylistId: String? = null, expectedRevision: Long? = null, limit: Int? = 40, cursor: String? = null) =
        preview(definition.toJson(), targetPlaylistId, expectedRevision, limit, cursor)

    private fun checkedPage(value: JsonObject, preview: Boolean): DerivedEvaluationPage {
        val page: DerivedEvaluationPage = personalDecode(value)
        if (preview) {
            if (!page.current || page.evaluationState != null || page.items.any { it.selectionToken != null }) throw incompatiblePersonalResponse()
        } else if (page.revision == null || page.items.any { (it.selectionToken != null) != page.current }) throw incompatiblePersonalResponse()
        try {
            if (page.current) requireOpaqueToken(page.fence, 4096)
            page.nextCursor?.let { requireOpaqueToken(it, 4096) }
            page.items.forEach {
                requireOpaqueToken(it.occurrenceId, 4096)
                it.selectionToken?.let { token -> requireOpaqueToken(token, 8192) }
            }
        } catch (_: IllegalArgumentException) { throw incompatiblePersonalResponse() }
        return page.copy(items = page.items.map { it.copy(track = it.track.copy(entryId = it.occurrenceId)) })
    }
}

private fun requireDerivedId(id: String): String {
    require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid playlist identifier" }
    return id
}
private fun derivedPath(id: String, action: String) = personalPlaylistsPath + listOf(requireDerivedId(id), action)
private fun requireOpaqueToken(value: String, maximum: Int) {
    require(value.length in 1..maximum && value.all { it.code in 0x21..0x7e }) { "Invalid evaluation token" }
}

@Serializable
private data class DerivedPlaybackWire(
    val context: PersonalPlaybackContext,
    val items: List<JsonObject>,
    val total: Long,
    val limit: Int,
    val truncated: Boolean,
    val revision: Long,
    val fence: String,
    val startOccurrenceId: String?,
    val notice: String?,
)

// Flat Go musical DTOs carry occurrence authority beside (not inside) track metadata.
@Serializable
private data class DerivedPlaybackOccurrence(val occurrenceId: String, val selectionToken: String)
private data class DerivedPreparedTrack(val occurrenceId: String, val selectionToken: String, val track: LibraryItem)
