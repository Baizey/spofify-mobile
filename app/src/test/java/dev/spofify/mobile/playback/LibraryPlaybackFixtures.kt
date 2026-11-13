package dev.spofify.mobile.playback

import dev.spofify.mobile.core.*

/** Synthetic DTO construction only, not authority or executor simulation. */
internal fun testLibrarySnapshot(
    devices: List<LibraryPlaybackDevice> = emptyList(), selected: String = "",
    playing: Boolean = false, error: String = "", stateVersion: Long = 1,
    outputVersion: Long = 1, reported: Boolean = true,
): LibraryPlaybackSnapshot {
    val state = LibraryPlaybackState(1, emptyList(), -1, null, false, "off", false,
        emptyList(), 0.0, 0.0, playing, false, false, error, false, "", null)
    val playback = LibraryPlaybackStatus(outputVersion, stateVersion, 1, "", reported,
        0.0, 0.0, playing, false, false, error)
    return LibraryPlaybackSnapshot("room", stateVersion, outputVersion, state, playback,
        devices, selected, if (selected.isEmpty()) "" else "socket-$selected", null, 0)
}
