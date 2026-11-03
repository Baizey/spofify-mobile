package dev.spofify.mobile

import dev.spofify.mobile.core.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class LikeOperationsTest {
    private class EmptyStore : SessionStore {
        override fun read(): StoredSession? = null
        override fun write(value: StoredSession?) = Unit
    }
    private fun TestScope.until(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) { runCurrent(); Thread.sleep(5) }
        runCurrent()
        assertTrue("Timed out waiting for the operation", condition())
    }

    @Test fun statusIsSingleFlightAndCannotOverwriteANewerSuccessfulMutation() = runTest {
        val readStarted = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val web = MockWebServer()
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/api/me/likes/status") {
                    readStarted.countDown()
                    releaseRead.await(5, TimeUnit.SECONDS)
                    return MockResponse().setHeader("Content-Type", "application/json")
                        .setBody("""{"items":[{"trackId":"t","liked":false}]}""")
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody("""{"trackId":"t","liked":true}""")
            }
        }
        web.start()
        try {
            val client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), EmptyStore())
            val values = mutableListOf<Boolean>()
            val operations = LikeOperations(this, { client }, { _, liked -> values.add(liked) }, { _, _ -> }, { throw it })
            operations.check("t"); operations.check("t")
            runCurrent()
            assertTrue(readStarted.await(5, TimeUnit.SECONDS))
            assertEquals(1, web.requestCount)
            operations.toggle("t", false)
            until { values.lastOrNull() == true }
            releaseRead.countDown()
            runCurrent()
            assertEquals(listOf(true), values)
            assertEquals(2, web.requestCount)
        } finally { releaseRead.countDown(); web.shutdown() }
    }

    @Test fun mutationsAreSerializedPerTrackAndConfirmedInOrder() = runTest {
        val releaseFirst = CountDownLatch(1)
        val requests = AtomicInteger()
        val web = MockWebServer()
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (requests.incrementAndGet() == 1) releaseFirst.await(5, TimeUnit.SECONDS)
                return MockResponse().setResponseCode(204)
            }
        }
        web.start()
        try {
            val client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), EmptyStore())
            val values = mutableListOf<Boolean>()
            val pending = mutableSetOf<String>()
            val operations = LikeOperations(this, { client }, { _, liked -> values.add(liked) },
                { id, busy -> if (busy) pending.add(id) else pending.remove(id) }, { throw it })
            operations.toggle("t", false)
            operations.toggle("t", false) // A second tap cannot launch a racing PUT.
            runCurrent()
            until { requests.get() == 1 }
            assertTrue("t" in pending)
            releaseFirst.countDown()
            until { values.size == 1 && pending.isEmpty() }
            operations.toggle("t", true)
            until { values.size == 2 && pending.isEmpty() }
            assertEquals(listOf(true, false), values)
            assertEquals(2, requests.get())
        } finally { releaseFirst.countDown(); web.shutdown() }
    }
}
