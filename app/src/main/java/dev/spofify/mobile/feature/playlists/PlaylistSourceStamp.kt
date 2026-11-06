package dev.spofify.mobile.feature.playlists

import dev.spofify.mobile.core.SpofifyClient

/** A read/action belongs to one session incarnation and its confirmed rule inputs, not history. */
internal class PlaylistSourceStamp(private val client: SpofifyClient) {
    private val session = client.currentSession()
    private val revision = client.personalRevision.value

    fun isCurrent(): Boolean {
        val current = client.personalRevision.value
        return session === client.currentSession() &&
            revision.playlists == current.playlists && revision.likes == current.likes
    }
}
