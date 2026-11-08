package dev.spofify.mobile.playback

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import android.net.Uri
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import dev.spofify.mobile.OfflineMediaReference
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

    fun create(item: LibraryItem, client: SpofifyClient, occurrenceId: String = UUID.randomUUID().toString()): MediaItem =
        createWithUri(item, client.audioUrl(item), occurrenceId)

    /** Opaque service queue address, never a file path or a caller-provided media URL. */
    fun createLocal(item: LibraryItem, queueId: String, occurrenceId: String = UUID.randomUUID().toString()): MediaItem {
        require(runCatching { UUID.fromString(queueId).toString() == queueId }.getOrDefault(false))
        val uri = Uri.Builder().scheme("spofify-local").authority(queueId).appendPath(occurrenceId).build()
        require(!item.entryId.isNullOrBlank())
        return createWithUri(item, uri.toString(), occurrenceId, cacheKey = uri.toString())
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun createWithUri(item: LibraryItem, uri: String, occurrenceId: String, cacheKey: String = item.id): MediaItem {
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
            .setUri(uri)
            .setCustomCacheKey(cacheKey) // Local source tokens bind exact saved references, never track-ID lookup.
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

/** A verified store opener is the only source of local bytes. Every open/read is fenced,
 * including reads from an already-open descriptor. No URI resolution or fallback exists. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class VerifiedLocalPlaybackSource(
    private val isCurrent: () -> Boolean,
    private val referenceFor: (DataSpec) -> OfflineMediaReference?,
    private val opener: suspend (OfflineMediaReference) -> DataSource?,
) : DataSource {
    private var source: DataSource? = null
    private var opened: DataSpec? = null
    private val listeners = mutableListOf<TransferListener>()

    private fun authorize(spec: DataSpec): OfflineMediaReference {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Local media operation interrupted")
        if (!isCurrent()) throw IOException("Local playback access changed")
        return referenceFor(spec) ?: throw IOException("Source is not in the local queue")
    }

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        source?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        check(source == null)
        if (Looper.myLooper() === Looper.getMainLooper()) throw IOException("Local media opens require the loading thread")
        val reference = authorize(dataSpec)
        val abandoned = AtomicBoolean()
        val returned = AtomicReference<DataSource?>()
        try {
            // Media3's synchronous loading boundary only. No UI/service admission path waits
            // for the saved owner's suspend factory or owns a duplicate lease/storage adapter.
            runBlocking {
                coroutineContext.ensureActive()
                authorize(dataSpec)
                val candidate = opener(reference)
                returned.set(candidate)
                if (abandoned.get()) {
                    returned.getAndSet(null)?.close()
                    throw CancellationException("Local media open cancelled")
                }
                coroutineContext.ensureActive()
                authorize(dataSpec)
            }
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val verified = returned.get() ?: throw IOException("Saved audio is unavailable or incomplete")
            source = verified
            opened = dataSpec
            listeners.forEach(verified::addTransferListener)
            authorize(dataSpec)
            val length = verified.open(dataSpec)
            authorize(dataSpec)
            returned.set(null) // Ownership moved to normal DataSource.close().
            return length
        } catch (failure: Exception) {
            abandoned.set(true)
            val late = returned.getAndSet(null)
            if (source != null) runCatching { close() } else runCatching { late?.close() }
            when (failure) {
                is InterruptedException -> {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("Local media open interrupted").apply { initCause(failure) }
                }
                is CancellationException -> throw IOException("Local media open cancelled", failure)
                else -> throw failure
            }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val spec = opened ?: throw IOException("Local source is not open")
        authorize(spec)
        val count = requireNotNull(source).read(buffer, offset, length)
        authorize(spec)
        return count
    }

    override fun getUri(): Uri? = opened?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()
    override fun close() {
        val closing = source
        source = null
        opened = null
        closing?.close()
    }
}

/** Bounded diagnostic only: never attach a Throwable/message, URL, headers or track metadata. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun playbackFailureDiagnostic(error: PlaybackException): String {
    val category = when (error.errorCode) {
        in 2000..2999 -> "io"
        in 3000..3999 -> "parsing"
        in 4000..4999 -> "decoder"
        in 5000..5999 -> "audio-output"
        in 6000..6999 -> "drm"
        else -> "player"
    }
    val causes = mutableListOf<String>()
    var cause: Throwable? = error
    repeat(12) {
        val current = cause ?: return@repeat
        // Class names, numeric codes only; raw exception messages can contain credentials.
        causes += current.javaClass.simpleName.take(80)
        if (current is HttpDataSource.InvalidResponseCodeException) causes += "http=${current.responseCode}"
        cause = current.cause
    }
    return "category=$category code=${error.errorCode} causes=${causes.joinToString(">")}"
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
