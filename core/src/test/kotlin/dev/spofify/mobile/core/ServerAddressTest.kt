package dev.spofify.mobile.core

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class ServerAddressTest {
    @Test fun canonicalOriginsPreserveExplicitScheme() {
        mapOf(
            " HTTPS://Example.COM:443/ " to "https://example.com",
            " HTTP://Example.COM:80/ " to "http://example.com",
            "https://example.com:8443" to "https://example.com:8443",
            "http://example.com:8080/" to "http://example.com:8080",
            "http://example.com:443" to "http://example.com:443",
            "https://example.com:80" to "https://example.com:80",
            "http://10.0.2.2:8080/" to "http://10.0.2.2:8080",
            "https://[2001:DB8::1]:443/" to "https://[2001:db8::1]",
            "http://[2001:DB8::1]:8080/" to "http://[2001:db8::1]:8080",
            "http://bücher.example/" to "http://xn--bcher-kva.example",
            "https://bücher.example/" to "https://xn--bcher-kva.example",
            "http://例え.テスト/" to "http://xn--r8jz45g.xn--zckzah",
            "https://例え.テスト/" to "https://xn--r8jz45g.xn--zckzah",
        ).forEach { (input, expected) ->
            assertEquals(input, expected, ServerAddress.parse(input).origin)
        }
    }

    @Test fun explicitHttpAndHttpsAdmitHostnamesAndPublicOrPrivateLiterals() {
        // .invalid is deliberately non-resolving: admission is syntactic, not a DNS check.
        val hosts = listOf(
            "localhost", "music.local", "example.com", "music.invalid", "127.0.0.1.nip.io", "123.example",
            "127.0.0.1", "10.0.2.2", "172.16.0.1", "192.168.1.1", "100.64.0.1",
            "8.8.8.8", "169.254.1.1", "0.0.0.0", "[::1]", "[fc00::1]",
            "[fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff]", "[fe80::1]", "[::]", "[2001:db8::1]",
        )
        listOf("http", "https").forEach { scheme ->
            hosts.forEach { host ->
                val origin = "$scheme://$host"
                assertEquals(origin, origin, ServerAddress.parse(origin).origin)
                assertEquals(origin, ServerAddress(origin).origin)
            }
        }
    }

    @Test fun rejectsAnythingBeyondAnExplicitUnambiguousOrigin() {
        listOf("http", "https").forEach { scheme ->
            listOf(
                "example.com/path", "example.com/..", "example.com//", "example.com/%2f",
                "example.com?", "example.com/?q=a", "example.com#", "example.com/#x",
                "u:p@example.com", "u@example.com", "@example.com", "example.com@other.invalid",
                "example.com\\x", "example.com:99999", "example.com:-1", "example.com:port",
                "[::1", "[not-an-ip]", "[fe80::1%25eth0]", "", "/example.com",
            ).forEach { authority ->
                rejects("$scheme://$authority")
            }
            listOf("\n", "\r", "\t", "\u0000", "\u007f").forEach { control ->
                rejects("$control$scheme://example.com")
                rejects("$scheme://exa${control}mple.com")
                rejects("$scheme://example.com$control")
            }
        }
        listOf("", "example.com", "example.com:8080", "//example.com", "http:example.com", "https:/example.com", "ftp://example.com", "ws://example.com").forEach(::rejects)
    }

    @Test fun rejectsAmbiguousNumericHostsAndEmptyExplicitPortsForBothSchemes() {
        listOf("http", "https").forEach { scheme ->
            listOf(
                "127.1", "2130706433", "0177.0.0.1", "127.000.0.1", "256.0.0.1", "127.0.0.1.",
                "127%2e1", "example.com:", "example.com:/", "127.0.0.1:", "[::1]:",
            ).forEach { authority -> rejects("$scheme://$authority") }
            listOf("0.0.0.0", "127.0.0.1", "192.0.2.123", "255.255.255.255").forEach { host ->
                assertEquals("$scheme://$host", ServerAddress.parse("$scheme://$host").origin)
                assertEquals("$scheme://$host:8080", ServerAddress.parse("$scheme://$host:8080").origin)
            }
        }
    }

    @Test fun containmentUsesCanonicalSchemeHostAndEffectivePort() {
        listOf("http", "https").forEach { scheme ->
            val address = ServerAddress.parse("$scheme://Music.Invalid")
            val defaultPort = if (scheme == "http") 80 else 443
            val otherScheme = if (scheme == "http") "https" else "http"
            assertTrue(address.contains("$scheme://MUSIC.INVALID:$defaultPort/api/artwork".toHttpUrl()))
            assertTrue(address.contains(address.url("api", "tracks", "id", "audio")))
            assertFalse(address.contains("$otherScheme://music.invalid:$defaultPort/api".toHttpUrl()))
            assertFalse(address.contains("$scheme://other.invalid:$defaultPort/api".toHttpUrl()))
            assertFalse(address.contains("$scheme://music.invalid:8080/api".toHttpUrl()))
            assertFalse(address.contains("$scheme://music.invalid.attacker.invalid/api".toHttpUrl()))
            assertFalse(address.contains("$scheme://user:password@music.invalid/api".toHttpUrl()))

            val nonDefaultPort = ServerAddress.parse("$scheme://music.invalid:8080")
            assertTrue(nonDefaultPort.contains("$scheme://music.invalid:8080/api".toHttpUrl()))
            assertFalse(nonDefaultPort.contains(address.url("api")))
        }
    }

    @Test fun opaqueSegmentsAreEncodedExactlyOnce() {
        val address = ServerAddress.parse("https://example.com")
        assertEquals("/api/library/tracks/a%2Fb%252Fc%20%3F%23/audio", address.url("api", "library", "tracks", "a/b%2Fc ?#", "audio").encodedPath)
        assertEquals("/tracks/%252e%252e", address.url("tracks", "%2e%2e").encodedPath)
        listOf(".", "..", "").forEach { segment ->
            try { address.url("tracks", segment); fail("Accepted a dot or empty ID") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun directConstructorCannotBypassCanonicalValidation() {
        listOf("http", "https").forEach { scheme ->
            val defaultPort = if (scheme == "http") 80 else 443
            listOf(
                "$scheme://example.com/", "$scheme://EXAMPLE.COM", "$scheme://example.com:$defaultPort",
                " $scheme://example.com", "$scheme://example.com ", "$scheme://[2001:DB8::1]",
            ).forEach(::rejectsDirect)
            val address = ServerAddress("$scheme://example.com")
            try {
                address.copy(origin = "$scheme://example.com/path")
                fail("Copy accepted a non-origin")
            } catch (_: IllegalArgumentException) { }
        }
    }

    private fun rejects(input: String) {
        try { ServerAddress.parse(input); fail("Accepted $input") } catch (_: IllegalArgumentException) { }
        rejectsDirect(input)
    }

    private fun rejectsDirect(input: String) {
        try { ServerAddress(input); fail("Direct constructor accepted $input") } catch (_: IllegalArgumentException) { }
    }
}
