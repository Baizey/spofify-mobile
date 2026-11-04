package dev.spofify.mobile.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.LibraryPage
import dev.spofify.mobile.ui.theme.AppTypography
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SearchPresentationTest {
    @get:Rule val compose = createComposeRule()

    private class Reader : CatalogReader {
        data class Read(val kind: String, val result: CompletableDeferred<LibraryPage> = CompletableDeferred())
        val reads = mutableListOf<Read>()
        override suspend fun browse(request: CatalogRequest): LibraryPage = Read(request.kind).also { reads += it }.result.await()
        override suspend fun detail(kind: String, id: String) = LibraryItem(kind, id, id)
        override suspend fun revision() = 1L
        fun last(kind: String) = reads.last { it.kind == kind }.result
        fun empty(kind: String) { last(kind).complete(LibraryPage(emptyList(), 0, revision = 1)) }
    }

    @Test fun noResultsWaitsForSettlementAndPartialFailuresRetainSuccessfulResults() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val reader = Reader()
        val holder = CatalogStateHolder(reader, scope, initialKind = "all", initialQuery = "music")
        try {
            compose.setContent {
                MaterialTheme(typography = AppTypography) {
                    val navigation by holder.state.collectAsState()
                    LaunchedEffect(holder) { holder.setVisible(true) }
                    CatalogScreenContent(navigation, holder, {}, { _, _ -> }, {}, {},
                        artwork = { _, modifier -> Box(modifier) }, modifier = Modifier.size(360.dp, 640.dp))
                }
            }
            compose.runOnIdle { reader.empty("artists"); reader.empty("tracks") }
            compose.onNodeWithText("No music matches this search.").assertDoesNotExist()
            compose.runOnIdle { reader.empty("albums") }
            compose.onAllNodesWithText("No music matches this search.").assertCountEquals(1)
            compose.runOnIdle { holder.retry("artists") }
            compose.onNodeWithText("No music matches this search.").assertDoesNotExist()
            compose.runOnIdle { reader.empty("artists") }
            compose.onAllNodesWithText("No music matches this search.").assertCountEquals(1)
            compose.runOnIdle { holder.retry("albums") }
            compose.onNodeWithText("No music matches this search.").assertDoesNotExist()
            compose.runOnIdle {
                reader.last("albums").complete(LibraryPage(listOf(LibraryItem("album", "album", "Album result")), 1, revision = 1))
            }
            compose.onNodeWithText("Album result").assertExists()
            listOf("Artists", "Albums", "Songs", "No music matches this search.",
                "No artists match this search.", "No songs match this search.").forEach {
                compose.onNodeWithText(it).assertDoesNotExist()
            }
            compose.runOnIdle { holder.retry("artists") }
            compose.runOnIdle { reader.last("artists").completeExceptionally(IOException()) }
            compose.onNodeWithText("Retry artists").assertExists()
            compose.onNodeWithText("Album result").assertExists()
            compose.onNodeWithText("No music matches this search.").assertDoesNotExist()
            compose.onNodeWithText("Retry artists").performClick()
            compose.onNodeWithText("Album result").assertExists()
            compose.runOnIdle {
                reader.last("artists").complete(LibraryPage(listOf(LibraryItem("artist", "artist", "Artist result")), 1, revision = 1))
            }
            compose.onNodeWithText("Artist result").assertExists()
            compose.onNodeWithText("Album result").assertExists()
            compose.onNodeWithText("Retry artists").assertDoesNotExist()
        } finally { holder.close(); scope.cancel() }
    }
}
