package dev.spofify.mobile.core

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CatalogApiTest {
    private lateinit var web: MockWebServer
    private lateinit var client: SpofifyClient
    private lateinit var catalog: CatalogApi
    private val page = """{"items":[],"total":0,"limit":40,"offset":0,"revision":4294967296}"""
    private val track = """{"kind":"track","id":"track/a%","name":"Song","hasAudio":true,"durationMs":1200,"artists":[{"id":"artist/a%","name":"Artist"}],"future":"ignored"}"""

    @Before fun setup() {
        web = MockWebServer()
        web.start()
        client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), MemorySessionStore())
        catalog = CatalogApi(client)
    }
    @After fun teardown() { web.shutdown() }

    @Test fun browseUsesExplicitListenerFiltersAndRetainsOpaqueRevision() = runBlocking {
        web.enqueue(jsonResponse(page))
        val result = catalog.browse()
        assertEquals(4294967296L, result.revision)
        val defaults = web.takeRequest().requestUrl!!
        assertEquals(setOf("kind", "scope", "availability", "limit", "offset"), defaults.queryParameterNames)
        assertEquals("albums", defaults.queryParameter("kind"))
        assertEquals("all", defaults.queryParameter("scope"))
        assertEquals("in-library", defaults.queryParameter("availability"))
        assertEquals("40", defaults.queryParameter("limit"))
        assertEquals("0", defaults.queryParameter("offset"))
        for (availability in listOf("all", "in-library", "complete", "partial", "metadata")) {
            web.enqueue(jsonResponse(page))
            catalog.browse("tracks", "Artist & Album/50% + ☃", availability, 100, 1_000_000)
            val url = web.takeRequest().requestUrl!!
            assertEquals("tracks", url.queryParameter("kind"))
            assertEquals("Artist & Album/50% + ☃", url.queryParameter("q"))
            assertEquals(availability, url.queryParameter("availability"))
            assertEquals("100", url.queryParameter("limit"))
            assertEquals("1000000", url.queryParameter("offset"))
        }
    }

    @Test fun childListsDiscardOuterSearchAndAvailabilityAndSelectTheirOwnKind() = runBlocking {
        web.enqueue(jsonResponse(page))
        catalog.browse(kind = "artists", query = "outer search", availability = "metadata", artistId = "artist/a%")
        val artist = web.takeRequest().requestUrl!!
        assertEquals("albums", artist.queryParameter("kind"))
        assertEquals("all", artist.queryParameter("availability"))
        assertEquals("artist/a%", artist.queryParameter("artistId"))
        assertNull(artist.queryParameter("albumId"))
        assertNull(artist.queryParameter("q"))
        assertNull(artist.queryParameter("releaseGroup"))
        web.enqueue(jsonResponse(page))
        catalog.browse(kind = "albums", query = "outer", availability = "complete", albumId = "album/a%", offset = 40)
        val album = web.takeRequest().requestUrl!!
        assertEquals("tracks", album.queryParameter("kind"))
        assertEquals("all", album.queryParameter("availability"))
        assertEquals("album/a%", album.queryParameter("albumId"))
        assertEquals("40", album.queryParameter("offset"))
        assertNull(album.queryParameter("artistId"))
        assertNull(album.queryParameter("q"))
    }

    @Test fun artistReleaseGroupsAreExplicitAndRemainIndependentOfSearch() = runBlocking {
        for (group in listOf("albums", "singles", "appearances", "all")) {
            web.enqueue(jsonResponse(page))
            catalog.browse(query = "not inherited", availability = "in-library", artistId = "artist", releaseGroup = group)
            val url = web.takeRequest().requestUrl!!
            assertEquals("albums", url.queryParameter("kind"))
            assertEquals("artist", url.queryParameter("artistId"))
            assertEquals(group, url.queryParameter("releaseGroup"))
            assertEquals("all", url.queryParameter("availability"))
            assertNull(url.queryParameter("q"))
        }
    }

    @Test fun singularDetailUsesOneEncodedIdSegmentAndNoQueryOrPrivateEndpoint() = runBlocking {
        for ((kind, plural) in listOf("artist" to "artists", "album" to "albums", "track" to "tracks")) {
            val item = track.replace("\"kind\":\"track\"", "\"kind\":\"$kind\"")
            web.enqueue(jsonResponse("""{"item":$item,"revision":9223372036854775807}"""))
            assertEquals(kind, catalog.detail(kind, "id/a%? &").kind)
            val request = web.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/library/$plural/id%2Fa%25%3F%20&", request.requestUrl!!.encodedPath)
            assertNull(request.requestUrl!!.query)
        }
        web.enqueue(jsonResponse("""{"error":"/private/missing"}""", 404))
        val missing = apiFailure { catalog.detail("track", "missing") }
        assertEquals(404, missing.status)
        assertEquals("This item is no longer available", missing.message)
    }

    @Test fun revisionIsLongAndAcceptsNoListOrDetailFilters() = runBlocking {
        web.enqueue(jsonResponse("""{"revision":9223372036854775807,"future":true}"""))
        assertEquals(Long.MAX_VALUE, catalog.revision())
        assertEquals("/api/library/revision", web.takeRequest().path)
    }

    @Test fun groupedQueryUsesIndependentTenRowPagesWithTheirOwnTotalsAndOffsets() = runBlocking {
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.requestUrl!!.queryParameter("kind")) {
                "artists" -> jsonResponse("""{"items":[],"total":31,"limit":10,"offset":10,"revision":11}""")
                "albums" -> jsonResponse("""{"items":[],"total":67,"limit":10,"offset":20,"revision":12}""")
                "tracks" -> jsonResponse("""{"items":[$track],"total":93,"limit":10,"offset":30,"revision":13}""")
                else -> jsonResponse("{}", 400)
            }
        }
        val result = catalog.groupedSearch("Artist & song% +", "metadata", 10, 20, 30)
        assertEquals(31, result.artists.total)
        assertEquals(67, result.albums.total)
        assertEquals(93, result.tracks.total)
        assertEquals(10, result.artists.offset)
        assertEquals(20, result.albums.offset)
        assertEquals(30, result.tracks.offset)
        assertEquals("Song", result.tracks.items.single().name)
        val requests = (1..3).map { web.takeRequest(5, TimeUnit.SECONDS)!!.requestUrl!! }
        assertEquals(setOf("artists", "albums", "tracks"), requests.map { it.queryParameter("kind") }.toSet())
        requests.forEach {
            assertEquals("10", it.queryParameter("limit"))
            assertEquals("all", it.queryParameter("scope"))
            assertEquals("metadata", it.queryParameter("availability"))
            assertEquals("Artist & song% +", it.queryParameter("q"))
            assertNull(it.queryParameter("artistId"))
            assertNull(it.queryParameter("albumId"))
        }
        assertEquals(3, web.requestCount)
    }

    @Test fun invalidFiltersAndIdentifiersNeverIssueAnHttpRequest() = runBlocking {
        val invalid: List<suspend () -> Any> = listOf(
            { catalog.browse(kind = "private-kind") },
            { catalog.browse(availability = "private-filter") },
            { catalog.browse(limit = 0) },
            { catalog.browse(limit = 101) },
            { catalog.browse(offset = -1) },
            { catalog.browse(offset = 1_000_001) },
            { catalog.browse(artistId = "artist", albumId = "album") },
            { catalog.browse(releaseGroup = "albums") },
            { catalog.browse(artistId = "artist", releaseGroup = "unknown") },
            { catalog.browse(albumId = "album", releaseGroup = "all") },
            { catalog.browse(artistId = " bad") },
            { catalog.browse(albumId = "bad\nvalue") },
            { catalog.browse(albumId = "a".repeat(513)) },
            { catalog.browse(query = "a".repeat(201)) },
            { catalog.browse(query = "bad\u0000value") },
            { catalog.browse(query = "\uD800") },
            { catalog.detail("albums", "id") },
            { catalog.detail("track", "..") },
            { catalog.detail("track", "bad ") },
            { catalog.groupedSearch("") },
            { catalog.groupedSearch("   ") },
            { catalog.groupedSearch("song", trackOffset = -1) },
        )
        for (operation in invalid) {
            try { operation(); fail("Invalid catalog input accepted") }
            catch (error: IllegalArgumentException) { assertFalse(error.message.orEmpty().contains("private-")) }
        }
        assertEquals(0, web.requestCount)
    }

    @Test fun unicodeQueryLimitCountsCodePointsRatherThanUtf16Units() = runBlocking {
        web.enqueue(jsonResponse(page))
        val query = "\uD83C\uDFB5".repeat(200)
        catalog.browse(query = query)
        assertEquals(query, web.takeRequest().requestUrl!!.queryParameter("q"))
    }

    @Test fun incompatibleTypedResponsesBecomeStaticSafeErrors() = runBlocking {
        for ((body, operation) in listOf<Pair<String, suspend () -> Any>>(
            """{"items":"/private/path","total":0}""" to { catalog.browse() },
            """{"item":{"kind":"track","id":"t","name":{}},"revision":1}""" to { catalog.detail("track", "t") },
            """{"revision":"/private/path"}""" to { catalog.revision() },
        )) {
            web.enqueue(jsonResponse(body))
            val error = apiFailure { operation() }
            assertEquals("The server returned an incompatible JSON response", error.message)
            assertNull(error.cause)
        }
    }

    @Test fun queueContextAndRevisionAreOptionalAndLegacyAvailabilityRemainsCompatible() = runBlocking {
        web.enqueue(jsonResponse("""{"context":{"kind":"album","id":"a","name":"Album"},"items":[$track],"total":1,"limit":1,"truncated":false,"revision":4294967296}"""))
        val queue = client.playback(LibraryItem("album", "a", "Album"))
        assertEquals(LibraryPlaybackContext("album", "a", "Album"), queue.context)
        assertEquals(4294967296L, queue.revision)
        assertTrue(queue.items.single().playable)
        assertNull(queue.items.single().libraryPath)
        assertTrue(LibraryItem("track", "legacy", "Legacy", libraryPath = "/private/legacy.flac").playable)
    }
}
