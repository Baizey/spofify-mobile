package dev.spofify.mobile.core.playback

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
    private val shareDetachedPlayback: Boolean = false,
) : AutoCloseable {
    private val view = MutableStateFlow(PlaybackView(localOutput = true, offline = true))
    val state = view.asStateFlow()
    private var socket: PlaybackConnection? = null
    private var worker: Job? = null
    private var life = 0L
    private var connection = PlaybackConnectionState.DISCONNECTED
    private var manual = false
    private var superseded = false
    private var online = false
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
    private data class ClaimReconciliation(val id: String, val cursor: AccountCursor, val sentAt: Long)
    private var claimReconciliation: ClaimReconciliation? = null
    private var claimRepairFollowsCommit = false
    private var localAction = 0L
    private val handledExecutions = linkedSetOf<String>()
    private fun nativeAhead() = advancing != null || transportCommit != null || claimReconciliation != null
    private var detached: Detached? = null
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
    private data class HelloLocal(val state: AccountState, val binding: AudioBinding?, val action: Long)
    private data class Detached(val session: String, val generation: Long, val revision: Long, val occurrence: String?) {
        fun json() = buildJsonObject {
            put("sessionId", session); put("generation", generation); put("revision", revision)
            occurrence?.let { put("occurrence", it) }
        }
    }
    private val listeners = mutableSetOf<(JsonObject?) -> Unit>()
    private val history = PlaybackHistory(clock, recordPlay)
    private fun sampleHistory(facts: AudioFacts) {
        val q = engine.state.value
        history.sample(q.current, q.occurrence?.eventId, sessionId.takeIf { isOwner() }, facts, !online)
    }
    private val engine = LocalPlaybackController(audio, object : LocalPlaybackLibrary by library {
        override suspend fun hasAudio(track: PlaybackTrack, isAuthorized: () -> Boolean) =
            if (online) isAuthorized() else library.hasAudio(track, isAuthorized)
    }, scope, { localAuthority && current() }, onNaturalEnd = ::ended, onObserved = ::observed,
        onFailure = ::failed, outputVersion = { owner.generation })
    private val nativeView = scope.launch { engine.state.collect { if (!online) publish() } }
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
                if (advances.firstOrNull()?.sentAt?.let { now - it >= ADVANCE_CONFIRM_MS } == true ||
                    claimReconciliation?.sentAt?.let { now - it >= SETTLEMENT_MS } == true) socket?.close()
                publish()
            }
        }
    }

    fun onPersonal(fn: (JsonObject?) -> Unit): AutoCloseable {
        listeners += fn
        return AutoCloseable { listeners -= fn }
    }
    fun sendPersonal(command: JsonObject) = online && send(fields("type" to "personal", "command" to command))
    fun matchesLocalAudio(binding: AudioBinding): Boolean = localAuthority && engine.operationIsCurrent(binding)
    private fun isOwner() = online && owner.deviceId == deviceId
    private fun localQueue(): AccountState = AccountState.from(engine.state.value)
    private fun projected() = projection.project(confirmed, pending.values)
    private fun displayBasis(): AccountState = if (isOwner() && nativeAhead() && confirmed.queue.any { it.key == nativeCursor.key })
        confirmed.copy(cursor = nativeCursor) else confirmed

    private fun publish() {
        val v = view.value
        val q: PlaybackQueueState
        var fact: AccountObservation? = null
        if (!online && !superseded) {
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
            if (online || superseded) fact != null else q.current != null, q.elapsed, q.duration,
            q.isPlaying, q.isBuffering, q.isLoading, q.error)
        val snap = PlaybackSnapshot(sessionId, revision, outputVersion, q, p,
            if (online) devices.map { PlaybackDevice(it.deviceId, it.name, true, it.canPlay) } else emptyList(),
            if (online) owner.deviceId.orEmpty() else "", if (isOwner()) connectionId else "", null, 0)
        val next = PlaybackView(connection, sessionId, connectionId, deviceId, snap, p, error,
            localAuthority, !online && !superseded, superseded, established,
            transferPending = online && pending.values.any { it.command.action.kind == "select" })
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

    fun connect(name: String, deviceId: String): Job {
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
                val localAtHello = localQueue().takeIf { shareDetachedPlayback && it.queue.isNotEmpty() }?.let { HelloLocal(it, engine.operationBinding(), localAction) }
                val claim = localAtHello?.let { playbackJson.encodeToJsonElement(it.state).jsonObject }
                val live = withTimeout(ATTEMPT_MS) { transport.connect(name, deviceId, claim, detached?.json().takeIf { shareDetachedPlayback }) }
                conn = live
                if (attempt != life || !current()) return@launch
                socket = live
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
                            snapshot(frame, localAtHello.takeIf { initial }, initial)
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
                        "personal" -> listeners.toList().forEach { it(frame.getValue("update").jsonObject) }
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
        val fallback = if (!replaced && online && !isOwner()) {
            // One queue per account: a controller keeps the latest confirmed shared
            // traversal, never its old isolated queue or an optimistic prediction.
            val measured = actual?.takeIf { it.matches(confirmed.cursor) }
            confirmed.presentation(elapsedMs = measured?.positionMs ?: confirmed.cursor.positionMs)
                .copy(desiredPlaying = false)
        } else null
        if (isOwner() && !replaced && !nativeAhead()) detached = Detached(sessionId, owner.generation, revision, confirmed.cursor.occurrence)
        // Do not smuggle an unconfirmed/rejected online transition back as detached progress.
        if (replaced || nativeAhead()) detached = null
        if (replaced || online && !isOwner()) {
            localAuthority = false
            engine.resetOutput()
            nativeCursor = AccountCursor.Empty
            nativeFact = null
        }
        online = false
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
        listeners.toList().forEach { it(null) }
    }

    private fun snapshot(m: JsonObject, localAtHello: HelloLocal?, initial: Boolean) {
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
        val awaitingClaim = claimReconciliation
        val reconciledClaim = awaitingClaim?.cursor == next.cursor
        val generationChanged = nextSession != sessionId || nextOwner.generation != owner.generation
        // Go orders this repair after the conditional echo, before any held commands.
        // It reaffirms the just-committed visit, not a rejection of the successor EOF
        // that this client could only report after receiving that echo.
        val reaffirmedCommit = claimRepairFollowsCommit && transportCommit == null && !generationChanged &&
            nextRevision == revision && next.cursor == old.cursor
        claimRepairFollowsCommit = false
        val local = localQueue()
        // The matching snapshot, not the hello alone, proves what was accepted. An operation
        // started after hello (e.g. an isolated seek) isn't covered by that acknowledgement.
        val acceptedClaim = nextOwner.deviceId == deviceId && !previouslyOnline && localAtHello?.state?.cursor == next.cursor
        val localFailure = engine.state.value.error
        val pausedAfterLoss = !shareDetachedPlayback && !previouslyOnline && nextOwner.deviceId == deviceId &&
            local.cursor.occurrence != null && local.cursor.occurrence == next.cursor.occurrence && !local.cursor.desiredPlaying
        val retainedFailure = acceptedClaim && localFailure.isNotEmpty() && local.cursor.sameVisit(next.cursor) &&
            localAction == localAtHello.action
        val retained = retainedFailure || acceptedClaim && local.cursor.key != 0L && local.cursor.sameVisit(next.cursor) &&
            local.cursor.desiredPlaying == next.cursor.desiredPlaying &&
            (local.cursor == next.cursor || localAtHello.binding == engine.operationBinding() && localAction == localAtHello.action)
        // Intentionally compare row identity and order, not server-rebuilt display metadata.
        val reconciliation = local.cursor.takeIf { cursor -> initial && acceptedClaim &&
            localAction != localAtHello.action && cursor != next.cursor && cursor.key != 0L &&
            local.queue.map { it.key } == next.queue.map { it.key } && next.queue.any { it.key == cursor.key } }
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
        detached = null
        projection.rememberCollection(confirmed.queue, confirmed.collectionFirst)
        retireSettled()
        val existingVisit = nativeCursor
        val awaitingTransport = transportCommit
        val committedTransport = awaitingTransport?.cursor == next.cursor
        val buffered = if (reconciledClaim || reaffirmedCommit) advances.toList() else emptyList()
        advances.clear()
        advances.addAll(buffered)
        transportCommit = null
        claimReconciliation = null
        val own = isOwner()
        if (!own) {
            localAuthority = false
            // Revoke and pause isolated sound before projecting the remote snapshot.
            engine.resetOutput()
            nativeFact = null
            nativeCursor = AccountCursor.Empty
        } else {
            localAuthority = true
            val keep = retained || previouslyOwner && !generationChanged &&
                (when { awaitingClaim != null -> reconciledClaim; awaitingTransport != null -> committedTransport; else -> old.cursor == next.cursor }) &&
                (buffered.isNotEmpty() || existingVisit.sameVisit(next.cursor) && existingVisit.desiredPlaying == next.cursor.desiredPlaying)
            if (!keep) { nativeFact = null; clearExecution() }
            nativeRevision = revision
            if (reconciliation != null) {
                val id = newCommandId() ?: return
                claimReconciliation = ClaimReconciliation(id, reconciliation, clock.monotonicMs())
                nativeCursor = reconciliation
                // Send before publishing READY so a reentrant command cannot overtake reconciliation.
                val basis = Detached(sessionId, owner.generation, revision, next.cursor.occurrence).json()
                send(fields("type" to "reconcileClaim", "id" to id, "basis" to basis,
                    "cursor" to playbackJson.encodeToJsonElement(reconciliation)))
                // Local queue/settings edits don't import. From this point use shared
                // traversal even while we retain the already applied local transport.
                val p = next.copy(cursor = reconciliation).presentation()
                engine.adopt(p.copy(elapsed = local.cursor.positionMs.seconds(), isPlaying = engine.state.value.isPlaying,
                    error = engine.state.value.error), execute = false)
                syncLocalMeasurements()
                publish()
                return
            }
            nativeCursor = if (buffered.isEmpty()) next.cursor else existingVisit
            val operation = ++execution
            publish() // FGS admission must see this device as current before the native start.
            if (execution != operation || !isOwner()) return
            if (buffered.isNotEmpty()) { sendAdvance(); return }
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
        // Go's in-flight execute wins this conflict and its claim repair is held until
        // execution settles. Do not report or use the provisional claim's measurements.
        val outgoingClaim = claimReconciliation
        val displacedClaim = outgoingClaim != null
        if (displacedClaim) {
            claimReconciliation = null
            advances.clear()
            nativeFact = null
        } else {
            // Native facts can indicate EOF before the observation collector has resumed.
            engine.consumeNativeEnd()
            if (!isOwner() || desynced) return
        }
        val old = nativeCursor
        val q = if (displacedClaim) confirmed.presentation() else engine.state.value
        val row = if (action == "next") LocalPlaybackQueue.successor(q, naturalEnd = false)?.let(q.queue::get) else null
        if (old.key == 0L || c.basis.occurrence != old.occurrence || c.basis.key?.let { it != old.key } == true || nativeAhead() ||
            displacedClaim && !old.sameVisit(confirmed.cursor) ||
            exhaustedVisit?.let { (visit, at) -> visit == old.occurrence && orderedAt < at } == true ||
            action == "next" && c.basis.successorKey != (row?.key ?: 0L)) {
            queueSettlement(c.id, "stale")
            if (displacedClaim) claimReconciliation = outgoingClaim
            val reportedCursor = if (displacedClaim) confirmed.cursor else old
            report(nativeFact ?: newObservation(reportedCursor, AudioFacts(elapsed = q.elapsed, isLoading = reportedCursor.desiredPlaying)),
                reflectedRevision = revision, settlementOnly = true)
            return
        }
        val elapsed = if (displacedClaim) q.elapsed else engine.sampleCurrent()?.elapsed ?: q.elapsed
        val sourceCursor = if (displacedClaim) confirmed.cursor else old
        val to = when {
            action == "seek" -> {
                val duration = q.current?.duration?.takeIf { it > 0 }?.let(::secondsMs)
                val requested = c.action.long("positionMs")
                sourceCursor.copy(positionMs = duration?.let { minOf(requested, it) } ?: requested)
            }
            row == null -> sourceCursor.copy(positionMs = secondsMs(elapsed), desiredPlaying = false)
            else -> AccountCursor(row.key, UUID.randomUUID().toString(), 0, true)
        }
        transportCommit = ConditionalExecution(c.id, to)
        claimRepairFollowsCommit = displacedClaim
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
        claimReconciliation = null
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
        // The server sends the definitive canonical snapshot before the terminal result.
        if (claimReconciliation?.id == id) {
            if (status != "accepted") socket?.close() // a peer that violates that fence cannot keep provisional authority
            return
        }
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
        claimReconciliation = null
        claimRepairFollowsCommit = false
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
        if (!online) {
            ++localAction
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
            if (advances.firstOrNull() === advance && transportCommit == null && claimReconciliation == null) sendAdvance()
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

    private fun issue(action: JsonObject, offline: () -> Boolean = { false }): Boolean {
        if (!current() || superseded || connection == PlaybackConnectionState.CLOSED || !addressableAccountChoice(action)) return false
        if (!online) {
            val before = localQueue().cursor
            val applied = offline()
            if (applied && localQueue().cursor != before) ++localAction
            return applied
        }
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
        track?.let { put("track", playbackJson.encodeToJsonElement(it.copy(selectionToken = null))) }
        ctx?.let { put("context", playbackJson.encodeToJsonElement(it.copy(revision = null, fence = null))) }
    }
    fun playTrack(track: PlaybackTrackReference, context: PlaybackContext? = null) =
        issue(fields("type" to "play", "selection" to selection(track, context))) { engine.playTrack(track, context) }
    fun playCollection(context: PlaybackContext, shuffle: Boolean = false) = issue(buildJsonObject {
        put("type", "play"); put("selection", selection(null, context)); put("shuffle", shuffle)
        if (shuffle) put("seed", kotlin.random.Random.nextLong(1L shl 32))
    }) { engine.playCollection(context, shuffle) }
    fun queueTrack(track: PlaybackTrackReference, context: PlaybackContext? = null, next: Boolean = false) =
        issue(fields("type" to "add", "selection" to selection(track, context), "placement" to if (next) "next" else "low")) { engine.queueTrack(track, context, next) }
    fun queueCollection(context: PlaybackContext, next: Boolean = false) =
        issue(fields("type" to "add", "selection" to selection(null, context), "placement" to if (next) "next" else "low")) { engine.queueCollection(context, next) }

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
        listeners.toList().forEach { it(null) }
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
