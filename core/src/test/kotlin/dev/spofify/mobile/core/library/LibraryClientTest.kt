package dev.spofify.mobile.core.library

import dev.spofify.mobile.core.auth.*
import dev.spofify.mobile.core.http.OkHttpAuthApi
import dev.spofify.mobile.core.origin.ServerOrigin
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class LibraryClientTest {
    private class Sessions(var value: StoredSession?) : SessionStore {
        override fun read() = value
        override fun write(value: StoredSession?) { this.value = value }
    }

    private suspend fun fixture(role: String = "user", block: suspend (MockWebServer, SessionRepository, AccountIdentity) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            val origin = ServerOrigin.parse(server.url("/").toString())
            val cookie = "spofify_session=fixture-token; Path=/; HttpOnly; Expires=Tue, 01 Jan 2030 00:00:00 GMT"
            val summary = SessionSummary("account", "fixture-user", role, "fixture-csrf")
            val client = OkHttpAuthApi.defaultClient()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val sessions = SessionRepository(Sessions(StoredSession(origin.value, cookie, summary)), object : LoginStore {
                override fun read(): RememberedLogin? = null
                override fun write(value: RememberedLogin) = Unit
            }, AuthApiFactory { OkHttpAuthApi(it, client) }, scope)
            server.enqueue(json("""{"accountId":"account","username":"fixture-user","role":"$role","csrfToken":"fixture-csrf"}"""))
            try {
                sessions.start()
                val state = withTimeout(3000) { sessions.state.first { it is AuthState.Authenticated } } as AuthState.Authenticated
                server.takeRequest(2, TimeUnit.SECONDS)
                block(server, sessions, state.identity)
            } finally {
                scope.cancel()
                scope.coroutineContext.job.join()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdownNow()
            }
        }
    }

    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body)
    private fun page(kind: String = "track", availability: String = "\"hasAudio\":true") =
        """{"items":[{"kind":"$kind","id":"one","name":"Love",$availability,"coverage":{"tracks":1,"inLibrary":1}}],"total":1,"limit":10,"offset":0,"revision":1}"""

    private val manualAlbumId = "manual-album-fixture"
    private val manualAlbumArtist = Artist("manual-artist-fixture", "Manual artist")

    private fun manualItem(role: String, kind: String, id: String, artists: List<Artist>): JsonObject =
        buildJsonObject {
            put("kind", kind)
            put("id", id)
            put("name", if (kind == "album") "Manual album" else "Manual recording")
            put("source", "manual")
            putJsonArray("artists") {
                artists.forEach { artist ->
                    add(buildJsonObject {
                        put("id", artist.id)
                        put("name", artist.name)
                    })
                }
            }
            if (kind == "track") {
                put("albumId", manualAlbumId)
                put("albumName", "Manual album")
                put("durationMs", 180_000)
                put("trackNumber", 1)
            }
            if (role == "admin") {
                if (kind == "track") put("libraryPath", "private-manual-recording.flac")
            } else {
                put("hasAudio", kind == "track")
            }
            putJsonObject("coverage") {
                put("tracks", if (kind == "album") 2 else 1)
                put("inLibrary", if (kind == "album") 2 else 1)
            }
        }

    private fun libraryPage(items: List<JsonObject>, limit: Int): String = buildJsonObject {
        putJsonArray("items") { items.forEach { add(it) } }
        put("total", items.size)
        put("limit", limit)
        put("offset", 0)
        put("revision", 1)
    }.toString()

    @Test fun manualAlbumAndTrackReadsPreserveNameOnlyCreditsForBothRoles() = runBlocking {
        for (role in listOf("user", "admin")) {
            fixture(role) { server, sessions, identity ->
                val api = LibraryClient(sessions, identity)
                val album = manualItem(role, "album", manualAlbumId, listOf(manualAlbumArtist))
                server.enqueue(json(libraryPage(listOf(album), 10)))
                assertEquals(manualAlbumId, api.search(MusicKind.ALBUM, "Manual album").items.single().id)
                server.enqueue(json(buildJsonObject {
                    put("item", album)
                    put("revision", 1)
                }.toString()))
                assertEquals(listOf(manualAlbumArtist), api.detail(MusicKind.ALBUM, manualAlbumId).artists)

                val mixedCredits = listOf(Artist("", "独立 artist"), Artist("catalog-artist", "Known artist"))
                val nameOnlyCredits = listOf(Artist("", "Manual artist"))
                val tracks = listOf(
                    manualItem(role, "track", "manual-one", mixedCredits),
                    manualItem(role, "track", "manual-two", nameOnlyCredits),
                )
                server.enqueue(json(libraryPage(tracks, 40)))
                val page = api.albumTracks(manualAlbumId)
                assertEquals(listOf("manual-one", "manual-two"), page.items.map { it.id })
                assertEquals(mixedCredits, page.items[0].artists)
                assertEquals(nameOnlyCredits, page.items[1].artists)
                assertTrue(page.items.all { it.hasAudio })
                assertFalse(page.toString().contains("private-manual-recording.flac"))
                assertFalse(page.hasMore)

                server.enqueue(json(buildJsonObject {
                    put("item", tracks.first())
                    put("revision", 1)
                }.toString()))
                assertEquals(mixedCredits, api.detail(MusicKind.TRACK, "manual-one").artists)
            }
        }
    }

    @Test fun trackSearchPreservesNameOnlyAndVerifiedArtistCreditsForBothRoles() = runBlocking {
        for (role in listOf("user", "admin")) {
            fixture(role) { server, sessions, identity ->
                val credits = listOf(Artist("known-artist", "Known artist"), Artist("", "Manual guest"))
                val track = manualItem(role, "track", "manual-one", credits)
                server.enqueue(json(libraryPage(listOf(track), 10)))
                val item = LibraryClient(sessions, identity).search(MusicKind.TRACK, "Manual recording").items.single()
                assertEquals(credits, item.artists)
                assertEquals(manualAlbumId, item.albumId)
                assertTrue(item.hasAudio)
            }
        }
    }

    @Test fun nonemptyArtistIdsRemainValidatedForBothRoles() = runBlocking {
        for (role in listOf("user", "admin")) {
            fixture(role) { server, sessions, identity ->
                val api = LibraryClient(sessions, identity)
                for (invalidId in listOf(" ", "bad\nartist", "bad\u0000artist", "a".repeat(513), "é".repeat(257))) {
                    val track = manualItem(role, "track", "manual-one", listOf(Artist(invalidId, "Artist")))
                    server.enqueue(json(libraryPage(listOf(track), 40)))
                    assertTrue(runCatching { api.albumTracks(manualAlbumId) }.exceptionOrNull() is IllegalArgumentException)
                }
                val credits = listOf(Artist("", "Manual guest"), Artist("é".repeat(256), "Known artist"))
                server.enqueue(json(libraryPage(listOf(manualItem(role, "track", "manual-one", credits)), 40)))
                assertEquals(credits, api.albumTracks(manualAlbumId).items.single().artists)
            }
        }
    }

    @Test fun nameOnlyCreditsDoNotMakeMissingOrNullArtistFieldsValid() = runBlocking {
        fixture { server, sessions, identity ->
            val api = LibraryClient(sessions, identity)
            val track = manualItem("user", "track", "manual-one", emptyList())
            val malformedCredits = listOf(
                buildJsonObject { put("name", "Artist") },
                buildJsonObject {
                    put("id", JsonNull)
                    put("name", "Artist")
                },
                buildJsonObject { put("id", "") },
            )
            for (credit in malformedCredits) {
                val malformed = JsonObject(track + ("artists" to buildJsonArray { add(credit) }))
                server.enqueue(json(libraryPage(listOf(malformed), 40)))
                assertTrue(runCatching { api.albumTracks(manualAlbumId) }.isFailure)
            }
        }
    }

    @Test fun entityAndRequestIdsRemainMandatory() = runBlocking {
        fixture { server, sessions, identity ->
            val api = LibraryClient(sessions, identity)
            for (kind in MusicKind.entries) {
                val wireKind = when (kind) {
                    MusicKind.ARTIST -> "artist"
                    MusicKind.ALBUM -> "album"
                    MusicKind.TRACK -> "track"
                }
                val item = manualItem("user", wireKind, "", listOf(Artist("", "Manual artist")))
                server.enqueue(json(libraryPage(listOf(item), 10)))
                assertTrue(runCatching { api.search(kind, "Manual") }.exceptionOrNull() is IllegalArgumentException)
            }
            val requests = server.requestCount
            assertTrue(runCatching { api.albumTracks("") }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { api.artistAlbums("") }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { api.detail(MusicKind.ALBUM, "") }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(requests, server.requestCount)
        }
    }

    @Test fun searchMatchesWorkingWebRequestAndEncodesQueryOnce() = runBlocking { fixture { server, sessions, identity ->
        server.enqueue(json(page()))
        val result = LibraryClient(sessions, identity).search(MusicKind.TRACK, "A & 日本 %2F +")
        assertEquals("one", result.items.single().id)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        val url = request.requestUrl!!
        assertEquals("/api/library", url.encodedPath)
        assertEquals("tracks", url.queryParameter("kind"))
        assertEquals("10", url.queryParameter("limit"))
        assertEquals("all", url.queryParameter("scope"))
        assertEquals("in-library", url.queryParameter("availability"))
        assertEquals("A & 日本 %2F +", url.queryParameter("q"))
        assertEquals("spofify_session=fixture-token", request.getHeader("Cookie"))
        assertEquals(identity.origin, request.getHeader("Origin"))
        assertFalse(request.path!!.contains("fixture-token"))
    } }

    @Test fun administrativeProjectionIsConsumedWithoutPublishingItsPrivatePath() = runBlocking { fixture("admin") { server, sessions, identity ->
        server.enqueue(json(page(availability = "\"libraryPath\":\"private-do-not-publish\"")))
        val item = LibraryClient(sessions, identity).search(MusicKind.TRACK, "love").items.single()
        assertTrue(item.hasAudio)
        assertFalse(item.toString().contains("private-do-not-publish"))
    } }

    @Test fun listenerMissingAvailabilityIsRejectedRatherThanInvented() = runBlocking { fixture { server, sessions, identity ->
        server.enqueue(json(page(availability = "\"libraryPath\":\"private-do-not-publish\"")))
        assertTrue(runCatching { LibraryClient(sessions, identity).search(MusicKind.TRACK, "love") }.isFailure)
    } }

    @Test fun emptyQueryAndRetiredIdentityNeverDispatch() = runBlocking { fixture { server, sessions, identity ->
        val api = LibraryClient(sessions, identity)
        for (query in listOf("", "  ", "x\n", "\uD800")) assertTrue(runCatching { api.search(MusicKind.TRACK, query) }.isFailure)
        assertTrue(runCatching { LibraryClient(sessions, identity.copy(incarnation = identity.incarnation + 1))
            .search(MusicKind.TRACK, "love") }.exceptionOrNull() is CancellationException)
        assertEquals(1, server.requestCount)
    } }

    @Test fun transientHttpStatusesAreNotAutomaticallyReplayed() = runBlocking { fixture { server, sessions, identity ->
        server.enqueue(json("{}", 503))
        val failure = runCatching { LibraryClient(sessions, identity).search(MusicKind.TRACK, "love") }.exceptionOrNull()
        assertTrue(failure is LibraryException && failure.status == 503)
        assertEquals(2, server.requestCount)
    } }

    @Test fun playbackAdmissionKeepsTicketOutOfUrlAndUsesExactSubprotocol() = runBlocking { fixture { server, sessions, identity ->
        val hello = CompletableDeferred<String>()
        server.enqueue(json("""{"ticket":"one-use-fixture-ticket"}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : okhttp3.WebSocketListener() {
            override fun onMessage(socket: okhttp3.WebSocket, text: String) {
                hello.complete(text)
                socket.send("""{"type":"snapshot"}""")
            }
        }).setHeader("Sec-WebSocket-Protocol", "spofify.library-playback.v2"))
        val deviceName = "😀".repeat(64)
        val connection = sessions.playbackTransport(identity).connect(deviceName, "fixture-device")
        try {
            val frame = kotlinx.serialization.json.Json.parseToJsonElement(withTimeout(3000) { hello.await() })
                .let { it as kotlinx.serialization.json.JsonObject }
            assertEquals(setOf("type", "ticket", "name", "deviceId", "canPlay"), frame.keys)
            assertEquals("hello", (frame.getValue("type") as kotlinx.serialization.json.JsonPrimitive).content)
            assertEquals(deviceName, (frame.getValue("name") as kotlinx.serialization.json.JsonPrimitive).content)
            assertEquals("one-use-fixture-ticket", (frame.getValue("ticket") as kotlinx.serialization.json.JsonPrimitive).content)
            assertEquals("true", (frame.getValue("canPlay") as kotlinx.serialization.json.JsonPrimitive).content)
            assertEquals("""{"type":"snapshot"}""", withTimeout(3000) { connection.messages.first() })
            val admission = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("POST", admission.method)
            assertEquals("/api/me/player/connect", admission.path)
            assertEquals("{}", admission.body.readUtf8())
            assertEquals("fixture-csrf", admission.getHeader("X-CSRF-Token"))
            val upgrade = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/api/me/player/socket", upgrade.path)
            assertNull(upgrade.requestUrl!!.query)
            assertEquals("spofify.library-playback.v2", upgrade.getHeader("Sec-WebSocket-Protocol"))
            assertEquals(identity.origin, upgrade.getHeader("Origin"))
            assertFalse(upgrade.path!!.contains("fixture-ticket"))
        } finally { connection.close() }
    } }

    @Test fun invalidDeviceNamesDoNotRequestAdmission() = runBlocking { fixture { server, sessions, identity ->
        val transport = sessions.playbackTransport(identity)
        for (name in listOf("", "  ", "a".repeat(65), "😀".repeat(65), "Phone\n", "\uD83D", "\uDE00")) {
            assertTrue(runCatching { transport.connect(name, "fixture-device") }.exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals(1, server.requestCount)
    } }

    @Test fun inPlaceRenewalDoesNotRetireAuthenticatedIdentity() = runBlocking { fixture { server, sessions, identity ->
        server.enqueue(json("""{"accountId":"account","username":"fixture-user","role":"user","csrfToken":"fixture-csrf"}"""))
        sessions.renew(identity)
        assertTrue(sessions.isCurrent(identity))
        assertEquals(identity, (sessions.state.value as AuthState.Authenticated).identity)
        assertEquals("/api/auth/session", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
    } }

    @Test fun audioRangesAreBytesAndStopReadingAfterAccountRetirement() = runBlocking { fixture { server, sessions, identity ->
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Type", "audio/mpeg")
            .setHeader("Content-Range", "bytes 2-5/10").setBody("2345"))
        sessions.audio(identity, "opaque/%", 2, 4).use { stream ->
            assertEquals(4L, stream.length)
            assertEquals("2345", stream.input.readBytes().decodeToString())
        }
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals(listOf("api", "library", "tracks", "opaque/%", "audio"), request.requestUrl!!.pathSegments)
        assertEquals("bytes=2-5", request.getHeader("Range"))
        assertEquals("identity", request.getHeader("Accept-Encoding"))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "audio/mpeg").setBody("audio"))
        val stream = sessions.audio(identity, "track", 0, null)
        try {
            server.enqueue(MockResponse().setResponseCode(204))
            sessions.signOut()
            assertTrue(runCatching { stream.input.read() }.exceptionOrNull() is java.io.IOException)
        } finally { stream.close() }
    } }

    @Test fun currentUnauthorizedResponseRetiresSession() = runBlocking { fixture { server, sessions, identity ->
        server.enqueue(json("{}", 401))
        assertTrue(runCatching { LibraryClient(sessions, identity).search(MusicKind.TRACK, "love") }.isFailure)
        assertTrue(sessions.state.value is AuthState.SignedOut)
    } }

}
