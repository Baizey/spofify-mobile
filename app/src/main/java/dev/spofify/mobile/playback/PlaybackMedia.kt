package dev.spofify.mobile.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import dev.spofify.mobile.core.ArtistCredit
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.SpofifyClient
import java.util.UUID

/** All queue identity is metadata, not a credential or a caller-supplied network address. */
internal object PlaybackMedia {
    private const val ID = "spofify.trackId"
    private const val OCCURRENCE = "spofify.occurrence"
    private const val ARTIST_IDS = "spofify.artistIds"
    private const val ARTIST_NAMES = "spofify.artistNames"
    private const val ENTRY_ID = "spofify.entryId"
    private const val ALBUM_ID = "spofify.albumId"
    private const val DURATION = "spofify.durationMs"

    fun create(item: LibraryItem, client: SpofifyClient, occurrenceId: String = UUID.randomUUID().toString()): MediaItem {
        require(item.kind == "track" && item.playable && validId(item.id))
        require(occurrenceId.isNotEmpty() && occurrenceId.length <= 128 && occurrenceId.all { it.code in 33..126 })
        val extras = Bundle().apply {
            putString(ID, item.id)
            putString(OCCURRENCE, occurrenceId)
            putStringArrayList(ARTIST_IDS, ArrayList(item.artists.map { it.id }))
            putStringArrayList(ARTIST_NAMES, ArrayList(item.artists.map { it.name }))
            putString(ALBUM_ID, item.albumId)
            putString(ENTRY_ID, item.entryId)
            putLong(DURATION, item.durationMs.coerceAtLeast(0))
        }
        return MediaItem.Builder()
            .setMediaId(item.id)
            .setUri(client.audioUrl(item))
            .setCustomCacheKey(item.id) // In-memory identity only; retained keys are opaque hashes.
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle(item.name)
                .setArtist(item.artists.joinToString(", ") { it.name })
                .setAlbumTitle(item.albumName)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setExtras(extras)
                // Deliberately no artworkUri: Media3's default bitmap loader does not use
                // our authenticated OkHttpClient. Text-only notifications cannot leak auth.
                .build())
            .build()
    }

    fun libraryItem(media: MediaItem): LibraryItem? {
        val metadata = media.mediaMetadata
        val extras = metadata.extras ?: return null
        val id = extras.getString(ID) ?: return null
        if (!validId(id) || id != media.mediaId) return null
        val artistIds = extras.getStringArrayList(ARTIST_IDS).orEmpty()
        val artistNames = extras.getStringArrayList(ARTIST_NAMES).orEmpty()
        return LibraryItem(
            kind = "track", id = id, name = metadata.title?.toString().orEmpty(),
            artists = artistNames.mapIndexed { index, name ->
                ArtistCredit(artistIds.getOrElse(index) { "" }, name)
            },
            albumId = extras.getString(ALBUM_ID),
            albumName = metadata.albumTitle?.toString(),
            durationMs = extras.getLong(DURATION).coerceAtLeast(0),
            hasAudio = true,
            entryId = extras.getString(ENTRY_ID),
        )
    }

    fun occurrence(media: MediaItem?): String? = media?.mediaMetadata?.extras?.getString(OCCURRENCE)

    private fun validId(id: String): Boolean =
        id.isNotBlank() && id != "." && id != ".." && id.none { it.isISOControl() }
}

/** Never surface server bodies, URLs, credentials, or raw ExoPlayer exception messages. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun playbackErrorMessage(error: PlaybackException): String {
    var cause: Throwable? = error
    repeat(12) {
        val current = cause ?: return@repeat
        if (current is HttpDataSource.InvalidResponseCodeException) {
            return when (current.responseCode) {
                401 -> "Your session has expired. Sign in again."
                403 -> "You do not have permission to play this track."
                404 -> "This track is no longer available."
                in 300..399 -> "The server redirected playback. Use its direct server address."
                else -> "The server could not play this track. Try again."
            }
        }
        cause = current.cause
    }
    return "Unable to play this track. Check your connection and try again."
}
