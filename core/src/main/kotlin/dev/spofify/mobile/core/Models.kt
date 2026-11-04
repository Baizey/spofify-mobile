package dev.spofify.mobile.core

import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject

@Serializable
data class Session(val accountId: String, val username: String, val role: String, val csrfToken: String)

/** Local invalidation counters only: never account, catalog, or playback state. */
data class PersonalRevision(val likes: Long = 0, val history: Long = 0, val playlists: Long = 0)

enum class PersonalCollection { LIKES, HISTORY, PLAYLISTS }

interface SessionStore {
    fun read(): StoredSession?
    fun write(value: StoredSession?)
}

/** Only the selected origin, expiring session cookie and public session summary are persisted. */
@Serializable
data class StoredSession(val server: String, val cookie: String, val session: Session)

@Serializable
data class ArtistCredit(val id: String, val name: String)

@Serializable
data class Coverage(val tracks: Int = 0, val inLibrary: Int = 0, val albums: Int = 0)

@Serializable
data class LibraryItem(
    val kind: String,
    val id: String,
    val name: String,
    val artists: List<ArtistCredit> = emptyList(),
    val albumId: String? = null,
    val albumName: String? = null,
    val durationMs: Long = 0,
    val hasAudio: Boolean? = null,
    val libraryPath: String? = null,
    val coverage: Coverage = Coverage(),
    val genres: List<String> = emptyList(),
    val albumType: String? = null,
    val releaseDate: String? = null,
    val relation: String? = null,
    val discNumber: Int = 0,
    val trackNumber: Int = 0,
    val entryId: String? = null,
) {
    val playable: Boolean
        get() = if (kind == "track") hasAudio ?: !libraryPath.isNullOrEmpty() else coverage.inLibrary > 0
    val subtitle: String
        get() = artists.joinToString(", ") { it.name }.ifEmpty { albumName ?: kind }
}

@Serializable
data class LibraryPage(val items: List<LibraryItem>, val total: Int, val limit: Int = 40, val offset: Int = 0, val revision: Long = 0)

@Serializable
data class PlaybackQueue(
    val items: List<LibraryItem>,
    val total: Int = 0,
    val limit: Int = 1000,
    val truncated: Boolean = false,
    val context: LibraryPlaybackContext? = null,
    val revision: Long = 0,
    val preparedState: PlaybackSnapshot? = null,
    @Transient val selectionAuthority: PreparedPlaybackAuthority? = null,
)

/**
 * Ephemeral selection permission, never part of a serialized queue or prepared wire snapshot.
 * Identity is bound to the original request's client/session and its selected musical occurrence.
 */
class PreparedPlaybackAuthority internal constructor(
    private val source: PreparedPlaybackSource,
    private val selectionToken: String?,
    private val context: PlaybackContext,
    private val trackId: String,
    private val targetId: String,
    private val occurrenceId: String,
) {
    fun isValidFor(client: SpofifyClient, state: PlaybackSnapshot): Boolean {
        if (!source.isCurrent(client)) return false
        val selected = state.queue.getOrNull(state.position) ?: return false
        return matchesContext(state.context) && matchesContext(selected.playbackContext) &&
            selected.id == trackId && selected.targetId == targetId && selected.occurrenceId == occurrenceId
    }

    /** Null is also the original whole-collection request: check isValidFor separately. */
    fun selectionTokenFor(client: SpofifyClient, state: PlaybackSnapshot): String? =
        if (isValidFor(client, state)) selectionToken else null

    private fun matchesContext(value: PlaybackContext?): Boolean = value != null &&
        value.kind == context.kind && value.id == context.id &&
        value.revision == context.revision && value.fence == context.fence

    override fun toString(): String = "PreparedPlaybackAuthority([redacted])"
}

/** Capture BEFORE the request; binding after its response must never capture a newer session. */
internal class PreparedPlaybackSource private constructor(
    private val client: SpofifyClient,
    private val origin: String,
    private val session: Session?,
    private val generation: Long,
) {
    internal fun isCurrent(candidate: SpofifyClient): Boolean = candidate === client &&
        candidate.server.origin == origin && session != null && candidate.currentSession() === session &&
        candidate.personalMutationGeneration() == generation

    internal fun bind(selectionToken: String?, state: PlaybackSnapshot): PreparedPlaybackAuthority? {
        if (!isCurrent(client)) return null
        val context = state.context ?: return null
        val selected = state.queue.getOrNull(state.position) ?: return null
        if (context.kind != "derived" || selected.targetId != context.id || selected.occurrenceId.isEmpty() ||
            selected.playbackContext != context) return null
        return bind(selectionToken, context, selected.id, selected.occurrenceId)
    }

    /** The API has already validated this returned context/start; do not add response validation here. */
    internal fun bind(
        selectionToken: String?,
        context: PlaybackContext,
        selectedTrackId: String?,
        selectedOccurrenceId: String?,
    ): PreparedPlaybackAuthority? {
        if (!isCurrent(client) || selectedTrackId == null || selectedOccurrenceId == null) return null
        return PreparedPlaybackAuthority(this, selectionToken, context, selectedTrackId, context.id, selectedOccurrenceId)
    }

    override fun toString(): String = "PreparedPlaybackSource([redacted])"

    internal companion object {
        fun capture(client: SpofifyClient): PreparedPlaybackSource {
            val generation = client.personalMutationGeneration()
            return PreparedPlaybackSource(client, client.server.origin, client.currentSession(), generation)
        }
    }
}

@Serializable
data class LibraryPlaybackContext(val kind: String, val id: String = "", val name: String = "")

@Serializable
data class PlaylistSummary(
    val id: String,
    val name: String,
    val description: String = "",
    val trackCount: Int = 0,
    val availableCount: Int = 0,
    val revision: Long = 0,
)

@Serializable
data class PlaylistPage(val items: List<PlaylistSummary>, val total: Int, val limit: Int = 40, val offset: Int = 0)

/**
 * Messages are local and safe, never a server body or URL. responseBody is optional, strictly
 * validated device reconciliation data only; it must not be logged or rendered as error text.
 */
class ApiException(
    val status: Int,
    override val message: String,
    val code: String? = null,
    val responseBody: JsonObject? = null,
) : IOException(message)
