package dev.spofify.mobile.playback

import androidx.lifecycle.ViewModelStore
import dev.spofify.mobile.core.content.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrackContentViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    private class Api : TrackContentApi {
        override val active = MutableStateFlow(true)
        override val isCurrent: Boolean get() = active.value
        val waveReads = mutableListOf<String>()
        val lyricReads = mutableListOf<String>()
        var readWave: suspend (String) -> TrackWaveform = { wave(it) }
        var readLyrics: suspend (String) -> TrackLyrics = { text(it) }
        override suspend fun waveform(trackId: String): TrackWaveform {
            waveReads.add(trackId)
            return readWave(trackId)
        }
        override suspend fun lyrics(trackId: String): TrackLyrics {
            lyricReads.add(trackId)
            return readLyrics(trackId)
        }
    }

    private fun model(api: Api) = TrackContentViewModel(api).also { store.put("content", it) }

    @Test fun fetchesOncePerActualOccurrenceNotClockRecompositionsAndClearsBeforeNextFetch() = runTest(dispatcher) {
        val api = Api()
        val model = model(api)
        model.select("one", "occurrence-1")
        repeat(20) { model.select("one", "occurrence-1") }
        assertTrue(model.state.value.loading)
        advanceUntilIdle()
        assertEquals(listOf("one"), api.waveReads)
        assertEquals(listOf("one"), api.lyricReads)
        assertEquals("one", model.state.value.waveform?.trackId)
        assertFalse(model.state.value.loading)
        model.select("one", "occurrence-2")
        assertNull(model.state.value.waveform)
        assertNull(model.state.value.lyrics)
        advanceUntilIdle()
        assertEquals(listOf("one", "one"), api.waveReads)
        assertEquals("occurrence-2", model.state.value.binding)
    }

    @Test fun lateResponsesFromSameTrackOldOccurrenceCannotPublishOrClearSuccessorLoading() = runTest(dispatcher) {
        val oldGate = CompletableDeferred<Unit>()
        val nextGate = CompletableDeferred<Unit>()
        val api = Api().apply {
            var waves = 0
            var lyrics = 0
            readWave = { id ->
                if (waves++ == 0) withContext(NonCancellable) { oldGate.await(); wave(id).copy(sourceGeneration = "old-source") }
                else { nextGate.await(); wave(id) }
            }
            readLyrics = { id ->
                if (lyrics++ == 0) withContext(NonCancellable) { oldGate.await(); text(id).copy(recordingVersion = "old-recording") }
                else { nextGate.await(); text(id) }
            }
        }
        val model = model(api)
        model.select("one", "old")
        runCurrent()
        model.select("one", "new")
        runCurrent()
        oldGate.complete(Unit)
        runCurrent()
        assertEquals("new", model.state.value.binding)
        assertTrue(model.state.value.loadingWaveform)
        assertTrue(model.state.value.loadingLyrics)
        assertNull(model.state.value.waveform)
        assertNull(model.state.value.lyrics)
        nextGate.complete(Unit)
        advanceUntilIdle()
        assertEquals("source", model.state.value.waveform?.sourceGeneration)
        assertEquals("recording", model.state.value.lyrics?.recordingVersion)
    }

    @Test fun selectNullClearsSynchronouslyAndLateNonCooperativeWorkCannotRestoreIt() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val api = Api().apply {
            readWave = { id -> withContext(NonCancellable) { gate.await(); wave(id) } }
            readLyrics = { id -> withContext(NonCancellable) { gate.await(); text(id) } }
        }
        val model = model(api)
        model.select("one", "occurrence")
        runCurrent()
        model.select(null, "obsolete")
        assertEquals(TrackContentState(), model.state.value)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(TrackContentState(), model.state.value)
        model.refresh()
        advanceUntilIdle()
        assertEquals(1, api.waveReads.size)
    }

    @Test fun retirementClearsContentCancelsRequestsAndNeverReusesDataForAnotherAccount() = runTest(dispatcher) {
        val api = Api()
        val model = model(api)
        model.select("one", "occurrence")
        advanceUntilIdle()
        assertNotNull(model.state.value.lyrics)
        val gate = CompletableDeferred<Unit>()
        api.readWave = { id -> withContext(NonCancellable) { gate.await(); wave(id) } }
        api.readLyrics = { id -> withContext(NonCancellable) { gate.await(); text(id) } }
        model.refresh()
        runCurrent()
        api.active.value = false
        runCurrent()
        assertEquals(TrackContentState(), model.state.value)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(TrackContentState(), model.state.value)
        api.active.value = true // account-bound owners do not revive after retirement
        model.select("one", "new-account")
        model.refresh()
        advanceUntilIdle()
        assertEquals(2, api.waveReads.size)
        assertEquals(TrackContentState(), model.state.value)
    }

    @Test fun documentedReadsProvideCurrentOccurrenceWaveformAndActualPositionLyricsWithoutExtraAuthority() = runTest(dispatcher) {
        val api = Api()
        val model = model(api)
        model.select("one", "occurrence")
        advanceUntilIdle()
        val state = model.state.value
        assertEquals("source", state.waveform?.sourceGeneration)
        assertEquals("product", state.waveform?.productVersion)
        assertEquals("recording", state.lyrics?.recordingVersion)
        assertSame(state.waveform, state.waveformForDuration(2.0))
        assertEquals("First", state.currentLine(500, 2000)?.text)
        assertEquals("", state.currentLine(1000, 2000)?.text)
        assertEquals(1, state.currentLineIndex(1000, 2000))
    }

    @Test fun durationMismatchPreservesOrdinarySeekAndUnknownLyricDurationStaysUnsynchronised() = runTest(dispatcher) {
        val model = model(Api())
        model.select("one", "occurrence")
        advanceUntilIdle()
        val state = model.state.value
        assertNotNull(state.waveformForDuration(2.25))
        assertNull(state.waveformForDuration(2.251))
        assertNull(state.waveformForDuration(Double.NaN))
        assertNull(state.waveformForDuration(0.0))
        for (duration in listOf(-1L, 0L, 999L, MAX_LYRIC_DURATION_MS + 1)) {
            assertNull(state.currentLine(500, duration))
            assertNull(state.currentLineIndex(500, duration))
        }
        // Unsynchronised is not missing: full authored text remains available for the sheet.
        assertEquals(listOf("First", ""), state.lyrics?.lines?.map { it.text })
        assertEquals("First", state.currentLine(500, 2000)?.text)
    }

    @Test fun sourceProductAndRecordingChangesWithinImmutableOccurrenceDiscardRatherThanRebind() = runTest(dispatcher) {
        for (change in listOf("wave-source", "wave-product", "lyric-source", "lyric-recording")) {
            val api = Api()
            val model = model(api)
            model.select("one", change)
            advanceUntilIdle()
            api.readWave = { id -> when (change) {
                "wave-source" -> wave(id).copy(sourceGeneration = "replacement")
                "wave-product" -> wave(id).copy(productVersion = "replacement")
                else -> wave(id)
            } }
            api.readLyrics = { id -> when (change) {
                "lyric-source" -> text(id).copy(sourceGeneration = "replacement")
                "lyric-recording" -> text(id).copy(recordingVersion = "replacement")
                else -> text(id)
            } }
            model.refresh()
            advanceUntilIdle()
            val state = model.state.value
            if (change.startsWith("wave")) {
                assertNull(state.waveform)
                assertNull(state.waveformForDuration(2.0))
                assertEquals(TrackContentFailure.INVALID_RESPONSE, state.waveformFailure)
                assertNotNull(state.lyrics)
            } else {
                assertNull(state.lyrics)
                assertNull(state.currentLine(500, 2000))
                assertEquals(TrackContentFailure.INVALID_RESPONSE, state.lyricsFailure)
                assertNotNull(state.waveform)
            }
            // A genuinely new occurrence gets its own namespace pins, not the predecessor's cache.
            model.select("one", "$change-next")
            advanceUntilIdle()
            assertNotNull(model.state.value.waveformForDuration(2.0))
            assertEquals("First", model.state.value.currentLine(500, 2000)?.text)
            assertNull(model.state.value.waveformFailure)
            assertNull(model.state.value.lyricsFailure)
        }
    }

    @Test fun plainFullTextIsAvailableWithoutDurationButNeverClaimsAHighlightedLine() = runTest(dispatcher) {
        val api = Api().apply {
            readLyrics = { id -> text(id).copy(state = LyricsState.PLAIN,
                plainText = "Plain authored\\ntext", lines = emptyList()) }
        }
        val model = model(api)
        model.select("one", "occurrence")
        advanceUntilIdle()
        val state = model.state.value
        assertEquals("Plain authored\\ntext", state.lyrics?.plainText)
        assertNull(state.lyricsFailure)
        assertNull(state.currentLine(500, 0))
        assertNull(state.currentLine(500, 2000))
        assertNull(state.currentLineIndex(500, 2000))
    }

    @Test fun pendingHasNoAutomaticPollingAndExplicitRefreshCannotMultiplyInflightReads() = runTest(dispatcher) {
        val api = Api().apply {
            readWave = { id -> wave(id).copy(state = WaveformState.RUNNING, frames = null,
                sampleRate = null, channels = null, durationSeconds = null, bins = emptyList()) }
            readLyrics = { id -> text(id).copy(state = LyricsState.PENDING, lines = emptyList()) }
        }
        val model = model(api)
        model.select("one", "occurrence")
        advanceUntilIdle()
        assertEquals(1, api.waveReads.size)
        assertEquals(LyricsState.PENDING, model.state.value.lyrics?.state)
        assertFalse(model.state.value.loading)
        val gate = CompletableDeferred<Unit>()
        api.readWave = { id -> gate.await(); wave(id) }
        api.readLyrics = { id -> gate.await(); text(id) }
        repeat(10) { model.refresh() }
        runCurrent()
        repeat(10) { model.refresh() }
        runCurrent()
        assertEquals(2, api.waveReads.size)
        assertEquals(2, api.lyricReads.size)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, api.waveReads.size)
    }

    @Test fun errorsAreSafeAndIndependentAndNeverPublishCredentialOrOperationalDiagnostics() = runTest(dispatcher) {
        val api = Api().apply {
            readWave = { throw IllegalStateException("/private/path cookie=secret") }
            readLyrics = { throw TrackContentException(TrackContentFailure.NOT_FOUND) }
        }
        val model = model(api)
        model.select("one", "occurrence")
        advanceUntilIdle()
        assertEquals(TrackContentFailure.INVALID_RESPONSE, model.state.value.waveformFailure)
        assertEquals(TrackContentFailure.NOT_FOUND, model.state.value.lyricsFailure)
        assertFalse(model.state.value.toString().contains("secret"))
        assertFalse(model.state.value.loading)
        api.readWave = { wave(it) }
        model.refresh()
        advanceUntilIdle()
        assertNotNull(model.state.value.waveform)
        assertNull(model.state.value.waveformFailure)
        assertEquals(TrackContentFailure.NOT_FOUND, model.state.value.lyricsFailure)
    }

    companion object {
        private fun wave(id: String) = TrackWaveform(id, WaveformState.READY, "source", "product",
            "peak-u8-two-pass-v1-half-up", 2000, 1000, 1, 2.0, List(2000) { 0 })
        private fun text(id: String) = TrackLyrics(id, LyricsState.TIMED, "recording", "local",
            lines = listOf(LyricLine(0, "First"), LyricLine(1000, "")),
            provenance = LyricProvenance.LOCAL_LRC,
            attribution = LyricAttribution("Library text", null, LyricOwnership.LIBRARY))
    }
}
