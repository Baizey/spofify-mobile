package dev.spofify.mobile.core.http

import java.io.EOFException
import java.io.IOException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** A transport failure safe to retry at the loader's requested byte offset, not an auth failure. */
class TransientAudioLoadFailure internal constructor(cause: IOException? = null) :
    IOException("Audio transfer interrupted", cause)

internal fun IOException.isAudioInterruption(readingKnownLength: Boolean = false): Boolean = when (this) {
    is SSLException -> false
    is SocketTimeoutException, is SocketException, is EOFException, is UnknownHostException -> true
    // OkHttp reports a fixed-length body cut short this way (not a generic malformed response).
    is ProtocolException -> readingKnownLength && message == "unexpected end of stream"
    // HTTP/1 can wrap an EOF while reading response headers.
    else -> cause is EOFException
}

internal fun Int.isTransientAudioStatus(): Boolean = this in setOf(408, 500, 502, 503, 504)
