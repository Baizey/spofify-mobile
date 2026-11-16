package dev.spofify.mobile.core.offline

import org.junit.Assert.*
import org.junit.Test

class OfflineParserTest {
    private fun page(body: String = OfflineTestData.page()) = OfflineParser.page(body.toByteArray(), OfflineTestData.playlist, 0, 2)
    private fun media(body: String = OfflineTestData.media(), id: String = "one") = OfflineParser.media(body.toByteArray(), id)
    private fun rejects(block: () -> Any?) { assertTrue(runCatching(block).isFailure) }

    @Test fun manifestProjectsPositiveTracksAndScopedOccurrencesWithoutPlaybackTokens() {
        val result = page(OfflineTestData.page(ids = listOf("one", "one")))
        assertEquals(OfflinePlaylistKind.MANUAL, result.collection.playlistKind)
        assertEquals(listOf("one", "one"), result.items.map { it.track.id })
        assertEquals(listOf(0L, 1L), result.items.map { it.position })
        assertEquals(2, result.items.map { it.occurrenceId }.distinct().size)
        assertTrue(result.items.all { it.track.hasAudio })
        val album = OfflineCollectionRef(OfflineCollectionKind.ALBUM, "album")
        val parsed = OfflineParser.page(OfflineTestData.page(album).toByteArray(), album, 0, 2)
        assertNull(parsed.collection.playlistKind)
        assertNull(parsed.collection.revision)
        assertEquals(album, parsed.collection.reference)
    }

    @Test fun malformedTypesCountsEligibilityPositionsAndWrongVariantFailWholePage() {
        val valid = OfflineTestData.page()
        for (body in listOf(
            valid.replace("\"version\":1", "\"version\":2"),
            valid.replace("\"version\":1", "\"version\":\"1\""),
            valid.replace("\"recordedAudioTotal\":1", "\"recordedAudioTotal\":0"),
            valid.replace("\"limit\":2", "\"limit\":1"),
            valid.replace("\"hasAudio\":true", "\"hasAudio\":false"),
            valid.replace("\"position\":0", "\"position\":1"),
            valid.replace("\"position\":0", "\"position\":0.0"),
            valid.replace("\"durationMs\":2000", "\"durationMs\":\"2000\""),
            valid.replace("\"durationMs\":2000", "\"durationMs\":-1"),
            valid.replace("\"kind\":\"manual\"", "\"kind\":\"tracked\""),
            valid.replace("\"fence\":\"fence-a\"", "\"fence\":\"\""),
            valid.replace("\"nextCursor\":null", "\"nextCursor\":\"of1_more\""),
            valid.replace("\"track\":{", "\"track\":{\"libraryPath\":\"private-path\","),
            valid.replace("\"revision\":1", "\"revision\":1,\"revi\\u0073ion\":1"),
            valid.replace("Synthetic song", "\\ud800"),
            valid.replace("\"playlist\":", "\"collection\":"),
        )) rejects { page(body) }
        rejects { page(" ".repeat(OfflineParser.MAX_PAGE_BYTES + 1)) }
        rejects { OfflineParser.page(byteArrayOf(0xc3.toByte(), 0x28), OfflineTestData.playlist, 0, 2) }
        rejects { page(OfflineTestData.page(total = 2)) } // terminal prefix must not masquerade as complete
        rejects { page(OfflineTestData.page(ids = emptyList(), total = 2, next = "of1_more")) }
        rejects { page(OfflineTestData.page(total = 2, next = "ofc1_foreign")) }
    }

    @Test fun canonicalRelativeMediaDescriptorHasExactHashVersionMimeAndPositiveInt64Length() {
        val descriptor = media()
        assertEquals("sha256:${OfflineTestData.hash}", descriptor.mediaVersion)
        assertEquals(123L, descriptor.byteLength)
        assertEquals("audio/wav", descriptor.contentType)
        assertFalse(descriptor.downloadPath.startsWith("http"))
        val id = "a+日本:%"
        assertTrue(media(OfflineTestData.media(id, "a+%E6%97%A5%E6%9C%AC:%25"), id).downloadPath.contains("%25"))
        assertEquals(Long.MAX_VALUE, media(OfflineTestData.media().replace("\"byteLength\":123", "\"byteLength\":9223372036854775807")).byteLength)
    }

    @Test fun arbitraryUrlAuthTargetsAliasesAndInvalidRepresentationsAreRejected() {
        val valid = OfflineTestData.media()
        val path = "/api/me/offline/v1/tracks/one/audio?version=sha256%3A${OfflineTestData.hash}"
        for (replacement in listOf("https://other.test$path", "//other.test$path", "$path&extra=1", "$path#fragment",
            path.replace("/one/", "/other/"), path.replace("%3A", "%3a"), path.replace("/audio?", "/%61udio?"),
            "/api/library/tracks/one/audio")) rejects { media(valid.replace(path, replacement)) }
        for (body in listOf(
            valid.replace("\"trackId\":\"one\"", "\"trackId\":\"other\""),
            valid.replace("sha256:", "sha512:"),
            valid.replace("\"byteLength\":123", "\"byteLength\":0"),
            valid.replace("\"byteLength\":123", "\"byteLength\":123.0"),
            valid.replace("\"byteLength\":123", "\"byteLength\":\"123\""),
            valid.replace("\"format\":\"wav\"", "\"format\":\"mp3\""),
            valid.replace("audio/wav", "audio/wav; charset=utf-8"),
            valid.replace("\"format\":\"wav\"", "\"format\":\"unknown\""),
        )) rejects { media(body) }
    }

    @Test fun receiptPayloadIsImmutableValidatedEvidenceWithNoAgeOrJavascriptIntegerCutoff() {
        val receipt = OfflineTestData.receipt
        assertEquals(receipt, receipt.copy())
        assertEquals("offline", receipt.origin)
        assertEquals(1000L, OfflinePlayReceipt(".", "one", 1000, 1000, 1000).occurredAtMs)
        assertEquals(Long.MAX_VALUE, receipt.copy(occurredAtMs = Long.MAX_VALUE, elapsedMs = Long.MAX_VALUE).elapsedMs)
        for (invalid in listOf<() -> Any?>(
            { receipt.copy(eventId = "") }, { receipt.copy(eventId = "x".repeat(129)) },
            { receipt.copy(eventId = " leading") }, { receipt.copy(trackId = "one\n") },
            { receipt.copy(eventId = "\uD800") }, { receipt.copy(origin = "online") },
            { receipt.copy(elapsedMs = 999) }, { receipt.copy(progressedMs = 999) },
            { receipt.copy(progressedMs = 1501) }, { receipt.copy(occurredAtMs = 1499) },
        )) rejects(invalid)
    }

    @Test fun onlyMatchingTypedCommittedAckCanRetireAnOutboxEntry() {
        val receipt = OfflineTestData.receipt
        assertFalse(OfflineParser.ack(OfflineTestData.ack().toByteArray(), receipt).duplicate)
        assertTrue(OfflineParser.ack(OfflineTestData.ack(duplicate = true).toByteArray(), receipt).duplicate)
        // A duplicate's original server time can precede the occurrence after clock skew/rollback.
        assertEquals(0L, OfflineParser.ack(OfflineTestData.ack().replace("11000", "0").toByteArray(), receipt).receivedAtMs)
        for (body in listOf(OfflineTestData.ack("other"), OfflineTestData.ack().replace("false", "\"false\""),
            OfflineTestData.ack().replace("11000", "-1"), OfflineTestData.ack().replace("11000", "11000.0"),
            OfflineTestData.ack().replace("\"duplicate\":false", "\"duplicate\":false,\"duplicate\":true")))
            rejects { OfflineParser.ack(body.toByteArray(), receipt) }
    }
}
