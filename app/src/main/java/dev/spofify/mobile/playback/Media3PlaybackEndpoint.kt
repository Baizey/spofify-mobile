package dev.spofify.mobile.playback

import android.os.Looper
import dev.spofify.mobile.core.PlaybackDeviceCommand
import dev.spofify.mobile.core.PlaybackSnapshot
import dev.spofify.mobile.feature.devices.NativePlaybackEndpoint
import dev.spofify.mobile.feature.devices.PlaybackAuthority
import dev.spofify.mobile.feature.devices.PlaybackEndpointResult

/** Same-process bridge to the single service output. Preparation is a pure exact-import plan. */
class Media3PlaybackEndpoint : NativePlaybackEndpoint {
    override val supportsExactHandoff: Boolean = true

    override fun suspendOutput() {
        checkMain()
        PlaybackRequests.invalidate()
        PlaybackService.suspendLocalOutput()
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

    override suspend fun activate(snapshot: PlaybackSnapshot, authority: PlaybackAuthority): PlaybackEndpointResult {
        checkMain()
        snapshot.validated()
        if (!authority.isValid()) return PlaybackEndpointResult("rejected", exportSnapshot())
        return PlaybackService.activateSnapshot(snapshot, authority)
    }

    override suspend fun execute(command: PlaybackDeviceCommand, authority: PlaybackAuthority): PlaybackEndpointResult {
        checkMain()
        if (!authority.isValid()) return PlaybackEndpointResult("rejected", exportSnapshot())
        return PlaybackService.executeCommand(command, authority)
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper()) {
        "The service playback endpoint must be called on the main looper"
    }
}
