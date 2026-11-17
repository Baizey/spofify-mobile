package dev.spofify.mobile.core.content

import kotlinx.coroutines.flow.Flow
import kotlin.math.abs
import kotlin.math.max

enum class WaveformState {
    READY, UNAVAILABLE, QUEUED, RUNNING, STALE, UNSUPPORTED, MISSING, ERROR, TIMEOUT, CANCELLED,
}

data class TrackWaveform(
    val trackId: String,
    val state: WaveformState,
    val sourceGeneration: String,
    val productVersion: String?,
    val algorithmVersion: String,
    val frames: Long? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val durationSeconds: Double? = null,
    val bins: List<Int> = emptyList(),
) {
    /** Use only for the current occurrence's version-pinned response; null preserves ordinary seek. */
    fun waveformForDuration(actualSeconds: Double): TrackWaveform? {
        val duration = durationSeconds ?: return null
        if (state != WaveformState.READY || bins.isEmpty() || !actualSeconds.isFinite() || actualSeconds <= 0) return null
        return takeIf { abs(actualSeconds - duration) <= max(0.25, duration / bins.size) }
    }
}

enum class LyricsState { PENDING, PLAIN, TIMED, INSTRUMENTAL, NOT_FOUND, UNAVAILABLE, ERROR }
enum class LyricProvenance { LOCAL_LRC, EMBEDDED, PROVIDER }
enum class LyricOwnership { LIBRARY, PROVIDER }
enum class LyricReason {
    SOURCE_NOT_CONFIGURED, LOCAL_INVALID, SOURCE_FAILED, NO_MATCH, SOURCE_DEFERRED,
    SOURCE_INVALID, RECORDING_UNAVAILABLE, AWAITING_PLAYBACK, AWAITING_PROCESSING,
}

data class LyricAttribution(val name: String, val url: String?, val ownership: LyricOwnership)
data class LyricLine(val startMs: Long, val text: String)

data class TrackLyrics(
    val trackId: String,
    val state: LyricsState,
    val recordingVersion: String,
    val sourceGeneration: String,
    val plainText: String = "",
    val lines: List<LyricLine> = emptyList(),
    val provenance: LyricProvenance? = null,
    val attribution: LyricAttribution? = null,
    val reason: LyricReason? = null,
    val storedAt: String? = null,
    val expiresAt: String? = null,
    val nextAttemptAt: String? = null,
) {
    // This resource never authorizes a durable/offline client copy.
    val offlineTransferAllowed: Boolean get() = false

    /** Actual-duration-gated upper-bound search: last equal timestamp wins, including an empty clearing line. */
    fun currentLine(actualElapsedMs: Long, actualDurationMs: Long): LyricLine? =
        currentLineIndex(actualElapsedMs, actualDurationMs)?.let(lines::get)

    fun currentLineIndex(actualElapsedMs: Long, actualDurationMs: Long): Int? {
        if (state != LyricsState.TIMED || actualElapsedMs < 0 || !withinDuration(actualDurationMs)) return null
        var low = 0
        var high = lines.size
        while (low < high) {
            val middle = low + (high - low) / 2
            if (lines[middle].startMs <= actualElapsedMs) low = middle + 1 else high = middle
        }
        return (low - 1).takeIf { it >= 0 }
    }

    /** Timing requires every line to fit a known, bounded actual media duration. */
    fun withinDuration(durationMs: Long): Boolean = durationMs in 1..MAX_LYRIC_DURATION_MS &&
        (state != LyricsState.TIMED || lines.lastOrNull()?.startMs?.let { it <= durationMs } == true)
}

const val MAX_LYRIC_DURATION_MS = 86_400_000L

enum class TrackContentFailure { UNAVAILABLE, NOT_FOUND, INVALID_RESPONSE }
class TrackContentException(val failure: TrackContentFailure) : Exception("Track content unavailable")

/** One account/session incarnation, with no persistent or cross-account content cache. */
interface TrackContentApi {
    val active: Flow<Boolean>
    val isCurrent: Boolean
    suspend fun waveform(trackId: String): TrackWaveform
    suspend fun lyrics(trackId: String): TrackLyrics
}
