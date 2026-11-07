package dev.spofify.mobile.playback

import android.os.Looper
import dev.spofify.mobile.core.PlaybackDeviceCommand
import dev.spofify.mobile.core.PlaybackSnapshot
import dev.spofify.mobile.core.QueueIntentState
import dev.spofify.mobile.feature.devices.NativePlaybackEndpoint
import dev.spofify.mobile.feature.devices.PlaybackAuthority
import dev.spofify.mobile.feature.devices.PlaybackEndpointResult

/** Same-process bridge to the single service output. Preparation is a pure exact-import plan. */
class Media3PlaybackEndpoint : NativePlaybackEndpoint {
    override fun retainCachedOutput(permission: dev.spofify.mobile.feature.devices.CachedPlaybackPermission): Boolean {
        checkMain()
        return PlaybackService.retainCachedOutput(permission)
    }
    override fun continuingOutputWitness(permission: dev.spofify.mobile.feature.devices.CachedPlaybackPermission): Any? {
        checkMain()
        return PlaybackService.continuingOutputWitness(permission)
    }
    override fun renewContinuingOutput(permission: dev.spofify.mobile.feature.devices.CachedPlaybackPermission,
        authority: PlaybackAuthority, witness: Any): Boolean {
        checkMain()
        return PlaybackService.renewContinuingOutput(permission, authority, witness)
    }
    override val supportsExactHandoff: Boolean = true
    override val supportsQueueIntents: Boolean = true

    override fun exportQueueIntentState(): QueueIntentState? {
        checkMain()
        return PlaybackService.exportLocalQueueIntentState()
    }

    override fun suspendOutput() {
        checkMain()
        PlaybackRequests.invalidate()
        PlaybackService.suspendLocalOutput()
    }

    override fun interruptQueueIntentStart(): Boolean {
        checkMain()
        return PlaybackService.interruptLocalQueueIntentStart()
    }

    override fun captureRevisionSync(): dev.spofify.mobile.feature.devices.PlaybackSyncCapture? {
        checkMain()
        return PlaybackService.captureLocalRevisionSync()
    }

    override fun exportSnapshot(): PlaybackSnapshot {
        checkMain()
        return PlaybackService.exportLocalSnapshot()
    }

    override suspend fun prepare(snapshot: PlaybackSnapshot): Boolean {
        checkMain()
        // No service call, media loading, queue mutation, history sampling, or network request.
        return runCatching { PlaybackSnapshotCodec.plan(snapshot); true }.getOrDefault(false)
    }

    override suspend fun prepare(snapshot: PlaybackSnapshot, queueIntentState: QueueIntentState?): Boolean {
        checkMain()
        return runCatching {
            PlaybackSnapshotCodec.plan(snapshot, queueIntentState)
            true
        }.getOrDefault(false)
    }

    override suspend fun activate(snapshot: PlaybackSnapshot, queueIntentState: QueueIntentState?, authority: PlaybackAuthority): PlaybackEndpointResult {
        checkMain()
        if (!prepare(snapshot, queueIntentState) || !authority.isValid()) return PlaybackEndpointResult("rejected", exportSnapshot())
        return withMetadata(PlaybackService.activateSnapshot(snapshot, queueIntentState, authority))
    }

    private fun withMetadata(result: PlaybackEndpointResult): PlaybackEndpointResult =
        if (result.queueIntentState != null) result else result.copy(queueIntentState = exportQueueIntentState())

    override suspend fun activate(snapshot: PlaybackSnapshot, authority: PlaybackAuthority): PlaybackEndpointResult {
        checkMain()
        snapshot.validated()
        if (!authority.isValid()) return PlaybackEndpointResult("rejected", exportSnapshot())
        return withMetadata(PlaybackService.activateSnapshot(snapshot, authority))
    }

    override suspend fun activatePreparedResume(snapshot: PlaybackSnapshot, authority: PlaybackAuthority): PlaybackEndpointResult {
        checkMain()
        snapshot.validated()
        if (!authority.isValid()) return PlaybackEndpointResult("rejected", exportSnapshot())
        return withMetadata(PlaybackService.activatePreparedResumeSnapshot(snapshot, authority))
    }

    override suspend fun execute(command: PlaybackDeviceCommand, authority: PlaybackAuthority): PlaybackEndpointResult {
        checkMain()
        if (!authority.isValid()) return PlaybackEndpointResult("rejected", exportSnapshot())
        return withMetadata(PlaybackService.executeCommand(command, authority))
    }

    override suspend fun execute(command: PlaybackDeviceCommand, authority: PlaybackAuthority,
        isCurrent: () -> Boolean): PlaybackEndpointResult {
        checkMain()
        if (!authority.isValid() || !isCurrent()) return PlaybackEndpointResult("rejected", exportSnapshot())
        return withMetadata(PlaybackService.executeCommand(command, authority, isCurrent))
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper()) {
        "The service playback endpoint must be called on the main looper"
    }
}
