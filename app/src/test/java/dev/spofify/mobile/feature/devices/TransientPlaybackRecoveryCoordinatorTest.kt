package dev.spofify.mobile.feature.devices

import dev.spofify.mobile.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Deterministic admission guards supplement BUG-103's actual-host/pinned-broker RED.
 * Recording output is not Media3/broker evidence. Recovery must never import server state. */
@OptIn(ExperimentalCoroutinesApi::class)
class TransientPlaybackRecoveryCoordinatorTest {
    private class Origin {
        var generation = 0
        fun capture(): () -> Boolean {
            val captured = generation
            return { generation == captured }
        }
    }

    private fun track(id: String, key: Long, order: Long = key - 1) =
        PlaybackTrack(id, id, emptyList(), "Album", "album", 120.0, key = key, order = order)

    private fun retained() = PlaybackSnapshot(
        queue = listOf(track("A", 1), track("H1", 2), track("C1", 3), track("C2", 4)),
        position = 0, context = PlaybackContext("album", "album", "Album"), elapsed = 42.0, duration = 120.0,
        occurrence = PlaybackOccurrence(1, "A", "same-history-event", 12.0, false))

    private fun metadata(state: PlaybackSnapshot) = QueueIntentState("native_receiver", 7, "tracked",
        state.queue.mapIndexed { index, value -> QueueIntentOrigin(value.key, when (index) {
            0 -> "now"; 1 -> "high"; else -> "low"
        }) }, listOf(2))

    private fun binding(owner: String, snapshot: PlaybackSnapshot, metadata: QueueIntentState) =
        QueueIntentBinding(owner, metadata.receiverId, metadata.revision,
            snapshot.queue.getOrNull(snapshot.position)?.key, snapshot.occurrence?.eventId)

    private fun room(snapshot: PlaybackSnapshot, metadata: QueueIntentState?, owner: String = "phone",
        available: Boolean = true, quarantine: Long = 0): PlaybackDevicesView = PlaybackDevicesView(
        "phone", listOf(PlaybackDevice("phone", "Phone", owner != "phone" || available),
            PlaybackDevice("remote", "Remote", owner != "remote" || available)),
        owner, 3, 8000, quarantine, snapshot, 1, 0, null, emptyList(), emptyList(),
        queueIntents = QueueIntentsView(1,
            listOf(QueueIntentReceiver("phone", 1), QueueIntentReceiver("remote", 1)),
            if (available && quarantine == 0L && metadata != null) binding(owner, snapshot, metadata) else null,
            metadata, emptyList(), emptyList()))

    private data class Publication(val snapshot: PlaybackSnapshot?, val metadata: QueueIntentState?, val acks: List<PlaybackAck>)

    private class Protocol(var current: PlaybackDevicesView) : PlaybackDevicesProtocol {
        var registrations = 0
        var claims = 0
        var transfers = 0
        var commands = 0
        var probes = 0
        var dropNextPoll = false
        var pollGate: CompletableDeferred<Unit>? = null
        val publications = mutableListOf<Publication>()
        override suspend fun view() = current
        override suspend fun probeQueueIntents(): PlaybackDevicesView { probes++; return current }
        override suspend fun register(name: String) = register(name, null)
        override suspend fun register(name: String, queueIntentsVersion: Int?): PlaybackDevicesView {
            assertEquals(1, probes)
            assertEquals(1, queueIntentsVersion)
            registrations++
            return current
        }
        override suspend fun poll(deviceId: String, epoch: Long, stateSeq: Long?, state: PlaybackSnapshot?, acks: List<PlaybackAck>) =
            poll(deviceId, epoch, stateSeq, state, acks, null)
        override suspend fun poll(deviceId: String, epoch: Long, stateSeq: Long?, state: PlaybackSnapshot?,
            acks: List<PlaybackAck>, queueIntentState: QueueIntentState?): PlaybackDevicesView {
            PlaybackPublication.validatePoll(deviceId, epoch, stateSeq, state, acks, queueIntentState)
            publications += Publication(state, queueIntentState, acks.toList())
            pollGate?.await()
            if (state != null && current.activeDeviceId == deviceId) {
                current = current.copy(state = state, stateSeq = requireNotNull(stateSeq),
                    queueIntents = current.queueIntents?.copy(state = queueIntentState,
                        binding = queueIntentState?.let { QueueIntentBinding(deviceId, it.receiverId, it.revision,
                            state.queue.getOrNull(state.position)?.key, state.occurrence?.eventId) }))
            }
            if (dropNextPoll) {
                dropNextPoll = false
                throw IOException("Synthetic accepted owner poll response loss")
            }
            return current
        }
        override suspend fun command(deviceId: String, epoch: Long, commandId: String, command: PlaybackDeviceCommand): PlaybackDevicesView {
            commands++
            return current
        }
        override suspend fun claimUnselected(deviceId: String, epoch: Long, commandId: String, selectionSeq: Long): PlaybackDevicesView {
            claims++
            error("This retained-owner test must never acquire an output")
        }
        override suspend fun transfer(deviceId: String, epoch: Long, commandId: String, targetDeviceId: String, selectionSeq: Long): PlaybackDevicesView {
            transfers++
            error("This retained-owner test must never transfer an output")
        }
        override suspend fun rename(deviceId: String, name: String) = current
        override suspend fun leave(deviceId: String) = Unit
    }

    /** Intentionally inherits the interface's FAIL-CLOSED activatePreparedResume default. */
    private open class RecordingEndpoint(initial: PlaybackSnapshot, initialMetadata: QueueIntentState) : NativePlaybackEndpoint {
        override val supportsExactHandoff = true
        override val supportsQueueIntents = true
        var snapshot = initial
        var metadata: QueueIntentState? = initialMetadata
        var imports = 0
        var prepares = 0
        var starts = 0
        var stops = 0
        val executions = mutableListOf<PlaybackDeviceCommand>()
        val startAuthorities = mutableListOf<PlaybackAuthority>()
        override fun suspendOutput() { stops++; snapshot = snapshot.paused() }
        override fun exportSnapshot() = snapshot
        override fun exportQueueIntentState() = metadata
        override suspend fun prepare(snapshot: PlaybackSnapshot): Boolean { prepares++; return true }
        override suspend fun activate(snapshot: PlaybackSnapshot, authority: PlaybackAuthority) = activate(snapshot, null, authority)
        override suspend fun activate(snapshot: PlaybackSnapshot, queueIntentState: QueueIntentState?, authority: PlaybackAuthority): PlaybackEndpointResult {
            check(authority.isValid())
            assertFalse("Generic initial preparation is paused", snapshot.playing)
            imports++
            this.snapshot = snapshot
            metadata = requireNotNull(queueIntentState)
            return result()
        }
        fun result() = PlaybackEndpointResult("ok", snapshot, queueIntentState = metadata)
        fun start(authority: PlaybackAuthority): PlaybackEndpointResult {
            check(authority.isValid())
            starts++; startAuthorities += authority
            snapshot = snapshot.copy(playing = true, error = "")
            return result()
        }
        override suspend fun execute(command: PlaybackDeviceCommand, authority: PlaybackAuthority): PlaybackEndpointResult {
            check(authority.isValid())
            executions += command
            assertEquals("queueIntent", command.type)
            val old = requireNotNull(metadata)
            val expected = QueueIntentBinding.fromJson(command.json.getValue("expected").jsonObject)
            assertEquals(QueueIntentBinding("phone", old.receiverId, old.revision,
                snapshot.queue[snapshot.position].key, snapshot.occurrence?.eventId), expected)
            // Recording High result only; AppGraph tests exercise production placement.
            val added = PlaybackTrack("B1", "B1", emptyList(), "Album", "album", 120.0, key = 5, order = 4)
            snapshot = snapshot.copy(queue = snapshot.queue.toMutableList().apply { add(2, added) })
            metadata = old.copy(revision = old.revision + 1,
                origins = old.origins.toMutableList().apply { add(2, QueueIntentOrigin(5, "high")) }, highKeys = old.highKeys + 5)
            val detail = QueueIntentResult("queued", 1, requireNotNull(metadata).revision, "high", "unchanged", null, "")
            return PlaybackEndpointResult("ok", snapshot, queueIntentState = metadata, queueIntentResult = detail)
        }
    }

    private class Endpoint(initial: PlaybackSnapshot, initialMetadata: QueueIntentState) : RecordingEndpoint(initial, initialMetadata) {
        val preparedAcceptances = mutableListOf<PlaybackSnapshot>()
        var acceptanceGate: CompletableDeferred<Unit>? = null
        override suspend fun activatePreparedResume(snapshot: PlaybackSnapshot, authority: PlaybackAuthority): PlaybackEndpointResult {
            check(authority.isValid())
            assertEquals("Must use exact already-live state, never server display", this.snapshot, snapshot)
            assertFalse(snapshot.playing)
            preparedAcceptances += snapshot
            // A stale successful completion must be rechecked; this installs no authority.
            acceptanceGate?.let { withContext(NonCancellable) { it.await() } }
            return PlaybackEndpointResult("ok", this.snapshot, queueIntentState = metadata)
        }
    }

    private data class Fixture(val api: Protocol, val output: RecordingEndpoint, val coordinator: PlaybackCoordinator) {
        fun resume(origin: () -> Boolean, rejected: () -> Unit = {}, completed: (PlaybackEndpointResult) -> Unit = {}) {
            coordinator.explicitLocalPlay(isCurrent = origin, admissionIsCurrent = origin,
                preparedResume = output.snapshot.paused(), onRejected = rejected, onCompleted = completed) { output.start(it) }
        }
        fun assertNoImportOrTakeover() {
            assertEquals("Only the original initial import", 1, output.imports)
            assertEquals(0, output.prepares)
            assertEquals(1, api.registrations)
            assertEquals(0, api.claims)
            assertEquals(0, api.transfers)
            assertEquals(0, api.commands)
        }
    }

    private suspend fun TestScope.withRecovery(defaultPreparedEndpoint: Boolean = false, block: suspend (Fixture) -> Unit) {
        val state = retained()
        val meta = metadata(state)
        val output = if (defaultPreparedEndpoint) RecordingEndpoint(state, meta) else Endpoint(state, meta)
        val api = Protocol(room(state, meta))
        val coordinator = PlaybackCoordinator(api, output, backgroundScope, clockMs = { testScheduler.currentTime })
        val fixture = Fixture(api, output, coordinator)
        try {
            coordinator.setServiceActive(true); runCurrent()
            assertEquals(0, output.starts)
            coordinator.localAction(isCurrent = Origin().capture()) { output.start(it) }
            runCurrent()
            assertEquals("Setup really executed the authorized initial action", 1, output.starts)
            assertTrue(output.snapshot.playing)
            assertNotNull(coordinator.currentAuthority())
            val originalPermit = output.startAuthorities.single()
            api.dropNextPoll = true
            coordinator.retry(); runCurrent()
            assertTrue("Lost response was an ordinary playing publication", api.publications.last().snapshot?.playing == true)
            assertTrue("No ACK uncertainty in this scenario", api.publications.last().acks.isEmpty())
            assertFalse(output.snapshot.playing)
            assertFalse(originalPermit.isValid())
            assertNull(coordinator.currentAuthority())
            assertEquals(PlaybackConnectionRecovery.VERIFICATION_NEEDED, coordinator.state.value.recovery)
            coordinator.retry(); runCurrent()
            assertTrue(coordinator.state.value.connected)
            assertEquals(PlaybackConnectionRecovery.NONE, coordinator.state.value.recovery)
            assertNull("Healthy observation is not activation", coordinator.currentAuthority())
            fixture.assertNoImportOrTakeover()
            block(fixture)
        } finally {
            (output as? Endpoint)?.acceptanceGate?.complete(Unit)
            api.pollGate?.complete(Unit)
            coordinator.close(); runCurrent()
        }
    }

    @Test fun repeatedFailedVerificationPreservesPriorActivationButCannotInventPristineProvenance() = runTest {
        withRecovery { f ->
            repeat(2) {
                f.api.dropNextPoll = true; f.coordinator.retry(); runCurrent()
                assertNull(f.coordinator.currentAuthority())
            }
            f.coordinator.retry(); runCurrent()
            assertTrue(f.coordinator.state.value.canQueueIntent)
            f.resume(Origin().capture()); runCurrent()
            assertEquals(2, f.output.starts)
            assertEquals(1, (f.output as Endpoint).preparedAcceptances.size)
            f.assertNoImportOrTakeover()
        }
        val state = retained()
        val output = Endpoint(state, metadata(state))
        val api = Protocol(room(state, output.metadata))
        val c = PlaybackCoordinator(api, output, backgroundScope, clockMs = { testScheduler.currentTime })
        try {
            c.setServiceActive(true); runCurrent()
            assertFalse(c.state.value.canQueueIntent)
            repeat(2) { api.dropNextPoll = true; c.retry(); runCurrent() }
            c.retry(); runCurrent()
            assertTrue(c.state.value.connected)
            assertFalse(c.state.value.canQueueIntent)
            var rejected = 0
            Fixture(api, output, c).resume(Origin().capture(), { rejected++ }); runCurrent()
            assertEquals(1, rejected)
            assertTrue(c.state.value.message.isNotBlank())
            assertTrue(output.preparedAcceptances.isEmpty())
            assertEquals(0, output.imports); assertEquals(0, output.starts)
            assertEquals(state, output.snapshot)
            assertNull(c.currentAuthority())
            assertEquals(1, api.registrations)
            assertEquals(0, api.claims); assertEquals(0, api.transfers)
        } finally { c.close(); runCurrent() }
    }

    @Test fun ownerAndEpochAbaRetiresProvenanceEvenWhenExactPreparationReturns() = runTest {
        withRecovery { f ->
            val original = f.api.current
            val frozen = f.output.snapshot
            f.api.current = room(requireNotNull(original.state), f.output.metadata, owner = "remote")
                .copy(epoch = original.epoch + 1, stateSeq = original.stateSeq)
            f.coordinator.retry(); runCurrent()
            f.api.current = original.copy(epoch = original.epoch + 2)
            f.coordinator.retry(); runCurrent()
            f.api.dropNextPoll = true; f.coordinator.retry(); runCurrent()
            f.coordinator.retry(); runCurrent()
            assertTrue(f.coordinator.state.value.connected)
            assertFalse(f.coordinator.state.value.canQueueIntent)
            var rejected = 0
            f.resume(Origin().capture(), { rejected++ }); runCurrent()
            assertEquals(1, rejected)
            assertTrue(f.coordinator.state.value.message.isNotBlank())
            assertTrue((f.output as Endpoint).preparedAcceptances.isEmpty())
            assertEquals(1, f.output.starts)
            assertEquals(frozen, f.output.snapshot)
            assertNull(f.coordinator.currentAuthority())
            f.assertNoImportOrTakeover()
        }
    }

    @Test fun passiveSuccessfulOwnerPollsExposeReadinessButNeverActivateOrReplayOldPlay() = runTest {
        withRecovery { f ->
            val output = f.output as Endpoint
            val frozen = output.snapshot
            val meta = output.metadata
            repeat(3) { advanceTimeBy(1000); runCurrent() }
            assertTrue("A fresh queue action can be offered without granting output", f.coordinator.state.value.canQueueIntent)
            assertTrue(output.preparedAcceptances.isEmpty())
            assertEquals(1, output.starts)
            assertEquals(frozen, output.snapshot)
            assertEquals(meta, output.metadata)
            assertNull(f.coordinator.currentAuthority())
            assertFalse(output.startAuthorities.single().isValid())
            f.assertNoImportOrTakeover()
        }
    }

    @Test fun freshPreparedResumeAcceptsExactLiveProgressOnceWithoutGenericSnapshotImport() = runTest {
        withRecovery { f ->
            val output = f.output as Endpoint
            // Live progress may be newer than the accepted heartbeat; preserve it exactly.
            output.snapshot = output.snapshot.copy(elapsed = 42.25, duration = 121.0, error = "media",
                occurrence = requireNotNull(output.snapshot.occurrence).copy(progress = 12.25, reported = true))
            val live = output.snapshot
            val meta = output.metadata
            var completed = 0
            var rejected = 0
            f.resume(Origin().capture(), { rejected++ }, { completed++ }); runCurrent()
            assertEquals(0, rejected)
            assertEquals(1, completed)
            assertEquals(listOf(live), output.preparedAcceptances)
            assertEquals(2, output.starts)
            assertEquals(live.copy(playing = true, error = ""), output.snapshot)
            assertEquals(meta, output.metadata)
            assertTrue(requireNotNull(f.coordinator.currentAuthority()).isValid())
            repeat(2) { advanceTimeBy(1000); runCurrent() }
            assertEquals(2, output.starts)
            assertEquals(1, output.preparedAcceptances.size)
            f.assertNoImportOrTakeover()
        }
    }

    @Test fun freshCapturedHighRecoversPreparedAdmissionButLeavesCurrentPausedAndPublishesOneReceipt() = runTest {
        withRecovery { f ->
            val output = f.output as Endpoint
            val frozen = output.snapshot
            val meta = requireNotNull(output.metadata)
            val expected = requireNotNull(f.coordinator.state.value.queueIntentBinding)
            val results = mutableListOf<QueueIntentResult?>()
            assertTrue(f.coordinator.state.value.canQueueIntent)
            f.coordinator.queueIntent(QueueIntentIntent.HIGH, expected,
                QueueIntentSelection.Track(buildJsonObject {
                    put("id", "B1"); put("name", "B1"); put("artists", JsonArray(emptyList()))
                    put("albumName", "Album"); put("albumId", "album"); put("duration", 120.0)
                }, null),
                Origin().capture(), results::add)
            runCurrent()
            val detail = requireNotNull(results.single())
            assertEquals("queued", detail.outcome)
            assertEquals(1, detail.acceptedCount)
            assertEquals("high", detail.placement)
            assertEquals("unchanged", detail.playback)
            assertNull(detail.playbackKey)
            assertEquals(listOf(frozen), output.preparedAcceptances)
            assertEquals(1, output.executions.size)
            assertEquals(1, output.starts)
            assertFalse(output.snapshot.playing)
            assertEquals(frozen.elapsed, output.snapshot.elapsed, 0.0)
            assertEquals(frozen.occurrence, output.snapshot.occurrence)
            assertEquals(frozen.queue[0], output.snapshot.queue[0])
            assertEquals(listOf("A", "H1", "B1", "C1", "C2"), output.snapshot.queue.map { it.id })
            assertEquals(meta.highKeys + 5, output.metadata?.highKeys)
            assertEquals(output.snapshot, f.api.current.state)
            assertEquals(output.metadata, f.api.current.queueIntents?.state)
            assertTrue(requireNotNull(f.coordinator.currentAuthority()).isValid())
            f.assertNoImportOrTakeover()
        }
    }

    @Test fun absentOrDifferentMetadataNeverBorrowsAcceptedReceiverAndRefusesWithGuidance() = runTest {
        for (mismatch in listOf("missing-live", "missing-accepted", "receiver", "revision", "origins", "pending-high")) {
            withRecovery { f ->
                val output = f.output as Endpoint
                val original = requireNotNull(output.metadata)
                when (mismatch) {
                    "missing-live" -> output.metadata = null
                    "missing-accepted" -> f.api.current = f.api.current.copy(queueIntents =
                        requireNotNull(f.api.current.queueIntents).copy(state = null, binding = null))
                    "receiver" -> output.metadata = original.copy(receiverId = "different_receiver")
                    "revision" -> output.metadata = original.copy(revision = original.revision + 1)
                    "origins" -> output.metadata = original.copy(origins = original.origins.map { if (it.key == 4L) it.copy(intent = "high") else it })
                    "pending-high" -> output.metadata = original.copy(highKeys = emptyList())
                }
                f.coordinator.retry(); runCurrent()
                assertFalse(mismatch, f.coordinator.state.value.canQueueIntent)
                var rejected = 0
                f.resume(Origin().capture(), { rejected++ }); runCurrent()
                assertEquals(mismatch, 1, rejected)
                assertTrue(mismatch, f.coordinator.state.value.message.isNotBlank())
                assertTrue(mismatch, output.preparedAcceptances.isEmpty())
                assertEquals(1, output.starts)
                assertNull(f.coordinator.currentAuthority())
                f.assertNoImportOrTakeover()
            }
        }
    }

    @Test fun differentLiveQueueHistoryContextOrTraversalCannotBeRepairedByServerImport() = runTest {
        val changes: List<Pair<String, (PlaybackSnapshot) -> PlaybackSnapshot>> = listOf(
            "track-metadata" to { it.copy(queue = it.queue.mapIndexed { i, track -> if (i == 3) track.copy(name = "Changed") else track }) },
            "baseline-order" to { it.copy(queue = it.queue.mapIndexed { i, track -> if (i == 3) track.copy(order = 20) else track }) },
            "history-event" to { it.copy(occurrence = requireNotNull(it.occurrence).copy(eventId = "different-event")) },
            "context" to { it.copy(context = PlaybackContext("album", "other-album", "Other")) },
            "shuffle" to { it.copy(shuffle = true) },
            "repeat" to { it.copy(repeat = "all") })
        for ((label, change) in changes) withRecovery { f ->
            val output = f.output as Endpoint
            output.snapshot = change(output.snapshot).validated()
            val untouched = output.snapshot
            f.coordinator.retry(); runCurrent()
            var rejected = 0
            f.resume(Origin().capture(), { rejected++ }); runCurrent()
            assertEquals(label, 1, rejected)
            assertFalse(label, f.coordinator.state.value.canQueueIntent)
            assertTrue(label, f.coordinator.state.value.message.isNotBlank())
            assertEquals(label, untouched, output.snapshot)
            assertTrue(label, output.preparedAcceptances.isEmpty())
            assertEquals(1, output.starts)
            assertNull(f.coordinator.currentAuthority())
            f.assertNoImportOrTakeover()
        }
    }

    @Test fun staleCapturedResumeOrHighWaitingBehindPollNeverRefreshesItsOrigin() = runTest {
        for (high in listOf(false, true)) withRecovery { f ->
            val output = f.output as Endpoint
            val gate = CompletableDeferred<Unit>()
            f.api.pollGate = gate
            f.coordinator.retry(); runCurrent()
            val origin = Origin()
            val captured = origin.capture()
            var rejected = 0
            if (high) f.coordinator.queueIntent(QueueIntentIntent.HIGH, f.coordinator.state.value.queueIntentBinding,
                QueueIntentSelection.Occurrence(4), captured, { assertNull(it); rejected++ })
            else f.resume(captured, { rejected++ })
            runCurrent()
            assertEquals(0, rejected)
            origin.generation += 2 // Route A -> B -> A does not revive captured generation.
            gate.complete(Unit); f.api.pollGate = null; runCurrent()
            assertEquals(1, rejected)
            assertTrue(output.preparedAcceptances.isEmpty())
            assertTrue(output.executions.isEmpty())
            assertEquals(1, output.starts)
            assertNull(f.coordinator.currentAuthority())
            f.assertNoImportOrTakeover()
        }
    }

    @Test fun staleOriginAtPreparedEndpointReturnDoesNotMarkActivatedOrExecuteMusic() = runTest {
        withRecovery { f ->
            val output = f.output as Endpoint
            val gate = CompletableDeferred<Unit>().also { output.acceptanceGate = it }
            val origin = Origin()
            var rejected = 0
            f.resume(origin.capture(), { rejected++ }); runCurrent()
            assertEquals(1, output.preparedAcceptances.size)
            assertNull(f.coordinator.currentAuthority())
            origin.generation++
            gate.complete(Unit); runCurrent()
            assertEquals(1, rejected)
            assertEquals(1, output.starts)
            assertNull(f.coordinator.currentAuthority())
            assertFalse(output.snapshot.playing)
            f.assertNoImportOrTakeover()
        }
    }

    @Test fun serviceSuspensionFencesEvenCancellationInsensitivePreparedEndpointSuccess() = runTest {
        withRecovery { f ->
            val output = f.output as Endpoint
            val gate = CompletableDeferred<Unit>().also { output.acceptanceGate = it }
            var rejected = 0
            f.resume(Origin().capture(), { rejected++ }); runCurrent()
            assertEquals(1, output.preparedAcceptances.size)
            f.coordinator.setServiceActive(false); runCurrent()
            gate.complete(Unit); runCurrent()
            assertEquals(1, rejected)
            assertEquals(1, output.starts)
            assertFalse(output.snapshot.playing)
            assertNull(f.coordinator.currentAuthority())
            f.assertNoImportOrTakeover()
        }
    }

    @Test fun heldFreshOriginSurvivesTwelveBenignOwnerRenewalsWithoutPassiveActivation() = runTest {
        withRecovery { f ->
            val output = f.output as Endpoint
            val captured = Origin().capture()
            val expected = f.coordinator.state.value.queueIntentBinding
            val frozen = output.snapshot
            repeat(12) {
                advanceTimeBy(1000); runCurrent()
                assertEquals(expected, f.coordinator.state.value.queueIntentBinding)
                assertTrue(f.coordinator.state.value.canQueueIntent)
                assertNull(f.coordinator.currentAuthority())
                assertTrue(output.preparedAcceptances.isEmpty())
                assertEquals(frozen, output.snapshot)
            }
            var rejected = 0
            f.resume(captured, { rejected++ }); runCurrent()
            assertEquals(0, rejected)
            assertEquals(listOf(frozen), output.preparedAcceptances)
            assertEquals(2, output.starts)
            f.assertNoImportOrTakeover()
        }
    }

    @Test fun newerResumeSupersedesWaitingRecoveryWithoutStartingTheOldIntent() = runTest {
        withRecovery { f ->
            val output = f.output as Endpoint
            val frozen = output.snapshot
            val gate = CompletableDeferred<Unit>().also { output.acceptanceGate = it }
            var firstRejected = 0
            var firstCompleted = 0
            var secondRejected = 0
            var secondCompleted = 0
            f.resume(Origin().capture(), { firstRejected++ }, { firstCompleted++ }); runCurrent()
            assertEquals(listOf(frozen), output.preparedAcceptances)
            f.resume(Origin().capture(), { secondRejected++ }, { secondCompleted++ }); runCurrent()
            assertEquals("Successor waits for the cancellation-insensitive predecessor", 1, output.preparedAcceptances.size)
            assertEquals(1, output.starts)
            assertNull(f.coordinator.currentAuthority())
            gate.complete(Unit); runCurrent()
            assertEquals(1, firstRejected)
            assertEquals(0, firstCompleted)
            assertEquals(0, secondRejected)
            assertEquals(1, secondCompleted)
            assertEquals(listOf(frozen, frozen), output.preparedAcceptances)
            assertEquals("Only the new deliberate Resume starts", 2, output.starts)
            repeat(2) { advanceTimeBy(1000); runCurrent() }
            assertEquals(2, output.starts)
            assertTrue(requireNotNull(f.coordinator.currentAuthority()).isValid())
            f.assertNoImportOrTakeover()
        }
    }

    @Test fun authLossDuringPreparedAcceptanceCannotReturnAuthorityOrExecuteMusic() = runTest {
        withRecovery { f ->
            val output = f.output as Endpoint
            val gate = CompletableDeferred<Unit>().also { output.acceptanceGate = it }
            val frozen = output.snapshot
            var rejected = 0
            var completed = 0
            f.resume(Origin().capture(), { rejected++ }, { completed++ }); runCurrent()
            assertEquals(listOf(frozen), output.preparedAcceptances)
            f.coordinator.authLost(); runCurrent()
            gate.complete(Unit); runCurrent()
            assertEquals(1, rejected)
            assertEquals(0, completed)
            assertEquals(PlaybackConnectionRecovery.AUTH_REQUIRED, f.coordinator.state.value.recovery)
            assertNull(f.coordinator.currentAuthority())
            assertFalse(f.coordinator.state.value.canQueueIntent)
            assertEquals(frozen, output.snapshot)
            assertEquals(1, output.starts)
            f.assertNoImportOrTakeover()
        }
    }

    @Test fun changedAcceptedBindingWhileHighWaitsCannotBorrowNewRecoveryReadiness() = runTest {
        withRecovery { f ->
            val output = f.output as Endpoint
            val frozen = output.snapshot
            val expected = requireNotNull(f.coordinator.state.value.queueIntentBinding)
            val gate = CompletableDeferred<Unit>().also { f.api.pollGate = it }
            f.coordinator.retry(); runCurrent()
            val results = mutableListOf<QueueIntentResult?>()
            f.coordinator.queueIntent(QueueIntentIntent.HIGH, expected,
                QueueIntentSelection.Occurrence(4), Origin().capture(), results::add)
            runCurrent()
            assertTrue(results.isEmpty())
            // A newer accepted semantic revision is not the captured action's binding,
            // even if the displayed queue happens to have returned to the same shape.
            val changed = requireNotNull(output.metadata).copy(revision = expected.revision + 2)
            f.api.current = room(frozen, changed).copy(stateSeq = f.api.current.stateSeq + 1)
            gate.complete(Unit); f.api.pollGate = null; runCurrent()
            assertEquals(listOf<QueueIntentResult?>(null), results)
            assertNotEquals(expected, f.coordinator.state.value.queueIntentBinding)
            assertTrue(output.preparedAcceptances.isEmpty())
            assertTrue(output.executions.isEmpty())
            assertEquals(frozen, output.snapshot)
            assertEquals(1, output.starts)
            assertNull(f.coordinator.currentAuthority())
            f.assertNoImportOrTakeover()
        }
    }

    @Test fun lostRecoveryPublicationStopsOnceAndNeverReplaysUntilAnotherFreshResume() = runTest {
        withRecovery { f ->
            val output = f.output as Endpoint
            val frozen = output.snapshot
            f.api.dropNextPoll = true
            f.resume(Origin().capture()); runCurrent()
            assertEquals(2, output.starts)
            assertEquals(listOf(frozen), output.preparedAcceptances)
            assertEquals(PlaybackConnectionRecovery.VERIFICATION_NEEDED, f.coordinator.state.value.recovery)
            assertEquals(frozen, output.snapshot)
            assertNull(f.coordinator.currentAuthority())
            assertTrue(output.startAuthorities.none { it.isValid() })
            val acceptedSequence = f.api.current.stateSeq
            f.coordinator.retry(); runCurrent()
            repeat(2) { advanceTimeBy(1000); runCurrent() }
            assertEquals("Healthy polls do not republish the lost result", acceptedSequence, f.api.current.stateSeq)
            assertEquals(2, output.starts)
            assertEquals(listOf(frozen), output.preparedAcceptances)
            assertEquals(frozen, output.snapshot)
            assertNull(f.coordinator.currentAuthority())
            f.resume(Origin().capture()); runCurrent()
            assertEquals("A distinct fresh action may start the retained occurrence", 3, output.starts)
            assertEquals(listOf(frozen, frozen), output.preparedAcceptances)
            assertEquals(frozen.occurrence, output.snapshot.occurrence)
            assertTrue(requireNotNull(f.coordinator.currentAuthority()).isValid())
            f.assertNoImportOrTakeover()
        }
    }

    @Test fun unsupportedPreparedEndpointFailsClosedWithoutGenericFallback() = runTest {
        withRecovery(defaultPreparedEndpoint = true) { f ->
            var rejected = 0
            f.resume(Origin().capture(), { rejected++ }); runCurrent()
            assertEquals(1, rejected)
            assertEquals(1, f.output.starts)
            assertFalse(f.output.snapshot.playing)
            assertNull(f.coordinator.currentAuthority())
            assertTrue(f.coordinator.state.value.message.isNotBlank())
            f.assertNoImportOrTakeover()
        }
    }

    @Test fun selectedRemoteUnavailableOutputAndQuarantineNeverUseLocalRecovery() = runTest {
        for (case in listOf("remote", "unavailable-remote", "unavailable-self", "quarantine", "pending-outcome")) withRecovery { f ->
            val output = f.output as Endpoint
            val remote = case == "remote" || case == "unavailable-remote"
            val meta = requireNotNull(output.metadata).let { if (remote) it.copy(receiverId = "remote_receiver") else it }
            f.api.current = room(requireNotNull(f.api.current.state), meta,
                owner = if (remote) "remote" else "phone", available = !case.startsWith("unavailable"),
                quarantine = if (case == "quarantine") 1000 else 0).copy(stateSeq = f.api.current.stateSeq,
                    commands = if (case == "pending-outcome") listOf(PlaybackOutcome("unresolved", "pending")) else emptyList())
            f.coordinator.retry(); runCurrent()
            var rejected = 0
            f.resume(Origin().capture(), { rejected++ }); runCurrent()
            assertEquals(case, 1, rejected)
            assertTrue(case, f.coordinator.state.value.message.isNotBlank())
            assertTrue(case, output.preparedAcceptances.isEmpty())
            assertEquals(1, output.starts)
            assertFalse(output.snapshot.playing)
            assertNull(f.coordinator.currentAuthority())
            f.assertNoImportOrTakeover()
        }
    }
}
