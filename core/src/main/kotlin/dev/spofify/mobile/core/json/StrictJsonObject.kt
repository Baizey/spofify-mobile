package dev.spofify.mobile.core.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Shared structural check only; each resource still owns its field/type/domain validation. */
internal object StrictJsonObject {
    fun parse(bytes: ByteArray, maxBytes: Int, maxDepth: Int = 32): JsonObject {
        require(maxBytes > 0 && maxDepth > 0 && bytes.size <= maxBytes)
        val source = bytes.decodeToString(throwOnInvalidSequence = true)
        rejectDuplicateKeys(source, maxDepth)
        return Json.parseToJsonElement(source) as? JsonObject ?: error("Invalid document")
    }

    // JsonObject normally keeps the last duplicate, including escaped aliases.
    private fun rejectDuplicateKeys(source: String, maxDepth: Int) {
        data class Frame(val keys: MutableSet<String>?, var expectingKey: Boolean)
        val stack = mutableListOf<Frame>()
        var index = 0
        while (index < source.length) {
            when (source[index]) {
                '{' -> { require(stack.size < maxDepth); stack.add(Frame(mutableSetOf(), true)) }
                '[' -> { require(stack.size < maxDepth); stack.add(Frame(null, false)) }
                '}', ']' -> { require(stack.isNotEmpty()); stack.removeAt(stack.lastIndex) }
                ',' -> stack.lastOrNull()?.let { it.expectingKey = it.keys != null }
                '"' -> {
                    val start = index++
                    while (index < source.length && source[index] != '"') {
                        if (source[index] == '\\') index++
                        index++
                    }
                    require(index < source.length)
                    stack.lastOrNull()?.let { frame ->
                        if (frame.expectingKey) {
                            val key = Json.decodeFromString<String>(source.substring(start, index + 1))
                            require(frame.keys!!.add(key))
                            frame.expectingKey = false
                        }
                    }
                }
            }
            index++
        }
        require(stack.isEmpty())
    }
}
