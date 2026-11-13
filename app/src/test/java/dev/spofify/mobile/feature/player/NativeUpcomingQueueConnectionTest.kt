package dev.spofify.mobile.feature.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.SpofifyApplication
import dev.spofify.mobile.core.LibraryPlaybackCommand
import dev.spofify.mobile.playback.OnlineQueueConnectionFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Actual retained UI callbacks -> real connection/coordinator -> controlled current socket.
 * No UI recomposition occurs between authority changes and old clicks; exercise both sampled
 * and unsampled connection publications. This is not a server reducer or audible-output oracle. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SpofifyApplication::class, qualifiers = "w400dp-h850dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(ExperimentalCoroutinesApi::class)
class NativeUpcomingQueueConnectionTest {
    @get:Rule val compose = createComposeRule()

    private fun fixture(block: suspend TestScope.(OnlineQueueConnectionFixture) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { OnlineQueueConnectionFixture(this).use { block(it) } }
        finally { Dispatchers.resetMain() }
    }

    private fun accessibilityEdits(): List<() -> Boolean> {
        val rows = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
        val first = rows[0].fetchSemanticsNode().config[SemanticsActions.CustomActions]
        val second = rows[1].fetchSemanticsNode().config[SemanticsActions.CustomActions]
        return listOf(first.single { it.label == "Move down" }.action,
            second.single { it.label == "Move up" }.action,
            first.single { it.label == "Remove from queue" }.action)
    }

    private fun menuEdits(): List<() -> Boolean> {
        compose.onNodeWithContentDescription("Queue actions for Occurrence 32").performClick()
        return listOf("Move down", "Remove from queue").map { label ->
            requireNotNull(compose.onNodeWithText(label).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        }
    }

    @Test fun heldAccessibilityEditsRejectUnsampledStateAndOutputAbaInBothPresentations() = checkHeldEdits(menu = false)
    @Test fun heldMoreEditsRejectUnsampledStateAndOutputAbaInBothPresentations() = checkHeldEdits(menu = true)

    private fun checkHeldEdits(menu: Boolean) = fixture { f ->
        val compact = mutableStateOf(false)
        val rendered = mutableStateOf(f.connection.state.value)
        val shown = mutableStateOf(true)
        compose.setContent { MaterialTheme { Column(Modifier.size(400.dp, 500.dp)) {
            if (shown.value) NativeUpcomingQueue(rendered.value, f.connection, {}, { _, modifier -> Box(modifier) }, compact = compact.value)
        } } }
        for (presentation in listOf(false, true)) {
            for ((change, sampleConnection) in listOf("state", "output", "selection").flatMap { change ->
                listOf(change to false, change to true)
            }) {
                compose.runOnIdle {
                    f.refresh()
                    compact.value = presentation
                    rendered.value = f.connection.state.value
                }
                val callbacks = if (menu) menuEdits() else accessibilityEdits()
                compose.runOnIdle {
                    val oldKey = rendered.value.interactionKey
                    val original = f.snapshot
                    when (change) {
                        "state" -> {
                            val reordered = original.state.copy(queue = listOf(original.state.queue[0], original.state.queue[2], original.state.queue[1]))
                            f.publish(original.copy(stateVersion = original.stateVersion + 1, state = reordered), refreshConnection = false)
                            f.publish(original.copy(stateVersion = original.stateVersion + 2), refreshConnection = false)
                        }
                        "output" -> {
                            f.publish(original.copy(stateVersion = original.stateVersion + 1, outputVersion = original.outputVersion + 1,
                                selectedDeviceId = "phone", selectedConnectionId = "socket-phone"), refreshConnection = false)
                            f.publish(original.copy(stateVersion = original.stateVersion + 2, outputVersion = original.outputVersion + 2), refreshConnection = false)
                        }
                        else -> {
                            // Even without a new server/UI frame, a same-target selection ABA
                            // must invalidate the old callback instead of borrowing fresh authority.
                            f.owner.select("phone")
                            f.owner.select("speaker")
                        }
                    }
                    if (sampleConnection) {
                        f.refresh() // Connection publication is newer, but the rendered row/menu is still old.
                        assertNotSame(oldKey, f.connection.state.value.interactionKey)
                    } else {
                        assertSame("Unsampled authority transition", oldKey, f.connection.state.value.interactionKey)
                    }
                    assertSame("No UI recomposition may refresh the held callback", oldKey, rendered.value.interactionKey)
                    val before = f.commands.toList()
                    callbacks.forEach { it() }
                    assertEquals("Held $change edits cannot reach the socket", before, f.commands)
                    assertEquals(original.state.queue, f.owner.state.value.snapshot!!.state.queue)
                    assertFalse(f.connection.state.value.isPlaying)
                    assertEquals(42_125L, f.connection.state.value.positionMs)
                }
                // Retire any old menu before the next deliberate rendered capture.
                if (menu) compose.waitForIdle()
            }
        }
        compose.runOnIdle { shown.value = false }
        compose.waitForIdle()
        f.assertNoHttpPlayback()
    }

    @Test fun freshEditsUseCapturedPreMoveAnchorsAndRemovalUsesExactDuplicate() = fixture { f ->
        val compact = mutableStateOf(false)
        val rendered = mutableStateOf(f.connection.state.value)
        compose.setContent { MaterialTheme { Column(Modifier.size(400.dp, 500.dp)) {
            NativeUpcomingQueue(rendered.value, f.connection, {}, { _, modifier -> Box(modifier) }, compact = compact.value)
        } } }
        for ((presentation, menu) in listOf(false, true).flatMap { compact -> listOf(compact to false, compact to true) }) {
            compose.runOnIdle { compact.value = presentation; rendered.value = f.connection.state.value }
            val edits = if (menu) menuEdits() else accessibilityEdits()
            val expected = if (menu) listOf(LibraryPlaybackCommand.Move(32, 91), LibraryPlaybackCommand.Remove(32))
                else listOf(LibraryPlaybackCommand.Move(32, 91), LibraryPlaybackCommand.Move(91, null), LibraryPlaybackCommand.Remove(32))
            edits.zip(expected).forEach { (callback, command) ->
                compose.runOnIdle {
                    val before = f.commands.size
                    callback()
                    assertEquals(before + 1, f.commands.size)
                    assertEquals(command, f.commands.last().command)
                    assertEquals(f.snapshot.stateVersion, f.commands.last().expectedStateVersion)
                    assertEquals(listOf(7L, 32L, 91L), f.owner.state.value.snapshot!!.state.queue.map { it.key })
                    assertEquals(42_125L, f.connection.state.value.positionMs)
                    assertFalse(f.connection.state.value.isPlaying)
                    f.result()
                }
            }
        }
        f.assertNoHttpPlayback()
    }

    @Test fun progressOnlyPublicationPreservesHeldMoveAndExactAnchor() = fixture { f ->
        val rendered = f.connection.state.value
        compose.setContent { MaterialTheme { Column(Modifier.size(400.dp, 500.dp)) {
            NativeUpcomingQueue(rendered, f.connection, {}, { _, modifier -> Box(modifier) })
        } } }
        val move = accessibilityEdits().first()
        compose.runOnIdle {
            val source = f.snapshot
            f.publish(source.copy(state = source.state.copy(elapsed = 43.125),
                playback = source.playback.copy(elapsed = 43.125, playbackSequence = source.playback.playbackSequence + 1)))
            assertSame(rendered.interactionKey, f.connection.state.value.interactionKey)
            move()
            assertEquals(LibraryPlaybackCommand.Move(32, 91), f.commands.single().command)
            assertEquals(43_125L, f.connection.state.value.positionMs)
            assertFalse(f.connection.state.value.isPlaying)
        }
        f.assertNoHttpPlayback()
    }

    @Test fun disposedQueueRejectsItsHeldEditCallbacksEvenWhenAuthorityIsUnchanged() = fixture { f ->
        val shown = mutableStateOf(true)
        compose.setContent { MaterialTheme { Column(Modifier.size(400.dp, 500.dp)) {
            if (shown.value) NativeUpcomingQueue(f.connection.state.value, f.connection, {}, { _, modifier -> Box(modifier) })
        } } }
        val callbacks = accessibilityEdits()
        compose.runOnIdle { shown.value = false }
        compose.waitForIdle()
        compose.runOnIdle {
            callbacks.forEach { it() }
            assertTrue(f.commands.isEmpty())
        }
        f.assertNoHttpPlayback()
    }
}
