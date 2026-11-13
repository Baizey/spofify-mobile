package dev.spofify.mobile.core

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** Single connection, single ordered event consumer. Never reconnects, restores or replays music.
 * Successful sends capture their monotonic time BEFORE enqueue. Closed is not stopped proof. */
interface LibraryPlaybackTransport : AutoCloseable {
    val identity: ValidatedSessionIdentity
    val isCurrent: Boolean
    val events: Flow<LibraryPlaybackEvent>
    fun send(message: LibraryPlaybackClientMessage): LibraryPlaybackSend?
    override fun close()
}

internal class OkHttpLibraryPlaybackTransport(
    override val identity: ValidatedSessionIdentity,
    scope: CoroutineScope,
    private val deviceId: String,
    private val current: () -> Boolean,
    private val guardedSend: (action: () -> Boolean) -> Boolean,
    private val renewAuthentication: suspend () -> Unit,
    private val unregister: () -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
) : LibraryPlaybackTransport {
    private data class Queued(val event: LibraryPlaybackEvent, val bytes: Int)
    private val lock = Any()
    private val channel = Channel<Queued>(64)
    private val lifecycle = SupervisorJob(scope.coroutineContext[Job])
    private val workers = CoroutineScope(scope.coroutineContext + lifecycle)
    private val started = AtomicBoolean()
    private var socket: WebSocket? = null
    private var closed = false
    private var ready = false
    private var sessionId: String? = null
    private var queuedBytes = 0
    private var commandCounter = 0L
    private var clientSequence = 0L
    override val isCurrent: Boolean get() = current() && synchronized(lock) { !closed }
    override val events: Flow<LibraryPlaybackEvent> = channel.receiveAsFlow().transform { queued ->
        val deliver = synchronized(lock) {
            queuedBytes = (queuedBytes - queued.bytes).coerceAtLeast(0)
            queued.event is LibraryPlaybackEvent.Closed || !closed
        }
        if (deliver && (queued.event is LibraryPlaybackEvent.Closed || current())) emit(queued.event)
    }

    init {
        lifecycle.invokeOnCompletion { retire(LibraryPlaybackEvent.Reason.CLOSED) }
        workers.launch {
            while (isActive) {
                delay(250)
                if (!current()) { retire(LibraryPlaybackEvent.Reason.AUTHENTICATION); break }
            }
        }
        workers.launch {
            while (isActive) {
                delay(60_000)
                try { renewAuthentication() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    retire(if (current()) LibraryPlaybackEvent.Reason.NETWORK else LibraryPlaybackEvent.Reason.AUTHENTICATION)
                    break
                }
            }
        }
    }

    /** The hello string exists only in this one-use listener until onOpen/failure. */
    fun start(hello: String, open: (WebSocketListener) -> WebSocket) {
        check(started.compareAndSet(false, true))
        if (!isCurrent) { retire(LibraryPlaybackEvent.Reason.AUTHENTICATION); return }
        val listener = object : WebSocketListener() {
            private var firstHello: String? = hello
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val payload = firstHello
                firstHello = null
                if (response.headers.values("Sec-WebSocket-Protocol") != listOf(LibraryPlaybackProtocol.SUBPROTOCOL) || payload == null || !current()) {
                    retire(LibraryPlaybackEvent.Reason.PROTOCOL); webSocket.cancel(); return
                }
                var capacity = false
                val sent = synchronized(lock) {
                    if (closed) false else {
                        socket = webSocket
                        guardedSend {
                            if (!fitsOutbound(webSocket, payload.toByteArray(Charsets.UTF_8).size)) { capacity = true; false }
                            else webSocket.send(payload)
                        }
                    }
                }
                if (!sent) { retire(if (capacity) LibraryPlaybackEvent.Reason.CAPACITY else LibraryPlaybackEvent.Reason.NETWORK); webSocket.cancel() }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!current()) { retire(LibraryPlaybackEvent.Reason.AUTHENTICATION); return }
                val message = try { LibraryPlaybackCodec.decodeServer(text) }
                    catch (_: Exception) { retire(LibraryPlaybackEvent.Reason.PROTOCOL); return }
                val bytes = text.toByteArray(Charsets.UTF_8).size
                var failure: LibraryPlaybackEvent.Reason? = null
                synchronized(lock) {
                    if (closed) return
                    if ((!ready && (message.type != "snapshot" || message.deviceId != deviceId)) ||
                        (ready && (message.type == "snapshot" || message.sessionId != sessionId))) {
                        failure = LibraryPlaybackEvent.Reason.PROTOCOL
                    } else if (!guardedSend {
                        if (queuedBytes.toLong() + bytes > MAX_QUEUED_BYTES || !channel.trySend(Queued(LibraryPlaybackEvent.Message(message), bytes)).isSuccess) false
                        else { queuedBytes += bytes; true }
                    }) failure = LibraryPlaybackEvent.Reason.CAPACITY
                    else if (!ready) { ready = true; sessionId = message.sessionId }
                }
                failure?.let(::retire)
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { retire(LibraryPlaybackEvent.Reason.PROTOCOL) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { firstHello = null; retire(LibraryPlaybackEvent.Reason.CLOSED) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { firstHello = null; retire(LibraryPlaybackEvent.Reason.CLOSED) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                firstHello = null
                retire(when {
                    !current() -> LibraryPlaybackEvent.Reason.AUTHENTICATION
                    t is LibraryPlaybackIngressException -> LibraryPlaybackEvent.Reason.PROTOCOL
                    else -> LibraryPlaybackEvent.Reason.NETWORK
                })
            }
        }
        val opened = try { open(listener) } catch (_: Exception) { retire(LibraryPlaybackEvent.Reason.NETWORK); return }
        synchronized(lock) { if (closed) opened.cancel() else socket = opened }
    }

    override fun send(message: LibraryPlaybackClientMessage): LibraryPlaybackSend? {
        if (!current()) { retire(LibraryPlaybackEvent.Reason.AUTHENTICATION); return null }
        var failure: LibraryPlaybackEvent.Reason? = null
        val receipt = synchronized(lock) {
            val ws = socket
            if (closed || !ready || ws == null) return@synchronized null
            val command = message is LibraryPlaybackClientMessage.Command
            if ((if (command) commandCounter else clientSequence) >= LibraryPlaybackProtocol.MAX_SAFE_INTEGER) {
                failure = LibraryPlaybackEvent.Reason.CAPACITY; return@synchronized null
            }
            val id = if (command) (commandCounter + 1).toString() else null
            val sequence = if (command) null else clientSequence + 1
            // Invalid local requests are atomic rejects and consume no counter/send time.
            val raw = try { LibraryPlaybackCodec.encode(message, id, sequence) } catch (_: LibraryPlaybackProtocolException) { return@synchronized null }
            val encodedBytes = raw.toByteArray(Charsets.UTF_8).size
            var sentAt = 0L
            if (!guardedSend {
                if (!fitsOutbound(ws, encodedBytes)) { failure = LibraryPlaybackEvent.Reason.CAPACITY; false }
                else { sentAt = nanoTime(); ws.send(raw) }
            }) {
                if (failure == null) failure = LibraryPlaybackEvent.Reason.NETWORK
                return@synchronized null
            }
            if (command) commandCounter++ else clientSequence++
            LibraryPlaybackSend(sentAt, id, sequence)
        }
        failure?.let(::retire)
        return receipt
    }

    /** Independent writer admission: never rely on OkHttp's larger default 16 MiB queue.
     * Send serialization covers hello and every application message; UTF-8 bytes, not chars. */
    private fun fitsOutbound(webSocket: WebSocket, bytes: Int): Boolean {
        val pending = webSocket.queueSize()
        return pending >= 0 && pending <= MAX_QUEUED_BYTES.toLong() - bytes
    }

    fun retire(reason: LibraryPlaybackEvent.Reason) {
        val ws = synchronized(lock) {
            if (closed) return
            closed = true; ready = false
            // Do not deliver stale queued grants after auth/socket loss. Preserve a terminal event.
            while (channel.tryReceive().isSuccess) Unit
            queuedBytes = 0
            channel.trySend(Queued(LibraryPlaybackEvent.Closed(reason), 0))
            channel.close()
            socket.also { socket = null }
        }
        ws?.cancel()
        unregister()
        lifecycle.cancel()
    }
    override fun close() = retire(LibraryPlaybackEvent.Reason.CLOSED)
    private companion object { const val MAX_QUEUED_BYTES = 5 * 1024 * 1024 }
}
