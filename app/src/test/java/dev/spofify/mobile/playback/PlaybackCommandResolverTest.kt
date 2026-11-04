package dev.spofify.mobile.playback

import dev.spofify.mobile.core.PlaybackContext
import dev.spofify.mobile.core.PlaybackDeviceCommand
import dev.spofify.mobile.core.PlaybackSnapshot
import dev.spofify.mobile.core.ServerAddress
import dev.spofify.mobile.core.SessionStore
import dev.spofify.mobile.core.SpofifyClient
import dev.spofify.mobile.core.StoredSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Loopback fixtures only; resolving never needs an Android player, service or audio request. */
class PlaybackCommandResolverTest {
    private lateinit var web: MockWebServer
    private lateinit var client: SpofifyClient
    private val derived = PlaybackContext("derived", "rules", "Original rules", 5, "f1_original+/%?=&")
    private val playlist = PlaybackContext("playlist", "manual", "Manual")

    @Before fun setup() {
        web = MockWebServer().also { it.start() }
        client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), object : SessionStore {
            override fun read(): StoredSession? = null
            override fun write(value: StoredSession?) = Unit
        })
    }
    @After fun teardown() { web.shutdown() }

    @Test fun transportIsNotMusicalAndDoesNotFetch() = runBlocking {
        assertNull(PlaybackCommandResolver.resolve(PlaybackDeviceCommand.transport("resume"), client))
        assertEquals(0, web.requestCount)
    }

    @Test fun currentPositivePlayMatchesExactEntryAndContextWithoutFetching() = runBlocking {
        val enqueue = PlaybackDeviceCommand.musical("enqueue", track = wire(album = "album", entry = "e2"), first = false)
        val current = requireNotNull(PlaybackCommandResolver.resolve(enqueue, client)).tracks.single()
        val state = PlaybackSnapshot(queue = listOf(current.copy(key = 4, order = 7), current.copy(key = 8, order = 3, entryId = "e1")),
            position = 0, context = playlist, elapsed = 42.5)
        fun play(entry: String, context: PlaybackContext = playlist) = PlaybackDeviceCommand.musical("play",
            track = wire(album = "album", entry = entry), context = context, entryId = entry)
        assertTrue(PlaybackCommandResolver.selectsCurrent(play("e2"), state))
        assertFalse(PlaybackCommandResolver.selectsCurrent(play("e1"), state))
        assertFalse(PlaybackCommandResolver.selectsCurrent(play("e2", playlist.copy(id = "other")), state))
        assertTrue(PlaybackCommandResolver.selectsCurrent(PlaybackDeviceCommand.musical("play", track = wire(album = "album")), state))
        assertEquals(0, web.requestCount)
    }

    @Test fun bareEnqueueNeverFetchesEvenWithAlbumAndReplacesUntrustedKeysWithUniquePlaceholders() = runBlocking {
        val command = PlaybackDeviceCommand.musical("enqueue", track = wire(album = "album"), first = true)
        val result = requireNotNull(PlaybackCommandResolver.resolve(command, client))
        assertEquals(listOf("same"), result.tracks.map { it.id })
        assertEquals(1L, result.tracks.single().key)
        assertEquals(0L, result.tracks.single().order)
        assertEquals(12.3456789, result.tracks.single().duration, 0.0)
        assertTrue(result.enqueue)
        assertTrue(result.first)
        assertFalse(result.shuffle)
        assertNull(result.context)
        assertEquals(0, web.requestCount)
    }

    @Test fun barePlayWithoutAlbumIsOneSafeTrackWithoutFetch() = runBlocking {
        val result = resolve(PlaybackDeviceCommand.musical("play", track = wire()))
        assertEquals("same", result.tracks.single().id)
        assertEquals(0, result.startIndex)
        assertTrue(result.pinStart)
        assertFalse(result.enqueue)
        assertNull(result.context)
        assertEquals(0, web.requestCount)
    }

    @Test fun barePlayWithAlbumExpandsApprovedAlbumEndpointAndProjectsOnlyMusic() = runBlocking {
        web.enqueue(response(queue("album", "album/a%", listOf(track("other"), track("same", privatePath = "/private/song.flac")))))
        val result = resolve(PlaybackDeviceCommand.musical("play", track = wire(album = "album/a%")))
        assertEquals("/api/library/albums/album%2Fa%25/playback", web.takeRequest().path)
        assertEquals(listOf("other", "same"), result.tracks.map { it.id })
        assertEquals(1, result.startIndex)
        assertEquals("album/a%", result.context?.id)
        assertEquals(7L, result.context?.revision)
        assertEquals(12.345, result.tracks[1].duration, 0.0)
        val snapshot = PlaybackSnapshot(queue = result.tracks, position = result.startIndex, context = result.context).toJson().toString()
        assertFalse(snapshot.contains("private"))
        assertFalse(snapshot.contains("libraryPath"))
        assertFalse(snapshot.contains("audio"))
        assertEquals(1, web.requestCount)
    }

    @Test fun explicitPlaylistOverridesAlbumAndHonorsSecondOccurrenceAndShuffleIntent() = runBlocking {
        web.enqueue(response(queue("playlist", "manual", listOf(track(entry = "e1"), track(entry = "e2")))))
        val result = resolve(PlaybackDeviceCommand.musical("play", track = wire(album = "wrong-album", entry = "e1"),
            context = playlist, entryId = "e2"))
        assertEquals("/api/me/playlists/manual/playback", web.takeRequest().path)
        assertEquals(listOf("same", "same"), result.tracks.map { it.id })
        assertEquals(listOf("e1", "e2"), result.tracks.map { it.entryId })
        assertEquals(listOf(1L, 2L), result.tracks.map { it.key })
        assertEquals(listOf(0L, 1L), result.tracks.map { it.order })
        assertEquals(1, result.startIndex)
        assertEquals(7L, result.context?.revision)
        assertTrue(result.pinStart)
        web.enqueue(response(queue("playlist", "manual", listOf(track(entry = "e1"), track(entry = "e2")))))
        val shuffled = resolve(PlaybackDeviceCommand.musical("playCollection", context = playlist,
            trackId = "same", entryId = "e2", shuffle = true))
        assertEquals(1, shuffled.startIndex)
        assertTrue(shuffled.shuffle)
        assertTrue(shuffled.pinStart)
        assertEquals(listOf("e1", "e2"), shuffled.tracks.map { it.entryId })
    }

    @Test fun entryOnlySelectionWorksButConflictingTrackNeverSubstitutes() = runBlocking {
        val body = queue("playlist", "manual", listOf(track("a", "e1"), track("b", "e2")))
        web.enqueue(response(body))
        assertEquals(1, resolve(PlaybackDeviceCommand.musical("playCollection", context = playlist, entryId = "e2")).startIndex)
        web.enqueue(response(body))
        rejects(PlaybackDeviceCommand.musical("playCollection", context = playlist, entryId = "e2", trackId = "a"))
        assertEquals(2, web.requestCount)
    }

    @Test fun explicitUnavailableOrMissingSelectedOccurrenceRejectsIncludingAtPlaybackCap() = runBlocking {
        val command = PlaybackDeviceCommand.musical("playCollection", context = playlist, trackId = "same", entryId = "e2")
        for (items in listOf(listOf(track(entry = "e1")), listOf(track(entry = "e1"), track(entry = "e2", available = false)))) {
            web.enqueue(response(queue("playlist", "manual", items, total = 1200, truncated = true)))
            rejects(command)
        }
        // No bare-track fallback and no second fetch to recover a selected row.
        assertEquals(2, web.requestCount)
    }

    @Test fun likesKeepsExplicitContextWithCanonicalWireIdEvenWhenMobileResponseIdIsEmpty() = runBlocking {
        for (responseId in listOf("", "liked-songs")) {
            web.enqueue(response(queue("likes", responseId, listOf(track()))))
            val result = resolve(PlaybackDeviceCommand.musical("play", track = wire(album = "album"),
                context = PlaybackContext("likes", "likes", "My likes")))
            assertEquals("/api/me/likes/playback", web.takeRequest().path)
            assertEquals(PlaybackContext("likes", "likes", "My likes", 7), result.context)
            assertEquals(0, result.startIndex)
            PlaybackSnapshot(queue = result.tracks, position = 0, context = result.context).validated()
        }
    }

    @Test fun enqueueArtistContextHasNoAutoplayIntentAndUnselectedShuffleIsNotPinned() = runBlocking {
        val artist = PlaybackContext("artist", "artist", "Artist")
        web.enqueue(response(queue("artist", "artist", listOf(track()))))
        val add = resolve(PlaybackDeviceCommand.musical("enqueueContext", context = artist, first = false))
        assertEquals("/api/library/artists/artist/playback", web.takeRequest().path)
        assertTrue(add.enqueue)
        assertFalse(add.first)
        assertFalse(add.pinStart)
        web.enqueue(response(queue("artist", "artist", listOf(track()))))
        val play = resolve(PlaybackDeviceCommand.musical("playCollection", context = artist, shuffle = true))
        assertTrue(play.shuffle)
        assertFalse(play.pinStart)
    }

    @Test fun derivedSelectionPostsOriginalFenceRevisionAndTokenOnceAndRetainsOccurrenceIdentity() = runBlocking {
        authenticate()
        web.enqueue(response(derivedBody(start = "o2")))
        val result = resolve(PlaybackDeviceCommand.musical("playCollection", context = derived, trackId = "same",
            occurrenceId = "o2", selectionToken = "s1_original+/%?=&", shuffle = true))
        val request = web.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/me/playlists/rules/playback", request.path)
        assertEquals("csrf", request.getHeader("X-CSRF-Token"))
        assertTrue(request.getHeader("Cookie")!!.contains("spofify_session=secret"))
        assertEquals(objectOf("""{"expectedRevision":5,"fence":"f1_original+/%?=&","selectionToken":"s1_original+/%?=&"}"""),
            objectOf(request.body.readUtf8()))
        assertEquals(listOf("same", "same"), result.tracks.map { it.id })
        assertEquals(listOf("o1", "o2"), result.tracks.map { it.occurrenceId })
        assertEquals(listOf("", ""), result.tracks.map { it.entryId })
        assertEquals(listOf("rules", "rules"), result.tracks.map { it.targetId })
        assertEquals(listOf(derived, derived), result.tracks.map { it.playbackContext })
        assertEquals(derived, result.context)
        assertEquals(1, result.startIndex)
        assertTrue(result.shuffle)
        assertTrue(result.pinStart)
        assertFalse(result.truncated)
        val snapshot = PlaybackSnapshot(queue = result.tracks, position = result.startIndex, context = result.context).toJson().toString()
        assertFalse(snapshot.contains("selectionToken"))
        assertFalse(snapshot.contains("s1_original"))
        assertFalse(snapshot.contains("libraryPath"))
        assertEquals(2, web.requestCount) // login + the single fenced playback request
    }

    @Test fun longDerivedOccurrenceStaysInIts4096BoundFieldNotManualEntryId() = runBlocking {
        val occurrence = "derived-" + "x".repeat(1600)
        web.enqueue(response(derivedBody(items = listOf(derivedTrack("o1"), derivedTrack(occurrence)), start = occurrence)))
        val result = resolve(PlaybackDeviceCommand.musical("playCollection", context = derived,
            occurrenceId = occurrence, selectionToken = "original-long-selection"))
        assertTrue(occurrence.length > 1024)
        assertEquals(listOf("o1", occurrence), result.tracks.map { it.occurrenceId })
        assertTrue(result.tracks.all { it.entryId.isEmpty() && it.targetId == derived.id && it.playbackContext == derived })
        assertEquals(1, result.startIndex)
        val prepared = PlaybackSnapshot(queue = result.tracks, position = result.startIndex, context = result.context).validated()
        assertEquals(prepared, PlaybackSnapshot.fromJson(prepared.toJson()))
        assertEquals("original-long-selection", objectOf(web.takeRequest().body.readUtf8())["selectionToken"]?.toString()?.trim('"'))
        assertEquals(1, web.requestCount)
    }

    @Test fun derivedPlayUsesExplicitSelectionOverridesNotStaleTrackAuthority() = runBlocking {
        web.enqueue(response(derivedBody(start = "o2")))
        val input = wire(occurrence = "o1", token = "old-token", context = derived)
        val result = resolve(PlaybackDeviceCommand.musical("play", track = input, context = derived,
            entryId = "o2", occurrenceId = "o2", selectionToken = "original-click"))
        assertEquals(1, result.startIndex)
        assertEquals("original-click", objectOf(web.takeRequest().body.readUtf8())["selectionToken"]?.toString()?.trim('"'))
        assertEquals("o2", result.tracks[result.startIndex].occurrenceId)
    }

    @Test fun unselectedDerivedEnqueueUsesFencedPostWithoutSelectionAndPreservesTruncationNotice() = runBlocking {
        web.enqueue(response(derivedBody(start = "o1", total = 5000000000, notice = "PLAYBACK_LIMIT")))
        val result = resolve(PlaybackDeviceCommand.musical("enqueueContext", context = derived, first = true))
        assertEquals(setOf("expectedRevision", "fence"), objectOf(web.takeRequest().body.readUtf8()).keys)
        assertTrue(result.enqueue)
        assertTrue(result.first)
        assertTrue(result.truncated)
        assertEquals("PLAYBACK_LIMIT", result.notice)
        assertTrue(result.pinStart)
    }

    @Test fun clickedDerivedOccurrenceBeyondPrefixIsExactlyOneOccurrenceNotFirstThousand() = runBlocking {
        web.enqueue(response(derivedBody(items = listOf(derivedTrack("far")), start = "far", total = 5000000000,
            notice = "CLICKED_OCCURRENCE_ONLY")))
        val result = resolve(PlaybackDeviceCommand.musical("playCollection", context = derived,
            occurrenceId = "far", selectionToken = "signed-far"))
        assertEquals(listOf("far"), result.tracks.map { it.occurrenceId })
        assertEquals(0, result.startIndex)
        assertTrue(result.truncated)
        assertEquals("CLICKED_OCCURRENCE_ONLY", result.notice)
        assertEquals(1, web.requestCount)
    }

    @Test fun derivedBareEnqueuePreservesPreparedIdentityAndDropsSelectionAuthorityWithoutFetch() = runBlocking {
        val result = resolve(PlaybackDeviceCommand.musical("enqueue",
            track = wire(occurrence = "o2", token = "signed-token", context = derived), first = false))
        assertTrue(result.enqueue)
        assertEquals(derived, result.context)
        assertEquals(derived, result.tracks.single().playbackContext)
        assertEquals("rules", result.tracks.single().targetId)
        assertEquals("o2", result.tracks.single().occurrenceId)
        assertFalse(PlaybackSnapshot(queue = result.tracks, position = 0).toJson().toString().contains("selectionToken"))
        assertEquals(0, web.requestCount)
    }

    @Test fun incompleteDerivedSelectionOrImplicitAlbumFallbackRejectsBeforeAnyFetch() = runBlocking {
        rejects(PlaybackDeviceCommand.musical("playCollection", context = derived, trackId = "same"))
        rejects(PlaybackDeviceCommand.musical("playCollection", context = derived, occurrenceId = "o2"))
        rejects(PlaybackDeviceCommand.musical("play", track = wire(album = "album", occurrence = "o2", token = "token", context = derived)))
        rejects(PlaybackDeviceCommand.musical("play", context = playlist,
            track = wire(album = "album", occurrence = "o2", token = "token", context = derived)))
        assertEquals(0, web.requestCount)
    }

    @Test fun staleAuthorityAndMismatchedOrUnavailableDerivedStartNeverRefreshRetryOrFallback() = runBlocking {
        val command = PlaybackDeviceCommand.musical("playCollection", context = derived, occurrenceId = "o2", selectionToken = "signed")
        val invalidBodies = listOf(
            derivedBody(start = "o1"),
            derivedBody(start = "o2").replace("f1_original+/%?=&", "other-fence"),
            derivedBody(start = "o2").replace("\"revision\":5", "\"revision\":6"),
            derivedBody(start = "o2", items = listOf(derivedTrack("o1"), derivedTrack("o2", available = false))),
            derivedBody(start = "o2", notice = "PLAYBACK_LIMIT"),
            derivedBody(start = "o2", total = 3000, notice = "CLICKED_OCCURRENCE_ONLY"),
        )
        for (body in invalidBodies) {
            web.enqueue(response(body))
            fails(command)
        }
        web.enqueue(response("""{"error":{"code":"STALE_EVALUATION","message":"private"}}""").setResponseCode(409))
        fails(command)
        assertEquals(invalidBodies.size + 1, web.requestCount)
        repeat(web.requestCount) { assertEquals("/api/me/playlists/rules/playback", web.takeRequest().path) }
    }

    @Test fun emptyDuplicateIdentityAndOversizedOrdinaryResultsRejectInsteadOfRepairing() = runBlocking {
        for (items in listOf(emptyList(), listOf(track(entry = "duplicate"), track(entry = "duplicate")),
            (0..1000).map { track(entry = "e$it") })) {
            web.enqueue(response(queue("playlist", "manual", items)))
            rejects(PlaybackDeviceCommand.musical("playCollection", context = playlist))
        }
        assertEquals(3, web.requestCount)
    }

    @Test fun cancellationDuringAwaitPropagatesAndDoesNotStartAnotherRequest() = runBlocking {
        web.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val deferred = async {
            PlaybackCommandResolver.resolve(PlaybackDeviceCommand.musical("playCollection", context = playlist), client)
        }
        // Start on a worker so this thread can observe the request before cancelling the await.
        val observer = async(kotlinx.coroutines.Dispatchers.IO) {
            requireNotNull(web.takeRequest(5, TimeUnit.SECONDS))
        }
        assertEquals("/api/me/playlists/manual/playback", observer.await().path)
        deferred.cancelAndJoin()
        assertTrue(deferred.isCancelled)
        assertThrows(CancellationException::class.java) { runBlocking { deferred.await() } }
        assertEquals(1, web.requestCount)
    }

    @Test fun alreadyCancelledResolutionDoesNotFetchEvenForBareEnqueue() = runBlocking {
        val deferred = async(start = CoroutineStart.LAZY) {
            PlaybackCommandResolver.resolve(PlaybackDeviceCommand.musical("enqueue", track = wire(), first = false), client)
        }
        deferred.cancel()
        assertThrows(CancellationException::class.java) { runBlocking { deferred.await() } }
        assertEquals(0, web.requestCount)
    }

    private suspend fun resolve(command: PlaybackDeviceCommand) = requireNotNull(PlaybackCommandResolver.resolve(command, client))
    private suspend fun rejects(command: PlaybackDeviceCommand) {
        try { resolve(command); fail("Expected explicit selection rejection") }
        catch (_: IllegalArgumentException) { }
    }
    private suspend fun fails(command: PlaybackDeviceCommand) {
        try { resolve(command); fail("Expected invalid response or API failure") }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            assertTrue(error is IllegalArgumentException || error is dev.spofify.mobile.core.ApiException)
        }
    }
    private suspend fun authenticate() {
        web.enqueue(response("""{"accountId":"listener","username":"listener","role":"user","csrfToken":"csrf"}""")
            .setHeader("Set-Cookie", "spofify_session=secret; Path=/; HttpOnly; Max-Age=3600"))
        client.login("listener", "password")
        web.takeRequest()
    }
    private fun objectOf(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
    private fun response(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun wire(album: String = "", entry: String = "", occurrence: String = "", token: String = "", context: PlaybackContext? = null): JsonObject = objectOf(
        """{"id":"same","name":"Song","artists":[{"name":"Artist"}],"albumName":"Album","albumId":"$album","duration":12.3456789,"entryId":"$entry","key":900,"order":800${
            if (occurrence.isEmpty()) "" else ""","occurrenceId":"$occurrence","targetId":"rules""""
        }${if (token.isEmpty()) "" else ""","selectionToken":"$token""""}${
            if (context == null) "" else ""","playbackContext":{"kind":"${context.kind}","id":"${context.id}","name":"${context.name}","revision":${context.revision},"fence":"${context.fence}"}"""
        }}""")
    private fun track(id: String = "same", entry: String = "", available: Boolean = true, privatePath: String = "/private/never-retain") =
        """{"kind":"track","id":"$id","name":"Song","artists":[{"id":"artist","name":"Artist"}],"durationMs":12345,"hasAudio":$available,"libraryPath":"$privatePath","entryId":"$entry"}"""
    private fun queue(kind: String, id: String, items: List<String>, total: Long = items.size.toLong(), truncated: Boolean = false) =
        """{"context":{"kind":"$kind","id":"$id","name":"Collection"},"items":[${items.joinToString()}],"total":$total,"limit":1000,"truncated":$truncated,"revision":7}"""
    private fun derivedTrack(occurrence: String, available: Boolean = true) =
        track(available = available).dropLast(1) + ""","occurrenceId":"$occurrence","selectionToken":"signed-$occurrence"}"""
    private fun derivedBody(items: List<String> = listOf(derivedTrack("o1"), derivedTrack("o2")), start: String,
        total: Long = items.size.toLong(), notice: String? = null) =
        """{"context":{"kind":"derived","id":"rules","name":"Server name","revision":5,"fence":"${derived.fence}"},"items":[${items.joinToString()}],"total":$total,"limit":1000,"truncated":${total > items.size},"revision":5,"fence":"${derived.fence}","startOccurrenceId":"$start","notice":${notice?.let { "\"$it\"" } ?: "null"}}"""
}
