package dev.spofify.mobile.core.library

import dev.spofify.mobile.core.auth.*
import dev.spofify.mobile.core.http.OkHttpAuthApi
import dev.spofify.mobile.core.origin.ServerOrigin
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
                val grant = withTimeout(3000) { sessions.onlineGrant.first { it != null } }!!
                server.takeRequest(2, TimeUnit.SECONDS)
                block(server, sessions, grant.identity)
            } finally {
                scope.cancel()
                scope.coroutineContext.job.join()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdownNow()
            }
        }
    }

    @Test fun detailReadsPreserveRetryAfterAndDoNotReplayStatusFailuresAtTransport() = runBlocking {
        fixture { server, sessions, identity ->
            val library = LibraryClient(sessions, identity)
            val cases = listOf(Triple(503, null, true), Triple(503, "0", false), Triple(502, "10", false),
                Triple(429, null, false), Triple(404, null, false))
            for ((index, case) in cases.withIndex()) {
                val (status, retryAfter, transient) = case
                server.enqueue(json("{}", status).apply { retryAfter?.let { setHeader("Retry-After", it) } })
                val error = runCatching { withTimeout(2_000) { library.detail(MusicKind.ALBUM, "one") } }.exceptionOrNull()
                assertTrue(error is LibraryException)
                error as LibraryException
                assertEquals(status, error.status)
                assertEquals(transient, error.transient)
                assertEquals(index + 2, server.requestCount)
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

    private fun libraryPage(items: List<JsonObject>, limit: Int, total: Int = items.size, offset: Int = 0): String = buildJsonObject {
        putJsonArray("items") { items.forEach { add(it) } }
        put("total", total)
        put("limit", limit)
        put("offset", offset)
        put("revision", 1)
    }.toString()

    private fun releaseItem(role: String, id: String, albumType: String? = null, relation: String? = null): JsonObject =
        JsonObject(manualItem(role, "album", id, listOf(manualAlbumArtist)) + buildJsonObject {
            albumType?.let { put("albumType", it) }
            relation?.let { put("relation", it) }
        })

    @Test fun artistAlbumPagesRetainReleaseTypesAndRelationsAcrossContinuationForBothRoles() = runBlocking {
        for (role in listOf("user", "admin")) fixture(role) { server, sessions, identity ->
            val artistId = "artist/%日本"
            val metadata = (0 until 40).map { index ->
                when (index % 4) {
                    0 -> "album" to "release"
                    1 -> "single" to "release"
                    2 -> "ep" to "release"
                    else -> "compilation" to "compilation"
                }
            }
            val firstRows = metadata.mapIndexed { index, values -> releaseItem(role, "release-$index", values.first, values.second) }
            val tail = listOf(
                releaseItem(role, "guest", "album", "appearance"),
                releaseItem(role, "future", "future-release-type", "future-credit-relation"),
                releaseItem(role, "missing"),
                releaseItem(role, "blank", "", ""),
            )
            server.enqueue(json(libraryPage(firstRows, 40, total = 44)))
            server.enqueue(json(libraryPage(tail, 40, total = 44, offset = 40)))
            val api = LibraryClient(sessions, identity)
            val first = api.artistAlbums(artistId)
            val next = api.artistAlbums(artistId, offset = 40)
            assertEquals(metadata, first.items.map { it.albumType to it.relation })
            assertEquals(listOf("album" to "appearance", "future-release-type" to "future-credit-relation",
                "" to "", "" to ""), next.items.map { it.albumType to it.relation })
            assertEquals((0 until 40).map { "release-$it" }, first.items.map { it.id })
            assertEquals(listOf("guest", "future", "missing", "blank"), next.items.map { it.id })
            assertEquals(44L, first.total)
            assertEquals(44L, next.total)
            assertTrue(first.hasMore)
            assertFalse(next.hasMore)
            assertTrue((first.items + next.items).all { !it.hasAudio })
            for (offset in listOf(0, 40)) {
                val request = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("GET", request.method)
                assertEquals("/api/library", request.requestUrl!!.encodedPath)
                assertEquals(setOf("kind", "artistId", "releaseGroup", "scope", "availability", "offset", "limit"),
                    request.requestUrl!!.queryParameterNames)
                for ((key, value) in listOf("kind" to "albums", "artistId" to artistId, "releaseGroup" to "all",
                        "scope" to "all", "availability" to "in-library", "limit" to "40", "offset" to "$offset"))
                    assertEquals(value, request.requestUrl!!.queryParameter(key))
                assertEquals("spofify_session=fixture-token", request.getHeader("Cookie"))
                assertEquals(identity.origin, request.getHeader("Origin"))
            }
        }
    }

    @Test fun albumDetailDefaultsMissingReleaseMetadataAndPreservesUnknownFutureStringsForBothRoles() = runBlocking {
        for (role in listOf("user", "admin")) fixture(role) { server, sessions, identity ->
            val api = LibraryClient(sessions, identity)
            val metadata = listOf(null to null, " Future / 日本 " to " future-relation-v2 ")
            for ((albumType, relation) in metadata) {
                server.enqueue(json(buildJsonObject {
                    put("item", releaseItem(role, "release", albumType, relation))
                    put("revision", 1)
                }.toString()))
                val item = api.detail(MusicKind.ALBUM, "release")
                assertEquals(albumType.orEmpty(), item.albumType)
                assertEquals(relation.orEmpty(), item.relation)
                assertEquals(listOf(manualAlbumArtist), item.artists)
            }
        }
    }

    @Test fun presentMalformedReleaseMetadataRejectsTheWholeAuthenticatedPageForBothRoles() = runBlocking {
        for (role in listOf("user", "admin")) fixture(role) { server, sessions, identity ->
            val api = LibraryClient(sessions, identity)
            val valid = releaseItem(role, "valid", "single", "release")
            val invalidValues = listOf(JsonNull, JsonPrimitive(true), JsonPrimitive(7), buildJsonArray {}, buildJsonObject {})
            for (field in listOf("albumType", "relation")) for (value in invalidValues) {
                val invalid = JsonObject(releaseItem(role, "invalid", "album", "appearance") + (field to value))
                server.enqueue(json(libraryPage(listOf(valid, invalid), 40)))
                assertTrue("Malformed $field must not produce a partial page", runCatching { api.artistAlbums("artist") }.isFailure)
            }
        }
    }

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
        }).setHeader("Sec-WebSocket-Protocol", "spofify.account-state.v3"))
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
            assertEquals("spofify.account-state.v3", upgrade.getHeader("Sec-WebSocket-Protocol"))
            assertEquals(identity.origin, upgrade.getHeader("Origin"))
            assertFalse(upgrade.path!!.contains("fixture-ticket"))
        } finally { connection.close() }
    } }

    @Test fun largeLocalPlayerIsSentWholeButNormalSendRemainsBounded() = runBlocking { fixture { server, sessions, identity ->
        val hello = CompletableDeferred<JsonObject>()
        val ticket = "ab".repeat(32)
        server.enqueue(json("""{"ticket":"$ticket"}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : okhttp3.WebSocketListener() {
            override fun onMessage(socket: okhttp3.WebSocket, text: String) {
                hello.complete(kotlinx.serialization.json.Json.parseToJsonElement(text) as JsonObject)
            }
        }).setHeader("Sec-WebSocket-Protocol", "spofify.account-state.v3"))
        val player = buildJsonObject {
            put("repeat", "one"); put("shuffle", false)
            putJsonObject("cursor") {
                put("key", 1); put("occurrence", "visit"); put("positionMs", 123); put("desiredPlaying", true)
            }
            putJsonArray("queue") {
                repeat(5500) { row ->
                    add(buildJsonObject {
                        put("id", "track-$row"); put("name", "Fixture track $row ${"😀".repeat(20)}")
                        putJsonArray("artists") { add(buildJsonObject { put("id", "artist"); put("name", "Fixture artist") }) }
                        put("albumName", "Fixture album"); put("albumId", "album"); put("duration", 120.0)
                        put("key", row + 1); put("order", row + 1); put("intent", "low")
                    })
                }
            }
        }
        assertTrue(player.toString().toByteArray().size in (1024 * 1024)..(2 * 1024 * 1024))
        val basis = buildJsonObject {
            put("sessionId", "session"); put("generation", 4); put("revision", 7); put("occurrence", "visit")
        }
        sessions.playbackTransport(identity).connect("phone", "device", player, basis).use { connection ->
            val frame = withTimeout(3000) { hello.await() }
            assertEquals(JsonPrimitive(ticket), frame["ticket"])
            assertEquals(player, frame["local"])
            assertEquals(basis, frame["reattachBasis"])
            assertFalse(connection.send("😀".repeat(16385)))
            assertTrue(connection.send("😀".repeat(16384)))
            assertTrue(connection.isOpen)
        }
    } }

    @Test fun socketRejectsLegacySubprotocolBeforeSendingHello() = runBlocking { fixture { server, sessions, identity ->
        server.enqueue(json("""{"ticket":"fixture"}"""))
        val hello = CompletableDeferred<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : okhttp3.WebSocketListener() {
            override fun onMessage(socket: okhttp3.WebSocket, text: String) { hello.complete(text) }
        }).setHeader("Sec-WebSocket-Protocol", "spofify.account-state.v1"))
        assertTrue(runCatching { sessions.playbackTransport(identity).connect("phone", "device") }
            .exceptionOrNull() is AuthFailure)
        assertNull(withTimeoutOrNull(100) { hello.await() })
        assertTrue(sessions.isCurrent(identity))
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
