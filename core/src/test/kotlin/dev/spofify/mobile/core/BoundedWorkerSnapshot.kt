package dev.spofify.mobile.core

/** Failure-only JVM fixture diagnostics. Never emit actual thread names, files, exception data
 * or locals. Output is limited to fixed worker families/states and bounded class.method:line. */
internal object BoundedWorkerSnapshot {
    private const val MAX_THREADS = 12
    private const val MAX_PER_FAMILY = 4
    private const val MAX_FRAMES = 12
    private const val MAX_SYMBOL = 128
    private val symbol = Regex("[A-Za-z0-9_.$]+")

    private enum class Family(val prefix: String) {
        OKHTTP("OkHttp"), MOCK_WEBSERVER("MockWebServer"), DEFAULT_DISPATCHER("DefaultDispatcher-"),
    }
    private class Worker(val family: Family, val state: Thread.State, val frames: Array<StackTraceElement>)

    fun capture(): String = try {
        render(Thread.getAllStackTraces())
    } catch (_: Exception) {
        "worker-snapshot=unavailable"
    }

    internal fun render(stacks: Map<Thread, Array<StackTraceElement>>): String {
        val candidates = stacks.mapNotNull { (thread, frames) ->
            val family = Family.entries.firstOrNull { thread.name.startsWith(it.prefix) } ?: return@mapNotNull null
            Worker(family, thread.state, frames)
        }
        // Reserve room for each family; prefer blocked or actively running workers over idle
        // pools so a large DefaultDispatcher cannot crowd out the HTTP/server participants.
        val selected = Family.entries.flatMap { family ->
            candidates.filter { it.family == family }.sortedBy { priority(it.state) }.take(MAX_PER_FAMILY)
        }.take(MAX_THREADS)
        return "worker-snapshot=${selected.size}" + selected.mapIndexed { index, worker ->
            val frames = worker.frames.take(MAX_FRAMES).joinToString(" <- ") {
                "${safeSymbol(it.className)}.${safeSymbol(it.methodName)}:${it.lineNumber}"
            }
            "\n${worker.family.name}[${index + 1}] state=${worker.state.name} frames=$frames"
        }.joinToString("")
    }

    private fun priority(state: Thread.State): Int = when (state) {
        Thread.State.BLOCKED -> 0
        Thread.State.RUNNABLE -> 1
        Thread.State.TIMED_WAITING -> 2
        Thread.State.WAITING -> 3
        else -> 4
    }

    private fun safeSymbol(value: String): String = when {
        value.length > MAX_SYMBOL -> "[omitted]"
        value == "<init>" || value == "<clinit>" || symbol.matches(value) -> value
        else -> "[omitted]"
    }
}
