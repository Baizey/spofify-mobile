package dev.spofify.mobile.core.cache

import dev.spofify.mobile.core.http.AudioStream
import dev.spofify.mobile.core.offline.OfflineMediaDescriptor
import java.io.InputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest

data class CacheNetwork(val handle: Long, val metered: Boolean)
fun CacheNetwork?.allows(options: CacheOptions) = this != null && (!metered || options.metered)

data class PrefetchPlan(val cap: Int, val window: CacheWindow?, val network: CacheNetwork?, val songs: List<CacheSong>, val generation: Long = 0)

fun CacheWindow?.plan(options: CacheOptions, network: CacheNetwork?, ready: Boolean, clears: ClearSuppression) =
    PrefetchPlan(options.cap, this, network, if (ready && network.allows(options))
        this?.priority(options.ahead).orEmpty().filter { clears.allows(it.id) } else emptyList())

/** One collector per live account. collectLatest joins the retired transfer before starting another;
 * the StateFlow identity check also fences publication while cancellation is still being delivered.
 */
suspend fun prefetchMusic(plans: StateFlow<PrefetchPlan>, owner: MusicOwner, store: MusicBlobStore,
    isCurrent: () -> Boolean, nowMs: () -> Long, prepare: suspend (String) -> OfflineMediaDescriptor,
    open: suspend (OfflineMediaDescriptor) -> AudioStream) {
    val failed = mutableMapOf<String, Long>()
    plans.collectLatest { work ->
        yield() // collectLatest must be listening for replacements before any blocking IO.
        val job = currentCoroutineContext().job
        fun current() = job.isActive && plans.value === work && isCurrent()
        if (!current()) return@collectLatest
        val ids = work.songs.map { it.id }
        try { store.limit(owner, work.cap, ids) } catch (_: Exception) { return@collectLatest }
        failed.entries.removeAll { it.value <= nowMs() }
        var budget = work.cap
        for (song in work.songs) {
            if (!current()) break
            val key = SongKey(owner, song.id)
            if (!store.hasOfflineReference(key) && budget-- <= 0) continue
            if (song.id in failed) continue
            try {
                store.fill(key, song.name, work.cap, ids, ::current, { prepare(song.id) }) { media, consume ->
                    open(media).consumeUntilCancelled(consume)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed[song.id] = nowMs() + 60_000 }
        }
    }
}

/** The parent is an IO worker blocked in InputStream.read; close the socket on cancellation rather
 * than waiting for another byte. Registration, normal completion and cancellation all own close.
 */
private suspend fun AudioStream.consumeUntilCancelled(consume: (InputStream) -> Unit) = use {
    coroutineScope {
        val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { close() }
        }
        try { consume(input) } finally { closer.cancel() }
    }
}
