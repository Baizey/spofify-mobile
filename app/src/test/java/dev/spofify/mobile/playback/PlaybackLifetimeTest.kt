package dev.spofify.mobile.playback

import dev.spofify.mobile.core.playback.*
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackLifetimeTest {
    @Test fun reconnectUsesFreshSnapshotsAndNeverReplaysPendingMusicalCommands() = runTest {
        Harness(this).use { h ->
            h.start()
            h.snapshot()
            assertNotNull(h.controller.selectDevice("remote"))
            assertNotNull(h.controller.pause())
            assertNotNull(h.controller.resume())
            assertNotNull(h.controller.playTrack(PlaybackTrackReference("track")))
            assertEquals(4, h.controller.state.value.pendingCommands)
            val old = h.transport.sockets.single()
            old.finish()
            runCurrent()
            assertEquals(PlaybackConnectionState.DISCONNECTED, h.controller.state.value.connection)
            advanceTimeBy(999)
            runCurrent()
            assertEquals(1, h.transport.sockets.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, h.transport.sockets.size)
            assertEquals(PlaybackConnectionState.CONNECTING, h.controller.state.value.connection)
            assertNull(h.controller.state.value.snapshot)
            assertEquals(0, h.controller.state.value.pendingCommands)
            val fresh = h.transport.sockets.last()
            assertTrue(fresh.sent.isEmpty())
            assertNull(h.controller.pause()) // No musical admission before its first snapshot.
            h.snapshot()
            assertEquals("connection-2", h.controller.state.value.connectionId)
            assertEquals(PlaybackConnectionState.READY, h.controller.state.value.connection)
            repeat(10) { h.reconnect.expedite() }
            runCurrent()
            assertEquals(2, h.transport.sockets.size)
            assertTrue(fresh.sent.isEmpty())
            assertEquals(0, h.audio.executions)
            assertEquals("1", h.controller.queueTrack(PlaybackTrackReference("track")))
            assertEquals(1, fresh.commands().size) // A new explicit action, never a replay.
        }
    }

    @Test fun reconnectBackoffIsExponentialCappedAndNotResetByUnstableReady() = runTest {
        val views = MutableStateFlow(PlaybackView())
        val attempts = mutableListOf<Long>()
        val loop = PlaybackReconnect(backgroundScope, views, { true },
            connectFresh = { attempts.add(testScheduler.currentTime); throw IOException() },
            nowMs = { testScheduler.currentTime }, jitter = { it })
        try {
            loop.start()
            runCurrent()
            advanceTimeBy(31_000)
            runCurrent()
            assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L, 15_000L, 31_000L), attempts)
            views.value = views.value.copy(connection = PlaybackConnectionState.READY)
            runCurrent()
            advanceTimeBy(100)
            views.value = views.value.copy(connection = PlaybackConnectionState.DISCONNECTED)
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(6, attempts.size) // A brief READY did not reset the 30-second backoff.
            views.value = views.value.copy(connection = PlaybackConnectionState.READY)
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()
            views.value = views.value.copy(connection = PlaybackConnectionState.DISCONNECTED)
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(63_100L, attempts.last()) // Stable READY reset the retry delay.
            loop.expedite()
            repeat(10) { loop.expedite() }
            runCurrent()
            assertEquals(7, attempts.size) // Expediting cannot bypass the minimum attempt gap.
        } finally { loop.close() }
    }

    @Test fun reconnectJitterCannotExceedThePaceBounds() = runTest {
        val attempts = mutableListOf<Long>()
        val bases = mutableListOf<Long>()
        val loop = PlaybackReconnect(backgroundScope, MutableStateFlow(PlaybackView()), { true },
            connectFresh = { attempts.add(testScheduler.currentTime); throw IOException() },
            nowMs = { testScheduler.currentTime }, jitter = { base ->
                bases.add(base)
                if (base == 1_000L) 0 else Long.MAX_VALUE
            })
        try {
            loop.start()
            runCurrent()
            advanceTimeBy(31_000)
            runCurrent()
            assertEquals(listOf(0L, 1_000L, 31_000L), attempts)
            assertEquals(listOf(1_000L, 2_000L, 4_000L), bases)
        } finally { loop.close() }
    }

    @Test fun foregroundDoesNotParallelizeConnectingAndRetirementCannotBeRevived() = runTest {
        val views = MutableStateFlow(PlaybackView())
        var current = true
        var attempts = 0
        val loop = PlaybackReconnect(backgroundScope, views, { current }, connectFresh = {
            attempts++
            views.value = views.value.copy(connection = PlaybackConnectionState.CONNECTING)
        }, nowMs = { testScheduler.currentTime }, jitter = { it })
        loop.expedite()
        repeat(10) { loop.expedite() }
        runCurrent()
        assertEquals(1, attempts)
        advanceTimeBy(10_000)
        repeat(10) { loop.expedite() }
        runCurrent()
        assertEquals(1, attempts)
        current = false
        loop.close()
        views.value = views.value.copy(connection = PlaybackConnectionState.READY)
        views.value = views.value.copy(connection = PlaybackConnectionState.DISCONNECTED)
        current = true // Even a later identity must get a new policy owner, not revive this one.
        loop.expedite()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, attempts)
    }

    @Test fun transientHttpMaintenanceDoesNotDisconnectAHealthyRemoteController() = runTest {
        Harness(this).use { h ->
            h.start()
            h.snapshot()
            var checks = 0
            var failures = 0
            val maintenance = PlaybackSessionMaintenance(backgroundScope, { h.current },
                isConnected = { h.controller.state.value.connection == PlaybackConnectionState.READY },
                renew = { checks++; throw IOException() }, onFailure = { failures++ })
            try {
                maintenance.start()
                runCurrent()
                advanceTimeBy(60_000)
                runCurrent()
                assertEquals(1, checks)
                assertEquals(1, failures)
                assertEquals(PlaybackConnectionState.READY, h.controller.state.value.connection)
                assertTrue(h.transport.sockets.single().isOpen)
                assertTrue(h.transport.sockets.single().sent.isEmpty())
                maintenance.refreshNow()
                h.reconnect.expedite()
                runCurrent()
                assertEquals(2, failures)
                assertEquals(1, h.transport.sockets.size)
                assertEquals(PlaybackConnectionState.READY, h.controller.state.value.connection)
            } finally { maintenance.close() }
        }
    }

    @Test fun maintenanceIsSingleFlightAndLateFailureCannotEscapeAccountRetirement() = runTest {
        var current = true
        var checks = 0
        var failures = 0
        val gate = CompletableDeferred<Unit>()
        val maintenance = PlaybackSessionMaintenance(backgroundScope, { current }, { true }, renew = {
            checks++
            withContext(NonCancellable) { gate.await() }
        }, onFailure = { failures++ })
        maintenance.start()
        maintenance.refreshNow()
        maintenance.refreshNow()
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, checks)
        current = false
        maintenance.close()
        gate.completeExceptionally(IOException())
        runCurrent()
        current = true
        maintenance.refreshNow()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, checks)
        assertEquals(0, failures)
    }

    @Test fun remoteServiceCleanupDoesNotPauseTheRemoteOutputOrResetItsSocket() = runTest {
        Harness(this).use { h ->
            h.start()
            h.snapshot()
            val view = h.controller.state.value
            assertTrue(view.isPlaying) // Server-observed sound belongs to the remote connection.
            assertFalse(view.isLocalSelectedConnection())
            assertFalse(retainLocalPlaybackService(view, h.audio.facts()))
            handlePlaybackServiceLoss(view, h.audio.facts(), false, false,
                restartLocalService = { error("Remote controller must not start FGS") },
                pauseNative = { error("Remote cleanup does not need a native operation") },
                reportLocalBlocked = { error("Remote output must not receive a local blocked report") })
            handlePlaybackServiceStartRefusal(view, true,
                pauseNative = { error("An unrelated service callback must be ignored") },
                reportLocalBlocked = { error("An unrelated service callback must be ignored") })
            h.reconnect.expedite()
            runCurrent()
            assertTrue(h.transport.sockets.single().sent.isEmpty())
            assertTrue(h.transport.sockets.single().isOpen)
            assertEquals(PlaybackConnectionState.READY, h.controller.state.value.connection)
            assertEquals(1, h.transport.sockets.size)
        }
    }

    @Test fun localServiceLossPreservesNativePlaybackWhenReattachmentIsAccepted() = runTest {
        Harness(this).use { h ->
            h.start()
            h.snapshot(local = true)
            h.activateLocal()
            val paused = h.audio.pauses
            val sent = h.transport.sockets.single().sent.size
            var starts = 0
            handlePlaybackServiceLoss(h.controller.state.value, h.audio.facts(), true, h.audio.hasAuthority,
                restartLocalService = { starts++; true }, pauseNative = h.audio::pause, reportLocalBlocked = h.audio::blocked)
            runCurrent()
            assertEquals(1, starts)
            assertEquals(paused, h.audio.pauses)
            assertTrue(h.audio.actual.isPlaying)
            assertTrue(h.audio.hasAuthority)
            assertEquals(1, h.audio.executions)
            assertEquals(sent, h.transport.sockets.single().sent.size)
            assertTrue(h.transport.sockets.single().commands().isEmpty())
        }
    }

    @Test fun actualRestartOrPromotionRefusalStopsOnlyNativeAudioAndReportsBlockedFacts() = runTest {
        for (promotionRefused in listOf(false, true)) Harness(this).use { h ->
            h.start()
            h.snapshot(local = true)
            h.activateLocal()
            val paused = h.audio.pauses
            var starts = 0
            handlePlaybackServiceLoss(h.controller.state.value, h.audio.facts(), true, h.audio.hasAuthority,
                restartLocalService = { starts++; promotionRefused }, pauseNative = h.audio::pause, reportLocalBlocked = h.audio::blocked)
            if (promotionRefused) {
                assertTrue(h.audio.actual.isPlaying)
                handlePlaybackServiceStartRefusal(h.controller.state.value, h.audio.hasAuthority,
                    pauseNative = h.audio::pause, reportLocalBlocked = h.audio::blocked)
            }
            runCurrent()
            assertEquals(1, starts)
            assertEquals(paused + 1, h.audio.pauses)
            assertFalse(h.audio.actual.isPlaying)
            assertEquals(1, h.audio.blockedCount)
            assertEquals(1, h.audio.executions)
            val socket = h.transport.sockets.single()
            assertTrue(socket.commands().isEmpty())
            val facts = socket.sent.last { it["type"]!!.jsonPrimitive.content == "report" }
            assertEquals("blocked", facts["error"]!!.jsonPrimitive.content)
            assertFalse(facts["isPlaying"]!!.jsonPrimitive.boolean)
            assertEquals(PlaybackConnectionState.READY, h.controller.state.value.connection)
        }
    }

    @Test fun serviceLossCannotRestartAnExpiredLocalLeaseOrReplayAfterLogout() = runTest {
        Harness(this).use { h ->
            h.start()
            h.snapshot(local = true)
            h.activateLocal()
            advanceTimeBy(8_000)
            runCurrent()
            assertFalse(h.audio.hasAuthority)
            handlePlaybackServiceLoss(h.controller.state.value, h.audio.facts(), false, false,
                restartLocalService = { error("Expired authority cannot restart playback") },
                pauseNative = h.audio::pause, reportLocalBlocked = h.audio::blocked)
            assertFalse(h.audio.actual.isPlaying)
            h.current = false
            h.reconnect.close()
            h.controller.close()
            h.reconnect.expedite()
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(PlaybackConnectionState.CLOSED, h.controller.state.value.connection)
            assertEquals(1, h.transport.sockets.size)
            assertTrue(h.transport.sockets.single().commands().isEmpty())
        }
    }

    @Test fun endedPlayWhenReadyWithoutDesiredPlaybackCannotRetainOrRestartService() {
        val original = view(local = true)
        val snapshot = requireNotNull(original.snapshot)
        val completed = original.copy(snapshot = snapshot.copy(state = snapshot.state.copy(desiredPlaying = false)))
        val ended = AudioFacts(ended = true)
        var starts = 0
        var pauses = 0
        var blocked = 0
        assertFalse(retainLocalPlaybackService(completed, ended, localPlayWhenReady = true))
        handlePlaybackServiceLoss(completed, ended, true, true,
            restartLocalService = { starts++; true }, pauseNative = { pauses++ }, reportLocalBlocked = { blocked++ })
        assertEquals(0, starts)
        assertEquals(0, pauses)
        assertEquals(0, blocked)
        // Genuine server intent retains the existing service between songs, but
        // an ended native item cannot itself launch another FGS. The next execute can.
        assertTrue(retainLocalPlaybackService(original, ended, localPlayWhenReady = true))
        handlePlaybackServiceLoss(original, ended, true, true,
            restartLocalService = { starts++; true }, pauseNative = { pauses++ }, reportLocalBlocked = { blocked++ })
        assertEquals(0, starts)
    }

    @Test fun taskRemovalRetainsLocalIntentBufferingPendingPlayAndTransferButNotRemoteFacts() {
        val local = view(local = true)
        val remote = view(local = false)
        assertTrue(retainLocalPlaybackService(local, AudioFacts(ended = true)))
        val localSnapshot = requireNotNull(local.snapshot)
        val paused = local.copy(snapshot = localSnapshot.copy(state = localSnapshot.state.copy(desiredPlaying = false)))
        assertFalse(retainLocalPlaybackService(paused, AudioFacts()))
        assertTrue(retainLocalPlaybackService(paused, AudioFacts(), localPlayWhenReady = true))
        assertTrue(retainLocalPlaybackService(paused, AudioFacts(isBuffering = true)))
        assertTrue(retainLocalPlaybackService(paused, AudioFacts(isLoading = true)))
        assertFalse(retainLocalPlaybackService(remote, AudioFacts()))
        val target = remote.copy(snapshot = remote.snapshot!!.copy(transfer = PlaybackTransfer(
            "remote", "phone", "remote-executor", "connection-1", "preparing")))
        assertTrue(retainLocalPlaybackService(target, AudioFacts()))
        assertFalse(target.isLocalSelectedConnection())
        assertTrue(target.isLocalTransferTarget())
        assertFalse(retainLocalPlaybackService(local.copy(connection = PlaybackConnectionState.DISCONNECTED), AudioFacts()))
        val sibling = local.copy(snapshot = local.snapshot!!.copy(selectedConnectionId = "other-phone-socket"))
        assertFalse(sibling.isLocalSelectedConnection())
        assertFalse(retainLocalPlaybackService(sibling, AudioFacts()))
    }

    private class Harness(private val test: TestScope) : AutoCloseable {
        var current = true
        val transport = FakeTransport()
        val audio = FakeAudio()
        val controller = PlaybackController(transport, audio, test.backgroundScope, object : PlaybackClock {
            override fun monotonicMs() = test.testScheduler.currentTime
            override fun wallTimeMs() = 1_700_000_000_000L + test.testScheduler.currentTime
        })
        val reconnect = PlaybackReconnect(test.backgroundScope, controller.state, { current },
            connectFresh = { controller.connect("Phone", "phone") }, nowMs = { test.testScheduler.currentTime }, jitter = { it })
        fun start() { reconnect.start(); test.runCurrent() }
        suspend fun snapshot(local: Boolean = false) {
            val index = transport.sockets.size
            val snapshot = snapshotValue(local, "connection-$index")
            transport.sockets.last().incoming.send(json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("type", "snapshot")
                put("sessionId", "session")
                put("connectionId", "connection-$index")
                put("deviceId", "phone")
                put("snapshot", json.encodeToJsonElement(snapshot))
            }))
            test.runCurrent()
        }
        suspend fun activateLocal() {
            instruction("prepare", "prepare", 0)
            instruction("activate", "activate", 1)
            assertTrue(audio.actual.isPlaying)
        }
        private suspend fun instruction(type: String, id: String, output: Long) {
            transport.sockets.last().incoming.send(buildJsonObject {
                put("type", "instruction")
                put("sessionId", "session")
                put("instruction", buildJsonObject {
                    put("id", id)
                    put("type", type)
                    put("outputVersion", output)
                    put("eventId", "event")
                    put("elapsed", 0.0)
                    put("desiredPlaying", true)
                    put("track", json.encodeToJsonElement(track))
                    if (type == "activate") put("lease", buildJsonObject {
                        put("clientSequence", 1)
                        put("durationMs", 8_000)
                    })
                })
            }.toString())
            test.runCurrent()
        }
        override fun close() { reconnect.close(); controller.close() }
    }

    private class FakeTransport : PlaybackTransport {
        val sockets = mutableListOf<FakeSocket>()
        override suspend fun connect(name: String, deviceId: String): PlaybackConnection = FakeSocket().also { sockets.add(it) }
    }

    private class FakeSocket : PlaybackConnection {
        val incoming = Channel<String>(Channel.UNLIMITED)
        override val messages = incoming.receiveAsFlow()
        override var isOpen = true
        val sent = mutableListOf<JsonObject>()
        override fun send(text: String): Boolean {
            if (!isOpen) return false
            sent.add(json.parseToJsonElement(text).jsonObject)
            return true
        }
        fun commands() = sent.filter { it["type"]!!.jsonPrimitive.content == "command" }
        fun finish() { isOpen = false; incoming.close() }
        override fun close() = finish()
    }

    private class FakeAudio : AudioExecutor {
        val events = Channel<AudioObservation>(Channel.UNLIMITED)
        override val observations = events.receiveAsFlow()
        var actual = AudioFacts()
        var pauses = 0
        var executions = 0
        var blockedCount = 0
        private var operationAlive = false
        private var permit: (() -> Boolean)? = null
        val hasAuthority get() = operationAlive && permit?.invoke() == true
        override fun facts() = actual
        override fun pause() { pauses++; operationAlive = false; actual = actual.copy(isPlaying = false) }
        override suspend fun prepare(binding: AudioBinding, track: PlaybackTrack?, elapsed: Double): AudioResult {
            actual = AudioFacts(binding, elapsed, track?.duration ?: 0.0)
            return AudioResult.OK
        }
        override suspend fun execute(binding: AudioBinding, track: PlaybackTrack?, elapsed: Double, desiredPlaying: Boolean,
            isAuthorized: () -> Boolean): AudioResult {
            executions++
            permit = isAuthorized
            operationAlive = true
            actual = AudioFacts(binding, elapsed, track?.duration ?: 0.0, isPlaying = desiredPlaying && isAuthorized())
            return AudioResult.OK
        }
        fun blocked() {
            pause()
            blockedCount++
            actual = actual.copy(error = "blocked")
            actual.binding?.let { events.trySend(AudioObservation(it, actual)) }
        }
    }

    companion object {
        private val json = Json { encodeDefaults = true }
        private val track = PlaybackTrack("track", "Song", emptyList(), "Album", "album", 10.0, 1, 1, "now")
        private fun snapshotValue(local: Boolean, connectionId: String = "connection-1") = PlaybackSnapshot(
            "session", 1, 0, PlaybackQueueState(queue = listOf(track), position = 0, desiredPlaying = true,
                occurrence = PlaybackOccurrence(1, "track", "event", 0.0, false)),
            PlaybackStatus(0, 1, 1, "event", !local, 0.0, 10.0, !local, false, false, ""),
            listOf(PlaybackDevice("phone", "Phone", true, true), PlaybackDevice("remote", "PC", true, true)),
            if (local) "phone" else "remote", if (local) connectionId else "remote-executor", null, 0)
        private fun view(local: Boolean) = PlaybackView(connection = PlaybackConnectionState.READY, sessionId = "session",
            connectionId = "connection-1", deviceId = "phone", snapshot = snapshotValue(local), playback = snapshotValue(local).playback)
    }
}
