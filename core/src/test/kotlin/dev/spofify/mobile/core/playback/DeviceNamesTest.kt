package dev.spofify.mobile.core.playback

import org.junit.Assert.*
import org.junit.Test

class DeviceNamesTest {
    @Test fun acceptsAsciiNonLatinAndSupplementaryNamesAtTheCharacterLimit() {
        listOf("Phone", "a".repeat(64), "音".repeat(64), "😀".repeat(64)).forEach {
            assertTrue(isValidDeviceName(it))
        }
        assertEquals(256, "😀".repeat(64).toByteArray(Charsets.UTF_8).size)
        assertTrue(isValidDeviceName(" \u2003" + "a".repeat(64) + "\u2003 "))
        assertTrue(isValidDeviceName("\uFEFF" + "a".repeat(64) + "\uFEFF"))
    }

    @Test fun originalBytesAndTrimmedCodePointsHaveIndependentBounds() {
        assertTrue(isValidDeviceName(" ".repeat(192) + "a".repeat(64)))
        listOf("a".repeat(65), "音".repeat(65), "😀".repeat(65), " ".repeat(256) + "a").forEach {
            assertFalse(isValidDeviceName(it))
        }
    }

    @Test fun rejectsBlankControlAndMalformedUnicodeBeforeTrimming() {
        listOf("", " \u2003 ", "\uFEFF", "name\n", "na\u0000me", "name\u0085", "\uD83D", "\uDE00", "\uDE00\uD83D", "\uD83Da").forEach {
            assertFalse(isValidDeviceName(it))
        }
    }
}
