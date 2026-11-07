package dev.spofify.mobile.feature.devices

import dev.spofify.mobile.core.PlaybackDeviceCommand
import dev.spofify.mobile.core.PlaybackSnapshot
import dev.spofify.mobile.core.QueueIntentState
import dev.spofify.mobile.core.QueueIntentResult

/**
 * Implemented by the application/service's ONE Media3 output, never a screen player.
 * Every start, async continuation and audible-history write must check authority.isValid().
 * Import/export preserves the entire prepared queue, duplicate keys/order baseline, context,
 * occurrence event nonce/progress/reported flag. Never fetch a saved playlist during import.
 * Resolve audio ONLY through authenticated client.streamUrl(track.id), never incoming URLs.
 */
interface NativePlaybackEndpoint {
    /** False until exact queue/history import AND generation fencing are implemented. No fake capability registration. */
    /** Install only over already-running exact local output; never starts or imports. */
    fun retainCachedOutput(permission: CachedPlaybackPermission): Boolean = false
    /** Rebind a genuinely renewed existing receiver lease to THIS continuing output only.
     * Synchronous acknowledgement; never import, prepare, start, seek or reset history. */
    fun renewContinuingOutput(permission: CachedPlaybackPermission, authority: PlaybackAuthority): Boolean = false
    val supportsExactHandoff: Boolean
    /** Advertise only after complete authoritative queue/provenance/result support. */
    val supportsQueueIntents: Boolean get() = false
    fun exportQueueIntentState(): QueueIntentState? = null
    /** Serial-owner capture. Null keeps legacy endpoints on full publication. Implementations
     * must not export/project/validate the queue merely to observe an unchanged fence. */
    fun captureRevisionSync(): PlaybackSyncCapture? = null
    /** Synchronous, idempotent: cancel ALL pending starts/generations, pause actual buffered output. No history reset. */
    fun suspendOutput()
    /** Explicit same-owner Pause interrupt only. Coordinator calls ONLY while its tracked
     * queue execution/publication is live (including a finalized frozen ACK awaiting HTTP).
     * Preserve lease and accepted receipt. False means live leased authority is unavailable. */
    fun interruptQueueIntentStart(): Boolean = false
    /** Positive wire DTO, seconds (not milliseconds), no URL/token/path. Must reflect actual output. */
    fun exportSnapshot(): PlaybackSnapshot
    /** Validation/preparation only; MUST NOT replace queue, load audio, play, or write history. */
    suspend fun prepare(snapshot: PlaybackSnapshot): Boolean
    /** Null is an intentional legacy provenance reset, not unknown-version downgrade. */
    suspend fun prepare(snapshot: PlaybackSnapshot, queueIntentState: QueueIntentState?): Boolean =
        queueIntentState == null && prepare(snapshot)
    /** Atomically replace queue with snapshot and attempt its explicit playing intent, under live authority. */
    suspend fun activate(snapshot: PlaybackSnapshot, authority: PlaybackAuthority): PlaybackEndpointResult
    suspend fun activate(snapshot: PlaybackSnapshot, queueIntentState: QueueIntentState?, authority: PlaybackAuthority): PlaybackEndpointResult =
        if (queueIntentState == null) activate(snapshot, authority) else PlaybackEndpointResult("rejected", exportSnapshot())
    /** Explicit in-app Resume staging only: accept authority over an EXACT already-live paused
     * occurrence without importing/replacing its queue or resetting local provenance/history.
     * The admission caller must retain the original service/queue/current lifetime fence.
     * Unsupported endpoints fail closed; never fall back to generic snapshot activation. */
    suspend fun activatePreparedResume(snapshot: PlaybackSnapshot, authority: PlaybackAuthority): PlaybackEndpointResult =
        PlaybackEndpointResult("rejected", exportSnapshot())
    /** Explicit musical/queue/transport action. Pause/close cancel older async work before acting. */
    suspend fun execute(command: PlaybackDeviceCommand, authority: PlaybackAuthority): PlaybackEndpointResult
    /** Origin lifetime is a transaction guard, never part of durable audible lease authority. */
    suspend fun execute(command: PlaybackDeviceCommand, authority: PlaybackAuthority, isCurrent: () -> Boolean): PlaybackEndpointResult =
        if (isCurrent()) execute(command, authority) else PlaybackEndpointResult("rejected", exportSnapshot())
}

/** Cache-only local continuation, deliberately not receiver/lease authority. */
data class PlaybackSyncCapture(
    val semanticFence: Any, val live: dev.spofify.mobile.core.PlaybackSyncLive,
    val structure: PlaybackSnapshot, val metadata: QueueIntentState?,
) {
    fun snapshot(): PlaybackSnapshot = live.applyTo(structure)
}

class CachedPlaybackPermission internal constructor(private val check: () -> Boolean) {
    fun isValid(): Boolean = check()
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
    /** Frozen with snapshot on the service main looper, never resampled by ACK transport. */
    val queueIntentState: QueueIntentState? = null,
    val queueIntentResult: QueueIntentResult? = null,
    /** Process-local committed-ticket receipt; never protocol authority or serialized. */
    val additiveTransition: dev.spofify.mobile.playback.PlaybackAdditiveTransition? = null,
) {
    init {
        require(status in setOf("ok", "blocked", "media", "rejected"))
        snapshot.validated()
        queueIntentState?.validated(snapshot)
        queueIntentResult?.validated(snapshot, requireNotNull(queueIntentState), status)
        if (status == "blocked" || status == "media") require(!snapshot.playing && snapshot.error == status)
    }
}
