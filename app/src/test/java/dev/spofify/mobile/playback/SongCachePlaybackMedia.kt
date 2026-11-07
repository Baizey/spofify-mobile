package dev.spofify.mobile.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.Loader
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.util.reflector.Accessor
import org.robolectric.util.reflector.Direct
import org.robolectric.util.reflector.ForType
import org.robolectric.util.reflector.Reflector.reflector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Renderer-only variant of PreparedTransportMedia's bounded consuming PCM sink.
 * No source replacement: the production DefaultMediaSourceFactory, extractor, authenticated
 * data source, real player, session, listeners and clock execute unchanged. These observations
 * are synthetic PCM evidence, NOT installed-Android codec/sink/acoustic approval.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Implements(value = ExoPlayer.Builder::class, isInAndroidSdk = false)
class SongCachePlaybackMedia {
    @RealObject private lateinit var builder: ExoPlayer.Builder

    @Implementation
    fun setMediaSourceFactory(factory: MediaSource.Factory): ExoPlayer.Builder {
        check(factory is DefaultMediaSourceFactory) { "Cache regression must exercise the production source factory" }
        // Attach only an optional transfer observer to the actual production DataSource.
        // This can hold a real file/HTTP read without replacing authorization, bytes or errors.
        val sources = reflector(FactoryFields::class.java, factory).getDataSourceFactory()
        // Read-only capture of the SAME service-bound factory. Recovery setup can
        // fully consume real authorized HTTP through its exact cache/namespace.
        productionDataSources = sources
        factory.setDataSourceFactory(DataSource.Factory {
            sources.createDataSource().also { source -> transferListener?.let(source::addTransferListener) }
        })
        val direct = reflector(BuilderDirect::class.java, builder)

        direct.setMediaSourceFactory(factory) // Identity, not a delegating/test source.
        direct.setRenderersFactory(RenderersFactory { _, _, _, _, _ -> arrayOf(PcmSink()) })
        factories.incrementAndGet()
        return builder
    }

    @ForType(DefaultMediaSourceFactory::class)
    interface FactoryFields {
        @Accessor("dataSourceFactory") fun getDataSourceFactory(): DataSource.Factory
    }

    @ForType(ExoPlayer.Builder::class)
    interface BuilderDirect {
        @Direct fun setMediaSourceFactory(factory: MediaSource.Factory): ExoPlayer.Builder
        @Direct fun setRenderersFactory(factory: RenderersFactory): ExoPlayer.Builder
    }

    /** Observe the real executor's release completion, not onLoadCanceled: released=true
     * cancellation can notify before a blocking load actually exits. No load is replaced. */
    @Implements(value = Loader::class, isInAndroidSdk = false)
    class LoaderRelease {
        @RealObject private lateinit var loader: Loader

        @Implementation
        fun release(callback: Loader.ReleaseCallback?) {
            val completed = onLoaderRelease?.invoke()
            val direct = reflector(LoaderDirect::class.java, loader)
            if (completed == null) direct.release(callback)
            else direct.release(Loader.ReleaseCallback {
                try { callback?.onLoaderReleased() } finally { completed() }
            })
        }

        @ForType(Loader::class)
        interface LoaderDirect {
            @Direct fun release(callback: Loader.ReleaseCallback?)
        }
    }

    class PcmSink : BaseRenderer(C.TRACK_TYPE_AUDIO) {
        private val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
        private var ended = false
        private var sampleUs = Long.MIN_VALUE
        override fun getName() = "SongCacheSyntheticPcmSink"
        override fun supportsFormat(format: Format) = RendererCapabilities.create(
            if (format.sampleMimeType == MimeTypes.AUDIO_RAW) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE)
        override fun isReady() = isSourceReady || ended
        override fun isEnded() = ended
        override fun onPositionReset(positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean) {
            resets.incrementAndGet()
            ended = false
            sampleUs = Long.MIN_VALUE
        }
        override fun onEnabled(joining: Boolean, mayRenderStartOfStream: Boolean) { enables.incrementAndGet() }
        override fun onStarted() { starts.incrementAndGet() }
        override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
            // Identical bounded position-driven consumption to PreparedTransportMedia; never
            // drain the whole source during prepare, fabricate readiness, or mutate a player.
            var reads = 0
            while (!ended && sampleUs <= positionUs && reads++ < 100) {
                buffer.clear()
                when (readSource(formatHolder, buffer, 0)) {
                    C.RESULT_NOTHING_READ -> return
                    C.RESULT_BUFFER_READ -> {
                        if (buffer.isEndOfStream) ended = true else {
                            sampleUs = buffer.timeUs
                            if (state == STATE_STARTED) {
                                samples.incrementAndGet()
                                val data = buffer.data?.duplicate() ?: continue
                                data.flip()
                                // Each fixture is constant, distinct signed-16 LE PCM. Record
                                // actual consumed bytes, not MediaItem IDs/URLs or hit counters.
                                // Every captured 16-bit pair in this slice must match its marker;
                                // capture is sampled identity, not a whole-stream/no-mixing hash.
                                // Bound capture to 64 bytes per buffer and 32 distinct markers.
                                if (data.remaining() >= 2) {
                                    val lo = data.get().toInt() and 255
                                    val hi = data.get().toInt() and 255
                                    val marker = lo or (hi shl 8)
                                    var checked = 2
                                    while (data.remaining() >= 2 && checked < 64) {
                                        val next = (data.get().toInt() and 255) or ((data.get().toInt() and 255) shl 8)
                                        check(next == marker) { "Mixed/corrupt synthetic PCM consumed" }
                                        checked += 2
                                    }
                                    check(consumed.size < 32 || consumed.containsKey(marker))
                                    consumed.computeIfAbsent(marker) { AtomicLong() }.addAndGet(checked.toLong())
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        @Volatile var productionDataSources: DataSource.Factory? = null
        @Volatile var transferListener: TransferListener? = null
        @Volatile var onLoaderRelease: (() -> (() -> Unit))? = null
        val factories = AtomicLong()
        val samples = AtomicLong()
        val starts = AtomicLong()
        val enables = AtomicLong()
        val resets = AtomicLong()
        val consumed = ConcurrentHashMap<Int, AtomicLong>()
        fun bytes(marker: Int) = consumed[marker]?.get() ?: 0L
        fun reset() {
            productionDataSources = null
            transferListener = null
            onLoaderRelease = null
            factories.set(0); samples.set(0); starts.set(0); enables.set(0); resets.set(0)
            consumed.clear()
        }
    }
}
