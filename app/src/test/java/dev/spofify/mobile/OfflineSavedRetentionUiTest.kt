package dev.spofify.mobile

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.spofify.mobile.core.ServerAddress
import dev.spofify.mobile.core.SessionStore
import dev.spofify.mobile.core.SpofifyClient
import dev.spofify.mobile.core.StoredSession
import dev.spofify.mobile.core.saved.*
import dev.spofify.mobile.feature.playlists.SavedDownloadsOwner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real saved-owner local removal, not a second metadata store or a network fixture. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-xhdpi")
class OfflineSavedRetentionUiTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    private val scope = SavedScope("https://synthetic.invalid", "account")
    private val target = SavedTarget(SavedTargetKind.ALBUM, "album")

    private fun localRemoval(staleConfirmation: Boolean) = runTest {
        val root = temporary.newFolder()
        SavedLibraryStore(root, SavedLocalEligibility { it == scope }).use { store ->
            store.bind(scope)
            val stage = store.beginManifest(scope, target, "Saved album", "generation", 1)
            store.appendManifest(stage, 0, listOf(SavedOccurrence("entry", "missing", "Missing song")), terminal = true)
            store.finishManifest(stage)
        }
        val source = SpofifyClient(ServerAddress.parse(scope.origin), object : SessionStore {
            override fun read(): StoredSession? = null
            override fun write(value: StoredSession?) = Unit
        })
        source.setOffline(true)
        val owner = SavedDownloadsOwner(StandardTestDispatcher(testScheduler)) { root }
        try {
            owner.configure(source)
            owner.setLocalEligibility(SavedLocalEligibility { it == scope })
            owner.setNetworkAllowed(false)
            assertTrue(owner.bindLocal(source, scope))
            assertEquals(listOf(target), requireNotNull(owner.access { true }).collections().map { it.target })
            runCurrent()
            compose.setContent { MaterialTheme { OfflineSavedRetentionControl(owner, target) { true } } }
            compose.onNodeWithText("Unsave").performClick()
            compose.onNodeWithText("Unsave?").assertExists()
            compose.onNodeWithText("Cancel").performClick()
            assertEquals(1, requireNotNull(owner.access { true }).collections().size)
            compose.onNodeWithText("Unsave").performClick()
            val button = compose.onNode(hasText("Unsave") and hasAnyAncestor(isDialog()))
            val held = button.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            if (staleConfirmation) {
                val oldRevision = owner.state.value.bindingRevision
                owner.prepareConfigure(scope.origin)
                owner.configure(source)
                assertTrue(owner.bindLocal(source, scope))
                assertEquals(1, requireNotNull(owner.access { true }).collections().size)
                runCurrent()
                assertNotEquals(oldRevision, owner.state.value.bindingRevision)
                compose.waitForIdle()
                button.assertIsNotEnabled()
                compose.runOnIdle { held() }
            } else button.performClick()
            owner.ownedBytes() // Wait for the original owner's ordered removal command.
            assertEquals(if (staleConfirmation) 1 else 0, requireNotNull(owner.access { true }).collections().size)
            assertFalse(owner.state.value.networkEnabled)
            assertNull(source.currentSession())
            assertEquals(0, source.httpClient.dispatcher.runningCallsCount())
            assertEquals(0, source.httpClient.dispatcher.queuedCallsCount())
        } finally {
            owner.close()
            source.httpClient.dispatcher.cancelAll()
            source.httpClient.connectionPool.evictAll()
            source.httpClient.dispatcher.executorService.shutdown()
        }
    }

    @Test fun partialLocalCollectionCanBeUnsavedOnlyAfterExplicitConfirmationWithoutOnlineAuth() = localRemoval(false)
    @Test fun heldConfirmationCannotBorrowASameAccountSuccessorBinding() = localRemoval(true)
}
