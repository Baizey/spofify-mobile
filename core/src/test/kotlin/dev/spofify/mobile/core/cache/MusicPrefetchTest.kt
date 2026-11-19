package dev.spofify.mobile.core.cache

import dev.spofify.mobile.core.http.AudioStream
import dev.spofify.mobile.core.playback.PlaybackQueueState
import dev.spofify.mobile.core.playback.PlaybackTrack
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MusicPrefetchTest {
    @get:Rule val root = TemporaryFolder()
    private fun queue(vararg ids: String, position: Int = 0, repeat: String = "off") = PlaybackQueueState(
        queue = ids.mapIndexed { i, id -> PlaybackTrack(id, id, emptyList(), "", "", 0.0, i.toLong(), i.toLong(), "low") },
        position = position, repeat = repeat)
    private val owner = MusicOwner("https://one.example", "123")

    @Test fun exactLimitsDistinctUpcomingRealOrderAndRepeatModes() {
        assertEquals(listOf(3, 10, 100, 1000), CacheOptions.caps)
        assertEquals(listOf(1, 3, 6, 12), CacheOptions.depths)
        assertEquals(listOf(1, 3, 6, 12), CacheOptions.caps.map { CacheOptions(it).ahead })
        assertThrows(IllegalArgumentException::class.java) { CacheOptions(7) }
        val q = queue("p", "c", "c", "n", "n", "2", "3", "4", position = 1)
        fun ids(state: PlaybackQueueState, depth: Int = 3) = state.cacheWindow()!!.priority(depth).map { it.id }
        assertEquals(listOf("c", "n", "p", "2", "3"), ids(q.copy(shuffle = true)))
        assertEquals(listOf("c", "n", "p"), ids(q, 1))
        assertEquals(listOf("c", "p"), ids(q.copy(repeat = "one")))
        assertEquals(listOf("4", "3"), ids(q.copy(position = 7)))
        assertEquals(listOf("4", "p", "3", "c", "n"), ids(q.copy(position = 7, repeat = "all")))
        assertEquals(listOf("p", "c", "4"), ids(q.copy(position = 0, repeat = "all"), 1))
        assertNull(queue().cacheWindow())
        val long = queue(*(0..24).map(Int::toString).toTypedArray()).cacheWindow()!!
        for (ahead in CacheOptions.depths) assertEquals(ahead, long.priority(ahead).size - 1)
    }

    @Test fun clearOnlyLiftsAtMeaningfulWindowNotStatusSeekOrReconnect() {
        val q = queue("one", "two")
        val w = q.cacheWindow()!!
        val clears = ClearSuppression(); clears.observe(w); clears.clear("one")
        val net = CacheNetwork(1, false)
        fun wanted() = w.plan(CacheOptions(), net, true, clears).songs.map { it.id }
        assertEquals(listOf("two"), wanted()); clears.clear(null)
        clears.observe(null); clears.observe(q.copy(elapsed = 500.0, isLoading = true).cacheWindow())
        clears.observe(w.copy(current = w.current.copy(name = "renamed")))
        assertTrue(wanted().isEmpty()); clears.observe(w.copy(event = "new traversal")); assertEquals(2, wanted().size)
        clears.clear(null); clears.observe(queue("one", "other").cacheWindow()); assertEquals(2, wanted().size)
    }

    @Test fun unmeteredByDefaultUnknownAlwaysBlocksAndNativeLoadingYields() {
        val w = queue("one").cacheWindow(); val clears = ClearSuppression()
        for (metered in listOf(true, false)) {
            val opt = CacheOptions(metered = metered)
            assertTrue(w.plan(opt, null, true, clears).songs.isEmpty())
            assertEquals(metered, w.plan(opt, CacheNetwork(1, true), true, clears).songs.isNotEmpty())
            assertTrue(w.plan(opt, CacheNetwork(2, false), true, clears).songs.isNotEmpty())
            assertTrue(w.plan(opt, CacheNetwork(2, false), false, clears).songs.isEmpty())
        }
    }

    @Test fun meteringCancelsBlockedReadBeforeReplacementAndNeverPublishesOldStage() = runBlocking {
        val store = MusicBlobStore(root.root)
        val options = CacheOptions(cap = 10); val clears = ClearSuppression()
        val unmetered = CacheNetwork(1, false)
        val w = queue("old").cacheWindow()
        val plans = MutableStateFlow(w.plan(options, unmetered, true, clears))
        val reading = CompletableDeferred<Unit>(); val closed = CompletableDeferred<Unit>()
        val release = CountDownLatch(1); val opened = AtomicInteger()
        val worker = launch(Dispatchers.IO) {
            prefetchMusic(plans, owner, store, { true }, { 1L }, { yield(); descriptor(it) }) { media ->
                assertEquals(1, opened.incrementAndGet())
                val source = if (media.trackId == "old") object : InputStream() {
                    override fun read(): Int {
                        reading.complete(Unit)
                        if (!release.await(5, TimeUnit.SECONDS)) error("stream was not cancelled")
                        throw IOException("closed")
                    }
                } else bytes.inputStream()
                AudioStream(source, bytes.size.toLong(), permitted = { true }) {
                    opened.decrementAndGet(); release.countDown(); closed.complete(Unit); source.close()
                }
            }
        }
        try {
            withTimeout(5000) {
                reading.await(); plans.value = w.plan(options, CacheNetwork(1, true), true, clears); closed.await()
                plans.value = queue("new", "b", "c", "d", "e").cacheWindow().plan(options, unmetered, true, clears)
                val stored = store.songs.first { it.size == 4 }
                assertEquals(listOf("new", "b", "c", "d"), stored.map { it.key.id })
            }
        } finally { release.countDown(); worker.cancelAndJoin() }
        assertEquals(0, opened.get()); assertFalse(root.root.listFiles()!!.any { it.extension == "tmp" })
    }
}
