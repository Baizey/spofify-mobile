package dev.spofify.mobile.core

import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap

/** Internal diagnostics, not user-facing copy. These categories contain no request data. */
internal enum class TransportFailurePhase { CALL, RESPONSE_CONSUMER }

/** A fresh, bounded diagnostic snapshot: never retains the original Throwable, its messages,
 * stack, suppressed errors, URL, headers or body. Only JVM throwable class names are copied. */
internal class TransportFailureDiagnostic private constructor(
    val phase: TransportFailurePhase,
    val throwableClasses: List<String>,
) : IOException("phase=${phase.name}; throwableClasses=${throwableClasses.joinToString(">")}") {
    override fun fillInStackTrace(): Throwable = this

    companion object {
        private const val MAX_DEPTH = 4
        private const val MAX_CLASS_NAME = 128
        private val className = Regex("[A-Za-z0-9_.$]+")

        fun capture(error: Throwable, phase: TransportFailurePhase): TransportFailureDiagnostic {
            val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            val classes = mutableListOf<String>()
            var current: Throwable? = error
            while (current != null && classes.size < MAX_DEPTH && seen.add(current)) {
                val name = current.javaClass.name
                classes.add(if (name.length <= MAX_CLASS_NAME && className.matches(name)) name else "[omitted]")
                current = try { current.cause } catch (_: Exception) { null }
            }
            return TransportFailureDiagnostic(phase, classes.toList())
        }
    }
}

internal fun networkError(error: IOException, phase: TransportFailurePhase): IOException {
    // This type already carries one exact, fixed safe internal category/message.
    if (error is NetworkAdmissionException) return error
    return IOException("Unable to reach the server. Check your connection and server address.").apply {
        initCause(TransportFailureDiagnostic.capture(error, phase))
    }
}
