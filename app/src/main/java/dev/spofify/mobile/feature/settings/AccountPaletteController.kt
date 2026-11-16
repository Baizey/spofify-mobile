package dev.spofify.mobile.feature.settings

import dev.spofify.mobile.core.auth.AccountIdentity
import dev.spofify.mobile.core.auth.AuthState
import dev.spofify.mobile.core.palette.AccountPalette
import dev.spofify.mobile.core.palette.PaletteApi
import dev.spofify.mobile.core.palette.PaletteId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class AccountPaletteState(
    val owner: AccountIdentity? = null,
    val confirmed: AccountPalette? = null,
    val draft: PaletteId? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val needsRefresh: Boolean = false,
    val notice: String? = null,
)

/** Application lifetime; call entrypoints and supply a scope on Main. No persisted account fallback. */
internal class AccountPaletteController(
    private val auth: StateFlow<AuthState>,
    private val apiFactory: (AccountIdentity) -> PaletteApi,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(AccountPaletteState())
    val state = mutable.asStateFlow()
    private var started = false
    private var generation = 0L
    private var operation: Job? = null
    private var api: PaletteApi? = null

    fun start() {
        if (started || !scope.isActive) return
        started = true
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            auth.collect { synchronizeOwner() }
        }
    }

    fun refresh() {
        start()
        synchronizeOwner()
        val current = mutable.value
        val owner = current.owner ?: return
        if (current.saving || current.loading || !scope.isActive) return
        val client = clientForOwner(owner) ?: return
        read(owner, client)
    }

    fun choose(id: PaletteId) {
        start()
        synchronizeOwner()
        val current = mutable.value
        val owner = current.owner ?: return
        val confirmed = current.confirmed ?: return
        val client = api ?: return
        if (current.loading || current.saving || current.needsRefresh || !scope.isActive ||
            confirmed.accountId != owner.accountId || confirmed.presets.none { it.id == id }) return
        val token = ++generation
        mutable.value = current.copy(draft = id, saving = true, notice = null)
        operation = scope.launch {
            try {
                val result = client.choose(id, confirmed.revision)
                if (!isCurrent(owner, token)) return@launch
                // The API validates the wire contract; also protect this UI ownership boundary.
                check(result.accountId == owner.accountId && result.paletteId == id &&
                    result.revision == confirmed.revision + 1)
                mutable.value = mutable.value.copy(confirmed = result, draft = null, saving = false,
                    needsRefresh = false, notice = null)
            } catch (cancelled: CancellationException) {
                if (isCurrent(owner, token)) reconcile(owner, client)
                throw cancelled
            } catch (_: Exception) {
                if (isCurrent(owner, token)) reconcile(owner, client)
            }
        }
    }

    /** Fence before cancelling: even an uncooperative old request cannot publish into its successor. */
    private fun synchronizeOwner() {
        if (!scope.isActive) return
        val owner = (auth.value as? AuthState.Authenticated)?.identity
        if (owner == mutable.value.owner) return
        generation++
        operation?.cancel()
        operation = null
        api = null
        mutable.value = AccountPaletteState(owner = owner, needsRefresh = owner != null)
        if (owner == null) return
        val client = clientForOwner(owner) ?: return
        read(owner, client)
    }

    private fun clientForOwner(owner: AccountIdentity): PaletteApi? {
        api?.let { return it }
        val token = generation
        val client = try { apiFactory(owner) } catch (_: Exception) {
            if (isCurrent(owner, token)) mutable.value = mutable.value.copy(
                notice = "Account colours are unavailable. Refresh to try again.")
            return null
        }
        if (!isCurrent(owner, token)) return null
        api = client
        return client
    }

    private fun reconcile(owner: AccountIdentity, client: PaletteApi) {
        // Retire the write token and its preview before starting exactly one GET.
        // No choice is queued: a later mutation always needs another explicit call.
        read(owner, client, afterWriteFailure = true)
    }

    private fun read(owner: AccountIdentity, client: PaletteApi, afterWriteFailure: Boolean = false) {
        val token = ++generation
        mutable.value = mutable.value.copy(draft = null, loading = true, saving = false, needsRefresh = true,
            notice = if (afterWriteFailure) "Choice was not confirmed. Reloading account colours." else null)
        operation = scope.launch {
            try {
                val result = client.read()
                if (!isCurrent(owner, token)) return@launch
                check(result.accountId == owner.accountId)
                mutable.value = mutable.value.copy(confirmed = result, draft = null, loading = false,
                    saving = false, needsRefresh = false,
                    notice = if (afterWriteFailure) "Account colours reloaded. Choose again to save a new selection." else null)
            } catch (cancelled: CancellationException) {
                if (isCurrent(owner, token)) readFailed()
                throw cancelled
            } catch (_: Exception) {
                if (isCurrent(owner, token)) readFailed()
            }
        }
    }

    private fun readFailed() {
        mutable.value = mutable.value.copy(draft = null, loading = false, saving = false, needsRefresh = true,
            notice = if (mutable.value.confirmed == null) "Account colours are unavailable. Refresh to try again."
                else "Showing the last confirmed account colours. Refresh before choosing again.")
    }

    private fun isCurrent(owner: AccountIdentity, token: Long): Boolean = scope.isActive &&
        token == generation && mutable.value.owner == owner &&
        (auth.value as? AuthState.Authenticated)?.identity == owner
}
