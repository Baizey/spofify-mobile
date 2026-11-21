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
            assertTrue(h.controller.selectDevice("remote"))
            assertTrue(h.controller.pause())
            assertTrue(h.controller.resume())
            assertTrue(h.controller.playTrack(PlaybackTrackReference("track")))
            assertEquals(4, h.transport.sockets.single().commands().size)
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
            assertNotNull(h.controller.state.value.snapshot) // Keep the last projection on screen.
            val fresh = h.transport.sockets.last()
            assertTrue(fresh.sent.isEmpty())
            assertFalse(h.controller.pause()) // No musical admission before its first snapshot.
            h.snapshot()
            assertEquals("connection-2", h.controller.state.value.connectionId)
            assertEquals(PlaybackConnectionState.READY, h.controller.state.value.connection)
            repeat(10) { h.reconnect.expedite() }
            runCurrent()
            assertEquals(2, h.transport.sockets.size)
            assertTrue(fresh.commands().isEmpty())
            assertEquals(0, h.audio.executions)
            assertTrue(h.controller.queueTrack(PlaybackTrackReference("track")))
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
                assertTrue(h.transport.sockets.single().commands().isEmpty())
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
            h.reconnect.expedite()
            runCurrent()
            assertTrue(h.transport.sockets.single().commands().isEmpty())
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
            val socket = h.transport.sockets.single()
            val beforeRefusal = socket.sent.size
            var starts = 0
            handlePlaybackServiceLoss(h.controller.state.value, h.audio.facts(), true, h.audio.hasAuthority,
                restartLocalService = { starts++; promotionRefused }, pauseNative = h.audio::pause, reportLocalBlocked = h.audio::blocked)
            if (promotionRefused) {
                assertTrue(h.audio.actual.isPlaying)
                handlePlaybackServiceLoss(h.controller.state.value, h.audio.facts(), true, h.audio.hasAuthority,
                    restartLocalService = { false }, pauseNative = h.audio::pause, reportLocalBlocked = h.audio::blocked)
            }
            runCurrent()
            assertEquals(1, starts)
            assertEquals(paused + 1, h.audio.pauses)
            assertFalse(h.audio.actual.isPlaying)
            assertEquals(1, h.audio.blockedCount)
            assertEquals(1, h.audio.executions)
            assertTrue(socket.commands().isEmpty())
            val fact = socket.sent.drop(beforeRefusal).single()
            assertEquals("fact", fact.getValue("type").jsonPrimitive.content)
            assertEquals("blocked", fact.getValue("error").jsonPrimitive.content)
            assertFalse(fact.getValue("playing").jsonPrimitive.boolean)
            assertFalse(fact.getValue("ended").jsonPrimitive.boolean)
            assertEquals(1L, fact.getValue("key").jsonPrimitive.long)
            assertEquals("visit", fact.getValue("chain").jsonPrimitive.content)
            assertEquals(0L, fact.getValue("visit").jsonPrimitive.long)
            assertEquals(0L, fact.getValue("run").jsonPrimitive.long)
            assertFalse(h.controller.state.value.isPlaying)
            advanceTimeBy(250); runCurrent() // Retired binding must not re-report on a sampler tick.
            assertEquals(beforeRefusal + 1, socket.sent.size)
            assertFalse(h.audio.hasAuthority)
            assertFalse(h.audio.facts().isPlaying)
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

    @Test fun failedLocalStartupCannotRetainOrReattachServiceOnStaleDesiredIntent() {
        val local = view(local = true)
        assertTrue(local.desiredPlaying)
        for (failure in listOf("blocked", "media")) {
            val failed = AudioFacts(error = failure)
            assertFalse(retainLocalPlaybackService(local, failed))
            assertTrue(shouldStopPlaybackService(local, failed))
            handlePlaybackServiceLoss(local, failed, false, true,
                restartLocalService = { error("Failed startup must not reattach") },
                pauseNative = { error("Quiescent failed startup has no native work to pause") },
                reportLocalBlocked = { error("Cleanup must not manufacture another failure") })
        }
    }

    @Test fun transientFocusAndRemainingNativeWorkRetainServiceEvenWithAnError() {
        val local = view(local = true)
        for (error in listOf("", "blocked", "media")) {
            val pendingFocus = AudioFacts(error = error)
            if (error != "media") {
                assertTrue(retainLocalPlaybackService(local, pendingFocus, localPlayWhenReady = true))
                assertFalse(shouldStopPlaybackService(local, pendingFocus, localPlayWhenReady = true))
            }
            for (active in listOf(AudioFacts(error = error, isPlaying = true),
                    AudioFacts(error = error, isLoading = true), AudioFacts(error = error, isBuffering = true))) {
                assertTrue(retainLocalPlaybackService(local, active))
                assertFalse(shouldStopPlaybackService(local, active))
            }
        }
    }

    @Test fun terminalMediaFailureCannotKeepAnUnpromotableServiceOnLatchedPlayIntent() {
        val local = view(local = true)
        val failed = AudioFacts(error = "media")
        assertFalse(retainLocalPlaybackService(local, failed, localPlayWhenReady = true))
        assertTrue(shouldStopPlaybackService(local, failed, localPlayWhenReady = true))
    }

    @Test fun preparingTargetIsRetainedUnlessNativeWorkHasActuallyFailed() {
        val remote = view(local = false)
        val target = remote.copy(snapshot = remote.snapshot!!.copy(transfer = PlaybackTransfer(
            "remote", "phone", "remote-executor", "connection-1", "preparing")))
        assertTrue(target.isLocalTransferTarget())
        assertTrue(retainLocalPlaybackService(target, AudioFacts()))
        assertFalse(shouldStopPlaybackService(target, AudioFacts()))
        val failed = AudioFacts(error = "blocked")
        assertFalse(retainLocalPlaybackService(target, failed))
        assertTrue(shouldStopPlaybackService(target, failed))
        assertTrue(retainLocalPlaybackService(target, failed.copy(isLoading = true)))
        assertFalse(shouldStopPlaybackService(target, failed.copy(isLoading = true)))
    }

    @Test fun musicCacheRequiresLiveSelectedPlaybackAndYieldsToNativeLoading() {
        val local = view(local = true)
        val binding = AudioBinding("instruction", 0, "event", "track")
        val facts = AudioFacts(binding = binding, isPlaying = true)
        assertTrue(local.readyForMusicCache(facts, true))
        assertFalse(local.readyForMusicCache(facts, false))
        assertFalse(view(local = false).readyForMusicCache(facts, true))
        for (stale in listOf(binding.copy(eventId = "old"), binding.copy(trackId = "other"), binding.copy(outputVersion = 1)))
            assertFalse(local.readyForMusicCache(facts.copy(binding = stale), true))
        assertFalse(local.readyForMusicCache(facts.copy(isLoading = true), true))
        assertFalse(local.readyForMusicCache(facts.copy(isBuffering = true), true))
        assertFalse(local.readyForMusicCache(facts.copy(isPlaying = false), true))
        assertFalse(local.copy(connection = PlaybackConnectionState.DISCONNECTED).readyForMusicCache(facts, true))
    }

    @Test fun ordinaryPausedLocalServiceRemainsAttachedUntilTaskRemoval() {
        val local = view(local = true)
        val snapshot = requireNotNull(local.snapshot)
        val paused = local.copy(snapshot = snapshot.copy(state = snapshot.state.copy(desiredPlaying = false)))
        assertFalse(retainLocalPlaybackService(paused, AudioFacts()))
        assertFalse(shouldStopPlaybackService(paused, AudioFacts()))
        assertTrue(shouldStopPlaybackService(view(local = false), AudioFacts()))
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
        val controller = AccountChannel(transport, audio, test.backgroundScope, object : PlaybackClock {
            override fun monotonicMs() = test.testScheduler.currentTime
            override fun wallTimeMs() = 1_700_000_000_000L + test.testScheduler.currentTime
        })
        val reconnect = PlaybackReconnect(test.backgroundScope, controller.state, { current },
            connectFresh = { controller.connect("Phone", "phone") }, nowMs = { test.testScheduler.currentTime }, jitter = { it })
        fun start() { reconnect.start(); test.runCurrent() }
        suspend fun snapshot(local: Boolean = false) {
            transport.sockets.last().incoming.send(accountSnapshot("connection-${transport.sockets.size}", local))
            test.runCurrent()
        }
        suspend fun activateLocal() {
            transport.sockets.last().incoming.send("""{"type":"grant","handle":"phone-output","basis":${test.testScheduler.currentTime},"ms":8000}""")
            test.runCurrent()
            assertTrue(audio.actual.isPlaying)
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
        fun commands() = sent.filterNot { it.getValue("type").jsonPrimitive.content in setOf("presence", "fact", "release") }
        override fun finish() { isOpen = false; incoming.close() }
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
            actual = actual.copy(error = "blocked", stoppedByRefusal = true)
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
