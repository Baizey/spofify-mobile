package dev.spofify.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsActions
import dev.spofify.mobile.core.QueueIntentIntent
import dev.spofify.mobile.playback.PlaybackInteractionKey
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.ui.theme.AppTypography
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Presentation fixtures only; these tests do not claim verified-store/media delivery. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-xhdpi")
class OfflineLibraryUiTest {
    @get:Rule val compose = createComposeRule()
    private val owner = object : OfflineLibraryAccess {
        override val revision = kotlinx.coroutines.flow.MutableStateFlow(0L)
        override suspend fun collections(origin: String, accountId: String) = emptyList<OfflineSavedCollection>()
        override fun retire() = Unit
    }
    private fun fixture(complete: Boolean): OfflineSavedCollection {
        val rows = listOf(
            LibraryItem("track", "first", "First", hasAudio = true, entryId = "e1"),
            LibraryItem("track", "second", "Second", hasAudio = complete, entryId = "e2"),
        )
        val media = (if (complete) rows else rows.take(1)).associate { row ->
            row.entryId!! to OfflineMediaReference(owner, "https://synthetic.invalid", "account") { null }
        }
        return OfflineSavedCollection(LibraryItem("album", "album", "Saved album"), rows, media, complete)
    }
    private fun show(snapshot: OfflineSavedCollection,
        onPlay: (OfflineSavedCollection, LibraryItem?) -> Unit,
        onMore: (OfflineSavedCollection, LibraryItem, () -> Boolean) -> Unit = { _, _, _ -> }) {
        compose.setContent {
            MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                OfflineSavedLibrary(listOf(snapshot), snapshot, false, false, {}, {}, onPlay,
                    { _, bounds -> Box(bounds) }, Modifier.fillMaxSize(), onMore)
            }
        }
    }

    @Test fun incompleteSnapshotRequiresExplicitPlayAvailableAndKeepsVerifiedMoreUsable() {
        val snapshot = fixture(false)
        val played = mutableListOf<Pair<OfflineSavedCollection, LibraryItem?>>()
        val menus = mutableListOf<String?>()
        show(snapshot, { collection, row -> played += collection to row },
            { collection, row, current -> assertSame(snapshot, collection); assertTrue(current()); menus += row.entryId })
        compose.onNodeWithText("Play available").performClick()
        assertEquals(1, played.size)
        assertSame(snapshot, played.single().first)
        assertNull(played.single().second)
        assertEquals(2, snapshot.rows.size)
        assertEquals(listOf("e1"), snapshot.availableRows.map { it.entryId })
        compose.onNodeWithText("First").assert(hasClickAction().not())
        compose.onNodeWithContentDescription("More actions for First").performClick()
        assertEquals(listOf("e1"), menus)
        compose.onNodeWithContentDescription("More actions for Second").assertIsNotEnabled()
        compose.onNodeWithText("Play").assertDoesNotExist()
    }

    @Test fun savedSongNamedPrioritiesIncludeHighAndNeverEncodeItAsLowOrNext() {
        val actions = mutableListOf<QueueIntentIntent>()
        compose.setContent {
            MaterialTheme { Column { OfflineSavedQueueActions(true) { actions += it } } }
        }
        compose.onNodeWithText("Queue High").performClick()
        compose.onNodeWithText("Queue Low").performClick()
        compose.onNodeWithText("Play next").performClick()
        assertEquals(listOf(QueueIntentIntent.HIGH, QueueIntentIntent.LOW, QueueIntentIntent.NEXT), actions)
    }

    @Test fun savedSongDefaultQueueUsesCapturedHighActionAndDoesNotPlayOrShortenContext() {
        val snapshot = fixture(true)
        val key = PlaybackInteractionKey()
        val queued = mutableListOf<String?>()
        var plays = 0
        var held: (() -> Boolean)? = null
        compose.setContent {
            MaterialTheme {
                OfflineSavedLibrary(listOf(snapshot), snapshot, false, false, {}, {}, { _, _ -> plays++ },
                    { _, bounds -> Box(bounds) }, Modifier.fillMaxSize(), { _, _, _ -> }, queueKey = key,
                    onQueueHigh = { collection, row, captured, current ->
                        assertSame(snapshot, collection)
                        assertSame(key, captured)
                        assertTrue(current())
                        held = current
                        queued += row.entryId
                    })
            }
        }
        val actions = compose.onNode(hasText("First") and SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
            .fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { assertTrue(actions.single { it.label == "Queue" }.action()) }
        assertEquals(listOf("e1"), queued)
        assertEquals(0, plays)
        assertEquals(listOf("e1", "e2"), snapshot.rows.map { it.entryId })
        compose.onNodeWithTag("offline-filter").performTextReplacement("Second")
        compose.runOnIdle { assertFalse("Removed source rows must retire deferred actions", requireNotNull(held).invoke()) }
    }

    @Test fun localFilterDoesNotShortenTheCompletePlaybackContext() {
        val snapshot = fixture(true)
        val played = mutableListOf<Pair<OfflineSavedCollection, LibraryItem?>>()
        show(snapshot, { collection, row -> played += collection to row })
        compose.onNodeWithTag("offline-filter").performTextReplacement("Second")
        compose.onNodeWithText("First").assertDoesNotExist()
        compose.onNode(hasText("Second") and hasTestTag("offline-filter").not()).performClick()
        assertEquals(1, played.size)
        assertSame(snapshot, played.single().first)
        assertEquals("e2", played.single().second!!.entryId)
        assertEquals(listOf("e1", "e2"), played.single().first.availableRows.map { it.entryId })
    }
}
