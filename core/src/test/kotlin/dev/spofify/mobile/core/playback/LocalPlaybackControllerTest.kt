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
        assertNull(h.state.occurrence)
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

    @Test fun missingSuccessorStaysSelectedUntilExplicitAdvance() = runTest {
        val h = Harness(this)
        h.controller.playCollection(playlist)
        runCurrent()
        val old = h.audio.attempts.single().binding
        h.library.missing += "b"
        h.audio.end(old)
        runCurrent()
        assertEquals(listOf("a", "b"), h.library.probes)
        assertEquals("b", h.state.current?.id)
        assertEquals("unavailable", h.state.error)
        assertFalse(h.state.isPlaying)
        assertFalse(h.state.desiredPlaying)
        assertEquals(1, h.audio.attempts.size)
        h.audio.end(old)
        runCurrent()
        assertEquals("b", h.state.current?.id)
        h.controller.next()
        runCurrent()
        assertEquals("c", h.state.current?.id)
        assertTrue(h.state.isPlaying)
        assertEquals(listOf("a", "b", "c"), h.library.probes)
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

    @Test fun accountOwnerTraversesDuplicateBeforeAnyResponseAndReconciliationDoesNotExecuteAgain() = runTest {
        val audio = FakeAudio()
        val facts = mutableListOf<AccountFact>()
        val views = mutableListOf<AccountState>()
        val owner = AccountPlayer(audio, backgroundScope, { true }, views::add, facts::add)
        val rows = listOf(track("same").copy(key = 7), track("same").copy(key = 8))
        val before = AccountCursor(7, "source", desired = true)
        owner.snapshot(AccountState(rows, before, "off", false), true)
        runCurrent()
        val source = audio.starts.single()
        audio.end(source)
        runCurrent()
        assertEquals(2, audio.starts.size)
        assertEquals(8L, views.last().cursor.key)
        assertEquals(8L, facts.single { it.ended }.landed)
        assertEquals(7L, facts.single { it.ended }.key)
        owner.update(AccountDelta(cursor = before.copy(elapsed = 29.0), observed = true))
        owner.update(AccountDelta(cursor = views.last().cursor, observed = true))
        runCurrent()
        assertEquals(2, audio.attempts.size)
        owner.output(false)
        val count = facts.size
        audio.end(audio.starts.last()); runCurrent()
        assertEquals(count, facts.size)
        owner.close()
    }

    @Test fun accountOwnerEditsCachedTraversalAndPromotesExactNativeBinding() = runTest {
        val audio=FakeAudio(); val facts=mutableListOf<AccountFact>(); val views=mutableListOf<AccountState>()
        val owner=AccountPlayer(audio,backgroundScope,{true},views::add,facts::add)
        val rows=(1..4).map { track("t$it","e$it").copy(playbackContext=playlist,key=it.toLong(),order=it.toLong(),intent="low") }
        val start=AccountState(rows,AccountCursor(1,"source",desired=true),"off",false)
        fun local(type:String,vararg v:Pair<String,Any?>)=owner.local(fields("type" to type,*v))
        val ctx=fields("kind" to "playlist","id" to "p","name" to "Playlist")
        val collection=fields("kind" to "collection","context" to ctx)
        val selection=fields("kind" to "track","track" to fields("id" to "t3","entryId" to "e3"),"context" to ctx)
        fun keys()=views.last().queue.map { it.key }
        owner.snapshot(start,true); runCurrent()
        local("play","selection" to collection);runCurrent();assertEquals(1,audio.attempts.size) // rows alone do not prove completeness
        owner.snapshot(start.copy(collectionFirst=1),true)
        local("shuffle","shuffle" to true,"seed" to 0);assertEquals(listOf(1L,3L,4L,2L),keys());local("shuffle","shuffle" to false);assertEquals(rows.map { it.key },keys())
        local("move","key" to 4,"after" to 0);assertEquals(listOf(1L,4L,2L,3L),keys());local("remove","key" to 2);assertEquals(listOf(1L,4L,3L),keys());local("clear");assertEquals(listOf(1L),keys())
        local("add","selection" to collection,"placement" to "next");local("add","selection" to selection,"placement" to "low");assertEquals(listOf("t1","t1","t2","t3","t4","t3"),views.last().queue.map { it.id });assertEquals(6,keys().distinct().size)
        owner.update(AccountDelta(cursor=start.cursor,observed=true));assertEquals(6,keys().size);runCurrent();assertEquals(1,audio.attempts.size)
        local("play","selection" to selection);runCurrent();val binding=audio.starts.last();assertEquals(listOf("e3","e4","e1","e2"),views.last().queue.map { it.entryId });assertEquals(2,audio.attempts.size)
        val actual=audio.actual.copy(elapsed=2.0);audio.actual=actual;audio.observations.tryEmit(AudioObservation(binding,actual));runCurrent();assertFalse(facts.any { it.chain.startsWith("local:") })
        val canonical=views.last().queue.mapIndexed { i,r->r.copy(key=i+20L,order=i+20L) }
        owner.update(AccountDelta(queue=AccountQueueEdit("replace",rows=canonical,collectionFirst=22),cursor=AccountCursor(20,"confirmed",desired=true)),ownSelection=true);runCurrent()
        assertEquals(2,audio.attempts.size);assertEquals(2.0,views.last().cursor.elapsed,0.0);assertTrue(views.last().cursor.playing)
        audio.observations.tryEmit(AudioObservation(binding,actual));runCurrent();assertEquals("confirmed",facts.last().chain);assertEquals(20L,facts.last().key)
        local("close");assertTrue(views.last().queue.isEmpty());assertFalse(audio.actual.isPlaying);assertFalse(audio.attempts.last().authorized());owner.close()
    }

    @Test fun confirmedAccountPreviewPublishesOnlyOutgoingRunUntilRealEndThenAdoptsNativePosition() = runTest {
        val audio = FakeAudio()
        val facts = mutableListOf<AccountFact>()
        val views = mutableListOf<AccountState>()
        val owner = AccountPlayer(audio, backgroundScope, { true }, views::add, facts::add)
        val rows = listOf(track("duplicate").copy(key = 1), track("duplicate").copy(key = 2))
        val initial = AccountState(rows, AccountCursor(1, "chain", desired = true), "off", false)
        owner.snapshot(initial, true)
        runCurrent()
        val outgoing = audio.starts.single()
        val resolver = requireNotNull(audio.successor)
        assertEquals(2L, resolver()?.key)
        owner.local(fields("type" to "repeat", "repeat" to "one"))
        assertNull(resolver()) // Optimistic repeat is not an authorized audible successor.
        owner.update(AccountDelta(repeat = "one"))
        assertNull(resolver()) // Confirmed repeat-one still does not overlap the same occurrence.
        owner.update(AccountDelta(repeat = "off"))
        audio.readyKey = resolver()?.key
        audio.actual = audio.actual.copy(elapsed = 29.9)
        advanceTimeBy(251)
        assertTrue(facts.all { !it.ended && it.key == 1L })
        audio.end(outgoing); runCurrent()
        assertEquals(2L, facts.single { it.ended }.landed)
        assertEquals(2L, views.last().cursor.key)
        assertEquals(4.9, views.last().cursor.elapsed, 0.0)
        assertEquals(1, audio.attempts.size) // The second deck wasn't restarted at zero.
        assertNotEquals(outgoing.eventId, audio.actual.binding!!.eventId)
        assertTrue(audio.outgoingAuthorizedDuringPromotion)
        assertFalse(audio.attempts.single().authorized())
        assertTrue(audio.promotedAuthority?.invoke() == true)
        assertNull(resolver()) // Stale resolver cannot donate its deck to any later run.
        owner.close()
        assertFalse(audio.promotedAuthority?.invoke() == true)
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
            actual = AudioFacts(binding, elapsed, 30.0, isLoading = true, isBuffering = true)
            hold?.await()
            if (!isAuthorized()) return AudioResult.REJECTED
            if (desiredPlaying) starts += binding
            actual = AudioFacts(binding, elapsed, 30.0, isPlaying = desiredPlaying)
            return AudioResult.OK
        }

        override fun facts() = actual
        override fun pause() {
            authorizedWhenPaused += attempts.lastOrNull()?.authorized?.invoke() == true
            actual = actual.copy(isPlaying = false, isLoading = false, isBuffering = false)
        }

        fun end(binding: AudioBinding) {
            val facts = AudioFacts(binding, elapsed = 30.0, duration = 30.0, ended = true)
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
