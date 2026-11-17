package dev.spofify.mobile.core.content

import org.junit.Assert.*
import org.junit.Test

class TrackContentParserTest {
    private fun waveform(body: String = WAVEFORM, id: String = "one") =
        TrackContentParser.waveform(body.toByteArray(), id)
    private fun lyrics(body: String = LYRICS, id: String = "one") =
        TrackContentParser.lyrics(body.toByteArray(), id)

    private fun rejected(block: () -> Any?) {
        val error = runCatching(block).exceptionOrNull()
        assertTrue("Expected sanitized rejection, got $error", error is TrackContentException)
        assertEquals(TrackContentFailure.INVALID_RESPONSE, (error as TrackContentException).failure)
        assertEquals("Track content unavailable", error.message)
        assertNull(error.cause)
    }

    @Test fun numericPeakEnvelopePreservesSilenceAndChannelsWithoutAutoscaling() {
        val ready = waveform()
        assertEquals(listOf(0, 128, 255, 64), ready.bins)
        assertEquals(2, ready.channels)
        assertEquals(4L, ready.frames)
        assertEquals("source-a", ready.sourceGeneration)
        assertEquals("product-a", ready.productVersion)
        assertEquals(listOf(0, 0, 0, 0), waveform(WAVEFORM.replace("[0,128,255,64]", "[0,0,0,0]")).bins)
    }

    @Test fun durationToleranceUsesLargerOfQuarterSecondAndOneBinAndRejectsUnknownDurations() {
        val short = waveform()
        assertSame(short, short.waveformForDuration(short.durationSeconds!! + 0.25))
        assertNull(short.waveformForDuration(short.durationSeconds!! + 0.250001))
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach {
            assertNull(short.waveformForDuration(it))
        }
        val slow = short.copy(frames = 4, sampleRate = 1, durationSeconds = 4.0)
        assertSame(slow, slow.waveformForDuration(5.0))
        assertNull(slow.waveformForDuration(5.0001))
        assertNull(short.copy(state = WaveformState.STALE).waveformForDuration(1.0))
    }

    @Test fun everyReadyNumericAndBindingFieldIsValidatedAsAWhole() {
        val invalid = listOf(
            WAVEFORM.replace("\"version\":1", "\"version\":2"),
            WAVEFORM.replace("\"version\":1", "\"version\":1.0"),
            WAVEFORM.replace("\"trackId\":\"one\"", "\"trackId\":\"other\""),
            WAVEFORM.replace("ready", "invented"),
            WAVEFORM.replace("source-a", ""),
            WAVEFORM.replace("product-a", ""),
            WAVEFORM.replace("peak-u8-two-pass-v1-half-up", "new-algorithm"),
            WAVEFORM.replace("\"frames\":4", "\"frames\":0"),
            WAVEFORM.replace("\"frames\":4", "\"frames\":4.0"),
            WAVEFORM.replace("\"frames\":4", "\"frames\":9223372036854775808"),
            WAVEFORM.replace("\"sampleRate\":48000", "\"sampleRate\":0"),
            WAVEFORM.replace("\"sampleRate\":48000", "\"sampleRate\":192001"),
            WAVEFORM.replace("\"channels\":2", "\"channels\":3"),
            WAVEFORM.replace("0.00008333333333333333", "0.1"),
            WAVEFORM.replace("0.00008333333333333333", "1e999"),
            WAVEFORM.replace("[0,128,255,64]", "[0,128,256,64]"),
            WAVEFORM.replace("[0,128,255,64]", "[0,128,-1,64]"),
            WAVEFORM.replace("[0,128,255,64]", "[0,128,255.0,64]"),
            WAVEFORM.replace("[0,128,255,64]", "[0,128,255]"),
            WAVEFORM.replace("[0,128,255,64]", "\"AID/QA==\""),
            WAVEFORM.replace("\"productVersion\":\"product-a\",", ""),
        )
        invalid.forEach { body -> rejected { waveform(body) } }
        rejected { waveform(id = "other") }
    }

    @Test fun fullLengthBinCountIs2048AndDecodedDurationIsBounded() {
        val bins = List(2048) { 0 }.joinToString(",", "[", "]")
        val full = WAVEFORM.replace("\"frames\":4", "\"frames\":345600000")
            .replace("0.00008333333333333333", "7200")
            .replace("[0,128,255,64]", bins)
        assertEquals(2048, waveform(full).bins.size)
        rejected { waveform(full.replace("345600000", "345600001")) }
        rejected { waveform(full.replace(bins, List(2047) { 0 }.joinToString(",", "[", "]"))) }
    }

    @Test fun safeWaveformStatesNeverCarryPartialEnvelopeAndUnknownStatesFail() {
        val base = """{"version":1,"trackId":"one","state":"unavailable","sourceGeneration":"","algorithmVersion":"peak-u8-two-pass-v1-half-up"}"""
        WaveformState.entries.filter { it != WaveformState.READY }.forEach { state ->
            val body = base.replace("unavailable", state.name.lowercase())
            assertEquals(state, waveform(body).state)
            rejected { waveform(body.dropLast(1) + ",\"bins\":[]}") }
            rejected { waveform(body.dropLast(1) + ",\"durationSeconds\":null}") }
        }
    }

    @Test fun malformedUtf8OversizeUnknownDuplicateAndInvalidEscapedUnicodeAreRejected() {
        rejected { TrackContentParser.waveform(byteArrayOf(0xC3.toByte(), 0x28), "one") }
        rejected { waveform(" ".repeat(16 * 1024 + 1)) }
        rejected { lyrics(" ".repeat(260 * 1024 + 1)) }
        rejected { waveform(WAVEFORM.dropLast(1) + ",\"path\":\"operational-private\"}") }
        rejected { waveform(WAVEFORM.replace("\"version\":1", "\"version\":2,\"version\":1")) }
        rejected { waveform(WAVEFORM.replace("\"version\":1", "\"version\":2,\"vers\\u0069on\":1")) }
        rejected { lyrics(LYRICS.replace("First", "\\ud800")) }
        rejected { lyrics(LYRICS.replace("First", "\\u0000")) }
        rejected { lyrics(LYRICS.replace("First", "\\u007f")) }
    }

    @Test fun binarySearchUsesActualPositionLastEqualAndEmptyLineClears() {
        val timed = lyrics()
        assertNull(timed.currentLine(-1, 2000))
        assertNull(timed.currentLine(99, 2000))
        assertEquals("First", timed.currentLine(100, 2000)?.text)
        assertEquals("First", timed.currentLine(999, 2000)?.text)
        assertEquals("", timed.currentLine(1000, 2000)?.text)
        assertEquals(2, timed.currentLineIndex(1000, 2000))
        assertEquals("Last", timed.currentLine(2000, 2000)?.text)
        for (duration in listOf(-1L, 0L, 1999L, MAX_LYRIC_DURATION_MS + 1)) {
            assertNull(timed.currentLine(100, duration))
            assertNull(timed.currentLineIndex(100, duration))
        }
        assertFalse(timed.withinDuration(1999))
        assertTrue(timed.withinDuration(2000))
        assertFalse(timed.withinDuration(0))
        assertFalse(timed.offlineTransferAllowed)
    }

    @Test fun plainTextNeverInventsTimingAndMissingDoesNotBecomeInstrumental() {
        val plain = lyrics(LYRICS.replace("\"state\":\"timed\"", "\"state\":\"plain\"")
            .replace(LINES, "[]").replace("\"lines\":[]", "\"lines\":[],\"plainText\":\"Plain\\ntext\""))
        assertEquals("Plain\ntext", plain.plainText)
        assertNull(plain.currentLine(500, 2000))
        assertNull(plain.currentLineIndex(500, 2000))
        assertNull(plain.currentLine(500, 0))
        val absent = lyrics(MISSING)
        assertEquals(LyricsState.UNAVAILABLE, absent.state)
        assertNull(absent.attribution)
        assertEquals(LyricReason.SOURCE_NOT_CONFIGURED, absent.reason)
        rejected { lyrics(MISSING.replace("unavailable", "plain")) }
        rejected { lyrics(LYRICS.replace("timed", "plain")) }
        rejected { lyrics(MISSING.replace("\"lines\":[]", "\"lines\":[],\"plainText\":\"text\"")) }
    }

    @Test fun lyricLimitsAndOrderingAndRequiredFieldsAreStrict() {
        val invalid = listOf(
            LYRICS.replace("\"version\":1", "\"version\":2"),
            LYRICS.replace("\"trackId\":\"one\"", "\"trackId\":\"other\""),
            LYRICS.replace("timed", "word_timed"),
            LYRICS.replace("recording-a", ""),
            LYRICS.replace("local-v1", ""),
            LYRICS.replace("local_lrc", "unknown"),
            LYRICS.replace("\"startMs\":100", "\"startMs\":-1"),
            LYRICS.replace("\"startMs\":2000", "\"startMs\":999"),
            LYRICS.replace("\"startMs\":2000", "\"startMs\":86400001"),
            LYRICS.replace("\"startMs\":2000", "\"startMs\":2000.5"),
            LYRICS.replace("First", "x".repeat(4097)),
            LYRICS.replace("First", "日".repeat(1366)),
            LYRICS.replace("First", "").replace("Equal", "").replace("Last", ""),
            LYRICS.replace(LINES, "[]"),
            LYRICS.replace(LINES, List(2049) { "{\"startMs\":0,\"text\":\"x\"}" }.joinToString(",", "[", "]")),
            LYRICS.replace(LINES, List(17) { "{\"startMs\":0,\"text\":\"${"x".repeat(4096)}\"}" }.joinToString(",", "[", "]")),
            LYRICS.replace("\"offlineTransferAllowed\":false", "\"offlineTransferAllowed\":true"),
            LYRICS.replace("\"offlineTransferAllowed\":false,", ""),
            LYRICS.replace("\"lines\":$LINES", "\"lines\":null"),
        )
        invalid.forEach { body -> rejected { lyrics(body) } }
    }

    @Test fun attributionIsPlainTextWithStrictHttpsAndOwnership() {
        val linked = LYRICS.replace("\"ownership\":\"library\"", "\"ownership\":\"library\",\"url\":\"https://example.test/credits\"")
        assertEquals("https://example.test/credits", lyrics(linked).attribution?.url)
        for (url in listOf("http://example.test/", "https://user:password@example.test/", "https://example.test/?q=secret",
            "https://example.test/?", "https://example.test/#", "https://example.test/#fragment", "javascript:alert(1)",
            "https:///missing-host", "https://example.test:99999/", "https://example.test/\\n")) {
            rejected { lyrics(linked.replace("https://example.test/credits", url)) }
        }
        assertEquals(LyricOwnership.PROVIDER, lyrics(linked.replace("\"ownership\":\"library\"", "\"ownership\":\"provider\"")).attribution?.ownership)
        rejected { lyrics(LYRICS.replace("Library text", "")) }
        rejected { lyrics(LYRICS.replace(",\"attribution\":{\"name\":\"Library text\",\"ownership\":\"library\"}", "")) }
    }

    @Test fun historicalCacheDatesAreValidatedButDoNotDecideAvailability() {
        val provider = LYRICS.replace("local_lrc", "provider").replace("\"ownership\":\"library\"", "\"ownership\":\"provider\"")
            .dropLast(1) + ",\"storedAt\":\"2025-01-01T00:00:00Z\",\"expiresAt\":\"2027-01-01T00:00:00Z\"}"
        assertEquals(LyricProvenance.PROVIDER, lyrics(provider).provenance)
        assertEquals(LyricsState.TIMED, lyrics(provider.replace("2027-01-01", "2026-01-01")).state)
        rejected { lyrics(provider.replace("2027-01-01", "2024-01-01")) }
        rejected { lyrics(provider.replace("2027-01-01T00:00:00Z", "2027-01-01T00:00:00+00:00")) }
        rejected { lyrics(MISSING.dropLast(1) + ",\"reason\":\"raw operational failure\"}") }
        rejected { lyrics(MISSING.dropLast(1) + ",\"nextAttemptAt\":\"tomorrow\"}") }
    }

    @Test fun physicalFileLyricsKeepProviderCreditsWithoutRequiringCacheFreshness() {
        for (origin in listOf("local_lrc", "embedded")) {
            val file = LYRICS.replace("local_lrc", origin)
                .replace("Library text", "LRCLIB").replace("\"ownership\":\"library\"", "\"ownership\":\"provider\"")
            val result = lyrics(file)
            assertEquals(LyricsState.TIMED, result.state)
            assertEquals("local-v1", result.sourceGeneration)
            assertEquals("LRCLIB", result.attribution?.name)
            assertEquals(LyricOwnership.PROVIDER, result.attribution?.ownership)
            assertNull(result.expiresAt)
            val expiredMetadata = file.dropLast(1) + ",\"storedAt\":\"2019-01-01T00:00:00Z\",\"expiresAt\":\"2020-01-01T00:00:00Z\"}"
            assertEquals(result.lines, lyrics(expiredMetadata).lines)
            val plain = file.replace("\"state\":\"timed\"", "\"state\":\"plain\"")
                .replace(LINES, "[]").replace("\"lines\":[]", "\"lines\":[],\"plainText\":\"Saved plain text\"")
            assertEquals("Saved plain text", lyrics(plain).plainText)
        }
    }

    @Test fun olderServerAwaitingProcessingIsAnOrdinaryUnavailableResponse() {
        val pending = lyrics(MISSING.replace("source_not_configured", "awaiting_processing"))
        assertEquals(LyricsState.UNAVAILABLE, pending.state)
        assertEquals(LyricReason.AWAITING_PROCESSING, pending.reason)
        assertTrue(pending.lines.isEmpty())
    }

    companion object {
        private const val WAVEFORM = """{"version":1,"trackId":"one","state":"ready","sourceGeneration":"source-a","algorithmVersion":"peak-u8-two-pass-v1-half-up","productVersion":"product-a","frames":4,"sampleRate":48000,"channels":2,"durationSeconds":0.00008333333333333333,"bins":[0,128,255,64]}"""
        private const val LINES = """[{"startMs":100,"text":"First"},{"startMs":1000,"text":"Equal"},{"startMs":1000,"text":""},{"startMs":2000,"text":"Last"}]"""
        private const val LYRICS = """{"version":1,"trackId":"one","recordingVersion":"recording-a","sourceGeneration":"local-v1","state":"timed","offlineTransferAllowed":false,"lines":$LINES,"provenance":"local_lrc","attribution":{"name":"Library text","ownership":"library"}}"""
        private const val MISSING = """{"version":1,"trackId":"one","recordingVersion":"recording-a","sourceGeneration":"source-disabled-v1","state":"unavailable","offlineTransferAllowed":false,"lines":[],"reason":"source_not_configured"}"""
    }
}
