package dev.spofify.mobile.core.http

import dev.spofify.mobile.core.auth.FeatureAuthority
import dev.spofify.mobile.core.playback.PlaybackConnection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.map
import okhttp3.WebSocket

/** The account owner can retire this connection even while its upgrade is pending. */
internal class SessionSocket(
    private val authority: FeatureAuthority,
    private val onRetired: (SessionSocket) -> Unit,
) : PlaybackConnection {
    private data class Message(val text: String, val bytes: Long)
    private val retired = AtomicBoolean(false)
    private val queuedBytes = AtomicLong(0)
    private val incoming = Channel<Message>(MAX_INCOMING_FRAMES, onUndeliveredElement = { queuedBytes.addAndGet(-it.bytes) })
    @Volatile private var socket: WebSocket? = null
    @Volatile private var opened = false

    override val isOpen: Boolean get() = opened && !retired.get() && authority.isCurrent
    override val messages: Flow<String> = incoming.consumeAsFlow().map { message ->
        queuedBytes.addAndGet(-message.bytes)
        if (!isOpen) throw CancellationException("Playback connection closed")
        message.text
    }

    fun attach(value: WebSocket) {
        socket = value
        if (retired.get()) value.cancel()
    }

    fun opened() { opened = true }

    fun receive(text: String) {
        if (!isOpen) { close(); return }
        val bytes = text.toByteArray(Charsets.UTF_8).size.toLong()
        if (bytes > MAX_INCOMING_BYTES || queuedBytes.addAndGet(bytes) > MAX_INCOMING_BYTES) {
            if (bytes <= MAX_INCOMING_BYTES) queuedBytes.addAndGet(-bytes)
            close()
            return
        }
        if (!incoming.trySend(Message(text, bytes)).isSuccess) {
            queuedBytes.addAndGet(-bytes)
            close()
        }
    }

    override fun send(text: String): Boolean {
        if (text.toByteArray(Charsets.UTF_8).size > 65536) return false
        val sent = authority.runIfCurrent { isOpen && socket?.send(text) == true }
        if (!sent) close()
        return sent
    }

    override fun close() {
        if (!retired.compareAndSet(false, true)) return
        opened = false
        incoming.cancel(CancellationException("Playback connection closed"))
        socket?.cancel()
        onRetired(this)
    }

    private companion object {
        const val MAX_INCOMING_FRAMES = 256
        const val MAX_INCOMING_BYTES = 5L * 1024 * 1024
    }
}
