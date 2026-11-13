package dev.spofify.mobile.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import dev.spofify.mobile.core.*
import kotlinx.serialization.json.*
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sin

/** On-device SYNTHETIC UI backend, not real-server protocol/audio acceptance. Never proxies. */
class UiFixtureServer : AutoCloseable {
    init { VirtualAvdOnly.requireVerified() }

    data class SearchCall(val kind: String, val query: String, val limit: Int, val offset: Int)
    data class DetailCall(val kind: String, val id: String)

    val catalogDetails = CopyOnWriteArrayList<DetailCall>()
    val unexpectedRoutes = CopyOnWriteArrayList<String>()
    val searchCalls = CopyOnWriteArrayList<SearchCall>()
    val emptyGlobalBrowseCalls = AtomicInteger()
    val audioRequests = AtomicInteger()
    /** Synthetic server-side selection/observation metrics; never obsolete REST calls. */
    val queueReplacements get() = playerSocket.queueReplacements
    val qualifiedHistoryEvents get() = playerSocket.qualifiedHistoryEvents
    val socketFailures get() = playerSocket.failures
    val selectedOutputName get() = playerSocket.outputName()
    val playlistWrites = AtomicInteger()
    val authenticationFailures = AtomicInteger()
    /** Frame classifications only; never hello, credentials, IDs or raw messages. */
    val playerCalls get() = playerSocket.calls
    private val playerSocket = SyntheticLibrarySocket(::resolveSelection) { id ->
        synchronized(this) { history.remove(id); history.add(0, id) }
    }

    /** Per-hold acknowledgements contain counts only, never input or request payloads. */
    class HeldSearchResponses internal constructor() {
        internal val release = CountDownLatch(1)
        internal val entered = AtomicInteger()
        internal val prepared = AtomicInteger()
        val enteredRequests: Int get() = entered.get()
        val preparedResponses: Int get() = prepared.get()
    }
    @Volatile private var searchGate: HeldSearchResponses? = null

    fun holdSearchResponses(): HeldSearchResponses {
        check(searchGate == null) { "Search responses already held" }
        return HeldSearchResponses().also { searchGate = it }
    }
    fun releaseSearchResponses() {
        val held = searchGate
        searchGate = null
        held?.release?.countDown()
    }

    private val server = MockWebServer()
    private val liked = linkedSetOf("track-02")
    private val history = mutableListOf("track-01", "track-09", "track-13")
    private var sharedSongRows = false

    /** Presentation-only opt-in; never changes catalog totals, history or playlist occurrences. */
    @Synchronized fun setSharedSongRows(enabled: Boolean) { sharedSongRows = enabled }
    @Synchronized fun recentTrackIds(): List<String> =
        if (sharedSongRows) SHARED_SONG_IDS else history.distinct().take(3)
    private var manualName = MANUAL_NAME
    private var manualDescription = "A little music for the way home."
    private var manualRevision = 1L
    private val manualTrackIds = (1..6).map { "track-%02d".format(it) }.toMutableList()
    private val pngs = (0..2).map(::artwork)
    private val wav = makeWav()

    val origin: String get() = "http://127.0.0.1:${server.port}"

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl
                // Never hold the catalog monitor while entering the socket monitor: report
                // history/resolution callbacks take the opposite direction.
                if (url?.encodedPath in listOf("/api/me/player/connect", "/api/me/player/socket"))
                    return routeSocket(request)
                if (url?.encodedPath == "/api/library" && !url.queryParameter("q").isNullOrBlank() &&
                    url.queryParameter("artistId") == null && url.queryParameter("albumId") == null) {
                    // Deterministic loading evidence, without sleeping the instrumentation thread.
                    val gate = searchGate
                    if (gate != null) {
                        gate.entered.incrementAndGet()
                        try {
                            if (!gate.release.await(60, TimeUnit.SECONDS)) {
                                unexpectedRoutes.add("synthetic search response gate timed out")
                                return json(JSONObject().put("error", "Synthetic response gate timed out"), 504)
                            }
                            return synchronized(this@UiFixtureServer) { route(request) }
                        } finally {
                            // Dispatcher completion, not a claim that a cancelled client accepted it.
                            gate.prepared.incrementAndGet()
                        }
                    }
                }
                return synchronized(this@UiFixtureServer) { route(request) }
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
                searchCalls.add(SearchCall(kind, query, limit, offset))
                if (query.equals(ERROR_QUERY, true)) return json(JSONObject().put("error", "Synthetic temporary failure"), 503)
            }
            val sharedQuery = query == SHARED_SONG_QUERY && artistId == null && albumId == null
            val all = when {
                sharedQuery -> if (sharedSongRows && kind == "tracks") SHARED_SONG_IDS.map(::track) else emptyList()
                albumId != null -> tracks().filter { it.getString("albumId") == albumId }
                artistId != null -> if (url.queryParameter("releaseGroup") !in listOf("singles", "appearances"))
                    albums().filter { hasArtist(it, artistId) } else emptyList()
                kind == "artists" -> listOf(artist())
                kind == "albums" -> albums()
                kind == "tracks" -> tracks()
                else -> listOf(artist()) + albums() + tracks()
            }.filter { sharedQuery || artistId != null || albumId != null || it.getString("name").contains(query, ignoreCase = true) }
            return json(page(all, limit, offset))
        }
        val catalog = Regex("/api/library/(artists|albums|tracks)/([^/]+)(?:/(artwork|audio))?").matchEntire(path)
        if (catalog != null && method in listOf("GET", "HEAD")) {
            val kind = catalog.groupValues[1]
            val id = catalog.groupValues[2]
            val action = catalog.groupValues[3]
            val item = when (kind) {
                "artists" -> CREDIT_ARTISTS.firstOrNull { it.first == id }?.let { artist(id) }
                "albums" -> albums().firstOrNull { it.getString("id") == id }
                else -> tracks().firstOrNull { it.getString("id") == id }
            } ?: return unexpected(request)
            return when (action) {
                "artwork" -> if (sharedSongRows && id == SHARED_UNAVAILABLE_ID)
                    json(JSONObject().put("error", "Synthetic artwork absent"), 404)
                else binary(pngs[albumIndex(id)], "image/png", request)
                "audio" -> if (kind == "tracks") { audioRequests.incrementAndGet(); binary(wav, "audio/wav", request) } else unexpected(request)
                "" -> {
                    catalogDetails.add(DetailCall(kind.removeSuffix("s"), id))
                    json(JSONObject().put("item", item).put("revision", 7))
                }
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
            val ids = if (path.endsWith("likes")) liked.toList() else recentTrackIds()
            val limit = url.queryParameter("limit")?.toIntOrNull() ?: 40
            val offset = url.queryParameter("offset")?.toIntOrNull() ?: 0
            return json(page(ids.map { JSONObject().put("track", track(it)) }, limit, offset))
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
        if (method == "GET" && path == "/api/me/playlists/$DERIVED_ID") return json(derived())
        if (method == "GET" && path == "/api/me/playlists/$DERIVED_ID/evaluation") {
            val limit = url.queryParameter("limit")?.toIntOrNull() ?: 40
            if (limit !in 1..100) return unexpected(request)
            val cursor = url.queryParameter("cursor")
            val offset = if (cursor == null) 0 else
                (1..3).firstOrNull { cursor == "synthetic-cursor-$it" } ?: return unexpected(request)
            return json(JSONObject().put("items", JSONArray().apply {
                tracks().take(4).drop(offset).take(limit).forEachIndexed { pageIndex, item ->
                    val index = offset + pageIndex
                    put(JSONObject().put("occurrenceId", "derived-occurrence-$index")
                        .put("selectionToken", "synthetic-selection-$index").put("position", index).put("track", item))
                }
            }).put("total", 4).put("availableTotal", 4).put("limit", limit)
                .put("nextCursor", if (offset + limit < 4) "synthetic-cursor-${offset + limit}" else JSONObject.NULL)
                .put("revision", 1).put("fence", "synthetic-fence").put("complete", true))
        }
        return unexpected(request)
    }

    private fun routeSocket(request: RecordedRequest): MockResponse {
        val url = request.requestUrl ?: return unexpected(request)
        val path = url.encodedPath
        val method = request.method.orEmpty()
        if (request.getHeader("Cookie")?.split(';')?.map(String::trim)
                ?.contains("spofify_session=synthetic-ui-cookie") != true ||
            (method != "GET" && request.getHeader("X-CSRF-Token") != CSRF)) {
            authenticationFailures.incrementAndGet()
            return json(JSONObject().put("error", "Synthetic authentication required"), 401)
        }
        if (path in listOf("/api/me/player/connect", "/api/me/player/socket")) {
            if (url.encodedQuery != null || request.getHeader("Origin") != origin) return unexpected(request)
            if (method == "POST" && path.endsWith("/connect") &&
                request.getHeader("Content-Type")?.substringBefore(';') == "application/json" &&
                request.body.readUtf8() == "{}") {
                return json(JSONObject().put("ticket", playerSocket.ticket()))
                    .setHeader("Cache-Control", "no-store")
            }
            if (method == "GET" && path.endsWith("/socket") &&
                request.headers.values("Sec-WebSocket-Protocol") == listOf(LibraryPlaybackProtocol.SUBPROTOCOL)) {
                return MockResponse().withWebSocketUpgrade(playerSocket.listener())
                    .setHeader("Sec-WebSocket-Protocol", LibraryPlaybackProtocol.SUBPROTOCOL)
            }
            return unexpected(request)
        }
        return unexpected(request)
    }

    private fun session() = JSONObject().put("accountId", "synthetic-listener").put("username", USERNAME).put("role", "user").put("csrfToken", CSRF)
    private fun artist(id: String = ARTIST_ID): JSONObject {
        val name = CREDIT_ARTISTS.single { it.first == id }.second
        val primary = id == ARTIST_ID
        return JSONObject().put("kind", "artist").put("id", id).put("name", name)
            .put("genres", JSONArray(listOf("Ambient", "Electronic"))).put("coverage", JSONObject()
                .put("tracks", if (primary) 16 else 1).put("inLibrary", if (primary) 16 else 1).put("albums", if (primary) 3 else 1))
    }
    private fun albums() = listOf(ALBUM_NAME, "Orbit Afterglow", "Orbit Tides").mapIndexed { index, name ->
        JSONObject().put("kind", "album").put("id", "album-${index + 1}").put("name", name).put("artists", credits(multiple = index == 0))
            .put("albumType", "album").put("releaseDate", "2025-0${index + 1}-14")
            .put("coverage", JSONObject().put("tracks", if (index == 0) 8 else 4).put("inLibrary", if (index == 0) 8 else 4))
    }
    private fun credits(multiple: Boolean = false) = JSONArray().apply {
        (if (multiple) CREDIT_ARTISTS else CREDIT_ARTISTS.take(1)).forEach { (id, name) ->
            put(JSONObject().put("id", id).put("name", name))
        }
    }
    private fun hasArtist(item: JSONObject, id: String): Boolean {
        val artists = item.getJSONArray("artists")
        return (0 until artists.length()).any { artists.getJSONObject(it).getString("id") == id }
    }
    private fun tracks() = (1..16).map { index ->
        val album = when { index <= 8 -> 1; index <= 12 -> 2; else -> 3 }
        JSONObject().put("kind", "track").put("id", "track-%02d".format(index))
            .put("name", TRACK_NAMES[index - 1]).put("artists", credits(multiple = index == 2)).put("albumId", "album-$album")
            .put("albumName", listOf(ALBUM_NAME, "Orbit Afterglow", "Orbit Tides")[album - 1])
            .put("durationMs", AUDIO_SECONDS * 1000).put("hasAudio", true).put("discNumber", 1).put("trackNumber", index)
            .put("genres", JSONArray(listOf("Ambient"))).apply {
                if (sharedSongRows) when (index) {
                    7 -> put("artists", JSONArray())
                    8 -> put("artists", JSONArray().put(JSONObject().put("id", "").put("name", SHARED_UNKNOWN_ARTIST)))
                    15 -> put("hasAudio", false)
                }
            }
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
    /**
     * Fixed synthetic catalog resolver, not the real server's authorization/token implementation.
     * Exact manual/derived row identities must match what this fixture actually served.
     */
    private fun resolveSelection(selection: JsonObject, intent: String): SyntheticLibrarySocket.Selection = synchronized(this) {
        fun reject(code: String = "unavailable"): Nothing = throw SyntheticLibrarySocket.Rejected(code)
        val kind = selection.getValue("kind").jsonPrimitive.content
        val requested = selection["context"]?.jsonObject
        val ref = selection["track"]?.jsonObject
        var canonical: LibraryPlaybackContext? = null
        val items: List<JSONObject> = if (requested == null) {
            if (kind != "track") reject("invalid")
            listOf(track(ref!!.getValue("id").jsonPrimitive.content))
        } else {
            val contextKind = requested.getValue("kind").jsonPrimitive.content
            val id = requested.getValue("id").jsonPrimitive.content
            when (contextKind) {
                "album" -> {
                    val album = albums().singleOrNull { it.getString("id") == id } ?: reject()
                    canonical = LibraryPlaybackContext("album", id, album.getString("name"), 7)
                    tracks().filter { it.getString("albumId") == id }
                }
                "artist" -> {
                    if (CREDIT_ARTISTS.none { it.first == id }) reject()
                    canonical = LibraryPlaybackContext("artist", id, artist(id).getString("name"), 7)
                    tracks().filter { hasArtist(it, id) }
                }
                "likes" -> {
                    if (id != "liked-songs") reject("invalid")
                    canonical = LibraryPlaybackContext("likes", id, "Liked songs")
                    liked.map(::track)
                }
                "playlist" -> {
                    if (id != MANUAL_ID) reject()
                    canonical = LibraryPlaybackContext("playlist", id, manualName)
                    manualTrackIds.mapIndexed { index, trackId -> track(trackId).put("entryId", "entry-${index + 1}") }
                }
                "derived" -> {
                    if (id != DERIVED_ID || requested["revision"]?.jsonPrimitive?.long != 1L ||
                        requested["fence"]?.jsonPrimitive?.content != "synthetic-fence") reject("stale_revision")
                    canonical = LibraryPlaybackContext("derived", id, DERIVED_NAME, 1, "synthetic-fence")
                    tracks().take(4).mapIndexed { index, item ->
                        item.put("targetId", id).put("occurrenceId", "derived-occurrence-$index")
                    }
                }
                else -> reject("invalid")
            }
        }
        val mapped = items.map { item ->
            val artists = item.getJSONArray("artists")
            LibraryPlaybackTrack(item.getString("id"), item.getString("name"),
                (0 until artists.length()).map { index -> artists.getJSONObject(index).let {
                    LibraryPlaybackArtist(it.getString("name"), it.optString("id").takeIf(String::isNotEmpty))
                } }, item.getString("albumName"), item.getString("albumId"), item.getLong("durationMs") / 1000.0,
                1, 1, intent, item.optString("entryId").takeIf(String::isNotEmpty),
                item.optString("targetId").takeIf(String::isNotEmpty),
                item.optString("occurrenceId").takeIf(String::isNotEmpty),
                if (canonical?.kind == "derived") canonical else null)
        }
        val selected = if (ref == null) 0 else {
            val matches = mapped.indices.filter { index ->
                val item = mapped[index]
                item.id == ref.getValue("id").jsonPrimitive.content &&
                    (canonical?.kind != "playlist" || item.entryId == ref["entryId"]?.jsonPrimitive?.content) &&
                    (canonical?.kind != "derived" || (ref["targetId"]?.jsonPrimitive?.content == canonical?.id &&
                        item.occurrenceId == ref["occurrenceId"]?.jsonPrimitive?.content &&
                        ref["selectionToken"]?.jsonPrimitive?.content == "synthetic-selection-$index"))
            }
            matches.singleOrNull() ?: reject()
        }
        if (mapped.isEmpty()) reject()
        if (ref != null && intent != "now") SyntheticLibrarySocket.Selection(listOf(mapped[selected]), canonical, 0)
        else SyntheticLibrarySocket.Selection(mapped, canonical, selected)
    }

    private fun page(items: List<JSONObject>, limit: Int, offset: Int) = JSONObject()
        .put("items", JSONArray(items.drop(offset).take(limit))).put("total", items.size).put("limit", limit).put("offset", offset).put("revision", 7)
    private fun conflict() = json(JSONObject().put("error", JSONObject().put("code", "REVISION_CONFLICT")
        .put("message", "Synthetic revision changed")), 409)
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

    override fun close() {
        releaseSearchResponses()
        try { playerSocket.close() } finally { server.shutdown() }
    }

    companion object {
        const val USERNAME = "fixture-listener"
        const val PASSWORD = "synthetic-ui-password"
        private const val CSRF = "synthetic-ui-csrf"
        const val ARTIST_ID = "artist-orbit"
        const val ARTIST_NAME = "Orbit Ensemble"
        // Credit-only aliases keep the root catalog at one searchable artist / three albums /
        // sixteen tracks, while each of the four displayed credits has a real detail identity.
        val CREDIT_ARTISTS = listOf(
            ARTIST_ID to ARTIST_NAME,
            "artist-credit-mira" to "Mira 夜航",
            "artist-credit-sol" to "Sol & the Northern Lights Ensemble",
            "artist-credit-noe" to "Noé Álvarez · 遠い海",
        )
        const val ALBUM_NAME = "Orbit Bloom"
        const val MANUAL_ID = "night-walk"
        const val MANUAL_NAME = "Night walk"
        const val DERIVED_ID = "ambient-rules"
        const val DERIVED_NAME = "Ambient discoveries"
        const val ERROR_QUERY = "fixture-error"
        const val SHARED_SONG_QUERY = "fixture-shared-song-rows"
        const val SHARED_UNAVAILABLE_ID = "track-15"
        const val SHARED_UNKNOWN_ARTIST = "Unlinked credit · 海辺の音楽 🌙"
        // Exactly Home's eight-row bound, with the SAME ordered objects returned by Search.
        // The first six manual-playlist tracks, entry IDs/order and normal recents stay untouched.
        val SHARED_SONG_IDS = listOf("track-01", "track-02", "track-07", "track-08",
            SHARED_UNAVAILABLE_ID, "track-16", "track-09", "track-13")
        val SHARED_SONG_NAMES: List<String> get() = SHARED_SONG_IDS.map { TRACK_NAMES[it.takeLast(2).toInt() - 1] }
        // Leave room for a slow software-rendered AVD without a natural track transition mid-assertion.
        private const val AUDIO_SECONDS = 600
        const val AUDIO_DURATION_MS = AUDIO_SECONDS * 1000L
        val TRACK_NAMES = listOf("Orbit Dawn", "Orbit Glass", "Orbit Lantern", "Orbit Still", "Orbit Leaves", "Orbit Rain",
            "Orbit Night", "Orbit Blue", "Orbit Ember", "Orbit Glow", "Orbit Haze", "Orbit Light", "Orbit Shore", "Orbit Foam", "Orbit Current",
            "Orbit Return — 夜の航路 · a slow journey through the northern lights")
    }
}
