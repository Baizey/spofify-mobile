package dev.spofify.mobile.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SpofifyClientLibraryTest {
    private lateinit var web: MockWebServer
    private lateinit var client: SpofifyClient
    private val track = """{"kind":"track","id":"t1","name":"Song","artists":[{"id":"a1","name":"Artist"}],"hasAudio":true,"durationMs":1234,"coverage":{"tracks":0,"inLibrary":0,"albums":0},"future":"ignored"}"""
    private val emptyPage = """{"items":[],"total":0,"limit":40,"offset":0,"revision":9223372036854775807}"""
    @Before fun setup() {
        web = MockWebServer()
        web.start()
        client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), MemorySessionStore())
    }
    @After fun teardown() { web.shutdown() }

    @Test fun adminAndListenerBrowseTheSameInLibraryScope() = runBlocking {
        for (role in listOf("admin", "user")) {
            web.enqueue(loginResponse(role))
            client.login("listener", "password")
            web.takeRequest()
            web.enqueue(jsonResponse(emptyPage))
            client.library(query = "artist & album", offset = 40)
            val request = web.takeRequest()
            val url = request.requestUrl!!
            assertEquals("/api/library", url.encodedPath)
            assertEquals("albums", url.queryParameter("kind"))
            assertEquals("all", url.queryParameter("scope"))
            assertEquals("in-library", url.queryParameter("availability"))
            assertEquals("40", url.queryParameter("limit"))
            assertEquals("40", url.queryParameter("offset"))
            assertEquals("artist & album", url.queryParameter("q"))
        }
    }

    @Test fun openingChildrenClearsOuterSearchAndAvailabilityFilters() = runBlocking {
        web.enqueue(jsonResponse(emptyPage))
        client.library(kind = "artists", query = "outer query", artistId = "artist/with%id")
        val artist = web.takeRequest().requestUrl!!
        assertEquals("albums", artist.queryParameter("kind"))
        assertEquals("all", artist.queryParameter("availability"))
        assertEquals("artist/with%id", artist.queryParameter("artistId"))
        assertNull(artist.queryParameter("q"))
        web.enqueue(jsonResponse(emptyPage))
        client.library(query = "outer", artistId = "outer-artist", albumId = "album/1%")
        val album = web.takeRequest().requestUrl!!
        assertEquals("tracks", album.queryParameter("kind"))
        assertEquals("all", album.queryParameter("availability"))
        assertEquals("album/1%", album.queryParameter("albumId"))
        assertNull(album.queryParameter("artistId"))
        assertNull(album.queryParameter("q"))
    }

    @Test fun manualPlaylistPagingAndEntriesFlattening() = runBlocking {
        web.enqueue(jsonResponse("""{"items":[{"id":"p1","name":"Playlist","description":"Description","kind":"manual","trackCount":8,"availableCount":3,"revision":4294967296,"capabilities":{}}],"total":81,"limit":40,"offset":40}"""))
        val playlists = client.playlists(40)
        assertEquals(81, playlists.total)
        assertEquals(40, playlists.offset)
        assertEquals(4294967296L, playlists.items.single().revision)
        val request = web.takeRequest().requestUrl!!
        assertEquals("manual", request.queryParameter("kind"))
        assertEquals("40", request.queryParameter("limit"))
        assertEquals("40", request.queryParameter("offset"))
        web.enqueue(jsonResponse("""{"items":[{"id":"entry1","position":40,"track":$track}],"total":80,"limit":40,"offset":40}"""))
        val entries = client.playlistEntries("p/a%", 40)
        assertEquals("t1", entries.items.single().id)
        assertEquals("entry1", entries.items.single().entryId)
        assertEquals(80, entries.total)
        assertEquals(40, entries.offset)
        assertEquals("/api/me/playlists/p%2Fa%25/entries?limit=40&offset=40", web.takeRequest().path)
    }

    @Test fun likesAndRecentFlattenWrappedTracks() = runBlocking {
        web.enqueue(jsonResponse("""{"items":[{"track":$track,"likedAt":"2026-01-01T00:00:00Z"}],"total":81,"availableTotal":1,"limit":40,"offset":80}"""))
        val likes = client.likes(80)
        assertEquals("Song", likes.items.single().name)
        assertTrue(likes.items.single().playable)
        assertEquals("Artist", likes.items.single().subtitle)
        assertEquals(81, likes.total)
        assertEquals(80, likes.offset)
        assertEquals("/api/me/likes?limit=40&offset=80", web.takeRequest().path)
        web.enqueue(jsonResponse("""{"items":[{"id":"history-id","playedAt":"2026-01-01T00:00:00Z","track":$track}],"total":1,"limit":40,"offset":0}"""))
        assertEquals("t1", client.recent().items.single().id)
        assertEquals("/api/me/recent?limit=40", web.takeRequest().path)
    }

    @Test fun playbackEndpointsAndStandaloneTrackBehavior() = runBlocking {
        val queue = """{"context":{"kind":"album","id":"a"},"items":[$track],"total":1,"limit":1000,"truncated":false,"revision":1}"""
        web.enqueue(jsonResponse(queue))
        val album = LibraryItem("album", "album/a%", "Album")
        val prepared = client.playback(album)
        assertEquals("t1", prepared.items.single().id)
        assertFalse(prepared.truncated)
        assertEquals(1, prepared.total)
        assertEquals("/api/library/albums/album%2Fa%25/playback", web.takeRequest().path)
        web.enqueue(jsonResponse(queue))
        client.playback(LibraryItem("track", "t1", "Song", albumId = "parent/album"))
        assertEquals("/api/library/albums/parent%2Falbum/playback", web.takeRequest().path)
        web.enqueue(jsonResponse(queue))
        client.playback(LibraryItem("artist", "artist/id", "Artist"))
        assertEquals("/api/library/artists/artist%2Fid/playback", web.takeRequest().path)
        val standalone = LibraryItem("track", "standalone", "Song", hasAudio = true)
        assertEquals(listOf(standalone), client.playback(standalone).items)
        assertTrue(client.playback(standalone.copy(hasAudio = false)).items.isEmpty())
        assertEquals(3, web.requestCount)
    }

    @Test fun personalPlaybackUsesAlreadyFlattenedGoItems() = runBlocking {
        val body = """{"items":[${track.dropLast(1)},"entryId":"e1"}],"total":1,"limit":1,"truncated":false}"""
        web.enqueue(jsonResponse(body))
        val queueItem = client.playlistPlayback("playlist/1").items.single()
        assertEquals("t1", queueItem.id)
        assertEquals("e1", queueItem.entryId)
        assertEquals("/api/me/playlists/playlist%2F1/playback", web.takeRequest().path)
        web.enqueue(jsonResponse(body))
        assertEquals("t1", client.likedPlayback().items.single().id)
        assertEquals("/api/me/likes/playback", web.takeRequest().path)
    }

    @Test fun likeStatusIsJsonPostNotTokenizedUrlAndHistoryUsesEventId() = runBlocking {
        web.enqueue(jsonResponse("""{"items":[{"trackId":"track/id%","liked":true}]}"""))
        assertTrue(client.likeStatus("track/id%"))
        val status = web.takeRequest()
        assertEquals("POST", status.method)
        assertEquals("/api/me/likes/status", status.path)
        assertEquals("""{"trackIds":["track/id%"]}""", status.body.readUtf8())
        web.enqueue(jsonResponse("", 204))
        client.recordHistory("event-unique", "track/id%")
        val history = web.takeRequest()
        assertEquals("POST", history.method)
        assertEquals("/api/me/history", history.path)
        assertEquals("""{"eventId":"event-unique","trackId":"track/id%"}""", history.body.readUtf8())
    }

    @Test fun mediaUrlsUseOnlySafeRoutesNeverFilesystemPaths() {
        val item = LibraryItem("track", "id/with%percent", "Song", libraryPath = "/secret/music/file.flac")
        assertEquals("${client.server.origin}/api/library/tracks/id%2Fwith%25percent/audio", client.audioUrl(item))
        assertEquals("${client.server.origin}/api/library/tracks/id%2Fwith%25percent/artwork", client.artworkUrl(item))
        assertFalse(client.audioUrl(item).contains("secret"))
    }

    @Test fun playableUsesHasAudioWithLegacyFallbackAndContainerCoverage() {
        val track = LibraryItem("track", "id", "Song", libraryPath = "legacy.flac")
        assertTrue(track.playable)
        assertFalse(track.copy(hasAudio = false).playable)
        assertTrue(track.copy(hasAudio = true, libraryPath = null).playable)
        assertFalse(track.copy(libraryPath = null).playable)
        assertTrue(LibraryItem("album", "a", "Album", coverage = Coverage(inLibrary = 1)).playable)
        assertFalse(LibraryItem("artist", "a", "Artist", hasAudio = true).playable)
        assertEquals("Album", track.copy(albumName = "Album").subtitle)
        assertEquals("track", track.subtitle)
        assertEquals("A, B", track.copy(artists = listOf(ArtistCredit("a", "A"), ArtistCredit("b", "B"))).subtitle)
    }

    @Test fun acceptsBothAdminLegacyAndListenerPositiveProjectionDtos() = runBlocking {
        val legacy = """{"kind":"track","id":"legacy","name":"Legacy","libraryPath":"music/legacy.flac","source":"internal","tracked":true,"coverage":{"tracks":0,"inLibrary":0,"albums":0,"status":"complete"}}"""
        web.enqueue(jsonResponse("""{"items":[$legacy,$track],"total":2,"limit":40,"offset":0,"revision":10}"""))
        val items = client.library(kind = "tracks").items
        assertNull(items[0].hasAudio)
        assertTrue(items[0].playable)
        assertTrue(items[1].playable)
        assertEquals("music/legacy.flac", items[0].libraryPath)
        assertNull(items[1].libraryPath)
    }

    @Test fun malformedJsonHasSafeHelpfulFailure() = runBlocking {
        web.enqueue(jsonResponse("""{"items":"private/files/password","total":0}"""))
        val error = apiFailure { client.library() }
        assertTrue(error.message.contains("JSON"))
        assertFalse(error.message.contains("private/files"))
    }
}
