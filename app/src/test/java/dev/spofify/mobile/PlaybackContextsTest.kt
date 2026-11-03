package dev.spofify.mobile

import dev.spofify.mobile.core.*
import dev.spofify.mobile.playback.playbackStartIndex
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class PlaybackContextsTest {
    private class EmptyStore : SessionStore {
        override fun read(): StoredSession? = null
        override fun write(value: StoredSession?) = Unit
    }
    private val queue = """{"items":[{"kind":"track","id":"A","name":"A","hasAudio":true,"entryId":"e1"},{"kind":"track","id":"B","name":"B","hasAudio":true,"entryId":"e2"},{"kind":"track","id":"A","name":"A","hasAudio":true,"entryId":"e3"}],"total":3}"""

    @Test fun tappingADuplicateUsesThePlaylistAndSelectedOccurrenceNotItsAlbum() = runBlocking {
        val web = MockWebServer().apply { start() }
        try {
            val client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), EmptyStore())
            web.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(queue))
            val selected = LibraryItem("track", "A", "A", albumId = "unrelated", hasAudio = true, entryId = "e3")
            val result = playbackForSelection(client, Page.Playlist(PlaylistSummary("playlist", "Playlist")), selected)
            assertEquals("/api/me/playlists/playlist/playback", web.takeRequest().path)
            assertEquals(2, playbackStartIndex(result.items, selected.id, selected.entryId))
            assertEquals(listOf("A", "B", "A"), result.items.map { it.id })
        } finally { web.shutdown() }
    }

    @Test fun likedRowUsesTheLikedCollection() = runBlocking {
        val web = MockWebServer().apply { start() }
        try {
            val client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), EmptyStore())
            web.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(queue))
            playbackForSelection(client, Page.Likes, LibraryItem("track", "A", "A", albumId = "other", hasAudio = true))
            assertEquals("/api/me/likes/playback", web.takeRequest().path)
        } finally { web.shutdown() }
    }

    @Test fun recentShelfDoesNotOfferOffsetPagination() {
        assertEquals(40, displayedTotal(Page.Home, 40, 90))
        assertEquals(90, displayedTotal(Page.Likes, 40, 90))
    }
}
