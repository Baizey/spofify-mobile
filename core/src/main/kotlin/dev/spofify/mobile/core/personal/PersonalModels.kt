package dev.spofify.mobile.core.personal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class PersonalPlaylistKind {
    @SerialName("manual") MANUAL,
    @SerialName("derived") DERIVED,
}

@Serializable
enum class PersonalCountState {
    @SerialName("exact") EXACT,
    @SerialName("stale") STALE,
    @SerialName("pending") PENDING,
}

@Serializable
enum class PersonalEvaluationState {
    @SerialName("pending") PENDING,
    @SerialName("ready") READY,
    @SerialName("stale") STALE,
    @SerialName("error") ERROR,
}

@Serializable
data class PersonalArtist(val id: String, val name: String)

@Serializable
data class PersonalCoverage(val tracks: Long, val inLibrary: Long, val albums: Long)

/** Exact positive ListeningItem projection; hasAudio is required, even for administrators. */
@Serializable
data class PersonalTrack(
    val kind: String,
    val id: String,
    val name: String,
    val hasAudio: Boolean,
    val coverage: PersonalCoverage,
    val artists: List<PersonalArtist> = emptyList(),
    val genres: List<String> = emptyList(),
    val albumId: String = "",
    val albumName: String = "",
    val albumType: String = "",
    val releaseDate: String = "",
    val relation: String = "",
    val durationMs: Long = 0,
    val discNumber: Int = 0,
    val trackNumber: Int = 0,
)

@Serializable
data class PersonalPlaylistCapabilities(
    val editMetadata: Boolean,
    val editEntries: Boolean,
    val editDefinition: Boolean,
    val play: Boolean,
    val copyToManual: Boolean,
    val delete: Boolean,
    val addTracks: Boolean,
)

@Serializable
data class PersonalPlaylistSummary(
    val id: String,
    val name: String,
    val description: String,
    val kind: PersonalPlaylistKind,
    val countState: PersonalCountState,
    val trackCount: Long?,
    val availableCount: Long?,
    val capabilities: PersonalPlaylistCapabilities,
    val revision: Long,
    val createdAt: String,
    val updatedAt: String,
    val evaluationState: PersonalEvaluationState? = null,
    val evaluationErrorCode: String? = null,
)

@Serializable
data class PersonalLike(val track: PersonalTrack, val likedAt: String)

@Serializable
data class PersonalLikesPage(
    val items: List<PersonalLike>,
    val total: Long,
    val availableTotal: Long,
    val limit: Int,
    val offset: Int,
) {
    val hasMore: Boolean get() = offset.toLong() + items.size < total
}

@Serializable
data class PersonalLikeStatus(val trackId: String, val liked: Boolean)

/** id is the server's entry identity; position is a stored slot, never a page index. */
@Serializable
data class PersonalManualEntry(val id: String, val position: Long, val track: PersonalTrack)

@Serializable
data class PersonalHistoryEntry(
    val id: String,
    val playedAt: String,
    val track: PersonalTrack,
    val origin: String? = null,
    val receivedAt: String? = null,
    val metadataMissing: Boolean = false,
)

enum class PersonalLibraryErrorCode {
    HTTP_ERROR, CONNECTION_FAILED, INVALID_RESPONSE,
    INVALID_REQUEST, INVALID_CURSOR, PLAYLIST_NOT_FOUND, REFERENCE_NOT_FOUND, COLLECTION_NOT_FOUND,
    REVISION_CONFLICT, STALE_EVALUATION, PLAYLIST_KIND_MISMATCH, SOURCE_REFERENCED, SOURCE_UNAVAILABLE,
    RECIPE_CYCLE, RECIPE_LIMIT_EXCEEDED, COPY_LIMIT_EXCEEDED, PLAYLIST_STORAGE_EXCEEDED,
    PLAYLIST_QUOTA_EXCEEDED, EVALUATION_BUSY, EVALUATION_TIMEOUT, EVALUATION_FAILED, PUBLICATION_LIMIT,
}

/** status=0 denotes a transport failure. No raw response body, URL, or diagnostic cause escapes. */
class PersonalLibraryException(val status: Int, val code: PersonalLibraryErrorCode) :
    Exception("Personal library request failed ($status, ${code.name})")

interface PersonalLibraryApi {
    suspend fun likes(limit: Int = 40, offset: Int = 0): PersonalLikesPage
    /** Preserves request order and repeated IDs; never return a map. */
    suspend fun likeStatus(trackIds: List<String>): List<PersonalLikeStatus>
    suspend fun setLike(trackId: String, liked: Boolean): PersonalLikeStatus
}
