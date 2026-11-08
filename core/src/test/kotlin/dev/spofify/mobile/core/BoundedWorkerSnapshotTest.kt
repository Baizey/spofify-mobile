package dev.spofify.mobile.core

import org.junit.Assert.*
import org.junit.Test

class BoundedWorkerSnapshotTest {
    @Test fun onlyFixedWorkerLabelsAndClassMethodLineAreRendered() {
        val privateName = "https://private.invalid/account Cookie=synthetic-token"
        val frame = StackTraceElement("fixture.Client", "waitForReply", privateName, 42)
        val stacks = linkedMapOf(
            Thread({}, "OkHttp $privateName") to arrayOf(frame),
            Thread({}, "MockWebServer $privateName") to arrayOf(frame),
            Thread({}, "DefaultDispatcher-worker-$privateName") to arrayOf(frame),
            Thread({}, "unrelated-$privateName") to arrayOf(frame),
        )
        val rendered = BoundedWorkerSnapshot.render(stacks)
        assertTrue(rendered.startsWith("worker-snapshot=3"))
        assertTrue(rendered.contains("OKHTTP[1] state=NEW frames=fixture.Client.waitForReply:42"))
        assertTrue(rendered.contains("MOCK_WEBSERVER[2]"))
        assertTrue(rendered.contains("DEFAULT_DISPATCHER[3]"))
        listOf("https:", "private.invalid", "Cookie", "synthetic-token", "unrelated", "DefaultDispatcher-worker-").forEach {
            assertFalse(rendered.contains(it))
        }
    }

    @Test fun threadFrameAndSymbolSizesAreBounded() {
        val frames = Array(30) { StackTraceElement("fixture.Worker", "read", "never-render-this-file", it) }
        val stacks = linkedMapOf<Thread, Array<StackTraceElement>>()
        for (prefix in listOf("OkHttp", "MockWebServer", "DefaultDispatcher-worker-")) {
            repeat(20) { stacks[Thread({}, "$prefix private-$it")] = frames }
        }
        val lines = BoundedWorkerSnapshot.render(stacks).lines()
        assertEquals("worker-snapshot=12", lines.first())
        assertEquals(13, lines.size)
        lines.drop(1).forEach { line ->
            assertEquals(12, line.substringAfter("frames=").split(" <- ").size)
            assertFalse(line.contains("never-render-this-file"))
        }
        val unsafe = arrayOf(StackTraceElement("x".repeat(129), "https://private.invalid", "private-file", 7))
        val rendered = BoundedWorkerSnapshot.render(mapOf(Thread({}, "OkHttp private-name") to unsafe))
        assertTrue(rendered.contains("frames=[omitted].[omitted]:7"))
        assertFalse(rendered.contains("private"))
        assertFalse(rendered.contains("x".repeat(129)))
    }
}
