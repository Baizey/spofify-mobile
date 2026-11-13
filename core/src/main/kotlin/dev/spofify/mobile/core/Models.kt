package dev.spofify.mobile.core

import java.io.IOException
import kotlinx.serialization.Serializable

@Serializable
data class Session(val accountId: String, val username: String, val role: String, val csrfToken: String)

/** Local invalidation counters only: never account, catalog, or playback state. */
data class PersonalRevision(val likes: Long = 0, val history: Long = 0, val playlists: Long = 0)

enum class PersonalCollection { LIKES, HISTORY, PLAYLISTS }

interface SessionStore {
    fun read(): StoredSession?
    fun write(value: StoredSession?)
}

/** Origin-bound encrypted auth record. Mode is negotiated authority metadata, not a new credential. */
@Serializable
data class StoredSession(val server: String, val cookie: String, val session: Session, val sessionMode: String? = null)

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

/** Messages are local and safe, never a server body, URL or playback reconciliation payload. */
class ApiException(
    val status: Int,
    override val message: String,
    val code: String? = null,
) : IOException(message)
