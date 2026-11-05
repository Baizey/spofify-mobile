package dev.spofify.mobile.playback

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SongPrefetchTest {
    @Test fun windowIsFiveOccurrencesBeforeDeduplicationAndNeverScansForMoreDistinctTracks() {
        var visited = 0
        val plan = SongPrefetch.plan("A", sequence {
            for (id in listOf("B", "B", "C", "D", "E", "F")) {
                visited++
                check(visited <= 5) { "Read beyond the permitted window" }
                yield(id)
            }
        })
        assertEquals(5, visited)
        assertEquals(listOf("B", "B", "C", "D", "E"), plan.nextOccurrenceTrackIds)
        assertEquals(listOf("B", "C", "D", "E"), plan.trackIds)
    }

    @Test fun currentIsExcludedWithoutExtendingWindowAndNoCurrentDoesNotReadUpcoming() {
        assertEquals(listOf("B", "C"), SongPrefetch.plan("A", listOf("A", "B", "A", "C", "B", "D")).trackIds)
        val empty = SongPrefetch.plan(null, sequence<String> { error("No prepared current") })
        assertTrue(empty.trackIds.isEmpty())
        assertTrue(empty.nextOccurrenceTrackIds.isEmpty())
    }

    @Test fun callerRepeatAndRealizedTraversalArePreservedRatherThanReconstructed() {
        assertTrue(SongPrefetch.plan("A", generateSequence { "A" }).trackIds.isEmpty()) // repeat one
        assertEquals(listOf("B", "C", "A", "B", "C"),
            SongPrefetch.plan("A", generateSequence(0) { it + 1 }.map { listOf("B", "C", "A")[it % 3] }).nextOccurrenceTrackIds)
        assertTrue(SongPrefetch.plan("A", emptyList()).trackIds.isEmpty()) // repeat off at end
        // These are accepted High/Next/context/shuffle steps, not physical list order.
        assertEquals(listOf("high", "next", "shuffled-low", "context"),
            SongPrefetch.plan("current", listOf("high", "next", "shuffled-low", "context")).trackIds)
    }

    @Test fun planSnapshotsInputAndComparesSemanticContent() {
        val input = mutableListOf("B", "C")
        val plan = SongPrefetch.plan("A", input)
        input[0] = "changed"
        assertEquals(listOf("B", "C"), plan.trackIds)
        assertEquals(SongPrefetch.plan("A", listOf("B", "C")), plan)
        assertNotEquals(SongPrefetch.plan("A", listOf("B", "B", "C")), plan)
        try {
            (plan.nextOccurrenceTrackIds as MutableList<String>).add("D")
            fail("Snapshot must not be mutable")
        } catch (_: UnsupportedOperationException) { }
    }

    @Test fun attemptsAreSequentialFailuresAreSkippedAndHeartbeatsDoNotRestart() = runTest {
        val attempts = mutableListOf<String>()
        var active = 0
        var maxActive = 0
        val scheduler = SongPrefetchScheduler<String>(this, ioDispatcher = StandardTestDispatcher(testScheduler)) { id, _ ->
            active++
            maxActive = maxOf(maxActive, active)
            try {
                attempts.add(id)
                if (id == "B") throw IOException("Synthetic failed speculation")
            } finally { active-- }
        }
        val plan = SongPrefetch.plan("A", listOf("B", "C", "D"))
        scheduler.reconcile(plan, plan.trackIds)
        runCurrent()
        repeat(10) { scheduler.reconcile(SongPrefetch.plan("A", listOf("B", "C", "D")), plan.trackIds); runCurrent() }
        assertEquals(listOf("B", "C", "D"), attempts)
        assertEquals(1, maxActive)
        scheduler.close()
    }

    @Test fun replacementBeforeCoroutineBodyStartsDoesNotStrandSuccessor() = runTest {
        val seen = mutableListOf<String>()
        val scheduler = SongPrefetchScheduler<String>(this, ioDispatcher = StandardTestDispatcher(testScheduler)) { id, _ -> seen.add(id) }
        scheduler.reconcile(SongPrefetch.plan("A", listOf("B")), listOf("B"))
        scheduler.reconcile(SongPrefetch.plan("X", listOf("Y")), listOf("Y"))
        runCurrent()
        assertEquals(listOf("Y"), seen)
        scheduler.close()
    }

    @Test fun requestsAreSnapshottedAndDemandBeforeStartDefersAllWork() = runTest {
        val seen = mutableListOf<String>()
        val scheduler = SongPrefetchScheduler<String>(this, ioDispatcher = StandardTestDispatcher(testScheduler)) { request, _ -> seen.add(request) }
        val plan = SongPrefetch.plan("A", listOf("B", "C"))
        val requests = mutableListOf("request-B", "request-C")
        scheduler.setDemandActive(true)
        scheduler.reconcile(plan, requests)
        requests[0] = "mutated"
        runCurrent()
        assertTrue(seen.isEmpty())
        scheduler.setDemandActive(false)
        runCurrent()
        assertEquals(listOf("request-B", "request-C"), seen)
        scheduler.close()
    }

    @Test fun pauseRequiresExplicitReconcileButSamePlanResumeCanRearm() = runTest {
        val seen = mutableListOf<String>()
        val scheduler = SongPrefetchScheduler<String>(this, ioDispatcher = StandardTestDispatcher(testScheduler)) { id, _ -> seen.add(id) }
        val plan = SongPrefetch.plan("A", listOf("B"))
        scheduler.setDemandActive(true)
        scheduler.reconcile(plan, plan.trackIds)
        scheduler.cancel()
        scheduler.setDemandActive(false)
        runCurrent()
        assertTrue(seen.isEmpty())
        scheduler.reconcile(plan, listOf("new-session-request-B"))
        runCurrent()
        assertEquals(listOf("new-session-request-B"), seen)
        scheduler.close()
        scheduler.reconcile(SongPrefetch.plan("X", listOf("Y")), listOf("Y"))
        runCurrent()
        assertEquals(1, seen.size)
    }

    @Test fun cancellationRegistrationCoversBothSidesOfOpenRaceAndCanBeRemoved() {
        val token = SongPrefetchCancellation()
        var cancelled = 0
        token.onCancel { cancelled++ }
        token.onCancel { error("Synthetic close failure") }.close()
        token.onCancel { throw IOException("Synthetic close failure") }
        val removed = token.onCancel { cancelled += 100 }
        removed.close()
        token.cancel()
        token.cancel()
        token.onCancel { cancelled++ }
        assertEquals(2, cancelled)
        try { token.ensureActive(); fail("Cancelled token must reject a late open") }
        catch (_: kotlinx.coroutines.CancellationException) { }
    }

    @Test fun replacementWaitsForActuallyDrainedIoAndStaleCompletionCannotClearSuccessor() {
        heldTransferScenario { scope, release, started, cancelled, nextStarted, live, maxLive ->
            val seen = CopyOnWriteArrayList<String>()
            val successorCancelled = CountDownLatch(1)
            val successorRelease = CountDownLatch(1)
            val scheduler = SongPrefetchScheduler<String>(scope) { id, token ->
                val count = live.incrementAndGet()
                maxLive.updateAndGet { maxOf(it, count) }
                try {
                    seen.add(id)
                    if (id == "B") {
                        token.onCancel { cancelled.countDown() }
                        started.countDown()
                        await(release)
                    } else {
                        token.onCancel { successorCancelled.countDown(); successorRelease.countDown() }
                        nextStarted.countDown()
                        await(successorRelease)
                    }
                } finally { live.decrementAndGet() }
            }
            try {
                scheduler.reconcile(SongPrefetch.plan("A", listOf("B", "C")), listOf("B", "C"))
                await(started)
                scheduler.reconcile(SongPrefetch.plan("X", listOf("Y")), listOf("Y"))
                await(cancelled)
                assertEquals("No successor while old blocking IO is still alive", 1L, nextStarted.count)
                release.countDown()
                await(nextStarted)
                assertEquals(listOf("B", "Y"), seen)
                assertEquals(1, maxLive.get())
                // Old completion has already drained before Y begins; it cannot cancel/clear Y.
                scheduler.cancel()
                await(successorCancelled)
            } finally {
                release.countDown()
                successorRelease.countDown()
                scheduler.close()
            }
        }
    }

    @Test fun demandCancelsHeldAttemptSkipsItAndContinuesRemainingOnlyAfterRelease() {
        heldTransferScenario { scope, release, started, cancelled, nextStarted, _, _ ->
            val seen = CopyOnWriteArrayList<String>()
            val scheduler = SongPrefetchScheduler<String>(scope) { id, token ->
                seen.add(id)
                if (id == "B") {
                    token.onCancel { cancelled.countDown() }
                    started.countDown()
                    await(release)
                } else nextStarted.countDown()
            }
            val plan = SongPrefetch.plan("A", listOf("B", "C"))
            try {
                scheduler.reconcile(plan, plan.trackIds)
                await(started)
                scheduler.setDemandActive(true)
                await(cancelled)
                scheduler.reconcile(plan, plan.trackIds) // heartbeat cannot restart the cancelled B
                assertEquals(1L, nextStarted.count)
                scheduler.setDemandActive(false)
                assertEquals(1L, nextStarted.count) // close requested is not actual drain
                release.countDown()
                await(nextStarted)
                assertEquals(listOf("B", "C"), seen)
            } finally { release.countDown(); scheduler.close() }
        }
    }

    @Test fun finiteTimeoutCancelsActualHeldOpenAndDoesNotOverlapNextAttempt() {
        assertEquals(30_000L, SongPrefetchScheduler.DEFAULT_ATTEMPT_TIMEOUT_MS)
        heldTransferScenario { scope, release, started, cancelled, nextStarted, _, _ ->
            val seen = CopyOnWriteArrayList<String>()
            val scheduler = SongPrefetchScheduler<String>(scope, attemptTimeoutMs = 1_000) { id, token ->
                seen.add(id)
                if (id == "B") {
                    token.onCancel { cancelled.countDown() }
                    started.countDown()
                    await(release)
                } else nextStarted.countDown()
            }
            try {
                scheduler.reconcile(SongPrefetch.plan("A", listOf("B", "C")), listOf("B", "C"))
                await(started)
                await(cancelled) // timer, not a service callback, must cancel the actual transfer
                assertEquals(1L, nextStarted.count)
                release.countDown()
                await(nextStarted)
                assertEquals(listOf("B", "C"), seen)
            } finally { release.countDown(); scheduler.close() }
        }
    }

    @Test fun parentScopeLossClosesBlockingTransferWithoutAnotherAttempt() {
        heldTransferScenario { scope, release, started, cancelled, nextStarted, _, _ ->
            val scheduler = SongPrefetchScheduler<String>(scope) { id, token ->
                if (id == "B") {
                    token.onCancel { cancelled.countDown(); release.countDown() }
                    started.countDown()
                    await(release)
                } else nextStarted.countDown()
            }
            try {
                scheduler.reconcile(SongPrefetch.plan("A", listOf("B", "C")), listOf("B", "C"))
                await(started)
                scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
                await(cancelled)
                assertEquals(1L, nextStarted.count)
            } finally { release.countDown(); scheduler.close() }
        }
    }

    private fun heldTransferScenario(
        body: (CoroutineScope, CountDownLatch, CountDownLatch, CountDownLatch, CountDownLatch, AtomicInteger, AtomicInteger) -> Unit,
    ) {
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner + Dispatchers.Default)
        val release = CountDownLatch(1)
        try {
            body(scope, release, CountDownLatch(1), CountDownLatch(1), CountDownLatch(1), AtomicInteger(), AtomicInteger())
        } finally {
            release.countDown()
            runBlocking { owner.cancelAndJoin() }
        }
    }

    private fun await(latch: CountDownLatch) {
        assertTrue("Timed out waiting for synthetic transfer", latch.await(5, TimeUnit.SECONDS))
    }
}
