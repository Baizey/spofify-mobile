package dev.spofify.mobile.core.offline

import dev.spofify.mobile.core.auth.AccountIdentity
import dev.spofify.mobile.core.content.LyricLine
import dev.spofify.mobile.core.content.LyricReason
import dev.spofify.mobile.core.content.LyricsState
import dev.spofify.mobile.core.content.TrackContentException
import dev.spofify.mobile.core.content.TrackLyrics
import dev.spofify.mobile.core.content.WaveformState
import dev.spofify.mobile.core.library.Artist
import dev.spofify.mobile.core.library.LibraryException
import dev.spofify.mobile.core.library.MusicItem
import dev.spofify.mobile.core.library.MusicKind
import dev.spofify.mobile.core.personal.PersonalArtist
import dev.spofify.mobile.core.personal.PersonalCoverage
import dev.spofify.mobile.core.personal.PersonalTrack
import dev.spofify.mobile.core.search.SearchErrorCode
import dev.spofify.mobile.core.search.SearchException
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalMusicCatalogTest {
    @get:Rule val folder = TemporaryFolder()
    private val identity = AccountIdentity("https://one.example", "account", "user", 1)
    private var available = emptySet<String>()
    private fun open(owner: AccountIdentity = identity) = LocalMusicCatalog(folder.root, owner, { available },
        Clock.fixed(Instant.parse("2025-03-01T00:00:00Z"), ZoneOffset.UTC))

    @Test fun discoveryUsesOnlyLocalCanonicalMembershipAndKeepsNameOnlyCredits() = runBlocking {
        val catalog = open()
        val actual = track("lib-1", "Été", disc = 2).copy(hasAudio = false)
        available = setOf("lib-1", "lib-2", "bare-id")
        catalog.rememberTracks(listOf(
            LocalTrackMetadata.Personal(actual),
            LocalTrackMetadata.Library(libraryTrack("lib-1", "Other title").copy(artists = listOf(Artist("", "Choir")))),
            LocalTrackMetadata.Personal(track("remote-only", "Été Radio")),
            LocalTrackMetadata.Library(libraryTrack("lib-2", "Au cœur")),
        ))
        assertEquals(actual, catalog.personalTrack("lib-1"))
        assertNull(catalog.personalTrack("lib-2"))
        assertEquals(listOf(Artist("artist-1", "Beyoncé"), Artist("", "Guest Ágata"), Artist("", "Choir")),
            catalog.detail(MusicKind.TRACK, "lib-1").artists)
        val album = catalog.artistAlbums("artist-1").items.single()
        assertEquals("album-1", album.id)
        assertEquals(2L, album.totalTracks)
        assertEquals(2L, catalog.detail(MusicKind.ARTIST, "artist-1").availableTracks)
        assertEquals(listOf("lib-2", "lib-1"), catalog.albumTracks(album.id).items.map { it.id })
        assertEquals(listOf("lib-1", "lib-2"),
            catalog.search("GUEST AGATA", MusicKind.TRACK, 10).items.map { it.id }.sorted())
        assertTrue(catalog.search("GUEST AGATA", MusicKind.ARTIST, 10).items.isEmpty())
        assertEquals(listOf("artist-1"), catalog.search("bEYoNce", MusicKind.ARTIST, 10).items.map { it.id })
        assertEquals(listOf("album-1"), catalog.search("NUIT D'ete", MusicKind.ALBUM, 10).items.map { it.id })
        for (id in listOf("bare-id", "remote-only")) {
            assertEquals(404, failure<LibraryException> { catalog.detail(MusicKind.TRACK, id) }.status)
        }
        assertTrue(catalog.albumTracks("nonexistent").items.isEmpty())
        assertTrue(catalog.detail(MusicKind.TRACK, "lib-1").hasAudio)
    }

    @Test fun prefetchedQueueCreditsAndLegacyTitlesSurviveRestartWithoutDetailOrListeningItems() = runBlocking {
        available = setOf("cached", "legacy")
        val prefetched = LocalTrackMetadata.Cached("cached", "Prefetched song", "album", "Release",
            listOf(dev.spofify.mobile.core.playback.PlaybackArtist("Singer", "credit")), 120000)
        val legacy = LocalTrackMetadata.Cached("legacy", "Legacy title")
        open().rememberTracks(listOf(prefetched, legacy))
        val restored = open()
        restored.rememberTracks(listOf(LocalTrackMetadata.Cached("cached", "Title only")))
        assertEquals("legacy", restored.search("Legacy title", MusicKind.TRACK, 10).items.single().id)
        assertEquals("cached", restored.search("Singer", MusicKind.TRACK, 10).items.single().id)
        assertEquals("album", restored.artistAlbums("credit").items.single().id)
        assertEquals("cached", restored.albumTracks("album").items.single().id)
        assertNull(restored.personalTrack("cached"))
        available = emptySet()
        assertTrue(restored.availableTracks().isEmpty())
    }

    @Test fun rankingAndPagingAreStableButDoNotSurviveAnInventoryRevision() = runBlocking {
        val catalog = open()
        val candidates = listOf(track("d", "Desert").copy(albumName = "Éléphant"), track("c", "For an Elephant"),
            track("b", "Éléphant Parade"), track("a", "éléphant"))
        available = candidates.mapTo(hashSetOf(), PersonalTrack::id)
        catalog.rememberTracks(candidates.map { LocalTrackMetadata.Personal(it) })
        val first = catalog.search("  ELEPHANT  ", MusicKind.TRACK, 2)
        assertEquals(listOf("a", "b"), first.items.map { it.id })
        val cursor = requireNotNull(first.nextCursor)
        val next = catalog.next(cursor)
        assertEquals(next, catalog.next(cursor))
        assertEquals(listOf("c", "d"), next.items.map { it.id })
        assertNull(next.nextCursor)
        assertEquals(4, first.resultCount)
        val albumBefore = catalog.albumTracks("album-1")
        available = available - "a"
        assertEquals(SearchErrorCode.SEARCH_EXPIRED, failure<SearchException> { catalog.next(cursor) }.code)
        val albumAfter = catalog.albumTracks("album-1")
        assertTrue(albumAfter.revision > albumBefore.revision)
        assertEquals(3L, albumAfter.total)
        assertEquals(listOf("b", "c", "d"), catalog.search(MusicKind.TRACK, "Elephant").items.map { it.id })
    }

    @Test fun searchCursorsAreBoundedAndExpire() = runBlocking {
        var now = Instant.parse("2025-03-01T00:00:00Z")
        val clock = object : Clock() {
            override fun instant() = now
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
        }
        available = setOf("a", "b")
        val catalog = LocalMusicCatalog(folder.root, identity, { available }, clock)
        catalog.rememberTracks(available.map { LocalTrackMetadata.Personal(track(it, "Song")) })
        suspend fun cursor() = requireNotNull(catalog.search("Song", MusicKind.TRACK, 1).nextCursor)
        val first = cursor()
        repeat(16) { cursor() }
        assertEquals(SearchErrorCode.SEARCH_EXPIRED, failure<SearchException> { catalog.next(first) }.code)
        val latest = cursor()
        now = now.plusSeconds(301)
        assertEquals(SearchErrorCode.SEARCH_EXPIRED, failure<SearchException> { catalog.next(latest) }.code)
    }

    @Test fun resultAndQueryLimitsAndOffsetPaging() = runBlocking {
        val catalog = open()
        val ids = (0..315).map { "track-${it.toString().padStart(3, '0')}" }
        available = ids.toSet()
        catalog.rememberTracks(ids.reversed().map { LocalTrackMetadata.Personal(track(it, "Straße")) })
        var current = catalog.search("STRASSE", MusicKind.TRACK, 50)
        val all = mutableListOf<String>()
        while (true) {
            assertEquals(300, current.resultCount)
            all.addAll(current.items.map { it.id })
            current = catalog.next(current.nextCursor ?: break)
        }
        assertEquals(ids.take(300), all)
        val first = catalog.albumTracks("album-1")
        val second = catalog.albumTracks("album-1", 40)
        assertEquals(first.revision, second.revision)
        assertEquals(ids.take(80), (first.items + second.items).map { it.id })
        assertEquals(316L, catalog.search(MusicKind.TRACK, "Straße", 300).total)
        for (query in listOf("a".repeat(201), "\u0000", "\uD800", "   ")) {
            assertEquals(SearchErrorCode.INVALID_SEARCH_REQUEST, failure<SearchException> { catalog.search(query) }.code)
        }
        assertEquals(SearchErrorCode.INVALID_SEARCH_REQUEST, failure<SearchException> { catalog.search("x", limit = 51) }.code)
    }

    @Test fun artworkAndLyricsMissesRoundTripButDisplayLyricsAreNeverPersisted() = runBlocking {
        val catalog = open()
        available = setOf("a", "b")
        val artwork = byteArrayOf(1, 2, 3)
        catalog.rememberArtwork(MusicKind.TRACK, "a", artwork)
        catalog.rememberArtwork(MusicKind.ALBUM, "a", null)
        artwork[0] = 9
        catalog.rememberLyrics(TrackLyrics("a", LyricsState.PLAIN, "v1", "source", plainText = "not-for-offline"))
        val miss = TrackLyrics("b", LyricsState.NOT_FOUND, "v2", "source", reason = LyricReason.NO_MATCH)
        catalog.rememberLyrics(miss.copy(plainText = "not-for-offline"))
        val reopened = open()
        assertArrayEquals(byteArrayOf(1, 2, 3), reopened.artwork(MusicKind.TRACK, "a"))
        assertEquals(LocalArtwork.Missing, reopened.artworkState(MusicKind.ALBUM, "a"))
        assertEquals(LocalArtwork.Unknown, reopened.artworkState(MusicKind.ARTIST, "a"))
        assertNull(open(identity.copy(accountId = "other")).artwork(MusicKind.TRACK, "a"))
        val active = MutableStateFlow(true)
        val content = reopened.trackContent(active)
        assertEquals(LyricsState.UNAVAILABLE, content.lyrics("a").state)
        assertEquals(miss, content.lyrics("b"))
        assertEquals(WaveformState.UNAVAILABLE, content.waveform("a").state)
        val authorized = TrackLyrics("a", LyricsState.TIMED, "v1", "local-source", lines = listOf(LyricLine(0, "Test")))
        assertEquals(authorized, reopened.trackContent(active) { authorized }.lyrics("a"))
        failure<TrackContentException> { reopened.trackContent(active) { authorized }.lyrics("b") }
        reopened.rememberLyrics(miss.copy(state = LyricsState.PLAIN, plainText = "not-for-offline"))
        assertEquals(LyricsState.UNAVAILABLE, open().trackContent(active).lyrics("b").state)
        available = emptySet()
        assertEquals(LyricsState.UNAVAILABLE, content.lyrics("b").state)
        active.value = false
        failure<CancellationException> { content.lyrics("a") }
        assertFalse(content.isCurrent)
    }

    @Test fun localLyricsCompletedAfterAccountRetirementAreDiscarded() = runBlocking {
        available = setOf("a")
        val active = MutableStateFlow(true)
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<TrackLyrics>()
        val content = open().trackContent(active) {
            entered.complete(Unit)
            response.await()
        }
        val pending = async { content.lyrics("a") }
        entered.await()
        active.value = false
        response.complete(TrackLyrics("a", LyricsState.PLAIN, "v1", "local", plainText = "Test"))
        failure<CancellationException> { pending.await() }
        assertFalse(content.isCurrent)
    }

    @Test fun rejectedOrInterruptedWritesCannotReplaceThePreviousSnapshot() = runBlocking {
        val catalog = open()
        available = setOf("a", "b")
        val original = track("a", "Music")
        catalog.rememberTracks(listOf(LocalTrackMetadata.Personal(original)))
        failure<IllegalArgumentException> {
            catalog.rememberTracks(listOf(LocalTrackMetadata.Personal(track("b", "New")),
                LocalTrackMetadata.Personal(track("c", "x".repeat(2049)))))
        }
        assertNull(catalog.personalTrack("b"))
        assertEquals(original, open().personalTrack("a"))
        val dir = folder.root.listFiles()!!.single()
        val target = File(dir, "tracks.json")
        val backup = File(dir, "tracks.old")
        assertTrue(target.renameTo(backup))
        assertTrue(target.mkdir()) // Force the same-directory atomic replacement to fail.
        failure<IOException> { catalog.rememberTracks(listOf(LocalTrackMetadata.Personal(track("b", "New")))) }
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
        assertNull(catalog.personalTrack("b"))
        assertTrue(target.delete())
        assertTrue(backup.renameTo(target))
        val reopened = open()
        assertEquals(original, reopened.personalTrack("a"))
        assertNull(reopened.personalTrack("b"))
    }

    @Test fun concurrentOnlineObservationsDoNotLoseCommittedMetadata() = runBlocking {
        val ids = (0..16).map { "track-$it" }
        available = ids.toSet()
        val catalog = open()
        ids.map { id -> async { catalog.rememberTracks(listOf(LocalTrackMetadata.Personal(track(id, "Song")))) } }.awaitAll()
        assertEquals(ids.toSet(), open().albumTracks("album-1").items.map { it.id }.toSet())
    }

    private fun track(id: String, name: String, disc: Int = 1) = PersonalTrack("track", id, name, true,
        PersonalCoverage(12, 10, 1), listOf(PersonalArtist("artist-1", "Beyoncé"), PersonalArtist("", "Guest Ágata")),
        albumId = "album-1", albumName = "Nuit d'été", discNumber = disc, trackNumber = 1)

    private fun libraryTrack(id: String, name: String) = MusicItem(MusicKind.TRACK, id, name,
        listOf(Artist("artist-1", "Beyoncé"), Artist("", "Guest Ágata")), "album-1", "Nuit d'été", "2025", 120000,
        1, true, 8, 10)

    private suspend inline fun <reified T : Throwable> failure(block: () -> Unit): T {
        val error = runCatching { block() }.exceptionOrNull()
        if (error is T) return error
        throw AssertionError("Expected ${T::class.java.name}", error)
    }
}
