package dev.spofify.mobile.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.spofify.mobile.core.content.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Current account/occurrence presentation data; timing uses actual media duration and position. */
data class TrackContentState(
    val trackId: String? = null,
    val binding: String = "",
    val waveform: TrackWaveform? = null,
    val lyrics: TrackLyrics? = null,
    val loadingWaveform: Boolean = false,
    val loadingLyrics: Boolean = false,
    val waveformFailure: TrackContentFailure? = null,
    val lyricsFailure: TrackContentFailure? = null,
) {
    val loading: Boolean get() = loadingWaveform || loadingLyrics

    /** Null means use the ordinary accessible seek, not invented silence. */
    fun waveformForDuration(actualSeconds: Double): TrackWaveform? =
        waveform?.waveformForDuration(actualSeconds)

    fun currentLine(actualElapsedMs: Long, actualDurationMs: Long): LyricLine? =
        lyrics?.currentLine(actualElapsedMs, actualDurationMs)

    fun currentLineIndex(actualElapsedMs: Long, actualDurationMs: Long): Int? =
        lyrics?.currentLineIndex(actualElapsedMs, actualDurationMs)
}

/**
 * One account-bound API, one immutable current occurrence. No per-row reads, persistence,
 * automatic polling or offline fetches. Dispose this owner when retiring the account.
 *
 * Ordinary audio exposes no matching incarnation: a same-duration media replacement
 * between stream-open and the FIRST metadata read cannot be independently detected.
 * Occurrence/version fences do not establish cross-response atomicity.
 */
class TrackContentViewModel(private val api: TrackContentApi, private val local: Boolean = false) : ViewModel() {
    private val mutable = MutableStateFlow(TrackContentState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var waveformRequest: Job? = null
    private var lyricsRequest: Job? = null
    private var retired = false
    private var waveformSource: String? = null
    private var waveformProduct: String? = null
    private var lyricRecording: String? = null

    init {
        viewModelScope.launch {
            api.active.collect { active -> if (!active) retire() }
        }
    }

    /** binding must change for occurrence, output, mode, or recording changes, not clock ticks. */
    fun select(trackId: String?, binding: String) {
        if (!api.isCurrent || retired) { retire(); return }
        if (trackId != null && mutable.value.trackId == trackId && mutable.value.binding == binding) return
        cancelRequests()
        waveformSource = null
        waveformProduct = null
        lyricRecording = null
        // Clearing happens synchronously, before a successor job can start.
        mutable.value = TrackContentState(trackId = trackId, binding = if (trackId == null) "" else binding)
        if (trackId != null) fetch()
    }

    /** Explicit content re-read only. This never starts a fetching/import job. */
    fun refresh() {
        if (!api.isCurrent || retired) { retire(); return }
        if (mutable.value.trackId == null || waveformRequest?.isActive == true || lyricsRequest?.isActive == true) return
        fetch()
    }

    private fun fetch() {
        val trackId = mutable.value.trackId ?: return
        val token = ++generation
        val binding = mutable.value.binding
        mutable.value = mutable.value.copy(loadingWaveform = !local, loadingLyrics = !local,
            waveformFailure = null, lyricsFailure = null)
        waveformRequest = viewModelScope.launch {
            try {
                if (!current(token, trackId, binding)) return@launch
                val result = api.waveform(trackId)
                if (!current(token, trackId, binding)) return@launch
                check(result.trackId == trackId)
                check(waveformSource == null || result.sourceGeneration == waveformSource)
                check(waveformProduct == null || result.productVersion == waveformProduct)
                if (result.sourceGeneration.isNotEmpty()) waveformSource = result.sourceGeneration
                result.productVersion?.let { waveformProduct = it }
                mutable.value = mutable.value.copy(waveform = result, loadingWaveform = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (current(token, trackId, binding)) mutable.value = mutable.value.copy(
                    waveform = null, loadingWaveform = false,
                    waveformFailure = failure.safeFailure())
            }
        }
        lyricsRequest = viewModelScope.launch {
            try {
                if (!current(token, trackId, binding)) return@launch
                val result = api.lyrics(trackId)
                if (!current(token, trackId, binding)) return@launch
                check(result.trackId == trackId)
                check(lyricRecording == null || lyricRecording == result.recordingVersion)
                // Jobs may add or replace lyrics without replacing the audio.
                // Pin the recording, not the lyric source or its cache history.
                lyricRecording = result.recordingVersion
                mutable.value = mutable.value.copy(lyrics = result, loadingLyrics = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (current(token, trackId, binding)) mutable.value = mutable.value.copy(
                    lyrics = null, loadingLyrics = false,
                    lyricsFailure = failure.safeFailure())
            }
        }
    }

    private fun current(token: Long, trackId: String, binding: String): Boolean {
        if (!api.isCurrent) { retire(); return false }
        return !retired && generation == token && mutable.value.trackId == trackId && mutable.value.binding == binding
    }

    private fun retire() {
        retired = true
        cancelRequests()
        waveformSource = null
        waveformProduct = null
        lyricRecording = null
        mutable.value = TrackContentState()
    }

    private fun cancelRequests() {
        generation++
        waveformRequest?.cancel()
        lyricsRequest?.cancel()
        waveformRequest = null
        lyricsRequest = null
    }

    private fun Exception.safeFailure(): TrackContentFailure =
        (this as? TrackContentException)?.failure ?: TrackContentFailure.INVALID_RESPONSE
}
