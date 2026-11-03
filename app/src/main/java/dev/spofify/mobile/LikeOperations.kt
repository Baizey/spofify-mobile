package dev.spofify.mobile

import dev.spofify.mobile.core.SpofifyClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Main-dispatcher confined, single-flight per track; reads cannot overwrite newer writes. */
internal class LikeOperations(
    private val scope: CoroutineScope,
    private val currentClient: () -> SpofifyClient?,
    private val changed: (String, Boolean) -> Unit,
    private val pending: (String, Boolean) -> Unit,
    private val failed: (Exception) -> Unit,
    private val statusFailed: (String) -> Unit = {},
) {
    private val versions = mutableMapOf<String, Long>()
    private val reads = mutableMapOf<String, Job>()
    private val writes = mutableSetOf<String>()
    private var owner: SpofifyClient? = null

    private fun activeClient(): SpofifyClient? {
        val client = currentClient()
        if (owner !== client) {
            reads.values.forEach { it.cancel() }
            reads.clear(); writes.clear(); versions.clear()
            owner = client
        }
        return client
    }

    fun check(trackId: String) {
        val client = activeClient() ?: return
        if (reads[trackId]?.isActive == true || trackId in writes) return
        val version = versions[trackId] ?: 0L
        reads[trackId] = scope.launch {
            try {
                val liked = client.likeStatus(trackId)
                if (currentClient() === client && (versions[trackId] ?: 0L) == version) changed(trackId, liked)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (currentClient() === client && (versions[trackId] ?: 0L) == version) statusFailed(trackId)
            }
        }
    }

    fun toggle(trackId: String, old: Boolean) {
        val client = activeClient() ?: return
        if (!writes.add(trackId)) return
        val version = (versions[trackId] ?: 0L) + 1
        versions[trackId] = version
        reads.remove(trackId)?.cancel()
        pending(trackId, true)
        scope.launch {
            try {
                client.setLiked(trackId, !old)
                if (currentClient() === client && versions[trackId] == version) changed(trackId, !old)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (currentClient() === client) failed(error)
            } finally {
                if (owner === client) writes.remove(trackId)
                if (currentClient() === client) pending(trackId, false)
            }
        }
    }
}
