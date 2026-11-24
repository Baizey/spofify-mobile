package dev.spofify.mobile.playback

import dev.spofify.mobile.core.playback.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackLifetimeTest {
    @Test fun fourFailedHandshakesBackOffThenProbeWithoutRestartingOnObserverStart() = runTest {
        val views = MutableStateFlow(PlaybackView(localOutput = true, offline = true))
        val attempts = mutableListOf<Long>()
        var fallback = 0
        val loop = PlaybackReconnect(backgroundScope, views, { true }, connectFresh = {
            backgroundScope.launch {
                attempts += testScheduler.currentTime
                views.value = views.value.copy(connection = PlaybackConnectionState.CONNECTING)
                delay(100)
                views.value = views.value.copy(connection = PlaybackConnectionState.DISCONNECTED)
            }
        }, onExhausted = { current -> if (current()) fallback++ })
        loop.start()
        advanceTimeBy(7_400); runCurrent()
        assertEquals(listOf(0L, 1_100L, 3_200L, 7_300L), attempts)
        assertEquals(1, fallback)
        loop.start() // A foreground/observer callback cannot reset the budget.
        advanceTimeBy(29_999); runCurrent()
        assertEquals(4, attempts.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(37_400L, attempts.last())
        assertEquals(1, fallback)
        loop.start(explicit = true)
        runCurrent()
        assertEquals(listOf(37_400L, 37_400L), attempts.takeLast(2))
        loop.close()
    }

    @Test fun establishedMarkerResetsTheBudgetEvenWhenReadyAndLossCoalesce() = runTest {
        val views = MutableStateFlow(PlaybackView(localOutput = true, offline = true))
        var attempts = 0
        var fallback = 0
        val loop = PlaybackReconnect(backgroundScope, views, { true }, connectFresh = {
            backgroundScope.launch {
                val successful = ++attempts == 3
                views.value = views.value.copy(connection = PlaybackConnectionState.CONNECTING)
                if (successful) views.value = views.value.copy(connection = PlaybackConnectionState.READY, established = 1)
                views.value = views.value.copy(connection = PlaybackConnectionState.DISCONNECTED)
            }
        }, onExhausted = { if (it()) fallback++ })
        loop.start()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(7, attempts)
        assertEquals(1, fallback)
        loop.close()
    }

    @Test fun establishedLossUsesFourNewAttemptsWithoutResendingOnlineActions() = runTest {
        Harness(this).use { h ->
            h.start()
            h.snapshot()
            assertTrue(h.controller.selectDevice("remote"))
            assertTrue(h.controller.pause())
            assertTrue(h.controller.resume())
            assertTrue(h.controller.playTrack(PlaybackTrackReference("track")))
            assertEquals(4, h.transport.sockets.single().commands().size)
            h.transport.sockets.single().finish()
            runCurrent()
            assertEquals(PlaybackConnectionState.CONNECTING, h.controller.state.value.connection)
            assertTrue(h.controller.state.value.offline)
            repeat(4) { index ->
                assertTrue(h.transport.sockets.last().commands().isEmpty())
                h.transport.sockets.last().finish()
                runCurrent()
                if (index < 3) { advanceTimeBy(1_000L shl index); runCurrent() }
            }
            assertEquals(5, h.transport.sockets.size)
            assertEquals(1, h.fallback)
            assertEquals(PlaybackConnectionState.DISCONNECTED, h.controller.state.value.connection)
            assertEquals(0, h.audio.executions)
        }
    }

    @Test fun sameDeviceRebindAndManualOfflineKeepThePreparedOperationAndService() = runTest {
        Harness(this).use { h ->
            h.start()
            h.snapshot(local = true)
            val first = h.audio.facts().binding
            val pauses = h.audio.pauses
            assertTrue(h.audio.hasAuthority)
            assertTrue(h.audio.actual.isPlaying)
            h.transport.sockets.single().finish()
            runCurrent()
            val isolated = h.controller.state.value
            assertTrue(isolated.offline)
            assertTrue(isolated.localOutput)
            assertTrue(retainLocalPlaybackService(isolated, h.audio.facts(), true))
            assertFalse(shouldStopPlaybackService(isolated, h.audio.facts(), true))
            h.snapshot(local = true)
            assertFalse(h.controller.state.value.offline)
            assertEquals(1, h.audio.executions)
            assertEquals(first, h.audio.facts().binding)
            assertTrue(h.audio.hasAuthority)
            h.manualOffline()
            assertFalse(h.transport.sockets.last().isOpen)
            assertTrue(h.controller.state.value.offline)
            assertTrue(h.controller.state.value.localOutput)
            assertEquals(first, h.audio.facts().binding)
            assertEquals(pauses, h.audio.pauses)
            assertTrue(h.audio.hasAuthority)
            assertTrue(h.audio.actual.isPlaying)
            advanceTimeBy(60_000); runCurrent()
            assertEquals(2, h.transport.sockets.size)
            assertEquals(0, h.fallback)
        }
    }

    @Test fun startupCanPlayAndSeekFromCacheBeforeTheFirstSnapshot() = runTest {
        Harness(this).use { h ->
            h.start()
            val start = PlayerPresentation(h.controller.state.value)
            assertTrue(start.ready)
            assertFalse(start.onlineReady)
            assertTrue(h.controller.playTrack(PlaybackTrackReference("track")))
            runCurrent()
            assertTrue(h.audio.actual.isPlaying)
            assertEquals(1, h.cacheChecks)
            assertTrue(h.controller.seek(12.0))
            runCurrent()
            assertEquals(2, h.cacheChecks)
            assertEquals(12.0, h.audio.actual.elapsed, 0.0)
            assertTrue(h.transport.sockets.single().commands().isEmpty())
            h.snapshot(local = false)
            assertTrue(h.controller.state.value.isolatedOutput)
            assertTrue(h.audio.actual.isPlaying)
            assertEquals(12.0, h.audio.actual.elapsed, 0.0)
            assertTrue(h.controller.state.value.isPlaying)
        }
    }

    @Test fun manualOfflineOnFollowerNeverClaimsRemoteAudioOrSendsPause() = runTest {
        Harness(this).use { h ->
            h.start()
            h.snapshot()
            assertTrue(h.controller.state.value.isPlaying)
            assertFalse(h.controller.state.value.localOutput)
            h.manualOffline()
            assertTrue(h.controller.state.value.offline)
            assertFalse(h.controller.state.value.isPlaying)
            assertFalse(h.controller.state.value.desiredPlaying)
            assertEquals("track", h.controller.state.value.current?.id)
            assertEquals(0, h.audio.executions)
            assertTrue(h.transport.sockets.single().commands().isEmpty())
        }
    }

    @Test fun isolatedSoundSurvivesConflictingSnapshotButStopsOnSupersession() = runTest {
        for (superseded in listOf(false, true)) Harness(this).use { h ->
            h.start()
            h.snapshot(local = true)
            assertTrue(h.audio.hasAuthority)
            if (superseded) {
                h.transport.sockets.single().replace()
                runCurrent()
                advanceTimeBy(60_000); runCurrent()
                assertEquals(1, h.transport.sockets.size)
                assertTrue(h.controller.state.value.superseded)
                assertFalse(h.controller.state.value.offline)
            } else {
                h.transport.sockets.single().finish()
                runCurrent()
                assertTrue(h.audio.actual.isPlaying)
                h.snapshot(local = false, generation = 2)
                assertTrue(h.controller.state.value.isolatedOutput)
            }
            assertEquals(!superseded, h.controller.state.value.localOutput)
            assertEquals(!superseded, h.audio.actual.isPlaying)
            assertEquals(!superseded, h.audio.hasAuthority)
            assertEquals(superseded, shouldStopPlaybackService(h.controller.state.value, h.audio.facts()))
            assertEquals(0, h.fallback)
        }
    }

    @Test fun transientHttpMaintenanceDoesNotDisconnectAHealthyController() = runTest {
        Harness(this).use { h ->
            h.start()
            h.snapshot()
            var checks = 0
            var failures = 0
            val maintenance = PlaybackSessionMaintenance(backgroundScope, { true },
                isConnected = { h.controller.state.value.connection == PlaybackConnectionState.READY },
                renew = { checks++; throw IOException() }, onFailure = { failures++ })
            try {
                maintenance.start()
                runCurrent()
                advanceTimeBy(60_000); runCurrent()
                assertEquals(1, checks)
                assertEquals(1, failures)
                assertEquals(PlaybackConnectionState.READY, h.controller.state.value.connection)
                assertTrue(h.transport.sockets.single().isOpen)
                assertTrue(h.transport.sockets.single().commands().isEmpty())
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
        assertEquals(1, checks)
        current = false
        maintenance.close()
        gate.completeExceptionally(IOException())
        runCurrent()
        current = true
        maintenance.refreshNow()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, checks)
        assertEquals(0, failures)
    }

    @Test fun foregroundServiceRecoveryWorksOnlineAndWhileIsolatedWithoutRestartingAudio() = runTest {
        for (isolated in listOf(false, true)) Harness(this).use { h ->
            h.start()
            h.snapshot(local = true)
            if (isolated) h.manualOffline()
            val paused = h.audio.pauses
            val socket = h.transport.sockets.single()
            var starts = 0
            val view = h.controller.state.value
            handlePlaybackServiceLoss(view, h.audio.facts(), true, h.audio.hasAuthority,
                restartLocalService = { starts++; true }, pauseNative = h.audio::pause, reportLocalBlocked = h.audio::blocked)
            assertEquals(1, starts)
            assertEquals(paused, h.audio.pauses)
            assertTrue(h.audio.hasAuthority)
            assertTrue(h.audio.actual.isPlaying)
            assertEquals(1, h.audio.executions)
            handlePlaybackServiceLoss(view, h.audio.facts(), true, h.audio.hasAuthority,
                restartLocalService = { false }, pauseNative = h.audio::pause, reportLocalBlocked = h.audio::blocked)
            runCurrent()
            assertEquals(paused + 1, h.audio.pauses)
            assertFalse(h.audio.actual.isPlaying)
            assertFalse(h.controller.state.value.isPlaying)
            assertFalse(h.audio.hasAuthority)
            assertEquals(1, h.audio.executions)
            assertTrue(socket.commands().isEmpty())
            val failed = socket.sent.any { it["observation"]?.jsonObject?.get("status")?.jsonPrimitive?.content == "failed" }
            assertEquals(!isolated, failed)
        }
    }

    @Test fun serviceCleanupCannotControlRemoteOutputOrTrustAnInvalidNativeOperation() = runTest {
        Harness(this).use { h ->
            h.start()
            h.snapshot()
            val view = h.controller.state.value
            assertTrue(view.isPlaying)
            assertFalse(retainLocalPlaybackService(view, h.audio.facts()))
            handlePlaybackServiceLoss(view, h.audio.facts(), false, false,
                restartLocalService = { error("A follower must not start an FGS") },
                pauseNative = { error("No remote audio on the native deck") },
                reportLocalBlocked = { error("Do not report failures for remote sound") })
            assertTrue(h.transport.sockets.single().isOpen)
            assertTrue(h.transport.sockets.single().commands().isEmpty())
        }
        var pauses = 0
        handlePlaybackServiceLoss(view(local = true), AudioFacts(isPlaying = true), true, false,
            restartLocalService = { error("An invalid native operation must not reattach") },
            pauseNative = { pauses++ }, reportLocalBlocked = { error("No owned operation to report") })
        assertEquals(1, pauses)
    }

    @Test fun endedPlayWhenReadyCannotRestartAService() {
        val local = view(local = true)
        val completed = local.withIntent(false)
        val ended = AudioFacts(ended = true)
        assertFalse(retainLocalPlaybackService(completed, ended, true))
        assertTrue(retainLocalPlaybackService(local, ended, true)) // between-track intent can keep the existing attachment.
        for (view in listOf(local, completed)) handlePlaybackServiceLoss(view, ended, true, true,
            restartLocalService = { error("EOF has no pending sound") },
            pauseNative = { error("EOF has no pending sound") }, reportLocalBlocked = { error("EOF is not a refusal") })
    }

    @Test fun terminalFailureStopsEvenWhenLatchedIntentIsStillPlaying() {
        val local = view(local = true)
        assertTrue(local.desiredPlaying)
        for (error in listOf("blocked", "media")) {
            val failed = AudioFacts(error = error)
            assertFalse(retainLocalPlaybackService(local, failed))
            assertTrue(shouldStopPlaybackService(local, failed))
            for (active in listOf(failed.copy(isPlaying = true), failed.copy(isLoading = true), failed.copy(isBuffering = true))) {
                assertTrue(retainLocalPlaybackService(local, active))
                assertFalse(shouldStopPlaybackService(local, active))
            }
        }
        assertTrue(shouldStopPlaybackService(local, AudioFacts(error = "media"), true))
        assertFalse(shouldStopPlaybackService(local, AudioFacts(error = "blocked"), true))
    }

    @Test fun taskRemovalFollowsActualOutputNotSocketOrRosterAndPausedMayStayAttached() {
        val local = view(local = true).withIntent(false)
        for (v in listOf(local, local.copy(offline = true, connection = PlaybackConnectionState.CONNECTING))) {
            assertFalse(retainLocalPlaybackService(v, AudioFacts()))
            assertFalse(shouldStopPlaybackService(v, AudioFacts()))
            assertTrue(retainLocalPlaybackService(v, AudioFacts(), localPlayWhenReady = true))
            assertTrue(retainLocalPlaybackService(v, AudioFacts(isBuffering = true)))
            assertTrue(retainLocalPlaybackService(v, AudioFacts(isLoading = true)))
        }
        // Even a same-device row may be a superseded binding; its roster ID is not authority.
        val sibling = local.copy(localOutput = false, superseded = true)
        assertFalse(retainLocalPlaybackService(sibling, AudioFacts(isPlaying = true)))
        assertTrue(shouldStopPlaybackService(sibling, AudioFacts(isPlaying = true)))
    }

    @Test fun musicCacheRequiresLiveSelectedPlaybackAndYieldsToNativeLoading() {
        val local = view(local = true)
        val binding = AudioBinding("instruction", 1, "visit", "track")
        val facts = AudioFacts(binding = binding, isPlaying = true)
        assertTrue(local.readyForMusicCache(facts, true))
        assertFalse(local.readyForMusicCache(facts, false))
        assertFalse(view(local = false).readyForMusicCache(facts, true))
        // A retained, still authorized operation may predate the latest server generation.
        assertTrue(local.readyForMusicCache(facts.copy(binding = binding.copy(outputVersion = 0)), true))
        for (stale in listOf(binding.copy(eventId = "old"), binding.copy(trackId = "other")))
            assertFalse(local.readyForMusicCache(facts.copy(binding = stale), true))
        assertFalse(local.readyForMusicCache(facts.copy(isLoading = true), true))
        assertFalse(local.readyForMusicCache(facts.copy(isBuffering = true), true))
        assertFalse(local.readyForMusicCache(facts.copy(isPlaying = false), true))
        assertFalse(local.copy(offline = true, connection = PlaybackConnectionState.DISCONNECTED).readyForMusicCache(facts, true))
    }

    private class Harness(private val test: TestScope) : AutoCloseable {
        private var online = true
        val transport = FakeTransport()
        val audio = FakeAudio()
        var cacheChecks = 0
        private val library = object : LocalPlaybackLibrary {
            override fun resolve(track: PlaybackTrackReference?, context: PlaybackContext?) = LocalPlaybackRows(listOf(
                PlaybackTrack("track", "Song", emptyList(), "Album", "album", 120.0, 1, 1, "low")))
            override suspend fun hasAudio(track: PlaybackTrack, isAuthorized: () -> Boolean): Boolean {
                cacheChecks++
                return isAuthorized()
            }
        }
        val controller = AccountChannel(transport, audio, test.backgroundScope, object : PlaybackClock {
            override fun monotonicMs() = test.testScheduler.currentTime
            override fun wallTimeMs() = 1_700_000_000_000L + test.testScheduler.currentTime
        }, library = library)
        var fallback = 0
        val reconnect = PlaybackReconnect(test.backgroundScope, controller.state, { online },
            connectFresh = { controller.connect("Phone", "phone") }, onExhausted = { if (it()) fallback++ })
        fun start() { reconnect.start(); test.runCurrent() }
        fun manualOffline() {
            online = false
            reconnect.stop()
            controller.setOffline(manual = true)
            test.runCurrent()
        }
        suspend fun snapshot(local: Boolean = false, generation: Long = 1) {
            transport.sockets.last().incoming.send(accountSnapshot("connection-${transport.sockets.size}", local,
                nowMs = 1_700_000_000_000L + test.testScheduler.currentTime, generation = generation))
            test.runCurrent()
        }
        override fun close() { reconnect.close(); controller.close() }
    }

    private class FakeTransport : PlaybackTransport {
        val sockets = mutableListOf<FakeSocket>()
        override suspend fun connect(name: String, deviceId: String, local: JsonObject?, reattachBasis: JsonObject?): PlaybackConnection =
            FakeSocket().also { sockets += it }
    }

    private class FakeSocket : PlaybackConnection {
        val incoming = Channel<String>(Channel.UNLIMITED)
        override val messages = incoming.receiveAsFlow()
        override var isOpen = true
        override var superseded = false
        val sent = mutableListOf<JsonObject>()
        override fun send(text: String): Boolean {
            if (!isOpen) return false
            sent += Json.parseToJsonElement(text).jsonObject
            return true
        }
        fun commands() = sent.filter { it.getValue("type").jsonPrimitive.content == "command" }
        fun replace() { superseded = true; close() }
        override fun close() { isOpen = false; incoming.close() }
    }

    private class FakeAudio : AudioExecutor {
        val events = Channel<AudioObservation>(Channel.UNLIMITED)
        override val observations = events.receiveAsFlow()
        var actual = AudioFacts()
        var pauses = 0
        var executions = 0
        private var permit: (() -> Boolean)? = null
        val hasAuthority get() = permit?.invoke() == true
        override fun facts() = actual
        override fun pause() { pauses++; permit = null; actual = actual.copy(isPlaying = false) }
        override suspend fun prepare(binding: AudioBinding, track: PlaybackTrack?, elapsed: Double): AudioResult = AudioResult.OK
        override suspend fun execute(binding: AudioBinding, track: PlaybackTrack?, elapsed: Double, desiredPlaying: Boolean,
            isAuthorized: () -> Boolean): AudioResult {
            if (!isAuthorized()) return AudioResult.REJECTED
            executions++
            permit = isAuthorized
            actual = AudioFacts(binding, elapsed, track?.duration ?: 0.0, isPlaying = desiredPlaying)
            return AudioResult.OK
        }
        fun blocked() {
            pause()
            actual = actual.copy(error = "blocked", stoppedByRefusal = true)
            actual.binding?.let { events.trySend(AudioObservation(it, actual)) }
        }
    }

    companion object {
        private fun PlaybackView.withIntent(play: Boolean): PlaybackView {
            val snapshot = requireNotNull(snapshot)
            return copy(snapshot = snapshot.copy(state = snapshot.state.copy(desiredPlaying = play)))
        }
        private fun view(local: Boolean): PlaybackView {
            val track = PlaybackTrack("track", "Song", emptyList(), "Album", "album", 120.0, 1, 1, "low")
            val status = PlaybackStatus(1, 1, 1, "visit", !local, 0.0, 120.0, !local, false, false, "")
            val snapshot = PlaybackSnapshot("room", 1, 1, PlaybackQueueState(queue = listOf(track), position = 0,
                desiredPlaying = true, occurrence = PlaybackOccurrence(1, "track", "visit", 0.0, false)), status,
                listOf(PlaybackDevice("phone", "Phone", true, true), PlaybackDevice("remote", "Remote", true, true)),
                if (local) "phone" else "remote", "", null, 0)
            return PlaybackView(PlaybackConnectionState.READY, "room", "c1", "phone", snapshot, status, localOutput = local)
        }
    }
}
