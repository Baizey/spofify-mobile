package dev.spofify.mobile.feature.library

import dev.spofify.mobile.core.library.Artist
import dev.spofify.mobile.core.library.MusicItem
import dev.spofify.mobile.core.library.MusicKind
import org.junit.Assert.*
import org.junit.Test

class ArtistDiscographyTest {
    @Test fun mixedOwnAndSecondaryReleasesAreGroupedExactlyOnce() {
        val source = listOf(
            release("guest-album", "appearance", "album"),
            release("own-single-a", "release", "single"),
            release("compilation", "compilation", "compilation"),
            release("own-album-a", "release", "album"),
            release("guest-single", "appearance", "single"),
            release("own-album-b", "release", "album"),
            release("own-single-b", "release", "single"),
            release("own-compilation", "release", "compilation"),
        )
        val groups = groupArtistDiscography(source)
        assertEquals(listOf("own-album-a", "own-album-b"), groups.albums.map { it.id })
        assertEquals(listOf("own-single-a", "own-single-b"), groups.singles.map { it.id })
        assertEquals(listOf("guest-album", "compilation", "guest-single", "own-compilation"), groups.appearsOn.map { it.id })
        assertEveryIdentityOnce(source, groups)
        assertSame(source[3], groups.albums[0])
        assertSame(source[1], groups.singles[0])
        assertSame(source[0], groups.appearsOn[0])
    }

    @Test fun guestSinglesAndAlbumsNeverBecomeOwnReleasesBecauseOfTheirType() {
        val source = listOf("album", "single", "compilation", "ep", "").mapIndexed { index, type ->
            release("guest-$index", "appearance", type)
        } + release("compilation-labelled-album", "compilation", "album")
        val groups = groupArtistDiscography(source)
        assertTrue(groups.albums.isEmpty())
        assertTrue(groups.singles.isEmpty())
        assertEquals(source, groups.appearsOn)
        assertEveryIdentityOnce(source, groups)
    }

    @Test fun unknownAndBlankMetadataRemainSecondaryWithoutGuessingFromCredits() {
        val artist = Artist("artist", "The same credited artist")
        val metadata = listOf(
            "" to "", "" to "album", "" to "single",
            "release" to "", "release" to "ep", "release" to "other",
            "unknown" to "album", "other" to "single",
            "Release" to "album", "release" to "Album", "release " to "single",
        )
        val source = metadata.mapIndexed { index, (relation, type) ->
            release("unknown-$index", relation, type).copy(artists = listOf(artist))
        }
        val groups = groupArtistDiscography(source)
        assertTrue(groups.albums.isEmpty())
        assertTrue(groups.singles.isEmpty())
        assertEquals(source, groups.appearsOn)
        assertEveryIdentityOnce(source, groups)
    }

    @Test fun explicitServerOwnershipWinsRegardlessOfDisplayedArtistNames() {
        val sameCredit = listOf(Artist("same-id", "Identical display name"))
        val album = release("own", "release", "album").copy(artists = sameCredit)
        val guest = release("guest", "appearance", "album").copy(artists = sameCredit)
        val uncreditedSingle = release("single", "release", "single")
        val groups = groupArtistDiscography(listOf(guest, album, uncreditedSingle))
        assertEquals(listOf(album), groups.albums)
        assertEquals(listOf(uncreditedSingle), groups.singles)
        assertEquals(listOf(guest), groups.appearsOn)
    }

    @Test fun sourceOrderingIsPreservedInsideEveryGroupWithoutDateOrTitleSorting() {
        val source = listOf(
            release("album-z", "release", "album").copy(name = "Zulu", releaseDate = "1990"),
            release("single-z", "release", "single").copy(name = "Zulu", releaseDate = "1990"),
            release("guest-z", "appearance", "album").copy(name = "Zulu", releaseDate = "1990"),
            release("album-a", "release", "album").copy(name = "Alpha", releaseDate = "2026"),
            release("guest-a", "compilation", "compilation").copy(name = "Alpha", releaseDate = "2026"),
            release("single-a", "release", "single").copy(name = "Alpha", releaseDate = "2026"),
        )
        val groups = groupArtistDiscography(source)
        assertEquals(listOf("album-z", "album-a"), groups.albums.map { it.id })
        assertEquals(listOf("single-z", "single-a"), groups.singles.map { it.id })
        assertEquals(listOf("guest-z", "guest-a"), groups.appearsOn.map { it.id })
        assertEveryIdentityOnce(source, groups)
    }

    @Test fun groupingDoesNotMutateTheInputListItemsOrTheirNestedCredits() {
        val credits = mutableListOf(Artist("artist", "Artist"))
        val source = mutableListOf(
            release("guest", "appearance", "album").copy(artists = credits),
            release("album", "release", "album"),
            release("single", "release", "single"),
        )
        val before = source.toList()
        val creditsBefore = credits.toList()
        val groups = groupArtistDiscography(source)
        assertEquals(before, source)
        assertEquals(creditsBefore, credits)
        source.indices.forEach { index -> assertSame(before[index], source[index]) }
        val all = groups.albums + groups.singles + groups.appearsOn
        source.forEach { item -> assertSame(item, all.single { it.key == item.key }) }
        // Output containers also do not alias the caller's mutable input list.
        source.clear()
        assertEquals(1, groups.albums.size)
        assertEquals(1, groups.singles.size)
        assertEquals(1, groups.appearsOn.size)
    }

    @Test fun subsequentPagesAddToTheCorrectGroupsWithoutReorderingPreviousRows() {
        val first = listOf(
            release("guest-a", "appearance", "album"),
            release("album-a", "release", "album"),
            release("single-a", "release", "single"),
            release("unknown-a"),
        )
        val second = listOf(
            release("album-b", "release", "album"),
            release("guest-single-b", "appearance", "single"),
            release("single-b", "release", "single"),
            release("compilation-b", "compilation", "compilation"),
        )
        val original = groupArtistDiscography(first)
        val combined = groupArtistDiscography(first + second)
        assertEquals(listOf("album-a", "album-b"), combined.albums.map { it.id })
        assertEquals(listOf("single-a", "single-b"), combined.singles.map { it.id })
        assertEquals(listOf("guest-a", "unknown-a", "guest-single-b", "compilation-b"), combined.appearsOn.map { it.id })
        assertEquals(original.albums, combined.albums.take(original.albums.size))
        assertEquals(original.singles, combined.singles.take(original.singles.size))
        assertEquals(original.appearsOn, combined.appearsOn.take(original.appearsOn.size))
        assertEquals(4, first.size)
        assertEquals(4, second.size)
        assertEveryIdentityOnce(first + second, combined)
    }

    @Test fun distinctReleasesWithIdenticalDisplayNamesAreNotCollapsed() {
        val source = listOf(
            release("album-original", "release", "album"),
            release("album-deluxe", "release", "album"),
            release("single", "release", "single"),
            release("guest", "appearance", "album"),
        ).map { it.copy(name = "Same display name") }
        val groups = groupArtistDiscography(source)
        assertEquals(source.take(2), groups.albums)
        assertEquals(listOf(source[2]), groups.singles)
        assertEquals(listOf(source[3]), groups.appearsOn)
        assertEveryIdentityOnce(source, groups)
    }

    @Test fun anEmptyDiscographyHasNoNonemptySections() {
        val groups = groupArtistDiscography(emptyList())
        assertTrue(groups.albums.isEmpty())
        assertTrue(groups.singles.isEmpty())
        assertTrue(groups.appearsOn.isEmpty())
    }

    private fun assertEveryIdentityOnce(source: List<MusicItem>, groups: ArtistDiscography) {
        val keys = (groups.albums + groups.singles + groups.appearsOn).map { it.key }
        assertEquals(source.map { it.key }.toSet(), keys.toSet())
        assertEquals(keys.toSet().size, keys.size)
    }

    private fun release(id: String, relation: String = "", type: String = "") = MusicItem(
        kind = MusicKind.ALBUM, id = id, name = id, artists = emptyList(), albumId = "", albumName = "",
        releaseDate = "2026-04-12", durationMs = 0, trackNumber = 0, hasAudio = false,
        availableTracks = 1, totalTracks = 1, albumType = type, relation = relation,
    )
}
