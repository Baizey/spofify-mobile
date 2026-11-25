package dev.spofify.mobile.playback

import dev.spofify.mobile.core.auth.AccountIdentity
import dev.spofify.mobile.core.offline.*
import dev.spofify.mobile.core.personal.*
import dev.spofify.mobile.core.playback.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeviceMusicLibraryTest {
    @get:Rule val folder = TemporaryFolder()
    private val track = PersonalTrack("track", "song", "Song", true, PersonalCoverage(1, 1, 1),
        artists = listOf(PersonalArtist("artist", "Artist")), albumId = "album", albumName = "Album")
    @Test fun cachedDerivedPlaybackUsesExactDuplicateAndKeepsUnavailableRowsForQueueAdvance() {
        val saved = listOf(PersonalManualEntry("first", 0, track), PersonalManualEntry("second", 1, track),
            PersonalManualEntry("last", 2, track.copy(id = "other")))
        val ctx = PlaybackContext("derived", "list", "List")
        val ref = PlaybackTrackReference(track.id, targetId = ctx.id, occurrenceId = "second")
        val rows = requireNotNull(cachedPlaylistRows(saved, ctx, ref, setOf("song")))
        assertEquals(1, rows.selectedIndex)
        assertEquals(listOf("first", "second", "last"), rows.tracks.map { it.occurrenceId })
        assertNull(cachedPlaylistRows(saved, ctx, ref.copy(occurrenceId = "absent"), setOf("song")))
    }

    @Test fun partialCatalogCollectionIncludesOnlyAvailableRowsButCannotSubstituteAnAbsentSelection() = runTest {
        val available = mutableSetOf("song", "other")
        val catalog = LocalMusicCatalog(folder.root, AccountIdentity("https://one.example", "a", "u", 1)) { available }
        catalog.rememberTracks(listOf(track, track.copy(id = "other")).map(LocalTrackMetadata::Personal))
        val resolver = DeviceMusicLibrary(catalog.availableTracks(), available::contains)
        available.remove("other")
        for (kind in listOf("album", "artist")) {
            val ctx = PlaybackContext(kind, kind, kind)
            assertEquals(listOf("song"), resolver.resolve(null, ctx)?.tracks?.map { it.id })
            assertNull(resolver.resolve(PlaybackTrackReference("other"), ctx))
        }
    }
}
