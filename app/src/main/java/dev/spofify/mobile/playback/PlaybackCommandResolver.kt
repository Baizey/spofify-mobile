package dev.spofify.mobile.playback

import dev.spofify.mobile.core.DerivedPlaylistsApi
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.PersonalCollectionsApi
import dev.spofify.mobile.core.PlaybackArtist
import dev.spofify.mobile.core.PlaybackContext
import dev.spofify.mobile.core.PlaybackDeviceCommand
import dev.spofify.mobile.core.PlaybackSnapshot
import dev.spofify.mobile.core.PlaybackTrack
import dev.spofify.mobile.core.SpofifyClient
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** An explicit new musical request, NOT a snapshot import or a grant of playback authority. */
internal data class ResolvedPlaybackCommand(
    /** Baseline order. Keys/orders are unique placeholders; the owner must rekey additions. */
    val tracks: List<PlaybackTrack>,
    val context: PlaybackContext?,
    val startIndex: Int,
    /** Shuffle intent; realizing traversal is the owner's responsibility. */
    val shuffle: Boolean = false,
    val first: Boolean = false,
    val enqueue: Boolean = false,
    val truncated: Boolean = false,
    /** Wire notice code, not server-controlled display text. */
    val notice: String = "",
    /** Shuffle may pick any first track only when there was no explicit/server-selected start. */
    val pinStart: Boolean = false,
)

/**
 * Expands only explicit play/playCollection/enqueue/enqueueContext commands using existing,
 * authenticated playback endpoints. Never used by snapshot prepare/import, restoration or resume.
 * No evaluation refresh, browse paging, acquisition, automatic retry or substitute selection.
 *
 * There is at most one request per resolution. The caller MUST recheck its live authority and
 * client/session identity after awaiting resolve, before mutating a queue or opening audio.
 * Cancellation and API errors deliberately propagate; null means a non-musical command only.
 */
internal object PlaybackCommandResolver {
    private val json = Json { ignoreUnknownKeys = false }

    /** Positive Play of the authoritative current selection is resume/no-op, not a new fetch
     * or traversal. Compare occurrence/context identity so duplicate and derived rows stay exact. */
    fun selectsCurrent(command: PlaybackDeviceCommand, state: PlaybackSnapshot): Boolean {
        if (command.type != "play") return false
        val fields = command.json
        val selectedFields = fields["track"]?.jsonObject ?: return false
        val selected = decodeTrack(selectedFields)
        val current = state.queue.getOrNull(state.position) ?: return false
        if (selected.id != current.id) return false
        val explicit = fields["context"]?.takeUnless { it == kotlinx.serialization.json.JsonNull }
            ?.let { json.decodeFromJsonElement<PlaybackContext>(it) }
        val currentContext = current.playbackContext ?: state.context
        if (explicit != null && (explicit.kind != currentContext?.kind || explicit.id != currentContext?.id)) return false
        val target = explicit?.takeIf { it.kind == "derived" }?.id ?: selected.targetId.takeIf { it.isNotEmpty() }
        if (target != null) {
            val occurrence = fields.text("occurrenceId") ?: selected.occurrenceId
            // This is an already prepared/current occurrence, not a NEW derived selection.
            // Prepared state intentionally has no selection token. New/different occurrences
            // still go through resolve(), which requires the original signed authority.
            return current.targetId == target && occurrence.isNotEmpty() && current.occurrenceId == occurrence
        }
        val entry = fields.text("entryId") ?: selected.entryId.takeIf { it.isNotEmpty() }
        return current.targetId.isEmpty() && !hasDerivedIdentity(selected) &&
            (entry == null || current.entryId == entry) && fields.text("occurrenceId") == null && fields.text("selectionToken") == null
    }

    /** One receiver-authoritative normalization. Queue rows never expand into their album. */
    suspend fun resolveQueueIntent(command: PlaybackDeviceCommand, client: SpofifyClient): ResolvedPlaybackCommand? {
        require(command.type == "queueIntent")
        val fields = PlaybackDeviceCommand.fromJson(command.json).json
        val intent = fields.getValue("intent").jsonPrimitive.content
        val selection = fields.getValue("selection").jsonObject
        return when (selection.getValue("kind").jsonPrimitive.content) {
            "occurrence" -> null // Exact live upcoming validation is serialized in the service.
            "collection" -> resolve(PlaybackDeviceCommand.musical("playCollection",
                context = json.decodeFromJsonElement<PlaybackContext>(selection.getValue("context"))), client)
            "track" -> {
                val raw = selection.getValue("track").jsonObject
                val track = decodeTrack(raw)
                val context = selection["context"]?.takeUnless { it is kotlinx.serialization.json.JsonNull }
                    ?.let { json.decodeFromJsonElement<PlaybackContext>(it) }
                require(track.playbackContext == null || context == track.playbackContext) { "Contradictory context" }
                if (intent == "now" || context?.kind in setOf("derived", "playlist")) {
                    val prepared = requireNotNull(resolve(PlaybackDeviceCommand.musical("play", track = raw, context = context), client))
                    if (intent == "now") prepared else prepared.copy(tracks = listOf(prepared.tracks[prepared.startIndex]), startIndex = 0)
                } else {
                    require(!hasDerivedIdentity(track)) { "Missing signed derived context" }
                    result(listOf(track.copy(playbackContext = context)), context, 0, pinStart = true)
                }
            }
            else -> throw IllegalArgumentException("Invalid queue selection")
        }
    }

    suspend fun resolve(command: PlaybackDeviceCommand, client: SpofifyClient): ResolvedPlaybackCommand? {
        currentCoroutineContext().ensureActive()
        if (command.type !in setOf("play", "playCollection", "enqueue", "enqueueContext")) return null
        val fields = PlaybackDeviceCommand.fromJson(command.json).json
        val enqueue = command.type.startsWith("enqueue")
        val first = fields["first"]?.jsonPrimitive?.boolean ?: false
        val shuffle = fields["shuffle"]?.jsonPrimitive?.boolean ?: false
        val trackFields = fields["track"]?.jsonObject
        val track = trackFields?.let(::decodeTrack)
        val explicitContext = fields["context"]?.takeUnless { it == kotlinx.serialization.json.JsonNull }
            ?.let { json.decodeFromJsonElement<PlaybackContext>(it) }

        // Bare enqueue intentionally performs no fetch (including for a prepared derived row).
        if (command.type == "enqueue") {
            val selected = requireNotNull(track)
            validatePreparedIdentity(selected)
            return result(listOf(selected), selected.playbackContext, 0, enqueue = true, first = first)
        }

        val context = explicitContext ?: track?.let { selected ->
            selected.albumId.takeIf { it.isNotEmpty() }
                ?.let { PlaybackContext("album", it, selected.albumName.ifEmpty { "Album" }) }
        }
        if (context == null) {
            val selected = requireNotNull(track)
            require(!hasDerivedIdentity(selected) && fields.text("occurrenceId") == null &&
                fields.text("selectionToken") == null && trackFields.text("selectionToken") == null) {
                "A derived occurrence requires its original explicit context and selection"
            }
            require(fields.text("entryId") == null || fields.text("entryId") == selected.entryId) {
                "An entry selection requires its collection"
            }
            return result(listOf(selected), null, 0, pinStart = true)
        }
        require(context.id != "." && context.id != "..") { "Invalid playback context" }
        val selectedTrack = fields.text("trackId") ?: track?.id
        val selectedEntry = fields.text("entryId") ?: track?.entryId?.takeIf { it.isNotEmpty() }
        val selectedOccurrence = fields.text("occurrenceId") ?: track?.occurrenceId?.takeIf { it.isNotEmpty() }
        val token = fields.text("selectionToken") ?: trackFields?.text("selectionToken")

        if (context.kind == "derived") {
            // A new derived Play may never degrade to album playback or refresh its authority.
            require(track == null || track.targetId.isEmpty() || track.targetId == context.id) { "Mismatched derived target" }
            track?.playbackContext?.let { require(it == context) { "Mismatched derived context" } }
            val hasSelection = selectedTrack != null || selectedEntry != null || selectedOccurrence != null || token != null
            require(!hasSelection || (!selectedOccurrence.isNullOrEmpty() && !token.isNullOrEmpty())) {
                "A derived selection requires its original occurrence and selection token"
            }
            val loaded = DerivedPlaylistsApi(client).playback(context.id, context.revision, context.fence, token)
            currentCoroutineContext().ensureActive()
            val items = loaded.items
            require(items.isNotEmpty() && items.all { it.kind == "track" && it.playable }) { "No available derived selection" }
            val start = requireNotNull(loaded.startIndex) { "Missing derived start" }
            val chosen = items[start]
            require(if (hasSelection) loaded.startOccurrenceId == selectedOccurrence else start == 0) { "Mismatched derived selection" }
            require(selectedTrack == null || chosen.id == selectedTrack) { "Mismatched selected track" }
            require(selectedEntry == null || chosen.entryId == selectedEntry) { "Mismatched selected entry" }
            require(!loaded.truncated && loaded.notice == null && loaded.total == items.size.toLong() && loaded.limit == items.size) {
                "Incomplete derived playback collection"
            }
            val tracks = items.mapIndexed { index, item ->
                project(item, index).copy(entryId = "", occurrenceId = requireNotNull(item.entryId), targetId = context.id, playbackContext = context)
            }
            return result(tracks, context, start, shuffle, first, enqueue, loaded.truncated, loaded.notice.orEmpty(), pinStart = true)
        }

        require(track == null || !hasDerivedIdentity(track)) { "A derived occurrence requires its original explicit context" }
        require(selectedOccurrence == null && token == null) { "Occurrence authority is only supported in a derived context" }
        // The public ordinary queue DTOs cannot carry derived authority; never GET a derived
        // collection and then pretend its flattened rows were prepared ordinary tracks.
        val loaded = when (context.kind) {
            "playlist" -> {
                val response = PersonalCollectionsApi(client).playback(context.id)
                require(response.context.kind == "playlist" && response.context.id == context.id) { "Mismatched playback context" }
                require(context.revision == 0L || response.revision == context.revision) { "Stale playlist revision" }
                OrdinaryQueue(response.items, response.total, response.limit, response.truncated,
                    PlaybackContext("playlist", response.context.id, response.context.name,
                        response.revision.takeIf { it > 0 } ?: response.context.revision ?: context.revision))
            }
            "album", "artist", "likes" -> {
                val response = if (context.kind == "likes") client.likedPlayback()
                    else client.playback(LibraryItem(context.kind, context.id, context.name))
                val returned = response.context
                require(returned == null || (returned.kind == context.kind &&
                    (context.kind == "likes" || returned.id == context.id))) { "Mismatched playback context" }
                val revision = response.revision.takeIf { it > 0 } ?: context.revision
                // The mobile likes DTO may return an empty id. Likes has no server-selected
                // entity identity: retain the explicit input context with the wire's canonical id.
                val resolvedContext = if (context.kind == "likes") context.copy(id = "likes", revision = revision)
                    else if (returned == null) context.copy(revision = revision)
                    else PlaybackContext(returned.kind, returned.id, returned.name, revision)
                OrdinaryQueue(response.items, response.total.toLong(), response.limit, response.truncated, resolvedContext)
            }
            else -> throw IllegalArgumentException("Unsupported playback context")
        }
        currentCoroutineContext().ensureActive()
        require(!loaded.truncated && loaded.limit == loaded.items.size && loaded.total == loaded.items.size.toLong()) { "Incomplete playback collection" }
        val available = loaded.items.filter { it.kind == "track" && it.playable }
        require(available.isNotEmpty()) { "No available music in this collection" }
        val identities = available.map { if (context.kind == "playlist") it.entryId.orEmpty() else it.id }
        require(identities.none { it.isEmpty() } && identities.distinct().size == identities.size) { "Invalid playback occurrence identity" }
        val hasSelection = selectedTrack != null || selectedEntry != null
        val start = if (!hasSelection) 0 else available.indexOfFirst {
            (selectedEntry == null || it.entryId == selectedEntry) && (selectedTrack == null || it.id == selectedTrack)
        }
        // A missing or unavailable clicked row never selects index zero. Even a truncated
        // response is not proof that an absent row still has audio; reject rather than guess.
        require(start >= 0) { "The selected occurrence is unavailable in this context" }
        return result(available.mapIndexed { index, item -> project(item, index) }, loaded.context, start,
            shuffle, first, enqueue, pinStart = hasSelection)
    }

    private data class OrdinaryQueue(val items: List<LibraryItem>, val total: Long, val limit: Int,
        val truncated: Boolean, val context: PlaybackContext)

    private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull

    private fun decodeTrack(fields: JsonObject): PlaybackTrack = json.decodeFromJsonElement(
        JsonObject(fields.filterKeys { it != "selectionToken" } + mapOf("key" to JsonPrimitive(1), "order" to JsonPrimitive(0))))

    private fun hasDerivedIdentity(track: PlaybackTrack) = track.targetId.isNotEmpty() || track.occurrenceId.isNotEmpty() || track.playbackContext?.kind == "derived"

    private fun validatePreparedIdentity(track: PlaybackTrack) {
        if (!hasDerivedIdentity(track)) return
        val context = track.playbackContext
        require(context?.kind == "derived" && track.targetId == context.id && track.occurrenceId.isNotEmpty()) {
            "Incomplete prepared derived occurrence"
        }
    }

    /** Positive music-only projection: never retain a LibraryItem or its filesystem path. */
    private fun project(item: LibraryItem, index: Int): PlaybackTrack {
        require(item.kind == "track" && item.playable && item.durationMs >= 0) { "Unavailable playback track" }
        return PlaybackTrack(item.id, item.name, item.artists.map { PlaybackArtist(it.id, it.name) },
            item.albumName.orEmpty(), item.albumId.orEmpty(), item.durationMs / 1000.0,
            entryId = item.entryId.orEmpty(), key = index + 1L, order = index.toLong())
    }

    private fun result(tracks: List<PlaybackTrack>, context: PlaybackContext?, start: Int,
        shuffle: Boolean = false, first: Boolean = false, enqueue: Boolean = false,
        truncated: Boolean = false, notice: String = "", pinStart: Boolean = false): ResolvedPlaybackCommand {
        val owned = tracks.mapIndexed { index, track -> track.copy(artists = track.artists.toList(), key = index + 1L, order = index.toLong()) }
        require(owned.none { it.id == "." || it.id == ".." }) { "Invalid playback track" }
        PlaybackSnapshot(queue = owned, context = context, position = start, shuffle = shuffle, truncated = truncated, notice = notice).publicationValidated()
        return ResolvedPlaybackCommand(owned, context, start, shuffle, first, enqueue, truncated, notice, pinStart)
    }
}
