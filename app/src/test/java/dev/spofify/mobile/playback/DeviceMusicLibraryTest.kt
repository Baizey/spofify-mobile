package dev.spofify.mobile.playback

import dev.spofify.mobile.core.auth.AccountIdentity
import dev.spofify.mobile.core.offline.*
import dev.spofify.mobile.core.personal.*
import dev.spofify.mobile.core.playback.*
import dev.spofify.mobile.feature.personal.PlaylistSong
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeviceMusicLibraryTest {
    @get:Rule val folder = TemporaryFolder()
    private val track = PersonalTrack("track", "song", "Song", true, PersonalCoverage(1, 1, 1),
        artists = listOf(PersonalArtist("artist", "Artist")), albumId = "album", albumName = "Album")
    private fun manifest(kind: OfflinePlaylistKind) = OfflineManifest(
        OfflineCollection(OfflineCollectionRef(OfflineCollectionKind.PLAYLIST, "list"), "List", kind, "", 4), "fence",
        listOf(OfflineOccurrence("first", 0, track), OfflineOccurrence("second", 1, track),
            OfflineOccurrence("last", 2, track.copy(id = "other"))), 3, 3)

    @Test fun savedDerivedDetailNeedsNoServerTokenAndSelectsTheExactDuplicate() = runTest {
        val saved = manifest(OfflinePlaylistKind.DERIVED)
        val available = mutableSetOf("song", "other")
        val catalog = LocalMusicCatalog(folder.root, AccountIdentity("https://one.example", "a", "u", 1)) { available }
        val personal = DevicePersonalLibrary(catalog) { listOf(saved) }
        assertFalse(personal.playlist("list").capabilities.editEntries)
        val entry = personal.derivedEntries("list", 1, "1").items.single()
        assertNull(entry.selectionToken)
        val row = PlaylistSong(entry.occurrenceId, entry.position, entry.track, occurrenceId = entry.occurrenceId)
        val ctx = PlaybackContext("derived", "list", "List", 4, "fence")
        assertNull(row.reference(ctx)) // never submit a device-only occurrence online
        val ref = requireNotNull(row.reference(ctx, savedLocally = true))
        val resolver = DeviceMusicLibrary(emptyList(), listOf(saved), available::contains)
        val rows = requireNotNull(resolver.resolve(ref, ctx))
        assertEquals(1, rows.selectedIndex)
        assertEquals("second", rows.tracks[1].occurrenceId)
        assertNull(resolver.resolve(ref, ctx.copy(fence = "replaced")))
        assertNull(resolver.resolve(ref.copy(selectionToken = "retired"), ctx))
        available.remove("other")
        assertNull(resolver.resolve(ref, ctx)) // no silent skipping within a saved membership
    }

    @Test fun partialCatalogCollectionIncludesOnlyAvailableRowsButCannotSubstituteAnAbsentSelection() = runTest {
        val available = mutableSetOf("song", "other")
        val catalog = LocalMusicCatalog(folder.root, AccountIdentity("https://one.example", "a", "u", 1)) { available }
        catalog.rememberTracks(listOf(track, track.copy(id = "other")).map(LocalTrackMetadata::Personal))
        val resolver = DeviceMusicLibrary(catalog.availableTracks(), emptyList(), available::contains)
        available.remove("other")
        for (kind in listOf("album", "artist")) {
            val ctx = PlaybackContext(kind, kind, kind)
            assertEquals(listOf("song"), resolver.resolve(null, ctx)?.tracks?.map { it.id })
            assertNull(resolver.resolve(PlaybackTrackReference("other"), ctx))
        }
        assertTrue(catalog.history().items.isEmpty())
    }
}
