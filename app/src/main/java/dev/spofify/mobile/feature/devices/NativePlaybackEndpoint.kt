package dev.spofify.mobile.feature.devices

import dev.spofify.mobile.core.LibraryPlaybackInstruction
import dev.spofify.mobile.core.PlaybackSnapshot

/** The sole service output. All calls and observations belong to the main dispatcher. */
interface NativePlaybackEndpoint {
    /** Operation ID is captured when the native listener is installed, never resampled. */
    var onObservation: ((instructionId: String, ended: Boolean) -> Unit)?
    /** Load paused; may authenticate/cache audio but cannot gain output authority or focus. */
    suspend fun prepare(instruction: LibraryPlaybackInstruction, isCurrent: () -> Boolean): String
    /** Cancel every pending native start and drain output BEFORE returning true. Retain prepared media. */
    fun release(): Boolean
    /** Absolute server execution only. Recheck authority after every asynchronous boundary. */
    suspend fun execute(instruction: LibraryPlaybackInstruction, authority: LibraryPlaybackAuthority): String
    /** Facts measured from Media3, never desiredPlaying or an imported elapsed/duration. */
    fun observe(): LibraryPlaybackObservation
    /** Retain successful local execution lineage for bounded, cache-only canonical plan reads.
     * This cannot authorize player reads, focus, starts, reports, ACKs or history. */
    fun parkCacheReads(lineage: LibraryPlaybackCacheLineage) = Unit
    fun suspendOutput()
    /** Drain a proven live replacement, retaining only bounded actual foreground ownership.
     * No release/stopped proof or independent permission to start is conferred. */
    fun suspendForReplacement() = suspendOutput()
}

/** Process-local trusted Android action, never server authority. The service captures its
 * freshness/identity before send; the coordinator supplies exact local acceptance lineage. */
class LibraryPlaybackUserAction internal constructor(
    private val bind: (LibraryPlaybackInstruction, () -> Boolean) -> Unit,
) {
    private var consumed = false
    private var revoked = false
    internal fun revoke() { revoked = true }
    internal fun bind(instruction: LibraryPlaybackInstruction, isCurrent: () -> Boolean) {
        if (consumed || revoked || instruction.type !in setOf("execute", "activate") || !instruction.desiredPlaying) return
        consumed = true
        bind.invoke(instruction) { !revoked && isCurrent() }
    }
}

class LibraryPlaybackAuthority internal constructor(private val check: () -> Boolean) {
    fun isValid(): Boolean = check()
}

/** Eligibility only: every cache-data plan still needs its own finite, exact-plan permit. */
class LibraryPlaybackCacheLineage internal constructor(private val check: () -> Boolean) {
    fun isValid(): Boolean = check()
}

data class LibraryPlaybackObservation(
    val elapsed: Double = 0.0, val duration: Double = 0.0,
    val isPlaying: Boolean = false, val isBuffering: Boolean = false,
    val isLoading: Boolean = false, val error: String = "",
)

/** Deliberate LOCAL/OFFLINE queue result only; never uploaded as online authority. */
data class PlaybackEndpointResult(
    val status: String,
    val snapshot: PlaybackSnapshot,
) {
    init { require(status in setOf("ok", "blocked", "media", "rejected")) }
}
