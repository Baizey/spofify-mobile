package dev.spofify.mobile.core.cache

import dev.spofify.mobile.core.playback.PlaybackQueueState

data class CacheOptions(val cap: Int = 10, val metered: Boolean = false) {
    init { require(cap in caps) }
    val ahead: Int get() = lookahead.getValue(cap)
    companion object {
        private val lookahead = mapOf(3 to 1, 10 to 3, 100 to 6, 1000 to 12)
        val caps = lookahead.keys.toList()
        val depths = lookahead.values.toList()
    }
}

data class CacheSong(val id: String, val name: String)

data class CacheWindow(val key: Long, val event: String?, val current: CacheSong, val previous: CacheSong?, val next: List<CacheSong>) {
    fun priority(depth: Int): List<CacheSong> {
        require(depth in CacheOptions.depths)
        return (listOfNotNull(current, next.firstOrNull(), previous) + next.take(depth).drop(1)).distinctBy { it.id }
    }
}

fun PlaybackQueueState.cacheWindow(): CacheWindow? {
    val here = current ?: return null
    val seen = hashSetOf(here.id)
    val next = mutableListOf<CacheSong>()
    if (repeat != "one") for (step in 1 until queue.size) {
        val index = position + step
        if (index >= queue.size && repeat != "all") break
        val track = queue[index % queue.size]
        if (seen.add(track.id)) next += CacheSong(track.id, track.name)
        if (next.size == CacheOptions.depths.last()) break
    }
    val prev = queue.getOrNull(position - 1) ?: queue.lastOrNull()?.takeIf { repeat == "all" }
    return CacheWindow(here.key, occurrence?.eventId, CacheSong(here.id, here.name), prev?.let { CacheSong(it.id, it.name) }, next)
}

/** Retain explicit clears across idle, reports, seeks and same-window socket reattachment. */
class ClearSuppression {
    private var last: CacheWindow? = null
    private var all = false
    private val ids = mutableSetOf<String>()
    fun observe(window: CacheWindow?) {
        val old = last
        if (window != null && (old == null || old.key != window.key || old.event != window.event ||
                old.current.id != window.current.id || old.previous?.id != window.previous?.id ||
                old.next.map { it.id } != window.next.map { it.id })) {
            last = window; all = false; ids.clear()
        }
    }
    fun clear(id: String?) { if (id == null) all = true else ids += id }
    fun allows(id: String) = !all && id !in ids
}
