package dev.spofify.mobile.core.saved

import dev.spofify.mobile.core.ServerAddress
import java.io.Closeable
import java.io.InputStream
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** Scope is supplied by authenticated account ownership, never inferred from a username/cookie. */
data class SavedScope(val origin: String, val accountId: String) {
    init {
        require(ServerAddress.parse(origin).origin == origin)
        require(accountId.isNotBlank())
    }
}

@Serializable
enum class SavedTargetKind { MANUAL_PLAYLIST, DERIVED_PLAYLIST, ALBUM, ARTIST }

@Serializable
data class SavedTarget(val kind: SavedTargetKind, val id: String) {
    init { require(id.isNotBlank()) }
}

/** Deliberately excludes URLs, library paths, tokens and serialized playback authority. */
@Serializable
data class SavedOccurrence(
    val occurrenceId: String,
    val trackId: String,
    val title: String,
    val artist: String = "",
    val albumId: String? = null,
    val albumTitle: String? = null,
    val durationMs: Long = 0,
    val hasAudio: Boolean = true,
) {
    init { require(occurrenceId.isNotBlank() && trackId.isNotBlank() && durationMs >= 0) }
}

/** Exact representation proof. Transport URLs and credentials remain solely in the API adapter. */
@Serializable
data class SavedMediaDescriptor(
    val trackId: String,
    val mediaVersion: String,
    val sha256: String,
    val byteLength: Long,
    val strongETag: String,
) {
    init {
        require(trackId.isNotBlank() && byteLength > 0)
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        require(mediaVersion == "sha256:$sha256" && strongETag == "\"$mediaVersion\"")
    }
}

enum class SavedDownloadState { DOWNLOADING, PARTIAL, COMPLETE, FAILED }

data class SavedOccurrenceSnapshot(
    val occurrence: SavedOccurrence,
    val media: SavedMediaDescriptor?,
    val available: Boolean,
)

data class SavedCollectionSnapshot(
    val target: SavedTarget,
    val title: String,
    val generation: String,
    val occurrences: List<SavedOccurrenceSnapshot>,
    val state: SavedDownloadState,
    val updating: Boolean,
    val transferredBytes: Long,
    /** A safe local error code, never raw server error text. */
    val failure: String?,
)

interface SavedMediaLease : Closeable {
    val descriptor: SavedMediaDescriptor
    val input: InputStream
}

/** Blocking disk operations: call from an IO dispatcher. Does not authorize server control/playback. */
interface SavedLibraryReader {
    /** Changes after every visible mutation/fence. Collect then read scoped snapshots on IO. */
    val revision: StateFlow<Long>
    fun snapshots(scope: SavedScope): List<SavedCollectionSnapshot>
    fun snapshot(scope: SavedScope, target: SavedTarget): SavedCollectionSnapshot?
    fun open(scope: SavedScope, target: SavedTarget, occurrenceId: String): SavedMediaLease?
}

/** Captures the retired incarnation, so delayed IO cleanup cannot revoke a successor login. */
class SavedScopeFence internal constructor(internal val namespace: String?)

/** In-memory stage capability; invalidated by cancellation, unsave, rebind, retire or a newer Update. */
class SavedManifestStage internal constructor(internal val token: String)

/** No wire DTO coupling: the production Offline API maps its descriptors to these values. */
interface SavedDownloadTransport {
    /** Throw SavedTransportException with an HTTP status; never include remote text in its message.
     * A raw 401 is not retirement authority: only the authenticated owner's accepted INVALIDATED
     * event calls fenceAccess() followed by deferred retire(). Stale credential responses may fail
     * this transfer while its healthy saved namespace remains available.
     */
    suspend fun prepare(scope: SavedScope, trackId: String): SavedMediaDescriptor
    /**
     * offset > 0 MUST send Range: bytes=offset- and If-Range: descriptor.strongETag.
     * Invoke consume inside the authenticated response worker; body never escapes that callback.
     * Coroutine cancellation must cancel pending opens and blocked body reads.
     */
    suspend fun transfer(scope: SavedScope, descriptor: SavedMediaDescriptor, offset: Long, consume: (SavedTransferResponse) -> Unit)
}

class SavedTransferResponse(
    val status: Int,
    val body: InputStream? = null,
    val contentLength: Long? = null,
    val contentRange: String? = null,
    val etag: String? = null,
    val retryAfterMillis: Long? = null,
    private val onClose: () -> Unit = {},
) : Closeable {
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    override fun close() { if (closed.compareAndSet(false, true)) try { body?.close() } finally { onClose() } }
}

class SavedTransportException(val status: Int, val retryAfterMillis: Long? = null) :
    java.io.IOException("Saved media request failed ($status)")

/** Store-owned cancellation registration, including the suspending operation's child Job. */
internal class SavedTransferCancellation(private val isActive: () -> Boolean = { true }) : Closeable {
    private val lock = Any()
    private var cancelled = false
    private var sequence = 0L
    private val callbacks = linkedMapOf<Long, () -> Unit>()
    fun onCancel(callback: () -> Unit): Closeable {
        val id: Long
        synchronized(lock) {
            id = ++sequence
            if (!cancelled) callbacks[id] = callback
            else { callback(); return Closeable {} }
        }
        return Closeable { synchronized(lock) { callbacks.remove(id) } }
    }
    fun throwIfCancelled() {
        synchronized(lock) {
            if (cancelled || !isActive()) throw kotlinx.coroutines.CancellationException("Saved transfer cancelled")
        }
    }
    override fun close() {
        val pending = synchronized(lock) {
            if (cancelled) return
            cancelled = true
            callbacks.values.toList().also { callbacks.clear() }
        }
        pending.forEach { runCatching { it() } }
    }
}

/** Caller delegates local eligibility; authoritative auth invalidation separately fences access. */
fun interface SavedLocalEligibility { fun eligible(scope: SavedScope): Boolean }
