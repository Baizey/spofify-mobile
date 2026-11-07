package dev.spofify.mobile.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.ForwardingTimeline
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.util.reflector.Reflector.reflector
import java.util.concurrent.ConcurrentHashMap

/** Real ExoPlayer/service listeners. Only source duration discovery and PCM sink are
 * synthetic; no fake player, direct listener invocation, authority or audio/network claim. */
@androidx.annotation.OptIn(UnstableApi::class)
@Implements(value = ExoPlayer.Builder::class, isInAndroidSdk = false)
class RevisionTimelineMedia {
    @RealObject private lateinit var builder: ExoPlayer.Builder
    @Implementation fun setMediaSourceFactory(ignored: MediaSource.Factory): ExoPlayer.Builder {
        val factory = object : MediaSource.Factory {
            override fun setDrmSessionManagerProvider(value: DrmSessionManagerProvider) = this
            override fun setLoadErrorHandlingPolicy(value: LoadErrorHandlingPolicy) = this
            override fun getSupportedTypes() = intArrayOf(C.CONTENT_TYPE_OTHER)
            override fun createMediaSource(item: MediaItem): MediaSource = UpdatingSource(item).also {
                sources[requireNotNull(PlaybackMedia.occurrence(item))] = it
            }
        }
        val direct = reflector(PreparedTransportMedia.BuilderDirect::class.java, builder)
        direct.setMediaSourceFactory(factory)
        direct.setRenderersFactory(RenderersFactory { _, _, _, _, _ -> arrayOf(PreparedTransportMedia.PcmSink()) })
        return builder
    }
    class UpdatingSource(private val original: MediaItem) : WrappingMediaSource(
        SilenceMediaSource(120_000_000).apply { updateMediaItem(original) }) {
        @Volatile private var playbackHandler: Handler? = null
        private var child: Timeline? = null
        override fun getInitialTimeline(): Timeline = SinglePeriodTimeline(C.TIME_UNSET, true, false, false, null, original)
        override fun onChildSourceInfoRefreshed(newTimeline: Timeline) {
            playbackHandler = Handler(requireNotNull(Looper.myLooper()))
            child = newTimeline
            refreshSourceInfo(newTimeline)
        }
        /** Emit a legitimate SOURCE_UPDATE with changed item metadata, not just duration.
         * The source publishes on ExoPlayer's playback thread through its real caller. */
        fun publishMetadataChange(): Boolean = playbackHandler?.post {
            val item = original.buildUpon().setMediaMetadata(original.mediaMetadata.buildUpon().setTitle("Updated source metadata").build()).build()
            mediaSource.updateMediaItem(item)
            refreshSourceInfo(object : ForwardingTimeline(requireNotNull(child)) {
                override fun getWindow(index: Int, window: Timeline.Window, projectionUs: Long): Timeline.Window =
                    super.getWindow(index, window, projectionUs).also { it.mediaItem = item }
            })
        } ?: false
    }
    companion object { val sources = ConcurrentHashMap<String, UpdatingSource>() }
}
