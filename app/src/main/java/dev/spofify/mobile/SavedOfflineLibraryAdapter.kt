package dev.spofify.mobile

import dev.spofify.mobile.feature.playlists.SavedDownloadsOwner
import dev.spofify.mobile.feature.playlists.SavedLibraryAccess
import dev.spofify.mobile.feature.playlists.SavedMediaRequest
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Read-only bridge for ONE genuine offline eligibility/read incarnation. The caller must capture
 * expectedEligibility before any suspension; it is a nonblocking identity/mode/read guard, never
 * an action-selection or collection-membership guard. This adapter cannot mint that authority. */
class SavedOfflineLibraryAdapter(
    private val owner: SavedDownloadsOwner,
    private val eligibility: OfflineEligibility,
    private val expectedEligibility: () -> Boolean,
) : OfflineLibraryAccess {
    private val retired = AtomicBoolean(false)
    private val access = AtomicReference<SavedLibraryAccess?>()
    // Initialization belongs to this incarnation, not a revision-keyed UI collector. A revision
    // emitted by owner.access must not cancel initialization and cause a fresh bind/revision loop.
    private val preparationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private class Preparation {
        val failed = AtomicBoolean(false)
        lateinit var result: Deferred<SavedLibraryAccess?>
    }
    private fun newPreparation(): Preparation = Preparation().also { request ->
        request.result = preparationScope.async(start = CoroutineStart.LAZY) {
            try {
                val admitted = owner.access(::current) ?: run {
                    if (!current()) return@async null
                    throw IOException("Saved library is unavailable")
                }
                access.set(admitted)
                if (!current()) {
                    access.compareAndSet(admitted, null)
                    admitted.retire()
                    null
                } else admitted
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { request.failed.set(true); throw failure }
        }
    }
    private val prepared = AtomicReference(newPreparation())

    /** Only the deliberate Retry action can replace a failed initialization. Revisions and
     * cancelled UI collectors reuse it; successful access/accepted streams are never reset. */
    fun retryFailedPreparation() {
        if (!current()) return
        val previous = prepared.get()
        if (!previous.failed.get()) return
        val next = newPreparation()
        if (!prepared.compareAndSet(previous, next) || !current()) next.result.cancel()
    }
    override val revision = owner.reader.revision

    private fun current(): Boolean = !retired.get() &&
        runCatching(expectedEligibility).getOrDefault(false) && !retired.get()

    override suspend fun collections(origin: String, accountId: String): List<OfflineSavedCollection> {
        if (origin != eligibility.origin || accountId != eligibility.accountId || !current()) return emptyList()
        // Await the owner's ordered IO bind, not currentScope()/an optimistic empty reader result.
        // The very same captured guard is retained by this access and every eventual stream.
        val admitted = try { prepared.get().result.await() } catch (cancelled: CancellationException) {
            // Retirement is an internal stale-result fence, not cancellation of the caller.
            // A genuinely cancelled collector must still receive its own cancellation.
            currentCoroutineContext().ensureActive()
            if (!current()) return emptyList()
            throw cancelled
        } ?: return emptyList()
        if (!current()) return emptyList()
        val snapshots = admitted.collections()
        if (!current()) return emptyList()
        val result = snapshots.map { snapshot ->
            savedOfflineCollection(snapshot) { row ->
                val request = SavedMediaRequest(snapshot.target, snapshot.generation,
                    row.occurrence.occurrenceId, requireNotNull(row.media))
                OfflineMediaReference(this, eligibility.origin, eligibility.accountId) {
                    admitted.openVerifiedMedia(request)
                }
            }
        }
        return if (current()) result else emptyList()
    }

    /** Fence only this bridge. No purge, disk access, lease closing or saved-owner retirement.
     * A racing registration rechecks current after insertion and retires itself if we missed it.
     * Already delivered streams also retain current(), so the atomic fence is immediate. */
    override fun retire() {
        if (!retired.compareAndSet(false, true)) return
        access.getAndSet(null)?.retire()
        preparationScope.cancel()
    }
}
