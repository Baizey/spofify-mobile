package dev.spofify.mobile.core.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountChannelTest {
    private class Socket : PlaybackConnection {
        var open = true
        var replaced = false
        override val isOpen get() = open
        override val superseded get() = replaced
        private val frames = Channel<String>(Channel.UNLIMITED)
        override val messages = frames.receiveAsFlow()
        val sent = mutableListOf<JsonObject>()
        override fun send(text: String): Boolean {
            if (!open) return false
            sent += playbackJson.parseToJsonElement(text).jsonObject
            return true
        }
        fun frame(vararg values: Pair<String, Any?>) { check(frames.trySend(fields(*values).toString()).isSuccess) }
        fun report() = sent.last { it.kind == "report" }
        fun commands() = sent.filter { it.kind == "command" }.map { playbackJson.decodeFromJsonElement<AccountCommand>(JsonObject(it - "type")) }
        override fun close() { open = false; frames.close() }
    }

    private class Audio : AudioExecutor {
        override val observations = MutableSharedFlow<AudioObservation>(extraBufferCapacity = 16)
        var facts = AudioFacts()
        var refusal = false
        var readyGate: CompletableDeferred<Unit>? = null
        var pauses = 0
        val starts = mutableListOf<AudioBinding>()
        val seeks = mutableListOf<Double>()
        val guards = mutableListOf<() -> Boolean>()
        override fun facts() = facts
        override fun pause() { pauses++; facts = facts.copy(isPlaying = false) }
        override suspend fun prepare(binding: AudioBinding, track: PlaybackTrack?, elapsed: Double) = error("unused")
        override suspend fun execute(binding: AudioBinding, track: PlaybackTrack?, elapsed: Double,
            desiredPlaying: Boolean, isAuthorized: () -> Boolean): AudioResult {
            if (!isAuthorized()) return AudioResult.REJECTED
            starts += binding
            seeks += elapsed
            guards += isAuthorized
            facts = AudioFacts(binding, elapsed, duration = 48.0, isPlaying = desiredPlaying && !refusal)
            readyGate?.await()
            return if (refusal) AudioResult.BLOCKED else AudioResult.OK
        }
        fun eof(elapsed: Double, signal: Boolean = true) {
            val b = requireNotNull(facts.binding)
            facts = facts.copy(elapsed = elapsed, ended = true, isPlaying = false)
            if (signal) check(observations.tryEmit(AudioObservation(b, facts)))
        }
    }

    private class Fixture(val test: TestScope, shareDetachedPlayback: Boolean = true) {
        val audio = Audio()
        var socket = Socket()
        val claims = mutableListOf<AccountState?>()
        val reattach = mutableListOf<JsonObject?>()
        var wallShift = 0L
        var upgradeDelay = 0L
        val clock = object : PlaybackClock {
            override fun monotonicMs() = test.testScheduler.currentTime
            override fun wallTimeMs() = WALL + monotonicMs() + wallShift
        }
        val channel = AccountChannel(PlaybackTransport { _, _, local, basis ->
            claims += local?.let { playbackJson.decodeFromJsonElement<AccountState>(it) }
            reattach += basis
            delay(upgradeDelay)
            socket
        }, audio, test.backgroundScope, clock, library = object : LocalPlaybackLibrary {
            override suspend fun hasAudio(track: PlaybackTrack, isAuthorized: () -> Boolean) = isAuthorized()
            override fun resolve(track: PlaybackTrackReference?, context: PlaybackContext?) = LocalPlaybackRows(rows)
        }, shareDetachedPlayback = shareDetachedPlayback)
        val view get() = channel.state.value
        fun connect() { channel.connect("Phone", "self"); test.runCurrent() }
        fun snapshot(owner: String? = "self", generation: Long = 1, revision: Long = 1,
            cursor: AccountCursor = first, observation: AccountObservation? = null, connection: String = "socket-1") {
            snapshot(AccountState(rows, cursor, "off", false), owner, generation, revision, observation, connection)
        }
        fun snapshot(player: AccountState, owner: String? = "self", generation: Long = 1, revision: Long = 1,
            observation: AccountObservation? = null, connection: String = "socket-1") {
            val optional = observation?.let { arrayOf("observation" to encode(it)) } ?: emptyArray()
            socket.frame("type" to "snapshot", "sessionId" to "room", "connectionId" to connection,
                "revision" to revision, "owner" to encode(AccountOwner(generation, owner)),
                "devices" to encode(listOf(AccountDevice("self", "Phone", true), AccountDevice("other", "Other", true))),
                "player" to encode(player), "serverTimeMs" to WALL + clock.monotonicMs(), *optional)
            test.runCurrent()
        }
        fun delta(revision: Long, delta: AccountDelta, command: AccountCommand? = null, generation: Long = 1) {
            val optional = command?.let { arrayOf("command" to commandWire(it)) } ?: emptyArray()
            socket.frame("type" to "state", "generation" to generation, "revision" to revision, "delta" to encode(delta), *optional)
            test.runCurrent()
        }
        fun execute(command: AccountCommand, revision: Long = 1, generation: Long = 1) {
            socket.frame("type" to "execute", "generation" to generation, "revision" to revision, "command" to commandWire(command))
            test.runCurrent()
        }
        fun result(command: AccountCommand, status: String, revision: Long) {
            socket.frame("type" to "result", "id" to command.id, "status" to status, "revision" to revision)
            test.runCurrent()
        }
        fun disconnect() { socket.close(); test.runCurrent(); socket = Socket() }
    }

    @Test fun followerProjectsRapidRepeatAcrossWatermarkedResultsWithoutNativeCommands() = runTest {
        val f = Fixture(this)
        f.connect(); f.snapshot(owner = "other")
        f.channel.cycleRepeat(); f.channel.cycleRepeat()
        val (first, second) = f.socket.commands()
        assertEquals(listOf("socket-1:1", "socket-1:2"), listOf(first.id, second.id))
        assertEquals(listOf("all", "one"), listOf(first.action.string("repeat"), second.action.string("repeat")))
        assertEquals("one", f.view.snapshot?.state?.repeat)
        f.result(second, "accepted", 2)
        f.result(second, "applied", 3)
        f.delta(2, AccountDelta(repeat = "all"), first)
        f.result(first, "applied", 2)
        assertEquals("one", f.view.snapshot?.state?.repeat)
        f.delta(3, AccountDelta(repeat = "one"), second)
        assertEquals("one", f.view.snapshot?.state?.repeat)
        assertTrue(f.audio.starts.isEmpty())
        assertFalse(f.view.isPlaying)
    }

    @Test fun isolatedQueueClaimsEvenAfterTenSecondsAndLosingClaimPausesBeforeAdopting() = runTest {
        val f = Fixture(this)
        assertTrue(f.channel.playTrack(PlaybackTrackReference("duplicate"))); runCurrent()
        advanceTimeBy(11_000); runCurrent()
        assertTrue(f.audio.facts.isPlaying)
        f.connect()
        assertEquals(2, f.claims.single()?.queue?.size)
        f.snapshot(owner = "other", observation = AccountObservation(1, "first", 1_000, f.clock.wallTimeMs(), "playing"))
        assertFalse(f.audio.facts.isPlaying)
        assertFalse(f.audio.guards.single()())
        assertEquals("first", f.view.snapshot?.state?.occurrence?.eventId)
        assertTrue(f.view.isPlaying) // Remote report is not local native work.
        f.disconnect()
        advanceTimeBy(11_000); runCurrent()
        assertTrue(f.view.offline)
        assertEquals(1L, f.view.current?.key)
        assertFalse(f.view.isPlaying)
    }

    @Test fun followerKeepsConfirmedSharedQueuePausedOnLossOrExplicitOffline() = runTest {
        for (manual in listOf(false, true)) {
            val f = Fixture(this)
            f.channel.playTrack(PlaybackTrackReference("duplicate")); runCurrent()
            val isolated = f.audio.starts.single()
            f.connect()
            val b = rows.first().copy(id = "shared-b", key = 71)
            val c = rows.last().copy(id = "shared-c", key = 72)
            val cursor = AccountCursor(71, "shared-visit", 4_000, true)
            f.snapshot(AccountState(listOf(b, c), cursor, "one", true), owner = "other",
                observation = AccountObservation(71, "shared-visit", 6_500, f.clock.wallTimeMs(), "playing"))
            assertFalse(f.audio.facts.isPlaying)
            assertEquals(isolated, f.audio.starts.single())
            f.channel.jump(72)
            assertEquals("shared-c", f.view.current?.id)
            if (manual) { f.channel.setOffline(true); runCurrent() } else f.disconnect()
            assertEquals("shared-b", f.view.current?.id)
            assertEquals(listOf(b, c), f.view.queue)
            assertEquals("one", f.view.snapshot?.state?.repeat)
            assertTrue(f.view.snapshot?.state?.shuffle == true)
            assertEquals(6.5, f.view.playback?.elapsed ?: -1.0, 0.0001)
            assertFalse(f.view.desiredPlaying)
            assertFalse(f.view.isPlaying)
            assertEquals(1, f.audio.starts.size)
            assertTrue(f.channel.resume()); runCurrent()
            assertEquals(listOf(0.0, 6.5), f.audio.seeks)
            assertEquals("shared-b", f.audio.starts.last().trackId)
            assertTrue(f.view.isPlaying)
            f.channel.close()
        }
    }

    @Test fun sameDeviceReconnectDoesNotRestartOrStopPlayingAndRefreshIsBoundToNewSocket() = runTest {
        val f = Fixture(this)
        f.connect(); f.snapshot()
        val b = f.audio.starts.single()
        f.audio.facts = f.audio.facts.copy(elapsed = 7.0)
        advanceTimeBy(251)
        f.disconnect()
        assertTrue(f.view.offline)
        advanceTimeBy(2_000)
        assertTrue(f.audio.facts.isPlaying)
        assertEquals(0, f.audio.pauses)
        f.connect(); f.snapshot(revision = 2, cursor = first.copy(positionMs = 7_000), connection = "socket-2")
        assertEquals(listOf(b), f.audio.starts)
        assertEquals(0, f.audio.pauses)
        assertEquals(1L, f.socket.report().long("sequence"))
        assertEquals(7_000L, f.socket.report().getValue("observation").jsonObject.long("positionMs"))
        assertTrue(f.socket.sent.any { it.kind == "refresh" && it.long("generation") == 1L })
    }

    @Test fun naturalAdvanceThroughDuplicateStartsImmediatelyAndEchoDoesNotRestart() = runTest {
        val f = Fixture(this)
        f.connect(); f.snapshot()
        f.audio.eof(42.0); runCurrent()
        val advance = playbackJson.decodeFromJsonElement<AccountAdvance>(f.socket.report().getValue("advance"))
        assertEquals(1L, advance.fromKey)
        assertEquals(2L, advance.toKey)
        assertEquals(42_000L, advance.endedPositionMs)
        val successorFact = f.socket.report().getValue("observation").jsonObject
        assertEquals(advance.toOccurrence, successorFact.string("occurrence"))
        assertEquals("loading", successorFact.string("status"))
        assertEquals(2, f.audio.starts.size)
        val successor = AccountCursor(2, advance.toOccurrence, 0, true)
        f.delta(2, AccountDelta(cursor = successor))
        assertEquals(2, f.audio.starts.size)
        assertEquals(2L, f.view.current?.key)
        assertEquals(advance.toOccurrence, f.view.snapshot?.state?.occurrence?.eventId)
        f.audio.eof(42.0); runCurrent()
        val tail = f.socket.report().getValue("advance").jsonObject
        assertEquals(0L, tail.long("toKey"))
        f.delta(3, AccountDelta(cursor = successor.copy(desiredPlaying = false)))
        assertFalse(f.view.desiredPlaying)
        assertEquals(0.0, f.view.playback?.elapsed)
        assertEquals(2, f.audio.starts.size)
    }

    @Test fun eofFirstSettlesNextStaleAndLateSeekCannotTouchNewVisit() = runTest {
        val f = Fixture(this)
        f.connect(); f.snapshot()
        f.channel.seek(12.5); f.channel.next()
        val (seek, next) = f.socket.commands()
        assertEquals("first", seek.basis.occurrence)
        assertEquals(12_500L, seek.action.long("positionMs"))
        assertEquals(2L, next.basis.successorKey)
        f.audio.eof(44.0); runCurrent()
        val advance = f.socket.report().getValue("advance").jsonObject
        f.execute(next)
        val staleNext = f.socket.report().getValue("settlements").jsonArray.single().jsonObject
        assertEquals("stale", staleNext.string("status")); assertFalse(staleNext.containsKey("cursor"))
        f.delta(2, AccountDelta(cursor = AccountCursor(2, advance.string("toOccurrence"), 0, true)))
        f.result(next, "stale", 2)
        f.execute(seek)
        assertEquals(2, f.audio.starts.size)
        assertEquals(listOf(0.0, 0.0), f.audio.seeks)
        assertEquals("stale", f.socket.report().getValue("settlements").jsonArray.single().jsonObject.string("status"))
        val settlements = f.socket.sent.flatMap { it["settlements"]?.jsonArray.orEmpty() }.map { it.jsonObject }
        assertTrue(settlements.any { it.string("id") == next.id && it.string("status") == "stale" })
    }

    @Test fun executeFirstAppliesNextOnceAndFencesNativeEofFromOutgoingBinding() = runTest {
        val f = Fixture(this)
        f.connect(); f.snapshot()
        val outgoing = f.audio.facts
        f.channel.next()
        val next = f.socket.commands().single()
        f.execute(next)
        val settlement = f.socket.sent.flatMap { it["settlements"]?.jsonArray.orEmpty() }.map { it.jsonObject }.single()
        assertEquals("applied", settlement.string("status"))
        val newCursor = playbackJson.decodeFromJsonElement<AccountCursor>(settlement.getValue("cursor"))
        assertEquals(2L, newCursor.key)
        assertNotEquals(first.occurrence, newCursor.occurrence)
        val facts = outgoing.copy(ended = true, isPlaying = false, elapsed = 42.0)
        f.audio.observations.tryEmit(AudioObservation(requireNotNull(facts.binding), facts)); runCurrent()
        f.execute(next) // Duplicate delivery must not apply or settle again.
        assertTrue(f.socket.sent.none { it.containsKey("advance") })
        f.delta(2, AccountDelta(cursor = newCursor), next)
        f.result(next, "applied", 2)
        assertEquals(listOf(0.0, 0.0), f.audio.seeks)
        assertEquals(newCursor.occurrence, f.view.snapshot?.state?.occurrence?.eventId)
    }

    @Test fun nativeEofSeenDuringSuspendedExecuteWinsBeforeNextWithoutFlowCallback() = runTest {
        val f = Fixture(this)
        f.audio.readyGate = CompletableDeferred()
        f.connect(); f.snapshot(AccountState(rows, first, "one", false))
        f.channel.next()
        val next = f.socket.commands().single()
        f.audio.eof(43.0, signal = false) // Native callback happened, its shared-flow work has not run.
        f.audio.readyGate = null
        f.execute(next)
        val packets = f.socket.sent.filter { it.kind == "report" }
        val eof = packets.indexOfFirst { it.containsKey("advance") }
        val stale = packets.indexOfFirst { it["settlements"]?.jsonArray?.any { item -> item.jsonObject.string("id") == next.id } == true }
        assertTrue(eof >= 0 && stale > eof)
        val advance = packets[eof].getValue("advance").jsonObject
        assertEquals(first.key, advance.long("toKey"))
        assertNotEquals(first.occurrence, advance.string("toOccurrence"))
        assertEquals("stale", packets[stale].getValue("settlements").jsonArray.single().jsonObject.string("status"))
    }

    @Test fun timedOutTailNextRestoresSoundBeforeHeldRepeat() = runTest {
        val f = Fixture(this)
        val tail = AccountCursor(2, "tail", 0, true)
        f.connect(); f.snapshot(cursor = tail)
        f.audio.facts = f.audio.facts.copy(elapsed = 7.5)
        f.channel.next(); val next = f.socket.commands().single()
        f.execute(next)
        val paused = f.socket.report().getValue("settlements").jsonArray.single().jsonObject.getValue("cursor").jsonObject
        assertEquals(7_500L, paused.long("positionMs"))
        assertFalse(f.audio.facts.isPlaying)
        f.snapshot(cursor = tail) // Exact owner/session/revision, but rejected execution did pause audio.
        assertTrue(f.audio.facts.isPlaying)
        assertEquals(2, f.audio.starts.size)
        f.result(next, "failed", 1)
        f.delta(2, AccountDelta(repeat = "all"), AccountCommand("other:1", WALL, next.basis, fields("type" to "repeat", "repeat" to "all")))
        assertEquals("all", f.view.snapshot?.state?.repeat)
        assertTrue(f.socket.sent.last { it.kind == "report" }.getValue("observation").jsonObject.string("status") == "playing")
    }

    @Test fun successorEofWaitsForNextCommitAndRejectedAdvanceReconcilesAfterTraversalEdit() = runTest {
        val f = Fixture(this)
        val three = rows + rows.last().copy(key = 3, order = 3)
        val original = AccountState(three, first, "off", false)
        f.connect(); f.snapshot(original)
        f.channel.next(); val next = f.socket.commands().single()
        f.execute(next)
        val second = playbackJson.decodeFromJsonElement<AccountCursor>(
            f.socket.report().getValue("settlements").jsonArray.single().jsonObject.getValue("cursor"))
        f.audio.eof(42.0); runCurrent()
        assertEquals(3, f.audio.starts.size)
        assertTrue(f.socket.sent.none { it.containsKey("advance") })
        f.delta(2, AccountDelta(cursor = second), next)
        val eof = f.socket.sent.single { it.containsKey("advance") }
        assertEquals(2L, eof.long("revision"))
        assertEquals(second.occurrence, eof.getValue("advance").jsonObject.string("fromOccurrence"))
        f.delta(3, AccountDelta(queue = AccountQueueEdit("remove", keys = listOf(3))))
        assertEquals(PlaybackConnectionState.READY, f.view.connection)
        f.snapshot(original.copy(queue = rows, cursor = second), revision = 3)
        assertEquals(4, f.audio.starts.size)
        assertEquals(second.occurrence, f.view.snapshot?.state?.occurrence?.eventId)
    }

    @Test fun reorderedSuccessorSilentlyDropsOptimisticNextWhileSourceVisitStillMatches() = runTest {
        val f = Fixture(this)
        val third = rows.last().copy(key = 3, order = 3)
        f.connect(); f.snapshot(AccountState(rows + third, first, "off", false), owner = "other")
        f.channel.next()
        val next = f.socket.commands().single()
        assertEquals(2L, next.basis.successorKey)
        assertEquals(2L, f.view.current?.key)
        f.delta(2, AccountDelta(queue = AccountQueueEdit("order", keys = listOf(1, 3, 2))))
        assertEquals(1L, f.view.current?.key)
        f.result(next, "stale", 2)
        assertNull(f.view.error)
        assertTrue(f.audio.starts.isEmpty())
    }

    @Test fun ownerRefusesNextWhenQueueEditChangedSuccessorButNotOccurrence() = runTest {
        val f = Fixture(this)
        val third = rows.last().copy(key = 3, order = 3)
        f.connect(); f.snapshot(AccountState(rows + third, first, "off", false))
        f.channel.next()
        val next = f.socket.commands().single()
        f.delta(2, AccountDelta(queue = AccountQueueEdit("order", keys = listOf(1, 3, 2))))
        f.execute(next, revision = 2)
        val settlement = f.socket.report().getValue("settlements").jsonArray.single().jsonObject
        assertEquals("stale", settlement.string("status"))
        assertFalse(settlement.containsKey("cursor"))
        assertEquals(1, f.audio.starts.size)
        assertEquals("first", f.view.snapshot?.state?.occurrence?.eventId)
    }

    @Test fun repeatOneMakesNewVisitAndOldNextAndSeekNeverApplyToIt() = runTest {
        val f = Fixture(this)
        f.connect(); f.snapshot(AccountState(rows, first, "one", false))
        f.channel.seek(13.0); f.channel.next()
        val (seek, next) = f.socket.commands()
        f.audio.eof(44.0); runCurrent()
        val advance = f.socket.report().getValue("advance").jsonObject
        assertEquals(first.key, advance.long("toKey"))
        val sameRowNewVisit = first.copy(occurrence = advance.string("toOccurrence"))
        assertNotEquals(first.occurrence, sameRowNewVisit.occurrence)
        f.execute(next)
        assertEquals("stale", f.socket.report().getValue("settlements").jsonArray.single().jsonObject.string("status"))
        f.execute(seek)
        assertEquals("stale", f.socket.report().getValue("settlements").jsonArray.single().jsonObject.string("status"))
        f.delta(2, AccountDelta(cursor = sameRowNewVisit))
        f.result(next, "stale", 2)
        f.result(seek, "stale", 2)
        assertEquals(listOf(0.0, 0.0), f.audio.seeks)
        assertEquals(sameRowNewVisit.occurrence, f.view.snapshot?.state?.occurrence?.eventId)
    }

    @Test fun seekExecuteKeepsVisitAndBoundsPositionWhileFencingOldEofAndDuplicateEcho() = runTest {
        val f = Fixture(this)
        f.connect(); f.snapshot()
        val outgoing = f.audio.facts
        f.channel.seek(52.0); val seek = f.socket.commands().single()
        f.execute(seek)
        val applied = f.socket.sent.flatMap { it["settlements"]?.jsonArray.orEmpty() }.single().jsonObject
        val sought = playbackJson.decodeFromJsonElement<AccountCursor>(applied.getValue("cursor"))
        assertEquals(first.copy(positionMs = 48_000), sought)
        val facts = outgoing.copy(ended = true, isPlaying = false, elapsed = 48.0)
        f.audio.observations.tryEmit(AudioObservation(requireNotNull(facts.binding), facts)); runCurrent()
        f.execute(seek)
        f.delta(2, AccountDelta(cursor = sought), seek)
        assertEquals(listOf(0.0, 48.0), f.audio.seeks)
        assertTrue(f.socket.sent.none { it.containsKey("advance") })
        assertEquals(first.occurrence, f.view.snapshot?.state?.occurrence?.eventId)
    }

    @Test fun seekBeyondFractionalDurationRoundsToServerMillisecondAndExecutesOnce() = runTest {
        val f = Fixture(this)
        val short = rows.first().copy(duration = 1.001)
        f.connect(); f.snapshot(AccountState(listOf(short), first, "off", false))
        f.channel.seek(5.0); val seek = f.socket.commands().single()
        f.execute(seek)
        val applied = f.socket.sent.flatMap { it["settlements"]?.jsonArray.orEmpty() }.single().jsonObject
        val sought = playbackJson.decodeFromJsonElement<AccountCursor>(applied.getValue("cursor"))
        assertEquals(1_001L, sought.positionMs)
        assertEquals(1_001L, playbackPositionMs(f.audio.seeks.last())) // Also used by Media3.
        f.delta(2, AccountDelta(cursor = sought), seek)
        f.result(seek, "applied", 2)
        assertEquals(listOf(0.0, 1.001), f.audio.seeks)
    }

    @Test fun eofSeenBeforeSeekDispatchIsReportedBeforeStaleWithoutReloadingEndedRecording() = runTest {
        val f = Fixture(this)
        f.connect(); f.snapshot()
        f.channel.seek(8.0); val seek = f.socket.commands().single()
        f.audio.eof(42.0, signal = false)
        f.execute(seek)
        val packets = f.socket.sent.filter { it.kind == "report" }
        val ended = packets.indexOfFirst { it.containsKey("advance") }
        val stale = packets.indexOfFirst { it["settlements"]?.jsonArray?.any { row -> row.jsonObject.string("id") == seek.id } == true }
        assertTrue(ended >= 0 && ended < stale)
        val advance = packets[ended].getValue("advance").jsonObject
        val actual = first.copy(key = 2, occurrence = advance.string("toOccurrence"))
        f.delta(2, AccountDelta(cursor = actual))
        f.result(seek, "stale", 2)
        assertEquals(listOf(0.0, 0.0), f.audio.seeks)
        assertEquals(2L, f.view.current?.key)
        assertNull(f.view.error)
    }

    @Test fun handoffPreservesAcceptedSelectionProjectionAndRoutesLateResolutionToNewOwner() = runTest {
        val f = Fixture(this)
        f.connect(); f.snapshot(owner = "other")
        f.channel.playTrack(PlaybackTrackReference("new"))
        val play = f.socket.commands().single()
        f.result(play, "accepted", 1)
        f.snapshot(generation = 2)
        assertEquals("new", f.view.current?.id)
        assertEquals(1, f.audio.starts.size) // Handoff begins with the latest confirmed state.
        val catalog = rows.first().copy(id = "new", key = 3)
        f.delta(2, AccountDelta(queue = AccountQueueEdit("replace", rows = listOf(catalog)),
            cursor = AccountCursor(3, "new-visit", 0, true)), play, generation = 2)
        assertEquals(2, f.audio.starts.size)
        assertEquals("new", f.audio.starts.last().trackId)
        assertEquals(1, f.socket.commands().size) // No replay on the new owner.
        f.result(play, "applied", 2)
        assertEquals(3L, f.view.current?.key)
    }

    @Test fun detachedEofReattachesWithOriginalSharedBasisAndDoesNotRestartSuccessor() = runTest {
        val f = Fixture(this)
        f.connect(); f.snapshot()
        f.disconnect()
        f.audio.eof(44.0); runCurrent()
        assertEquals(2, f.audio.starts.size)
        f.connect()
        val local = requireNotNull(f.claims.last())
        assertEquals(2L, local.cursor.key)
        assertEquals("first", f.reattach.last()?.string("occurrence"))
        assertEquals(1L, f.reattach.last()?.long("revision"))
        f.snapshot(local, revision = 2, connection = "socket-2")
        assertEquals(2, f.audio.starts.size)
        assertEquals(local.cursor.occurrence, f.view.snapshot?.state?.occurrence?.eventId)
        assertEquals(1L, f.socket.report().long("sequence"))
    }

    @Test fun rejectedReattachReconcilesChangedSharedPositionAndDoesNotReportDetachedProgress() = runTest {
        val f = Fixture(this)
        f.connect(); f.snapshot()
        f.audio.facts = f.audio.facts.copy(elapsed = 8.0)
        advanceTimeBy(251); runCurrent()
        f.disconnect()
        f.connect()
        assertEquals(8_000L, f.claims.last()?.cursor?.positionMs)
        f.snapshot(revision = 2, cursor = first.copy(positionMs = 3_000), connection = "socket-2")
        assertEquals(listOf(0.0, 3.0), f.audio.seeks)
        assertEquals(3_000L, f.socket.report().getValue("observation").jsonObject.long("positionMs"))
    }

    @Test fun detachedPauseAtMeasuredPositionIsRetainedWhenServerAdoptsIt() = runTest {
        val f = Fixture(this)
        f.connect(); f.snapshot()
        f.disconnect()
        f.audio.facts = f.audio.facts.copy(elapsed = 8.0)
        f.channel.pause(); runCurrent()
        f.connect()
        val local = requireNotNull(f.claims.last())
        assertFalse(local.cursor.desiredPlaying)
        assertEquals(8_000L, local.cursor.positionMs)
        f.snapshot(local, revision = 2, connection = "socket-2")
        assertEquals(listOf(0.0), f.audio.seeks)
        assertEquals("paused", f.socket.report().getValue("observation").jsonObject.string("status"))
    }

    @Test fun pauseBetweenHelloAndWinningSnapshotReconcilesWithoutStartingAudioAgain() = runTest {
        val f = Fixture(this)
        f.channel.playTrack(PlaybackTrackReference("duplicate")); runCurrent()
        f.connect()
        val submitted = requireNotNull(f.claims.last())
        f.audio.facts = f.audio.facts.copy(elapsed = 9.0)
        f.channel.pause(); runCurrent()
        f.snapshot(submitted)
        assertEquals(1, f.audio.starts.size)
        assertFalse(f.audio.facts.isPlaying)
        assertFalse(f.view.desiredPlaying)
        val sent = f.socket.sent.single { it.kind == "reconcileClaim" }
        assertEquals("socket-1:1", sent.string("id"))
        assertEquals(setOf("type", "id", "basis", "cursor"), sent.keys)
        val basis = sent.getValue("basis").jsonObject
        assertEquals(setOf("sessionId", "generation", "revision", "occurrence"), basis.keys)
        assertEquals(1L, basis.long("revision"))
        assertEquals(submitted.cursor.occurrence, basis.string("occurrence"))
        assertTrue(f.socket.sent.none { it.kind == "report" })
        val paused = playbackJson.decodeFromJsonElement<AccountCursor>(sent.getValue("cursor"))
        assertEquals(9_000L, paused.positionMs)
        f.snapshot(submitted.copy(cursor = paused), revision = 2)
        f.socket.frame("type" to "result", "id" to sent.string("id"), "status" to "applied", "revision" to 2L); runCurrent()
        assertEquals(1, f.audio.starts.size)
        assertFalse(f.view.isPlaying)
        assertEquals("paused", f.socket.report().getValue("observation").jsonObject.string("status"))
        assertEquals(2L, f.socket.report().long("revision"))
        f.channel.setRepeat("all")
        assertEquals("socket-1:2", f.socket.commands().single().id)
    }

    @Test fun pauseBetweenHelloAndLosingClaimNeverReachesLiveOwner() = runTest {
        val f = Fixture(this)
        f.channel.playTrack(PlaybackTrackReference("duplicate")); runCurrent()
        f.connect()
        f.channel.pause(); runCurrent()
        f.snapshot(owner = "other", observation = AccountObservation(1, "first", 15_000, WALL, "playing"))
        assertFalse(f.view.localOutput)
        assertTrue(f.view.desiredPlaying)
        assertTrue(f.view.isPlaying)
        assertFalse(f.audio.facts.isPlaying)
        assertEquals(1, f.audio.starts.size)
        assertTrue(f.socket.sent.none { it.kind in setOf("reconcileClaim", "command", "report") })
    }

    @Test fun pauseResumeReturningToHelloCursorDoesNotStartAnotherNativeOperation() = runTest {
        val f = Fixture(this)
        f.channel.playTrack(PlaybackTrackReference("duplicate")); runCurrent()
        f.connect()
        val submitted = requireNotNull(f.claims.last())
        f.channel.pause(); f.channel.resume(); runCurrent()
        val active = requireNotNull(f.audio.facts.binding)
        f.snapshot(submitted)
        assertEquals(2, f.audio.starts.size)
        assertEquals(active, f.audio.facts.binding)
        assertTrue(f.socket.sent.none { it.kind == "reconcileClaim" })
    }

    @Test fun queuedExecuteSettlesBeforeClaimCorrectionWithoutPublishingUnacceptedPosition() = runTest {
        val f = Fixture(this)
        f.channel.playTrack(PlaybackTrackReference("duplicate")); runCurrent()
        f.connect()
        val submitted = requireNotNull(f.claims.last())
        f.channel.seek(11.0); runCurrent()
        f.snapshot(submitted)
        val remote = AccountCommand("other:1", WALL, AccountBasis("room", 1, 1,
            submitted.cursor.occurrence, submitted.cursor.key, 2), fields("type" to "next"))
        f.execute(remote)
        assertEquals(listOf(0.0, 11.0, 0.0), f.audio.seeks)
        assertTrue(f.socket.sent.filter { it.kind == "report" }.all {
            it.getValue("observation").jsonObject.long("positionMs") != 11_000L
        })
        val applied = f.socket.sent.flatMap { it["settlements"]?.jsonArray.orEmpty() }.single().jsonObject
        assertEquals("applied", applied.string("status"))
        val cursor = playbackJson.decodeFromJsonElement<AccountCursor>(applied.getValue("cursor"))
        assertEquals(2L, cursor.key)
        f.delta(2, AccountDelta(cursor = cursor), remote)
        f.snapshot(submitted.copy(cursor = cursor), revision = 2)
        assertEquals(listOf(0.0, 11.0, 0.0), f.audio.seeks)
        assertEquals(2L, f.view.current?.key)
    }

    @Test fun offlineSelectionDoesNotBecomeAnOnlineClaim() = runTest {
        val f = Fixture(this, shareDetachedPlayback = false)
        assertTrue(f.channel.playTrack(PlaybackTrackReference(rows.first().id)))
        runCurrent()
        f.connect()
        assertNull(f.claims.last())
        assertNull(f.reattach.last())
    }

    @Test fun claimRepairAfterNextEchoDoesNotReloadNativeSuccessorAlreadyStartedByEof() = runTest {
        val f = Fixture(this)
        f.channel.playTrack(PlaybackTrackReference("duplicate")); runCurrent()
        f.connect()
        val submitted = requireNotNull(f.claims.last())
        f.channel.seek(11.0); runCurrent()
        f.snapshot(submitted)
        val remote = AccountCommand("other:1", WALL, AccountBasis("room", 1, 1,
            submitted.cursor.occurrence, submitted.cursor.key, 2), fields("type" to "next"))
        f.execute(remote)
        val cursor = playbackJson.decodeFromJsonElement<AccountCursor>(
            f.socket.report().getValue("settlements").jsonArray.single().jsonObject.getValue("cursor"))
        f.audio.eof(42.0); runCurrent() // B stops at the tail before its Next echo arrives.
        assertTrue(f.socket.sent.none { it.containsKey("advance") })
        f.delta(2, AccountDelta(cursor = cursor), remote)
        f.snapshot(submitted.copy(cursor = cursor), revision = 2) // Claim repair immediately follows echo.
        f.delta(3, AccountDelta(cursor = cursor.copy(desiredPlaying = false)))
        assertEquals(listOf(0.0, 11.0, 0.0), f.audio.seeks)
        assertFalse(f.view.isPlaying)
        assertEquals(1, f.socket.sent.count { it.containsKey("advance") })
    }

    @Test fun failureAfterHelloDoesNotAutomaticallyRetryTheGrantedVisit() = runTest {
        val f = Fixture(this)
        val gate = CompletableDeferred<Unit>()
        f.audio.readyGate = gate
        f.audio.refusal = true
        f.channel.playTrack(PlaybackTrackReference("duplicate")); runCurrent()
        f.connect()
        val submitted = requireNotNull(f.claims.last())
        gate.complete(Unit); runCurrent()
        f.snapshot(submitted)
        assertEquals(1, f.audio.starts.size)
        assertTrue(f.view.localOutput)
        assertFalse(f.view.isPlaying)
        assertEquals("failed", f.socket.report().getValue("observation").jsonObject.string("status"))
        assertTrue(f.socket.sent.none { it.kind == "reconcileClaim" })
    }

    @Test fun staleCursorReconciliationRevertsOnlyToCanonicalSnapshotWithoutRetry() = runTest {
        val f = Fixture(this)
        f.channel.playTrack(PlaybackTrackReference("duplicate")); runCurrent()
        f.connect()
        val submitted = requireNotNull(f.claims.last())
        f.channel.seek(11.0); runCurrent()
        f.snapshot(submitted)
        val sent = f.socket.sent.single { it.kind == "reconcileClaim" }
        assertEquals(listOf(0.0, 11.0), f.audio.seeks)
        f.snapshot(submitted) // A newer accepted command blocked reconcileClaim before it advanced revision.
        f.socket.frame("type" to "result", "id" to sent.string("id"), "status" to "stale", "revision" to 1L); runCurrent()
        assertEquals(listOf(0.0, 11.0, 0.0), f.audio.seeks)
        assertEquals(PlaybackConnectionState.READY, f.view.connection)
        assertEquals(1, f.socket.sent.count { it.kind == "reconcileClaim" })
    }

    @Test fun offerCannotStartAudioAndRefusedStartLeavesTargetSelected() = runTest {
        val f = Fixture(this)
        f.connect(); f.snapshot(owner = "other")
        f.socket.frame("type" to "offer", "id" to "transfer", "generation" to 1L); runCurrent()
        assertEquals("transfer", f.socket.sent.last { it.kind == "accept" }.string("id"))
        assertTrue(f.audio.starts.isEmpty())
        f.audio.refusal = true
        f.snapshot(generation = 2, revision = 2, cursor = first.copy(positionMs = 3_000))
        assertEquals("self", f.view.snapshot?.selectedDeviceId)
        assertTrue(f.view.localOutput)
        assertFalse(f.view.isPlaying)
        assertEquals("failed", f.socket.report().getValue("observation").jsonObject.string("status"))
        assertEquals(1, f.audio.starts.size)
    }

    @Test fun observationUsesClockOffsetBoundAndCannotOverrideSeekOrStaleGeneration() = runTest {
        val f = Fixture(this)
        f.wallShift = 60_000
        f.connect(); f.snapshot(owner = "other")
        val ping = f.socket.sent.last { it.kind == "ping" }
        advanceTimeBy(100)
        f.socket.frame("type" to "pong", "clientTimeMs" to ping.long("clientTimeMs"), "serverTimeMs" to WALL + 50); runCurrent()
        fun sample(revision: Long, generation: Long = 1) {
            f.socket.frame("type" to "observation", "generation" to generation, "revision" to revision,
                "observation" to encode(AccountObservation(1, "first", 47_000, WALL, "playing"))); runCurrent()
        }
        sample(1)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(48.0, f.view.playback?.elapsed)
        assertTrue(f.view.isPlaying)
        advanceTimeBy(9_000); runCurrent()
        assertFalse(f.view.isPlaying)
        f.channel.seek(12.0)
        assertFalse(f.view.isPlaying)
        f.delta(2, AccountDelta(cursor = first.copy(positionMs = 12_000)), f.socket.commands().last())
        sample(1)
        sample(2, generation = 0)
        assertEquals(12.0, f.view.playback?.elapsed)
        assertFalse(f.view.isPlaying)
    }

    @Test fun malformedFirstSnapshotIsNotCountedAsSuccessfulBinding() = runTest {
        val f = Fixture(this)
        f.connect()
        f.socket.frame("type" to "snapshot", "sessionId" to "room", "connectionId" to "socket-1", "revision" to 1L,
            "owner" to encode(AccountOwner(1, "self")), "devices" to encode(emptyList<AccountDevice>()),
            "player" to encode(AccountState(rows, first, "off", false))) // No required serverTimeMs.
        runCurrent()
        assertEquals(0L, f.view.established)
        assertEquals(PlaybackConnectionState.DISCONNECTED, f.view.connection)
        assertTrue(f.audio.starts.isEmpty())
    }

    @Test fun snapshotWithLegacyCursorCannotEstablishPlaybackBinding() = runTest {
        val f = Fixture(this)
        f.connect()
        val legacyCursor = fields("key" to 1L, "chain" to "old", "visit" to 1L, "run" to 1L,
            "elapsed" to 0.0, "desired" to true, "playing" to true)
        val player = JsonObject(encode(AccountState(rows, first, "off", false)).jsonObject + ("cursor" to legacyCursor))
        f.socket.frame("type" to "snapshot", "sessionId" to "room", "connectionId" to "socket-1", "revision" to 1L,
            "owner" to encode(AccountOwner(1, "self")), "devices" to encode(emptyList<AccountDevice>()),
            "player" to player, "serverTimeMs" to WALL)
        runCurrent()
        assertFalse(f.socket.isOpen)
        assertEquals(0L, f.view.established)
        assertEquals(PlaybackConnectionState.DISCONNECTED, f.view.connection)
        assertTrue(f.socket.sent.isEmpty())
        assertTrue(f.audio.starts.isEmpty())
    }

    @Test fun socketAndFirstSnapshotShareOneAttemptDeadline() = runTest {
        val f = Fixture(this)
        f.upgradeDelay = 7_500
        f.connect()
        advanceTimeBy(9_999); runCurrent()
        assertEquals(PlaybackConnectionState.CONNECTING, f.view.connection)
        assertTrue(f.socket.isOpen)
        advanceTimeBy(1); runCurrent()
        assertFalse(f.socket.isOpen)
        assertEquals(PlaybackConnectionState.DISCONNECTED, f.view.connection)
        assertEquals(0L, f.view.established)
        assertTrue(f.view.offline)
    }

    @Test fun supersededOwnerStopsAndDoesNotReopenOrStartLocalAudio() = runTest {
        val f = Fixture(this)
        f.connect(); f.snapshot()
        f.socket.replaced = true
        f.disconnect()
        assertTrue(f.view.superseded)
        assertFalse(f.view.localOutput)
        assertFalse(f.audio.facts.isPlaying)
        f.connect()
        assertEquals(1, f.claims.size)
        assertFalse(f.channel.playTrack(PlaybackTrackReference("duplicate")))
    }

    private companion object {
        const val WALL = 1_700_000_000_000L
        val rows = (1L..2L).map { PlaybackTrack("duplicate", "Same", emptyList(), "", "", 48.0, it, it, "low") }
        val first = AccountCursor(1, "first", 0, true)
        inline fun <reified T> encode(value: T) = playbackJson.encodeToJsonElement(value)
        fun commandWire(value: AccountCommand) = JsonObject(encode(value).jsonObject + fields("type" to "command"))
    }
}
