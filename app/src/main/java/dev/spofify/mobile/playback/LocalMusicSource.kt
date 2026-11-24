package dev.spofify.mobile.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.WrappingMediaSource
import dev.spofify.mobile.core.cache.MusicSourceChoice
import dev.spofify.mobile.core.http.AudioStream
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException

/** A prepared local period cannot acquire HTTP, even on its first range or a same-track reopen. */
internal class LocalMusicRanges(
    private val installed: AudioSourceOwner,
    private val current: () -> Boolean,
    choice: (() -> Boolean) -> MusicSourceChoice,
) : Closeable {
    private fun active() = installed.job.isActive && current()
    private val choice = choice(::active)

    fun open(key: String?, position: Long): AudioStream {
        if (key != installed.key || !active()) throw InterruptedIOException("Local audio source retired")
        return try {
            choice.openLocal(installed.trackId, position, allowTemporary = { false },
                onChoice = { installed.downloadRetention = it }) ?: throw IOException("Song unavailable locally")
        } catch (e: IOException) { onReadFailure(); throw e }
    }

    fun onReadFailure() = installed.drainBufferedAudioIf(retired = !installed.verifiedDownload)

    override fun close() = choice.close()
}

@UnstableApi
internal fun localMusicSource(item: MediaItem, ranges: LocalMusicRanges): WrappingMediaSource {
    val factory = ProgressiveMediaSource.Factory(DataSource.Factory {
        RangeSource(false, onReadFailure = { ranges.onReadFailure() }) { spec -> ranges.open(spec.key, spec.position) }
    }).setLoadErrorHandlingPolicy(AudioLoadRecovery()) // Plain disk errors never request a retry.
    return object : WrappingMediaSource(factory.createMediaSource(item)) {
        override fun releaseSourceInternal() {
            try { super.releaseSourceInternal() } finally { ranges.close() }
        }
    }
}
