package dev.spofify.mobile.feature.settings

import dev.spofify.mobile.core.auth.AccountIdentity
import dev.spofify.mobile.core.auth.AuthNotice
import dev.spofify.mobile.core.auth.AuthState
import dev.spofify.mobile.core.palette.*
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountPaletteControllerTest {
    @Test fun authenticatedLocalOwnerWithoutGrantNeverConstructsPaletteClientOrRendersLoading() = runTest {
        val owner = AccountIdentity("https://one.example", "one", "one", 1)
        val controller = AccountPaletteController(MutableStateFlow(AuthState.Authenticated(owner)),
            { error("No local palette transport") }, backgroundScope, MutableStateFlow(null))
        controller.start(); runCurrent()
        controller.refresh(); controller.choose(PaletteId.EMBER); runCurrent()
        assertEquals(owner, controller.state.value.owner)
        assertFalse(controller.state.value.online)
        assertFalse(controller.state.value.loading)
        assertFalse(controller.state.value.saving)
        assertNull(controller.state.value.notice)
    }

    @Test fun startAndRefreshAreSingleFlightAndChoicesAreIgnoredUntilInitialRead() = runTest {
        val h = Harness(this)
        val gate = CompletableDeferred<Unit>()
        h.api.onRead = { gate.await(); h.api.stored }
        h.controller.start()
        assertEquals(h.owner, h.controller.state.value.owner)
        assertTrue(h.controller.state.value.loading)
        h.controller.start()
        h.controller.refresh()
        h.controller.choose(PaletteId.EMBER)
        runCurrent()
        assertEquals(1, h.api.reads)
        assertTrue(h.api.choices.isEmpty())
        gate.complete(Unit)
        runCurrent()
        assertEquals(h.api.stored, h.controller.state.value.confirmed)
        assertFalse(h.controller.state.value.loading)
        assertFalse(h.controller.state.value.needsRefresh)
    }

    @Test fun previewIsLiveButRapidChoicesAndRefreshNeverQueueAnotherWrite() = runTest {
        val h = Harness(this)
        h.start()
        val original = h.controller.state.value.confirmed
        val gate = CompletableDeferred<Unit>()
        h.api.onChoose = { id, revision ->
            gate.await()
            palette(h.owner, id, revision + 1).also { h.api.stored = it }
        }
        h.controller.choose(PaletteId.EMBER)
        assertEquals(PaletteId.EMBER, h.controller.state.value.draft)
        assertEquals(original, h.controller.state.value.confirmed)
        assertTrue(h.controller.state.value.saving)
        h.controller.choose(PaletteId.VIOLET)
        h.controller.refresh()
        h.controller.choose(PaletteId.JADE)
        runCurrent()
        assertEquals(listOf(Choice(PaletteId.EMBER, 0)), h.api.choices)
        assertEquals(1, h.api.reads)
        gate.complete(Unit)
        runCurrent()
        val state = h.controller.state.value
        assertNull(state.draft)
        assertFalse(state.saving)
        assertEquals(PaletteId.EMBER, state.confirmed!!.paletteId)
        assertEquals(1L, state.confirmed.revision)
        assertEquals(1, h.api.choices.size)
        assertNull(state.notice)
    }

    @Test fun uncertainWriteRollsBackImmediatelyThenReadsWithoutReplayingTheChoice() = runTest {
        val h = Harness(this)
        h.start()
        val writeGate = CompletableDeferred<Unit>()
        val readGate = CompletableDeferred<Unit>()
        h.api.onChoose = { id, revision ->
            writeGate.await()
            h.api.stored = palette(h.owner, id, revision + 1) // Server may have committed.
            throw PaletteException(PaletteFailure.UNAVAILABLE, outcomeUncertain = true)
        }
        h.api.onRead = { readGate.await(); h.api.stored }
        h.controller.choose(PaletteId.EMBER)
        runCurrent()
        writeGate.complete(Unit)
        runCurrent()
        val pending = h.controller.state.value
        assertNull(pending.draft)
        assertEquals(PaletteId.JADE, pending.confirmed!!.paletteId)
        assertTrue(pending.loading)
        assertFalse(pending.saving)
        assertTrue(pending.needsRefresh)
        h.controller.choose(PaletteId.VIOLET)
        h.controller.refresh()
        readGate.complete(Unit)
        runCurrent()
        assertEquals(PaletteId.EMBER, h.controller.state.value.confirmed!!.paletteId)
        assertFalse(h.controller.state.value.needsRefresh)
        assertNotNull(h.controller.state.value.notice)
        assertEquals(2, h.api.reads)
        assertEquals(1, h.api.choices.size)
        h.api.onChoose = { id, revision -> palette(h.owner, id, revision + 1) }
        h.controller.choose(PaletteId.VIOLET) // A new explicit choice, using the reconciled revision.
        runCurrent()
        assertEquals(listOf(Choice(PaletteId.EMBER, 0), Choice(PaletteId.VIOLET, 1)), h.api.choices)
        assertEquals(2L, h.controller.state.value.confirmed!!.revision)
    }

    @Test fun conflictAndUnknownErrorsPerformOneGetOnlyAndNeverExposeDiagnostics() = runTest {
        for (error in listOf(PaletteException(PaletteFailure.CONFLICT),
                PaletteException(PaletteFailure.CATALOG), IOException("secret/private/path"))) {
            val h = Harness(this)
            h.start()
            h.api.onChoose = { _, _ -> throw error }
            h.controller.choose(PaletteId.EMBER)
            runCurrent()
            assertEquals(1, h.api.choices.size)
            assertEquals(2, h.api.reads)
            assertEquals(PaletteId.JADE, h.controller.state.value.confirmed!!.paletteId)
            assertNull(h.controller.state.value.draft)
            assertFalse(h.controller.state.value.needsRefresh)
            assertSafe(h.controller.state.value.notice)
        }
    }

    @Test fun failedReconciliationKeepsConfirmedStateAndBlocksChoiceUntilExplicitSuccessfulGet() = runTest {
        val h = Harness(this)
        h.start()
        val confirmed = h.controller.state.value.confirmed
        h.api.onChoose = { _, _ -> throw PaletteException(PaletteFailure.UNAVAILABLE, true) }
        h.api.onRead = { throw IOException("secret/private/path") }
        h.controller.choose(PaletteId.EMBER)
        runCurrent()
        assertEquals(confirmed, h.controller.state.value.confirmed)
        assertNull(h.controller.state.value.draft)
        assertTrue(h.controller.state.value.needsRefresh)
        assertFalse(h.controller.state.value.loading)
        assertFalse(h.controller.state.value.saving)
        assertTrue(h.controller.state.value.notice!!.contains("last confirmed"))
        assertSafe(h.controller.state.value.notice)
        h.controller.choose(PaletteId.VIOLET)
        runCurrent()
        assertEquals(1, h.api.choices.size)
        assertEquals(2, h.api.reads)
        h.api.onRead = { palette(h.owner, PaletteId.EMBER, 1) }
        h.controller.refresh()
        runCurrent()
        assertFalse(h.controller.state.value.needsRefresh)
        assertEquals(PaletteId.EMBER, h.controller.state.value.confirmed!!.paletteId)
        assertNull(h.controller.state.value.notice)
        assertEquals(1, h.api.choices.size)
    }

    @Test fun sameOwnerRefreshFailureRetainsConfirmedColorsAndRequiresFreshReadBeforeChoice() = runTest {
        val h = Harness(this)
        h.start()
        val confirmed = h.controller.state.value.confirmed
        h.api.onRead = { throw IOException("secret/private/path") }
        h.controller.refresh()
        runCurrent()
        assertEquals(h.owner, h.controller.state.value.owner)
        assertEquals(confirmed, h.controller.state.value.confirmed)
        assertTrue(h.controller.state.value.needsRefresh)
        h.controller.choose(PaletteId.EMBER)
        assertTrue(h.api.choices.isEmpty())
        assertSafe(h.controller.state.value.notice)
    }

    @Test fun lateReadSuccessOrErrorCannotPublishAfterAccountOrEpochSwitch() = runTest {
        for (sameAccount in listOf(false, true)) for (lateError in listOf(false, true)) {
            val h = Harness(this)
            val gate = CompletableDeferred<Unit>()
            h.api.onRead = {
                withContext(NonCancellable) { gate.await() }
                if (lateError) throw IOException("secret/private/path")
                h.api.stored
            }
            h.start()
            val nextOwner = if (sameAccount) h.owner.copy(incarnation = 2) else owner("b")
            val next = Api(nextOwner).apply { stored = palette(nextOwner, PaletteId.VIOLET, 7) }
            h.apis[nextOwner] = next
            h.auth.value = AuthState.Authenticated(nextOwner)
            h.controller.refresh() // Proactively observes live auth, even before its collector runs.
            assertEquals(nextOwner, h.controller.state.value.owner)
            assertNull(h.controller.state.value.confirmed)
            runCurrent()
            val accepted = h.controller.state.value
            assertEquals(next.stored, accepted.confirmed)
            gate.complete(Unit)
            runCurrent()
            assertEquals(accepted, h.controller.state.value)
            assertEquals(1, h.api.reads)
            assertEquals(1, next.reads)
            assertTrue(next.choices.isEmpty())
        }
    }

    @Test fun lateWriteSuccessOrErrorCannotPreviewConfirmOrReconcileIntoAnotherSession() = runTest {
        for (sameAccount in listOf(false, true)) for (lateError in listOf(false, true)) {
            val h = Harness(this)
            h.start()
            val gate = CompletableDeferred<Unit>()
            h.api.onChoose = { id, revision ->
                withContext(NonCancellable) { gate.await() }
                if (lateError) throw IOException("secret/private/path")
                palette(h.owner, id, revision + 1)
            }
            h.controller.choose(PaletteId.EMBER)
            runCurrent()
            val nextOwner = if (sameAccount) h.owner.copy(incarnation = 2) else owner("b")
            val next = Api(nextOwner).apply { stored = palette(nextOwner, PaletteId.VIOLET, 7) }
            h.apis[nextOwner] = next
            h.auth.value = AuthState.Authenticated(nextOwner)
            h.controller.choose(PaletteId.EMBER)
            assertNull(h.controller.state.value.confirmed)
            assertNull(h.controller.state.value.draft)
            assertFalse(h.controller.state.value.saving)
            runCurrent()
            val accepted = h.controller.state.value
            gate.complete(Unit)
            runCurrent()
            assertEquals(accepted, h.controller.state.value)
            assertEquals(next.stored, accepted.confirmed)
            assertEquals(1, h.api.choices.size)
            assertEquals(1, h.api.reads) // No GET for the retired write.
            assertEquals(1, next.reads)
            assertTrue(next.choices.isEmpty())
        }
    }

    @Test fun signedOutOrUnknownAuthClearsCacheWhileSameIdentityNoticeDoesNotReload() = runTest {
        val h = Harness(this)
        h.start()
        val original = h.controller.state.value
        h.auth.value = AuthState.Authenticated(h.owner, AuthNotice.SAVE_FAILED)
        runCurrent()
        assertEquals(original, h.controller.state.value)
        assertEquals(1, h.api.reads)
        h.auth.value = AuthState.Validating
        runCurrent()
        assertEquals(AccountPaletteState(), h.controller.state.value)
        h.auth.value = AuthState.Authenticated(h.owner)
        runCurrent()
        assertEquals(2, h.api.reads)
        h.auth.value = AuthState.SignedOut()
        runCurrent()
        assertEquals(AccountPaletteState(), h.controller.state.value)
        h.controller.refresh()
        h.controller.choose(PaletteId.EMBER)
        assertEquals(2, h.api.reads)
        assertTrue(h.api.choices.isEmpty())
    }

    @Test fun foreignReadAndMismatchedWriteResponsesCannotBecomeConfirmed() = runTest {
        val h = Harness(this)
        h.api.onRead = { palette(owner("foreign")) }
        h.start()
        assertNull(h.controller.state.value.confirmed)
        assertTrue(h.controller.state.value.needsRefresh)
        h.api.onRead = { h.api.stored }
        h.controller.refresh()
        runCurrent()
        for (invalid in listOf(palette(owner("foreign"), PaletteId.EMBER, 1),
                palette(h.owner, PaletteId.VIOLET, 1), palette(h.owner, PaletteId.EMBER, 9))) {
            h.api.onChoose = { _, _ -> invalid }
            h.controller.choose(PaletteId.EMBER)
            runCurrent()
            assertEquals(h.api.stored, h.controller.state.value.confirmed)
            assertNull(h.controller.state.value.draft)
        }
        assertEquals(3, h.api.choices.size)
        assertEquals(5, h.api.reads)
    }

    @Test fun failedApiConstructionCanBeRetriedOnlyByExplicitRefresh() = runTest {
        val identity = owner("a")
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated(identity))
        val api = Api(identity)
        var factories = 0
        val controller = AccountPaletteController(auth, {
            factories++
            if (factories == 1) throw IllegalStateException("secret/private/path")
            api
        }, backgroundScope)
        controller.start()
        runCurrent()
        assertTrue(controller.state.value.needsRefresh)
        assertSafe(controller.state.value.notice)
        controller.refresh()
        runCurrent()
        assertEquals(api.stored, controller.state.value.confirmed)
        assertEquals(2, factories)
        assertEquals(1, api.reads)
    }

    private class Harness(private val test: TestScope) {
        val owner = owner("a")
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated(owner))
        val api = Api(owner)
        val apis = mutableMapOf(owner to api)
        val controller = AccountPaletteController(auth, { apis.getValue(it) }, test.backgroundScope)
        fun start() { controller.start(); test.runCurrent() }
    }

    private data class Choice(val id: PaletteId, val revision: Long)
    private class Api(private val owner: AccountIdentity) : PaletteApi {
        var stored = palette(owner)
        var reads = 0
        val choices = mutableListOf<Choice>()
        var onRead: suspend () -> AccountPalette = { stored }
        var onChoose: suspend (PaletteId, Long) -> AccountPalette = { id, revision ->
            palette(owner, id, revision + 1).also { stored = it }
        }
        override suspend fun read(): AccountPalette { reads++; return onRead() }
        override suspend fun choose(id: PaletteId, expectedRevision: Long): AccountPalette {
            choices += Choice(id, expectedRevision)
            return onChoose(id, expectedRevision)
        }
    }

    companion object {
        private fun owner(id: String) = AccountIdentity("http://palette.test", "account-$id", "user-$id", 1)
        private fun palette(owner: AccountIdentity, id: PaletteId = PaletteId.JADE, revision: Long = 0): AccountPalette {
            val presets = listOf(
                PalettePreset(PaletteId.JADE, "Jade", PaletteTokens("#66e3b4", "#08271d", "#91bfff", "#dfbd78"), PaletteTokens("#08704e", "#ffffff", "#2759a4", "#77551b")),
                PalettePreset(PaletteId.EMBER, "Ember", PaletteTokens("#ffad89", "#31170d", "#c4b0ff", "#a4d7bd"), PaletteTokens("#aa431b", "#ffffff", "#654199", "#23644a")),
                PalettePreset(PaletteId.VIOLET, "Violet", PaletteTokens("#c8b5ff", "#22183c", "#6de2d5", "#e8b4c5"), PaletteTokens("#6442a5", "#ffffff", "#0c6d67", "#8b465e")),
            )
            return AccountPalette(owner.accountId, id, revision, 1, PaletteId.JADE, presets)
        }
        private fun assertSafe(notice: String?) {
            assertNotNull(notice)
            assertFalse(notice!!.contains("secret"))
            assertFalse(notice.contains("private"))
        }
    }
}
