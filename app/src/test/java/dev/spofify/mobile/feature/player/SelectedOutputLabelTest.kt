package dev.spofify.mobile.feature.player

import dev.spofify.mobile.core.LibraryPlaybackDevice
import dev.spofify.mobile.feature.devices.PlaybackDevicesState
import dev.spofify.mobile.feature.devices.PlaybackDeviceSelection
import dev.spofify.mobile.playback.testLibrarySnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectedOutputLabelTest {
    private fun state(selected: String = "phone", available: Boolean = true) = PlaybackDevicesState(
        deviceId = "phone", connectionId = "socket-phone", connected = true,
        snapshot = testLibrarySnapshot(listOf(LibraryPlaybackDevice("phone", "Pocket", available, true),
            LibraryPlaybackDevice("room", "Living room", true, true)), selected))

    @Test fun truthfulLocalRemoteUnavailableAndUnselectedLabels() {
        assertEquals("This phone · Pocket", selectedOutputLabel(state()))
        assertEquals("Living room", selectedOutputLabel(state("room")))
        assertEquals("This phone · Pocket · Unavailable", selectedOutputLabel(state(available = false)))
        assertEquals("Living room · Unavailable", selectedOutputLabel(state("room").copy(connected = false)))
        assertEquals("No output selected", selectedOutputLabel(state("")))
        assertEquals("Outputs unavailable", selectedOutputLabel(null))
        assertEquals("Outputs unavailable", selectedOutputLabel(PlaybackDevicesState()))
        assertEquals("Loading outputs", selectedOutputLabel(PlaybackDevicesState(connecting = true)))
    }
    @Test fun pendingChoiceNeverPretendsToBeActualOutput() {
        assertEquals("This phone · Pocket", selectedOutputLabel(state().copy(
            pendingSelection = PlaybackDeviceSelection("room", 1))))
    }
}
