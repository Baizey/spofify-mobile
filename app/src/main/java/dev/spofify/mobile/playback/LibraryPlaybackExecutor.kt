package dev.spofify.mobile.playback

import dev.spofify.mobile.core.*
import dev.spofify.mobile.feature.devices.NativePlaybackEndpoint
import dev.spofify.mobile.feature.devices.LibraryPlaybackAuthority
import dev.spofify.mobile.feature.devices.LibraryPlaybackCacheLineage
import dev.spofify.mobile.feature.devices.LibraryPlaybackObservation
import kotlinx.coroutines.*

/** One main-dispatcher executor per authenticated coordinator. Never owns an online queue. */
class LibraryPlaybackExecutor(
    private val endpoint: NativePlaybackEndpoint,
    private val scope: CoroutineScope,
    private val clockMs: () -> Long,
    private val send: (LibraryPlaybackClientMessage) -> LibraryPlaybackSend?,
    private val isCurrent: () -> Boolean = { true },
) {
    private data class Basis(val time: Long, val output: Long, val event: String, val instruction: String, val status: String?)
    private class Operation(val instruction: LibraryPlaybackInstruction, val generation: Long, val plan: String? = null) {
        val outcome = CompletableDeferred<String>()
        var ended = false
        var confirmed = plan == null
        var completionSent = false
        @Volatile var stopInterrupted = false
        var successfulLocalPlayback = false
    }
    private var sessionId = ""
    @Volatile private var connectionId = ""
    private var snapshot: LibraryPlaybackSnapshot? = null
    @Volatile private var generation = 0L
    private var highOutput = 0L
    @Volatile private var operation: Operation? = null
    private var staged: LibraryPlaybackInstruction? = null
    private val retiredPlans = linkedSetOf<String>()
    private var nextDisabled = false
    @Volatile private var deadline = 0L
    @Volatile private var parked = false
    private var expiry: Job? = null
    private var sampler: Job? = null
    private var work: Job? = null
    private val bases = linkedMapOf<Long, Basis>()
    private var lastClientSequence = 0L
    private var lastFacts: LibraryPlaybackObservation? = null
    private var lastReportAt = Long.MIN_VALUE
    private var preparedId: String? = null
    private var preparedEvent: String? = null
    private var preparedOutput = 0L
    private var preparedStateVersion = -1L
    private var preparedTrack: LibraryPlaybackTrack? = null
    private var preparedReleaseId: String? = null

    init {
        // The media callback carries the operation ID captured at listener installation,
        // never a late callback relabeled with the current execution.
        endpoint.onObservation = { id, ended -> observe(id, ended) }
    }

    fun connected(sessionId: String, connectionId: String) {
        disconnected()
        require(sessionId.isNotBlank() && connectionId.isNotBlank())
        this.sessionId = sessionId
        this.connectionId = connectionId
        sampler = scope.launch {
            while (isActive) {
                delay(100)
                val op = operation ?: continue
                if (!parked && !live(op)) { expire(); continue }
                // Also discovers stopped fact changes after a failed native listener.
                val now = clockMs()
                if (parked || lastFacts == null || now - lastReportAt >= 2000) report(op, false, null, sampled = true)
            }
        }
    }

    fun disconnected() {
        generation++
        connectionId = ""; sessionId = ""; snapshot = null
        work?.cancel(); work = null
        expiry?.cancel(); expiry = null
        sampler?.cancel(); sampler = null
        endpoint.suspendOutput()
        operation = null; staged = null; retiredPlans.clear(); nextDisabled = false; deadline = 0; parked = false
        highOutput = 0; preparedId = null; preparedEvent = null; preparedOutput = 0
        preparedTrack = null; preparedReleaseId = null
        bases.clear(); lastClientSequence = 0L; lastFacts = null; lastReportAt = Long.MIN_VALUE
    }

    fun update(snapshot: LibraryPlaybackSnapshot) {
        if (connectionId.isEmpty() || snapshot.sessionId != sessionId) return
        val old = this.snapshot
        if (old != null && snapshot.stateVersion < old.stateVersion) return
        this.snapshot = snapshot
        if (operation == null && preparedId != null && snapshot.stateVersion > preparedStateVersion &&
            (snapshot.transfer?.targetConnectionId != connectionId || snapshot.outputVersion != preparedOutput)) {
            generation++; work?.cancel(); endpoint.suspendOutput()
            preparedId = null; preparedTrack = null; preparedReleaseId = null
        }
        val op = operation ?: return
        // Targeted activation precedes its structural publication. An older snapshot
        // cannot revoke the newly admitted operation. Logical device equality is NEVER enough.
        if (snapshot.outputVersion >= op.instruction.outputVersion &&
            (snapshot.selectedConnectionId != connectionId || snapshot.outputVersion != op.instruction.outputVersion)) expire()
    }

    fun discardStagedNext() {
        staged?.planId?.let(::retirePlan)
        staged = null
    }

    /** Explicit Pause/Close (including focus/noisy Pause) cancels native starts BEFORE
     * the command send. This is not a lease retirement or an execution/stopped ACK. */
    fun interruptForStopIntent() {
        if (connectionId.isEmpty() || !isCurrent() || snapshot?.selectedConnectionId != connectionId) return
        discardStagedNext()
        operation?.stopInterrupted = true
        work?.cancel()
        endpoint.suspendOutput()
        // Keep operation/outcome, generation and finite deadline for the authoritative
        // absolute pause/close execution (or release/prepare). No frames are sent here.
    }

    private fun retirePlan(id: String) {
        if (retiredPlans.size < 256) retiredPlans += id
        else nextDisabled = true // bounded tombstones; ordinary server-ended traversal still works
    }

    private fun addressed(value: LibraryPlaybackInstruction): Boolean {
        val state = snapshot ?: return false
        if (connectionId.isEmpty() || !isCurrent() || value.outputVersion < highOutput) return false
        return when (value.type) {
            // The ordered targeted control precedes the structural transfer publication.
            // Preparation is silent and grants no authority, even on a sibling controller.
            "prepare" -> value.outputVersion >= state.outputVersion
            "activate" -> state.selectedConnectionId == connectionId || state.transfer?.targetConnectionId == connectionId
            "release" -> state.selectedConnectionId == connectionId || state.transfer?.sourceConnectionId == connectionId || operation != null || preparedId != null
            else -> state.selectedConnectionId == connectionId
        }
    }

    fun instruction(value: LibraryPlaybackInstruction) {
        if (!addressed(value)) return
        when (value.type) {
            "stageNext" -> {
                val op = operation ?: return
                if (!nextDisabled && !op.stopInterrupted && value.planId !in retiredPlans && live(op) && !op.ended && value.outputVersion == op.instruction.outputVersion &&
                    value.eventId == op.instruction.eventId && value.currentInstructionId == op.instruction.id && value.next != null &&
                    (staged == null || staged?.planId != value.planId)) staged = value
                return
            }
            "cancelNext" -> {
                val op = operation ?: return
                if (value.outputVersion == op.instruction.outputVersion && value.eventId == op.instruction.eventId &&
                    value.currentInstructionId == op.instruction.id) {
                    value.planId?.let(::retirePlan)
                    if (staged?.planId == value.planId) staged = null
                }
                // An admitted next keeps its inherited finite deadline, but cancellation
                // invalidates its pending native operation. Only a newer execute can restart.
                if (op.plan == value.planId && value.planId != null && !op.confirmed) {
                    generation++; endpoint.suspendOutput(); work?.cancel()
                    op.outcome.complete("rejected")
                }
                return
            }
        }
        if (value.type == "release") { release(value); return }
        val existing = operation
        if (value.type == "execute" && existing != null && existing.instruction.id == value.id && existing.plan != null &&
            existing.plan == value.planId && existing.instruction.eventId == value.eventId &&
            existing.instruction.outputVersion == value.outputVersion) {
            // Deduplication starts at admission, including pending and FAILED operations.
            if (existing.completionSent) return
            val until = leaseDeadline(value.lease) ?: return reject(value)
            if (!live(existing)) return reject(value)
            deadline = until; existing.confirmed = true; scheduleExpiry(existing)
            scope.launch {
                val result = existing.outcome.await()
                if (operation === existing && connectionId.isNotEmpty()) complete(existing, value, result)
            }
            return
        }
        if (existing?.instruction?.id == value.id) return // no repeated seek/play admission
        val hadLiveExecution = existing != null && live(existing)
        val continuingMedia = value.type == "execute" && value.desiredPlaying && existing != null &&
            hadLiveExecution && !existing.stopInterrupted && existing.successfulLocalPlayback &&
            existing.instruction.outputVersion == value.outputVersion && existing.instruction.eventId == value.eventId &&
            existing.instruction.track?.key == value.track?.key && existing.instruction.track?.id == value.track?.id
        staged = null; retiredPlans.clear(); nextDisabled = false
        highOutput = maxOf(highOutput, value.outputVersion)
        val token = ++generation
        work?.cancel()
        // A proven live same-occurrence execute rebinds inside the service without draining
        // focus/FGS/decoder. All pending-start or replacement paths are synchronously stopped.
        if (!continuingMedia) {
            if (value.type == "execute" && value.desiredPlaying && hadLiveExecution &&
                existing?.successfulLocalPlayback == true && !existing.stopInterrupted)
                endpoint.suspendForReplacement()
            else endpoint.suspendOutput()
        }
        expiry?.cancel(); deadline = 0; parked = false; lastFacts = null
        when (value.type) {
            "prepare" -> {
                operation = null
                preparedId = value.id; preparedEvent = value.eventId; preparedOutput = value.outputVersion
                preparedStateVersion = snapshot?.stateVersion ?: -1L
                preparedTrack = value.track; preparedReleaseId = null
                work = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    val result = outcome { endpoint.prepare(value) { current(token) } }
                    if (current(token)) {
                        if (result != "ok") endpoint.suspendOutput()
                        ack(value, result)
                    }
                }
            }
            "activate", "execute" -> {
                val until = leaseDeadline(value.lease)
                val basis = value.lease?.clientSequence?.let(bases::get)
                val prepared = value.type != "activate" || (preparedId != null && preparedEvent == value.eventId &&
                    preparedOutput + 1 == value.outputVersion && preparedTrack == value.track && basis != null && basis.status == "ok" &&
                    basis.event == preparedEvent && basis.output == preparedOutput &&
                    basis.instruction == (preparedReleaseId ?: preparedId))
                if (until == null || !prepared || value.type == "execute" && !hadLiveExecution) { reject(value); return }
                preparedId = null; preparedReleaseId = null; preparedTrack = null
                deadline = until
                val op = Operation(value, token)
                op.successfulLocalPlayback = existing != null && existing.successfulLocalPlayback &&
                    existing.instruction.outputVersion == value.outputVersion && existing.instruction.eventId == value.eventId &&
                    existing.instruction.track?.key == value.track?.key && existing.instruction.track?.id == value.track?.id
                operation = op
                scheduleExpiry(op)
                work = scope.launch(start = CoroutineStart.UNDISPATCHED) { runOperation(op) }
            }
        }
    }

    private fun release(value: LibraryPlaybackInstruction) {
        val source = operation
        val matching = source != null && source.instruction.outputVersion == value.outputVersion &&
            source.instruction.eventId == value.eventId && !source.ended && current(source.generation)
        // Revoke pending native starts without retiring its report identity or inherited
        // deadline yet. Cleanup and final actual observation precede the release receipt.
        source?.stopInterrupted = true
        work?.cancel()
        discardStagedNext()
        val stopped = endpoint.release()
        if (stopped && matching && source != null) report(source, false, null)
        highOutput = maxOf(highOutput, value.outputVersion)
        val token = ++generation
        operation = null; deadline = 0; parked = false; lastFacts = null
        expiry?.cancel()
        if (stopped && value.eventId == preparedEvent && value.outputVersion == preparedOutput) preparedReleaseId = value.id
        if (current(token)) ack(value, if (stopped) "ok" else "rejected")
    }

    private fun current(token: Long) = connectionId.isNotEmpty() && generation == token && isCurrent()
    private fun live(op: Operation) = operation === op && current(op.generation) && !parked && clockMs() < deadline

    private suspend fun outcome(block: suspend () -> String): String = try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { "media" }

    private suspend fun runOperation(op: Operation) {
        val result = try {
            outcome { endpoint.execute(op.instruction, LibraryPlaybackAuthority { live(op) && !op.stopInterrupted }) }
        } catch (cancelled: CancellationException) {
            op.outcome.complete("rejected"); throw cancelled
        }
        op.outcome.complete(result)
        if (result == "ok" && op.instruction.desiredPlaying) op.successfulLocalPlayback = true
        if (operation !== op || !current(op.generation)) return
        if (!live(op)) { expire(); return }
        if (op.confirmed) complete(op, op.instruction, result)
        else {
            // No ACK before server confirmation, including failure. Keep inherited drain.
            if (result != "ok") endpoint.suspendOutput()
            report(op, false, null)
        }
    }

    private fun complete(op: Operation, instruction: LibraryPlaybackInstruction, status: String) {
        if (operation !== op || !current(op.generation) || op.completionSent) return
        // A fresh local stop leaves the original outcome stored, not rewritten as a
        // server receipt. Its successor absolute execution still uses the finite source
        // lease; an old completion must not park/retire that lease or cause reject loops.
        if (op.stopInterrupted) return
        if (status != "ok" || !instruction.desiredPlaying) {
            endpoint.suspendOutput() // cancellation/stop proof precedes the receipt
        }
        if (!live(op)) { expire(); return } // expiry during native stop cannot mint parked lineage
        if (!ack(instruction, status)) return
        op.completionSent = true
        if (status == "blocked" || status == "media" || status == "ok" && !instruction.desiredPlaying) {
            parked = true; deadline = 0; expiry?.cancel(); staged = null
            lastFacts = null // ACK may reset server facts; publish one actual stopped observation
            if (status == "ok" && !instruction.desiredPlaying && op.successfulLocalPlayback) {
                endpoint.parkCacheReads(LibraryPlaybackCacheLineage {
                    operation === op && current(op.generation) && parked && !op.stopInterrupted &&
                        snapshot?.selectedConnectionId == connectionId && snapshot?.outputVersion == op.instruction.outputVersion &&
                        snapshot?.state?.occurrence?.eventId.orEmpty() == op.instruction.eventId
                })
            }
        } else if (status == "rejected") { expire(); return }
        report(op, false, null)
    }

    fun lease(value: LibraryPlaybackLease) {
        val op = operation ?: return
        // Late source grants MUST NOT suspend or extend locally admitted next.
        if (parked || !op.confirmed || value.outputVersion != op.instruction.outputVersion || value.eventId != op.instruction.eventId) return
        val basis = bases[value.clientSequence] ?: return
        if (basis.output != value.outputVersion || basis.event != value.eventId || basis.instruction != op.instruction.id) return
        val until = leaseDeadline(LibraryPlaybackLeaseTiming(value.clientSequence, value.durationMs)) ?: return
        if (!live(op)) { expire(); return }
        if (until <= deadline) return // delayed older grant cannot shorten a newer grant
        deadline = until
        scheduleExpiry(op) // renewal never calls the media endpoint
    }

    private fun leaseDeadline(value: LibraryPlaybackLeaseTiming?): Long? {
        value ?: return null
        if (value.durationMs !in 1..LibraryPlaybackProtocol.MAX_LEASE_MS) return null
        val basis = bases[value.clientSequence] ?: return null
        if (basis.status != null && basis.status != "ok") return null
        val until = basis.time + value.durationMs
        return until.takeIf { it > clockMs() }
    }

    private fun scheduleExpiry(op: Operation) {
        expiry?.cancel()
        val due = deadline
        expiry = scope.launch {
            delay((due - clockMs()).coerceAtLeast(0))
            if (operation === op && !parked && deadline == due && clockMs() >= due) expire()
        }
    }

    private fun expire() {
        generation++; staged = null; deadline = 0; parked = false
        work?.cancel(); expiry?.cancel()
        endpoint.suspendOutput()
        operation?.outcome?.complete("rejected")
        operation = null; lastFacts = null
    }

    private fun reject(value: LibraryPlaybackInstruction) {
        expire()
        ack(value, "rejected")
    }

    private fun ack(value: LibraryPlaybackInstruction, status: String): Boolean = transmit(
        LibraryPlaybackClientMessage.Ack(value.id, value.outputVersion, value.eventId, status),
        value.outputVersion, value.eventId, value.id, status,
    )

    private fun transmit(message: LibraryPlaybackClientMessage, output: Long, event: String, instruction: String, status: String? = null): Boolean {
        if (connectionId.isEmpty() || !isCurrent()) return false
        val receipt = send(message)
        val sequence = receipt?.clientSequence
        if (receipt == null || sequence == null || sequence <= lastClientSequence || sequence > LibraryPlaybackProtocol.MAX_SAFE_INTEGER) {
            disconnected(); return false
        }
        lastClientSequence = sequence
        bases[sequence] = Basis(receipt.sentAtNanos / 1_000_000L, output, event, instruction, status)
        val cutoff = clockMs() - LibraryPlaybackProtocol.MAX_LEASE_MS
        bases.entries.removeAll { it.value.time < cutoff }
        // No unbounded receipt ledger even with malicious native event rates.
        if (bases.size > 512) { disconnected(); return false }
        return true
    }

    private fun observe(instructionId: String, ended: Boolean) {
        val op = operation ?: return
        if (op.instruction.id != instructionId || !current(op.generation)) return
        if (ended) {
            if (!live(op) || op.stopInterrupted || op.ended) return
            op.ended = true
            val plan = staged?.takeIf { it.outputVersion == op.instruction.outputVersion && it.eventId == op.instruction.eventId &&
                it.currentInstructionId == op.instruction.id }
            staged = null
            if (!report(op, true, plan?.planId)) return
            val next = plan?.next ?: return
            if (!live(op)) return
            // Admission happens BEFORE launching/suspending native work.
            val value = LibraryPlaybackInstruction(next.instructionId, "execute", op.instruction.outputVersion,
                next.eventId, 0.0, true, track = next.track, planId = plan.planId)
            val admitted = Operation(value, ++generation, plan.planId)
            operation = admitted; lastFacts = null; retiredPlans.clear(); nextDisabled = false
            work?.cancel()
            scheduleExpiry(admitted) // EXACT inherited deadline, no new local lease
            work = scope.launch(start = CoroutineStart.UNDISPATCHED) { runOperation(admitted) }
        } else if (parked || live(op)) report(op, false, null, sampled = false)
    }

    private fun report(op: Operation, ended: Boolean, plan: String?, sampled: Boolean = false): Boolean {
        if (operation !== op || !current(op.generation) || (!parked && !live(op))) return false
        val facts = endpoint.observe()
        if (parked && (facts.isPlaying || ended)) { endpoint.suspendOutput(); return false }
        val previous = lastFacts
        if (!ended && previous == facts && (parked || !sampled)) return true
        if (!ended && sampled && previous != null && !parked && clockMs() - lastReportAt < 2000) return true
        val value = op.instruction
        if (!transmit(LibraryPlaybackClientMessage.Report(value.outputVersion, value.eventId, value.id,
                facts.elapsed, facts.duration, facts.isPlaying && !ended, facts.isBuffering, facts.isLoading,
                facts.error, ended, plan), value.outputVersion, value.eventId, value.id)) return false
        lastFacts = facts; lastReportAt = clockMs()
        return true
    }
}
