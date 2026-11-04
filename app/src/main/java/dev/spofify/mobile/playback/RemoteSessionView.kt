package dev.spofify.mobile.playback

import android.os.Bundle
import androidx.media3.common.AdPlaybackState
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import dev.spofify.mobile.core.PlaybackSnapshot
import kotlin.math.roundToLong

/**
 * In-memory session display only: no output, audio configuration, network or authority.
 * Queue indices are the already-realized wire traversal, NOT the local output's physical indices.
 * The caller selects this view only while remote output is selected and owns all transport/events.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class RemoteSessionView(snapshot: PlaybackSnapshot) {
    // Own list containers; a later caller-side edit must not change this sampled projection.
    val snapshot: PlaybackSnapshot = snapshot.validated().let { state ->
        state.copy(queue = state.queue.map { it.copy(artists = it.artists.toList()) })
    }
    val currentIndex: Int = this.snapshot.position
    val durationMs: Long = if (currentIndex == C.INDEX_UNSET) C.TIME_UNSET else
        secondsToDurationMs(this.snapshot.duration).takeUnless { it == C.TIME_UNSET }
            ?: secondsToDurationMs(this.snapshot.queue[currentIndex].duration)
    val positionMs: Long = if (currentIndex == C.INDEX_UNSET) 0 else
        secondsToElapsedMs(this.snapshot.elapsed, durationMs)

    val mediaItems: List<MediaItem> = this.snapshot.queue.map { track ->
        MediaItem.Builder()
            .setMediaId(track.id)
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle(track.name)
                .setArtist(track.artists.joinToString(", ") { it.name })
                .setAlbumTitle(track.albumName)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                // Only public occurrence identity, never context/fence, URLs or credentials.
                .setExtras(Bundle().apply { putLong(WIRE_KEY, track.key) })
                .build())
            // Deliberately no URI/localConfiguration or artwork requiring an authenticated loader.
            .build()
    }

    fun keyAtMediaItemIndex(index: Int): Long? = this.snapshot.queue.getOrNull(index)?.key

    val timeline: Timeline = object : Timeline() {
        private val indexByKey = this@RemoteSessionView.snapshot.queue
            .mapIndexed { index, track -> track.key to index }.toMap()

        override fun getWindowCount(): Int = mediaItems.size
        override fun getPeriodCount(): Int = mediaItems.size

        private fun durationUs(index: Int): Long {
            val ms = if (index == currentIndex) durationMs else
                secondsToDurationMs(this@RemoteSessionView.snapshot.queue[index].duration)
            return if (ms == C.TIME_UNSET) C.TIME_UNSET else ms * 1000
        }

        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window =
            window.set(
                this@RemoteSessionView.snapshot.queue[windowIndex].key,
                mediaItems[windowIndex], null,
                C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET,
                true, false, null,
                0, durationUs(windowIndex), windowIndex, windowIndex, 0,
            )

        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
            val key = this@RemoteSessionView.snapshot.queue[periodIndex].key
            return period.set(
                if (setIds) key else null, if (setIds) key else null,
                periodIndex, durationUs(periodIndex), 0, AdPlaybackState.NONE, false,
            )
        }

        override fun getIndexOfPeriod(uid: Any): Int =
            if (uid is Long) indexByKey[uid] ?: C.INDEX_UNSET else C.INDEX_UNSET
        override fun getUidOfPeriod(periodIndex: Int): Any = this@RemoteSessionView.snapshot.queue[periodIndex].key

        // Timeline's defaults are linear even with shuffle=true, as required for realized queues.
        // Guard absent/invalid current indices too: no phantom successor for an empty session.
        override fun getNextWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int =
            if (windowIndex !in mediaItems.indices) C.INDEX_UNSET else
                super.getNextWindowIndex(windowIndex, repeatMode, shuffleModeEnabled)

        override fun getPreviousWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int =
            if (windowIndex !in mediaItems.indices) C.INDEX_UNSET else
                super.getPreviousWindowIndex(windowIndex, repeatMode, shuffleModeEnabled)
    }

    companion object {
        const val WIRE_KEY = "spofify.wireKey"
        private const val MAX_SECONDS = 604800.0
        private const val MAX_MILLISECONDS = 604800000L

        /** Wire zero duration means unknown; never multiply a sentinel or unbounded number. */
        fun secondsToDurationMs(seconds: Double): Long =
            if (seconds.isFinite() && seconds > 0.0 && seconds <= MAX_SECONDS)
                (seconds * 1000).roundToLong() else C.TIME_UNSET

        /** No clock extrapolation/audible credit. Unknown/invalid elapsed is safely zero. */
        fun secondsToElapsedMs(seconds: Double, durationMs: Long = C.TIME_UNSET): Long {
            val ms = if (seconds.isFinite() && seconds in 0.0..MAX_SECONDS)
                (seconds * 1000).roundToLong() else 0L
            val upper = if (durationMs in 0..MAX_MILLISECONDS) durationMs else MAX_MILLISECONDS
            return ms.coerceIn(0, upper)
        }
    }
}
