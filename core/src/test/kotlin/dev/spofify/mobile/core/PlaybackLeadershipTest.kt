package dev.spofify.mobile.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackLeadershipTest {
    private val phone = "a".repeat(32)
    private fun view(lead: String) = """{"deviceId":"","devices":[],"activeDeviceId":"","epoch":1,"leaseMs":0,"state":null,"stateSeq":0,"stateAgeMs":0,"transfer":null,"directives":[],"commands":[],"leadership":$lead}"""
    @Test fun negotiatedRawLeadershipRetainsExactRevisionAndChoice() {
        val decoded = PlaybackDevicesView.fromRawJson(view("""{"version":1,"revision":42,"state":"installation","deviceId":"$phone","acceptedRevision":41}"""))
        assertTrue(decoded.leadership!!.names(phone))
        assertEquals(42L, decoded.leadership!!.revision)
        assertEquals(41L, decoded.leadership!!.acceptedRevision)
        assertEquals(decoded, PlaybackDevicesView.fromRawJson(decoded.toJson().toString()))
    }
    @Test fun nonInstallationChoicesCannotNameADevice() {
        for (state in listOf("unset", "legacy", "cleared")) {
            assertFalse(PlaybackLeadership(1, 1, state, "").validated().names(phone))
            assertThrows(IllegalArgumentException::class.java) { PlaybackLeadership(1, 1, state, phone).validated() }
        }
    }
    @Test fun unknownMalformedAndDuplicateLeadershipFailClosed() {
        for (lead in listOf(
            """{"version":2,"revision":1,"state":"installation","deviceId":"$phone"}""",
            """{"version":1,"revision":"1","state":"installation","deviceId":"$phone"}""",
            """{"version":1,"revision":1,"revision":2,"state":"installation","deviceId":"$phone"}""",
            """{"version":1,"revision":1,"state":"installation","deviceId":""}""",
            """{"version":1,"revision":1,"state":"installation","deviceId":"$phone","acceptedRevision":2}"""
        )) assertThrows(IllegalArgumentException::class.java) { PlaybackDevicesView.fromRawJson(view(lead)) }
    }
}
