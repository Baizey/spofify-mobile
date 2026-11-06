package dev.spofify.mobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

/** Shared wire format; unknown server fields never become a source of private error text. */
val ApiJson = Json { ignoreUnknownKeys = true }

/**
 * Listener API transport using the account client's cookie, CSRF, origin and invalidation policy.
 * Reading and parsing stay on the client's OkHttp worker, with cancellation attached through both.
 * Neither this transport nor its client follows redirects or replays mutations.
 */
class ApiTransport(val client: SpofifyClient) {
    suspend fun get(path: List<String>, query: Map<String, String> = emptyMap()): JsonObject =
        send("GET", path, query = query)
            ?: throw ApiException(204, "The server returned an empty JSON response")

    suspend fun send(
        method: String,
        path: List<String>,
        body: JsonObject? = null,
        query: Map<String, String> = emptyMap(),
        playbackExtensions: Boolean = false,
        playbackRegistration: Boolean = false,
    ): JsonObject? {
        require(method in setOf("GET", "HEAD", "OPTIONS", "POST", "PUT", "PATCH", "DELETE")) { "Unsupported API method" }
        require(path.isNotEmpty() && path.none { segment -> segment.isEmpty() || segment == "." || segment == ".." || segment.any { it.isISOControl() } }) { "Invalid API path" }
        require(body == null || method !in setOf("GET", "HEAD")) { "This API method does not accept a body" }
        val url = client.server.url(*path.toTypedArray()).newBuilder().apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val payload = when {
            body != null -> body.toString().toRequestBody(JSON_MEDIA_TYPE)
            method in setOf("POST", "PUT", "PATCH") -> "".toRequestBody(JSON_MEDIA_TYPE)
            else -> null
        }
        val deviceRequest = path == listOf("api", "me", "player")
        require(!(playbackExtensions || playbackRegistration) || deviceRequest)
        val request = Request.Builder().url(url).header("Accept", "application/json").apply {
            if (playbackExtensions) header("X-Spofify-Playback-Extensions", "queue-intents-v1")
            if (playbackRegistration) header("X-Spofify-Playback-Registration", "installation-v1")
            if (deviceRequest) header("X-Spofify-Playback-Leadership", "last-requested-v1")
        }.method(method, payload).build()
        return client.response(request, errorFactory = { safeApiError(it, deviceRequest, playbackExtensions, playbackRegistration) }) { response ->
            if (response.code == 204) null
            else {
                requireJson(response)
                val text = readBounded(response, MAX_RESPONSE_BYTES)
                    ?: throw ApiException(response.code, "The server returned an oversized JSON response")
                try {
                    if (deviceRequest) PlaybackRawJson.parse(text, maxBytes = MAX_RESPONSE_BYTES)
                    else ApiJson.parseToJsonElement(text) as? JsonObject
                        ?: throw IllegalArgumentException()
                } catch (_: IllegalArgumentException) {
                    throw ApiException(response.code, "The server returned an incompatible JSON response")
                }
            }
        }
    }
}

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
// Accommodates thousand-track queues and large recipes without unbounded response allocation.
private const val MAX_RESPONSE_BYTES = 16L * 1024 * 1024
private const val MAX_ERROR_BYTES = 8L * 1024

private fun requireJson(response: Response) {
    if (!isJson(response)) throw ApiException(response.code, "Expected a JSON API response; check the server address and reverse proxy")
}

private fun isJson(response: Response): Boolean {
    val subtype = response.body?.contentType()?.subtype
    return subtype == "json" || subtype?.endsWith("+json") == true
}

/** Read at most limit+1 bytes, including for chunked responses or a dishonest Content-Length. */
private fun readBounded(response: Response, limit: Long): String? {
    val body = response.body ?: return ""
    if (body.contentLength() > limit) return null
    val buffer = Buffer()
    val source = body.source()
    while (buffer.size <= limit) {
        if (source.read(buffer, minOf(8192L, limit + 1 - buffer.size)) == -1L) return buffer.readUtf8()
    }
    return null
}

private val SAFE_ERROR_CODES = setOf(
    "INVALID_REQUEST", "INVALID_CURSOR", "INVALID_SELECTION", "PLAYLIST_NOT_FOUND", "REFERENCE_NOT_FOUND",
    "REVISION_CONFLICT", "STALE_EVALUATION", "PLAYLIST_KIND_MISMATCH", "SOURCE_REFERENCED", "RECIPE_CYCLE",
    "RECIPE_LIMIT_EXCEEDED", "COPY_LIMIT_EXCEEDED", "PLAYLIST_QUOTA_EXCEEDED", "OCCURRENCE_UNAVAILABLE",
    "EVALUATION_BUSY", "EVALUATION_TIMEOUT", "EVALUATION_FAILED",
)

private fun safeApiError(response: Response, deviceRequest: Boolean, playbackExtensions: Boolean, playbackRegistration: Boolean): ApiException {
    // A server's free-form message is never surfaced, even when the typed code is recognized.
    val deviceConflict = deviceRequest && response.code in setOf(404, 409)
    val root = if (isJson(response)) {
        try {
            val text = readBounded(response, if (deviceConflict) 2L * 1024 * 1024 else MAX_ERROR_BYTES)
            text?.let { if (deviceConflict) PlaybackRawJson.parse(it) else ApiJson.parseToJsonElement(it) } as? JsonObject
        } catch (_: IllegalArgumentException) { null }
    } else null
    val error = root?.get("error") as? JsonObject
    val primitive = error?.get("code") as? JsonPrimitive
    val code = primitive?.takeIf { it.isString }?.content?.takeIf { it in SAFE_ERROR_CODES }
    // Error views never deliver directives on the server. Reject them before DTO decoding:
    // directive commands intentionally contain opaque JsonObjects, unsuitable for error retention.
    // All remaining fields use positive DTO/state validation; never retain arbitrary JSON,
    // unknown keys, server error strings, or snapshots failing the device protocol's validation.
    val directives = root?.get("directives") as? JsonArray
    val reconciliation = if (deviceConflict && root != null && directives != null && directives.isEmpty()) {
        try {
            require(root["queueIntents"]?.jsonObject?.get("directives")?.let { it is JsonArray && it.isEmpty() } != false)
            val view = PlaybackDevicesView.fromJson(JsonObject(root.filterKeys { it != "error" }), playbackExtensions, playbackRegistration)
            view.toJson()
        } catch (_: IllegalArgumentException) { null }
    } else null
    val message = when (response.code) {
        401 -> "Your session has expired. Sign in again."
        403 -> "The server refused this request. Check your account permissions."
        404 -> "This item is no longer available"
        409 -> "The item changed on the server. Refresh and try again."
        429 -> "Too many requests. Please try again later."
        in 300..399 -> "The server redirected the request. Enter its direct origin instead."
        in 500..599 -> "The server is temporarily unavailable"
        else -> "The server could not complete the request (HTTP ${response.code})"
    }
    // Preserve exactly one protocol rejection fact only after the authenticated player
    // error view passed validation. Never retain/render arbitrary server error strings.
    val playbackConflictReason = if (response.code == 409 && reconciliation != null &&
        (!playbackExtensions || reconciliation["queueIntents"] is JsonObject) &&
        (root?.get("error") as? JsonPrimitive)?.takeIf { it.isString }?.content == "stale epoch")
        PlaybackConflictReason.STALE_EPOCH else null
    return ApiException(response.code, message, code, reconciliation, playbackConflictReason)
}
