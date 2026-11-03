package dev.spofify.mobile.playback

import android.os.Looper
import dev.spofify.mobile.core.PlaybackDeviceCommand
import dev.spofify.mobile.core.PlaybackSnapshot
import dev.spofify.mobile.feature.devices.NativePlaybackEndpoint
import dev.spofify.mobile.feature.devices.PlaybackAuthority
import dev.spofify.mobile.feature.devices.PlaybackEndpointResult

/**
 * Conservative service bridge. Export and suspension are real, not a screen's cached state.
 *
 * Registration/handoff intentionally remains disabled: MediaSession notification commands still
 * act directly on Media3, so advertising an exclusive-output capability would be unsafe. Enabling
 * this requires routing those commands through coordinator authority and implementing fenced,
 * exact import (including per-derived-occurrence contexts) before ANY asynchronous prepare/start.
 * No incoming snapshot is ever loaded as a fallback, and no saved playlist is fetched here.
 */
class Media3PlaybackEndpoint : NativePlaybackEndpoint {
    override val supportsExactHandoff: Boolean = false

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
        snapshot.validated()
        // Validation has no playback/history/network side effects. No false capability ACK.
        return false
    }

    override suspend fun activate(snapshot: PlaybackSnapshot, authority: PlaybackAuthority): PlaybackEndpointResult {
        checkMain()
        snapshot.validated()
        return PlaybackEndpointResult("rejected", exportSnapshot())
    }

    override suspend fun execute(command: PlaybackDeviceCommand, authority: PlaybackAuthority): PlaybackEndpointResult {
        checkMain()
        // Safety interruptions remain available even though musical remote execution is disabled.
        if (command.type == "pause" || command.type == "close") suspendOutput()
        return PlaybackEndpointResult("rejected", exportSnapshot())
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper()) {
        "The service playback endpoint must be called on the main looper"
    }
}
