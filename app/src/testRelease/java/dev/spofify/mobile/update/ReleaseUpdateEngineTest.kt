package dev.spofify.mobile.update

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ReleaseUpdateEngineTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun onlyExplicitOnlineForegroundChecksAndNeverDownloadsAnInstalledOrOlderRelease() = runTest {
        val wire = FakeWire().apply {
            enqueue(release(tag = "mobile-v3"))
            repeat(2) { enqueue(release(tag = "mobile-v2")) }
        }
        val engine = engine(wire, installed = 3) { _, _ -> error("archive should not be inspected") }
        engine.setForeground(true)
        engine.setOnline(false)
        assertSame(UpdateStatus.Unavailable, engine.status.value)
        runCurrent()
        assertTrue(wire.requests.isEmpty())
        engine.setOnline(true)
        assertSame(UpdateStatus.Checking, engine.status.value)
        runCurrent()
        assertSame(UpdateStatus.UpToDate, engine.status.value)
        engine.setForeground(false)
        engine.setForeground(true)
        runCurrent()
        assertEquals(1, wire.requests.size)
        advanceTimeBy(30 * 60 * 1000L)
        engine.setForeground(true)
        runCurrent()
        assertEquals(1, wire.requests.size)
        engine.setForeground(false)
        engine.setForeground(true)
        runCurrent()
        assertSame(UpdateStatus.UpToDate, engine.status.value)
        assertEquals(2, wire.requests.size)
        engine.setOnline(null)
        engine.setOnline(true)
        runCurrent()
        assertSame(UpdateStatus.UpToDate, engine.status.value)
        assertEquals(List(3) { UpdateResource.METADATA }, wire.requests.map { it.resource })
    }

    @Test fun verifiedReadySurvivesForegroundReturnsWithoutPollingUntilOffline() = runTest {
        val cache = temp.newFolder()
        val partial = File(cache, "spofify-update-abandoned.part.apk").apply { writeText("partial") }
        val old = File(cache, "spofify-update-abandoned.apk").apply { writeText("old") }
        val unrelated = File(cache, "non-engine-file").apply { writeText("preserved") }
        val wire = FakeWire().apply {
            enqueue(release()); enqueue(redirect(CDN)); enqueue(apk()); enqueue(release(tag = "mobile-v1"))
        }
        val verifiedNames = mutableListOf<String>()
        val engine = engine(wire, cache = cache) { file, version ->
            assertEquals(2L, version)
            assertArrayEquals(fixtureApk, file.readBytes())
            verifiedNames += file.name
            true
        }
        engine.start()
        runCurrent()
        val ready = (engine.status.value as UpdateStatus.Ready).apk
        assertFalse(partial.exists())
        assertFalse(old.exists())
        assertTrue(unrelated.exists())
        assertEquals(setOf(unrelated, ready.file), cache.listFiles()!!.toSet())
        assertEquals(listOf(LATEST_RELEASE, URI(fixtureUrl("mobile-v2")), URI(CDN)), wire.requests.map { it.url })
        assertTrue(verifiedNames.single().endsWith(".part.apk"))
        assertSame(ready, engine.revalidate(ready))
        assertEquals(ready.file.name, verifiedNames.last())
        assertEquals("admitted", engine.withCurrentApk(ready) { "admitted" })
        engine.setForeground(false)
        assertSame(ready, (engine.status.value as UpdateStatus.Ready).apk)
        assertNull(engine.revalidate(ready))
        assertNull(engine.withCurrentApk(ready) { error("background launch") })
        assertTrue(ready.file.exists())
        engine.setForeground(true)
        assertTrue(engine.isCurrent(ready))
        assertTrue(ready.file.exists())
        advanceTimeBy(30 * 60 * 1000L)
        engine.setForeground(true)
        runCurrent()
        assertEquals(3, wire.requests.size)
        engine.setForeground(false)
        engine.setForeground(true)
        runCurrent()
        assertEquals(3, wire.requests.size)
        assertSame(ready, (engine.status.value as UpdateStatus.Ready).apk)
        assertSame(ready, engine.revalidate(ready))
        engine.setOnline(null)
        assertFalse(engine.isCurrent(ready))
        runCurrent()
        assertFalse(ready.file.exists())
        engine.setOnline(true)
        runCurrent()
        assertSame(UpdateStatus.UpToDate, engine.status.value)
        assertEquals(4, wire.requests.size)
        assertNull(engine.withCurrentApk(ready) { error("previous handle was admitted") })
    }

    @Test fun mutatedReadyFileCannotBeRevalidatedEvenAtTheSameSize() = runTest {
        val wire = FakeWire().apply { enqueue(release()); enqueue(apk()); enqueue(release(tag = "mobile-v1")) }
        var archiveChecks = 0
        val engine = engine(wire) { _, _ -> archiveChecks++; true }
        engine.start()
        runCurrent()
        val ready = (engine.status.value as UpdateStatus.Ready).apk
        ready.file.writeBytes(fixtureApk.map { (it.toInt() xor 1).toByte() }.toByteArray())
        assertNull(engine.revalidate(ready))
        runCurrent()
        assertEquals(1, archiveChecks)
        assertSame(UpdateStatus.Failure, engine.status.value)
        assertFalse(ready.file.exists())
        assertFalse(engine.isCurrent(ready))
        advanceTimeBy(30 * 60 * 1000L)
        engine.setForeground(false)
        engine.setForeground(true)
        runCurrent()
        assertSame(UpdateStatus.UpToDate, engine.status.value)
        assertEquals(3, wire.requests.size)
    }

    @Test fun rejectsBadArchiveAndCleansUpStagingFileWithoutRetry() = runTest {
        val wire = FakeWire().apply { enqueue(release()); enqueue(apk()) }
        val cache = temp.newFolder()
        val engine = engine(wire, cache = cache) { file, version ->
            assertArrayEquals(fixtureApk, file.readBytes())
            assertEquals(2L, version)
            false
        }
        engine.start()
        runCurrent()
        assertSame(UpdateStatus.Failure, engine.status.value)
        assertEquals(2, wire.requests.size)
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test fun boundsBothBodiesAndRejectsMisleadingHeadersOrContent() = runTest {
        val bigMetadata = CountingInput(ByteArray(MAX_METADATA_BYTES + 100))
        val bigApk = CountingInput(fixtureApk + ByteArray(100))
        val neverRead = CountingInput(fixtureApk)
        val forbidden = listOf(
            listOf(FakeResponse(body = bigMetadata)),
            listOf(FakeResponse(body = neverRead, headers = mapOf("Content-Length" to listOf("999999")))),
            listOf(release(), FakeResponse(body = bigApk)),
            listOf(release(), apk(fixtureApk.copyOf(fixtureApk.size - 1))),
            listOf(release(), apk(ByteArray(fixtureApk.size))),
            listOf(release(), FakeResponse(body = neverRead, headers = mapOf("Content-Encoding" to listOf("gzip")))),
            listOf(release(), FakeResponse(body = neverRead, headers = mapOf("Content-Length" to listOf("0")))),
            listOf(release(), FakeResponse(body = neverRead, headers = mapOf("Content-Length" to listOf("19", "19")))),
            listOf(release(), FakeResponse(code = 206, body = neverRead)),
            listOf(release(), FakeResponse(body = neverRead, headers = mapOf("Content-Range" to listOf("bytes 0-18/19")))),
        )
        for (steps in forbidden) {
            val wire = FakeWire().apply { steps.forEach(::enqueue) }
            val cache = temp.newFolder()
            val engine = engine(wire, cache = cache) { _, _ -> error("unverified data reached the archive check") }
            engine.start()
            runCurrent()
            assertSame(UpdateStatus.Failure, engine.status.value)
            assertEquals(steps.size, wire.requests.size)
            assertTrue(cache.listFiles()!!.isEmpty())
        }
        assertEquals(MAX_METADATA_BYTES + 1, bigMetadata.consumed)
        assertEquals(fixtureApk.size + 1, bigApk.consumed)
        assertEquals(0, neverRead.consumed)
    }

    @Test fun onlyAllowsBoundedAssetCdnRedirectsAndNeverFollowsMetadataRedirects() = runTest {
        val locations = listOf("/relative", "http://release-assets.githubusercontent.com/file", "https://github.com/other",
            "https://api.github.com/other", "https://objects.githubusercontent.com/file",
            "https://release-assets.githubusercontent.com.evil.example/file",
            "https://evil.example@release-assets.githubusercontent.com/file",
            "https://release-assets.githubusercontent.com:8443/file", "$CDN#fragment", "$CDN${"x".repeat(8192)}")
        for (location in locations) {
            val wire = FakeWire().apply { enqueue(release()); enqueue(redirect(location)) }
            val engine = engine(wire)
            engine.start()
            runCurrent()
            assertSame(UpdateStatus.Failure, engine.status.value)
            assertEquals(2, wire.requests.size)
        }
        val duplicate = FakeWire().apply {
            enqueue(release()); enqueue(FakeResponse(302, headers = mapOf("Location" to listOf(CDN, CDN))))
        }
        val apiRedirect = FakeWire().apply { enqueue(redirect(CDN)) }
        val loop = FakeWire().apply { enqueue(release()); repeat(4) { enqueue(redirect(CDN)) } }
        for ((wire, count) in listOf(duplicate to 2, apiRedirect to 1, loop to 5)) {
            val engine = engine(wire)
            engine.start()
            runCurrent()
            assertSame(UpdateStatus.Failure, engine.status.value)
            assertEquals(count, wire.requests.size)
        }
    }

    @Test fun usesRemainingDownloadDeadlineAcrossRedirectsAndRejectsLateBody() = runTest {
        var clock = 0L
        val wire = FakeWire().apply {
            enqueue(release())
            enqueue { clock += 80_000; redirect(CDN) }
            enqueue {
                FakeResponse(body = object : ByteArrayInputStream(fixtureApk) {
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        clock += 40_001
                        return super.read(bytes, offset, length)
                    }
                })
            }
        }
        val engine = engine(wire, time = { clock })
        engine.start()
        runCurrent()
        assertSame(UpdateStatus.Downloading, engine.status.value) // Backoff; never Ready.
        assertEquals(listOf(15_000L, 120_000L, 40_000L), wire.requests.map { it.budget })
        engine.setOnline(false)
        advanceTimeBy(20_000)
        runCurrent()
        assertSame(UpdateStatus.Unavailable, engine.status.value)
        assertEquals(3, wire.requests.size)
    }

    @Test fun retriesTransientFailuresWithTwoBoundedBackoffsButNotRateLimits() = runTest {
        val wire = FakeWire().apply { repeat(3) { enqueue(FakeResponse(code = 503)) } }
        val engine = engine(wire)
        engine.start()
        runCurrent()
        assertEquals(1, wire.requests.size)
        advanceTimeBy(1999)
        runCurrent()
        assertEquals(1, wire.requests.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, wire.requests.size)
        advanceTimeBy(7999)
        runCurrent()
        assertEquals(2, wire.requests.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, wire.requests.size)
        assertSame(UpdateStatus.Failure, engine.status.value)
        for (response in listOf(FakeResponse(code = 429), FakeResponse(code = 503,
            headers = mapOf("Retry-After" to listOf("60"))))) {
            val limited = FakeWire().apply { enqueue(response) }
            val rejected = engine(limited)
            rejected.start()
            runCurrent()
            assertSame(UpdateStatus.Failure, rejected.status.value)
            assertEquals(1, limited.requests.size)
        }
    }

    @Test fun offlineCancelsCurrentCallAndOldBlockingDownloadCannotPublish() = runBlocking {
        val entered = CountDownLatch(1)
        val canceled = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val scopeJob = SupervisorJob()
        val cache = temp.newFolder()
        try {
            val wire = FakeWire().apply {
                enqueue(release())
                enqueue(onCancel = { canceled.countDown() }) {
                    entered.countDown()
                    assertTrue(unblock.await(5, TimeUnit.SECONDS)) // Simulate noncooperative I/O.
                    apk()
                }
                enqueue(release(tag = "mobile-v3")); enqueue(apk())
            }
            val engine = ReleaseUpdateEngine(CoroutineScope(scopeJob), cache, 1, { _, code -> code == 3L }, wire)
            engine.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertSame(UpdateStatus.Downloading, engine.status.value)
            val stale = scopeJob.children.first()
            engine.setOnline(false)
            assertEquals(0L, canceled.count)
            assertSame(UpdateStatus.Unavailable, engine.status.value)
            engine.setOnline(true)
            val current = withTimeout(5_000) { engine.status.filterIsInstance<UpdateStatus.Ready>().first() }
            assertEquals(3L, current.apk.versionCode)
            unblock.countDown()
            withTimeout(5_000) { stale.join() }
            assertSame(current, engine.status.value)
            assertEquals(listOf(current.apk.file), cache.listFiles()!!.toList())
        } finally {
            unblock.countDown()
            scopeJob.cancelAndJoin()
        }
    }

    @Test fun offlineDuringFinalVerificationFencesBothPromotionAndTapRevalidation() = runBlocking {
        val verifying = CountDownLatch(1)
        val unverify = CountDownLatch(1)
        val tapping = CountDownLatch(1)
        val untap = CountDownLatch(1)
        val retapping = CountDownLatch(1)
        val unretap = CountDownLatch(1)
        val checks = AtomicInteger()
        val scopeJob = SupervisorJob()
        val cache = temp.newFolder()
        try {
            val wire = FakeWire().apply {
                enqueue(release()); enqueue(apk()); enqueue(release(tag = "mobile-v3")); enqueue(apk())
            }
            val engine = ReleaseUpdateEngine(CoroutineScope(scopeJob), cache, 1, { _, _ ->
                when (checks.incrementAndGet()) {
                    1 -> { verifying.countDown(); assertTrue(unverify.await(5, TimeUnit.SECONDS)) }
                    3 -> { tapping.countDown(); assertTrue(untap.await(5, TimeUnit.SECONDS)) }
                    4 -> { retapping.countDown(); assertTrue(unretap.await(5, TimeUnit.SECONDS)) }
                }
                true
            }, wire)
            engine.start()
            assertTrue(verifying.await(5, TimeUnit.SECONDS))
            val stale = scopeJob.children.first()
            engine.setOnline(null)
            engine.setOnline(true)
            val current = withTimeout(5_000) { engine.status.filterIsInstance<UpdateStatus.Ready>().first() }.apk
            assertEquals(3L, current.versionCode)
            unverify.countDown()
            withTimeout(5_000) { stale.join() }
            assertEquals(listOf(current.file), cache.listFiles()!!.toList())
            val tap = async(Dispatchers.Default) { engine.revalidate(current) }
            assertTrue(tapping.await(5, TimeUnit.SECONDS))
            engine.setForeground(false)
            engine.setForeground(true)
            untap.countDown()
            assertNull(withTimeout(5_000) { tap.await() })
            assertTrue(current.file.exists())
            val retap = async(Dispatchers.Default) { engine.revalidate(current) }
            assertTrue(retapping.await(5, TimeUnit.SECONDS))
            engine.setOnline(false)
            assertSame(UpdateStatus.Unavailable, engine.status.value)
            assertNull(engine.withCurrentApk(current) { error("offline launch") })
            unretap.countDown()
            assertNull(withTimeout(5_000) { retap.await() })
        } finally {
            unverify.countDown()
            untap.countDown()
            unretap.countDown()
            scopeJob.cancelAndJoin()
        }
    }

    private fun TestScope.engine(
        wire: FakeWire,
        installed: Long = 1L,
        cache: File = temp.newFolder(),
        time: () -> Long = { testScheduler.currentTime },
        verify: (File, Long) -> Boolean = { _, _ -> true },
    ) = ReleaseUpdateEngine(backgroundScope, cache, installed, verify, wire, StandardTestDispatcher(testScheduler), time)

    private fun ReleaseUpdateEngine.start() {
        setForeground(true)
        setOnline(true)
    }

    private fun release(tag: String = "mobile-v2") = FakeResponse(body = metadata(tag = tag).byteInputStream())
    private fun apk(bytes: ByteArray = fixtureApk) = FakeResponse(body = bytes.inputStream())
    private fun redirect(location: String) = FakeResponse(302, headers = mapOf("Location" to listOf(location)))

    private companion object {
        const val CDN = "https://release-assets.githubusercontent.com/synthetic-id?token=fixture"
    }
}

private class FakeResponse(
    override val code: Int = 200,
    override val body: InputStream = ByteArrayInputStream(byteArrayOf()),
    private val headers: Map<String, List<String>> = emptyMap(),
) : UpdateResponse {
    override fun headers(name: String) = headers[name].orEmpty()
    override fun close() = body.close()
}

private class CountingInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
    val consumed get() = pos
}

private class FakeWire : UpdateTransport {
    data class Request(val url: URI, val resource: UpdateResource, val budget: Long)
    val requests = CopyOnWriteArrayList<Request>()
    private val steps = ConcurrentLinkedQueue<UpdateCall>()

    fun enqueue(response: UpdateResponse) = enqueue { response }

    fun enqueue(onCancel: () -> Unit = {}, execute: () -> UpdateResponse) {
        steps += object : UpdateCall {
            override fun cancel() = onCancel()
            override fun execute() = execute.invoke()
        }
    }

    override fun newCall(url: URI, resource: UpdateResource, remainingMillis: Long): UpdateCall {
        requests += Request(url, resource, remainingMillis)
        return steps.poll() ?: throw AssertionError("Unexpected fake HTTP request")
    }
}
