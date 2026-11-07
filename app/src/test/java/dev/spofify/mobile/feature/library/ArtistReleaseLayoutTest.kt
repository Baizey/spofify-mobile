package dev.spofify.mobile.feature.library

import dev.spofify.mobile.core.Coverage
import dev.spofify.mobile.core.LibraryItem
import org.junit.Assert.*
import org.junit.Test

class ArtistReleaseLayoutTest {
    @Test fun responsiveColumnsKeepNativeTextScalingAndIdentityBasedRestoration() {
        assertEquals(2, artistReleaseColumns(288f, 1f))
        assertEquals(1, artistReleaseColumns(288f, 2f))
        assertEquals(1, artistReleaseColumns(240f, 1f))
        assertEquals(2, artistReleaseColumns(728f, 2f))
        val releases = List(40) { LibraryItem("album", "release-$it", "Release $it") }
        val artist = CatalogScreen(item = LibraryItem("artist", "artist", "Artist"),
            sections = mapOf("albums" to CatalogSection(items = releases, loaded = true)),
            scrollIndex = 12, scrollOffset = 37, releaseAnchorId = "release-9")
        assertEquals(12, artistReleaseScrollIndex(artist, 1))
        assertEquals(7, artistReleaseScrollIndex(artist, 2))
        assertEquals(12, artistReleaseScrollIndex(artist.copy(releaseAnchorId = "retired"), 2))
        assertEquals(12, artistReleaseScrollIndex(artist.copy(item = LibraryItem("album", "album", "Album")), 2))
        assertEquals(0, artistReleaseScrollIndex(artist.copy(scrollIndex = 0, releaseAnchorId = null), 2))
    }

    @Test fun collectionMetadataComesOnlyFromSupportedFields() {
        assertEquals("2025 · 9 songs · 30:00", itemMetadata(LibraryItem("album", "album", "Album",
            coverage = Coverage(9, 8), releaseDate = "2025-09", durationMs = 1_800_000)))
        assertEquals("Unavailable", itemMetadata(LibraryItem("artist", "artist", "Artist")))
        assertEquals("2022 · 1:01 · Unavailable", itemMetadata(LibraryItem("track", "song", "Song",
            durationMs = 61_000, releaseDate = "2022-06", hasAudio = false)))
    }
}
