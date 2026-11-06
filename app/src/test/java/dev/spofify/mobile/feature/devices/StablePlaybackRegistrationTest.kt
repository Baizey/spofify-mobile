package dev.spofify.mobile.feature.devices

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.spofify.mobile.core.*
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class StablePlaybackRegistrationTest {
    private class Endpoint : NativePlaybackEndpoint {
        override val supportsExactHandoff = true
        var starts = 0
        override fun suspendOutput() = Unit
        override fun exportSnapshot() = PlaybackSnapshot()
        override suspend fun prepare(snapshot: PlaybackSnapshot) = true
        override suspend fun activate(snapshot: PlaybackSnapshot, authority: PlaybackAuthority): PlaybackEndpointResult {
            starts++; return PlaybackEndpointResult("ok", snapshot)
        }
        override suspend fun execute(command: PlaybackDeviceCommand, authority: PlaybackAuthority): PlaybackEndpointResult {
            starts++; return PlaybackEndpointResult("ok", PlaybackSnapshot())
        }
    }
    private class Protocol(val stableId: String) : PlaybackDevicesProtocol {
        var registers = 0
        val receivers = mutableListOf<String>()
        var retired = false
        var generation = "first-generation"
        var current = view("not-registered")
        private fun view(receiver: String) = PlaybackDevicesView(stableId,
            listOf(PlaybackDevice(stableId, "Phone", true)), "", 1, 0, state = null, stateSeq = 0, stateAgeMs = 0,
            transfer = null, directives = emptyList(), commands = emptyList(), registration = PlaybackRegistrationView(1,
                "room-generation", "admission-token", listOf(PlaybackRegistrationDevice(stableId, receiver, generation))))
        override suspend fun view() = current
        override suspend fun register(name: String): PlaybackDevicesView = error("Managed registration must supply stable identity")
        override suspend fun register(name: String, queueIntentsVersion: Int?, installationId: String?, receiverId: String): PlaybackDevicesView {
            assertEquals(stableId, installationId); assertNull(queueIntentsVersion)
            registers++; receivers += receiverId; retired = false
            current = view(receiverId); return current
        }
        override suspend fun poll(deviceId: String, epoch: Long, stateSeq: Long?, state: PlaybackSnapshot?, acks: List<PlaybackAck>) = current
        override suspend fun rename(deviceId: String, name: String) = current
        override suspend fun command(deviceId: String, epoch: Long, commandId: String, command: PlaybackDeviceCommand) = current
        override suspend fun transfer(deviceId: String, epoch: Long, commandId: String, targetDeviceId: String, selectionSeq: Long) = current
        override suspend fun leave(deviceId: String) { retired = true }
        fun replace() {
            generation = "successor-generation"
            current = view("successor-receiver").copy(activeDeviceId = stableId, leaseMs = 8000,
                directives = listOf(PlaybackDirective("successor-activation", 1, "activate", state = PlaybackSnapshot())))
        }
    }
    @Test fun stableEnrollmentSurvivesHealthyReattachmentAndRecreationWithoutPassiveSelectionOrSuccessorAuthority() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("stable-registration-test", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().clear().commit())
        val store = PlaybackDeviceIdentityStore(prefs, { SecretKeySpec(ByteArray(32) { 1 }, "AES") },
            ServerAddress.parse("https://music.example"), "synthetic-account", { "1234567890abcdef" })
        val stable = store.readOrCreate().stableDeviceId
        val api = Protocol(stable)
        val endpoint = Endpoint()
        val c = PlaybackCoordinator(api, endpoint, backgroundScope, identity = { store }, clockMs = { testScheduler.currentTime })
        c.setServiceActive(true); runCurrent()
        assertEquals(stable, c.state.value.deviceId)
        assertEquals(1, api.registers)
        assertEquals("", c.state.value.activeDeviceId)
        c.setServiceActive(true); c.retry(); runCurrent(); advanceTimeBy(3000); runCurrent()
        assertEquals(1, api.registers)
        assertEquals(0, endpoint.starts)
        c.setServiceActive(false); runCurrent(); assertTrue(api.retired)
        c.setServiceActive(true); runCurrent()
        assertEquals(2, api.registers)
        assertEquals(stable, c.state.value.deviceId)
        assertNotEquals(api.receivers[0], api.receivers[1])
        assertEquals(0, endpoint.starts)
        api.replace(); c.retry(); runCurrent()
        assertFalse(c.state.value.exclusiveOwner)
        assertFalse(c.state.value.connected)
        assertEquals(0, endpoint.starts)
        c.retry(); runCurrent()
        assertEquals(2, api.registers) // An old receiver must not replace the observed successor.
        c.close(); runCurrent()
    }
}
