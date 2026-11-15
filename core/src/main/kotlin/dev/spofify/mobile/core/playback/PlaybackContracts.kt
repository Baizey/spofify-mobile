package dev.spofify.mobile.core.playback

import java.io.Closeable
import kotlinx.coroutines.flow.Flow

/** Admission (including the credential-private hello) belongs to the transport. */
fun interface PlaybackTransport {
    suspend fun connect(name: String, deviceId: String): PlaybackConnection
}

/** messages completes or fails immediately on socket/auth loss. Never replay frames. */
interface PlaybackConnection : Closeable {
    val messages: Flow<String>
    val isOpen: Boolean
    fun send(text: String): Boolean
}

/** Every callback and sampled fact must identify the native operation it observes. */
data class AudioBinding(
    val instructionId: String,
    val outputVersion: Long,
    val eventId: String,
    val trackId: String?,
)

data class AudioFacts(
    val binding: AudioBinding? = null,
    val elapsed: Double = 0.0,
    val duration: Double = 0.0,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isLoading: Boolean = false,
    val error: String = "",
    val ended: Boolean = false,
)

/** ended=true is emitted only for an actual native media-ended callback, not a timer. */
data class AudioObservation(
    val binding: AudioBinding,
    val facts: AudioFacts,
    val seekCompleted: Boolean = false,
)

enum class AudioResult { OK, BLOCKED, MEDIA, REJECTED }

interface AudioExecutor {
    val observations: Flow<AudioObservation>
    suspend fun prepare(binding: AudioBinding, track: PlaybackTrack?, elapsed: Double): AudioResult
    /** Check isAuthorized before play AND after every suspended preparation step. */
    suspend fun execute(
        binding: AudioBinding,
        track: PlaybackTrack?,
        elapsed: Double,
        desiredPlaying: Boolean,
        isAuthorized: () -> Boolean,
    ): AudioResult
    /** Synchronously cancel pending starts and stop sound; preserve prepared media. */
    fun pause()
    fun facts(): AudioFacts
}

interface PlaybackClock {
    fun monotonicMs(): Long
    fun wallTimeMs(): Long
}

object SystemPlaybackClock : PlaybackClock {
    override fun monotonicMs(): Long = System.nanoTime() / 1_000_000
    override fun wallTimeMs(): Long = System.currentTimeMillis()
}
