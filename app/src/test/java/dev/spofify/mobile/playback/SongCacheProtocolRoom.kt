package dev.spofify.mobile.playback

import dev.spofify.mobile.core.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic CURRENT-protocol server authority for cache/source tests. Commands contain only
 * selection IDs; fixture catalog setup is not a client queue upload. Actual endpoint ACKs and
 * observations drive completion and playback facts. Not wire, broker or acoustic acceptance.
 */
internal class SongCacheProtocolRoom(private val remote: Boolean = false, private val sampleRate: Int = 8000,
    private val longCurrent: Boolean = false, private val longFutureB: Boolean = false,
    private val normalTrackSeconds: Int = 12, private val prepareOnJoin: Boolean = true) : Dispatcher() {
    data class Audio(val id: String, val method: String, val range: String?, val scheduledBodyBytes: Int)
    val audio = CopyOnWriteArrayList<Audio>()
    @Volatile var onAudioRequest: ((Audio) -> Unit)? = null
    val unexpected = CopyOnWriteArrayList<String>()
    val admissions = AtomicInteger()
    val reports = AtomicInteger()
    val history = AtomicInteger()
    val mutations = CopyOnWriteArrayList<String>()
    val messages = CopyOnWriteArrayList<LibraryPlaybackClientMessage>()
    val resolutions = AtomicInteger()
    @Volatile var restoreStatus = 200
    @Volatile var audioFailureStatus: Int? = null
    @Volatile var malformedAudio = false
    @Volatile var delayedTrack: String? = null
    var renewalsEnabled = true
    private var socket: Socket? = null
    private var serial = 0L
    private var key = 0L
    private var scalar = 1L
    private var catalogs = linkedMapOf<String, List<LibraryItem>>()
    private var pending: Pending? = null
    private var live = false
    private var latestSequence = 0L
    private var executionId = ""
    private data class Pending(val instruction: LibraryPlaybackInstruction, val commandId: String?,
        val count: Int, val placement: String)
    var snapshot = testLibrarySnapshot(selected = if (remote) "remote" else "phone", reported = false)
        private set
    val prepareAcks = AtomicInteger()
    val activationAcks = AtomicInteger()

    fun catalog(items: List<LibraryItem>): LibraryPlaybackContext {
        val id = "fixture-catalog-${catalogs.size + 1}"
        catalogs[id] = items.map { it.copy(artists = it.artists.toList()) }
        return LibraryPlaybackContext("playlist", id, "Synthetic collection")
    }
    fun open(identity: ValidatedSessionIdentity, deviceId: String): LibraryPlaybackTransport {
        check(socket?.isCurrent != true)
        val generation = admissions.incrementAndGet()
        socket = Socket(identity).also { current ->
            val devices = listOf(LibraryPlaybackDevice(deviceId, "Phone", true, true)) +
                if (remote) listOf(LibraryPlaybackDevice("remote", "Remote", true, true)) else emptyList()
            snapshot = snapshot.copy(devices = devices, selectedDeviceId = if (remote) "remote" else deviceId,
                selectedConnectionId = if (remote) "remote-socket" else "socket-$generation")
            if (remote && snapshot.state.queue.isEmpty()) {
                val track = track(LibraryItem("track", "R", "Remote song", hasAudio = true))
                snapshot = snapshot.copy(state = snapshot.state.copy(queue = listOf(track), position = 0,
                    desiredPlaying = true, occurrence = LibraryPlaybackOccurrence(track.key, track.id, "remote-event", 0.0, false)),
                    playback = snapshot.playback.copy(eventId = "remote-event", hasReportedPlayback = true, isPlaying = true))
            }
            current.emit(LibraryPlaybackServerMessage("snapshot", snapshot.sessionId, "socket-$generation", deviceId, snapshot = snapshot))
        }
        if (!remote && prepareOnJoin) begin(null, 0, "none")
        return requireNotNull(socket)
    }
    fun disconnect() {
        socket?.fail()
        live = false; pending = null
        snapshot = snapshot.copy(stateVersion = snapshot.stateVersion + 1, outputVersion = snapshot.outputVersion + 1,
            state = snapshot.state.copy(desiredPlaying = false), selectedDeviceId = "", selectedConnectionId = "", transfer = null,
            playback = snapshot.playback.copy(hasReportedPlayback = false, isPlaying = false))
    }
    private inner class Socket(override val identity: ValidatedSessionIdentity) : LibraryPlaybackTransport {
        override var isCurrent = true
        private val eventsChannel = Channel<LibraryPlaybackEvent>(128)
        override val events = eventsChannel.receiveAsFlow()
        private var commands = 0L
        private var sequences = 0L
        fun emit(message: LibraryPlaybackServerMessage) {
            // A real socket cannot synchronously return a result inside its caller's send().
            // Post delivery so the coordinator records the send receipt/basis first.
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (isCurrent) eventsChannel.trySend(LibraryPlaybackEvent.Message(message)).getOrThrow()
            }
        }
        override fun send(message: LibraryPlaybackClientMessage): LibraryPlaybackSend? {
            if (!isCurrent) return null
            val time = System.nanoTime()
            messages += message
            return when (message) {
                is LibraryPlaybackClientMessage.Command -> {
                    val id = (++commands).toString()
                    if (message.expectedStateVersion != null && message.expectedStateVersion != snapshot.stateVersion)
                        result(id, 0, "none", "unchanged", outcome = "rejected", code = "stale_state_version")
                    else command(id, message.command)
                    LibraryPlaybackSend(time, commandId = id)
                }
                is LibraryPlaybackClientMessage.Ack -> {
                    val sequence = ++sequences
                    latestSequence = sequence
                    ack(message, sequence)
                    LibraryPlaybackSend(time, clientSequence = sequence)
                }
                is LibraryPlaybackClientMessage.Report -> {
                    val sequence = ++sequences
                    latestSequence = sequence
                    report(message, sequence)
                    LibraryPlaybackSend(time, clientSequence = sequence)
                }
            }
        }
        fun fail() {
            if (!isCurrent) return
            eventsChannel.trySend(LibraryPlaybackEvent.Closed(LibraryPlaybackEvent.Reason.NETWORK)).getOrThrow()
            isCurrent = false
        }
        override fun close() {
            isCurrent = false; eventsChannel.close()
            if (socket === this) {
                live = false; pending = null
                snapshot = snapshot.copy(state = snapshot.state.copy(desiredPlaying = false), transfer = null,
                    playback = snapshot.playback.copy(hasReportedPlayback = false, isPlaying = false))
            }
        }
    }
    private fun emit(type: String, instruction: LibraryPlaybackInstruction? = null, result: LibraryPlaybackResult? = null,
        lease: LibraryPlaybackLease? = null, playback: LibraryPlaybackStatus? = null) {
        socket?.emit(LibraryPlaybackServerMessage(type, snapshot.sessionId,
            snapshot = snapshot.takeIf { type == "state" }, instruction = instruction, result = result, lease = lease, playback = playback))
    }
    private fun structure() {
        val event = snapshot.state.occurrence?.eventId.orEmpty()
        val facts = snapshot.playback.let { previous -> if (previous.eventId == event) previous else previous.copy(
            eventId = event, hasReportedPlayback = false, elapsed = snapshot.state.elapsed, duration = 0.0,
            isPlaying = false, isBuffering = false, isLoading = false, error = "") }
        snapshot = snapshot.copy(stateVersion = snapshot.stateVersion + 1,
            playback = facts.copy(stateVersion = snapshot.stateVersion + 1))
        emit("state")
    }
    private fun track(item: LibraryItem) = LibraryPlaybackTrack(item.id, item.name,
        item.artists.map { LibraryPlaybackArtist(it.name, it.id.takeUnless(String::isEmpty)) },
        item.albumName.orEmpty(), item.albumId.orEmpty(), item.durationMs / 1000.0,
        ++key, key - 1, "low", entryId = item.entryId)
    private fun result(id: String, count: Int, placement: String, execution: String, final: Boolean = true,
        outcome: String = "accepted", code: String = "") = emit("result", result = LibraryPlaybackResult(
        id, outcome, code, snapshot.stateVersion, count, placement, execution, final))
    private fun command(id: String, command: LibraryPlaybackCommand) {
        mutations += command.javaClass.simpleName
        when (command) {
            is LibraryPlaybackCommand.QueueIntent -> {
                resolutions.incrementAndGet()
                val selection = command.selection
                if (selection is LibraryPlaybackSelection.Occurrence) {
                    val at = snapshot.state.queue.indexOfFirst { it.key == selection.key }
                    check(at >= 0)
                    if (command.intent == "now") {
                        snapshot = snapshot.copy(state = snapshot.state.copy(position = at, desiredPlaying = true,
                            elapsed = 0.0, occurrence = event(snapshot.state.queue[at])))
                        structure(); begin(id, 0, "existing")
                    } else {
                        val before = snapshot.state.queue
                        val selected = before[at]
                        val rest = before.filterNot { it.key == selected.key }.toMutableList()
                        val insertion = when (command.intent) {
                            "low" -> rest.size
                            "next" -> snapshot.state.position + 1
                            else -> rest.indexOfLast { it.key in snapshot.state.highKeys }.takeIf { it > snapshot.state.position }?.plus(1)
                                ?: (snapshot.state.position + 1)
                        }
                        rest.add(insertion, selected)
                        val high = if (command.intent == "low") snapshot.state.highKeys - selected.key else snapshot.state.highKeys + selected.key
                        snapshot = snapshot.copy(state = snapshot.state.copy(queue = rest, highKeys = rest.filter { it.key in high }.map { it.key }))
                        structure(); result(id, 1, command.intent, "unchanged")
                    }
                    return
                }
                val context = when (selection) {
                    is LibraryPlaybackSelection.Collection -> selection.context
                    is LibraryPlaybackSelection.Track -> selection.context
                    else -> error("Unsupported fixture selection")
                }
                val rows = context?.let { requireNotNull(catalogs[it.id]) } ?: listOf(
                    LibraryItem("track", (selection as LibraryPlaybackSelection.Track).track.id, "Synthetic", hasAudio = true))
                val selected = if (selection is LibraryPlaybackSelection.Track) rows.indexOfFirst {
                    it.id == selection.track.id && (selection.track.entryId == null || it.entryId == selection.track.entryId)
                }.also { check(it >= 0) } else 0
                val additions = (if (command.intent == "now" || selection is LibraryPlaybackSelection.Collection) rows else listOf(rows[selected]))
                    .map { track(it).copy(intent = command.intent) }
                if (command.intent == "now") {
                    snapshot = snapshot.copy(state = snapshot.state.copy(queue = additions, position = selected, context = context,
                        desiredPlaying = true, elapsed = 0.0, highKeys = emptyList(), shuffle = command.shuffle == true,
                        occurrence = event(additions[selected])))
                    structure(); begin(id, additions.size, "replace")
                } else {
                    val before = snapshot.state
                    val insertion = when (command.intent) {
                        "low" -> before.queue.size
                        "next" -> before.position + 1
                        else -> before.queue.indexOfLast { it.key in before.highKeys }.takeIf { it > before.position }?.plus(1) ?: before.position + 1
                    }
                    val queue = before.queue.toMutableList().apply { addAll(insertion, additions) }
                    val high = if (command.intent == "low") before.highKeys else before.highKeys + additions.map { it.key }
                    snapshot = snapshot.copy(state = before.copy(queue = queue, highKeys = queue.filter { it.key in high }.map { it.key }))
                    structure(); result(id, additions.size, command.intent, "unchanged")
                }
            }
            LibraryPlaybackCommand.Pause -> {
                snapshot = snapshot.copy(state = snapshot.state.copy(desiredPlaying = false)); structure(); begin(id, 0, "none")
            }
            LibraryPlaybackCommand.Resume, LibraryPlaybackCommand.Retry -> {
                snapshot = snapshot.copy(state = snapshot.state.copy(desiredPlaying = true)); structure(); begin(id, 0, "none")
            }
            is LibraryPlaybackCommand.Seek -> {
                snapshot = snapshot.copy(state = snapshot.state.copy(elapsed = command.value)); structure(); begin(id, 0, "none")
            }
            LibraryPlaybackCommand.Next -> advance(id)
            LibraryPlaybackCommand.Previous -> {
                val at = (snapshot.state.position - 1).coerceAtLeast(0)
                snapshot = snapshot.copy(state = snapshot.state.copy(position = at, elapsed = 0.0, desiredPlaying = true,
                    occurrence = event(snapshot.state.queue[at]))); structure(); begin(id, 0, "none")
            }
            is LibraryPlaybackCommand.Jump -> {
                val at = snapshot.state.queue.indexOfFirst { it.key == command.key }; check(at >= 0)
                snapshot = snapshot.copy(state = snapshot.state.copy(position = at, elapsed = 0.0, desiredPlaying = true,
                    occurrence = event(snapshot.state.queue[at]))); structure(); begin(id, 0, "existing")
            }
            is LibraryPlaybackCommand.SetRepeat -> {
                snapshot = snapshot.copy(state = snapshot.state.copy(repeat = command.repeat)); structure(); result(id, 0, "none", "unchanged")
            }
            is LibraryPlaybackCommand.SetShuffle -> {
                val state = snapshot.state
                val past = state.queue.take(state.position + 1)
                val future = state.queue.drop(state.position + 1)
                val reordered = if (command.shuffle) future.reversed() else future.sortedBy { it.order }
                snapshot = snapshot.copy(state = state.copy(queue = past + reordered, shuffle = command.shuffle))
                structure(); result(id, 0, "none", "unchanged")
            }
            is LibraryPlaybackCommand.Remove -> {
                snapshot = snapshot.copy(state = snapshot.state.copy(queue = snapshot.state.queue.filterNot { it.key == command.key },
                    highKeys = snapshot.state.highKeys - command.key)); structure(); result(id, 0, "none", "unchanged")
            }
            is LibraryPlaybackCommand.Move -> {
                val state = snapshot.state
                val track = state.queue.single { it.key == command.key }
                val rest = state.queue.filterNot { it.key == command.key }.toMutableList()
                val at = command.afterKey?.let { anchor -> rest.indexOfFirst { it.key == anchor } + 1 } ?: state.position + 1
                rest.add(at, track)
                snapshot = snapshot.copy(state = state.copy(queue = rest.mapIndexed { i, t -> t.copy(order = i.toLong()) },
                    shuffle = false, highKeys = rest.drop(state.position + 1).map { it.key }))
                structure(); result(id, 0, "none", "unchanged")
            }
            else -> error("Unsupported cache-fixture command")
        }
    }
    private fun event(track: LibraryPlaybackTrack) = LibraryPlaybackOccurrence(track.key, track.id, "event-${++serial}", 0.0, false)
    private fun advance(commandId: String?) {
        val state = snapshot.state
        val next = if (state.repeat == "one") state.position else state.position + 1
        val at = if (next < state.queue.size) next else if (state.repeat == "all") 0 else -1
        if (at < 0) snapshot = snapshot.copy(state = state.copy(desiredPlaying = false))
        else snapshot = snapshot.copy(state = state.copy(position = at, elapsed = 0.0,
            occurrence = event(state.queue[at]), highKeys = state.highKeys - state.queue[at].key))
        structure(); begin(commandId, 0, "none")
    }
    fun preparePaused(item: LibraryItem) {
        val track = track(item)
        snapshot = snapshot.copy(state = snapshot.state.copy(queue = listOf(track), position = 0, desiredPlaying = false,
            elapsed = 0.0, highKeys = emptyList(), occurrence = event(track)))
        structure(); begin(null, 0, "replace")
    }
    private fun begin(commandId: String?, count: Int, placement: String) {
        val state = snapshot.state
        val instruction = LibraryPlaybackInstruction("instruction-${++serial}", if (live) "execute" else "prepare",
            if (live) snapshot.outputVersion else snapshot.outputVersion + 1, state.occurrence?.eventId.orEmpty(), state.elapsed,
            state.desiredPlaying, commandId = commandId, track = state.queue.getOrNull(state.position),
            lease = if (live) LibraryPlaybackLeaseTiming(latestSequence, 8000) else null)
        pending = Pending(instruction, commandId, count, placement)
        if (!live) {
            snapshot = snapshot.copy(transfer = LibraryPlaybackTransfer("phone", "phone", snapshot.selectedConnectionId,
                snapshot.selectedConnectionId, "preparing"))
            emit("state")
        }
        if (commandId != null) result(commandId, count, placement, "pending", final = false)
        emit("instruction", instruction = instruction)
    }
    private fun ack(ack: LibraryPlaybackClientMessage.Ack, sequence: Long) {
        val pending = pending ?: return
        if (ack.instructionId != pending.instruction.id) return
        val instruction = pending.instruction
        if (instruction.type in setOf("prepare", "release") && ack.status != "ok") {
            live = false
            snapshot = snapshot.copy(state = snapshot.state.copy(desiredPlaying = false), transfer = null)
            emit("state")
            pending.commandId?.let { result(it, pending.count, pending.placement,
                ack.status.takeIf { status -> status in setOf("blocked", "media") } ?: "uncertain") }
            this.pending = null
            return
        }
        when (instruction.type) {
            "prepare" -> {
                prepareAcks.incrementAndGet()
                val release = instruction.copy(id = "release-${++serial}", type = "release", track = null, desiredPlaying = false, lease = null)
                this.pending = pending.copy(instruction = release.copy(track = instruction.track))
                emit("instruction", instruction = release)
            }
            "release" -> {
                val activation = instruction.copy(id = "activate-${++serial}", type = "activate", outputVersion = instruction.outputVersion + 1,
                    desiredPlaying = snapshot.state.desiredPlaying, lease = LibraryPlaybackLeaseTiming(sequence, 8000))
                this.pending = pending.copy(instruction = activation)
                snapshot = snapshot.copy(outputVersion = activation.outputVersion, transfer = null,
                    playback = snapshot.playback.copy(outputVersion = activation.outputVersion, hasReportedPlayback = false))
                emit("instruction", instruction = activation); emit("state")
            }
            else -> {
                activationAcks.incrementAndGet()
                live = ack.status == "ok" && instruction.desiredPlaying
                executionId = instruction.id
                snapshot = snapshot.copy(transfer = null, state = snapshot.state.copy(desiredPlaying = live))
                emit("state")
                pending.commandId?.let { result(it, pending.count, pending.placement,
                    if (ack.status == "ok") "confirmed" else ack.status.takeIf { s -> s in setOf("blocked", "media") } ?: "uncertain") }
                this.pending = null
            }
        }
    }
    private fun report(report: LibraryPlaybackClientMessage.Report, sequence: Long) {
        reports.incrementAndGet()
        if (report.outputVersion != snapshot.outputVersion || report.eventId != snapshot.state.occurrence?.eventId.orEmpty() ||
            report.instructionId != executionId) return
        val facts = LibraryPlaybackStatus(snapshot.outputVersion, snapshot.stateVersion, ++scalar, report.eventId, true,
            report.elapsed, report.duration, report.isPlaying, report.isBuffering, report.isLoading, report.error)
        // This fixture does not simulate listening/history qualification or credit seeks.
        snapshot = snapshot.copy(playback = facts, state = snapshot.state.copy(elapsed = report.elapsed, duration = report.duration))
        emit("playback", playback = facts)
        if (report.ended) { advance(null); return }
        if (live && renewalsEnabled) emit("lease", lease = LibraryPlaybackLease(snapshot.outputVersion, report.eventId, sequence, 8000))
    }
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    override fun dispatch(request: RecordedRequest): MockResponse {
        if (request.path == "/api/auth/login") return json(SESSION)
            .setHeader("Set-Cookie", "spofify_session=cache-session; Path=/; HttpOnly; Max-Age=3600")
        if (request.getHeader("Cookie") != "spofify_session=cache-session") {
            unexpected += "Unauthenticated request"; return MockResponse().setResponseCode(401)
        }
        if (request.path == "/api/auth/session") return json(SESSION).setResponseCode(restoreStatus)
        val path = request.requestUrl!!.pathSegments
        if (path.size == 5 && path.take(3) == listOf("api", "library", "tracks") && path[4] == "audio") {
            val id = path[3]; check(id.length == 1 && id[0] in 'A'..'Z')
            audioFailureStatus?.let { audio += Audio(id, request.method!!, request.getHeader("Range"), 0); return MockResponse().setResponseCode(it) }
            if (malformedAudio) return MockResponse().setHeader("Content-Type", "audio/wav").setBody("not an audio container")
            val wav = audioPayload(id)
            val range = request.getHeader("Range")
            val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
            if (start !in wav.indices) return MockResponse().setResponseCode(416)
            val count = if (request.method == "HEAD") 0 else wav.size - start
            val observed = Audio(id, request.method!!, range, count); audio += observed; onAudioRequest?.invoke(observed)
            val response = MockResponse().setHeader("Content-Type", "audio/wav").setHeader("Cache-Control", "no-store")
                .setHeader("Accept-Ranges", "bytes").setHeader("Content-Length", wav.size - start)
            if (range != null) response.setResponseCode(206).setHeader("Content-Range", "bytes $start-${wav.lastIndex}/${wav.size}")
            if (request.method != "HEAD") response.setBody(Buffer().write(wav, start, count))
            if (id == delayedTrack) response.setBodyDelay(1200, java.util.concurrent.TimeUnit.MILLISECONDS)
            return response
        }
        if (request.path == "/api/me/history" && request.method == "POST") history.incrementAndGet()
        unexpected += "${request.method} ${request.requestUrl!!.encodedPath}"
        return MockResponse().setResponseCode(404)
    }
    fun audioPayload(id: String) = wav(id, sampleRate, (durationMillis(id) / 1000).toInt())
    fun durationMillis(id: String) = if (longCurrent && id == "A" || longFutureB && id == "B") 120_000L else normalTrackSeconds * 1000L
    fun fetched() = audio.filter { it.method == "GET" }.map { it.id }.toSet()
    fun scheduledBodies(id: String) = audio.filter { it.id == id && it.method == "GET" }.sumOf { it.scheduledBodyBytes.toLong() }
    companion object {
        private const val SESSION = """{"accountId":"cache-account","username":"listener","role":"user","csrfToken":"cache-csrf"}"""
        fun marker(id: String) = 0x1000 + id.single().code * 17
        fun wav(id: String, sampleRate: Int = 8000, seconds: Int = 12): ByteArray {
            val size = seconds * sampleRate * 2
            return ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(size + 36); put("WAVEfmt ".toByteArray())
                putInt(16); putShort(1); putShort(1); putInt(sampleRate); putInt(sampleRate * 2)
                putShort(2); putShort(16); put("data".toByteArray()); putInt(size)
                repeat(size / 2) { putShort(marker(id).toShort()) }
            }.array()
        }
    }
}
