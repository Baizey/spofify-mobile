package dev.spofify.mobile.core.http

import dev.spofify.mobile.core.auth.AuthFailure
import dev.spofify.mobile.core.auth.FeatureAuthority
import dev.spofify.mobile.core.cache.MusicBlobStore
import dev.spofify.mobile.core.cache.MusicOwner
import dev.spofify.mobile.core.cache.SongKey
import dev.spofify.mobile.core.cache.bytes
import dev.spofify.mobile.core.cache.descriptor
import dev.spofify.mobile.core.origin.ServerOrigin
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioLoadRecoveryTest {
    @Test fun truncatedBodyCanContinueAtRequestedOffsetWithoutDuplicateBytes() = fixture { server, api ->
        val payload = "0123456789abcdef"
        server.enqueue(response(payload).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val received = ByteArrayOutputStream()
        val original = api.open().use { stream ->
            assertThrows(TransientAudioLoadFailure::class.java) { stream.input.copyTo(received) }
            stream.representation
        }
        val offset = received.size()
        assertTrue(offset in 1 until payload.length)
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        assertTrue(runCatching { api.open(offset.toLong()) }.exceptionOrNull() is TransientAudioLoadFailure)
        server.enqueue(response(payload.substring(offset)).setResponseCode(206)
            .setHeader("Content-Range", "bytes $offset-${payload.lastIndex}/${payload.length}"))
        api.open(offset.toLong()).use {
            it.checkContinuation(original, offset.toLong(), null)
            it.input.copyTo(received)
        }
        assertArrayEquals(payload.toByteArray(), received.toByteArray())
        assertNull(server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Range"))
        assertEquals("bytes=$offset-", server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Range"))
        val resumed = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("bytes=$offset-", resumed.getHeader("Range"))
        assertEquals("identity", resumed.getHeader("Accept-Encoding"))
        assertEquals("spofify_session=fixture", resumed.getHeader("Cookie"))
        assertEquals(3, server.requestCount)
    }

    @get:Rule val root = TemporaryFolder()

    @Test fun interruptedVersionedFillDoesNotReopenASuffixOrPublishPartialBytes() = fixture { server, api ->
        val key = SongKey(MusicOwner(api.origin.value, "123"), "fixture-track")
        val media = descriptor(key.id)
        val store = MusicBlobStore(root.root)
        server.enqueue(response(bytes.decodeToString()).setHeader("ETag", "\"${media.mediaVersion}\"")
            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val error = runCatching {
            store.fill(key, "fixture", 3, listOf(key.id), { true }, { media }) { descriptor, consume ->
                api.versionedAudio(descriptor, "spofify_session=fixture; Path=/; HttpOnly", FeatureAuthority(Any()) { true }, { _, _ -> })
                    .use { consume(it.input) }
            }
        }.exceptionOrNull()
        assertTrue(error is IOException && error !is TransientAudioLoadFailure)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertNull(request.getHeader("Range"))
        assertEquals(media.mediaVersion, request.requestUrl!!.queryParameter("version"))
        assertEquals(1, server.requestCount)
        assertTrue(store.songs.value.isEmpty())
        assertTrue(root.root.listFiles()!!.isEmpty())
    }

    @Test fun headerDisconnectIsOneObservedAttemptNotATransparentReplay() = fixture { server, api ->
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(response("abcdefgh"))
        assertTrue(runCatching { api.open() }.exceptionOrNull() is TransientAudioLoadFailure)
        assertEquals(1, server.requestCount)
        api.open().use { assertEquals("abcdefgh", it.input.readBytes().decodeToString()) }
        assertEquals(2, server.requestCount)
    }

    @Test fun transientStatusIsSurfacedOnceAndCanReopen() = fixture { server, api ->
        for (status in listOf(408, 500, 502, 503, 504)) {
            server.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After", "0"))
            server.enqueue(response("abcdefgh"))
            assertTrue(runCatching { api.open() }.exceptionOrNull() is TransientAudioLoadFailure)
            api.open().use { assertEquals("abcdefgh", it.input.readBytes().decodeToString()) }
        }
        assertEquals(10, server.requestCount)
    }

    @Test fun authorizationAndInvalidRangesAreNotTransient() = fixture { server, api ->
        for (status in listOf(401, 403, 404, 416, 429)) {
            val observed = mutableListOf<Int>()
            server.enqueue(MockResponse().setResponseCode(status))
            assertTrue(runCatching { api.open(4, headers = { code, _ -> observed.add(code) }) }
                .exceptionOrNull() is AuthFailure)
            assertEquals(listOf(status), observed)
        }
        for (invalid in listOf(
            response("efgh"), // ignored Range
            response("efgh").setResponseCode(206).setHeader("Content-Range", "bytes 3-6/8"),
            response("efgh").setResponseCode(206).setHeader("Content-Range", "bytes 4-6/8"),
            response("efgh").setResponseCode(206).setHeader("Content-Range", "bytes 4-7/8")
                .setHeader("Content-Encoding", "gzip"),
        )) {
            server.enqueue(invalid)
            assertTrue(runCatching { api.open(4) }.exceptionOrNull() is AuthFailure)
        }
        assertEquals(9, server.requestCount)
    }

    @Test fun retryRejectsChangedValidatorsLengthOrIncompleteSuffixBeforeUse() = fixture { server, api ->
        server.enqueue(response("abcdefgh").setHeader("ETag", "\"original\""))
        val original = api.open().use { it.representation }
        val retry = response("efgh").setResponseCode(206).setHeader("Content-Range", "bytes 4-7/8")
            .setHeader("ETag", "\"original\"")
        for (mismatch in listOf(
            retry.clone().setHeader("ETag", "\"replacement\""),
            retry.clone().setHeader("Last-Modified", "Wed, 02 Jan 2030 00:00:00 GMT"),
            retry.clone().setHeader("Content-Range", "bytes 4-7/9"),
            retry.clone().setHeader("Content-Range", "bytes 4-6/8").setBody("efg"),
        )) {
            server.enqueue(mismatch)
            api.open(4).use {
                assertThrows(IOException::class.java) { it.checkContinuation(original, 4, null) }
            }
        }
    }

    @Test fun retryWillNotSpliceBytesWithoutAValidator() = fixture { server, api ->
        server.enqueue(response("abcdefgh").removeHeader("Last-Modified"))
        val original = api.open().use { it.representation }
        server.enqueue(response("efgh").removeHeader("Last-Modified").setResponseCode(206)
            .setHeader("Content-Range", "bytes 4-7/8"))
        api.open(4).use {
            assertThrows(IOException::class.java) { it.checkContinuation(original, 4, null) }
        }
    }

    @Test fun ambiguousOrInvalidIdentityDoesNotAuthorizeAByteSplice() = fixture { server, api ->
        val noDate = response("abcdefgh").removeHeader("Last-Modified")
        for (invalid in listOf(noDate.clone().setHeader("ETag", "not-quoted"),
            noDate.clone().setHeader("ETag", "W/\"weak\""),
            noDate.clone().setHeader("Last-Modified", "invalid date"),
            response("abcdefgh").addHeader("ETag", "\"a\"").addHeader("ETag", "\"b\""))) {
            server.enqueue(invalid)
            api.open().use { assertFalse(it.representation.canContinue) }
        }
        server.enqueue(noDate.clone().setHeader("ETag", "\"opaque\""))
        api.open().use { assertTrue(it.representation.canContinue) }
    }

    @Test fun ordinaryShortAndUnknownTotalRangesRemainReadableButNotExactContinuations() = fixture { server, api ->
        for (total in listOf("*", "12")) {
            server.enqueue(response("ef").setResponseCode(206).setHeader("Content-Range", "bytes 4-5/$total"))
            api.open(4, length = 4).use {
                assertThrows(IOException::class.java) { it.checkContinuation(null, 4, 4) }
                assertEquals("ef", it.input.readBytes().decodeToString())
            }
            assertEquals("bytes=4-7", server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Range"))
        }
    }

    @Test fun cancellingAnOwnedBodyWakesTheReaderWithoutSchedulingAnotherRequest() = fixture { server, api ->
        server.enqueue(response("abcdefgh").setBodyDelay(3, TimeUnit.SECONDS))
        api.open().use { stream ->
            val started = CompletableDeferred<Unit>()
            val read = async(Dispatchers.IO) {
                started.complete(Unit)
                runCatching { stream.input.read() }.exceptionOrNull()
            }
            started.await()
            stream.cancel()
            val failure = withTimeout(1_500) { read.await() }
            assertTrue(failure is IOException && failure !is TransientAudioLoadFailure)
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun retiringTransportDoesNotTurnCancellationIntoRetry() = fixture { server, api ->
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val current = AtomicBoolean(true)
        val authority = FeatureAuthority(Any()) { current.get() }
        val open = async(Dispatchers.IO) { runCatching { api.open(authority = authority) }.exceptionOrNull() }
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        current.set(false)
        api.cancel()
        assertTrue(open.await() is AuthFailure)
        assertTrue(runCatching { api.open(authority = authority) }.exceptionOrNull() is AuthFailure)
        assertEquals(1, server.requestCount)
    }

    @Test fun cancellingCallerCancelsAnOutstandingOpen() = fixture { server, api ->
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val open = async(Dispatchers.IO) { api.open() }
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        open.cancelAndJoin()
        assertTrue(open.isCancelled)
        assertEquals(1, server.requestCount)
    }

    private fun response(payload: String) = MockResponse().setBody(payload)
        .setHeader("Last-Modified", "Tue, 01 Jan 2030 00:00:00 GMT").setHeader("Content-Type", "audio/mpeg")

    private suspend fun OkHttpAuthApi.open(
        position: Long = 0, authority: FeatureAuthority = FeatureAuthority(Any()) { true },
        headers: (Int, String?) -> Unit = { _, _ -> }, length: Long? = null,
    ) = audio("fixture-track", position, length, "spofify_session=fixture; Path=/; HttpOnly", authority, headers)

    private fun fixture(test: suspend kotlinx.coroutines.CoroutineScope.(MockWebServer, OkHttpAuthApi) -> Unit) = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getLoopbackAddress(), 0)
            val api = OkHttpAuthApi(ServerOrigin.parse(server.url("/").toString()))
            try { test(server, api) } finally { api.cancel() }
        }
    }
}
