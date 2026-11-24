package dev.spofify.mobile.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import dev.spofify.mobile.core.auth.AccountIdentity
import dev.spofify.mobile.core.auth.SessionRepository

/** The app's opaque audio IDs are progressive media, never playlists. Keep the representation
 * decision on its prepared source: Media3 closes/reopens individual DataSpecs on seek and EOF.
 */
@UnstableApi
internal class SessionMusicSourceFactory(private val sessions: SessionRepository, private val identity: AccountIdentity,
    private val cache: AutomaticMusicCache, private val currentSource: () -> AudioSourceOwner?) : MediaSource.Factory {
    private var drm: DrmSessionManagerProvider? = null
    private var loadPolicy: LoadErrorHandlingPolicy? = null
    override fun getSupportedTypes() = intArrayOf(C.CONTENT_TYPE_OTHER)
    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider) = apply { drm = provider }
    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy) = apply { loadPolicy = policy }

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val grant = sessions.onlineGrant.value?.takeIf { it.identity == identity }
        // Verified downloads keep account authority across grants. SessionAudioDataSource
        // separately fences all HTTP and AUTO-only disk bytes for this exact grant.
        val allowed = { sessions.isCurrent(identity) }
        var ranges = cache.onlineRanges(allowed)
        val factory = ProgressiveMediaSource.Factory(DataSource.Factory { SessionAudioDataSource(sessions, grant, ranges, currentSource) })
        drm?.let(factory::setDrmSessionManagerProvider)
        loadPolicy?.let(factory::setLoadErrorHandlingPolicy)
        return object : WrappingMediaSource(factory.createMediaSource(mediaItem)) {
            override fun releaseSourceInternal() {
                try { super.releaseSourceInternal() } finally {
                    ranges.close() // Old DataSources remain fenced even if this source is prepared again.
                    ranges = cache.onlineRanges(allowed)
                }
            }
        }
    }
}
