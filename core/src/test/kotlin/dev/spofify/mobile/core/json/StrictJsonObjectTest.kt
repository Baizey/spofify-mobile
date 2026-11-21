package dev.spofify.mobile.core.json

import dev.spofify.mobile.core.content.TrackContentParser
import dev.spofify.mobile.core.content.WaveformState
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class StrictJsonObjectTest {
    private fun parse(value: String, depth: Int = 32) = StrictJsonObject.parse(value.toByteArray(), 4096, depth)

    @Test fun duplicateKeysIncludingEscapedAliasesAreRejectedAtEveryObjectDepth() {
        for (value in listOf("""{"a":1,"a":2}""", """{"a":1,"\u0061":2}""",
            """{"items":[{"a":1,"a":2}]}""", """{"x":{"a":1,"\u0061":2}}"""))
            assertTrue(runCatching { parse(value) }.isFailure)
        assertEquals(2, parse("""{"x":{"a":1},"y":{"a":2}}""").size)
        assertEquals("{,\"a\":1}", parse("""{"text":"{,\"a\":1}"}""")["text"]!!.jsonPrimitive.content)
    }

    @Test fun callersKeepTheirOwnByteAndDepthBoundsAndUtf8IsStrict() {
        fun nested(arrays: Int) = "{\"x\":" + "[".repeat(arrays) + "0" + "]".repeat(arrays) + "}"
        assertNotNull(parse(nested(15), 16))
        assertTrue(runCatching { parse(nested(16), 16) }.isFailure)
        assertNotNull(parse(nested(31), 32))
        assertTrue(runCatching { parse(nested(32), 32) }.isFailure)
        assertTrue(runCatching { StrictJsonObject.parse("{} ".toByteArray(), 2) }.isFailure)
        assertTrue(runCatching { StrictJsonObject.parse(byteArrayOf(0xc3.toByte(), 0x28), 4096) }.isFailure)
        for (invalid in listOf("[]", "null", "{", "{}{}", "{\"x\":]}", "{\"x\":\"unterminated}"))
            assertTrue(runCatching { parse(invalid) }.isFailure)
    }

    @Test fun trackDomainProjectionAndLimitsRemainUnchanged() {
        val waveform = """{"version":1,"trackId":"one","state":"unavailable","sourceGeneration":"","algorithmVersion":"peak-u8-two-pass-v1-half-up"}"""
        assertEquals(WaveformState.UNAVAILABLE, TrackContentParser.waveform(waveform.toByteArray(), "one").state)
        assertTrue(runCatching { TrackContentParser.waveform(waveform.replace("\"version\":1", "\"version\":1,\"version\":2").toByteArray(), "one") }.isFailure)
        assertTrue(runCatching { TrackContentParser.waveform(" ".repeat(16 * 1024 + 1).toByteArray(), "one") }.isFailure)
    }
}
