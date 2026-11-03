package dev.spofify.mobile.feature.playlists

import dev.spofify.mobile.core.ApiException

/** A successful HTTP status without a usable acknowledgement may already have committed. */
internal fun playlistWriteUncertain(error: Exception): Boolean = error !is ApiException ||
    error.status in 200..299 || error.status == 408 || error.status >= 500

/** Request tickets are local authority, not server fences. Every changed input invalidates old work. */
internal class PlaylistRequestGeneration {
    private var generation = 0L
    fun invalidate(): Long = ++generation
    fun ticket(): Long = generation
    fun accepts(ticket: Long): Boolean = ticket == generation
}

internal fun <T> movedOccurrence(items: List<T>, from: Int, to: Int): List<T> {
    if (from !in items.indices || to !in items.indices || from == to) return items
    return items.toMutableList().apply { add(to, removeAt(from)) }
}

internal fun playlistMetadataError(name: String, description: String): String? {
    val title = name.trim()
    if (title.isEmpty() || title.codePointCount(0, title.length) > 100 ||
        description.codePointCount(0, description.length) > 500) {
        return "Use a name of 1–100 characters and a description of at most 500 characters."
    }
    if (title.any { Character.isISOControl(it) } || description.any { Character.isISOControl(it) && it != '\n' && it != '\t' }) {
        return "The name and description cannot contain control characters."
    }
    return null
}
