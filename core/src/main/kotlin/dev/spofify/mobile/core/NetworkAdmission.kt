package dev.spofify.mobile.core

import java.io.IOException
import java.util.WeakHashMap
import okhttp3.Call
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

/** Process-local transport authority, deliberately independent of live authentication.
 * Both edges advance the epoch: exiting offline mode never revives captured online work.
 * The gate owns no credentials and can also fence the isolated terminal leave transport. */
internal class NetworkAdmission(private val lock: Any) {
    private var offline = false
    private var epoch = 0L
    private val calls = WeakHashMap<Call, Long>()

    val isOffline: Boolean get() = synchronized(lock) { offline }
    val revision: Long get() = synchronized(lock) { epoch }

    /** Retire provisional auth atomically with the edge, before any cancellation callback.
     * The owner cancels only this snapshot after releasing the shared client/admission lock. */
    fun setOffline(value: Boolean, retire: () -> Unit): List<Call> = synchronized(lock) {
        if (offline == value) return@synchronized emptyList()
        offline = value
        epoch++
        retire()
        calls.keys.toList().also { calls.clear() }
    }

    fun capture(): Long = synchronized(lock) { requireLocked(epoch); epoch }
    fun requireCurrent(expected: Long) = synchronized(lock) { requireLocked(expected) }
    fun isCurrent(expected: Long): Boolean = synchronized(lock) { !offline && epoch == expected }
    fun <T> publish(expected: Long, block: () -> T): T = synchronized(lock) {
        requireLocked(expected)
        block()
    }

    fun track(call: Call, expected: Long) = synchronized(lock) {
        requireLocked(expected)
        calls[call] = expected
    }
    fun finished(call: Call) { synchronized(lock) { calls.remove(call) } }

    private fun requireLocked(expected: Long) {
        if (offline || epoch != expected) throw NetworkAdmissionException()
    }

    /** Fence raw artwork/media body access too. Delivered bytes cannot be recalled, and a raw
     * BufferedSource's internal prefetched bytes can remain readable after an admission edge.
     * Consumer operation/account/mode publication fences are mandatory, even with this guard.
     * Core's response helper additionally gates parsing and checks the receiving coroutine. */
    fun guard(response: Response, expected: Long): Response {
        requireCurrent(expected)
        val body = response.body
        val guarded = object : ResponseBody() {
            private val guardedSource = object : ForwardingSource(body.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    requireCurrent(expected)
                    val read = super.read(sink, byteCount)
                    requireCurrent(expected)
                    return read
                }
            }.buffer()
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun source() = guardedSource.also { requireCurrent(expected) }
            override fun close() = body.close()
        }
        return response.newBuilder().body(guarded).build()
    }
}

/** Fixed safe category: distinguish deliberate admission fencing from an actual socket failure. */
internal class NetworkAdmissionException : IOException("Online work is unavailable in offline mode or was superseded")

/** Optional request tag binds an auth operation before Call construction/dispatcher waits. */
internal class NetworkAdmissionEpoch(val value: Long)
