package dev.spofify.mobile.feature.playlists

import androidx.activity.ComponentDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.spofify.mobile.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real route -> overflow -> editor/confirmation/HTTP regressions, without a queue-intent host.
 * PlaylistMoreHostTest separately covers the four exact captured receiver queue intents.
 * Synthetic evaluator tokens below are opaque wire fixtures, not signed integration tokens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaylistManagementHostTest {
    // These are HTTP/UI behavior tests, not virtual-delay tests. Use the standard editor-host
    // rule (as in PlaybackDevicesSheetTest/SignInUiTest): a v2 effect TestDispatcher also
    // auto-advances recurring TextField effects and can prevent the editor from becoming idle.
    // Management behavior is independent of decorative TextField transition frames.
    @get:Rule val compose = createComposeRule(effectContext = object : MotionDurationScale {
        override val scaleFactor: Float = 0f
    })

    @Test fun manualHeaderHasOneFullCollectionCountAndNoEmptyDescriptionOrManagementBar() = withHost(
        total = 204,
    ) {
        awaitLoaded()
        assertCleanHeader("204 songs") // Only two occurrences are in this browse page.
        compose.onAllNodes(blankText, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("2 songs").assertDoesNotExist()
        openMore()
        compose.onNode(hasText("Playlist fixture") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        dismissDialogWithBack()
        compose.onNodeWithContentDescription(playlistMore).assertIsDisplayed()
        assertEquals(0, writes().size)
    }

    @Test fun derivedHeaderShowsDescriptionAndOneKnownEvaluationCountInsteadOfImplementationInventory() = withHost(
        derived = true, total = 1002, description = "For the night train",
    ) {
        awaitLoaded()
        assertCleanHeader("1002 songs")
        compose.onNodeWithText("For the night train").assertIsDisplayed()
        compose.onNodeWithText("2 songs").assertDoesNotExist()
        compose.onNodeWithText("Rules playlist", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Complete result evaluated", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Playback prepares up to 1,000", substring = true).assertDoesNotExist()
        assertEquals(0, writes().size)
    }

    @Test fun manualManagementWorksWithoutQueueIntentCallbackAndReorderIsExplicit() = withHost {
        awaitLoaded()
        openMore()
        listOf("Edit details", "Delete", "Reorder all occurrences").forEach { menuAction(it).assertIsEnabled() }
        listOf("Edit rules", "Make editable copy", "Queue Low", "Queue High", "Play next", "Play now").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        menuAction("Edit details").performClick()
        compose.onNodeWithText("Edit playlist").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsEnabled()
        compose.onNodeWithText("Cancel").performClick()

        openMore()
        menuAction("Delete").performClick()
        compose.onNodeWithText("Delete playlist?").assertIsDisplayed()
        compose.onNodeWithText("Delete playlist").assertIsEnabled()
        assertTrue("Opening a destructive confirmation is not a write", writes().isEmpty())
        compose.onNodeWithText("Cancel").performClick()

        openMore()
        menuAction("Reorder all occurrences").performClick()
        waitText("Save order")
        compose.onNodeWithText("Cancel reorder").performScrollTo().performClick()
        awaitLoaded()
        compose.onNodeWithText("Save order").assertDoesNotExist()
        assertTrue("Loading/cancelling the complete order never saves it", writes().isEmpty())
    }

    @Test fun derivedManagementStillOpensRulesAndCopyWithoutQueueIntentCallback() = withHost(derived = true) {
        awaitLoaded()
        openMore()
        listOf("Edit details", "Edit rules", "Make editable copy", "Delete").forEach { menuAction(it).assertIsEnabled() }
        compose.onNodeWithText("Refresh").assertDoesNotExist()
        compose.onNodeWithText("Reorder all occurrences").assertDoesNotExist()
        menuAction("Edit rules").performClick()
        waitText("Back — keep draft")
        compose.onNodeWithText("Back — keep draft").performClick()
        awaitLoaded()
        openMore()
        menuAction("Make editable copy").performClick()
        compose.onNodeWithText("Name").assertExists()
        compose.onNodeWithText("Playlist fixture (copy)").assertExists()
        compose.onNodeWithText("maximum 1,000", substring = true).assertExists()
        compose.onNodeWithText("Save").assertIsEnabled()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(writes().isEmpty())
    }

    @Test fun capabilitiesDoNotBecomeWriteOrPlayAuthorityWhenManagementMovesIntoMore() = withHost(
        derived = true,
        capabilities = PlaylistCapabilities(play = false, editMetadata = false, editEntries = false,
            editDefinition = false, copyToManual = false, delete = false, addTracks = false),
    ) {
        awaitLoaded()
        compose.onNodeWithText("Play").assertDoesNotExist()
        openMore()
        compose.onNodeWithText("Refresh").assertDoesNotExist()
        managementActions.forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        assertTrue(writes().isEmpty())
    }

    @Test fun unevaluatedLoadingNeverInventsZeroAndMoreKeepsBusyActionsDisabled() = withHost(
        derived = true,
        beforeMount = { evaluationGate = CountDownLatch(1) },
    ) {
        await { requests.any { it.requestUrl?.encodedPath == "$detailPath/entries" } }
        compose.onNodeWithText("Playlist fixture").assertIsDisplayed()
        compose.onAllNodes(songCount).assertCountEquals(0)
        compose.onNodeWithText("Play").assertIsNotEnabled()
        openMore()
        listOf("Edit details", "Edit rules", "Make editable copy", "Delete").forEach {
            menuAction(it).assertIsNotEnabled()
        }
        dismissDialogWithBack()
        evaluationGate.countDown()
        awaitLoaded()
        compose.onNodeWithText("2 songs").assertIsDisplayed()
        compose.onNodeWithText("Play").assertIsEnabled()
        assertTrue(writes().isEmpty())
    }

    @Test fun failedInitialFetchOffersReadOnlyRetry() = withHost(
        beforeMount = { failSummaryOnce.set(true) },
    ) {
        await { noProgress() && requests.any { it.requestUrl?.encodedPath == detailPath } }
        compose.onAllNodes(songCount).assertCountEquals(0)
        compose.onNodeWithText("Retry loading playlist").assertIsEnabled().performClick()
        awaitLoaded()
        compose.onNodeWithText("2 songs").assertIsDisplayed()
        assertTrue(writes().isEmpty())
    }

    @Test fun failedReevaluationRetainsRowsButDoesNotOfferStaleCountPlaybackOrCopy() = withHost(derived = true) {
        awaitLoaded()
        failEvaluation.set(true)
        compose.runOnIdle { client.notifyPersonalChange(PersonalCollection.PLAYLISTS) }
        waitText("Playlist could not load.")
        compose.onAllNodesWithContentDescription(songMore).assertCountEquals(2)
        compose.onAllNodes(songCount).assertCountEquals(0)
        compose.onNodeWithText("Play").assertIsNotEnabled()
        openMore()
        compose.onNodeWithText("Refresh").assertDoesNotExist()
        menuAction("Make editable copy").assertIsNotEnabled()
        // A failed read cannot authorize management against the old source revision.
        menuAction("Edit details").assertIsNotEnabled()
        menuAction("Delete").assertIsNotEnabled()
        dismissDialogWithBack()
        compose.onAllNodesWithContentDescription(songMore)[1].performClick()
        waitText("Like song")
        menuAction("Play now").assertIsNotEnabled()
        assertTrue(writes().isEmpty())
    }

    @Test fun metadataConflictBlocksManagementWritesUntilExplicitRefresh() = withHost(
        beforeMount = { failMetadata.set(true) },
    ) {
        awaitLoaded()
        openMore()
        menuAction("Edit details").performClick()
        compose.onNodeWithText("Save").performClick()
        waitText("Verify saved playlist")
        compose.onNodeWithText("Save").assertDoesNotExist()
        compose.onNodeWithText("Cancel").performClick()
        openMore()
        listOf("Edit details", "Delete", "Reorder all occurrences").forEach { menuAction(it).assertIsNotEnabled() }
        dismissDialogWithBack()
        compose.onNodeWithText("Verify saved playlist").performScrollTo().performClick()
        awaitLoaded()
        openMore()
        listOf("Edit details", "Delete", "Reorder all occurrences").forEach { menuAction(it).assertIsEnabled() }
        assertEquals(listOf("PATCH"), writes().map { it.method })
    }

    @Test fun secondDuplicateMoreRemovesOnlyItsEntryAfterConfirmationAndNeverByTrackId() = withHost {
        awaitLoaded()
        compose.onAllNodesWithText("Repeated song").assertCountEquals(2)
        compose.onNodeWithText("Remove this occurrence").assertDoesNotExist()
        fun selectSecondRemoval() {
            compose.onAllNodesWithContentDescription(songMore)[1].performClick()
            waitText("Like song")
            menuAction("Remove this occurrence").assertIsEnabled().performClick()
            compose.onNodeWithText("Remove occurrence?").assertIsDisplayed()
        }
        selectSecondRemoval()
        assertTrue(writes().isEmpty())
        compose.onNodeWithText("Cancel").performClick()
        compose.onAllNodesWithContentDescription(songMore).assertCountEquals(2)
        assertTrue(writes().isEmpty())
        selectSecondRemoval()
        compose.onNodeWithText("Remove").performClick()
        await { noProgress() && compose.onAllNodesWithContentDescription(songMore).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("1 song").assertIsDisplayed()
        val removal = writes().single()
        assertEquals("DELETE", removal.method)
        assertEquals("$detailPath/entries/second-entry", removal.requestUrl!!.encodedPath)
        assertEquals("5", body(removal).getValue("expectedRevision").jsonPrimitive.content)
        assertEquals(setOf("expectedRevision"), body(removal).keys)
        compose.onNodeWithText("Remove occurrence?").assertDoesNotExist()
    }

    @Test fun uncertainCopyVerificationAndSourceRefreshNeverReplayOrSilentlyUnlockTheWrite() = withHost(
        derived = true,
        beforeMount = { failCopy.set(true) },
    ) {
        awaitLoaded()
        openMore()
        menuAction("Make editable copy").performClick()
        compose.onNodeWithText("Save").performClick()
        waitText("Verify saved playlist")
        compose.onNodeWithText("Save").assertDoesNotExist()
        val copy = writes().single()
        assertEquals("POST", copy.method)
        assertEquals("$detailPath/copy", copy.requestUrl!!.encodedPath)
        assertEquals("5", body(copy).getValue("expectedRevision").jsonPrimitive.content)
        assertEquals("evaluation-fence", body(copy).getValue("fence").jsonPrimitive.content)
        assertEquals("Playlist fixture (copy)", body(copy).getValue("name").jsonPrimitive.content)
        compose.onNodeWithText("Verify saved playlist").performClick()
        waitText("A matching name cannot prove", substring = true)
        assertTrue(requests.any { it.requestUrl?.encodedPath == "/api/me/playlists" && it.requestUrl?.queryParameter("kind") == "manual" })
        compose.onNodeWithText("Save").assertDoesNotExist()
        compose.onNodeWithText("Cancel").performClick()
        openMore()
        menuAction("Verify saved copies").assertIsEnabled()
        menuAction("Make editable copy").assertIsNotEnabled()
        dismissDialogWithBack()
        compose.onNodeWithText("Verify saved playlist").performScrollTo().performClick()
        awaitLoaded()
        openMore()
        menuAction("Make editable copy").assertIsNotEnabled()
        // Verification plus a source refresh still needs a separate, explicit review acknowledgement.
        menuAction("I reviewed saved copies — allow a new copy").performClick()
        openMore()
        menuAction("Make editable copy").assertIsEnabled()
        assertEquals("Verification, refresh and acknowledgement are reads/UI, not a copy retry", 1, writes().size)
    }

    private fun assertCleanHeader(count: String) {
        compose.onNodeWithText("Playlist fixture").assertIsDisplayed()
        compose.onNodeWithText(count).assertIsDisplayed()
        compose.onAllNodes(songCount).assertCountEquals(1)
        compose.onNodeWithText("Play").assertIsDisplayed().assertIsEnabled()
        compose.onAllNodesWithContentDescription(playlistMore).assertCountEquals(1)
        compose.onNodeWithContentDescription(playlistMore).assertIsDisplayed().assertIsEnabled()
        (managementActions + listOf("Refresh", "Remove this occurrence")).forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        compose.onNodeWithText("with audio", substring = true).assertDoesNotExist()
        compose.onNodeWithText("live inputs", substring = true).assertDoesNotExist()
    }

    private fun openMore() = compose.onNodeWithContentDescription(playlistMore).assertIsEnabled().performClick()
    private fun menuAction(label: String) = compose.onNodeWithText(label).performScrollTo()
    private fun dismissDialogWithBack() {
        val dialog = compose.runOnIdle { ShadowDialog.getLatestDialog() as ComponentDialog }
        compose.runOnIdle { dialog.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }
    private fun await(condition: () -> Boolean) = compose.waitUntil(10_000) {
        compose.waitForIdle()
        condition()
    }
    private fun waitText(text: String, substring: Boolean = false) = await {
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }
    private fun noProgress() = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
        .fetchSemanticsNodes().isEmpty()
    private fun body(request: RecordedRequest) = ApiJson.parseToJsonElement(request.body.clone().readUtf8()).jsonObject

    private fun withHost(
        derived: Boolean = false,
        total: Int = 2,
        description: String = "",
        capabilities: PlaylistCapabilities = if (derived) PlaylistCapabilities(editEntries = false, editDefinition = true,
            copyToManual = true, addTracks = false) else PlaylistCapabilities(),
        beforeMount: Fixture.() -> Unit = {},
        test: Fixture.() -> Unit,
    ) {
        val fixture = Fixture(derived, total, description, capabilities)
        val shown = mutableStateOf(true)
        var testFailure: Throwable? = null
        try {
            fixture.beforeMount()
            runBlocking { fixture.client.login("listener", "synthetic") }
            compose.setContent {
                MaterialTheme {
                    if (shown.value) PlaylistsRoute(fixture.client, {}, { _, _, _ -> fail("Browsing/management must not prepare playback") }, {},
                        initialPlaylistId = "fixture") // Intentionally no onQueueIntent or prepared-action callback.
                }
            }
            fixture.test()
            assertTrue("Browsing must not hit a playback preparation endpoint", fixture.requests.none {
                it.requestUrl?.encodedPath?.endsWith("/playback") == true
            })
        } catch (failure: Throwable) {
            testFailure = failure
            throw failure
        } finally {
            fixture.evaluationGate.countDown()
            var cleanupFailure: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() }
                catch (failure: Throwable) {
                    val previous = cleanupFailure
                    if (previous == null) cleanupFailure = failure else previous.addSuppressed(failure)
                }
            }
            cleanup {
                // Do not require an already-broken editor to become idle before removing it.
                compose.runOnUiThread { shown.value = false }
                compose.waitForIdle()
            }
            cleanup { fixture.client.httpClient.dispatcher.cancelAll() }
            cleanup { fixture.client.httpClient.connectionPool.evictAll() }
            cleanup { fixture.client.httpClient.dispatcher.executorService.shutdown() }
            cleanup { fixture.web.shutdown() }
            cleanupFailure?.let { failure ->
                val original = testFailure
                if (original == null) throw failure else original.addSuppressed(failure)
            }
        }
    }

    private inner class Fixture(
        val derived: Boolean,
        val total: Int,
        val description: String,
        val capabilities: PlaylistCapabilities,
    ) {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val failSummaryOnce = AtomicBoolean()
        val failEvaluation = AtomicBoolean()
        val failMetadata = AtomicBoolean()
        val failCopy = AtomicBoolean()
        val removed = AtomicBoolean()
        @Volatile var evaluationGate = CountDownLatch(0)
        val web = MockWebServer()
        val client: SpofifyClient
        private val track = LibraryItem("track", "same-song", "Repeated song",
            artists = listOf(ArtistCredit("artist", "Fixture artist")), hasAudio = true)

        init {
            web.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val path = request.requestUrl!!.encodedPath
                    return when {
                        path == "/api/auth/login" -> wire("""{"accountId":"account","username":"listener","role":"user","csrfToken":"synthetic"}""")
                            .setHeader("Set-Cookie", "spofify_session=synthetic; Path=/; HttpOnly; Max-Age=3600")
                        path == "/api/me/playlists" -> {
                            val item = if (request.requestUrl!!.queryParameter("kind") == "manual") {
                                PersonalPlaylistSummary("saved-copy", "Playlist fixture (copy)", trackCount = 2, availableCount = 2, revision = 1)
                            } else summary()
                            typedJson(PersonalPlaylistPage(listOf(item), 1, limit = request.requestUrl!!.queryParameter("limit")?.toInt() ?: 40))
                        }
                        path == detailPath && request.method == "GET" -> {
                            if (failSummaryOnce.getAndSet(false)) failure(503) else typedJson(summary())
                        }
                        path == detailPath && request.method == "PATCH" -> {
                            if (failMetadata.get()) failure(409) else typedJson(summary())
                        }
                        path == "$detailPath/entries/second-entry" && request.method == "DELETE" -> {
                            removed.set(true)
                            typedJson(summary())
                        }
                        path == "$detailPath/entries" && !derived -> {
                            val entries = listOf(PersonalPlaylistEntry("first-entry", 0, track)) +
                                if (removed.get()) emptyList() else listOf(PersonalPlaylistEntry("second-entry", 1, track))
                            typedJson(PersonalEntriesPage(entries, total - if (removed.get()) 1 else 0,
                                limit = request.requestUrl!!.queryParameter("limit")?.toInt() ?: 40))
                        }
                        path == "$detailPath/entries" && derived -> {
                            if (!evaluationGate.await(20, TimeUnit.SECONDS)) failure(503)
                            else if (failEvaluation.get()) failure(409)
                            else {
                                val items = if (total > 40) (0 until 40).map { index ->
                                    DerivedEvaluationEntry("path-$index", "opaque.$index.signature", index.toLong(), track)
                                } else listOf(
                                    DerivedEvaluationEntry("first-path", "opaque.first.signature", 0, track),
                                    DerivedEvaluationEntry("second-path", "opaque.second.signature", 1, track),
                                )
                                typedJson(DerivedEvaluationPage(items, total.toLong(), total.toLong(), 40,
                                    if (total > 40) "next-page" else null, 5, "evaluation-fence", true))
                            }
                        }
                        path == "$detailPath/definition" -> typedJson(DerivedDefinitionResult(Recipe(
                            listOf(RecipeSource.Likes("s_00000000000000000000000000000001")), RecipeDuplicates.KEEP, RecipeSort.SOURCE_ORDER,
                        ).toJson(), 5))
                        path == "$detailPath/copy" -> if (failCopy.get()) failure(503) else typedJson(summary())
                        path == "/api/me/likes/status" -> wire("""{"items":[{"trackId":"same-song","liked":false}]}""")
                        else -> failure(404)
                    }
                }
            }
            web.start(InetAddress.getByName("127.0.0.1"), 0)
            client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), object : SessionStore {
                override fun read(): StoredSession? = null
                override fun write(value: StoredSession?) = Unit
            })
        }

        fun summary() = PersonalPlaylistSummary("fixture", "Playlist fixture", description,
            kind = if (derived) PlaylistKind.DERIVED else PlaylistKind.MANUAL,
            countState = if (derived) PlaylistCountState.UNEVALUATED else PlaylistCountState.EXACT,
            trackCount = if (derived) null else (total - if (removed.get()) 1 else 0).toLong(),
            availableCount = if (derived) null else (total - if (removed.get()) 1 else 0).toLong(),
            capabilities = capabilities, revision = if (removed.get()) 6 else 5)
        fun writes() = requests.filter { request ->
            val path = request.requestUrl?.encodedPath
            // Like-status accepts a batch body via POST but does not mutate saved data.
            // Exempt only this exact read; like writes and every other unexpected POST still fail.
            val readOnlyStatus = request.method == "POST" && path == "/api/me/likes/status"
            request.method in listOf("POST", "PATCH", "PUT", "DELETE") &&
                path != "/api/auth/login" && !readOnlyStatus
        }
        fun awaitLoaded() = await {
            val count = compose.onAllNodesWithContentDescription(songMore).fetchSemanticsNodes().size
            noProgress() && if (total > 40) count > 0 else count == if (removed.get()) 1 else 2
        }
    }

    private fun wire(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private inline fun <reified T> typedJson(value: T) = wire(ApiJson.encodeToString(value))
    private fun failure(status: Int) = wire("""{"error":{"code":"${if (status == 409) "REVISION_CONFLICT" else "SYNTHETIC_FAILURE"}","message":"Synthetic fixture failure"}}""")
        .setResponseCode(status)

    private val songCount = SemanticsMatcher("one concise known song count") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.matches(Regex("[0-9]+ songs")) } == true
    }
    private val blankText = SemanticsMatcher("empty description Text") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.isBlank() } == true
    }
    private val managementActions = listOf("Edit details", "Edit rules", "Make editable copy", "Delete", "Reorder all occurrences")
    private val detailPath = "/api/me/playlists/fixture"
    private val playlistMore = "More actions for Playlist fixture"
    private val songMore = "More actions for Repeated song"
}
