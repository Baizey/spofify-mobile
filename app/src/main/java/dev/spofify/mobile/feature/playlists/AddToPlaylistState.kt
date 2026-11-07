package dev.spofify.mobile.feature.playlists

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.spofify.mobile.core.ApiException
import dev.spofify.mobile.core.ApiJson
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.PersonalCollectionsApi
import dev.spofify.mobile.core.PersonalPlaylistPage
import dev.spofify.mobile.core.PersonalPlaylistSummary
import dev.spofify.mobile.core.PlaylistKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

internal data class AddBlockedWrite(val target: PersonalPlaylistSummary?, val uncertain: Boolean)

internal enum class AddWriteRecovery { REJECTED, CONFLICT, UNCERTAIN }
private class AddPlaylistsChanged : Exception()

/** A malformed successful response is uncertain too: receiving HTTP 2xx does not confirm the saved DTO. */
internal fun addWriteRecovery(error: Exception): AddWriteRecovery {
    val status = (error as? ApiException)?.status
    return when {
        status == 409 || error is AddPlaylistsChanged -> AddWriteRecovery.CONFLICT
        status == null || status !in 400..499 || status == 408 -> AddWriteRecovery.UNCERTAIN
        else -> AddWriteRecovery.REJECTED
    }
}

/** Single-flight state lives only as long as this client's sheet; verification never restores write authority. */
internal class AddToPlaylistState(
    private val api: PersonalCollectionsApi,
    private val track: LibraryItem,
    private val scope: CoroutineScope,
    private val personalRevision: () -> Long = { 0L },
    private val currentSession: () -> Any? = { null },
) {
    private val session = currentSession()
    private var closed = false
    fun dispose() { closed = true }
    private fun current() {
        if (closed || !scope.isActive || session !== currentSession()) throw CancellationException()
    }
    var targetIdentity by mutableStateOf<Any>(Any()); private set
    var verificationIdentity by mutableStateOf<Any>(Any()); private set
    fun checkFreshness(revision: Long) {
        if (session !== currentSession() || (blocked == null && targetRevision != null && targetRevision != revision)) {
            targetsStale = true
            message = "Playlists changed."
        }
    }
    var targetError by mutableStateOf<String?>(null); private set
    var verificationError by mutableStateOf<String?>(null); private set
    var targetsStale by mutableStateOf(false); private set
    private var targetRevision: Long? = null
    private var verificationRevision: Long? = null
    private var verificationOffset = 0
    private var failedVerificationOffset = 0
    private var failedTargetOffset = 0
    private var intendedSelectionId: String? = null
    private var replacingTargets by mutableStateOf(false)
    private var pendingTargetError by mutableStateOf<String?>(null)
    private var pendingVerificationError by mutableStateOf<String?>(null)
    var targetsEnded by mutableStateOf(false); private set
    var verificationEnded by mutableStateOf(false); private set
    val canLoadMoreTargets get() = !closed && !busy && !creating && !createdTarget && !finished &&
        blocked == null && targets != null && !targetsEnded && !targetsStale && targetError == null
    val canLoadMoreVerification get() = !closed && !busy && blocked?.target == null && blocked != null &&
        verifiedTargets != null && !verificationEnded && verificationError == null
    fun moreTargets() { if (canLoadMoreTargets) loadTargets(targetOffset) }
    fun retryTargets() {
        if (targetError != null && !targetsStale && !busy) {
            pendingTargetError = targetError
            targetError = null; loadTargets(failedTargetOffset)
        }
    }
    fun refreshTargets() {
        if (busy || blocked != null || closed) return
        targetIdentity = Any()
        // Retain the viewport's rows while reading, but retire every old DTO's write authority.
        replacingTargets = true
        selected = null; targetRevision = null; targetError = null; targetsStale = false
        createdTarget = false
        targetsEnded = false; targetOffset = 0; loadTargets(0)
    }
    fun moreVerification() { if (canLoadMoreVerification) verify(verificationOffset) }
    fun retryVerification() {
        if (verificationError != null && !busy) {
            pendingVerificationError = verificationError
            verificationError = null; verify(failedVerificationOffset)
        }
    }
    var targets by mutableStateOf<PersonalPlaylistPage?>(null)
        private set
    var selected by mutableStateOf<PersonalPlaylistSummary?>(null)
        private set
    var targetOffset by mutableStateOf(0)
        private set
    var creating by mutableStateOf(false)
        private set
    var createdTarget by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
    var name by mutableStateOf("")
    var description by mutableStateOf("")
    var mutating by mutableStateOf(false)
        private set
    var reading by mutableStateOf(false)
        private set
    var finished by mutableStateOf(false)
        private set
    var blocked by mutableStateOf<AddBlockedWrite?>(null)
        private set
    var verifiedTargets by mutableStateOf<PersonalPlaylistPage?>(null)
        private set
    var message by mutableStateOf("")
        private set
    var reviewedTarget by mutableStateOf<PersonalPlaylistSummary?>(null); private set
    var reviewCount by mutableStateOf<Int?>(null); private set
    val busy: Boolean get() = mutating || reading
    val presentation get() = AddPlaylistUi(
        trackName = track.name, trackCredit = track.artists.joinToString(", ") { it.name }.ifEmpty { track.albumName.orEmpty() },
        targets = filteredTargets, selectedId = selected?.id,
        query = query, searchable = !createdTarget && targets != null,
        searchIncomplete = !createdTarget && query.isNotBlank() && !targetsEnded, canSearchMore = canLoadMoreTargets,
        hiddenSelection = selected?.takeIf { target -> query.isNotBlank() && filteredTargets.none { it.id == target.id } }?.name,
        creating = creating, name = name, description = description, busy = busy, mutating = mutating,
        canAdd = canAdd, canSelect = !closed && session === currentSession() && !busy && !replacingTargets && !targetsStale && blocked == null && !finished,
        canCreate = !createdTarget && !finished, stale = targetsStale, message = message,
        targetError = targetError ?: pendingTargetError, blocked = blocked != null,
        reviewed = reviewedTarget?.let { listOf(it) } ?: verifiedTargets?.items.orEmpty(),
        reviewCount = reviewCount, reviewError = verificationError ?: pendingVerificationError,
    )
    private val filteredTargets get() = targets?.items.orEmpty().let { rows ->
        if (createdTarget) rows else rows.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }
    val canAdd: Boolean get() = !closed && session === currentSession() && !busy && !finished && blocked == null && !creating && !replacingTargets && !targetsStale &&
        targetRevision == personalRevision() && track.kind == "track" && selected?.let { it.kind == PlaylistKind.MANUAL && it.capabilities.addTracks } == true

    fun loadTargets(offset: Int) {
        if (closed || session !== currentSession() || busy || blocked != null || creating || createdTarget || finished || targetsStale || targetError != null) return
        if (offset != targetOffset || targetsEnded) return
        val revision = targetRevision ?: personalRevision().also { targetRevision = it }
        failedTargetOffset = offset
        read("", onFailure = { error ->
            targetsStale = error is AddPlaylistsChanged
            targetError = if (targetsStale) null else addPlaylistReadError(error)
            if (targetsStale) message = "Playlists changed."
        }) {
            if (revision != personalRevision()) throw AddPlaylistsChanged()
            val page = manualPage(offset)
            if (revision != personalRevision()) throw AddPlaylistsChanged()
            val merged = appendPage(if (replacingTargets) null else targets, page)
            targets = merged
            replacingTargets = false
            targetOffset = offset + page.items.size // Raw consumed offset, never the deduplicated row count.
            targetsEnded = targetOffset == page.total
            selected = intendedSelectionId?.let { id -> merged.items.find { it.id == id } }
                ?: merged.items.firstOrNull().takeIf { intendedSelectionId == null }
            if (intendedSelectionId == null) intendedSelectionId = selected?.id
            message = ""
        }
    }

    fun select(id: String) {
        if (!closed && session === currentSession() && !busy && !replacingTargets && !targetsStale && targetRevision == personalRevision() && blocked == null && !creating && !finished) {
            selected = targets?.items?.find { it.id == id }
            intendedSelectionId = selected?.id
        }
    }

    fun beginCreate() {
        if (closed || session !== currentSession() || busy || blocked != null || finished || createdTarget) return
        creating = true
        message = ""
    }

    fun cancelCreate() {
        if (closed || busy || blocked != null) return
        creating = false
        message = ""
    }

    fun create() {
        if (closed || session !== currentSession() || !creating || busy || blocked != null || finished) return
        val title = name.trim()
        val details = description.trim()
        playlistMetadataError(title, details)?.let { message = it; return }
        mutate(null) {
            val saved = api.createPlaylist(title, details)
            current()
            check(saved.kind == PlaylistKind.MANUAL && saved.capabilities.addTracks)
            targets = PersonalPlaylistPage(listOf(saved), 1)
            selected = saved
            intendedSelectionId = saved.id
            replacingTargets = false
            creating = false
            createdTarget = true
            targetRevision = personalRevision()
            targetsStale = false
            message = ""
        }
    }

    fun add() {
        if (!canAdd) return
        val snapshot = selected ?: return
        val revision = targetRevision
        mutate(snapshot) {
            if (revision != personalRevision()) throw AddPlaylistsChanged()
            val saved = api.addEntry(snapshot.id, track.id, snapshot.revision)
            current()
            check(saved.id == snapshot.id && saved.kind == PlaylistKind.MANUAL)
            finished = true
        }
    }

    fun verify(offset: Int = 0) {
        val failure = blocked ?: return
        if (closed || session !== currentSession() || busy || finished) return
        // A deliberate verification starts a fresh read-only chain; continuation/retry keeps its exact offset.
        if (offset == 0) {
            verificationIdentity = Any()
            verifiedTargets = null; verificationOffset = 0; verificationEnded = false
            verificationRevision = personalRevision(); verificationError = null
            reviewedTarget = null; reviewCount = null
        } else if (offset != verificationOffset || verificationEnded || verificationError != null) return
        failedVerificationOffset = offset
        read(message, onFailure = { verificationError = addPlaylistReadError(it) }) {
            if (verificationRevision != personalRevision()) throw AddPlaylistsChanged()
            val target = failure.target
            if (target == null) {
                if (verificationRevision != personalRevision()) throw AddPlaylistsChanged()
                val page = manualPage(offset)
                if (verificationRevision != personalRevision()) throw AddPlaylistsChanged()
                verifiedTargets = appendPage(verifiedTargets, page)
                verificationOffset = offset + page.items.size
                verificationEnded = verificationOffset == page.total
                // Read-only matching names never resolve the create's outcome or re-enable writes.
            } else {
                val saved = api.playlist(target.id)
                current()
                check(saved.id == target.id)
                if (failure.uncertain) {
                    check(saved.kind == PlaylistKind.MANUAL)
                    val count = saved.trackCount ?: error("Missing count")
                    check(count in 0..Int.MAX_VALUE.toLong())
                    val entryIds = mutableSetOf<String>()
                    var retainedBytes = 0L
                    var previousPosition = -1
                    var seen = 0
                    var occurrences = 0
                    do {
                        val page = api.entries(target.id, limit = 100, offset = seen)
                        current()
                        check(page.offset == seen && page.limit in 1..100 && page.items.size <= page.limit &&
                            page.total.toLong() == count && seen.toLong() + page.items.size <= count)
                        check(page.items.isNotEmpty() || seen.toLong() == count)
                        for (entry in page.items) {
                            check(entry.position > previousPosition)
                            previousPosition = entry.position
                            // Bound actual entry JSON bytes across this read, not a nominal per-song allowance.
                            retainedBytes += ApiJson.encodeToString(entry).toByteArray(Charsets.UTF_8).size.toLong()
                            check(retainedBytes <= 16L * 1024 * 1024)
                            check(entryIds.add(entry.id))
                            if (entry.track.id == track.id) occurrences++
                        }
                        seen += page.items.size
                    } while (seen.toLong() < count)
                    val after = api.playlist(target.id)
                    current()
                    check(after.id == saved.id && after.kind == PlaylistKind.MANUAL &&
                        after.revision == saved.revision && after.trackCount == count)
                    if (verificationRevision != personalRevision()) throw AddPlaylistsChanged()
                    reviewCount = occurrences
                }
                if (verificationRevision != personalRevision()) throw AddPlaylistsChanged()
                reviewedTarget = saved
                // Keep the original uncertainty/conflict message and blocked write reservation.
            }
        }
    }

    private suspend fun manualPage(offset: Int): PersonalPlaylistPage {
        val page = api.playlists(kind = PlaylistKind.MANUAL, limit = 40, offset = offset)
        current()
        check(page.offset == offset && page.limit == 40 && page.total >= 0 && page.items.size <= 40)
        check(page.items.size == (page.total - offset).coerceIn(0, 40))
        check(page.items.map { it.id }.toSet().size == page.items.size)
        check(page.items.all { it.kind == PlaylistKind.MANUAL && it.capabilities.addTracks })
        return page
    }

    private fun appendPage(prefix: PersonalPlaylistPage?, page: PersonalPlaylistPage): PersonalPlaylistPage {
        if (prefix != null) check(prefix.total == page.total)
        val old = prefix?.items.orEmpty().associateBy { it.id }
        check(page.items.all { old[it.id]?.let { previous -> previous == it } ?: true })
        val fresh = page.items.filter { it.id !in old }
        check(fresh.isNotEmpty() || page.total == 0)
        val all = prefix?.items.orEmpty() + fresh
        // Retained summaries are byte-bounded across pages, not limited to a playlist-count prefix.
        check(all.sumOf { ApiJson.encodeToString(it).toByteArray(Charsets.UTF_8).size.toLong() } <= 16L * 1024 * 1024)
        // An overlapping offset page cannot truthfully establish a complete chooser.
        check(page.offset + page.items.size < page.total || all.size == page.total)
        return page.copy(items = all, offset = 0)
    }

    private fun addPlaylistReadError(error: Exception): String = when ((error as? ApiException)?.status) {
        401 -> "Session expired. Sign in again."
        403 -> "Permission denied."
        404 -> "Playlist unavailable."
        429 -> "Too many requests. Try later."
        else -> if (error is AddPlaylistsChanged) "Playlists changed." else "Could not load playlists."
    }

    private fun read(failureText: String, onFailure: (Exception) -> Unit = {}, action: suspend () -> Unit) {
        reading = true
        scope.launch {
            try { current(); action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (!closed && session === currentSession()) { message = failureText; onFailure(error) }
            }
            finally {
                reading = false
                pendingTargetError = null; pendingVerificationError = null
            }
        }
    }

    private fun mutate(target: PersonalPlaylistSummary?, action: suspend () -> Unit) {
        // Reserve synchronously, not after dispatch: a second tap cannot submit twice.
        mutating = true
        message = ""
        scope.launch {
            try { current(); action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (closed || session !== currentSession()) return@launch
                val status = (error as? ApiException)?.status
                val recovery = addWriteRecovery(error)
                if (recovery != AddWriteRecovery.REJECTED) {
                    blocked = AddBlockedWrite(target, recovery == AddWriteRecovery.UNCERTAIN)
                    message = when {
                        recovery == AddWriteRecovery.CONFLICT -> "Playlist changed."
                        target == null -> "Create unconfirmed."
                        else -> "Add unconfirmed."
                    }
                } else {
                    message = when (status) {
                        401 -> "Session expired. Sign in again."
                        403 -> "Permission denied."
                        404 -> "Playlist or song unavailable."
                        429 -> "Too many requests. Try later."
                        else -> if (target == null) "Could not create playlist."
                            else "Could not add song."
                    }
                }
            } finally { mutating = false }
        }
    }
}
