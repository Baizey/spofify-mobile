package dev.spofify.mobile.core.http

import dev.spofify.mobile.core.auth.FeatureAuthority
import dev.spofify.mobile.core.playback.PlaybackConnection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.map
import okhttp3.WebSocket
import okio.utf8Size

/** The account owner can retire this connection even while its upgrade is pending. */
internal class SessionSocket(
    private val authority: FeatureAuthority,
    private val onRetired: (SessionSocket) -> Unit,
) : PlaybackConnection {
    private data class Message(val text: String, val bytes: Long)
    private enum class Retirement { CLOSED, SUPERSEDED }
    private val retirement = AtomicReference<Retirement?>(null)
    private val helloSent = AtomicBoolean(false)
    private val queuedBytes = AtomicLong(0)
    private val incoming = Channel<Message>(MAX_INCOMING_FRAMES, onUndeliveredElement = { queuedBytes.addAndGet(-it.bytes) })
    @Volatile private var socket: WebSocket? = null
    @Volatile private var opened = false

    override val isOpen: Boolean get() = opened && retirement.get() == null && authority.isCurrent
    override val superseded: Boolean get() = retirement.get() == Retirement.SUPERSEDED
    override val messages: Flow<String> = incoming.consumeAsFlow().map { message ->
        queuedBytes.addAndGet(-message.bytes)
        if (!isOpen) throw CancellationException("Playback connection closed")
        message.text
    }

    fun attach(value: WebSocket) {
        socket = value
        if (retirement.get() != null) value.cancel()
    }

    fun opened() { opened = true }

    fun receive(text: String) {
        if (!isOpen) { close(); return }
        val bytes = text.utf8Size()
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

    /** Only the admission path can send the first frame. Android never uploads an isolated queue. */
    fun sendHello(text: String): Boolean {
        if (text.utf8Size() > MAX_HELLO_BYTES) return false
        return authority.runIfCurrent {
            isOpen && helloSent.compareAndSet(false, true) && socket?.send(text) == true
        }
    }

    override fun send(text: String): Boolean {
        if (!helloSent.get() || text.utf8Size() > MAX_COMMAND_BYTES) return false
        val sent = authority.runIfCurrent { isOpen && socket?.send(text) == true }
        if (!sent) close()
        return sent
    }

    override fun finish() {
        if (!retirement.compareAndSet(null, Retirement.CLOSED)) return
        opened = false
        incoming.cancel(CancellationException("Output relinquished"))
        if (socket?.close(1000, null) != true) socket?.cancel()
        onRetired(this)
    }

    fun peerClosed(code: Int, reason: String) {
        if (code == SUPERSEDED_CODE && reason == SUPERSEDED_REASON && authority.runIfCurrent {
                retirement.compareAndSet(null, Retirement.SUPERSEDED)
            }) {
            // Record the cause before waking a suspended collector. A late callback must
            // never change the cause for an already-retired or unauthorized connection.
            retire("Playback connection superseded")
        } else close()
    }

    override fun close() {
        if (retirement.compareAndSet(null, Retirement.CLOSED)) retire("Playback connection closed")
    }

    private fun retire(message: String) {
        opened = false
        incoming.cancel(CancellationException(message))
        socket?.cancel()
        onRetired(this)
    }

    companion object {
        private const val SUPERSEDED_CODE = 4001
        private const val SUPERSEDED_REASON = "superseded"
        private const val MAX_COMMAND_BYTES = 64 * 1024
        const val MAX_HELLO_BYTES = MAX_COMMAND_BYTES
        private const val MAX_INCOMING_FRAMES = 256
        private const val MAX_INCOMING_BYTES = 5L * 1024 * 1024
    }
}
