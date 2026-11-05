package dev.spofify.mobile.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import org.robolectric.util.reflector.Direct
import org.robolectric.util.reflector.ForType
import org.robolectric.util.reflector.Reflector.reflector
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Test-only source/sink substitution, NOT a fake Player or an acoustic assertion.
 * The production service builds its real ExoPlayer, attaches its own listeners and session,
 * and executes startOwned unchanged. ExoPlayer owns IDLE/BUFFERING/READY, play intent,
 * isPlaying, clock progress, stop/reprepare and listener delivery. Only PCM rendering and
 * the source of the samples are synthetic: Media3's SilenceMediaSource and a consuming sink.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Implements(value = ExoPlayer.Builder::class, isInAndroidSdk = false)
class PreparedTransportMedia {
    @RealObject private lateinit var builder: ExoPlayer.Builder

    @Implementation
    fun setMediaSourceFactory(ignored: MediaSource.Factory): ExoPlayer.Builder {
        val source = object : MediaSource.Factory {
            override fun setDrmSessionManagerProvider(value: DrmSessionManagerProvider) = this
            override fun setLoadErrorHandlingPolicy(value: LoadErrorHandlingPolicy) = this
            override fun getSupportedTypes() = intArrayOf(C.CONTENT_TYPE_OTHER)
            override fun createMediaSource(item: MediaItem): MediaSource =
                SilenceMediaSource(120_000_000).apply { updateMediaItem(item) }
        }
        val direct = reflector(BuilderDirect::class.java, builder)
        direct.setMediaSourceFactory(source)
        direct.setRenderersFactory(RenderersFactory { _, _, _, _, _ -> arrayOf(PcmSink()) })
        interceptions.incrementAndGet()
        return builder
    }

    @ForType(ExoPlayer.Builder::class)
    interface BuilderDirect {
        @Direct fun setMediaSourceFactory(factory: MediaSource.Factory): ExoPlayer.Builder
        @Direct fun setRenderersFactory(factory: RenderersFactory): ExoPlayer.Builder
    }

    class PcmSink : BaseRenderer(C.TRACK_TYPE_AUDIO) {
        private val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
        private var ended = false
        private var sampleUs = Long.MIN_VALUE
        override fun getName() = "PreparedTransportSyntheticPcmSink"
        override fun supportsFormat(format: Format) = RendererCapabilities.create(
            if (format.sampleMimeType == MimeTypes.AUDIO_RAW) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE)
        override fun isReady() = !holdReady.get() && (isSourceReady || ended)
        override fun isEnded() = ended
        override fun onPositionReset(positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean) {
            resets.incrementAndGet()
            ended = false; sampleUs = Long.MIN_VALUE
        }
        override fun onEnabled(joining: Boolean, mayRenderStartOfStream: Boolean) { enables.incrementAndGet() }
        override fun onStarted() { starts.incrementAndGet() }
        override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
            // Only a renderer/source-readiness gate: Media3 still owns BUFFERING/READY.
            if (holdReady.get()) return
            if (failNextRender.compareAndSet(true, false)) {
                rendererFailures.incrementAndGet()
                throw createRendererException(IOException("Synthetic PCM renderer failure"), null,
                    PlaybackException.ERROR_CODE_DECODING_FAILED)
            }
            // Consume only to the renderer position plus one bounded sample, never drain the
            // entire source to EOS at prepare. No custom clock or isPlaying mutation exists.
            var reads = 0
            while (!ended && sampleUs <= positionUs && reads++ < 100) {
                buffer.clear()
                when (readSource(formatHolder, buffer, 0)) {
                    C.RESULT_NOTHING_READ -> return
                    C.RESULT_BUFFER_READ -> {
                        if (buffer.isEndOfStream) ended = true
                        else {
                            sampleUs = buffer.timeUs
                            if (state == STATE_STARTED) samples.incrementAndGet()
                        }
                    }
                }
            }
        }
    }
    companion object {
        val samples = AtomicLong()
        val interceptions = AtomicLong()
        // Actual renderer callbacks only: these do not synthesize player readiness or starts.
        val enables = AtomicLong()
        val resets = AtomicLong()
        val starts = AtomicLong()
        // Opt-in faults default off. Owners reset both in finally before another test runs.
        val holdReady = AtomicBoolean(false)
        val failNextRender = AtomicBoolean(false)
        val rendererFailures = AtomicLong()
    }
}
