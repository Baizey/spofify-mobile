package dev.spofify.mobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

/** Catalog/personal HTTP format. Playback has its independent strict WebSocket codec. */
val ApiJson = Json { ignoreUnknownKeys = true }

/** One exact-origin account client for HTTP reads/edits; uncertain writes are never replayed. */
class ApiTransport(val client: SpofifyClient) {
    suspend fun get(path: List<String>, query: Map<String, String> = emptyMap()): JsonObject =
        send("GET", path, query = query)
            ?: throw ApiException(204, "The server returned an empty JSON response")

    suspend fun send(method: String, path: List<String>, body: JsonObject? = null,
        query: Map<String, String> = emptyMap()): JsonObject? {
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
        val request = Request.Builder().url(url).header("Accept", "application/json").method(method, payload).build()
        return client.response(request, errorFactory = ::safeApiError) { response ->
            if (response.code == 204) null
            else {
                if (!isJson(response)) throw ApiException(response.code, "Expected a JSON API response; check the server address and reverse proxy")
                val text = readBounded(response, MAX_RESPONSE_BYTES)
                    ?: throw ApiException(response.code, "The server returned an oversized JSON response")
                try { ApiJson.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException() }
                catch (_: IllegalArgumentException) { throw ApiException(response.code, "The server returned an incompatible JSON response") }
            }
        }
    }
}

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private const val MAX_RESPONSE_BYTES = 16L * 1024 * 1024
private const val MAX_ERROR_BYTES = 8L * 1024
private fun isJson(response: Response): Boolean {
    val subtype = response.body?.contentType()?.subtype
    return subtype == "json" || subtype?.endsWith("+json") == true
}

/** Bounded even for chunked data or dishonest Content-Length. */
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
    "RECIPE_LIMIT_EXCEEDED", "COPY_LIMIT_EXCEEDED", "PLAYLIST_QUOTA_EXCEEDED", "PLAYLIST_STORAGE_EXCEEDED", "OCCURRENCE_UNAVAILABLE",
    "EVALUATION_BUSY", "EVALUATION_TIMEOUT", "EVALUATION_FAILED",
)
private fun safeApiError(response: Response): ApiException {
    val root = if (isJson(response)) try {
        readBounded(response, MAX_ERROR_BYTES)?.let { ApiJson.parseToJsonElement(it) } as? JsonObject
    } catch (_: IllegalArgumentException) { null } else null
    val code = ((root?.get("error") as? JsonObject)?.get("code") as? JsonPrimitive)
        ?.takeIf { it.isString }?.content?.takeIf { it in SAFE_ERROR_CODES }
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
    return ApiException(response.code, message, code)
}
