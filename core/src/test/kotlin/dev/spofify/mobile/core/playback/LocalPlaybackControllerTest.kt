package dev.spofify.mobile.core.playback

import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.random.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalPlaybackControllerTest {
    @Test fun stopDuringLocalLookupDoesNotPrepareLateCompletion() = runTest {
        val h = Harness(this)
        h.library.hold = Gate()
        assertTrue(h.controller.playCollection(playlist))
        runCurrent()
        assertTrue(h.library.hold!!.waiting)
        assertTrue(h.controller.hasPendingStart)
        assertFalse(h.state.isLoading)
        assertFalse(h.state.isPlaying)
        h.controller.stop()
        assertFalse(h.controller.hasPendingStart)
        assertFalse(h.library.authorization!!())
        h.library.hold!!.release()
        runCurrent()
        assertTrue(h.audio.attempts.isEmpty())
        assertNull(h.state.current)
        assertFalse(h.state.isBuffering)
    }

    @Test fun stopDuringNativePreparationRevokesTheCallbackBeforePause() = runTest {
        val h = Harness(this)
        h.audio.hold = Gate()
        h.controller.playCollection(playlist)
        runCurrent()
        val pending = h.audio.attempts.single()
        assertTrue(pending.authorized())
        assertTrue(h.state.desiredPlaying)
        assertFalse(h.state.isPlaying)
        h.controller.stop()
        assertFalse(h.audio.authorizedWhenPaused.single())
        h.audio.hold!!.release()
        runCurrent()
        assertTrue(h.audio.starts.isEmpty())
        assertNull(h.state.current)
    }

    @Test fun pauseAndPausedSeekUseNativePositionWithoutStartingSound() = runTest {
        val h = Harness(this)
        h.controller.playTrack(PlaybackTrackReference("a"))
        runCurrent()
        assertTrue(h.state.isPlaying)
        val visit = requireNotNull(h.state.occurrence).eventId
        h.audio.actual = h.audio.actual.copy(elapsed = 6.25)
        advanceTimeBy(251)
        assertEquals(6.25, h.state.elapsed, 0.0)
        h.controller.pause()
        assertFalse(h.state.desiredPlaying)
        assertFalse(h.state.isPlaying)
        assertEquals(6.25, h.state.elapsed, 0.0)
        h.controller.seek(12.75)
        runCurrent()
        val seek = h.audio.attempts.last()
        assertFalse(seek.play)
        assertEquals(12.75, seek.elapsed, 0.0)
        assertEquals(12.75, h.state.elapsed, 0.0)
        assertFalse(h.state.isPlaying)
        h.controller.seek(Double.NaN)
        h.controller.resume()
        runCurrent()
        assertEquals(3, h.audio.attempts.size)
        assertEquals(12.75, h.audio.attempts.last().elapsed, 0.0)
        assertTrue(h.state.isPlaying)
        assertEquals(visit, h.state.occurrence?.eventId)
    }

    @Test fun finalNativeEofStopsAtZeroAndPlayReplaysTheExactRow() = runTest {
        val h = Harness(this)
        h.library.tracks = listOf(track("a", "first"), track("a", "second").copy(duration = 0.0))
        h.audio.nativeDuration = 0.0
        h.controller.playCollection(playlist)
        h.controller.jump(h.state.queue.last().key)
        runCurrent()
        val pauseCount = h.audio.authorizedWhenPaused.size
        val rows = h.state.queue
        val outgoing = h.audio.attempts.single()
        h.audio.end(outgoing.binding, elapsed = 42.0)
        runCurrent()
        assertEquals(rows, h.state.queue)
        assertEquals(rows.last(), h.state.current)
        assertFalse(h.state.desiredPlaying)
        assertFalse(h.state.isPlaying)
        assertEquals(0.0, h.state.elapsed, 0.0)
        assertFalse(outgoing.authorized())
        assertEquals(listOf(false), h.audio.authorizedWhenPaused.drop(pauseCount))
        assertEquals(1, h.audio.attempts.size)
        assertEquals(listOf("a"), h.library.probes)

        h.controller.toggle()
        runCurrent()
        val replay = h.audio.attempts.last()
        assertEquals(2, h.audio.attempts.size)
        assertEquals(0.0, replay.elapsed, 0.0)
        assertTrue(replay.play)
        assertNotEquals(outgoing.binding, replay.binding)
        assertEquals(rows.last(), h.state.current)
        h.audio.end(outgoing.binding, elapsed = 42.0)
        runCurrent()
        assertTrue(h.state.isPlaying)
        assertTrue(replay.authorized())
    }

    @Test fun pauseAndManualNextAtTailResumeMeasuredPositionBeyondInaccurateMetadata() = runTest {
        val h = Harness(this)
        h.audio.nativeDuration = 0.0
        h.controller.playTrack(PlaybackTrackReference("a"))
        runCurrent()
        for ((elapsed, stop) in listOf(30.0 to h.controller::pause, 34.5 to h.controller::next)) {
            h.audio.actual = h.audio.actual.copy(elapsed = elapsed)
            stop()
            assertFalse(h.state.desiredPlaying)
            assertEquals(elapsed, h.state.elapsed, 0.0)
            h.controller.toggle()
            runCurrent()
            assertEquals(elapsed, h.audio.attempts.last().elapsed, 0.0)
            assertTrue(h.state.isPlaying)
        }
        h.controller.pause()
        h.controller.seek(30.0)
        runCurrent()
        assertEquals(30.0, h.state.elapsed, 0.0)
        h.controller.toggle()
        runCurrent()
        assertEquals(30.0, h.audio.attempts.last().elapsed, 0.0)
    }

    @Test fun newSeekFencesPendingStartEvenForSameTrack() = runTest {
        val h = Harness(this)
        val pending = Gate().also { h.audio.hold = it }
        h.controller.playCollection(playlist)
        runCurrent()
        val old = h.audio.attempts.single()
        h.audio.hold = null
        h.controller.seek(4.0)
        runCurrent()
        val current = h.audio.attempts.last()
        assertNotEquals(old.binding, current.binding)
        assertFalse(old.authorized())
        pending.release()
        h.audio.end(old.binding)
        runCurrent()
        assertEquals(listOf(current.binding), h.audio.starts)
        assertTrue(h.state.isPlaying)
        assertEquals(4.0, h.state.elapsed, 0.0)
        assertEquals(0, h.state.position)
    }

    @Test fun explicitlySelectedMissingOccurrenceDoesNotSubstituteAnotherSong() = runTest {
        val h = Harness(this)
        h.controller.playCollection(playlist)
        runCurrent()
        h.library.missing += "b"
        h.controller.jump(h.state.queue.first { it.id == "b" }.key)
        runCurrent()
        assertEquals(listOf("a", "b"), h.library.probes)
        assertEquals("b", h.state.current?.id)
        assertEquals("unavailable", h.state.error)
        assertFalse(h.state.isPlaying)
        assertFalse(h.state.desiredPlaying)
        assertEquals(1, h.audio.attempts.size)
    }

    @Test fun unavailableRowsCannotEnterNewQueue() = runTest {
        val h = Harness(this)
        h.library.missing += "b"
        assertFalse(h.controller.playTrack(PlaybackTrackReference("b"), playlist))
        assertFalse(h.controller.queueTrack(PlaybackTrackReference("b")))
        assertTrue(h.controller.playCollection(playlist))
        runCurrent()
        assertEquals(listOf("a", "c"), h.state.queue.map { it.id })
        assertEquals(listOf("a"), h.library.probes)
    }

    @Test fun selectedDuplicateAndQueueDuplicatesRetainIndependentKeysAndProvenance() = runTest {
        val h = Harness(this)
        h.library.tracks = listOf(track("a", "first"), track("b"), track("a", "second"))
        val ref = PlaybackTrackReference("a", entryId = "second", occurrenceId = "row-second")
        assertTrue(h.controller.playTrack(ref, playlist))
        runCurrent()
        assertEquals(listOf("second", "first", null), h.state.queue.map { it.entryId })
        assertEquals("second", h.state.current?.entryId)
        assertTrue(h.controller.queueTrack(ref, playlist, next = true))
        val queue = h.state.queue
        assertEquals(listOf("second", "second", "first", null), queue.map { it.entryId })
        assertEquals(4, queue.map { it.key }.toSet().size)
        assertEquals(playlist, queue[1].playbackContext)
        h.controller.jump(queue[2].key)
        runCurrent()
        assertEquals("first", h.state.current?.entryId)
        assertNotEquals(h.audio.attempts[0].binding, h.audio.attempts[1].binding)
    }

    @Test fun shuffleAndQueueEditsPreserveCurrentAndExplicitNext() = runTest {
        val h = Harness(this)
        h.controller.playCollection(playlist)
        runCurrent()
        val original = h.state.queue
        h.controller.queueTrack(PlaybackTrackReference("a"), next = true)
        val priority = h.state.queue[1]
        h.controller.setShuffle(true)
        assertEquals(original.first(), h.state.current)
        assertEquals(priority, h.state.queue[1])
        h.controller.setShuffle(false)
        assertEquals(listOf(original[0], priority, original[1], original[2]), h.state.queue)
        h.controller.moveUpcoming(original[2].key, null)
        assertEquals(listOf(original[0].key, original[2].key, priority.key, original[1].key), h.state.queue.map { it.key })
        h.controller.remove(priority.key)
        assertEquals(listOf("a", "c", "b"), h.state.queue.map { it.id })
        h.controller.clearUpcoming()
        assertEquals(listOf("a"), h.state.queue.map { it.id })
        assertEquals(1, h.audio.attempts.size)
    }

    @Test fun repeatOneIsOnlyNaturalAndARealEndCallbackIsRequired() = runTest {
        val h = Harness(this)
        h.controller.playCollection(playlist)
        runCurrent()
        val first = h.audio.attempts.single().binding
        h.controller.setRepeat("one")
        h.audio.actual = h.audio.actual.copy(ended = true, elapsed = 30.0, isPlaying = false)
        advanceTimeBy(251)
        assertEquals(1, h.audio.attempts.size)
        h.audio.end(first)
        runCurrent()
        assertEquals("a", h.state.current?.id)
        assertEquals(2, h.audio.attempts.size)
        assertEquals(0.0, h.state.elapsed, 0.0)
        h.controller.next()
        runCurrent()
        assertEquals("b", h.state.current?.id)
        h.controller.next()
        runCurrent()
        h.controller.setRepeat("all")
        h.audio.end(h.audio.attempts.last().binding)
        runCurrent()
        assertEquals("a", h.state.current?.id)
    }

    @Test fun nativeErrorStopsWithoutSkippingOrExposingRawError() = runTest {
        val h = Harness(this)
        h.controller.playCollection(playlist)
        runCurrent()
        val current = h.audio.attempts.single().binding
        h.audio.actual = h.audio.actual.copy(error = "raw source details", isLoading = true, isBuffering = true)
        h.audio.observations.tryEmit(AudioObservation(current, h.audio.actual))
        runCurrent()
        assertEquals("media", h.state.error)
        assertFalse(h.state.isPlaying)
        assertFalse(h.state.isLoading)
        assertFalse(h.state.isBuffering)
        assertFalse(h.state.desiredPlaying)
        assertEquals("a", h.state.current?.id)
        assertEquals(1, h.audio.attempts.size)
    }

    @Test fun withdrawingPermissionWhileLookupSuspendsSuppressesItsResult() = runTest {
        val h = Harness(this)
        h.library.hold = Gate()
        h.controller.playCollection(playlist)
        runCurrent()
        h.allowed = false
        assertFalse(h.library.authorization!!())
        h.library.hold!!.release()
        runCurrent()
        assertFalse(h.state.desiredPlaying)
        assertFalse(h.state.isPlaying)
        assertTrue(h.audio.attempts.isEmpty())
        assertFalse(h.controller.playCollection(playlist))
    }

    @Test fun retireFencesOutgoingModeAndNeverTouchesIncomingExecutorOperation() = runTest {
        val h = Harness(this)
        val gate = Gate().also { h.audio.hold = it }
        h.controller.playCollection(playlist)
        runCurrent()
        val outgoing = h.audio.attempts.single()
        h.allowed = false
        h.controller.retire()
        assertFalse(outgoing.authorized())
        assertEquals(PlaybackQueueState(), h.state)

        // A different account/pass through manual-offline may use the same player and track.
        h.audio.hold = null
        val incoming = LocalPlaybackController(h.audio, h.library, backgroundScope, { true })
        incoming.playCollection(playlist)
        runCurrent()
        val live = h.audio.attempts.last()
        val pauseCount = h.audio.authorizedWhenPaused.size
        assertNotEquals(outgoing.binding, live.binding)
        h.allowed = true // A later mode change cannot revive the retired controller.
        gate.release()
        h.audio.end(outgoing.binding)
        h.controller.stop()
        h.controller.resume()
        assertFalse(h.controller.playCollection(playlist))
        runCurrent()
        assertEquals(pauseCount, h.audio.authorizedWhenPaused.size)
        assertEquals(listOf(live.binding), h.audio.starts)
        assertTrue(incoming.state.value.isPlaying)
        assertEquals("a", incoming.state.value.current?.id)
        assertEquals(PlaybackQueueState(), h.state)
    }

    @Test fun consecutiveFadePromotionsDoNotRevokeTheOutgoingLoaderEarly() = runTest {
        val h = Harness(this)
        h.controller.playCollection(playlist); runCurrent()
        val a = h.audio.starts.single()
        h.audio.readyKey = h.audio.successor?.invoke()?.key
        h.audio.end(a); runCurrent()
        assertEquals("b", h.state.current?.id)
        val b = requireNotNull(h.audio.actual.binding)
        val bAuthority = requireNotNull(h.audio.promotedAuthority)
        h.audio.readyKey = h.audio.successor?.invoke()?.key
        h.audio.end(b); runCurrent()
        assertEquals("c", h.state.current?.id)
        assertEquals(2, h.audio.promotions)
        assertEquals(1, h.audio.attempts.size)
        assertTrue(h.audio.outgoingAuthorizedDuringPromotion)
        assertFalse(bAuthority())
        assertTrue(h.audio.promotedAuthority?.invoke() == true)
    }

    @Test fun localPreviewTracksLiveQueueAndNeverPromotesOnSeekOrManualSkip() = runTest {
        val h = Harness(this)
        h.controller.playCollection(playlist); runCurrent()
        val resolve = requireNotNull(h.audio.successor)
        h.audio.readyKey = resolve()!!.key
        h.controller.remove(h.audio.readyKey!!)
        assertEquals("c", resolve()?.id)
        h.controller.setRepeat("one")
        assertNull(resolve())
        h.controller.seek(24.0); runCurrent()
        assertNull(resolve())
        h.controller.next(); runCurrent()
        assertEquals("c", h.state.current?.id)
        assertEquals(0, h.audio.promotions)
        val last = requireNotNull(h.audio.successor)
        h.controller.pause()
        assertNull(last())
    }

    @Test fun oneEntryRepeatAllDoesNotPreviewTheSameOccurrence() = runTest {
        val h = Harness(this)
        h.library.tracks = listOf(track("a"))
        h.controller.playCollection(playlist); runCurrent()
        h.controller.setRepeat("all")
        assertNull(requireNotNull(h.audio.successor).invoke())
    }

    @Test fun naturalAdvanceSkipsMissingInOrderAndRepeatStopsAfterOneTraversal() = runTest {
        val h = Harness(this)
        h.controller.playCollection(playlist); runCurrent()
        h.library.missing += "b"
        h.audio.end(h.audio.starts.last()); runCurrent()
        assertEquals(listOf("a", "b", "c"), h.library.probes)
        assertEquals("c", h.state.current?.id)
        h.library.missing += listOf("a", "c")
        h.controller.setRepeat("all")
        h.audio.end(h.audio.starts.last()); runCurrent()
        assertEquals(listOf("a", "b", "c", "a", "b", "c"), h.library.probes)
        assertFalse(h.state.desiredPlaying)
    }

    private class Harness(scope: TestScope) {
        var allowed = true
        val audio = FakeAudio()
        val library = FakeLibrary()
        val controller = LocalPlaybackController(audio, library, scope.backgroundScope, { allowed }, Random(19))
        val state get() = controller.state.value
    }

    /** Model an IO/native completion that cannot be prevented by coroutine cancellation alone. */
    private class Gate {
        private var continuation: Continuation<Unit>? = null
        val waiting get() = continuation != null
        suspend fun await() = suspendCoroutine { continuation = it }
        fun release() { continuation!!.resume(Unit); continuation = null }
    }

    private class FakeLibrary : LocalPlaybackLibrary {
        var tracks = listOf(track("a"), track("b"), track("c"))
        val missing = mutableSetOf<String>()
        val probes = mutableListOf<String>()
        var hold: Gate? = null
        var authorization: (() -> Boolean)? = null

        override fun resolve(track: PlaybackTrackReference?, context: PlaybackContext?): LocalPlaybackRows? {
            val eligible = tracks.filter { it.id !in missing }
            if (track == null) return LocalPlaybackRows(eligible)
            val selected = eligible.indexOfFirst {
                it.id == track.id && (track.entryId == null || it.entryId == track.entryId) &&
                    (track.occurrenceId == null || it.occurrenceId == track.occurrenceId)
            }
            if (selected < 0) return null
            return if (context == null) LocalPlaybackRows(listOf(eligible[selected])) else LocalPlaybackRows(eligible, selected)
        }

        override suspend fun hasAudio(track: PlaybackTrack, isAuthorized: () -> Boolean): Boolean {
            assertTrue(isAuthorized())
            probes += track.id
            authorization = isAuthorized
            hold?.await()
            return track.id !in missing
        }
    }

    private class FakeAudio : AudioExecutor {
        data class Attempt(val binding: AudioBinding, val elapsed: Double, val play: Boolean, val authorized: () -> Boolean)
        override val observations = MutableSharedFlow<AudioObservation>(extraBufferCapacity = 16)
        val attempts = mutableListOf<Attempt>()
        val starts = mutableListOf<AudioBinding>()
        val authorizedWhenPaused = mutableListOf<Boolean>()
        var actual = AudioFacts()
        var nativeDuration = 30.0
        var successor: (() -> PlaybackTrack?)? = null
        var readyKey: Long? = null
        var promotions = 0
        var outgoingAuthorizedDuringPromotion = false
        var promotedAuthority: (() -> Boolean)? = null
        override fun armCrossfade(binding: AudioBinding, successor: () -> PlaybackTrack?) { this.successor = successor }
        override fun promoteCrossfade(from: AudioBinding, next: AudioBinding, track: PlaybackTrack, authorized: () -> Boolean): Boolean {
            promotions++
            if (readyKey != track.key || actual.binding != from || !actual.ended || !authorized()) return false
            val outgoingAuthority = promotedAuthority ?: attempts.last { it.binding == from }.authorized
            outgoingAuthorizedDuringPromotion = outgoingAuthority()
            promotedAuthority = authorized
            actual = AudioFacts(next, 4.9, 30.0, isPlaying = true)
            return true
        }
        var hold: Gate? = null

        override suspend fun prepare(binding: AudioBinding, track: PlaybackTrack?, elapsed: Double): AudioResult =
            error("Local preparation must also check authorization")

        override suspend fun execute(binding: AudioBinding, track: PlaybackTrack?, elapsed: Double, desiredPlaying: Boolean,
            isAuthorized: () -> Boolean): AudioResult {
            assertTrue(isAuthorized())
            attempts += Attempt(binding, elapsed, desiredPlaying, isAuthorized)
            actual = AudioFacts(binding, elapsed, nativeDuration, isLoading = true, isBuffering = true)
            hold?.await()
            if (!isAuthorized()) return AudioResult.REJECTED
            if (desiredPlaying) starts += binding
            actual = AudioFacts(binding, elapsed, nativeDuration, isPlaying = desiredPlaying)
            return AudioResult.OK
        }

        override fun facts() = actual
        override fun pause() {
            authorizedWhenPaused += attempts.lastOrNull()?.authorized?.invoke() == true
            actual = actual.copy(isPlaying = false, isLoading = false, isBuffering = false)
        }

        fun end(binding: AudioBinding, elapsed: Double = 30.0) {
            val facts = AudioFacts(binding, elapsed = elapsed, duration = nativeDuration, ended = true)
            if (actual.binding == binding) actual = facts
            check(observations.tryEmit(AudioObservation(binding, facts)))
        }
    }

    private companion object {
        val playlist = PlaybackContext("playlist", "p", "Playlist")
        fun track(id: String, entry: String? = null) = PlaybackTrack(id, id, emptyList(), "Album", "album", 30.0,
            0, 0, "", entryId = entry, occurrenceId = entry?.let { "row-$it" })
    }
}
