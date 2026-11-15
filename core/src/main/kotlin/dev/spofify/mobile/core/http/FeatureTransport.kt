package dev.spofify.mobile.core.http

/** Internal, session-owned transport. Credentials never become feature or UI state. */
internal interface FeatureTransport {
    suspend fun get(
        segments: List<String>,
        query: List<Pair<String, String>>,
        cookie: String,
        artwork: Boolean,
        onHeaders: (status: Int, renewedCookie: String?) -> Unit,
    ): FeatureResponse
}

internal class FeatureResponse(val status: Int, val bytes: ByteArray, val mediaType: String?)
