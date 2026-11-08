package dev.spofify.mobile.playback

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedDemandPublicationTest {
    @Test fun lateAggregateRechecksOperationsAfterFastTrue() {
        val operations = AtomicInteger()
        val publication = SavedDemandPublication(operations)
        val values = mutableListOf<Boolean>()
        val earlierPlayerSnapshot = false
        operations.incrementAndGet()
        publication.active { values += it }
        publication.aggregate(earlierPlayerSnapshot) { values += it }
        assertEquals(listOf(true, true), values)
        operations.decrementAndGet()
        publication.aggregate(false) { values += it }
        assertEquals(listOf(true, true, false), values)
    }

    @Test fun earlierAggregateFalseCannotOverwriteNewerFastTrue() {
        val operations = AtomicInteger()
        val publication = SavedDemandPublication(operations)
        val values = CopyOnWriteArrayList<Boolean>()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val aggregateEntered = CountDownLatch(1)
        val releaseAggregate = CountDownLatch(1)
        val fastRequested = CountDownLatch(1)
        val fastFinished = AtomicBoolean()
        val workers = mutableListOf<Thread>()
        fun start(name: String, block: () -> Unit) {
            workers += Thread({ try { block() } catch (error: Throwable) { failures.add(error) } }, name).also { it.start() }
        }
        try {
            start("saved-aggregate-publication") {
                publication.aggregate(false) { value ->
                    aggregateEntered.countDown()
                    check(releaseAggregate.await(5, TimeUnit.SECONDS))
                    values += value
                }
            }
            assertTrue(aggregateEntered.await(5, TimeUnit.SECONDS))
            start("saved-fast-publication") {
                operations.incrementAndGet()
                fastRequested.countDown()
                publication.active { values += it }
                fastFinished.set(true)
            }
            assertTrue(fastRequested.await(5, TimeUnit.SECONDS))
            assertFalse(fastFinished.get())
        } finally {
            releaseAggregate.countDown()
            workers.forEach { it.join(5_000) }
        }
        assertTrue(workers.none { it.isAlive })
        failures.peek()?.let { throw AssertionError("Owned publication worker failed", it) }
        assertTrue(fastFinished.get())
        assertEquals(listOf(false, true), values.toList())
        assertTrue(operations.get() > 0)
    }

    @Test fun playerLoadingOrBufferingKeepsDemandWithoutAnOpenRead() {
        val publication = SavedDemandPublication(AtomicInteger())
        val values = mutableListOf<Boolean>()
        publication.aggregate(true) { values += it }
        publication.aggregate(false) { values += it }
        assertEquals(listOf(true, false), values)
    }
}
