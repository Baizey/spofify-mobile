package dev.spofify.mobile.feature.playlists

import dev.spofify.mobile.core.*
import org.junit.Assert.*
import org.junit.Test

class PlaylistFilterTest {
    private val song = LibraryItem("track", "same", "Café 音楽 🌙", artists = listOf(
        ArtistCredit("a", "Mira Vale"), ArtistCredit("b", "Second Credit")), albumName = "After the Tide", hasAudio = true)
    private val duplicate = listOf(PersonalPlaylistEntry("first", 0, song), PersonalPlaylistEntry("second", 1, song))

    @Test fun literalTrimmedCaseInsensitiveTitleAllCreditsAndAlbumKeepExactOccurrences() {
        for (query in listOf(" CAFÉ ", "音楽 🌙", "mIRA vALE", "SECOND credit", "after THE tide")) {
            val result = filterPlaylistEntries(duplicate, query) { it.track }
            assertEquals(listOf("first", "second"), result.map { it.id })
            assertSame(duplicate[1], result[1])
        }
        assertTrue(filterPlaylistEntries(duplicate, "title:Café") { it.track }.isEmpty())
        assertTrue(filterPlaylistEntries(duplicate, ".*") { it.track }.isEmpty())
        assertSame(duplicate, filterPlaylistEntries(duplicate, " \n\t ") { it.track })
        assertSame(duplicate, filterPlaylistEntries(duplicate, "") { it.track })
    }

    @Test fun projectionRecomputesFromLegitimateAppendRemovalAndMetadataChangeWithoutMutatingSource() {
        val appended = duplicate + PersonalPlaylistEntry("last", 2, song.copy(name = "Only append", albumName = "Other"))
        assertEquals(listOf("last"), filterPlaylistEntries(appended, "only append") { it.track }.map { it.id })
        val removed = appended.drop(1)
        assertEquals(listOf("second"), filterPlaylistEntries(removed, "tide") { it.track }.map { it.id })
        val updated = removed.map { it.copy(track = it.track.copy(albumName = "New album")) }
        assertTrue(filterPlaylistEntries(updated, "tide") { it.track }.isEmpty())
        assertEquals(listOf("first", "second"), duplicate.map { it.id })
    }

    @Test fun allResidentRowsNotJustViewportAndLongUnicodeRemainLiteral() {
        val long = "音楽🌙é".repeat(400)
        val resident = (0 until 50_000).map { index ->
            PersonalPlaylistEntry("entry-$index", index, song.copy(name = if (index == 49_999) long else "Song $index", artists = emptyList(), albumName = null))
        }
        assertEquals(listOf("entry-49999"), filterPlaylistEntries(resident, long) { it.track }.map { it.id })
        assertEquals(50_000, filterPlaylistEntries(resident, "Song") { it.track }.size + 1)
        assertEquals(50_000, resident.size)
    }

    @Test fun cancelledLargeProjectionStopsBeforeScanningTheRetiredSource() {
        val resident = (0 until 50_000).map { PersonalPlaylistEntry("entry-$it", it, song) }
        var visits = 0
        try {
            filterPlaylistEntries(resident, "missing", ensureCurrent = {
                if (++visits == 128) throw kotlinx.coroutines.CancellationException("retired query")
            }) { it.track }
            fail("Retired projection must cancel")
        } catch (_: kotlinx.coroutines.CancellationException) {
            assertEquals(128, visits)
            assertEquals(50_000, resident.size)
        }
    }

    @Test fun filterLabelIsExactAndAbsentWithoutTrimmedQuery() {
        assertEquals("Filtered to 2 songs", playlistFilterLabel(2, " needle "))
        assertEquals("Filtered to 0 songs", playlistFilterLabel(0, "missing"))
        assertNull(playlistFilterLabel(40, ""))
        assertNull(playlistFilterLabel(40, " \t "))
    }

    @Test fun browseMemoryRetainsQueryAndAnchorsButNewPlaylistRetiresThem() {
        val web = okhttp3.mockwebserver.MockWebServer()
        web.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        val client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), object : SessionStore {
            override fun read(): StoredSession? = null
            override fun write(value: StoredSession?) = Unit
        })
        try {
            val memory = PlaylistBrowseMemory()
            memory.bind(client); memory.openPlaylist("one")
            val detail = memory.detail("one", 0)
            detail.filterQuery.value = "retained"
            memory.bind(client)
            assertSame(detail, memory.detail("one", 0))
            assertEquals("retained", detail.filterQuery.value)
            memory.openPlaylist("two")
            assertEquals("", memory.detail("two", 0).filterQuery.value)
        } finally {
            client.httpClient.dispatcher.cancelAll()
            client.httpClient.connectionPool.evictAll()
            client.httpClient.dispatcher.executorService.shutdown()
            web.close()
        }
    }
}
