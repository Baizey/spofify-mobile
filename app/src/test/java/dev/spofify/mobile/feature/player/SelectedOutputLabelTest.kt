package dev.spofify.mobile.feature.player

import dev.spofify.mobile.core.PlaybackDevice
import dev.spofify.mobile.core.PlaybackDevicesView
import dev.spofify.mobile.feature.devices.PlaybackDevicesState
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectedOutputLabelTest {
    private fun state(active: String = "phone", available: Boolean = true) = PlaybackDevicesState(
        deviceId = "phone", connected = true,
        view = PlaybackDevicesView(devices = listOf(PlaybackDevice("phone", "Pocket", available),
            PlaybackDevice("room", "Living room", true)), activeDeviceId = active,
            epoch = 1, leaseMs = 1000, state = null, stateSeq = 0, stateAgeMs = 0,
            transfer = null, directives = emptyList(), commands = emptyList()))

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
            selectedDeviceId = "room", selectionUnconfirmed = true)))
    }
}
