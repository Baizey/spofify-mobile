package dev.spofify.mobile.core.offline

import dev.spofify.mobile.core.cache.CacheOptions
import dev.spofify.mobile.core.cache.MusicBlobStore
import dev.spofify.mobile.core.cache.MusicOwner
import dev.spofify.mobile.core.cache.SongKey
import dev.spofify.mobile.core.http.AudioStream
import java.io.InputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

/** One account+origin facade over the app's existing store, not a service or queue. The caller
 * owns the Job (cancel on retirement/policy change) and a nonblocking generation/policy check.
 * Reads have no network dependency. Pending work is never a selectable playlist.
 */
class OfflinePlaylistRepository(
    private val store: MusicBlobStore,
    private val owner: MusicOwner,
    private val manifest: suspend (OfflineCollectionRef) -> OfflineManifest,
    private val prepare: suspend (String) -> OfflineMediaDescriptor,
    private val open: suspend (OfflineMediaDescriptor) -> AudioStream,
) {
    suspend fun complete(): List<OfflineManifest> = withContext(Dispatchers.IO) { store.complete(owner) }
    suspend fun select(id: String): OfflineManifest? = withContext(Dispatchers.IO) { store.select(owner, id) }

    suspend fun refresh(id: String, cap: Int, current: () -> Boolean): OfflineManifest = withContext(Dispatchers.IO) {
        val ref = OfflineCollectionRef(OfflineCollectionKind.PLAYLIST, id)
        require(cap in CacheOptions.caps && OfflineParser.validReference(ref))
        val job = currentCoroutineContext().job
        fun live() = job.isActive && current()
        fun checkCurrent() { if (!live()) throw CancellationException("Playlist work retired") }
        store.playlistWriter.withLock {
            try {
                checkCurrent()
                val result = transient(::checkCurrent) { manifest(ref) }
                checkCurrent()
                validatePlaylist(id, result)
                for (item in result.items.distinctBy { it.track.id }) {
                    val track = item.track
                    checkCurrent()
                    store.stage(SongKey(owner, track.id), track.name, cap, ::live,
                        { transient(::checkCurrent) { prepare(track.id) } }) { media, consume ->
                        checkCurrent()
                        open(media).consumeBody(::checkCurrent, consume)
                    }
                }
                checkCurrent()
                store.commit(owner, result, cap, ::checkCurrent)
                checkCurrent()
                result
            } catch (e: Exception) {
                checkCurrent() // Late errors from non-cooperative functions must not escape retirement.
                throw e
            } finally { store.finishStage() }
        }
    }

    suspend fun remove(id: String, cap: Int, current: () -> Boolean): Boolean = withContext(Dispatchers.IO) {
        require(cap in CacheOptions.caps)
        val job = currentCoroutineContext().job
        store.playlistWriter.withLock {
            store.remove(owner, id, cap) {
                if (!job.isActive || !current()) throw CancellationException("Playlist work retired")
            }
        }
    }

    /** Only retry classified transient metadata reads once. No retry of auth, stale membership,
     * storage, or an interrupted body; a later explicit refresh can start another attempt.
     */
    private suspend fun <T> transient(checkCurrent: () -> Unit, read: suspend () -> T): T {
        checkCurrent()
        try { return read() } catch (e: OfflineException) {
            if (e.failure !in setOf(OfflineFailure.UNAVAILABLE, OfflineFailure.BUSY, OfflineFailure.TIMEOUT)) throw e
        }
        delay(250)
        checkCurrent()
        return read()
    }

    // Closing a response body concurrently with its read is not a socket cancellation. Signal
    // Call.cancel on parent cancellation; the reader alone closes the body when read unwinds.
    private suspend fun AudioStream.consumeBody(checkCurrent: () -> Unit, consume: (InputStream) -> Unit) = use {
        coroutineScope {
            val reader = currentCoroutineContext().job
            val interrupt = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { if (!reader.isActive) this@consumeBody.cancel() }
            }
            try { checkCurrent(); consume(input) } finally { interrupt.cancel() }
        }
    }
}
