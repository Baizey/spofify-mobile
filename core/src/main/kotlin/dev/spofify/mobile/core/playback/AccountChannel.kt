package dev.spofify.mobile.core.playback

import dev.spofify.mobile.core.auth.OnlineGrant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

private object EmptyPlaybackLibrary : LocalPlaybackLibrary {
    override fun resolve(track: PlaybackTrackReference?, context: PlaybackContext?) = null
    override suspend fun hasAudio(track: PlaybackTrack, isAuthorized: () -> Boolean) = false
}

/** One account and one engine. The socket is a replaceable binding, not the output lifetime. All
 * state transitions are on the player looper; only the native operation guard is read off-looper. */
class AccountChannel(
    private val transport: PlaybackTransport, audio: AudioExecutor, private val scope: CoroutineScope,
    private val clock: PlaybackClock = SystemPlaybackClock, private val current: () -> Boolean = { true },
    library: LocalPlaybackLibrary = EmptyPlaybackLibrary,
    recordPlay: (dev.spofify.mobile.core.personal.PersonalHistoryEntry) -> Unit = {},
    private val canPlaySharedPlaylist: (String) -> Boolean = { true },
) : AutoCloseable {
    private val view = MutableStateFlow(PlaybackView(localOutput = true, offline = true))
    val state = view.asStateFlow()
    private var socket: PlaybackConnection? = null
    var socketGrant: OnlineGrant? = null
        private set
    private var worker: Job? = null
    private var life = 0L
    private var connection = PlaybackConnectionState.DISCONNECTED
    private var manual = false
    private var superseded = false
    private var online = false
    private var retainedLocal = false
    @Volatile private var localAuthority = true
    private var deviceId = ""
    private var connectionId = ""
    private var sessionId = ""
    private var owner = AccountOwner(0)
    private var devices = emptyList<AccountDevice>()
    private var revision = 0L
    private var confirmed = AccountState.Empty
    private var actual: AccountObservation? = null
    private var nativeCursor = AccountCursor.Empty
    private var exhaustedVisit: Pair<String, Long>? = null
    private var nativeFact: AccountObservation? = null
    private var nativeRevision = 0L
    private var execution = 0L
    private data class AdvanceReport(val advance: AccountAdvance, var observation: AccountObservation? = null,
        var sentAt: Long? = null)
    private val advances = ArrayDeque<AdvanceReport>()
    private val advancing get() = advances.firstOrNull()?.advance
    private data class ConditionalExecution(val id: String, val cursor: AccountCursor)
    private var transportCommit: ConditionalExecution? = null
    private val handledExecutions = linkedSetOf<String>()
    private fun nativeAhead() = advancing != null || transportCommit != null
    private var personalSync = false
    private var sequence = 0L
    private var commandCounter = 0L
    private var established = 0L
    private var desynced = false
    private val sampleClock = SampleClock(clock)
    private val projection = AccountProjection(library)
    private val pending = linkedMapOf<String, AccountProjection.Pending>()
    private val timeouts = mutableMapOf<String, Long>()
    private var executing: String? = null
    private val settlements = ArrayList<AccountSettlement>()
    private var error: String? = null
    private val listeners = mutableSetOf<(JsonObject?, OnlineGrant?) -> Unit>()
    private val history = PlaybackHistory(clock, recordPlay)
    private fun sampleHistory(facts: AudioFacts) {
        val q = engine.state.value
        history.sample(q.current, q.occurrence?.eventId, sessionId.takeIf { isOwner() }, facts, !online || retainedLocal)
    }
    private val engine = LocalPlaybackController(audio, object : LocalPlaybackLibrary by library {
        override suspend fun hasAudio(track: PlaybackTrack, isAuthorized: () -> Boolean) =
            if (online && !retainedLocal) isAuthorized() else library.hasAudio(track, isAuthorized)
    }, scope, { localAuthority && current() }, onNaturalEnd = ::ended, onObserved = ::observed,
        onFailure = ::failed, outputVersion = { owner.generation })
    private val nativeView = scope.launch { engine.state.collect { if (!online || retainedLocal) publish() } }
    private val ticker = scope.launch {
        while (isActive) {
            delay(250)
            if (online) {
                val now = clock.monotonicMs()
                for (id in timeouts.filterValues { it <= now }.keys) {
                    val bound = pending[id]?.terminalAt
                    if (bound != null && bound > revision) {
                        // A confirmed result exposed a missing state revision. Don't discard its
                        // projection without that watermark; repair it or retire this binding.
                        if (desynced) socket?.close()
                        else {
                            timeouts[id] = now + SETTLEMENT_MS
                            requestSync()
                        }
                        break
                    }
                    pending.remove(id); timeouts.remove(id)
                    error = "unavailable"
                }
                if (advances.firstOrNull()?.sentAt?.let { now - it >= ADVANCE_CONFIRM_MS } == true) socket?.close()
                publish()
            }
        }
    }

    fun onPersonal(fn: (JsonObject?, OnlineGrant?) -> Unit): AutoCloseable {
        listeners += fn
        return AutoCloseable { listeners -= fn }
    }
    fun sendPersonal(command: JsonObject) = online && send(fields("type" to "personal", "command" to command))
    fun syncPersonal(): Boolean {
        if (!online) return false
        if (desynced || personalSync) return true // Playback repair also sends a personal snapshot.
        personalSync = send(fields("type" to "sync"))
        return personalSync
    }
    fun matchesLocalAudio(binding: AudioBinding): Boolean = localAuthority && engine.operationIsCurrent(binding)
    private fun isOwner() = online && !retainedLocal && owner.deviceId == deviceId
    private fun localQueue(): AccountState = AccountState.from(engine.state.value)
    private fun projected() = projection.project(confirmed, pending.values)
    private fun displayBasis(): AccountState = if (isOwner() && nativeAhead() && confirmed.queue.any { it.key == nativeCursor.key })
        confirmed.copy(cursor = nativeCursor) else confirmed

    private fun publish() {
        val v = view.value
        val q: PlaybackQueueState
        var fact: AccountObservation? = null
        if (!online && !superseded || retainedLocal) {
            q = engine.state.value
        } else {
            // Do not roll an owner UI back to the outgoing row while EOF is in flight.
            val basis = displayBasis()
            val projected = projection.project(basis, pending.values)
            val sample = if (isOwner()) nativeFact else actual
            val overlayChangesTransport = projected.cursor != basis.cursor ||
                pending.values.any { !it.reflected && it.command.action.kind in TRANSPORT_ACTIONS &&
                    it.command.basis.occurrence == projected.cursor.occurrence }
            val compatible = sample?.takeIf { !overlayChangesTransport && !desynced && owner.deviceId != null && it.matches(projected.cursor) }
            val now = if (isOwner()) clock.wallTimeMs() else sampleClock.serverNow()
            val age = compatible?.let { sample -> now?.minus(sample.sampledAtMs) }
            fact = compatible?.let { if (it.status == "playing" && (age == null || age !in -2_000..10_000)) it.copy(status = "stopped") else it }
            val extrapolate = if (!isOwner() && compatible?.status == "playing") (age ?: 0).coerceIn(0, 10_000) else 0
            val pos = fact?.let { (it.positionMs + extrapolate).coerceAtMost(MAX_SAFE_INTEGER) } ?: projected.cursor.positionMs
            q = projected.presentation(fact, pos).let { p ->
                if (isOwner() && fact?.status == "failed") p.copy(error = engine.state.value.error.ifEmpty { "media" }) else p
            }
        }
        val outputVersion = owner.generation
        val p = PlaybackStatus(outputVersion, revision, sequence, q.occurrence?.eventId.orEmpty(),
            if (!retainedLocal && (online || superseded)) fact != null else q.current != null, q.elapsed, q.duration,
            q.isPlaying, q.isBuffering, q.isLoading, q.error)
        val snap = PlaybackSnapshot(sessionId, revision, outputVersion, q, p,
            if (online) devices.map { PlaybackDevice(it.deviceId, it.name, true, it.canPlay) } else emptyList(),
            if (online) owner.deviceId.orEmpty() else "", if (isOwner()) connectionId else "", null, 0)
        val next = PlaybackView(connection, sessionId, connectionId, deviceId, snap, p, error,
            localAuthority, !online && !superseded, superseded, established,
            transferPending = online && pending.values.any { it.command.action.kind == "select" }, isolatedOutput = retainedLocal)
        if (next != v) view.value = next
    }

    /** Stop transport only; an owner keeps its prepared binding and can continue independently. */
    fun setOffline(manual: Boolean) {
        if (connection == PlaybackConnectionState.CLOSED || !current()) return
        this.manual = manual
        if (!manual) {
            if (superseded) { superseded = false; localAuthority = true; publish() }
            return
        }
        ++life
        worker?.cancel()
        worker = null
        socket?.close()
        socket = null
        detach(false)
    }

    fun connect(name: String, deviceId: String, personalGrant: OnlineGrant? = null): Job {
        if (!current() || manual || superseded || connection == PlaybackConnectionState.CLOSED) return scope.launch { }
        val attempt = ++life
        worker?.cancel()
        socket?.close()
        socket = null
        if (online) detach(false)
        this.deviceId = deviceId
        connection = PlaybackConnectionState.CONNECTING
        publish()
        if (attempt != life || !current() || manual) return scope.launch { }
        return scope.launch {
            if (attempt != life || !current() || manual) return@launch
            var conn: PlaybackConnection? = null
            var pulse: Job? = null
            var handshake: Job? = null
            try {
                val started = clock.monotonicMs()
                val live = withTimeout(ATTEMPT_MS) { transport.connect(name, deviceId) }
                conn = live
                if (attempt != life || !current()) return@launch
                socket = live
                socketGrant = personalGrant
                sampleClock.reset()
                sequence = 0
                commandCounter = 0
                handledExecutions.clear()
                var initial = true
                handshake = launch { delay((ATTEMPT_MS - (clock.monotonicMs() - started)).coerceAtLeast(0)); if (initial) live.close() }
                live.messages.collect { text ->
                    if (life != attempt || !current()) return@collect
                    val frame = playbackJson.parseToJsonElement(text).jsonObject
                    require(!initial || frame.kind == "snapshot")
                    when (frame.kind) {
                        "snapshot" -> {
                            snapshot(frame, initial)
                            if (initial) {
                                initial = false
                                handshake.cancel()
                                pulse = launch {
                                    while (isActive && life == attempt && current()) {
                                        if (isOwner()) send(fields("type" to "refresh", "generation" to owner.generation))
                                        ping()
                                        delay(2_000)
                                    }
                                }
                            }
                        }
                        "ownership" -> ownership(frame)
                        "state" -> stateUpdate(frame)
                        "execute" -> conditionalTransport(frame)
                        "observation" -> observation(frame)
                        "result" -> result(frame)
                        "offer" -> if (frame.long("generation") == owner.generation && online && !desynced)
                            send(fields("type" to "accept", "id" to frame.string("id"), "generation" to owner.generation))
                        "pong" -> { sampleClock.accept(frame.long("clientTimeMs"), frame.long("serverTimeMs")); publish() }
                        "personal" -> listeners.toList().forEach { it(frame.getValue("update").jsonObject, personalGrant) }
                        else -> error("Unknown account frame")
                    }
                }
            } catch (_: TimeoutCancellationException) {
                // One deadline covers both transport and the first snapshot.
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // Invalid transport or frame. Isolate this binding rather than mutating the next one.
            } finally {
                handshake?.cancel()
                pulse?.cancel()
                conn?.close()
                if (life == attempt && connection != PlaybackConnectionState.CLOSED) {
                    socket = null
                    detach(superseded || conn?.superseded == true)
                }
            }
        }.also { worker = it }
    }

    private fun detach(replaced: Boolean) {
        socketGrant = null
        personalSync = false
        val fallback = if (!replaced && !retainedLocal && online && !isOwner()) {
            // One queue per account: a controller keeps the latest confirmed shared
            // traversal, never its old isolated queue or an optimistic prediction.
            val measured = actual?.takeIf { it.matches(confirmed.cursor) }
            confirmed.presentation(elapsedMs = measured?.positionMs ?: confirmed.cursor.positionMs)
                .copy(desiredPlaying = false)
        } else null
        if (replaced || !retainedLocal && online && !isOwner()) {
            localAuthority = false
            engine.resetOutput()
            nativeCursor = AccountCursor.Empty
            nativeFact = null
        }
        online = false
        retainedLocal = false
        superseded = replaced
        localAuthority = !replaced
        advances.clear()
        clearPending()
        clearExecution()
        error = null
        connectionId = ""
        connection = PlaybackConnectionState.DISCONNECTED
        fallback?.let { engine.adopt(it, execute = false) }
        publish()
        listeners.toList().forEach { it(null, null) }
    }

    private fun snapshot(m: JsonObject, initial: Boolean) {
        // No part of a failed handshake can reset the reconnect budget or authorize audio.
        val nextSession = m.string("sessionId").also { require(it.isNotEmpty()) }
        val nextRevision = m.long("revision").also { require(it in 0..MAX_SAFE_INTEGER) }
        val nextOwner = m.decode<AccountOwner>("owner").also {
            require(it.generation in 0..MAX_SAFE_INTEGER && it.deviceId?.isEmpty() != true)
        }
        if (online && nextSession == sessionId && (nextOwner.generation < owner.generation || nextRevision < revision)) return
        val next = m.decode<AccountState>("player").valid()
        val roster = m.decode<List<AccountDevice>>("devices")
        val serverTime = m.long("serverTimeMs").also { require(it in 0..MAX_SAFE_INTEGER) }
        val bindingId = m.string("connectionId").also { require(it.isNotEmpty()) }
        val sample = m.optional<AccountObservation>("observation")?.takeIf { it.matches(next.cursor) && it.valid() }
        val previouslyOnline = online
        val previouslyOwner = isOwner()
        val old = confirmed
        val generationChanged = nextSession != sessionId || nextOwner.generation != owner.generation
        val routineSync = personalSync && !desynced && previouslyOnline && !generationChanged &&
            nextRevision == revision && next == old && nextOwner == owner && bindingId == connectionId
        personalSync = false
        if (routineSync) {
            // A personal freshness check also echoes the canonical player. The unchanged echo
            // does not reject a local EOF or conditional action still waiting to be reflected.
            devices = roster
            actual = sample
            sampleClock.seed(serverTime)
            publish()
            return
        }
        val local = localQueue()
        if (!previouslyOnline && localAuthority &&
            (local.cursor.desiredPlaying || engine.state.value.error == "connection")) retainedLocal = true
        val localFailure = engine.state.value.error
        val pausedAfterLoss = !previouslyOnline && nextOwner.deviceId == deviceId &&
            local.cursor.occurrence != null && local.cursor.occurrence == next.cursor.occurrence && !local.cursor.desiredPlaying
        if (generationChanged) {
            if (!previouslyOnline || nextSession != sessionId) clearPending()
            else {
                // An accepted catalog choice stays live through handoff. Keep its projection,
                // not a replay of its old command or old owner's execution settlement.
                val unaccepted = pending.values.filterNot { it.accepted }.map { it.command.id }
                unaccepted.forEach { pending.remove(it); timeouts.remove(it) }
            }
            clearExecution()
            handledExecutions.clear()
            exhaustedVisit = null
            if (nextSession != sessionId) projection.resetCollections()
        }
        devices = roster
        confirmed = next
        sessionId = nextSession
        owner = nextOwner
        revision = nextRevision
        connectionId = bindingId
        connection = PlaybackConnectionState.READY
        if (initial) ++established
        online = true
        actual = sample
        sampleClock.seed(serverTime)
        desynced = false
        projection.rememberCollection(confirmed.queue, confirmed.collectionFirst)
        retireSettled()
        val existingVisit = nativeCursor
        val awaitingTransport = transportCommit
        val committedTransport = awaitingTransport?.cursor == next.cursor
        advances.clear()
        transportCommit = null
        if (retainedLocal) {
            // The socket is recovered (including personal updates) but this local visit
            // remains private. Do not replace its queue or report progress to the room.
            publish()
            return
        }
        val own = isOwner()
        if (!own) {
            localAuthority = false
            // Revoke and pause isolated sound before projecting the remote snapshot.
            engine.resetOutput()
            nativeFact = null
            nativeCursor = AccountCursor.Empty
        } else {
            localAuthority = true
            val keep = previouslyOwner && !generationChanged &&
                (if (awaitingTransport != null) committedTransport else old.cursor == next.cursor) &&
                existingVisit.sameVisit(next.cursor) && existingVisit.desiredPlaying == next.cursor.desiredPlaying
            if (!keep) { nativeFact = null; clearExecution() }
            nativeRevision = revision
            nativeCursor = next.cursor
            val operation = ++execution
            publish() // FGS admission must see this device as current before the native start.
            if (execution != operation || !isOwner()) return
            engine.adopt(next.presentation().let { if (pausedAfterLoss) it.copy(desiredPlaying = false,
                elapsed = local.cursor.positionMs.seconds(), error = localFailure) else if (keep) it.copy(elapsed = local.cursor.positionMs.seconds(),
                isPlaying = engine.state.value.isPlaying, error = localFailure,
                desiredPlaying = it.desiredPlaying && localFailure.isEmpty()) else it }, !keep)
            if (keep) syncLocalMeasurements()
        }
        publish()
    }

    private fun syncLocalMeasurements() {
        val q = engine.state.value
        val sample = engine.sampleCurrent()
        if (sample != null) observed(sample)
        else if (q.error.isNotEmpty()) failed(q.error, AudioFacts(elapsed = q.elapsed))
        else if (!q.desiredPlaying) observed(AudioFacts(elapsed = q.elapsed))
    }

    private fun ownership(m: JsonObject) {
        val received = m.decode<AccountOwner>("owner")
        if (received != owner) { requestSync(); return }
        devices = m.decode("devices")
        publish()
    }

    private fun stateUpdate(m: JsonObject) {
        val g = m.long("generation")
        val r = m.long("revision")
        if (g < owner.generation || g == owner.generation && r <= revision) return
        if (g != owner.generation || r != revision + 1 || desynced) { requestSync(); return }
        val delta = m.decode<AccountDelta>("delta")
        val routed = m.optional<AccountCommand>("command")
        val old = confirmed
        confirmed = confirmed.changed(delta).valid()
        revision = r
        delta.queue?.let { projection.rememberCollection(it.rows, it.collectionFirst) }
        if (delta.cursor != null) actual = null
        routed?.let { pending[it.id]?.reflected = true }
        retireSettled()
        if (isOwner()) execute(old.cursor, delta, routed)
        publish()
    }

    private fun conditionalTransport(m: JsonObject) {
        if (!isOwner() || desynced || m.long("generation") != owner.generation) return
        val orderedAt = m.long("revision")
        if (orderedAt > revision) { requestSync(); return }
        val c = m.decode<AccountCommand>("command")
        val action = c.action.kind
        if (action !in CONDITIONAL_ACTIONS || c.id in handledExecutions) return
        handledExecutions += c.id
        if (handledExecutions.size > MAX_PENDING) handledExecutions.remove(handledExecutions.first())
        // Native facts can indicate EOF before the observation collector has resumed.
        engine.consumeNativeEnd()
        if (!isOwner() || desynced) return
        val old = nativeCursor
        val q = engine.state.value
        val row = if (action == "next") LocalPlaybackQueue.successor(q, naturalEnd = false)?.let(q.queue::get) else null
        if (old.key == 0L || c.basis.occurrence != old.occurrence || c.basis.key?.let { it != old.key } == true || nativeAhead() ||
            exhaustedVisit?.let { (visit, at) -> visit == old.occurrence && orderedAt < at } == true ||
            action == "next" && c.basis.successorKey != (row?.key ?: 0L)) {
            queueSettlement(c.id, "stale")
            report(nativeFact ?: newObservation(old, AudioFacts(elapsed = q.elapsed, isLoading = old.desiredPlaying)),
                reflectedRevision = revision, settlementOnly = true)
            return
        }
        val elapsed = engine.sampleCurrent()?.elapsed ?: q.elapsed
        val to = when {
            action == "seek" -> {
                val duration = q.current?.duration?.takeIf { it > 0 }?.let(::secondsMs)
                val requested = c.action.long("positionMs")
                old.copy(positionMs = duration?.let { minOf(requested, it) } ?: requested)
            }
            row == null -> old.copy(positionMs = secondsMs(elapsed), desiredPlaying = false)
            else -> AccountCursor(row.key, UUID.randomUUID().toString(), 0, true)
        }
        transportCommit = ConditionalExecution(c.id, to)
        nativeCursor = to
        nativeRevision = revision
        nativeFact = null
        executing?.let { queueSettlement(it, "stale") }
        executing = null
        queueSettlement(c.id, "applied", to)
        if (action == "next" && row == null) {
            engine.pauseAt(q.copy(desiredPlaying = false, elapsed = elapsed))
            nativeFact = newObservation(to, AudioFacts(elapsed = elapsed))
        } else {
            val next = confirmed.copy(cursor = to).presentation()
            engine.adopt(next, execute = true)
        }
        val sample = nativeFact ?: newObservation(to, AudioFacts(elapsed = to.positionMs.seconds(), isLoading = true))
        report(sample, settlementOnly = true)
        publish()
    }

    private fun execute(old: AccountCursor, delta: AccountDelta, command: AccountCommand?) {
        val next = confirmed.cursor
        val action = command?.action?.kind
        val source = command?.basis?.occurrence
        if (action in CONDITIONAL_ACTIONS && command != null) {
            // Only execute frames can move the owner for Next or Seek. Their state echo
            // publishes the committed transition without re-entering the native executor.
            if (transportCommit?.let { it.id == command.id && it.cursor == next } == true) {
                transportCommit = null
                nativeRevision = revision
                if (advances.isNotEmpty()) sendAdvance()
                else {
                    engine.adopt(confirmed.presentation(nativeFact), execute = false)
                    nativeFact?.let { report(it) }
                }
            }
            return
        }
        val predicted = advancing
        val matchingAdvance = predicted != null && (next.key == predicted.toKey && next.occurrence == predicted.toOccurrence ||
            predicted.toKey == 0L && next.sameVisit(nativeCursor) && !next.desiredPlaying && next.positionMs == 0L)
        val stale = command != null && action in setOf("previous", "pause", "resume") && source != nativeCursor.occurrence
        if (matchingAdvance) {
            advances.removeFirst()
            if (predicted?.toKey == 0L) exhaustedVisit = predicted.fromOccurrence to revision
            nativeRevision = revision
            if (command != null && stale) queueSettlement(command.id, "stale")
            if (advances.isEmpty()) {
                nativeCursor = next
                engine.adopt(confirmed.presentation(nativeFact), execute = false)
                nativeFact?.let { report(it) }
            } else sendAdvance()
            return
        }
        if (command != null && stale) {
            queueSettlement(command.id, "stale")
            // The owner assessed this revision but did not apply its transport change. The
            // matching old occurrence cannot be asserted here, so send the actual native visit;
            // Go fences that sample separately and can still accept this settlement.
            val fact = nativeFact ?: newObservation(nativeCursor, AudioFacts(elapsed = engine.state.value.elapsed,
                isLoading = nativeCursor.desiredPlaying))
            report(fact, reflectedRevision = revision, settlementOnly = true)
            return
        }
        if (delta.cursor == null || old == next && action !in setOf("play", "seek", "resume", "pause", "next", "previous", "jump", "close")) {
            nativeRevision = revision
            if (!nativeAhead()) {
                val p = confirmed.presentation(nativeFact)
                engine.adopt(if (nativeFact.isFailure()) p.copy(desiredPlaying = false, error = engine.state.value.error) else p,
                    execute = false)
            }
            return
        }
        // A new transport run supersedes suspended execution of an earlier command.
        executing?.let { queueSettlement(it, "stale") }
        executing = command?.id
        advances.clear()
        transportCommit = null
        nativeFact = null
        nativeRevision = revision
        nativeCursor = next
        val operation = ++execution
        publish()
        if (operation != execution || !isOwner()) return
        val q = confirmed.presentation()
        if (next.key == 0L) {
            engine.adopt(q, execute = true)
            observedEmpty()
        } else if (!next.desiredPlaying && next.sameVisit(old) && action != "seek") {
            val tail = engine.sampleBeforePause()
            val elapsed = tail?.elapsed ?: q.elapsed
            engine.pauseAt(q.copy(elapsed = elapsed))
            observed(AudioFacts(elapsed = elapsed))
        } else engine.adopt(q, execute = true)
    }

    private fun observation(m: JsonObject) {
        if (m.long("generation") != owner.generation || m.long("revision") != revision || isOwner()) return
        val next = m.decode<AccountObservation>("observation")
        if (!next.matches(confirmed.cursor) || !next.valid()) return
        if (actual?.let { it.sampledAtMs > next.sampledAtMs && it.matches(confirmed.cursor) } == true) return
        actual = next
        publish()
    }

    private fun result(m: JsonObject) {
        val id = m.string("id")
        val status = m.string("status")
        val p = pending[id] ?: return
        if (status == "accepted") { p.accepted = true; return }
        if (status !in setOf("applied", "satisfied", "stale", "failed", "unavailable")) return
        p.terminalAt = m.long("revision")
        if (status == "unavailable" || status == "failed") error = status
        retireSettled()
        publish()
    }

    private fun retireSettled() {
        val done = pending.values.filter { (it.terminalAt ?: Long.MAX_VALUE) <= revision }.map { it.command.id }
        done.forEach { pending.remove(it); timeouts.remove(it) }
    }
    private fun clearPending() { pending.clear(); timeouts.clear(); desynced = false }
    private fun clearExecution() {
        executing = null
        transportCommit = null
        settlements.clear()
        ++execution
    }
    private fun requestSync() {
        if (!desynced) {
            desynced = true
            actual = null
            send(fields("type" to "sync"))
            publish()
        }
    }

    private fun newObservation(c: AccountCursor, f: AudioFacts, failed: Boolean = false) = AccountObservation(c.key,
        c.occurrence, secondsMs(f.elapsed), clock.wallTimeMs().coerceAtLeast(0), when {
            c.key == 0L -> "stopped"
            failed -> "failed"
            f.isPlaying -> "playing"
            f.isBuffering -> "buffering"
            f.isLoading -> "loading"
            c.desiredPlaying -> "stopped"
            else -> "paused"
        })

    private fun observed(f: AudioFacts) {
        if (localAuthority && !f.ended) sampleHistory(f)
        if (!isOwner() || !localAuthority || f.ended || !safeSeconds(f.elapsed)) return
        val fact = newObservation(nativeCursor, f)
        nativeFact = fact
        if (!nativeAhead()) {
            executing?.let { queueSettlement(it, "applied") }
            executing = null
            report(fact)
        }
        publish()
    }

    private fun observedEmpty() = observed(AudioFacts())
    private fun failed(error: String, f: AudioFacts?) {
        history.discontinuity()
        if (!isOwner() || !localAuthority) return
        val fact = newObservation(nativeCursor, AudioFacts(elapsed = f?.elapsed ?: nativeCursor.positionMs.seconds(), error = error), failed = true)
        nativeFact = fact
        executing?.let { queueSettlement(it, "failed") }
        executing = null
        if (!nativeAhead()) report(fact)
        publish()
    }

    private fun ended(q: PlaybackQueueState, f: AudioFacts) {
        if (!localAuthority) return
        sampleHistory(f)
        if (!online || retainedLocal) {
            engine.advanceAvailable(q)
            publish()
            return
        }
        val i = LocalPlaybackQueue.successor(q, naturalEnd = true)
        val old = if (online) nativeCursor else localQueue().cursor
        val row = i?.let(q.queue::get)
        val visit = row?.let { UUID.randomUUID().toString() }
        val next = if (row == null) old.copy(positionMs = 0, desiredPlaying = false) else AccountCursor(row.key, visit, 0, true)
        val advance = if (isOwner() && old.occurrence != null) {
            if (advances.size >= MAX_UNCONFIRMED_ADVANCES) {
                // A broken peer never accumulates an unbounded chain of unreconciled audio.
                socket?.close()
                null
            } else AdvanceReport(AccountAdvance(old.occurrence, old.key, row?.key ?: 0L, visit, secondsMs(f.elapsed))).also {
                executing?.let { id -> queueSettlement(id, "applied") }
                executing = null
                advances.addLast(it)
                nativeCursor = next
                nativeFact = null
            }
        } else null
        if (row == null) engine.finishNaturalEnd(q)
        else {
            val chosen = LocalPlaybackQueue.select(q, i)
            engine.adopt(chosen.copy(occurrence = PlaybackOccurrence(row.key, row.id, requireNotNull(visit), 0.0, false)),
                execute = true, natural = true)
        }
        if (advance != null) {
            // EOF itself proves a stopped tail. Otherwise native promotion can supply an
            // actual sample; never infer that the successor is already playing.
            advance.observation = nativeFact ?: AccountObservation(next.key, next.occurrence, 0,
                clock.wallTimeMs().coerceAtLeast(0), if (row == null) "stopped" else "loading")
            nativeFact = advance.observation
            if (advances.firstOrNull() === advance && transportCommit == null) sendAdvance()
        }
        publish()
    }

    private fun queueSettlement(id: String, status: String, cursor: AccountCursor? = null) {
        if (settlements.none { it.id == id }) settlements.add(AccountSettlement(id, status, cursor))
    }
    private fun sendAdvance() {
        val head = advances.firstOrNull() ?: return
        if (head.sentAt != null) return
        head.observation?.let {
            head.sentAt = clock.monotonicMs()
            report(it, head.advance)
        }
    }
    private fun report(fact: AccountObservation, advance: AccountAdvance? = null, reflectedRevision: Long = nativeRevision,
        settlementOnly: Boolean = false) {
        if (!isOwner() || desynced || nativeAhead() && advance == null && !settlementOnly) return
        val frame = buildJsonObject {
            put("type", "report"); put("generation", owner.generation); put("revision", reflectedRevision)
            put("sequence", ++sequence)
            sampleClock.offset()?.let { put("clockOffsetMs", it) }
            put("observation", playbackJson.encodeToJsonElement(fact))
            advance?.let { put("advance", playbackJson.encodeToJsonElement(it)) }
            if (settlements.isNotEmpty()) put("settlements", playbackJson.encodeToJsonElement(settlements.toList()))
        }
        if (send(frame)) settlements.clear()
    }

    private fun ping() {
        val stamp = sampleClock.ping()
        send(fields("type" to "ping", "clientTimeMs" to stamp))
    }

    private fun send(frame: JsonObject): Boolean {
        val live = socket?.takeIf { current() && it.isOpen } ?: return false
        return try { live.send(frame.toString()).also { if (!it) live.close() } }
        catch (_: Exception) { live.close(); false }
    }

    private fun newCommandId(): String? = if (commandCounter < MAX_SAFE_INTEGER) "$connectionId:${++commandCounter}" else null

    private fun issue(action: JsonObject, context: PlaybackContext? = null, offline: () -> Boolean = { false }): Boolean {
        if (!current() || superseded || connection == PlaybackConnectionState.CLOSED || !addressableAccountChoice(action)) return false
        if (!online || retainedLocal && action.kind !in setOf("play", "select")) {
            return offline()
        }
        if (context != null && context.kind in setOf("playlist", "derived") && !canPlaySharedPlaylist(context.id)) return false
        if (desynced || pending.size >= MAX_PENDING) return false
        val c = (nativeCursor.takeIf { isOwner() && nativeAhead() } ?: confirmed.cursor)
        val display = projection.project(displayBasis(), pending.values)
        // The displayed speculative visit has no shared occurrence ID. Never seek or navigate
        // the outgoing visit on its behalf, even if the preceding choice eventually fails.
        if (action.kind in OCCURRENCE_ACTIONS && !display.cursor.sameVisit(c)) return false
        val basisState = display.takeIf { it.cursor.sameVisit(c) } ?: confirmed.copy(cursor = c)
        val successor = if (action.kind == "next") LocalPlaybackQueue.successor(basisState.presentation(), false)?.let { basisState.queue[it].key } ?: 0L else null
        val id = newCommandId() ?: return false
        val cmd = AccountCommand(id, clock.wallTimeMs().coerceAtLeast(0),
            AccountBasis(sessionId, owner.generation, revision, c.occurrence, c.key.takeIf { it != 0L }, successor), action)
        val frame = JsonObject(playbackJson.encodeToJsonElement(cmd).jsonObject + fields("type" to "command"))
        if (!send(frame)) return false
        if (retainedLocal) {
            retainedLocal = false
            engine.resetOutput()
            localAuthority = isOwner()
            nativeCursor = confirmed.cursor
            nativeRevision = revision
        }
        pending[cmd.id] = projection.prepare(cmd, projected())
        timeouts[cmd.id] = clock.monotonicMs() + SETTLEMENT_MS
        error = null
        publish()
        return true
    }

    private fun action(type: String, local: () -> Unit) = issue(fields("type" to type)) { local(); true }
    fun pause() = action("pause", engine::pause)
    fun resume() = action("resume", engine::resume)
    fun toggle() = if (state.value.wantsPause) pause() else resume()
    fun next() = action("next", engine::next)
    fun previous() = action("previous", engine::previous)
    fun stop() = action("close", engine::stop)
    fun seek(seconds: Double) = safeSeconds(seconds) && issue(fields("type" to "seek", "positionMs" to secondsMs(seconds))) { engine.seek(seconds); true }
    fun jump(key: Long) = issue(fields("type" to "jump", "key" to key)) { engine.jump(key); true }
    fun remove(key: Long) = issue(fields("type" to "remove", "key" to key)) { engine.remove(key); true }
    fun moveUpcoming(key: Long, after: Long?) = issue(fields("type" to "move", "key" to key, "after" to (after ?: 0))) { engine.moveUpcoming(key, after); true }
    fun clearUpcoming() = action("clear", engine::clearUpcoming)
    fun setShuffle(enabled: Boolean) = issue(buildJsonObject {
        put("type", "shuffle"); put("shuffle", enabled)
        if (enabled) put("seed", kotlin.random.Random.nextLong(1L shl 32))
    }) { engine.setShuffle(enabled); true }
    fun toggleShuffle() = setShuffle(state.value.snapshot?.state?.shuffle != true)
    fun setRepeat(mode: String) = mode in setOf("off", "all", "one") && issue(fields("type" to "repeat", "repeat" to mode)) { engine.setRepeat(mode); true }
    fun cycleRepeat() = setRepeat(LocalPlaybackQueue.nextRepeat(state.value.snapshot?.state?.repeat))
    fun selectDevice(deviceId: String) = issue(fields("type" to "select", "deviceId" to deviceId))
    fun renameDevice(name: String, deviceId: String) = online && send(fields("type" to "rename", "name" to name, "deviceId" to deviceId))
    private fun selection(track: PlaybackTrackReference?, ctx: PlaybackContext?) = buildJsonObject {
        put("kind", if (track == null) "collection" else "track")
        track?.let { put("track", playbackJson.encodeToJsonElement(it)) }
        ctx?.let { put("context", playbackJson.encodeToJsonElement(it.copy(revision = null))) }
    }
    fun playTrack(track: PlaybackTrackReference, context: PlaybackContext? = null) =
        issue(fields("type" to "play", "selection" to selection(track, context)), context) { engine.playTrack(track, context) }
    fun playCollection(context: PlaybackContext, shuffle: Boolean = false) = issue(buildJsonObject {
        put("type", "play"); put("selection", selection(null, context)); put("shuffle", shuffle)
        if (shuffle) put("seed", kotlin.random.Random.nextLong(1L shl 32))
    }, context) { engine.playCollection(context, shuffle) }
    fun queueTrack(track: PlaybackTrackReference, context: PlaybackContext? = null, next: Boolean = false) =
        issue(fields("type" to "add", "selection" to selection(track, context), "placement" to if (next) "next" else "low"), context) { engine.queueTrack(track, context, next) }
    fun queueCollection(context: PlaybackContext, next: Boolean = false) =
        issue(fields("type" to "add", "selection" to selection(null, context), "placement" to if (next) "next" else "low"), context) { engine.queueCollection(context, next) }

    override fun close() {
        if (connection == PlaybackConnectionState.CLOSED) return
        connection = PlaybackConnectionState.CLOSED
        ++life
        localAuthority = false
        online = false
        worker?.cancel()
        socket?.close()
        socket = null
        engine.retire()
        nativeView.cancel()
        ticker.cancel()
        listeners.toList().forEach { it(null, null) }
        listeners.clear()
        clearPending()
        publish()
    }

    private companion object {
        const val ATTEMPT_MS = 10_000L
        const val MAX_PENDING = 64
        const val MAX_UNCONFIRMED_ADVANCES = 8
        const val ADVANCE_CONFIRM_MS = 5_000L
        const val SETTLEMENT_MS = 15_000L
        val CONDITIONAL_ACTIONS = setOf("next", "seek")
        val OCCURRENCE_ACTIONS = setOf("pause", "resume", "seek", "next", "previous")
        val TRANSPORT_ACTIONS = OCCURRENCE_ACTIONS + setOf("play", "jump", "close")
    }
}

private fun AccountObservation?.isFailure() = this?.status == "failed"
private fun AccountObservation.valid() = positionMs in 0..MAX_SAFE_INTEGER && sampledAtMs in 0..MAX_SAFE_INTEGER &&
    status in setOf("loading", "buffering", "playing", "paused", "stopped", "failed") &&
    (key != 0L || positionMs == 0L && status == "stopped")
private inline fun <reified T> JsonObject.decode(name: String): T = playbackJson.decodeFromJsonElement(getValue(name))
private inline fun <reified T> JsonObject.optional(name: String): T? = get(name)?.let { playbackJson.decodeFromJsonElement(it) }
