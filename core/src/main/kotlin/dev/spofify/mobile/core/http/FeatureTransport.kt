package dev.spofify.mobile.core.http

import dev.spofify.mobile.core.auth.FeatureAuthority
import dev.spofify.mobile.core.playback.PlaybackConnection

/** Internal, session-owned transport. Credentials never become feature or UI state. */
internal interface FeatureTransport {
    suspend fun get(
        segments: List<String>,
        query: List<Pair<String, String>>,
        cookie: String,
        artwork: Boolean,
        // Opt-in bounded JSON 503 diagnostics; never enables automatic status replay.
        preserveUnavailableBody: Boolean = false,
        onHeaders: (status: Int, renewedCookie: String?) -> Unit,
    ): FeatureResponse

    suspend fun socket(
        cookie: String,
        hello: String,
        authority: FeatureAuthority,
        onHeaders: (status: Int, renewedCookie: String?) -> Unit,
    ): PlaybackConnection

    suspend fun audio(
        trackId: String,
        position: Long,
        length: Long?,
        cookie: String,
        authority: FeatureAuthority,
        onHeaders: (status: Int, renewedCookie: String?) -> Unit,
    ): AudioStream

    suspend fun post(
        segments: List<String>, body: String, cookie: String, csrfToken: String,
        onHeaders: (status: Int, renewedCookie: String?) -> Unit,
    ): FeatureResponse = mutate(segments, "POST", body, cookie, csrfToken, onHeaders)

    suspend fun mutate(
        segments: List<String>, method: String, body: String, cookie: String, csrfToken: String,
        onHeaders: (status: Int, renewedCookie: String?) -> Unit,
    ): FeatureResponse
}

internal class FeatureResponse(val status: Int, val bytes: ByteArray, val mediaType: String?)
