package dev.spofify.mobile.feature.devices

import dev.spofify.mobile.core.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** The broker/owner lifecycle, not a second independently invented queue-order oracle.
 * Musical state and terminal receipts below are consumed from the frozen Go corpus. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackCoordinatorQueueIntentsTest {
    private fun corpusFile(name: String): File = sequenceOf(
        File("../core/src/test/resources/queue-intents-v1/$name"),
        File("core/src/test/resources/queue-intents-v1/$name"),
    ).first { it.isFile }
    private val corpus by lazy { Json.parseToJsonElement(corpusFile("queue-intents-v1.json").readText()).jsonObject }
    private fun vector(group: String, name: String) = corpus.getValue(group).jsonArray
        .single { it.jsonObject["name"]?.jsonPrimitive?.content == name }.jsonObject
    private fun snapshot(name: String) = PlaybackSnapshot.fromJson(vector("states", name).getValue("state").jsonObject)
    private fun metadata(name: String) = QueueIntentState.fromJson(vector("states", name).getValue("metadata").jsonObject, snapshot(name))
    private fun receipt(name: String) = QueueIntentResult.fromJson(vector("results", name).getValue("result").jsonObject)
    private fun command(name: String): PlaybackDeviceCommand {
        val raw = vector("requests", name).getValue("raw").jsonPrimitive.content
        return PlaybackDeviceCommand.fromJson(Json.parseToJsonElement(raw).jsonObject.getValue("command").jsonObject)
    }
    private fun binding(owner: String, state: PlaybackSnapshot, metadata: QueueIntentState) = QueueIntentBinding(
        owner, metadata.receiverId, metadata.revision, state.queue.getOrNull(state.position)?.key, state.occurrence?.eventId)
    private fun room(owner: String = "pc", state: PlaybackSnapshot = snapshot("confirmed-P1-final"),
        metadata: QueueIntentState? = metadata("confirmed-P1-final"), epoch: Long = 7,
        directives: List<PlaybackDirective> = emptyList(), transfer: PlaybackTransfer? = null,
        sidecars: List<QueueIntentDirective> = emptyList()): PlaybackDevicesView = PlaybackDevicesView(
        "phone", listOf(PlaybackDevice("phone", "Phone", true), PlaybackDevice("pc", "PC", true)),
        owner, epoch, if (owner.isEmpty()) 0 else 8000, 0, state, 1, 0, transfer, directives, emptyList(),
        queueIntents = QueueIntentsView(1, listOf(QueueIntentReceiver("phone", 1), QueueIntentReceiver("pc", 1)),
            if (owner.isNotEmpty() && transfer == null && metadata != null) binding(owner, state, metadata) else null,
            metadata, sidecars, emptyList()))

    private data class Publication(val sequence: Long?, val snapshot: PlaybackSnapshot?,
        val metadata: QueueIntentState?, val acks: List<PlaybackAck>)

    private class Protocol(var current: PlaybackDevicesView) : PlaybackDevicesProtocol {
        val calls = mutableListOf<String>()
        val registrations = mutableListOf<Int?>()
        val publications = mutableListOf<Publication>()
        val commands = mutableListOf<Pair<String, PlaybackDeviceCommand>>()
        var readGate: CompletableDeferred<Unit>? = null
        var pollGate: CompletableDeferred<Unit>? = null
        var commandGate: CompletableDeferred<Unit>? = null
        var loseCommand = false
        var loseAck = false
        var repeatDeliveries = false
        var claims = 0
        var onClaim: ((String) -> PlaybackDevicesView)? = null
        var onTransfer: ((String) -> PlaybackDevicesView)? = null
        var onPoll: ((List<PlaybackAck>) -> Unit)? = null
        private var probed = false
        private val delivered = mutableSetOf<String>()
        override suspend fun view() = current.copy(directives = emptyList(),
            queueIntents = current.queueIntents?.copy(directives = emptyList()))
        override suspend fun probeQueueIntents(): PlaybackDevicesView {
            calls += "probe"; readGate?.await(); probed = true; return view()
        }
        override suspend fun register(name: String) = register(name, null)
        override suspend fun register(name: String, queueIntentsVersion: Int?): PlaybackDevicesView {
            assertTrue("Probe must precede register", probed)
            if (queueIntentsVersion != null) assertNotNull(current.queueIntents)
            calls += "register"; registrations += queueIntentsVersion
            return current
        }
        override suspend fun poll(deviceId: String, epoch: Long, stateSeq: Long?, state: PlaybackSnapshot?, acks: List<PlaybackAck>) =
            poll(deviceId, epoch, stateSeq, state, acks, null)
        override suspend fun poll(deviceId: String, epoch: Long, stateSeq: Long?, state: PlaybackSnapshot?,
            acks: List<PlaybackAck>, queueIntentState: QueueIntentState?): PlaybackDevicesView {
            PlaybackPublication.validatePoll(deviceId, epoch, stateSeq, state, acks, queueIntentState)
            calls += "poll"
            publications += Publication(stateSeq, state, queueIntentState, acks.toList())
            pollGate?.await()
            if (loseAck && acks.isNotEmpty()) error("synthetic lost ACK")
            if (state != null && current.activeDeviceId == deviceId) {
                current = current.copy(state = state, stateSeq = requireNotNull(stateSeq), queueIntents = current.queueIntents?.copy(
                    state = queueIntentState,
                    binding = if (current.transfer == null && queueIntentState != null) QueueIntentBinding(deviceId,
                        queueIntentState.receiverId, queueIntentState.revision, state.queue.getOrNull(state.position)?.key,
                        state.occurrence?.eventId) else null))
            }
            onPoll?.invoke(acks)
            val directives = current.directives.filter { repeatDeliveries || delivered.add(it.deliveryId) }
            return current.copy(directives = directives, queueIntents = current.queueIntents?.copy(
                directives = current.queueIntents!!.directives.filter { sidecar -> directives.any { it.deliveryId == sidecar.deliveryId } }))
        }
        override suspend fun command(deviceId: String, epoch: Long, commandId: String, command: PlaybackDeviceCommand): PlaybackDevicesView {
            calls += "command"; commands += commandId to command
            commandGate?.await(); if (loseCommand) error("synthetic lost command response")
            return current.copy(commands = listOf(PlaybackOutcome(commandId, "pending")))
        }
        override suspend fun claimUnselected(deviceId: String, epoch: Long, commandId: String, selectionSeq: Long): PlaybackDevicesView {
            calls += "claim"; claims++
            return requireNotNull(onClaim)(commandId)
        }
        override suspend fun transfer(deviceId: String, epoch: Long, commandId: String, targetDeviceId: String, selectionSeq: Long): PlaybackDevicesView {
            calls += "transfer"; return onTransfer?.invoke(targetDeviceId) ?: current
        }
        override suspend fun rename(deviceId: String, name: String) = current
        override suspend fun leave(deviceId: String) { calls += "leave" }
    }

    private class Endpoint : NativePlaybackEndpoint {
        override val supportsExactHandoff = true
        override var supportsQueueIntents = true
        var snapshot = PlaybackSnapshot()
        var metadata = QueueIntentState("native_receiver", 1, "tracked", emptyList(), emptyList())
        val preparations = mutableListOf<Pair<PlaybackSnapshot, QueueIntentState?>>()
        val activations = mutableListOf<Pair<PlaybackSnapshot, QueueIntentState?>>()
        val executions = mutableListOf<PlaybackDeviceCommand>()
        var stops = 0
        var rejectPreparation = false
        var onInterrupt: (() -> Boolean)? = null
        var executionGuard: (() -> Boolean)? = null
        var retainedAuthority: PlaybackAuthority? = null
        var onExecute: (suspend (PlaybackDeviceCommand, PlaybackAuthority) -> PlaybackEndpointResult)? = null
        override fun suspendOutput() { stops++; snapshot = snapshot.paused() }
        override fun interruptQueueIntentStart() = onInterrupt?.invoke() ?: false
        override fun exportSnapshot() = snapshot
        override fun exportQueueIntentState() = metadata
        override suspend fun prepare(snapshot: PlaybackSnapshot) = prepare(snapshot, null)
        override suspend fun prepare(snapshot: PlaybackSnapshot, queueIntentState: QueueIntentState?): Boolean {
            snapshot.validated(); queueIntentState?.validated(snapshot)
            preparations += snapshot to queueIntentState
            return !rejectPreparation
        }
        override suspend fun activate(snapshot: PlaybackSnapshot, authority: PlaybackAuthority) = activate(snapshot, null, authority)
        override suspend fun activate(snapshot: PlaybackSnapshot, queueIntentState: QueueIntentState?, authority: PlaybackAuthority): PlaybackEndpointResult {
            check(authority.isValid()); activations += snapshot to queueIntentState; this.snapshot = snapshot
            // Incarnation remains this actual output's; imports retain provenance, not identity.
            metadata = queueIntentState?.copy(receiverId = metadata.receiverId, revision = metadata.revision + 1)
                ?: QueueIntentState(metadata.receiverId, metadata.revision + 1, "legacy-reset",
                    snapshot.queue.map { QueueIntentOrigin(it.key, "legacy") }, emptyList())
            return PlaybackEndpointResult("ok", snapshot, queueIntentState = metadata)
        }
        override suspend fun execute(command: PlaybackDeviceCommand, authority: PlaybackAuthority): PlaybackEndpointResult {
            retainedAuthority = authority
            executions += command
            return onExecute?.invoke(command, authority) ?: PlaybackEndpointResult("ok", snapshot, queueIntentState = metadata)
        }
        override suspend fun execute(command: PlaybackDeviceCommand, authority: PlaybackAuthority,
            isCurrent: () -> Boolean): PlaybackEndpointResult {
            executionGuard = isCurrent
            check(isCurrent())
            return execute(command, authority)
        }
    }

    @Test fun probePrecedesRegistrationAndAdvertisementRequiresBothServerAndCompleteEndpoint() = runTest {
        for (server in listOf(false, true)) for (receiver in listOf(false, true)) {
            val api = Protocol(room().let { if (server) it else it.copy(queueIntents = null) })
            val output = Endpoint().apply { supportsQueueIntents = receiver }
            val coordinator = PlaybackCoordinator(api, output, backgroundScope, clockMs = { testScheduler.currentTime })
            coordinator.setServiceActive(true); runCurrent()
            assertEquals(listOf("probe", "register"), api.calls)
            assertEquals(listOf(if (server && receiver) 1 else null), api.registrations)
            assertTrue(output.executions.isEmpty()); assertTrue(output.activations.isEmpty())
            coordinator.close(); runCurrent()
        }
    }

    @Test fun viewOnlyProbeNeverRegistersOrAdvertisesAndServiceRestartProbesFresh() = runTest {
        val api = Protocol(room()); val output = Endpoint()
        val coordinator = PlaybackCoordinator(api, output, backgroundScope, clockMs = { testScheduler.currentTime })
        coordinator.retry(); runCurrent()
        assertEquals(listOf("probe"), api.calls); assertFalse(coordinator.state.value.canQueueIntent)
        assertTrue(output.activations.isEmpty()); assertEquals(0, output.stops)
        repeat(2) {
            coordinator.setServiceActive(true); runCurrent()
            coordinator.setServiceActive(false); runCurrent()
        }
        assertEquals(listOf(1, 1), api.registrations)
        assertEquals(3, api.calls.count { it == "probe" })
        assertTrue(output.executions.isEmpty())
        coordinator.close()
    }

    @Test fun remoteDispatchUsesOneCapturedBindingAndOnlyTerminalCorpusDetailCompletes() = runTest {
        val api = Protocol(room()); val output = Endpoint()
        val coordinator = PlaybackCoordinator(api, output, backgroundScope, clockMs = { testScheduler.currentTime })
        coordinator.setServiceActive(true); runCurrent()
        val expected = requireNotNull(coordinator.state.value.queueIntentBinding)
        val selection = QueueIntentSelection.fromJson(command("low-collection").json.getValue("selection").jsonObject)
        val results = mutableListOf<QueueIntentResult?>()
        coordinator.queueIntent(QueueIntentIntent.LOW, expected, selection, { true }, results::add)
        runCurrent()
        assertTrue(results.isEmpty()); assertEquals(1, api.commands.size)
        val (id, sent) = api.commands.single()
        assertEquals(PlaybackDeviceCommand.queueIntent(QueueIntentIntent.LOW, expected, selection), sent)
        assertTrue(output.executions.isEmpty()); assertTrue(output.activations.isEmpty()); assertEquals(0, api.claims)
        val detail = receipt("corrected-P1-queued-B-keeps-paused-A")
        api.current = api.current.copy(commands = listOf(PlaybackOutcome(id, "ok")),
            queueIntents = api.current.queueIntents!!.copy(results = listOf(QueueIntentCommandResult(id, detail))))
        coordinator.retry(); runCurrent()
        assertEquals(listOf(detail), results)
        repeat(3) { coordinator.retry(); runCurrent() }
        assertEquals(1, results.size); assertEquals(1, api.commands.size)
        coordinator.close()
    }

    @Test fun pendingTimeoutLostResponseAndMissingDetailNeverBecomeSuccessOrReplay() = runTest {
        for (failure in listOf("timeout", "lost", "missing", "cancel")) {
            val api = Protocol(room()).apply { loseCommand = failure == "lost" }
            val output = Endpoint(); val coordinator = PlaybackCoordinator(api, output, backgroundScope,
                clockMs = { testScheduler.currentTime })
            coordinator.setServiceActive(true); runCurrent()
            val results = mutableListOf<QueueIntentResult?>()
            coordinator.queueIntent(QueueIntentIntent.HIGH, coordinator.state.value.queueIntentBinding,
                QueueIntentSelection.fromJson(command("high-row").json.getValue("selection").jsonObject), { true }, results::add)
            runCurrent()
            if (failure == "missing") {
                val id = api.commands.single().first
                api.current = api.current.copy(commands = listOf(PlaybackOutcome(id, "ok")))
                coordinator.retry(); runCurrent()
            } else if (failure == "cancel") coordinator.select("phone")
            advanceTimeBy(9000); runCurrent()
            assertEquals(failure, listOf<QueueIntentResult?>(null), results)
            repeat(2) { coordinator.retry(); runCurrent() }
            assertEquals(failure, 1, api.commands.size)
            assertTrue(output.executions.isEmpty()); assertEquals(0, api.claims)
            coordinator.close(); runCurrent()
        }
    }

    @Test fun heldRemoteBindingCannotFollowSemanticAbaOutputSessionOrOriginReplacement() = runTest {
        for (change in listOf("semantic", "output", "origin", "auth", "service", "capability")) {
            val api = Protocol(room()); val output = Endpoint()
            val coordinator = PlaybackCoordinator(api, output, backgroundScope, clockMs = { testScheduler.currentTime })
            coordinator.setServiceActive(true); runCurrent()
            val captured = requireNotNull(coordinator.state.value.queueIntentBinding)
            val capturedOrigin = coordinator.interactionVersion
            var origin = true
            // Own transport first observes every intermediate publication, even if the UI
            // still holds A. The delayed action must not acquire that newer binding.
            when (change) {
                "semantic" -> {
                    val q = api.current.queueIntents!!
                    api.current = api.current.copy(queueIntents = q.copy(state = q.state!!.copy(revision = requireNotNull(q.state).revision + 1),
                        binding = captured.copy(revision = captured.revision + 1)))
                    coordinator.retry(); runCurrent()
                    // Musical A -> B -> A does not restore the semantic revision. The
                    // receiver records both changes even if UI observes only the final A.
                    api.current = api.current.copy(queueIntents = q.copy(state = q.state!!.copy(revision = captured.revision + 2),
                        binding = captured.copy(revision = captured.revision + 2)))
                    coordinator.retry(); runCurrent()
                }
                "output" -> {
                    // An output ABA changes ownership epoch even if the original receiver
                    // returns with identical music/revision. Binding has no epoch field;
                    // the caller's captured interaction/origin guard supplies that fence.
                    api.current = room("phone", epoch = 8)
                    coordinator.retry(); runCurrent()
                    api.current = room("pc", epoch = 9)
                    coordinator.retry(); runCurrent()
                    assertEquals(captured, coordinator.state.value.queueIntentBinding)
                }
                "origin" -> origin = false
                "auth" -> coordinator.authLost()
                "service" -> coordinator.setServiceActive(false)
                "capability" -> {
                    api.current = api.current.copy(queueIntents = api.current.queueIntents!!.copy(binding = null, receivers = emptyList()))
                    coordinator.retry(); runCurrent()
                }
            }
            val results = mutableListOf<QueueIntentResult?>()
            coordinator.queueIntent(QueueIntentIntent.HIGH, captured, QueueIntentSelection.Occurrence(105),
                { origin && coordinator.interactionVersion == capturedOrigin }, results::add)
            runCurrent()
            assertEquals(change, listOf<QueueIntentResult?>(null), results)
            assertTrue(change, api.commands.isEmpty()); assertTrue(output.executions.isEmpty())
            coordinator.close(); runCurrent()
        }
    }

    @Test fun queueIntentAlreadyWaitingForTransportIsCancelledByOutputChoiceAndNeverSentAfterOutputAba() = runTest {
        val api = Protocol(room()); val output = Endpoint()
        val coordinator = PlaybackCoordinator(api, output, backgroundScope, clockMs = { testScheduler.currentTime })
        coordinator.setServiceActive(true); runCurrent()
        val captured = requireNotNull(coordinator.state.value.queueIntentBinding)
        val transportGate = CompletableDeferred<Unit>()
        api.pollGate = transportGate
        coordinator.retry(); runCurrent() // Hold the existing serialized transport owner.
        val results = mutableListOf<QueueIntentResult?>()
        coordinator.queueIntent(QueueIntentIntent.HIGH, captured, QueueIntentSelection.Occurrence(105), { true }, results::add)
        runCurrent()
        assertTrue(results.isEmpty()); assertTrue(api.commands.isEmpty())
        api.onTransfer = { target ->
            api.current = room(target, epoch = if (target == "phone") 8 else 9)
            api.current
        }
        coordinator.select("phone"); runCurrent()
        assertEquals(listOf<QueueIntentResult?>(null), results)
        transportGate.complete(Unit); runCurrent()
        coordinator.select("pc"); runCurrent()
        assertEquals("pc", coordinator.state.value.activeDeviceId)
        assertEquals(captured, coordinator.state.value.queueIntentBinding)
        repeat(3) { coordinator.retry(); runCurrent() }
        assertEquals(listOf<QueueIntentResult?>(null), results)
        assertTrue("Cancelled held action must not follow a restored binding", api.commands.isEmpty())
        assertTrue(output.executions.isEmpty()); assertTrue(output.activations.isEmpty())
        assertEquals(0, api.claims)
        coordinator.close()
    }

    @Test fun decreasingRevisionFromSameReceiverFailsClosedInsteadOfRestoringAnOldCapturedBinding() = runTest {
        val api = Protocol(room()); val output = Endpoint()
        val coordinator = PlaybackCoordinator(api, output, backgroundScope, clockMs = { testScheduler.currentTime })
        coordinator.setServiceActive(true); runCurrent()
        val old = requireNotNull(coordinator.state.value.queueIntentBinding)
        val q = requireNotNull(api.current.queueIntents)
        api.current = api.current.copy(queueIntents = q.copy(state = q.state!!.copy(revision = old.revision + 1),
            binding = old.copy(revision = old.revision + 1)))
        coordinator.retry(); runCurrent()
        api.current = api.current.copy(queueIntents = q)
        coordinator.retry(); runCurrent()
        assertFalse(coordinator.state.value.connected); assertFalse(coordinator.state.value.canQueueIntent)
        val results = mutableListOf<QueueIntentResult?>()
        coordinator.queueIntent(QueueIntentIntent.HIGH, old, QueueIntentSelection.Occurrence(105), { true }, results::add)
        runCurrent()
        assertEquals(listOf<QueueIntentResult?>(null), results)
        assertTrue(api.commands.isEmpty()); assertTrue(output.executions.isEmpty())
        coordinator.close()
    }

    @Test fun pristineAlreadyLeasedRawQueueAdmissionRequiresExactAcceptedLiveEmptyIncarnationAndNeverImports() = runTest {
        for (case in listOf("exact", "stale", "nonempty")) for (incoming in listOf(false, true)) {
            val output = Endpoint()
            if (case == "nonempty") {
                output.snapshot = snapshot("confirmed-P1-final")
                output.metadata = metadata("confirmed-P1-final")
            }
            val acceptedMetadata = output.metadata.let { if (case == "stale") it.copy(revision = it.revision + 1) else it }
            val api = Protocol(room("phone", output.snapshot, acceptedMetadata))
            val coordinator = PlaybackCoordinator(api, output, backgroundScope, clockMs = { testScheduler.currentTime })
            coordinator.setServiceActive(true); runCurrent()
            assertEquals(case == "exact", coordinator.state.value.canQueueIntent)
            val expected = requireNotNull(coordinator.state.value.queueIntentBinding)
            val selection = QueueIntentSelection.fromJson(command("low-row").json.getValue("selection").jsonObject)
            val detail = receipt("active-no-op")
            output.onExecute = { _, authority ->
                assertTrue(authority.isValid())
                PlaybackEndpointResult("ok", output.snapshot, queueIntentState = output.metadata, queueIntentResult = detail)
            }
            val results = mutableListOf<QueueIntentResult?>()
            if (incoming) {
                val command = PlaybackDeviceCommand.queueIntent(QueueIntentIntent.LOW, expected, selection)
                api.current = api.current.copy(directives = listOf(PlaybackDirective("pristine", 7, "command", "pristine-command", command.json)))
                coordinator.retry(); runCurrent(); coordinator.retry(); runCurrent()
            } else {
                coordinator.queueIntent(QueueIntentIntent.LOW, expected, selection, { true }, results::add)
                runCurrent()
            }
            assertTrue("No stale import or initialization to manufacture validity", output.activations.isEmpty())
            assertTrue(output.preparations.isEmpty())
            assertEquals(if (case == "exact") 1 else 0, output.executions.size)
            if (case == "exact") {
                assertTrue(requireNotNull(coordinator.currentAuthority()).isValid())
                if (incoming) assertEquals(detail, api.publications.single { it.acks.isNotEmpty() }.acks.single().queueIntentResult)
                else assertEquals(listOf(detail), results)
            } else if (!incoming) assertEquals(listOf<QueueIntentResult?>(null), results)
            coordinator.close(); runCurrent()
        }
    }

    @Test fun rejectedActivationValidationReturnsActualUntouchedStateAndPrepareReturnsRejectedAck() = runTest {
        val output = Endpoint()
        val api = Protocol(room("phone", output.snapshot, output.metadata))
        val coordinator = PlaybackCoordinator(api, output, backgroundScope, clockMs = { testScheduler.currentTime })
        coordinator.setServiceActive(true); runCurrent()
        coordinator.localAction { authority -> output.activate(snapshot("confirmed-P1-final"), metadata("confirmed-P1-final"), authority) }
        runCurrent()
        val before = output.snapshot
        val beforeMetadata = output.metadata
        output.rejectPreparation = true
        for (type in listOf("prepare", "activate")) {
            val directive = PlaybackDirective("invalid-$type", 7, type, state = snapshot("empty-owner"))
            api.current = api.current.copy(directives = listOf(directive),
                queueIntents = api.current.queueIntents!!.copy(directives = listOf(QueueIntentDirective(directive.deliveryId, metadata("empty-owner")))))
            coordinator.retry(); runCurrent(); coordinator.retry(); runCurrent()
            val rejected = api.publications.single { it.acks.any { ack -> ack.deliveryId == directive.deliveryId } }
            assertEquals("rejected", rejected.acks.single().status)
            assertEquals(before, output.snapshot); assertEquals(beforeMetadata, output.metadata)
            if (type == "activate") { assertEquals(before, rejected.snapshot); assertEquals(beforeMetadata, rejected.metadata) }
        }
        coordinator.close()
    }

    @Test fun progressLeaseAndPausePresentationAloneDoNotReplaceCapturedRemoteSemanticBinding() = runTest {
        val api = Protocol(room()); val coordinator = PlaybackCoordinator(api, Endpoint(), backgroundScope,
            clockMs = { testScheduler.currentTime })
        coordinator.setServiceActive(true); runCurrent()
        val expected = requireNotNull(coordinator.state.value.queueIntentBinding)
        repeat(12) { index ->
            api.current = api.current.copy(state = api.current.state!!.copy(elapsed = 42.0 + index,
                playing = index % 2 == 0, occurrence = api.current.state!!.occurrence!!.copy(progress = 10.0 + index)),
                stateSeq = index + 2L, stateAgeMs = index.toLong())
            coordinator.retry(); runCurrent()
        }
        coordinator.queueIntent(QueueIntentIntent.HIGH, expected, QueueIntentSelection.Occurrence(105), { true }, onResult = {})
        runCurrent()
        assertEquals(1, api.commands.size)
        assertEquals(expected.toJson(), api.commands.single().second.json["expected"])
        coordinator.close()
    }

    @Test fun queueOnlyAndUnavailableSelectedRemoteNeverAcquireOrExecuteLocally() = runTest {
        for (selected in listOf(false, true)) for (intent in QueueIntentIntent.entries) {
            var view = room(owner = if (selected) "pc" else "")
            if (selected) view = view.copy(devices = view.devices.map { if (it.deviceId == "pc") it.copy(available = false) else it },
                queueIntents = view.queueIntents!!.copy(binding = null))
            val api = Protocol(view); val output = Endpoint()
            val coordinator = PlaybackCoordinator(api, output, backgroundScope, clockMs = { testScheduler.currentTime })
            coordinator.setServiceActive(true); runCurrent()
            // Now is the deliberate admission exception only in an actually unselected room.
            if (!selected && intent == QueueIntentIntent.NOW) { coordinator.close(); continue }
            val results = mutableListOf<QueueIntentResult?>()
            coordinator.queueIntent(intent, null, QueueIntentSelection.Occurrence(105), { true }, results::add)
            runCurrent(); advanceTimeBy(2000); runCurrent()
            assertEquals(listOf<QueueIntentResult?>(null), results); assertEquals(0, api.claims)
            assertTrue(api.commands.isEmpty()); assertTrue(output.executions.isEmpty()); assertTrue(output.activations.isEmpty())
            coordinator.close(); runCurrent()
        }
    }

    @Test fun capableAndLegacyHandoffsValidateBeforeActivationAndPreserveOrResetOnlyPriority() = runTest {
        for (legacy in listOf(false, true)) {
            val imported = snapshot("legacy-reset")
            val priority = if (legacy) null else metadata("legacy-reset").copy(provenance = "tracked", highKeys = listOf(imported.queue[1].key))
            val prepare = PlaybackDirective("prepare", 7, "prepare", "handoff", state = imported)
            val api = Protocol(room(owner = "pc", state = imported, metadata = priority, directives = listOf(prepare),
                transfer = PlaybackTransfer("handoff", "pc", "phone", "preparing"), sidecars = listOf(QueueIntentDirective("prepare", priority))))
            val output = Endpoint(); val coordinator = PlaybackCoordinator(api, output, backgroundScope,
                clockMs = { testScheduler.currentTime })
            coordinator.setServiceActive(true); runCurrent()
            assertEquals(listOf(imported to priority), output.preparations); assertTrue(output.activations.isEmpty())
            val activate = PlaybackDirective("activate", 8, "activate", "handoff", state = imported)
            api.current = room("phone", imported, priority, 8, listOf(activate),
                PlaybackTransfer("handoff", "pc", "phone", "activating"), listOf(QueueIntentDirective("activate", priority)))
            coordinator.retry(); runCurrent()
            assertEquals(imported, output.snapshot)
            assertEquals(imported to priority, output.activations.single())
            coordinator.retry(); runCurrent()
            val sent = api.publications.single { it.acks.any { ack -> ack.deliveryId == "activate" } }
            assertEquals(imported, sent.snapshot)
            assertEquals(if (legacy) "legacy-reset" else "tracked", sent.metadata!!.provenance)
            assertEquals(if (legacy) emptyList<Long>() else priority!!.highKeys, sent.metadata.highKeys)
            assertEquals(imported.queue.map { it.key }, sent.metadata.origins.map { it.key })
            coordinator.close(); runCurrent()
        }
    }

    @Test fun unknownSidecarAndMisalignedOriginsFailBeforePrepareOrAnySourceRelease() = runTest {
        for (invalid in listOf("version", "origins")) {
            val api = Protocol(room()); val output = Endpoint()
            val coordinator = PlaybackCoordinator(api, output, backgroundScope, clockMs = { testScheduler.currentTime })
            coordinator.setServiceActive(true); runCurrent()
            val q = api.current.queueIntents!!
            api.current = api.current.copy(queueIntents = if (invalid == "version") q.copy(version = 2)
                else q.copy(state = q.state!!.copy(origins = emptyList())))
            coordinator.retry(); runCurrent()
            assertFalse(coordinator.state.value.connected); assertFalse(coordinator.state.value.canQueueIntent)
            assertTrue(output.preparations.isEmpty()); assertTrue(output.activations.isEmpty()); assertTrue(output.executions.isEmpty())
            coordinator.close(); runCurrent()
        }
    }

    @Test fun negotiatedMissingHandoffEntryNeverCallsEndpointOrAcknowledgesSourceReleaseReadiness() = runTest {
        for (case in listOf("prepare", "activate-after-prepare", "direct-activate")) {
            val imported = snapshot("confirmed-P1-final")
            val priority = metadata("confirmed-P1-final")
            val api = Protocol(room()); val output = Endpoint()
            val coordinator = PlaybackCoordinator(api, output, backgroundScope, clockMs = { testScheduler.currentTime })
            coordinator.setServiceActive(true); runCurrent()
            val untouched = output.snapshot
            if (case == "activate-after-prepare") {
                val prepare = PlaybackDirective("valid-prepare", 7, "prepare", "handoff", state = imported)
                api.current = room("pc", imported, priority, directives = listOf(prepare),
                    transfer = PlaybackTransfer("handoff", "pc", "phone", "preparing"),
                    sidecars = listOf(QueueIntentDirective(prepare.deliveryId, priority)))
                coordinator.retry(); runCurrent(); coordinator.retry(); runCurrent()
                assertEquals(listOf(imported to priority), output.preparations)
                assertEquals("ok", api.publications.single { it.acks.isNotEmpty() }.acks.single().status)
            }
            val type = if (case == "prepare") "prepare" else "activate"
            val missing = PlaybackDirective("missing-$case", if (type == "prepare") 7 else 8, type, "handoff", state = imported)
            api.current = room(if (type == "prepare") "pc" else "phone", imported, priority, missing.epoch,
                listOf(missing), PlaybackTransfer("handoff", "pc", "phone", if (type == "prepare") "preparing" else "activating"),
                sidecars = emptyList())
            val previousPreparations = output.preparations.size
            coordinator.retry(); runCurrent()
            assertFalse(case, coordinator.state.value.connected)
            assertEquals(case, previousPreparations, output.preparations.size)
            assertTrue(case, output.activations.isEmpty()); assertTrue(case, output.executions.isEmpty())
            assertEquals(case, untouched, output.snapshot)
            assertTrue(case, api.publications.flatMap { it.acks }.none {
                it.deliveryId == missing.deliveryId && it.status == "ok"
            })
            // No strip-and-activate or cached successful prepare after reconciliation.
            repeat(2) { coordinator.retry(); runCurrent() }
            assertTrue(case, output.activations.isEmpty()); assertTrue(case, output.executions.isEmpty())
            assertEquals(case, previousPreparations, output.preparations.size)
            coordinator.close(); runCurrent()
        }
    }

    @Test fun firstPlayCannotHideMissingRawHandoffEntryByStagingBlankState() = runTest {
        for (missingStage in listOf("prepare", "activate")) {
            val original = snapshot("confirmed-P1-final")
            val priority = metadata("confirmed-P1-final")
            val api = Protocol(room("", original, priority)); val output = Endpoint()
            var claimId = ""
            api.onClaim = { id ->
                claimId = id
                val prepare = PlaybackDirective("prepare-$id", 7, "prepare", id, state = original)
                api.current = room("", original, priority, directives = listOf(prepare),
                    transfer = PlaybackTransfer(id, "", "phone", "preparing"),
                    sidecars = if (missingStage == "prepare") emptyList() else listOf(QueueIntentDirective(prepare.deliveryId, priority)))
                    .copy(commands = listOf(PlaybackOutcome(id, "pending")))
                // As with production claim, only the subsequent poll delivers directives.
                api.current.copy(directives = emptyList(), queueIntents = api.current.queueIntents!!.copy(directives = emptyList()))
            }
            api.onPoll = { acks ->
                if (acks.any { it.deliveryId == "prepare-$claimId" && it.status == "ok" }) {
                    val activate = PlaybackDirective("activate-$claimId", 8, "activate", claimId, state = original)
                    api.current = room("phone", original, priority, 8, listOf(activate),
                        PlaybackTransfer(claimId, "", "phone", "activating"), sidecars = emptyList())
                        .copy(commands = listOf(PlaybackOutcome(claimId, "pending")))
                }
            }
            val coordinator = PlaybackCoordinator(api, output, backgroundScope, clockMs = { testScheduler.currentTime })
            coordinator.setServiceActive(true); runCurrent()
            val results = mutableListOf<QueueIntentResult?>()
            coordinator.queueIntent(QueueIntentIntent.NOW, null,
                QueueIntentSelection.fromJson(command("now-row").json.getValue("selection").jsonObject), { true }, results::add)
            runCurrent(); advanceTimeBy(4000); runCurrent()
            assertEquals(missingStage, 1, api.claims)
            assertEquals(missingStage, if (missingStage == "prepare") 0 else 1, output.preparations.size)
            assertTrue(missingStage, output.activations.isEmpty()); assertTrue(missingStage, output.executions.isEmpty())
            assertTrue(missingStage, output.snapshot.queue.isEmpty())
            assertFalse(api.calls.contains("transfer"))
            assertTrue(api.publications.flatMap { it.acks }.none { it.deliveryId == "$missingStage-$claimId" && it.status == "ok" })
            assertEquals(missingStage, listOf<QueueIntentResult?>(null), results)
            coordinator.close(); runCurrent()
        }
    }

    @Test fun explicitNowClaimsOnlyUnselectedSelfAndValidatesRawHandoffBeforeBlankStaging() = runTest {
        for (rejectRaw in listOf(false, true)) {
            val original = snapshot("confirmed-P1-final")
            val priority = metadata("confirmed-P1-final")
            val api = Protocol(room(owner = "", state = original, metadata = priority))
            val output = Endpoint().apply { rejectPreparation = rejectRaw }
            var claimId = ""
            api.onClaim = { id ->
                claimId = id
                api.current = room("", original, priority, directives = listOf(
                    PlaybackDirective("prepare-$id", 7, "prepare", id, state = original)),
                    transfer = PlaybackTransfer(id, "", "phone", "preparing"),
                    sidecars = listOf(QueueIntentDirective("prepare-$id", priority)))
                    .copy(commands = listOf(PlaybackOutcome(id, "pending")))
                api.current.copy(directives = emptyList(), queueIntents = api.current.queueIntents!!.copy(directives = emptyList()))
            }
            var activationAccepted = false
            api.onPoll = { acks ->
                if (acks.any { it.deliveryId == "prepare-$claimId" && it.status == "ok" }) {
                    api.current = room("phone", original, priority, 8,
                        listOf(PlaybackDirective("activate-$claimId", 8, "activate", claimId, state = original)),
                        PlaybackTransfer(claimId, "", "phone", "activating"),
                        listOf(QueueIntentDirective("activate-$claimId", priority)))
                        .copy(commands = listOf(PlaybackOutcome(claimId, "pending")))
                } else if (acks.any { it.deliveryId == "activate-$claimId" && it.status == "ok" }) {
                    val staged = api.publications.last()
                    assertTrue(requireNotNull(staged.snapshot).queue.isEmpty())
                    assertTrue(requireNotNull(staged.metadata).origins.isEmpty())
                    activationAccepted = true
                    api.current = room("phone", staged.snapshot, staged.metadata, 8)
                        .copy(stateSeq = requireNotNull(staged.sequence), commands = listOf(PlaybackOutcome(claimId, "ok")))
                }
            }
            output.onExecute = { _, authority ->
                assertTrue("Terminal blank activation acceptance precedes musical execution", activationAccepted)
                assertTrue(authority.isValid())
                val detail = receipt("paused-deduplicated-no-resume")
                PlaybackEndpointResult("ok", output.snapshot, queueIntentState = output.metadata, queueIntentResult = detail)
            }
            val coordinator = PlaybackCoordinator(api, output, backgroundScope, clockMs = { testScheduler.currentTime })
            coordinator.setServiceActive(true); runCurrent()
            val results = mutableListOf<QueueIntentResult?>()
            coordinator.queueIntent(QueueIntentIntent.NOW, null,
                QueueIntentSelection.fromJson(command("now-row").json.getValue("selection").jsonObject), { true }, results::add)
            runCurrent(); advanceTimeBy(4000); runCurrent()
            assertEquals(1, api.claims); assertFalse(api.calls.contains("transfer"))
            assertEquals(original to priority, output.preparations.first())
            if (rejectRaw) {
                assertTrue(output.activations.isEmpty()); assertTrue(output.executions.isEmpty())
            } else {
                assertEquals(1, output.executions.size)
                assertTrue(output.activations.all { it.first.queue.isEmpty() && it.second == null })
                assertEquals(listOf(receipt("paused-deduplicated-no-resume")), results)
            }
            coordinator.close(); runCurrent()
        }
    }

    @Test fun newerRevisionSupersessionPublishesActualFrozenStateAndDuplicateDeliveryExecutesOnce() = runTest {
        val before = snapshot("empty-owner")
        val api = Protocol(room("phone", before, metadata("empty-owner")))
        val output = Endpoint()
        val coordinator = PlaybackCoordinator(api, output, backgroundScope, clockMs = { testScheduler.currentTime })
        coordinator.setServiceActive(true); runCurrent()
        coordinator.localAction { authority -> output.activate(before, null, authority) }; runCurrent()
        val after = snapshot("confirmed-P1-final")
        val afterMetadata = metadata("confirmed-P1-final").copy(receiverId = output.metadata.receiverId, revision = 22)
        val detail = receipt("BR-001-accepted-then-superseded")
        val selection = QueueIntentSelection.fromJson(command("now-collection").json.getValue("selection").jsonObject)
        val incoming = PlaybackDeviceCommand.queueIntent(QueueIntentIntent.NOW,
            binding("phone", output.snapshot, output.metadata), selection)
        val directive = PlaybackDirective("once-only", 7, "command", "superseded", incoming.json)
        output.onExecute = { _, _ ->
            output.snapshot = after; output.metadata = afterMetadata
            PlaybackEndpointResult("ok", after, queueIntentState = afterMetadata, queueIntentResult = detail)
        }
        api.current = room("phone", output.snapshot, output.metadata, directives = listOf(directive))
        coordinator.retry(); runCurrent()
        // A newer live edit cannot replace the already finalized ACK's musical half.
        output.snapshot = after.copy(repeat = "all")
        output.metadata = afterMetadata.copy(revision = 23)
        coordinator.retry(); runCurrent()
        val acknowledged = api.publications.single { it.acks.any { ack -> ack.deliveryId == directive.deliveryId } }
        assertEquals(after, acknowledged.snapshot); assertEquals(afterMetadata, acknowledged.metadata)
        assertEquals(detail, acknowledged.acks.single().queueIntentResult)
        assertTrue(requireNotNull(detail.commitRevision) < requireNotNull(acknowledged.metadata).revision)
        assertEquals(2, detail.acceptedCount); assertEquals("replace", detail.placement)
        assertNull(detail.playbackKey); assertEquals(1, output.executions.size)
        // A repeated server delivery reaching the coordinator still cannot execute again.
        api.repeatDeliveries = true
        api.current = api.current.copy(directives = listOf(directive))
        coordinator.retry(); runCurrent()
        assertEquals(1, output.executions.size)
        coordinator.close()
    }

    @Test fun actualCoordinatorPauseCancelsPrecommitButRetainsSameOwnerPostcommitReceiptAndAuthority() = runTest {
        val fixture = Json.parseToJsonElement(corpusFile("queue-intents-pause-receipt-v1.json").readText()).jsonObject
        val terminal = fixture.getValue("terminalPoll").jsonObject
        val installed = PlaybackSnapshot.fromJson(terminal.getValue("state").jsonObject)
        val installedMetadata = QueueIntentState.fromJson(terminal.getValue("queueIntentState").jsonObject, installed)
        val frozenDetail = QueueIntentResult.fromJson(terminal.getValue("acks").jsonArray.single().jsonObject
            .getValue("queueIntentResult").jsonObject)
        val sourceCommand = PlaybackDeviceCommand.fromJson(fixture.getValue("commandRequest").jsonObject.getValue("command").jsonObject)
        for (incoming in listOf(false, true)) for (committed in listOf(false, true)) {
            val api = Protocol(room("phone", snapshot("empty-owner"), metadata("empty-owner")))
            val output = Endpoint()
            val coordinator = PlaybackCoordinator(api, output, backgroundScope, clockMs = { testScheduler.currentTime })
            coordinator.setServiceActive(true); runCurrent()
            coordinator.localAction { authority -> output.activate(snapshot("empty-owner"), null, authority) }; runCurrent()
            val expected = requireNotNull(coordinator.state.value.queueIntentBinding)
            val afterMetadata = installedMetadata.copy(receiverId = output.metadata.receiverId, revision = output.metadata.revision + 1)
            val detail = if (committed) frozenDetail.copy(commitRevision = afterMetadata.revision) else receipt("precommit-stale")
            val held = CompletableDeferred<Unit>()
            var interrupts = 0
            var retainedAuthority: PlaybackAuthority? = null
            output.onExecute = { _, authority ->
                retainedAuthority = authority
                if (committed) { output.snapshot = installed; output.metadata = afterMetadata }
                held.await()
                assertTrue("Same-owner Pause must not revoke receipt authority", authority.isValid())
                PlaybackEndpointResult(detail.status, output.snapshot, queueIntentState = output.metadata, queueIntentResult = detail)
            }
            output.onInterrupt = {
                interrupts++; output.snapshot = output.snapshot.paused(); held.complete(Unit); true
            }
            val results = mutableListOf<QueueIntentResult?>()
            val ordering = mutableListOf<String>()
            if (incoming) {
                val command = PlaybackDeviceCommand.queueIntent(QueueIntentIntent.HIGH, expected,
                    QueueIntentSelection.fromJson(sourceCommand.json.getValue("selection").jsonObject))
                api.current = api.current.copy(directives = listOf(PlaybackDirective("held", 7, "command", "held-command", command.json)))
                coordinator.retry()
            } else {
                coordinator.queueIntent(QueueIntentIntent.HIGH, expected,
                    QueueIntentSelection.fromJson(sourceCommand.json.getValue("selection").jsonObject), { true },
                    onResult = { ordering += "result"; results += it }, onLocalCompleted = { ordering += "local" })
            }
            runCurrent()
            assertEquals(1, output.executions.size)
            val stops = output.stops
            val revision = output.metadata.revision
            coordinator.remoteCommand(PlaybackDeviceCommand.transport("pause")); runCurrent()
            assertEquals(1, interrupts); assertEquals(stops, output.stops)
            assertTrue(requireNotNull(retainedAuthority).isValid())
            assertFalse(output.snapshot.playing); assertEquals(revision, output.metadata.revision)
            if (incoming) {
                coordinator.retry(); runCurrent()
                val publication = api.publications.single { it.acks.any { ack -> ack.deliveryId == "held" } }
                assertEquals(detail, publication.acks.single().queueIntentResult)
                assertEquals(output.snapshot, publication.snapshot)
                if (committed) assertEquals(detail.commitRevision, publication.metadata!!.revision)
            } else {
                assertEquals(listOf(detail), results)
                assertEquals(listOf("local", "result"), ordering)
            }
            if (committed) {
                assertEquals(1, detail.acceptedCount); assertEquals("superseded", detail.playback)
                assertEquals("high", detail.placement); assertNull(detail.playbackKey)
                assertEquals(installed.queue, output.snapshot.queue)
            } else assertTrue(output.snapshot.queue.isEmpty())
            assertEquals(1, output.executions.size) // No second command or replay.
            coordinator.close(); runCurrent()
        }
    }

    @Test fun ownAcceptedBindingSuccessorDoesNotRevokeStoredAudibleAuthorityOrReuseAdmissionGuard() = runTest {
        val empty = snapshot("empty-owner")
        val api = Protocol(room("phone", empty, metadata("empty-owner")))
        val output = Endpoint()
        val coordinator = PlaybackCoordinator(api, output, backgroundScope, clockMs = { testScheduler.currentTime })
        coordinator.setServiceActive(true); runCurrent()
        coordinator.localAction { authority -> output.activate(empty, null, authority) }; runCurrent()
        val expected = requireNotNull(coordinator.state.value.queueIntentBinding)
        val initial = snapshot("confirmed-P1-final")
        val started = initial.copy(queue = initial.queue.take(1), elapsed = 0.0, playing = true,
            occurrence = initial.occurrence!!.copy(progress = 0.0))
        val committed = metadata("confirmed-P1-final").copy(receiverId = output.metadata.receiverId,
            revision = output.metadata.revision + 1, origins = metadata("confirmed-P1-final").origins.take(1), highKeys = emptyList())
        val detail = receipt("empty-first-actual-start").copy(commitRevision = committed.revision)
        var originCurrent = true
        val observations = mutableListOf<String>()
        output.onExecute = { _, authority ->
            assertTrue(authority.isValid())
            assertTrue(requireNotNull(output.executionGuard).invoke())
            output.snapshot = started; output.metadata = committed
            PlaybackEndpointResult("ok", started, queueIntentState = committed, queueIntentResult = detail)
        }
        coordinator.queueIntent(QueueIntentIntent.LOW, expected,
            QueueIntentSelection.fromJson(command("low-row").json.getValue("selection").jsonObject),
            isCurrent = { originCurrent }, onResult = {
                assertEquals(detail, it)
                observations += "result"
                // UI completion retires its originating request. It must not become a
                // lasting ownership condition for the now-successful audible output.
                originCurrent = false
            }, onLocalCompleted = {
                observations += "local"
                assertNotEquals(expected, coordinator.state.value.queueIntentBinding)
                assertTrue(requireNotNull(output.retainedAuthority).isValid())
                // The separate native preparation/start guard is the RAW origin guard,
                // not the old Binding's pre-dispatch admission predicate.
                assertTrue(requireNotNull(output.executionGuard).invoke())
            })
        runCurrent()
        assertEquals(listOf("local", "result"), observations)
        assertTrue(output.snapshot.playing)
        assertFalse(requireNotNull(output.executionGuard).invoke())
        assertTrue("Terminal origin retirement must not pause successful audio", requireNotNull(output.retainedAuthority).isValid())
        coordinator.retry(); runCurrent()
        assertTrue(requireNotNull(output.retainedAuthority).isValid())
        assertTrue(output.snapshot.playing)
        // Actual lease/output loss remains authoritative and revokes that same permit.
        coordinator.setServiceActive(false); runCurrent()
        assertFalse(requireNotNull(output.retainedAuthority).isValid())
        coordinator.close()
    }

    @Test fun pauseAfterFinalizationKeepsFrozenStartedReceiptThenPublishesActualPausedStateInSequence() = runTest {
        for (incoming in listOf(false, true)) {
            val api = Protocol(room("phone", snapshot("empty-owner"), metadata("empty-owner")))
            val output = Endpoint()
            val coordinator = PlaybackCoordinator(api, output, backgroundScope, clockMs = { testScheduler.currentTime })
            coordinator.setServiceActive(true); runCurrent()
            coordinator.localAction { authority -> output.activate(snapshot("empty-owner"), null, authority) }; runCurrent()
            val expected = requireNotNull(coordinator.state.value.queueIntentBinding)
            val initial = snapshot("confirmed-P1-final")
            val started = initial.copy(queue = initial.queue.take(1), elapsed = 0.0, playing = true,
                occurrence = initial.occurrence!!.copy(progress = 0.0))
            val startedMetadata = metadata("confirmed-P1-final").copy(receiverId = output.metadata.receiverId,
                revision = output.metadata.revision + 1, origins = metadata("confirmed-P1-final").origins.take(1), highKeys = emptyList())
            val detail = receipt("empty-first-actual-start").copy(commitRevision = startedMetadata.revision)
            val gate = CompletableDeferred<Unit>()
            output.onExecute = { _, _ ->
                output.snapshot = started; output.metadata = startedMetadata
                api.pollGate = gate
                PlaybackEndpointResult("ok", started, queueIntentState = startedMetadata, queueIntentResult = detail)
            }
            var pauses = 0
            output.onInterrupt = { pauses++; output.snapshot = output.snapshot.paused(); true }
            val selection = QueueIntentSelection.fromJson(command("low-row").json.getValue("selection").jsonObject)
            val result = mutableListOf<QueueIntentResult?>()
            if (incoming) {
                val command = PlaybackDeviceCommand.queueIntent(QueueIntentIntent.LOW, expected, selection)
                api.current = api.current.copy(directives = listOf(PlaybackDirective("finalized", 7, "command", "finalized-command", command.json)))
                coordinator.retry(); runCurrent()
                coordinator.retry() // Start the frozen ACK publication, now held in transport.
            } else coordinator.queueIntent(QueueIntentIntent.LOW, expected, selection, { true }, result::add)
            runCurrent()
            val frozen = api.publications.last()
            assertEquals(started, frozen.snapshot)
            val stops = output.stops
            coordinator.remoteCommand(PlaybackDeviceCommand.transport("pause")); runCurrent()
            assertEquals(1, pauses); assertEquals(stops, output.stops); assertFalse(output.snapshot.playing)
            assertTrue("Terminal result remains pending until its publication returns", result.isEmpty())
            gate.complete(Unit); runCurrent()
            if (incoming) assertEquals(detail, frozen.acks.single().queueIntentResult)
            else assertEquals(listOf(detail), result)
            // Do not relabel an already-finalized actual start as superseded or erase count.
            assertEquals("started", detail.playback); assertEquals(1, detail.acceptedCount)
            coordinator.retry(); runCurrent()
            val paused = api.publications.last()
            assertFalse(requireNotNull(paused.snapshot).playing)
            assertEquals(started.queue, paused.snapshot.queue)
            assertEquals(startedMetadata, paused.metadata)
            assertTrue(requireNotNull(paused.sequence) > requireNotNull(frozen.sequence))
            assertEquals(1, output.executions.size)
            coordinator.close(); runCurrent()
        }
    }

    @Test fun incomingDetailedAckFreezesCorpusPauseOnlyReceiptAndDoesNotResampleOnNextPublication() = runTest {
        val fixture = Json.parseToJsonElement(corpusFile("queue-intents-pause-receipt-v1.json").readText()).jsonObject
        val initial = fixture.getValue("initialPoll").jsonObject
        val terminal = fixture.getValue("terminalPoll").jsonObject
        val before = PlaybackSnapshot.fromJson(initial.getValue("state").jsonObject)
        val beforeMetadata = QueueIntentState.fromJson(initial.getValue("queueIntentState").jsonObject, before)
        val after = PlaybackSnapshot.fromJson(terminal.getValue("state").jsonObject)
        val afterMetadata = QueueIntentState.fromJson(terminal.getValue("queueIntentState").jsonObject, after)
        val ack = terminal.getValue("acks").jsonArray.single().jsonObject
        val detail = QueueIntentResult.fromJson(ack.getValue("queueIntentResult").jsonObject)
        val incoming = PlaybackDeviceCommand.fromJson(fixture.getValue("commandRequest").jsonObject.getValue("command").jsonObject)
        val directed = PlaybackDirective("delivery_pause_only", 7, "command", "pause_only", incoming.json)
        val api = Protocol(room("phone", before, beforeMetadata))
        val output = Endpoint().apply { snapshot = before; metadata = beforeMetadata }
        val coordinator = PlaybackCoordinator(api, output, backgroundScope, clockMs = { testScheduler.currentTime })
        coordinator.setServiceActive(true); runCurrent()
        // Establish a silent local incarnation first, then deliver the real corpus command.
        coordinator.localAction { authority -> output.activate(before, beforeMetadata, authority) }; runCurrent()
        output.onExecute = { _, _ ->
            output.snapshot = after; output.metadata = afterMetadata
            PlaybackEndpointResult("ok", after, queueIntentState = afterMetadata, queueIntentResult = detail)
        }
        api.current = room("phone", before, beforeMetadata, directives = listOf(directed))
        coordinator.retry(); runCurrent()
        // Independent progress/presentation after finalization must not rewrite frozen ACK.
        output.snapshot = after.copy(elapsed = 12.0)
        coordinator.retry(); runCurrent()
        val publication = api.publications.single { it.acks.any { a -> a.deliveryId == directed.deliveryId } }
        assertEquals(after, publication.snapshot); assertEquals(afterMetadata, publication.metadata)
        assertEquals(detail, publication.acks.single().queueIntentResult)
        assertEquals(detail.commitRevision, publication.metadata!!.revision)
        assertEquals(1, output.executions.size)
        coordinator.retry(); runCurrent()
        assertEquals(1, api.publications.count { it.acks.any { a -> a.deliveryId == directed.deliveryId } })
        coordinator.close()
    }
}
