package dev.spofify.mobile.core

import org.junit.Assert.*
import org.junit.Test

class ServerAddressTest {
    @Test fun canonicalOriginsAndHttpsHostnames() {
        assertEquals("https://example.com", ServerAddress.parse(" HTTPS://Example.COM:443/ ").origin)
        assertEquals("https://example.com:8443", ServerAddress.parse("https://example.com:8443").origin)
        assertEquals("http://10.0.2.2:8080", ServerAddress.parse("http://10.0.2.2:8080/").origin)
        assertEquals("https://[2001:db8::1]", ServerAddress.parse("https://[2001:db8::1]/").origin)
    }

    @Test fun onlyConcretePrivateHttpAddresses() {
        listOf("127.0.0.1", "127.255.255.254", "10.0.0.0", "10.255.255.255", "172.16.0.0", "172.31.255.255", "192.168.0.0", "192.168.255.255", "100.64.0.0", "100.127.255.255", "[::1]", "[fc00::]", "[fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff]").forEach {
            assertTrue(ServerAddress.parse("http://$it").origin.startsWith("http:"))
        }
        listOf("localhost", "music.local", "example.com", "127.0.0.1.nip.io", "8.8.8.8", "126.255.255.255", "128.0.0.0", "9.255.255.255", "11.0.0.0", "172.15.255.255", "172.32.0.0", "192.167.255.255", "192.169.0.0", "100.63.255.255", "100.128.0.0", "169.254.1.1", "0.0.0.0", "127.1", "2130706433", "0177.0.0.1", "[::]", "[fbff::1]", "[fe00::1]", "[fe80::1]", "[2001:db8::1]", "[::ffff:8.8.8.8]", "[fe80::1%25eth0]").forEach {
            rejects("http://$it")
        }
    }

    @Test fun rejectsAnythingBeyondOrigin() {
        listOf("https://example.com/path", "https://example.com/..", "https://example.com//", "https://example.com/%2f", "https://example.com?", "https://example.com/?q=a", "https://example.com#", "https://example.com/#x", "https://u:p@example.com", "https://@example.com", "https://example.com\\x", "ftp://example.com", "example.com", "https://example.com:99999", "https://exa\nmple.com").forEach(::rejects)
    }

    @Test fun opaqueSegmentsAreEncodedExactlyOnce() {
        val address = ServerAddress.parse("https://example.com")
        assertEquals("/api/library/tracks/a%2Fb%252Fc%20%3F%23/audio", address.url("api", "library", "tracks", "a/b%2Fc ?#", "audio").encodedPath)
        assertEquals("/tracks/%252e%252e", address.url("tracks", "%2e%2e").encodedPath)
        listOf(".", "..", "").forEach { segment ->
            try { address.url("tracks", segment); fail("Accepted a dot or empty ID") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun directConstructorCannotBypassPolicy() {
        try { ServerAddress("http://example.com"); fail("Bypassed HTTP policy") } catch (_: IllegalArgumentException) { }
        try { ServerAddress("https://example.com/"); fail("Accepted a noncanonical origin") } catch (_: IllegalArgumentException) { }
    }

    private fun rejects(input: String) {
        try { ServerAddress.parse(input); fail("Accepted $input") } catch (_: IllegalArgumentException) { }
    }
}
