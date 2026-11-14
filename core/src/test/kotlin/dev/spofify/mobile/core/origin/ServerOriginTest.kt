package dev.spofify.mobile.core.origin

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class ServerOriginTest {
    @Test fun canonicalSchemeHostAndEffectivePort() {
        assertEquals("https://example.test", ServerOrigin.parse("HTTPS://EXAMPLE.TEST:443/").value)
        assertEquals(ServerOrigin.parse("http://example.test:80"), ServerOrigin.parse("http://EXAMPLE.test/"))
        assertEquals("http://[::1]:6030", ServerOrigin.parse("http://[::1]:6030/").value)
        assertEquals("http://public.example", ServerOrigin.parse("http://public.example").value)
    }

    @Test fun rejectsAmbiguousAndNonOriginInputs() {
        val rejected = listOf(
            "", "example.test", "//example.test", "ftp://example.test", "https://u:p@example.test",
            "http://@example.test", "http://example.test:", "http://example.test/a", "http://example.test/.",
            "http://example.test/%2f", "http://example.test?", "http://example.test#",
            "http://example.test\\evil", " http://example.test", "http://example.test\n",
            "http://%65xample.test", "http://[fe80::1%25eth0]", "http://example.test:99999",
        )
        rejected.forEach { raw ->
            assertThrows(raw, IllegalArgumentException::class.java) { ServerOrigin.parse(raw) }
        }
    }

    @Test fun containsIsNotHostSuffixOrPortGuessing() {
        val origin = ServerOrigin.parse("https://example.test")
        assertTrue(origin.contains("https://EXAMPLE.test:443/api/auth/session".toHttpUrl()))
        assertFalse(origin.contains("http://example.test".toHttpUrl()))
        assertFalse(origin.contains("https://example.test:444".toHttpUrl()))
        assertFalse(origin.contains("https://example.test.evil".toHttpUrl()))
    }
}
