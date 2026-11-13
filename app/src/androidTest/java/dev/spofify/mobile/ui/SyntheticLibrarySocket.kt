package dev.spofify.mobile.ui

import android.os.SystemClock
import dev.spofify.mobile.core.*
import kotlinx.serialization.json.*
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * SINGLE-output synthetic UI choreography, NOT the backend reducer or protocol acceptance.
 * No fabricated executor ACKs/facts, cross-output handoff, planned-next, or acoustic claims.
 * Unsupported commands/ended playback fail closed; the long UI WAV avoids natural-end tests.
 * Lease sequences echo actual received client sends. Only the CLIENT can validate its local
 * monotonic send-time deadline; this fixture never substitutes receipt time for that basis.
 */
internal class SyntheticLibrarySocket(
    private val resolve: (JsonObject, String) -> Selection,
    private val onHistory: (String) -> Unit,
) : AutoCloseable {
    init { VirtualAvdOnly.requireVerified() }

    data class Selection(val tracks: List<LibraryPlaybackTrack>, val context: LibraryPlaybackContext?, val selected: Int)
    class Rejected(val code: String) : RuntimeException()
    val calls = CopyOnWriteArrayList<String>()
    val failures = CopyOnWriteArrayList<String>() // fixed classifications only; never frame contents
    val queueReplacements = AtomicInteger()
    val qualifiedHistoryEvents = AtomicInteger()
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val session = UUID.randomUUID().toString()
    private val tickets = mutableMapOf<String, Long>()
    private var peer: Peer? = null
    private var closed = false
    private var stateVersion = 1L
    private var outputVersion = 1L
    private var playbackSequence = 0L
    private var nextKey = 0L
    private var queue = emptyList<LibraryPlaybackTrack>()
    private var position = -1
    private var context: LibraryPlaybackContext? = null
    private var shuffle = false
    private var repeat = "off"
    private var desired = false
    private var high = emptyList<Long>()
    private var event = ""
    private var progress = 0.0
    private var reported = false
    private var elapsed = 0.0
    private var duration = 0.0
    private var playing = false
    private var buffering = false
    private var loading = false
    private var error = ""
    private var hasFacts = false
    private var selected = false
    private var transfer: LibraryPlaybackTransfer? = null
    private var outstanding: LibraryPlaybackInstruction? = null
    private var execution: LibraryPlaybackInstruction? = null
    private var parked = false
    private var deadline = 0L
    private var operationDeadline = 0L
    private var drainUntil = 0L
    private var pendingResult: LibraryPlaybackResult? = null
    private var lastObservationAt = 0L
    private var lastObservationElapsed = 0.0
    private var lastObservationPlaying = false
    private fun now() = SystemClock.elapsedRealtime()
    private fun id() = UUID.randomUUID().toString()
    private fun current() = queue.getOrNull(position)

    init {
        timer.scheduleAtFixedRate({ synchronized(this) {
            try { tick() } catch (_: Exception) { peer?.let { fail(it, "fixture-tick-failure") } }
        } }, 100, 100, TimeUnit.MILLISECONDS)
    }

    @Synchronized fun ticket(): String {
        check(!closed)
        tickets.entries.removeAll { it.value <= now() }
        check(tickets.size < 16)
        return (id().replace("-", "") + id().replace("-", "")).also { tickets[it] = now() + 20_000 }
    }

    @Synchronized fun outputName(): String? = peer?.name?.takeIf { selected && outstanding == null }

    @Synchronized fun listener(): WebSocketListener {
        check(!closed)
        return Peer()
    }

    private inner class Peer : WebSocketListener() {
        var socket: WebSocket? = null
        var connection = ""
        var device = ""
        var name = ""
        var canPlay = false
        var sequence = 0L
        var sequenceAt = 0L
        var commandHighWater = 0L
        val receipts = linkedMapOf<String, Pair<JsonObject, LibraryPlaybackResult>>()
        val admittedAt = now()
        var retired = false
        override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(this@SyntheticLibrarySocket) {
            socket = webSocket
            if (closed || peer != null) fail(this, "unsupported-concurrent-output") else peer = this
        }
        override fun onMessage(webSocket: WebSocket, text: String) = synchronized(this@SyntheticLibrarySocket) {
            if (retired || peer !== this || closed) return@synchronized
            try {
                tick()
                if (retired) return@synchronized
                val value = StrictObject.parse(text)
                if (connection.isEmpty()) hello(this, value) else receive(this, value)
            } catch (_: Exception) { fail(this, "invalid-client-frame") }
        }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = synchronized(this@SyntheticLibrarySocket) { fail(this, "binary-client-frame") }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = synchronized(this@SyntheticLibrarySocket) {
            webSocket.close(code, null); retire(this)
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = synchronized(this@SyntheticLibrarySocket) { retire(this) }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = synchronized(this@SyntheticLibrarySocket) { retire(this) }
    }

    private fun hello(p: Peer, v: JsonObject) {
        require(v.keys == setOf("type", "ticket", "name", "deviceId", "canPlay"))
        require(v.string("type") == "hello")
        val expiry = tickets.remove(v.string("ticket")) ?: error("ticket")
        require(now() < expiry && now() - p.admittedAt < 5_000)
        p.name = v.string("name").also { require(it.trim { c -> c.isWhitespace() || c == '\uFEFF' }.isNotEmpty() && it.toByteArray().size <= 256 && it.codePointCount(0, it.length) <= 64 && it.none { c -> Character.isISOControl(c) }) }
        p.device = v.string("deviceId").also { require(it.isNotBlank() && it.toByteArray().size <= 128 && it.none { c -> Character.isISOControl(c) }) }
        p.canPlay = v.getValue("canPlay").jsonPrimitive.let { require(!it.isString); it.boolean }
        p.connection = id()
        calls += "hello"
        send("snapshot", buildJsonObject {
            put("connectionId", p.connection); put("deviceId", p.device); put("snapshot", snapshot())
        })
        if (now() >= drainUntil) prepare(null) // join is always stopped, never restored autoplay
    }

    private fun receive(p: Peer, v: JsonObject) {
        when (v.string("type")) {
            "command" -> command(p, v)
            "ack", "report" -> {
                val sequence = v.long("clientSequence")
                require(sequence > p.sequence && sequence <= LibraryPlaybackProtocol.MAX_SAFE_INTEGER)
                val message = if (v.string("type") == "ack") LibraryPlaybackClientMessage.Ack(
                    v.string("instructionId"), v.long("outputVersion"), v.string("eventId"), v.string("status"))
                else LibraryPlaybackClientMessage.Report(v.long("outputVersion"), v.string("eventId"), v.string("instructionId"),
                    v.double("elapsed"), v.double("duration"), v.boolean("isPlaying"), v.boolean("isBuffering"),
                    v.boolean("isLoading"), v.string("error"), v.boolean("ended"), v["planId"]?.jsonPrimitive?.content)
                require(Json.parseToJsonElement(LibraryPlaybackCodec.encode(message, clientSequence = sequence)) == v)
                p.sequence = sequence; p.sequenceAt = now()
                if (message is LibraryPlaybackClientMessage.Ack) ack(p, message) else report(p, message as LibraryPlaybackClientMessage.Report)
            }
            else -> error("variant")
        }
    }

    private fun command(p: Peer, v: JsonObject) {
        val c = v.getValue("command").jsonObject
        val command = when (c.string("type")) {
            "queueIntent" -> {
                val s = c.getValue("selection").jsonObject
                fun ctx() = s["context"]?.let { json.decodeFromJsonElement<LibraryPlaybackContext>(it) }
                val selection = when (s.string("kind")) {
                    "occurrence" -> LibraryPlaybackSelection.Occurrence(s.long("key"))
                    "collection" -> LibraryPlaybackSelection.Collection(checkNotNull(ctx()))
                    "track" -> { val t = s.getValue("track").jsonObject
                        LibraryPlaybackSelection.Track(LibraryPlaybackTrackRef(t.string("id"), t.optional("entryId"), t.optional("targetId"), t.optional("occurrenceId"), t.optional("selectionToken")), ctx()) }
                    else -> error("selection")
                }
                LibraryPlaybackCommand.QueueIntent(c.string("intent"), selection, c["shuffle"]?.jsonPrimitive?.boolean)
            }
            "pause" -> LibraryPlaybackCommand.Pause
            "resume" -> LibraryPlaybackCommand.Resume
            "retry" -> LibraryPlaybackCommand.Retry
            "next" -> LibraryPlaybackCommand.Next
            "previous" -> LibraryPlaybackCommand.Previous
            "seek" -> LibraryPlaybackCommand.Seek(c.double("value"))
            "jump" -> LibraryPlaybackCommand.Jump(c.long("key"))
            "remove" -> LibraryPlaybackCommand.Remove(c.long("key"))
            "move" -> LibraryPlaybackCommand.Move(c.long("key"), c["afterKey"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.long)
            "setShuffle" -> LibraryPlaybackCommand.SetShuffle(c.boolean("shuffle"))
            "setRepeat" -> LibraryPlaybackCommand.SetRepeat(c.string("repeat"))
            "rename" -> LibraryPlaybackCommand.Rename(c.string("name"), c.optional("deviceId"))
            "select" -> LibraryPlaybackCommand.Select(c.string("deviceId"))
            "clear" -> LibraryPlaybackCommand.Clear
            "close" -> LibraryPlaybackCommand.Close
            "deselect" -> LibraryPlaybackCommand.Deselect
            else -> error("command")
        }
        val commandId = v.string("id")
        val expected = v["expectedStateVersion"]?.jsonPrimitive?.long
        require(Json.parseToJsonElement(LibraryPlaybackCodec.encode(LibraryPlaybackClientMessage.Command(command, expected), commandId = commandId)) == v)
        p.receipts[commandId]?.let { (original, receipt) ->
            result(if (original == v) receipt else rejected(commandId, "conflict")); return
        }
        if (commandId.toLong() <= p.commandHighWater) { result(rejected(commandId, "expired")); return }
        p.commandHighWater = commandId.toLong()
        calls += "command"
        if (expected != null && expected != stateVersion) { remember(p, v, rejected(commandId, "stale_state_version")); return }
        // This UI subset does not model overlapping operations, transfers, deselect or end plans.
        if (outstanding != null) {
            // Do not make double-dispatch native tests pass by swallowing a second command
            // behind a simulated busy response. Unsupported choreography is an explicit RED.
            fail(p, "unsupported-overlapping-command"); return
        }
        if (!selected || command is LibraryPlaybackCommand.Deselect) {
            remember(p, v, rejected(commandId, "unavailable")); return
        }
        val before = state()
        val beforeFacts = hasFacts
        var count = 0
        var placement = "none"
        var execute = false
        try {
            when (command) {
                is LibraryPlaybackCommand.QueueIntent -> {
                    val intent = command.intent
                    val occurrence = command.selection as? LibraryPlaybackSelection.Occurrence
                    if (occurrence != null) {
                        val index = queue.indexOfFirst { it.key == occurrence.key }
                        if (index <= position) throw Rejected("invalid")
                        if (intent == "now") { position = index; newOccurrence(); desired = true; execute = true; placement = "existing" }
                        else {
                            val item = queue[index]
                            val remaining = queue.toMutableList().apply { removeAt(index) }
                            high = high.filterNot { it == item.key }
                            val at = if (intent == "low") remaining.size else position + 1 + if (intent == "high") high.size else 0
                            remaining.add(at, item); queue = remaining
                            if (intent != "low") high = queue.drop(position + 1).map { it.key }.filter { it in high || it == item.key }
                            placement = intent
                        }
                        count = 1
                    } else {
                        val selection = resolve(c.getValue("selection").jsonObject, intent)
                        require(selection.tracks.isNotEmpty() && selection.selected in selection.tracks.indices)
                        val items = selection.tracks.map { it.copy(key = ++nextKey, order = nextKey, intent = intent) }
                        count = items.size
                        if (intent == "now") {
                            queue = items; position = selection.selected; context = selection.context
                            high = emptyList(); shuffle = command.shuffle == true
                            if (shuffle) { queue = items.shuffled(); position = 0 }
                            newOccurrence(); desired = true; execute = true; placement = "replace"
                        } else {
                            val at = if (intent == "low") queue.size else position + 1 + if (intent == "high") high.size else 0
                            queue = queue.toMutableList().apply { addAll(at, items) }
                            if (position < 0) { position = 0; context = selection.context; newOccurrence(); execute = true }
                            if (intent != "low") high = queue.drop(position + 1).map { it.key }.filter { it in high || items.any { t -> t.key == it } }
                            placement = intent
                        }
                    }
                }
                LibraryPlaybackCommand.Pause -> { desired = false; execute = !parked }
                LibraryPlaybackCommand.Resume, LibraryPlaybackCommand.Retry -> { if (current() == null) throw Rejected("unavailable"); desired = true; execute = true }
                is LibraryPlaybackCommand.Seek -> { if (current() == null) throw Rejected("unavailable"); elapsed = command.value.coerceAtMost(current()!!.duration); execute = true }
                LibraryPlaybackCommand.Next -> { if (position + 1 >= queue.size) throw Rejected("unavailable"); position++; newOccurrence(); desired = true; execute = true }
                LibraryPlaybackCommand.Previous -> { if (current() == null) throw Rejected("unavailable"); if (elapsed < 3 && position > 0) position--; newOccurrence(); desired = true; execute = true }
                is LibraryPlaybackCommand.Jump -> { val at = queue.indexOfFirst { it.key == command.key }; if (at < 0) throw Rejected("invalid"); position = at; newOccurrence(); desired = true; execute = true }
                is LibraryPlaybackCommand.Remove -> { if (queue.drop(position + 1).none { it.key == command.key }) throw Rejected("invalid"); queue = queue.filterNot { it.key == command.key }; high = high.filterNot { it == command.key } }
                is LibraryPlaybackCommand.Move -> {
                    val upcoming = queue.drop(position + 1)
                    val item = upcoming.singleOrNull { it.key == command.key } ?: throw Rejected("invalid")
                    if (command.afterKey != null && upcoming.none { it.key == command.afterKey }) throw Rejected("invalid")
                    val moved = upcoming.filterNot { it.key == item.key }.toMutableList()
                    moved.add(if (command.afterKey == null) 0 else moved.indexOfFirst { it.key == command.afterKey } + 1, item)
                    queue = (queue.take(position + 1) + moved).mapIndexed { i, t -> t.copy(order = i + 1L) }
                    high = moved.map { it.key }; shuffle = false
                }
                is LibraryPlaybackCommand.SetShuffle -> {
                    shuffle = command.shuffle
                    val future = queue.drop(position + 1)
                    val priority = future.filter { it.key in high }
                    val low = future.filterNot { it.key in high }
                    queue = queue.take(position + 1) + priority + if (shuffle) low.shuffled() else low.sortedBy { it.order }
                }
                is LibraryPlaybackCommand.SetRepeat -> repeat = command.repeat
                is LibraryPlaybackCommand.Rename -> { if (command.deviceId != null && command.deviceId != p.device) throw Rejected("unavailable"); p.name = command.name }
                is LibraryPlaybackCommand.Select -> if (command.deviceId != p.device) throw Rejected("unavailable")
                LibraryPlaybackCommand.Clear -> { queue = queue.take(position + 1); high = emptyList() }
                LibraryPlaybackCommand.Close -> { queue = emptyList(); position = -1; high = emptyList(); event = ""; elapsed = 0.0; desired = false; execute = true }
                LibraryPlaybackCommand.Deselect -> throw Rejected("unavailable")
            }
            if (snapshot().getValue("state").toString().toByteArray().size > LibraryPlaybackProtocol.STATE_BYTES)
                throw Rejected("capacity")
        } catch (failure: Rejected) {
            // Prospective state-byte admission is all-or-error, not prefix truncation. Queue
            // keys already reserved may be skipped, but no visible/current state is committed.
            queue = before.queue; position = before.position; context = before.context
            shuffle = before.shuffle; repeat = before.repeat; desired = before.desiredPlaying
            high = before.highKeys; elapsed = before.elapsed; duration = before.duration
            playing = before.isPlaying; buffering = before.isBuffering; loading = before.isLoading; error = before.error
            event = before.occurrence?.eventId.orEmpty(); progress = before.occurrence?.progress ?: 0.0
            reported = before.occurrence?.reported ?: false; hasFacts = beforeFacts
            lastObservationPlaying = false // reject never accrues speculative listening time
            remember(p, v, rejected(commandId, failure.code)); return
        }
        if (placement == "replace") queueReplacements.incrementAndGet()
        high = high.filter { key -> queue.drop(position + 1).any { it.key == key } }
        if (!p.canPlay) desired = false
        stateVersion++
        val receipt = LibraryPlaybackResult(commandId, "accepted", "", stateVersion, count, placement, if (execute) "pending" else "unchanged", !execute)
        p.receipts[commandId] = v to receipt
        trimReceipts(p)
        if (execute) {
            pendingResult = receipt
            if (parked) prepare(commandId) else grant("execute", commandId, p.sequence)
        } else publish()
        result(receipt)
    }

    private fun newOccurrence() {
        event = id(); elapsed = 0.0; duration = current()?.duration ?: 0.0; progress = 0.0; reported = false
        hasFacts = false; playing = false; buffering = false; loading = false; error = ""
        lastObservationAt = 0; lastObservationPlaying = false
    }
    private fun prepare(commandId: String?) {
        val p = peer ?: return
        transfer = LibraryPlaybackTransfer(if (selected) p.device else "", p.device, if (selected) p.connection else "", p.connection, "preparing")
        stateVersion++; publish()
        control("prepare", commandId)
    }
    private fun control(type: String, commandId: String?) {
        val instruction = LibraryPlaybackInstruction(id(), type, outputVersion, event, elapsed, false, commandId,
            if (type == "prepare" && pCanPlay()) current() else null)
        outstanding = instruction; operationDeadline = now() + 8_000
        instruction(instruction)
    }
    private fun pCanPlay() = peer?.canPlay == true
    private fun grant(type: String, commandId: String?, sequence: Long) {
        val p = peer ?: return
        require(sequence > 0 && sequence == p.sequence && now() - p.sequenceAt < 8_000)
        if (type == "activate") { outputVersion++; selected = true }
        parked = false; deadline = now() + 8_000; hasFacts = false
        lastObservationPlaying = false // absolute execution/seek is not listening progress
        val value = LibraryPlaybackInstruction(id(), type, outputVersion, event, elapsed, desired, commandId,
            if (p.canPlay) current() else null, LibraryPlaybackLeaseTiming(sequence, 8_000))
        execution = value; outstanding = value; operationDeadline = now() + 8_000
        instruction(value); stateVersion++; publish()
    }
    private fun ack(p: Peer, a: LibraryPlaybackClientMessage.Ack) {
        val value = outstanding ?: error("unsolicited ack")
        require(a.instructionId == value.id && a.outputVersion == value.outputVersion && a.eventId == value.eventId)
        calls += "ack"
        outstanding = null
        when (value.type) {
            "prepare" -> {
                require(a.status == "ok") // no simulated success/trackless retry after native failure
                if (selected) {
                    transfer = transfer?.copy(phase = "releasing"); publish(); control("release", value.commandId)
                } else grant("activate", value.commandId, p.sequence)
            }
            "release" -> { require(a.status == "ok"); grant("activate", value.commandId, p.sequence) }
            "activate", "execute" -> {
                require(a.status != "rejected")
                parked = a.status in setOf("blocked", "media") || !value.desiredPlaying
                if (parked) deadline = 0
                if (a.status in setOf("blocked", "media")) desired = false
                transfer = null; stateVersion++; publish()
                pendingResult?.let { initial ->
                    val final = initial.copy(execution = if (a.status == "ok") "confirmed" else a.status, final = true)
                    p.receipts[initial.id]?.let { p.receipts[initial.id] = it.first to final }
                    result(final); pendingResult = null
                }
            }
        }
    }
    private fun report(p: Peer, r: LibraryPlaybackClientMessage.Report) {
        val active = execution ?: error("missing execution")
        require(selected && r.outputVersion == outputVersion && r.eventId == event && r.instructionId == active.id)
        require(!r.ended && r.planId == null) // no invented admitted-next/end acceptance
        require(!parked || !r.isPlaying)
        require(parked || now() < deadline)
        require(parked || r.error.isEmpty()) // spontaneous failure recovery is outside this UI subset
        val before = status()
        val at = now()
        // Only successive real playing reports accrue listening, bounded by wall-time as well
        // as positive media progression. Seeks/execution changes reset the observation anchor.
        if (lastObservationPlaying && r.isPlaying && !r.isBuffering && !r.isLoading && r.error.isEmpty()) {
            val delta = r.elapsed - lastObservationElapsed
            val wall = (at - lastObservationAt) / 1000.0
            if (delta > 0 && delta <= wall + 0.5) progress += minOf(delta, wall)
        }
        lastObservationAt = at; lastObservationElapsed = r.elapsed; lastObservationPlaying = r.isPlaying && !r.isBuffering && !r.isLoading && r.error.isEmpty()
        if (!reported && progress >= 1 && current() != null) {
            reported = true; qualifiedHistoryEvents.incrementAndGet(); onHistory(current()!!.id)
        }
        elapsed = r.elapsed; duration = r.duration; playing = r.isPlaying; buffering = r.isBuffering; loading = r.isLoading; error = r.error
        hasFacts = true; calls += "report"
        val changed = status() != before
        if (changed) playbackSequence++
        if (!parked) {
            deadline = at + 8_000
            send("lease", buildJsonObject { put("lease", json.encodeToJsonElement(LibraryPlaybackLease(outputVersion, event, p.sequence, 8_000))) })
        }
        if (changed) send("playback", buildJsonObject { put("playback", json.encodeToJsonElement(status())) })
    }
    private fun status() = LibraryPlaybackStatus(outputVersion, stateVersion, playbackSequence, event, hasFacts, elapsed, duration, playing, buffering, loading, error)
    private fun state() = LibraryPlaybackState(1, queue, position, context, shuffle, repeat, desired, high, elapsed, duration, playing, buffering, loading, error, false, "",
        current()?.let { LibraryPlaybackOccurrence(it.key, it.id, event, progress, reported) })
    private fun snapshot(): JsonObject {
        val p = peer
        val state = state()
        val encoded = json.encodeToJsonElement(state).jsonObject.toMutableMap()
        encoded["context"] = context?.let { json.encodeToJsonElement(it) } ?: JsonNull
        encoded["occurrence"] = state.occurrence?.let { json.encodeToJsonElement(it) } ?: JsonNull
        return buildJsonObject {
            put("sessionId", session); put("stateVersion", stateVersion); put("outputVersion", outputVersion)
            put("state", JsonObject(encoded)); put("playback", json.encodeToJsonElement(status()))
            put("devices", JsonArray(if (p == null || p.connection.isEmpty()) emptyList() else listOf(json.encodeToJsonElement(LibraryPlaybackDevice(p.device, p.name, true, p.canPlay)))))
            put("selectedDeviceId", if (selected) p?.device.orEmpty() else "")
            put("selectedConnectionId", if (selected) p?.connection.orEmpty() else "")
            put("transfer", transfer?.let { json.encodeToJsonElement(it) } ?: JsonNull)
            put("drainUntilMs", if (drainUntil > now()) System.currentTimeMillis() + drainUntil - now() else 0)
        }
    }
    private fun publish() = send("state", buildJsonObject { put("snapshot", snapshot()) })
    private fun instruction(value: LibraryPlaybackInstruction) = send("instruction", buildJsonObject { put("instruction", json.encodeToJsonElement(value)) })
    private fun result(value: LibraryPlaybackResult) = send("result", buildJsonObject { put("result", json.encodeToJsonElement(value)) })
    private fun rejected(id: String, code: String) = LibraryPlaybackResult(id, "rejected", code, stateVersion, 0, "none", "unchanged", true)
    private fun remember(p: Peer, request: JsonObject, receipt: LibraryPlaybackResult) { p.receipts[receipt.id] = request to receipt; trimReceipts(p); result(receipt) }
    private fun trimReceipts(p: Peer) { while (p.receipts.size > 128) p.receipts.remove(p.receipts.keys.first()) }
    private fun send(type: String, fields: JsonObject) {
        val frame = buildJsonObject { put("type", type); put("sessionId", session); fields.forEach { (k, v) -> put(k, v) } }.toString()
        LibraryPlaybackCodec.decodeServer(frame) // current strict production schema, never old DTOs
        val socket = peer?.socket ?: return
        require(socket.queueSize() + frame.toByteArray().size <= 5L * 1024 * 1024 && socket.send(frame))
    }
    private fun tick() {
        if (closed) return
        val p = peer ?: return
        if (p.connection.isEmpty()) { if (now() - p.admittedAt >= 5_000) fail(p, "hello-timeout"); return }
        if (outstanding != null && now() >= operationDeadline || !parked && deadline > 0 && now() >= deadline) {
            fail(p, "execution-timeout"); return
        }
        if (!selected && transfer == null && outstanding == null && now() >= drainUntil) prepare(null)
    }
    private fun fail(p: Peer, classification: String) { failures += classification; p.socket?.close(1008, "Synthetic fixture closed"); retire(p) }
    private fun retire(p: Peer) {
        if (p.retired) return
        p.retired = true
        if (peer !== p) return
        // Socket loss is NOT stopped proof. No successor grant until the outstanding lease
        // deadline; a previously exact stopped ACK is the sole no-drain case.
        drainUntil = maxOf(drainUntil, deadline)
        peer = null; selected = false; desired = false; parked = false; deadline = 0
        outstanding = null; execution = null; transfer = null; pendingResult = null
        playing = false; hasFacts = false; lastObservationPlaying = false; outputVersion++; stateVersion++
    }
    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true; tickets.clear(); peer?.let { it.socket?.cancel(); retire(it) }
        }
        timer.shutdownNow()
        check(timer.awaitTermination(5, TimeUnit.SECONDS)) { "Synthetic socket timer did not stop" }
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.let { require(it.isString); it.content }
    private fun JsonObject.optional(key: String): String? = if (containsKey(key)) string(key) else null
    private fun JsonObject.long(key: String) = getValue(key).jsonPrimitive.long
    private fun JsonObject.double(key: String) = getValue(key).jsonPrimitive.double
    private fun JsonObject.boolean(key: String) = getValue(key).jsonPrimitive.boolean

    /** Strict syntax plus token-level duplicate keys before kotlinx JSON can overwrite them. */
    private class StrictObject(private val source: String) {
        private var at = 0
        private fun whitespace() { while (at < source.length && source[at] in " \r\n\t") at++ }
        private fun take(c: Char): Boolean { whitespace(); return if (source.getOrNull(at) == c) { at++; true } else false }
        private fun string(): String {
            whitespace(); require(source.getOrNull(at) == '"'); val start = at++; var escaped = false
            while (at < source.length) {
                val c = source[at++]
                if (!escaped && c == '"') {
                    val decoded = Json.parseToJsonElement(source.substring(start, at)).jsonPrimitive.content
                    var index = 0
                    while (index < decoded.length) {
                        val char = decoded[index++]
                        if (char.isHighSurrogate()) { require(index < decoded.length && decoded[index++].isLowSurrogate()) }
                        else require(!char.isLowSurrogate())
                    }
                    return decoded
                }
                escaped = !escaped && c == '\\'
            }
            error("string")
        }
        private fun value(depth: Int) {
            require(depth <= 8); whitespace()
            when (source.getOrNull(at)) {
                '{' -> {
                    at++; val keys = mutableSetOf<String>()
                    if (!take('}')) {
                        do { require(keys.add(string())); require(take(':')); value(depth + 1) } while (take(','))
                        require(take('}'))
                    }
                }
                '[' -> error("client arrays")
                '"' -> string()
                else -> {
                    val start = at
                    while (at < source.length && source[at] !in ",]} \r\n\t") at++
                    require(at > start)
                    val token = source.substring(start, at)
                    if (token !in setOf("null", "true", "false")) {
                        val number = token.toDouble(); require(number.isFinite() && kotlin.math.abs(number) <= LibraryPlaybackProtocol.MAX_SAFE_INTEGER)
                    }
                }
            }
        }
        companion object {
            fun parse(raw: String): JsonObject {
                require(raw.toByteArray().size in 1..LibraryPlaybackProtocol.CLIENT_BYTES)
                val element = Json.parseToJsonElement(raw).jsonObject
                StrictObject(raw).apply { value(0); whitespace(); require(at == raw.length) }
                return element
            }
        }
    }
}
