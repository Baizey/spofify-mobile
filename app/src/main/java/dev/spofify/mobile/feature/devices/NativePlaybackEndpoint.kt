package dev.spofify.mobile.feature.devices

import dev.spofify.mobile.core.PlaybackDeviceCommand
import dev.spofify.mobile.core.PlaybackSnapshot

/**
 * Implemented by the application/service's ONE Media3 output, never a screen player.
 * Every start, async continuation and audible-history write must check authority.isValid().
 * Import/export preserves the entire prepared queue, duplicate keys/order baseline, context,
 * occurrence event nonce/progress/reported flag. Never fetch a saved playlist during import.
 * Resolve audio ONLY through authenticated client.streamUrl(track.id), never incoming URLs.
 */
interface NativePlaybackEndpoint {
    /** False until exact queue/history import AND generation fencing are implemented. No fake capability registration. */
    val supportsExactHandoff: Boolean
    /** Synchronous, idempotent: cancel ALL pending starts/generations, pause actual buffered output. No history reset. */
    fun suspendOutput()
    /** Positive wire DTO, seconds (not milliseconds), no URL/token/path. Must reflect actual output. */
    fun exportSnapshot(): PlaybackSnapshot
    /** Validation/preparation only; MUST NOT replace queue, load audio, play, or write history. */
    suspend fun prepare(snapshot: PlaybackSnapshot): Boolean
    /** Atomically replace queue with snapshot and attempt its explicit playing intent, under live authority. */
    suspend fun activate(snapshot: PlaybackSnapshot, authority: PlaybackAuthority): PlaybackEndpointResult
    /** Explicit musical/queue/transport action. Pause/close cancel older async work before acting. */
    suspend fun execute(command: PlaybackDeviceCommand, authority: PlaybackAuthority): PlaybackEndpointResult
}

class PlaybackAuthority internal constructor(val epoch: Long, val generation: Long, private val check: () -> Boolean) {
    fun isValid(): Boolean = check()
}

/** Service-instance-scoped close disposal; queued replacement intent only HOLDS its obligation. */
class PlaybackFinalClose internal constructor(
    private val finish: (dispose: Boolean) -> Unit,
    private val releaseReplacement: () -> Unit,
) {
    fun dispose() = finish(true)
    fun holdForReplacement() = finish(false)
    /** Safe from a job-completion callback: the service marshals release to its main looper. */
    fun finishReplacement() = releaseReplacement()
}

data class PlaybackEndpointResult(
    val status: String,
    val snapshot: PlaybackSnapshot,
    /** Transient lifecycle capability, never serialized into snapshots or protocol state. */
    val finalClose: PlaybackFinalClose? = null,
) {
    init {
        require(status in setOf("ok", "blocked", "media", "rejected"))
        snapshot.validated()
        if (status == "blocked" || status == "media") require(!snapshot.playing && snapshot.error == status)
    }
}
