package dev.spofify.mobile.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sin

/** Entirely on-device synthetic listener backend. Never contacts or proxies any real server. */
class UiFixtureServer : AutoCloseable {
    data class SearchCall(val kind: String, val query: String, val limit: Int)

    val unexpectedRoutes = CopyOnWriteArrayList<String>()
    val searchCalls = CopyOnWriteArrayList<SearchCall>()
    val emptyGlobalBrowseCalls = AtomicInteger()
    val audioRequests = AtomicInteger()
    val historyWrites = AtomicInteger()
    val playlistWrites = AtomicInteger()
    val authenticationFailures = AtomicInteger()
    @Volatile var searchBodyDelayMs: Long = 0

    private val server = MockWebServer()
    private val liked = linkedSetOf("track-02")
    private val history = mutableListOf("track-01", "track-09", "track-13")
    private var manualName = MANUAL_NAME
    private var manualDescription = "A little music for the way home."
    private var manualRevision = 1L
    private val manualTrackIds = (1..6).map { "track-%02d".format(it) }.toMutableList()
    private val pngs = (0..2).map(::artwork)
    private val wav = makeWav()

    val origin: String get() = "http://127.0.0.1:${server.port}"

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this@UiFixtureServer) {
                route(request)
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    private fun route(request: RecordedRequest): MockResponse {
        val url = request.requestUrl ?: return unexpected(request)
        val path = url.encodedPath
        val method = request.method.orEmpty()
        if (method == "POST" && path == "/api/auth/login") {
            val body = runCatching { JSONObject(request.body.readUtf8()) }.getOrNull()
            if (body?.optString("username") != USERNAME || body?.optString("password") != PASSWORD) {
                authenticationFailures.incrementAndGet()
                return json(JSONObject().put("error", "Synthetic sign-in was not accepted"), 401)
            }
            return json(session()).setHeader("Set-Cookie", "spofify_session=synthetic-ui-cookie; Path=/; HttpOnly; Max-Age=3600")
        }
        if (request.getHeader("Cookie")?.contains("spofify_session=synthetic-ui-cookie") != true ||
            (method !in listOf("GET", "HEAD") && request.getHeader("X-CSRF-Token") != CSRF)) {
            authenticationFailures.incrementAndGet()
            return json(JSONObject().put("error", "Synthetic authentication required"), 401)
        }
        if (method == "GET" && path == "/api/auth/session") return json(session())
        if (method == "POST" && path == "/api/auth/logout") return MockResponse().setResponseCode(204)
        if (method == "GET" && path == "/api/library/revision") return json(JSONObject().put("revision", 7))
        if (method == "GET" && path == "/api/library") {
            val kind = url.queryParameter("kind") ?: "all"
            val limit = url.queryParameter("limit")?.toIntOrNull() ?: 40
            val offset = url.queryParameter("offset")?.toIntOrNull() ?: 0
            val artistId = url.queryParameter("artistId")
            val albumId = url.queryParameter("albumId")
            val query = url.queryParameter("q").orEmpty()
            if (artistId == null && albumId == null) {
                if (query.isBlank()) {
                    emptyGlobalBrowseCalls.incrementAndGet()
                    return json(JSONObject().put("error", "Empty global browse is forbidden in this fixture"), 400)
                }
                searchCalls.add(SearchCall(kind, query, limit))
                if (query.equals(ERROR_QUERY, true)) return json(JSONObject().put("error", "Synthetic temporary failure"), 503)
            }
            val all = when {
                albumId != null -> tracks().filter { it.getString("albumId") == albumId }
                artistId != null -> albums()
                kind == "artists" -> listOf(artist())
                kind == "albums" -> albums()
                kind == "tracks" -> tracks()
                else -> listOf(artist()) + albums() + tracks()
            }.filter { artistId != null || albumId != null || it.getString("name").contains(query, ignoreCase = true) }
            return json(page(all, limit, offset)).also {
                if (artistId == null && albumId == null && searchBodyDelayMs > 0) it.setBodyDelay(searchBodyDelayMs, TimeUnit.MILLISECONDS)
            }
        }
        val catalog = Regex("/api/library/(artists|albums|tracks)/([^/]+)(?:/(artwork|audio|playback))?").matchEntire(path)
        if (catalog != null && method in listOf("GET", "HEAD")) {
            val kind = catalog.groupValues[1]
            val id = catalog.groupValues[2]
            val action = catalog.groupValues[3]
            val item = when (kind) {
                "artists" -> artist().takeIf { id == ARTIST_ID }
                "albums" -> albums().firstOrNull { it.getString("id") == id }
                else -> tracks().firstOrNull { it.getString("id") == id }
            } ?: return unexpected(request)
            return when (action) {
                "artwork" -> binary(pngs[albumIndex(id)], "image/png", request)
                "audio" -> if (kind == "tracks") { audioRequests.incrementAndGet(); binary(wav, "audio/wav", request) } else unexpected(request)
                "playback" -> {
                    val items = if (kind == "artists") tracks() else tracks().filter { it.getString("albumId") == id }
                    json(playback(items, kind.removeSuffix("s"), id, item.getString("name")))
                }
                "" -> json(JSONObject().put("item", item).put("revision", 7))
                else -> unexpected(request)
            }
        }
        if (method == "POST" && path == "/api/me/likes/status") {
            val ids = JSONObject(request.body.readUtf8()).getJSONArray("trackIds")
            return json(JSONObject().put("items", JSONArray().apply {
                for (index in 0 until ids.length()) put(JSONObject().put("trackId", ids.getString(index)).put("liked", ids.getString(index) in liked))
            }))
        }
        if (method == "PUT" && path == "/api/me/likes") {
            val body = JSONObject(request.body.readUtf8())
            if (body.getBoolean("liked")) liked.add(body.getString("trackId")) else liked.remove(body.getString("trackId"))
            return MockResponse().setResponseCode(204)
        }
        if (method == "GET" && path in listOf("/api/me/likes", "/api/me/recent")) {
            val ids = if (path.endsWith("likes")) liked.toList() else history.distinct().take(3)
            val limit = url.queryParameter("limit")?.toIntOrNull() ?: 40
            val offset = url.queryParameter("offset")?.toIntOrNull() ?: 0
            return json(page(ids.map { JSONObject().put("track", track(it)) }, limit, offset))
        }
        if (method == "GET" && path == "/api/me/likes/playback") return json(playback(liked.map(::track), "likes", "likes", "Liked songs"))
        if (method == "POST" && path == "/api/me/history") {
            val id = JSONObject(request.body.readUtf8()).getString("trackId")
            history.remove(id); history.add(0, id); historyWrites.incrementAndGet()
            return MockResponse().setResponseCode(204)
        }
        if (method == "GET" && path == "/api/me/playlists") {
            val kind = url.queryParameter("kind")
            val items = when (kind) { "manual" -> listOf(manual()); "derived" -> listOf(derived()); else -> listOf(manual(), derived()) }
            return json(page(items, url.queryParameter("limit")?.toIntOrNull() ?: 40, url.queryParameter("offset")?.toIntOrNull() ?: 0))
        }
        if (path == "/api/me/playlists/$MANUAL_ID") {
            if (method == "GET") return json(manual())
            if (method == "PATCH") {
                val body = JSONObject(request.body.readUtf8())
                if (body.getLong("expectedRevision") != manualRevision) return conflict()
                manualName = body.getString("name"); manualDescription = body.getString("description")
                manualRevision++; playlistWrites.incrementAndGet()
                return json(manual())
            }
        }
        if (method == "GET" && path == "/api/me/playlists/$MANUAL_ID/entries") {
            val entries = manualTrackIds.mapIndexed { index, id -> JSONObject().put("id", "entry-${index + 1}").put("position", index).put("track", track(id)) }
            return json(page(entries, url.queryParameter("limit")?.toIntOrNull() ?: 40, url.queryParameter("offset")?.toIntOrNull() ?: 0))
        }
        if (method == "GET" && path == "/api/me/playlists/$MANUAL_ID/playback") {
            val items = manualTrackIds.mapIndexed { index, id -> track(id).put("entryId", "entry-${index + 1}") }
            return json(playback(items, "playlist", MANUAL_ID, manualName).put("revision", manualRevision)
                .put("context", JSONObject().put("kind", "playlist").put("id", MANUAL_ID).put("name", manualName).put("revision", manualRevision)))
        }
        if (method == "GET" && path == "/api/me/playlists/$DERIVED_ID") return json(derived())
        if (method == "GET" && path == "/api/me/playlists/$DERIVED_ID/evaluation") {
            return json(JSONObject().put("items", JSONArray().apply {
                tracks().take(4).forEachIndexed { index, item -> put(JSONObject().put("occurrenceId", "derived-occurrence-$index")
                    .put("selectionToken", "synthetic-selection-$index").put("position", index).put("track", item)) }
            }).put("total", 4).put("availableTotal", 4).put("limit", 40).put("nextCursor", JSONObject.NULL)
                .put("revision", 1).put("fence", "synthetic-fence").put("complete", true))
        }
        if (method == "GET" && path == "/api/me/player") return json(devices())
        return unexpected(request)
    }

    private fun session() = JSONObject().put("accountId", "synthetic-listener").put("username", USERNAME).put("role", "user").put("csrfToken", CSRF)
    private fun artist() = JSONObject().put("kind", "artist").put("id", ARTIST_ID).put("name", ARTIST_NAME)
        .put("genres", JSONArray(listOf("Ambient", "Electronic"))).put("coverage", JSONObject().put("tracks", 16).put("inLibrary", 16).put("albums", 3))
    private fun albums() = listOf(ALBUM_NAME, "Orbit Afterglow", "Orbit Tides").mapIndexed { index, name ->
        JSONObject().put("kind", "album").put("id", "album-${index + 1}").put("name", name).put("artists", credits())
            .put("albumType", "album").put("releaseDate", "2025-0${index + 1}-14")
            .put("coverage", JSONObject().put("tracks", if (index == 0) 8 else 4).put("inLibrary", if (index == 0) 8 else 4))
    }
    private fun credits() = JSONArray().put(JSONObject().put("id", ARTIST_ID).put("name", ARTIST_NAME))
    private fun tracks() = (1..16).map { index ->
        val album = when { index <= 8 -> 1; index <= 12 -> 2; else -> 3 }
        JSONObject().put("kind", "track").put("id", "track-%02d".format(index))
            .put("name", TRACK_NAMES[index - 1]).put("artists", credits()).put("albumId", "album-$album")
            .put("albumName", listOf(ALBUM_NAME, "Orbit Afterglow", "Orbit Tides")[album - 1])
            .put("durationMs", AUDIO_SECONDS * 1000).put("hasAudio", true).put("discNumber", 1).put("trackNumber", index)
            .put("genres", JSONArray(listOf("Ambient")))
    }
    private fun track(id: String) = tracks().first { it.getString("id") == id }
    private fun manual() = JSONObject().put("id", MANUAL_ID).put("name", manualName).put("description", manualDescription)
        .put("kind", "manual").put("countState", "exact").put("trackCount", manualTrackIds.size)
        .put("availableCount", manualTrackIds.size).put("revision", manualRevision)
        .put("capabilities", JSONObject().put("editMetadata", true).put("editEntries", true).put("editDefinition", false)
            .put("play", true).put("copyToManual", false).put("delete", true).put("addTracks", true))
    private fun derived() = JSONObject().put("id", DERIVED_ID).put("name", DERIVED_NAME).put("description", "Saved ambient music, gathered by live rules.")
        .put("kind", "derived").put("countState", "unevaluated").put("trackCount", JSONObject.NULL).put("availableCount", JSONObject.NULL).put("revision", 1)
        .put("capabilities", JSONObject().put("editMetadata", true).put("editEntries", false).put("editDefinition", true)
            .put("play", true).put("copyToManual", true).put("delete", true).put("addTracks", false))
    private fun devices() = JSONObject().put("deviceId", "").put("devices", JSONArray().put(JSONObject()
        .put("deviceId", "synthetic-speaker").put("name", "Studio speaker").put("available", true)))
        .put("activeDeviceId", "synthetic-speaker").put("epoch", 1).put("leaseMs", 0).put("quarantineMs", 0)
        .put("state", JSONObject.NULL).put("stateSeq", 0).put("stateAgeMs", 0).put("transfer", JSONObject.NULL)
        .put("directives", JSONArray()).put("commands", JSONArray()).put("error", "")
    private fun playback(items: List<JSONObject>, kind: String, id: String, name: String) = JSONObject()
        .put("items", JSONArray(items)).put("total", items.size).put("limit", 1000).put("truncated", false)
        .put("revision", 7).put("context", JSONObject().put("kind", kind).put("id", id).put("name", name))
    private fun page(items: List<JSONObject>, limit: Int, offset: Int) = JSONObject()
        .put("items", JSONArray(items.drop(offset).take(limit))).put("total", items.size).put("limit", limit).put("offset", offset).put("revision", 7)
    private fun conflict() = json(JSONObject().put("code", "REVISION_CONFLICT").put("error", "Synthetic revision changed"), 409)
    private fun json(value: JSONObject, status: Int = 200) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json; charset=utf-8").setBody(value.toString())

    private fun unexpected(request: RecordedRequest): MockResponse {
        // No bodies, headers, query strings, credentials, private paths, or remote URLs in logs.
        val safe = "${request.method.orEmpty()} ${request.requestUrl?.encodedPath.orEmpty()}"
        unexpectedRoutes.add(safe)
        Log.w("NativeUiFixture", safe)
        return json(JSONObject().put("error", "Unexpected synthetic fixture route"), 404)
    }

    private fun binary(content: ByteArray, type: String, request: RecordedRequest): MockResponse {
        val response = MockResponse().setHeader("Content-Type", type).setHeader("Accept-Ranges", "bytes")
        if (request.method == "HEAD") return response.setHeader("Content-Length", content.size)
        val range = request.getHeader("Range")
        if (range == null) return response.setBody(Buffer().write(content))
        val match = Regex("bytes=(\\d*)-(\\d*)").matchEntire(range)
            ?: return response.setResponseCode(416).setHeader("Content-Range", "bytes */${content.size}")
        val first = match.groupValues[1]
        val last = match.groupValues[2]
        val start = if (first.isEmpty()) (content.size - (last.toLongOrNull() ?: 0)).coerceAtLeast(0) else first.toLongOrNull() ?: -1
        val end = if (first.isEmpty() || last.isEmpty()) content.lastIndex.toLong() else (last.toLongOrNull() ?: -1).coerceAtMost(content.lastIndex.toLong())
        if (start !in 0..content.lastIndex.toLong() || end < start) return response.setResponseCode(416).setHeader("Content-Range", "bytes */${content.size}")
        return response.setResponseCode(206).setHeader("Content-Range", "bytes $start-$end/${content.size}")
            .setBody(Buffer().write(content, start.toInt(), (end - start + 1).toInt()))
    }

    private fun albumIndex(id: String): Int = when {
        id == "album-2" || id in (9..12).map { "track-%02d".format(it) } -> 1
        id == "album-3" || id in (13..16).map { "track-%02d".format(it) } -> 2
        else -> 0
    }
    private fun artwork(index: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val backgrounds = intArrayOf(Color.rgb(51, 66, 95), Color.rgb(117, 66, 57), Color.rgb(31, 96, 91))
        canvas.drawColor(backgrounds[index])
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(239, 194, 126) }
        canvas.drawCircle(160f, 140f, 80f, paint)
        paint.color = backgrounds[index]
        canvas.drawCircle(190f, 120f, 76f, paint)
        paint.color = Color.rgb(126, 207, 181)
        canvas.drawRect(0f, 238f, 320f, 320f, paint)
        paint.color = Color.rgb(41, 72, 70)
        canvas.drawCircle(82f, 322f, 105f, paint)
        return ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            bitmap.recycle()
            output.toByteArray()
        }
    }
    private fun makeWav(): ByteArray {
        val rate = 8_000
        val samples = rate * AUDIO_SECONDS
        val dataSize = samples * 2
        return ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + dataSize); put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16); putShort(1.toShort()); putShort(1.toShort()); putInt(rate); putInt(rate * 2)
            putShort(2.toShort()); putShort(16.toShort()); put("data".toByteArray(Charsets.US_ASCII)); putInt(dataSize)
            repeat(samples) { sample -> putShort((sin(sample * 2.0 * Math.PI * 220 / rate) * 300).toInt().toShort()) }
        }.array()
    }

    override fun close() { server.shutdown() }

    companion object {
        const val USERNAME = "fixture-listener"
        const val PASSWORD = "synthetic-ui-password"
        private const val CSRF = "synthetic-ui-csrf"
        const val ARTIST_ID = "artist-orbit"
        const val ARTIST_NAME = "Orbit Ensemble"
        const val ALBUM_NAME = "Orbit Bloom"
        const val MANUAL_ID = "night-walk"
        const val MANUAL_NAME = "Night walk"
        const val DERIVED_ID = "ambient-rules"
        const val DERIVED_NAME = "Ambient discoveries"
        const val ERROR_QUERY = "fixture-error"
        private const val AUDIO_SECONDS = 120
        val TRACK_NAMES = listOf("Orbit Dawn", "Orbit Glass", "Orbit Lantern", "Orbit Still", "Orbit Leaves", "Orbit Rain",
            "Orbit Night", "Orbit Blue", "Orbit Ember", "Orbit Glow", "Orbit Haze", "Orbit Light", "Orbit Shore", "Orbit Foam", "Orbit Current", "Orbit Return")
    }
}
