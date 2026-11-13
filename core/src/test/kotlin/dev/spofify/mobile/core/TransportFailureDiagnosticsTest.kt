package dev.spofify.mobile.core

import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.net.SocketException
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class TransportFailureDiagnosticsTest {
    private val secret = "password=synthetic-secret https://private.invalid/path Cookie: private-token raw-body"
    private class MessageTrap : IOException() {
        override val message: String get() = throw AssertionError("Original message must never be inspected")
    }

    @Test fun callFailurePreservesOnlyBoundedClassChainAndFixedPublicMessage() {
        val original = SocketTimeoutException(secret).apply {
            initCause(SocketException(secret))
            addSuppressed(IOException(secret))
            stackTrace = arrayOf(StackTraceElement("PrivateClass", "privateMethod", secret, 1))
        }
        val safe = networkError(original, TransportFailurePhase.CALL)
        assertEquals("Unable to reach the server. Check your connection and server address.", safe.message)
        val diagnostic = safe.cause as TransportFailureDiagnostic
        assertEquals(TransportFailurePhase.CALL, diagnostic.phase)
        assertEquals(listOf("java.net.SocketTimeoutException", "java.net.SocketException"), diagnostic.throwableClasses)
        assertNotSame(original, diagnostic)
        assertNull(diagnostic.cause)
        assertTrue(diagnostic.suppressed.isEmpty())
        assertTrue(diagnostic.stackTrace.isEmpty())
        assertTrue(safe.suppressed.isEmpty())
        val rendered = StringWriter().also { safe.printStackTrace(PrintWriter(it)) }.toString()
        listOf("synthetic-secret", "private.invalid", "private-token", "raw-body", "PrivateClass").forEach {
            assertFalse(rendered.contains(it))
        }
    }

    @Test fun messageGettersAreNeverConsultedAndResponseConsumerPhaseIsDistinct() {
        val original = MessageTrap()
        assertTrue("Fixture class name must fit the diagnostic's unchanged byte budget", original.javaClass.name.length <= 128)
        val safe = networkError(original, TransportFailurePhase.RESPONSE_CONSUMER)
        val diagnostic = safe.cause as TransportFailureDiagnostic
        assertEquals(TransportFailurePhase.RESPONSE_CONSUMER, diagnostic.phase)
        assertEquals(listOf(original.javaClass.name), diagnostic.throwableClasses)
        assertNull(diagnostic.cause)
    }

    @Test fun deepAndCyclicCausesAreBoundedWithoutKeepingOriginalThrowables() {
        val chain = List(8) { IOException(secret) }
        chain.zipWithNext().forEach { (previous, next) -> previous.initCause(next) }
        val bounded = TransportFailureDiagnostic.capture(chain.first(), TransportFailurePhase.CALL)
        assertEquals(4, bounded.throwableClasses.size)
        assertTrue(bounded.throwableClasses.all { it.length <= 128 })
        assertNull(bounded.cause)
        assertFalse(bounded.toString().contains(secret))

        val first = IOException(secret)
        val second = IOException(secret)
        first.initCause(second)
        second.initCause(first)
        val cyclic = TransportFailureDiagnostic.capture(first, TransportFailurePhase.CALL)
        assertEquals(listOf("java.io.IOException", "java.io.IOException"), cyclic.throwableClasses)
        assertNull(cyclic.cause)
        assertTrue(cyclic.suppressed.isEmpty())
    }

    @Test fun deliberateAdmissionRejectionKeepsItsExactSafeType() {
        val rejected = NetworkAdmissionException()
        assertSame(rejected, networkError(rejected, TransportFailurePhase.CALL))
        assertSame(rejected, networkError(rejected, TransportFailurePhase.RESPONSE_CONSUMER))
        assertNull(rejected.cause)
        assertTrue(rejected.suppressed.isEmpty())
    }
}
