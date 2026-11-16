package dev.spofify.mobile.core.search

import dev.spofify.mobile.core.library.Artist
import dev.spofify.mobile.core.library.MusicKind
import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SearchParserTest {
    private fun parse(body: JsonObject, limit: Int = 25) = SearchParser.page(body.toString().toByteArray(), limit)
    private fun rejects(body: JsonObject, limit: Int = 25) { assertTrue(runCatching { parse(body, limit) }.isFailure) }
    private fun changedItem(key: String, value: JsonElement) = SearchTestData.page(listOf(JsonObject(SearchTestData.item() + (key to value))))

    @Test fun mixedServerOrderDisplayMetadataAndNameOnlyCreditsSurviveWithoutQuotas() {
        val rows = listOf(SearchTestData.item("track", "z"), SearchTestData.item("artist", "same"),
            SearchTestData.item("album", "same"), SearchTestData.item("track", "a"))
        val result = parse(SearchTestData.page(rows, count = 300, next = "opaque-next"), 4)
        assertEquals(listOf("TRACK:z", "ARTIST:same", "ALBUM:same", "TRACK:a"), result.items.map { it.key })
        assertEquals(300, result.resultCount)
        assertEquals("opaque-next", result.nextCursor)
        assertEquals(Instant.parse(SearchTestData.expiry), result.expiresAt)
        val track = result.items.first()
        assertEquals("Original 日本 name", track.name)
        assertEquals(listOf(Artist("", "Manual guest"), Artist("known", "Known artist")), track.artists)
        assertEquals("2025", track.releaseDate)
        assertEquals(180000L, track.durationMs)
        assertEquals(2, track.trackNumber)
        assertEquals(3L, track.availableTracks)
        assertEquals(4L, track.totalTracks)
        assertEquals(listOf("世界"), track.genres)
        assertFalse(result.items[1].hasAudio) // No local availability filtering.
    }

    @Test fun emptyAndHundredItemPagesKeepRetainedCountsAndExpiryIsOnlyMetadata() {
        val empty = parse(SearchTestData.page(emptyList(), expires = "2000-01-01T00:00:00Z"))
        assertTrue(empty.items.isEmpty())
        assertEquals(0, empty.resultCount)
        assertNull(empty.nextCursor)
        val rows = (1..100).map { SearchTestData.item(id = "$it") }
        assertEquals(100, parse(SearchTestData.page(rows, count = 300, next = "opaque"), 100).items.size)
        rejects(SearchTestData.page(rows + SearchTestData.item(id = "101"), count = 300, next = "opaque"), 100)
        rejects(SearchTestData.page(rows, count = 301, next = "opaque"), 100)
        rejects(SearchTestData.page(count = 0))
        rejects(SearchTestData.page(count = 2)) // Missing next page.
        rejects(SearchTestData.page(next = "unexpected"))
        rejects(SearchTestData.page(emptyList(), count = 1, next = "opaque"))
        rejects(SearchTestData.page(expires = "not-a-time"))
        rejects(SearchTestData.page(expires = "2026-02-30T12:00:00Z"))
    }

    @Test fun continuationMustBeNonemptyBoundedAndNotReturnItsOwnCursor() {
        val body = SearchTestData.page(count = 300, next = "different").toString().toByteArray()
        assertEquals("different", SearchParser.page(body, cursor = "previous").nextCursor)
        assertTrue(runCatching { SearchParser.page(body, cursor = "different") }.isFailure)
        assertTrue(runCatching { SearchParser.page(SearchTestData.page(emptyList()).toString().toByteArray(), cursor = "previous") }.isFailure)
        rejects(SearchTestData.page(count = 2, next = ""))
        rejects(SearchTestData.page(count = 2, next = "x".repeat(513)))
        rejects(SearchTestData.page(count = 2, next = "bad\ncursor"))
    }

    @Test fun hasAudioIsRequiredAndLegacyPathsNeverBecomeAudioFactsOrPresentation() {
        val row = SearchTestData.item()
        rejects(SearchTestData.page(listOf(JsonObject((row - "hasAudio") + ("libraryPath" to JsonPrimitive("private/secret.flac"))))))
        rejects(changedItem("hasAudio", JsonPrimitive("true")))
        rejects(changedItem("hasAudio", JsonNull))
        val safe = parse(SearchTestData.page(listOf(JsonObject(row + mapOf(
            "libraryPath" to JsonPrimitive("private/secret.flac"), "error" to JsonPrimitive("private/secret/error"))))))
        assertTrue(safe.items.single().hasAudio)
        assertFalse(safe.toString().contains("private/secret"))
    }

    @Test fun entityIdsAndTypedMetadataRemainStrictWhileEmptyCreditIdentityIsValid() {
        for (id in listOf("", " ", "bad\nid", "a".repeat(513), "é".repeat(257)))
            rejects(changedItem("id", JsonPrimitive(id)))
        rejects(changedItem("kind", JsonPrimitive("unknown")))
        rejects(changedItem("name", JsonPrimitive(7)))
        rejects(changedItem("durationMs", JsonPrimitive(-1)))
        rejects(changedItem("durationMs", JsonPrimitive("180000")))
        rejects(changedItem("trackNumber", JsonPrimitive(-1)))
        rejects(changedItem("discNumber", JsonPrimitive(-1)))
        rejects(changedItem("coverage", buildJsonObject { put("tracks", 1); put("inLibrary", 2) }))
        rejects(changedItem("coverage", buildJsonObject { put("tracks", "4"); put("inLibrary", 3) }))
        rejects(changedItem("artists", buildJsonArray { add(buildJsonObject { put("name", "Missing ID") }) }))
        rejects(changedItem("artists", buildJsonArray { add(buildJsonObject { put("id", ""); put("name", JsonNull) }) }))
        rejects(changedItem("artists", buildJsonArray { add(buildJsonObject { put("id", "bad\nid"); put("name", "Artist") }) }))
        rejects(SearchTestData.page(listOf(SearchTestData.item(), SearchTestData.item())))
        assertTrue(runCatching { SearchParser.page(SearchTestData.page().toString().toByteArray(), 25, MusicKind.ARTIST) }.isFailure)
    }

    @Test fun strictJsonRejectsDuplicatesDepthInvalidUtf8BodyOverflowAndWrongScalarTypes() {
        val valid = SearchTestData.page().toString()
        val bodies = listOf(valid.replace("\"resultCount\":1", "\"resultCount\":1,\"resultCount\":1"),
            valid.replace("\"resultCount\":1", "\"resultCount\":1,\"result\\u0043ount\":1"),
            valid.replace("\"resultCount\":1", "\"resultCount\":\"1\""),
            valid.replace("\"resultCount\":1", "\"resultCount\":1.0"),
            valid.replace("\"resultCount\":1", "\"resultCount\":-1"),
            valid.replace("\"id\":\"one\"", "\"id\":\"\\uD800\""),
            valid.replace("\"hasAudio\":true", "\"hasAudio\":true,\"hasAudio\":false"),
            "[".repeat(17) + "0" + "]".repeat(17), " ".repeat(2 * 1024 * 1024 + 1))
        for (body in bodies) assertTrue(runCatching { SearchParser.page(body.toByteArray(), 25) }.isFailure)
        assertTrue(runCatching { SearchParser.page(byteArrayOf(0xc3.toByte(), 0x28), 25) }.isFailure)
    }

    @Test fun semanticErrorCodesMustMatchHttpStatusAndNeverTrustAmbiguousBodies() {
        val code = """{"code":"SEARCH_BUSY","error":"private/secret"}""".toByteArray()
        assertEquals(SearchErrorCode.SEARCH_BUSY, SearchParser.errorCode(code, 503))
        assertNull(SearchParser.errorCode(code, 400))
        assertNull(SearchParser.errorCode("""{"code":"SEARCH_BUSY","code":"SEARCH_UNAVAILABLE","error":"private/secret"}""".toByteArray(), 503))
        assertNull(SearchParser.errorCode(" ".repeat(8193).toByteArray(), 503))
    }
}
