package dev.spofify.mobile.core.playback

import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive

private const val RETIRED_PLAN_CAPACITY = 128

/**
 * One live v2 connection. All decisions are serialized under lock; native work runs
 * outside it and must pass a generation/lease predicate before it can start sound.
 * Reconnect is explicit and never resubmits an uncertain musical action.
 */
class PlaybackController(
    private val transport: PlaybackTransport,
    private val audio: AudioExecutor,
    private val scope: CoroutineScope,
    private val clock: PlaybackClock = SystemPlaybackClock,
) : Closeable {
    private val lock = Any()
    private val mutableState = MutableStateFlow(PlaybackView())
    val state: StateFlow<PlaybackView> = mutableState.asStateFlow()

    private var connection: PlaybackConnection? = null
    private var connectionJob: Job? = null
    private var samplerJob: Job? = null
    private var leaseJob: Job? = null
    private var mediaJob: Job? = null
    private var lifecycle = 0L
    private var operation = 0L
    private var closed = false
    private var requestedDeviceId = ""
    private var outputVersion = 0L
    private var commandId = 0L
    private var clientSequence = 0L
    private var leaseDeadline = 0L
    private var prepared: Prepared? = null
    private var execution: Execution? = null
    private var nextPlan: PlaybackInstruction? = null
    private var futurePlayback: PlaybackStatus? = null
    private val sendTimes = linkedMapOf<Long, Long>()
    private val seenInstructions = linkedSetOf<String>()
    private val pending = linkedMapOf<String, PendingCommand>()
    private var undo: UndoState? = null
    private var lastUndoRequest: String? = null

    private data class Prepared(val instruction: PlaybackInstruction, val basis: Long)
    private data class PendingCommand(val command: JsonObject, val sentAt: Long, val removeKey: Long?)
    private data class UndoState(val token: String, val key: Long, val deadline: Long)
    private class Execution(
        val instruction: PlaybackInstruction,
        val generation: Long,
        val source: PlaybackInstruction? = null,
    ) {
        val binding = instruction.binding()
        var confirmed = source == null
        var speculationDisabled = false
        var parked = false
        var revoked = false
        var endedReported = false
        var outcome: AudioResult? = null
        var lastFacts: AudioFacts? = null
        var lastReportAt: Long? = null
        val receipts = mutableListOf(instruction)
        val retiredPlans = linkedSetOf<String>()
    }

    /** Transport must expose every frame from the first snapshot onward. */
    fun connect(name: String, deviceId: String): Job = synchronized(lock) {
        check(!closed) { "Playback controller is closed" }
        require(isValidDeviceName(name))
        require(validIdentifier(deviceId))
        disconnectLocked(null)
        requestedDeviceId = deviceId
        mutableState.value = PlaybackView(connection = PlaybackConnectionState.CONNECTING, deviceId = deviceId)
        val life = lifecycle
        scope.launch {
            var admitted: PlaybackConnection? = null
            try {
                val opened = withTimeout(10_000) { transport.connect(name, deviceId) }
                admitted = opened
                val accepted = synchronized(lock) {
                    if (life != lifecycle || closed) false else {
                        connection = opened
                        startSampler(life)
                        true
                    }
                }
                if (!accepted) {
                    opened.close()
                    return@launch
                }
                // A missing snapshot is not authority and must not leave UI stuck forever.
                val snapshotTimeout = scope.launch {
                    delay(10_000)
                    synchronized(lock) {
                        if (life == lifecycle && mutableState.value.snapshot == null) disconnectLocked("Playback connection timed out.")
                    }
                }
                try {
                    opened.messages.collect { text ->
                        synchronized(lock) {
                            if (life == lifecycle) receive(text)
                        }
                    }
                } finally {
                    snapshotTimeout.cancel()
                }
            } catch (_: CancellationException) {
                // Explicit disconnect already invalidated every native generation.
            } catch (_: Exception) {
                synchronized(lock) {
                    if (life == lifecycle) disconnectLocked("Playback connection unavailable. Reconnect explicitly.")
                }
            } finally {
                synchronized(lock) {
                    if (life == lifecycle) disconnectLocked("Playback disconnected. Actions will not be replayed.")
                }
                admitted?.close()
            }
        }.also { connectionJob = it }
    }

    /** Call synchronously when authentication/account/origin becomes uncertain. */
    fun disconnect() = synchronized(lock) { disconnectLocked("Playback disconnected.") }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            observationJob.cancel()
            disconnectLocked(null)
            mutableState.value = mutableState.value.copy(connection = PlaybackConnectionState.CLOSED)
        }
    }

    fun playTrack(track: PlaybackTrackReference, context: PlaybackContext? = null): String? =
        issueQueue("now", trackSelection(track, context))

    fun playCollection(context: PlaybackContext, shuffle: Boolean = false): String? =
        issueQueue("now", collectionSelection(context), shuffle)

    // Individual Queue follows existing high-priority additions; collections append below that band.
    fun queueTrack(track: PlaybackTrackReference, context: PlaybackContext? = null, next: Boolean = false): String? =
        issueQueue(if (next) "next" else "high", trackSelection(track, context))

    fun queueCollection(context: PlaybackContext, next: Boolean = false): String? =
        issueQueue(if (next) "next" else "low", collectionSelection(context))

    fun pause(): String? = simple("pause")
    fun resume(): String? = simple("resume")
    fun toggle(): String? = synchronized(lock) { simple(if (state.value.desiredPlaying) "pause" else "resume") }
    fun next(): String? = simple("next")
    fun previous(): String? = simple("previous")
    fun retry(): String? = simple("retry")
    fun clearUpcoming(): String? = simple("clear")
    fun stop(): String? = simple("close")
    fun deselectDevice(): String? = simple("deselect")
    fun setShuffle(shuffle: Boolean): String? = issue(fields("type" to "setShuffle", "shuffle" to shuffle))
    fun toggleShuffle(): String? = synchronized(lock) { setShuffle(state.value.snapshot?.state?.shuffle != true) }
    fun setRepeat(repeat: String): String? {
        require(repeat in setOf("off", "all", "one"))
        return issue(fields("type" to "setRepeat", "repeat" to repeat))
    }
    fun cycleRepeat(): String? = synchronized(lock) {
        val nextRepeat = when (state.value.snapshot?.state?.repeat) {
            "off" -> "all"
            "all" -> "one"
            else -> "off"
        }
        setRepeat(nextRepeat)
    }
    fun seek(elapsed: Double): String? {
        require(safeSeconds(elapsed))
        return issue(fields("type" to "seek", "value" to elapsed), guarded = true)
    }
    fun selectDevice(deviceId: String): String? {
        require(validIdentifier(deviceId))
        return issue(fields("type" to "select", "deviceId" to deviceId))
    }
    fun renameDevice(name: String, deviceId: String? = null): String? {
        require(isValidDeviceName(name))
        require(deviceId == null || validIdentifier(deviceId))
        return issue(JsonObject(fields("type" to "rename", "name" to name.trim()) +
            if (deviceId != null) fields("deviceId" to deviceId) else emptyMap()))
    }
    fun jump(key: Long): String? {
        require(key in 1..MAX_SAFE_INTEGER)
        return issue(fields("type" to "jump", "key" to key), guarded = true)
    }
    fun remove(key: Long, offerUndo: Boolean = true): String? {
        require(key in 1..MAX_SAFE_INTEGER)
        return issue(fields("type" to "remove", "key" to key, "undo" to offerUndo), guarded = true,
            removeKey = key.takeIf { offerUndo })
    }
    fun moveUpcoming(key: Long, afterKey: Long?): String? {
        require(key in 1..MAX_SAFE_INTEGER && (afterKey == null || afterKey in 1..MAX_SAFE_INTEGER && afterKey != key))
        return issue(fields("type" to "move", "key" to key, "afterKey" to afterKey), guarded = true)
    }
    fun undoRemove(): String? = synchronized(lock) {
        val capability = undo ?: return@synchronized null
        if (clock.monotonicMs() >= capability.deadline) {
            undo = null
            publish()
            return@synchronized null
        }
        undo = null
        lastUndoRequest = null
        issue(fields("type" to "undoRemove", "token" to capability.token))
    }

    private fun trackSelection(track: PlaybackTrackReference, context: PlaybackContext?): JsonObject {
        require(validIdentifier(track.id, 512))
        return JsonObject(fields("kind" to "track", "track" to playbackJson.encodeToJsonElement(track)) +
            if (context != null) fields("context" to playbackJson.encodeToJsonElement(context)) else emptyMap())
    }
    private fun collectionSelection(context: PlaybackContext) =
        fields("kind" to "collection", "context" to playbackJson.encodeToJsonElement(context))

    private fun issueQueue(intent: String, selection: JsonObject, shuffle: Boolean? = null): String? =
        issue(JsonObject(fields("type" to "queueIntent", "intent" to intent, "selection" to selection) +
            if (shuffle != null) fields("shuffle" to shuffle) else emptyMap()))

    private fun simple(type: String): String? = issue(fields("type" to type))

    /** Returns a sent command ID, not a success receipt. Results are server-only. */
    private fun issue(command: JsonObject, guarded: Boolean = false, removeKey: Long? = null): String? = synchronized(lock) {
        val view = state.value
        if (view.connection != PlaybackConnectionState.READY || connection?.isOpen != true) return@synchronized null
        if (pending.size >= 32 || commandId >= MAX_SAFE_INTEGER) {
            mutableState.value = view.copy(error = "Too many pending playback actions.")
            return@synchronized null
        }
        val rename = command["type"]?.jsonPrimitive?.content == "rename"
        if (!rename) {
            discardStage()
            undo = null
            lastUndoRequest = null
        }
        val id = (++commandId).toString()
        if (removeKey != null) lastUndoRequest = id
        pending[id] = PendingCommand(command, clock.monotonicMs(), removeKey)
        val envelope = JsonObject(fields("type" to "command", "id" to id, "command" to command) +
            if (guarded) fields("expectedStateVersion" to view.snapshot!!.stateVersion) else emptyMap())
        if (!send(envelope)) return@synchronized null
        publish()
        id
    }

    private fun receive(text: String) {
        try {
            require(text.toByteArray(Charsets.UTF_8).size <= 5 * 1024 * 1024)
            val message = decodePlaybackMessage(text)
            require(validIdentifier(message.sessionId))
            val view = state.value
            if (view.snapshot == null) {
                require(message.type == "snapshot" && message.deviceId == requestedDeviceId)
                require(message.connectionId?.let { validIdentifier(it) } == true)
                mutableState.value = view.copy(sessionId = message.sessionId, connectionId = message.connectionId!!)
            } else {
                require(message.type != "snapshot" && message.sessionId == view.sessionId)
            }
            when (message.type) {
                "snapshot", "state" -> acceptSnapshot(requireNotNull(message.snapshot))
                "playback" -> acceptPlayback(requireNotNull(message.playback))
                "instruction" -> acceptInstruction(requireNotNull(message.instruction))
                "lease" -> acceptLease(requireNotNull(message.lease))
                "result" -> acceptResult(requireNotNull(message.result))
                "error" -> disconnectLocked("Playback session unavailable. Reconnect explicitly.")
                else -> error("Unknown playback frame")
            }
        } catch (_: Exception) {
            disconnectLocked("Invalid playback session data. Reconnect explicitly.")
        }
    }

    private fun acceptSnapshot(snapshot: PlaybackSnapshot) {
        snapshot.validate()
        require(snapshot.sessionId == state.value.sessionId)
        val previous = state.value.snapshot
        if (snapshot.outputVersion < outputVersion || previous != null &&
            (snapshot.stateVersion < previous.stateVersion || snapshot.outputVersion < previous.outputVersion)) return
        if (previous != null && snapshot.stateVersion == previous.stateVersion) {
            acceptPlayback(snapshot.playback)
            return
        }
        val targeted = snapshot.selectedConnectionId == state.value.connectionId ||
            snapshot.transfer?.targetConnectionId == state.value.connectionId
        if (snapshot.outputVersion > outputVersion || execution != null && !targeted) {
            invalidateExecution()
        }
        outputVersion = maxOf(outputVersion, snapshot.outputVersion)
        val eventId = snapshot.state.occurrence?.eventId.orEmpty()
        val candidates = listOfNotNull(snapshot.playback, state.value.playback, futurePlayback).filter {
            it.outputVersion == snapshot.outputVersion && it.stateVersion == snapshot.stateVersion && it.eventId == eventId
        }
        val playback = candidates.maxByOrNull { it.playbackSequence }
        futurePlayback = futurePlayback?.takeIf { it.outputVersion > snapshot.outputVersion || it.stateVersion > snapshot.stateVersion }
        mutableState.value = state.value.copy(connection = PlaybackConnectionState.READY, snapshot = snapshot,
            playback = playback, error = null)
        publish()
    }

    private fun acceptPlayback(playback: PlaybackStatus) {
        playback.validate()
        val snapshot = state.value.snapshot ?: return
        if (playback.outputVersion < snapshot.outputVersion || playback.stateVersion < snapshot.stateVersion) return
        if (playback.outputVersion > snapshot.outputVersion || playback.stateVersion > snapshot.stateVersion) {
            if (futurePlayback == null || playback.playbackSequence > futurePlayback!!.playbackSequence) futurePlayback = playback
            return
        }
        if (playback.eventId != snapshot.state.occurrence?.eventId.orEmpty()) return
        if (state.value.playback?.let { playback.playbackSequence <= it.playbackSequence } == true) return
        mutableState.value = state.value.copy(playback = playback)
    }

    private fun acceptResult(result: PlaybackCommandResult) {
        require(result.id.matches(Regex("[1-9][0-9]*")) && result.id.toLongOrNull()?.let { it <= MAX_SAFE_INTEGER } == true)
        require(result.outcome in setOf("resolving", "accepted", "rejected"))
        require(safeCounter(result.stateVersion) && result.acceptedCount >= 0)
        require(result.placement in setOf("none", "low", "high", "next", "replace", "existing"))
        require(result.execution in setOf("unchanged", "pending", "confirmed", "blocked", "media", "uncertain", "superseded"))
        val request = pending[result.id] ?: return
        if (result.outcome == "accepted" && request.removeKey != null && lastUndoRequest == result.id && undo == null) {
            result.undo?.let { capability ->
                val remaining = minOf(4_000 - (clock.monotonicMs() - request.sentAt), capability.expiresAtMs - clock.wallTimeMs())
                if (remaining > 0) undo = UndoState(capability.token, request.removeKey, clock.monotonicMs() + remaining)
            }
        }
        if (result.final) pending.remove(result.id)
        // Do not expose the secret undo token to UI state or logs.
        mutableState.value = state.value.copy(latestResult = result.copy(undo = null), error = when {
            result.outcome == "rejected" -> "Playback action rejected (${result.code})."
            result.execution == "uncertain" -> "Playback action unconfirmed. It will not be replayed."
            result.execution == "blocked" -> "Playback needs permission on the selected device."
            result.execution == "media" -> "The selected device could not play this track."
            else -> null
        })
        publish()
    }

    private fun acceptInstruction(instruction: PlaybackInstruction) {
        instruction.validate()
        if (instruction.id in seenInstructions) return
        seenInstructions.add(instruction.id)
        while (seenInstructions.size > 128) seenInstructions.remove(seenInstructions.first())
        if (instruction.type == "stageNext" || instruction.type == "cancelNext") {
            acceptPlan(instruction)
            return
        }
        if (instruction.outputVersion < outputVersion) {
            ack(instruction, AudioResult.REJECTED)
            return
        }
        if (instruction.type == "release") {
            audio.pause()
            execution?.let { report(it, audio.facts(), allowFinal = true) }
            val ready = prepared
            invalidateExecution()
            outputVersion = instruction.outputVersion
            val basis = ack(instruction, AudioResult.OK)
            if (ready != null && ready.instruction.outputVersion == instruction.outputVersion &&
                ready.instruction.eventId == instruction.eventId && basis != null) prepared = ready.copy(basis = basis)
            return
        }
        val current = execution
        if (instruction.type == "execute" && instruction.planId != null && current?.source != null &&
            instruction.planId == current.instruction.planId && instruction.binding() == current.binding &&
            instruction.track?.key == current.instruction.track?.key) {
            if (!authorized(current) || !targetOutput() || !grant(instruction.lease!!)) {
                invalidateExecution()
                ack(instruction, AudioResult.REJECTED)
                return
            }
            current.confirmed = true
            val outcome = current.outcome
            if (outcome == null) current.receipts.add(instruction) else {
                parkIfStopped(current, outcome)
                ack(instruction, outcome)
                report(current, audio.facts())
            }
            return
        }
        discardStage()
        val ready = prepared
        if (instruction.outputVersion > outputVersion) {
            invalidateExecution()
            outputVersion = instruction.outputVersion
        }
        if (instruction.type == "prepare") {
            invalidateExecution()
            prepare(instruction)
            return
        }
        val validPrepare = ready != null && ready.instruction.outputVersion <= instruction.outputVersion &&
            ready.instruction.eventId == instruction.eventId && ready.basis == instruction.lease!!.clientSequence &&
            ready.instruction.track?.id == instruction.track?.id && ready.instruction.track?.key == instruction.track?.key
        val validExecution = execution?.binding?.outputVersion == instruction.outputVersion && execution?.parked != true
        if (!targetOutput() || (instruction.type == "activate" && !validPrepare) ||
            (instruction.type == "execute" && !validExecution) || !grant(instruction.lease!!)) {
            invalidateExecution()
            ack(instruction, AudioResult.REJECTED)
            return
        }
        beginExecution(instruction)
    }

    private fun prepare(instruction: PlaybackInstruction) {
        val generation = operation
        val life = lifecycle
        mediaJob = scope.launch {
            if (!synchronized(lock) { life == lifecycle && generation == operation }) return@launch
            val outcome = try {
                audio.prepare(instruction.binding(), instruction.track, instruction.elapsed)
            } catch (_: Exception) { AudioResult.MEDIA }
            synchronized(lock) {
                if (life != lifecycle || generation != operation) return@synchronized
                if (outcome != AudioResult.OK) audio.pause()
                val basis = ack(instruction, outcome)
                if (outcome == AudioResult.OK && basis != null) prepared = Prepared(instruction, basis)
            }
        }
    }

    private fun beginExecution(instruction: PlaybackInstruction, source: PlaybackInstruction? = null) {
        operation++
        mediaJob?.cancel()
        prepared = null
        nextPlan = null
        execution?.revoked = true
        val current = Execution(instruction, operation, source)
        execution = current // Admission, not completion, is the deduplication point.
        val life = lifecycle
        mediaJob = scope.launch {
            if (!synchronized(lock) { life == lifecycle && authorized(current) }) return@launch
            val outcome = try {
                audio.execute(current.binding, instruction.track, instruction.elapsed, instruction.desiredPlaying) {
                    synchronized(lock) { authorized(current) }
                }
            } catch (_: Exception) { AudioResult.MEDIA }
            synchronized(lock) {
                if (life != lifecycle || execution !== current || current.generation != operation || current.revoked) return@synchronized
                current.outcome = outcome
                if (outcome != AudioResult.OK) audio.pause()
                if (current.confirmed) parkIfStopped(current, outcome)
                // An unconfirmed next failure keeps its finite inherited deadline.
                current.receipts.forEach { ack(it, outcome) }
                current.receipts.clear()
                report(current, audio.facts())
            }
        }
    }

    private fun parkIfStopped(current: Execution, outcome: AudioResult) {
        if (outcome == AudioResult.REJECTED || current.instruction.desiredPlaying && outcome == AudioResult.OK) return
        // Stop pending native starts BEFORE the stopped-proof ACK.
        audio.pause()
        current.parked = true
        current.lastFacts = null
        discardStage()
        leaseDeadline = 0
        leaseJob?.cancel()
        leaseJob = null
    }

    private fun acceptPlan(instruction: PlaybackInstruction) {
        val current = execution ?: return
        if (instruction.type == "cancelNext") {
            if (sameSource(instruction, current)) {
                retirePlan(current, instruction.planId!!)
                if (nextPlan?.planId == instruction.planId) nextPlan = null
            }
            val source = current.source
            if (source != null && !current.confirmed && instruction.planId == current.instruction.planId &&
                instruction.outputVersion == source.outputVersion && instruction.eventId == source.eventId &&
                instruction.currentInstructionId == source.currentInstructionId) {
                current.revoked = true
                operation++
                audio.pause()
            }
            return
        }
        if (current.speculationDisabled || !sameSource(instruction, current) || !authorized(current) || !current.instruction.desiredPlaying ||
            current.endedReported || !targetOutput() || instruction.planId in current.retiredPlans ||
            pending.values.any { it.command["type"]?.jsonPrimitive?.content != "rename" }) return
        if (nextPlan?.planId == instruction.planId) return
        discardStage()
        // Retiring the replaced plan may have exhausted this execution's tombstones.
        if (!current.speculationDisabled) nextPlan = instruction
    }

    private fun sameSource(plan: PlaybackInstruction, current: Execution) =
        plan.outputVersion == current.binding.outputVersion && plan.eventId == current.binding.eventId &&
            plan.currentInstructionId == current.binding.instructionId

    private fun retirePlan(current: Execution, id: String) {
        if (current.speculationDisabled || id in current.retiredPlans) return
        if (current.retiredPlans.size < RETIRED_PLAN_CAPACITY) current.retiredPlans.add(id)
        if (current.retiredPlans.size == RETIRED_PLAN_CAPACITY) {
            // Never evict a tombstone and later resurrect its authorization. Only
            // speculation is retired; current audio, reports and its lease continue.
            current.speculationDisabled = true
            nextPlan = null
        }
    }

    private fun discardStage() {
        nextPlan?.let { plan -> execution?.let { retirePlan(it, plan.planId!!) } }
        nextPlan = null
    }

    private fun acceptLease(lease: PlaybackLease) {
        require(safeCounter(lease.outputVersion) && lease.clientSequence in 1..MAX_SAFE_INTEGER && lease.durationMs in 1..MAX_LEASE_MS)
        if (lease.outputVersion < outputVersion) return
        val current = execution ?: return
        if (current.source?.let { lease.outputVersion == it.outputVersion && lease.eventId == it.eventId } == true) return
        if (current.parked && lease.outputVersion == current.binding.outputVersion && lease.eventId == current.binding.eventId) return
        if (lease.outputVersion != current.binding.outputVersion || lease.eventId != current.binding.eventId ||
            !authorized(current) || !grant(LeaseTiming(lease.clientSequence, lease.durationMs))) invalidateExecution()
    }

    private fun grant(timing: LeaseTiming): Boolean {
        val basis = sendTimes[timing.clientSequence] ?: return false
        if (timing.durationMs !in 1..MAX_LEASE_MS) return false
        val deadline = basis + timing.durationMs
        if (deadline <= clock.monotonicMs()) return false
        leaseDeadline = deadline
        leaseJob?.cancel()
        val life = lifecycle
        leaseJob = scope.launch {
            while (true) {
                val remaining = synchronized(lock) {
                    if (life != lifecycle || leaseDeadline == 0L) return@launch
                    leaseDeadline - clock.monotonicMs()
                }
                if (remaining > 0) delay(remaining) else {
                    synchronized(lock) {
                        if (life == lifecycle && leaseDeadline != 0L && clock.monotonicMs() >= leaseDeadline) {
                            invalidateExecution()
                            mutableState.value = state.value.copy(playback = state.value.playback?.copy(hasReportedPlayback = false,
                                isPlaying = false, isBuffering = false, isLoading = false), error = "Playback permission expired. Select the output again.")
                        }
                    }
                    return@launch
                }
            }
        }
        return true
    }

    private fun authorized(current: Execution): Boolean = !closed && connection?.isOpen == true &&
        execution === current && current.generation == operation && !current.revoked && !current.parked &&
        clock.monotonicMs() < leaseDeadline

    private fun observable(current: Execution): Boolean = authorized(current) ||
        current.parked && !current.revoked && execution === current && connection?.isOpen == true

    private fun targetOutput(): Boolean {
        val view = state.value
        return view.connectionId.isNotEmpty() && (view.snapshot?.selectedConnectionId == view.connectionId ||
            view.snapshot?.transfer?.targetConnectionId == view.connectionId)
    }

    private fun startSampler(life: Long) {
        samplerJob = scope.launch {
            while (true) {
                delay(100)
                synchronized(lock) {
                    if (life != lifecycle) return@launch
                    if (connection?.isOpen != true) {
                        disconnectLocked("Playback disconnected.")
                        return@launch
                    }
                    val now = clock.monotonicMs()
                    val expired = pending.filterValues { now - it.sentAt >= 15_000 }.keys.toList()
                    expired.forEach { pending.remove(it) }
                    if (expired.isNotEmpty()) mutableState.value = state.value.copy(error = "Playback action unconfirmed. It will not be replayed.")
                    execution?.let { current ->
                        if (observable(current) && (current.parked || current.lastReportAt?.let { now - it >= REPORT_INTERVAL_MS } != false)) {
                            // Sampling never manufactures a native ended event.
                            report(current, audio.facts(), endedEvent = false)
                        }
                    }
                    publish()
                }
            }
        }
    }

    private val observationJob = scope.launch {
        audio.observations.collect { observation ->
            synchronized(lock) {
                val current = execution ?: return@synchronized
                if (observation.binding != current.binding || observation.facts.binding != current.binding) return@synchronized
                val previous = current.lastFacts
                val facts = observation.facts
                // Ordinary native timeupdate must not become a second progress sampler.
                val meaningful = previous == null || facts.isPlaying != previous.isPlaying ||
                    facts.isBuffering != previous.isBuffering || facts.isLoading != previous.isLoading ||
                    facts.error != previous.error || facts.duration != previous.duration || facts.ended || observation.seekCompleted ||
                    (!facts.isPlaying && facts.elapsed != previous.elapsed)
                if (meaningful) report(current, facts, endedEvent = facts.ended)
            }
        }
    }

    private fun report(current: Execution, facts: AudioFacts, endedEvent: Boolean = false, allowFinal: Boolean = false) {
        if (facts.binding != current.binding || execution !== current || current.revoked) return
        if (!allowFinal && !observable(current)) return
        if (!safeSeconds(facts.elapsed) || !safeSeconds(facts.duration) || !validMediaError(facts.error)) return
        if (current.parked && (facts.isPlaying || facts.ended)) {
            if (facts.isPlaying) audio.pause()
            return
        }
        // A timer observing ended=true cannot authorize traversal. Wait for the callback.
        if (facts.ended && !endedEvent && !current.endedReported && !allowFinal) return
        if (current.parked && current.lastFacts == facts) return
        if (endedEvent && current.endedReported) return
        val ended = endedEvent && facts.ended && !current.endedReported
        val plan = nextPlan?.takeIf {
            !current.speculationDisabled && ended && sameSource(it, current) && current.instruction.desiredPlaying && authorized(current)
        }
        val payload = fields("type" to "report", "outputVersion" to current.binding.outputVersion,
            "eventId" to current.binding.eventId, "instructionId" to current.binding.instructionId,
            "elapsed" to facts.elapsed, "duration" to facts.duration, "isPlaying" to facts.isPlaying,
            "isBuffering" to facts.isBuffering, "isLoading" to facts.isLoading, "error" to facts.error, "ended" to ended)
        val sent = numbered(JsonObject(payload + if (plan != null) fields("planId" to plan.planId) else emptyMap()))
        if (sent == null) return
        current.lastFacts = facts
        current.lastReportAt = clock.monotonicMs()
        if (ended) current.endedReported = true
        if (plan != null && execution === current && authorized(current)) {
            val next = plan.next!!
            // Source ended is already in socket order; next inherits its exact deadline.
            beginExecution(PlaybackInstruction(next.instructionId, "execute", current.binding.outputVersion,
                next.eventId, 0.0, true, track = next.track, planId = plan.planId), source = plan)
        }
    }

    private fun ack(instruction: PlaybackInstruction, outcome: AudioResult): Long? = numbered(fields(
        "type" to "ack", "instructionId" to instruction.id, "outputVersion" to instruction.outputVersion,
        "eventId" to instruction.eventId, "status" to outcome.name.lowercase(),
    ))

    private fun numbered(payload: JsonObject): Long? {
        if (clientSequence >= MAX_SAFE_INTEGER) {
            disconnectLocked("Playback counter exhausted. Reconnect explicitly.")
            return null
        }
        val sequence = ++clientSequence
        sendTimes[sequence] = clock.monotonicMs()
        while (sendTimes.size > 256) sendTimes.remove(sendTimes.keys.first())
        if (!send(JsonObject(payload + fields("clientSequence" to sequence)))) {
            sendTimes.remove(sequence)
            return null
        }
        return sequence
    }

    private fun send(payload: JsonObject): Boolean {
        val live = connection
        val text = payload.toString()
        val sent = try {
            text.toByteArray(Charsets.UTF_8).size <= 64 * 1024 && live?.isOpen == true && live.send(text)
        } catch (_: Exception) { false }
        if (!sent) disconnectLocked("Playback connection lost. Actions will not be replayed.")
        return sent
    }

    private fun invalidateExecution() {
        operation++
        mediaJob?.cancel()
        mediaJob = null
        execution?.revoked = true
        execution = null
        prepared = null
        nextPlan = null
        leaseDeadline = 0
        leaseJob?.cancel()
        leaseJob = null
        audio.pause()
        mutableState.value = state.value.copy(playback = state.value.playback?.copy(hasReportedPlayback = false,
            isPlaying = false, isBuffering = false, isLoading = false))
    }

    private fun disconnectLocked(error: String?) {
        lifecycle++
        invalidateExecution()
        val old = connection
        connection = null
        connectionJob?.cancel()
        connectionJob = null
        samplerJob?.cancel()
        samplerJob = null
        old?.close()
        pending.clear()
        sendTimes.clear()
        seenInstructions.clear()
        futurePlayback = null
        undo = null
        lastUndoRequest = null
        commandId = 0
        clientSequence = 0
        outputVersion = 0
        mutableState.value = state.value.copy(connection = PlaybackConnectionState.DISCONNECTED, pendingCommands = 0,
            undo = null, latestResult = null, playback = state.value.playback?.copy(hasReportedPlayback = false,
                isPlaying = false, isBuffering = false, isLoading = false), error = error)
    }

    private fun publish() {
        val now = clock.monotonicMs()
        if (undo?.let { now >= it.deadline } == true) undo = null
        mutableState.value = state.value.copy(pendingCommands = pending.size,
            undo = undo?.let { PlaybackUndo(it.key, it.deadline - now) })
    }
}
