package dev.spofify.mobile.core.offline

import dev.spofify.mobile.core.personal.PersonalTrack
import kotlinx.serialization.Serializable

enum class OfflineCollectionKind(val path: String) { PLAYLIST("playlists"), ALBUM("albums"), ARTIST("artists") }
data class OfflineCollectionRef(val kind: OfflineCollectionKind, val id: String)
enum class OfflinePlaylistKind { MANUAL, DERIVED }

data class OfflineCollection(
    val reference: OfflineCollectionRef,
    val name: String,
    val playlistKind: OfflinePlaylistKind? = null,
    val description: String? = null,
    val revision: Long? = null,
)

data class OfflineOccurrence(val occurrenceId: String, val position: Long, val track: PersonalTrack)

/** Complete fenced membership only. This does not claim any downloaded or playable local bytes. */
data class OfflineManifest(
    val collection: OfflineCollection,
    val fence: String,
    val items: List<OfflineOccurrence>,
    val total: Long,
    val recordedAudioTotal: Long,
)

/** Prepared current representation, not a bearer capability or permission to authenticate arbitrary URLs. */
data class OfflineMediaDescriptor(
    val trackId: String,
    val mediaVersion: String,
    val sha256: String,
    val byteLength: Long,
    val format: String,
    val contentType: String,
    val downloadPath: String,
)

/** Construct once at actual qualification; an outbox must persist and retry this exact immutable payload. */
@Serializable
data class OfflinePlayReceipt(
    val eventId: String,
    val trackId: String,
    val occurredAtMs: Long,
    val elapsedMs: Long,
    val progressedMs: Long,
    val origin: String = "offline",
) {
    init {
        require(OfflineValidation.id(eventId, 128) && OfflineValidation.id(trackId) && origin == "offline" &&
            elapsedMs >= 1000 && progressedMs in 1000..elapsedMs && occurredAtMs >= elapsedMs) {
            "Invalid offline receipt"
        }
    }
}

data class OfflineReceiptAck(val eventId: String, val receivedAtMs: Long, val duplicate: Boolean)

/** Local rejection bounds, not server catalog-size guarantees. Exceeding either fails the WHOLE read. */
data class OfflineReadLimits(val maxManifestBytes: Long = 16L * 1024 * 1024, val maxOccurrences: Int = 100_000) {
    init { require(maxManifestBytes > 0 && maxOccurrences > 0) }
}

enum class OfflineFailure {
    INVALID_REQUEST, INVALID_RESPONSE, LIMIT_EXCEEDED, STALE_MANIFEST,
    CONFLICT_REPREPARE, NOT_FOUND, UNAVAILABLE, BUSY, UNAUTHORIZED, FORBIDDEN, UNSUPPORTED,
    INVALID_CURSOR, SOURCE_UNAVAILABLE, MEDIA_CHANGED, TIMEOUT,
    RECEIPT_CONFLICT, CLOCK_ERROR,
}

/** No raw transport/provider errors, operational diagnostics, cookies, or response bodies escape. */
class OfflineException(val failure: OfflineFailure, val status: Int = 0) : Exception("Offline request failed")

/** One authenticated account/session incarnation. No downloader, storage, polling, or automatic retry. */
interface OfflineApi {
    suspend fun manifest(collection: OfflineCollectionRef): OfflineManifest
    suspend fun prepareMedia(trackId: String): OfflineMediaDescriptor
    suspend fun submitReceipt(receipt: OfflinePlayReceipt): OfflineReceiptAck
}

internal object OfflineValidation {
    fun text(value: String, maxBytes: Int, multiline: Boolean = false): Boolean =
        value.toByteArray(Charsets.UTF_8).size <= maxBytes &&
            value.codePoints().noneMatch { it in 0xD800..0xDFFF } &&
            value.none { it.isISOControl() && !(multiline && (it == '\n' || it == '\t')) }

    fun id(value: String, maxBytes: Int = 512): Boolean = value.isNotBlank() && value == value.trim() && text(value, maxBytes)
}
