package dev.spofify.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.playback.PlaybackInteractionKey
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synthetic pointer/lifetime checks; not native rendered-device validation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h850dp-mdpi")
class ContextualRowSwipeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun physicalRightTranslationAndReleaseAreIndependentOfRtl() {
        var commits = 0
        val key = PlaybackInteractionKey()
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme {
                    ContextualSwipeRow("song", key, true, "Play now", { captured, _ ->
                        assertSame(key, captured); commits++
                    }, Modifier.width(320.dp)) { modifier, _ ->
                        Box(modifier.height(72.dp).width(320.dp).background(MaterialTheme.colorScheme.background)
                            .testTag("row").clickable {})
                    }
                }
            }
        }
        val row = compose.onNodeWithTag("row")
        val before = row.getUnclippedBoundsInRoot()
        row.performTouchInput { down(Offset(100f, 36f)); moveTo(Offset(200f, 36f)) }
        compose.runOnIdle { assertEquals(0, commits) }
        assertTrue("RTL translation is physically right", row.getUnclippedBoundsInRoot().left > before.left)
        row.performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, commits) }
        assertEquals(before, row.getUnclippedBoundsInRoot())
        row.performTouchInput { swipe(Offset(220f, 36f), Offset(80f, 36f)) }
        compose.runOnIdle { assertEquals(1, commits) }
    }

    @Test fun identityRoundTripKeyLossCancelAndSecondTouchNeverCommit() {
        val identity = mutableStateOf("A")
        val key = mutableStateOf<PlaybackInteractionKey?>(PlaybackInteractionKey())
        var commits = 0
        compose.setContent {
            MaterialTheme {
                ContextualSwipeRow(identity.value, key.value, true, "Play now", { _, _ -> commits++ },
                    Modifier.width(320.dp)) { modifier, _ -> Box(modifier.fillMaxWidth().height(72.dp).testTag("row")) }
            }
        }
        val row = compose.onNodeWithTag("row")
        fun hold() { row.performTouchInput { down(Offset(80f, 36f)); moveTo(Offset(180f, 36f)) } }
        hold()
        compose.runOnIdle { identity.value = "B" }
        compose.runOnIdle { identity.value = "A" }
        row.performTouchInput { up() }
        hold()
        compose.runOnIdle { key.value = null }
        row.performTouchInput { up() }
        compose.runOnIdle { key.value = PlaybackInteractionKey() }
        hold(); row.performTouchInput { cancel() }
        hold()
        row.performTouchInput { down(1, Offset(200f, 40f)); up(1); up(0) }
        compose.runOnIdle { assertEquals(0, commits) }
    }

    @Test fun childConsumedMovementCancelsWithoutCommitOrTranslation() {
        val key = PlaybackInteractionKey()
        var commits = 0
        var consumedMovements = 0
        compose.setContent {
            MaterialTheme {
                ContextualSwipeRow("song", key, true, "Play now", { _, _ -> commits++ },
                    Modifier.width(320.dp)) { modifier, _ ->
                    Box(modifier.fillMaxWidth().height(72.dp).testTag("row")) {
                        Box(Modifier.matchParentSize().pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    event.changes.forEach { change ->
                                        if (change.position != change.previousPosition) {
                                            change.consume()
                                            consumedMovements++
                                        }
                                    }
                                }
                            }
                        })
                    }
                }
            }
        }
        val row = compose.onNodeWithTag("row")
        val before = row.getUnclippedBoundsInRoot()
        row.performTouchInput { swipe(Offset(80f, 36f), Offset(200f, 36f)) }
        compose.runOnIdle {
            assertTrue("The child actually consumed movement at Initial pass", consumedMovements > 0)
            assertEquals("A consumed drag cannot commit", 0, commits)
        }
        assertEquals(before, row.getUnclippedBoundsInRoot())
    }

    @Test fun verticalDragActuallyScrollsParentLazyColumnWithoutRowCommit() {
        val key = PlaybackInteractionKey()
        var commits = 0
        lateinit var listState: LazyListState
        compose.setContent {
            MaterialTheme {
                listState = rememberLazyListState()
                LazyColumn(Modifier.width(320.dp).height(300.dp).testTag("list"), state = listState) {
                    items(30, key = { it }) { index ->
                        ContextualSwipeRow("song-$index", key, true, "Play now", { _, _ -> commits++ }) { modifier, _ ->
                            Box(modifier.fillMaxWidth().height(72.dp).background(MaterialTheme.colorScheme.background).clickable {}) {
                                Text("Song $index")
                            }
                        }
                    }
                }
            }
        }
        compose.runOnIdle {
            assertEquals(0, listState.firstVisibleItemIndex)
            assertEquals(0, listState.firstVisibleItemScrollOffset)
        }
        compose.onNodeWithTag("list").performTouchInput {
            swipe(Offset(120f, 240f), Offset(120f, 40f), 300)
        }
        compose.runOnIdle {
            assertTrue("Vertical pointer movement must produce real parent-list scrolling",
                listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)
            assertEquals("Rows must not steal vertical scroll as a music action", 0, commits)
        }
    }

    @Test fun physicalEdgesMoreAndDedicatedHandleExcludeSwipesButCenterStillCommits() {
        val key = PlaybackInteractionKey()
        val hasHandle = mutableStateOf(false)
        var commits = 0
        compose.setContent {
            MaterialTheme {
                ContextualSwipeRow("song", key, true, "Play now", { _, _ -> commits++ },
                    Modifier.width(320.dp), excludeLeadingHandle = hasHandle.value) { modifier, _ ->
                    Box(modifier.fillMaxWidth().height(72.dp).testTag("row").clickable {})
                }
            }
        }
        val row = compose.onNodeWithTag("row")
        // x=12/308 lie in the physical 24dp edges; x=284 lies inside More's 48dp
        // target but outside the physical-right edge. All distances exceed commit threshold.
        for (start in listOf(12f, 308f, 284f)) {
            row.performTouchInput { swipe(Offset(start, 36f), Offset(start + 80f, 36f)) }
            compose.runOnIdle { assertEquals("Excluded pointer-down x=$start", 0, commits) }
        }
        compose.runOnIdle { hasHandle.value = true }
        // x=36 is outside the edge but inside the dedicated leading 48dp handle.
        row.performTouchInput { swipe(Offset(36f, 36f), Offset(136f, 36f)) }
        compose.runOnIdle { assertEquals("Handle must retain its own gesture target", 0, commits) }
        row.performTouchInput { swipe(Offset(80f, 36f), Offset(180f, 36f)) }
        compose.runOnIdle { assertEquals("The same row remains executable from its center", 1, commits) }
    }

    @Test fun harmlessProgressRecompositionWhileHeldPreservesBindingUntilOneFinalRelease() {
        val key = PlaybackInteractionKey()
        val progress = mutableStateOf(0)
        var commits = 0
        compose.setContent {
            MaterialTheme {
                ContextualSwipeRow("same-occurrence", key, true, "Add to queue", { captured, isCurrent ->
                    assertSame(key, captured)
                    assertTrue(isCurrent())
                    commits++
                }, Modifier.width(320.dp)) { modifier, _ ->
                    Box(modifier.fillMaxWidth().height(72.dp).testTag("row").clickable {}) {
                        Text("Position ${progress.value}")
                    }
                }
            }
        }
        val row = compose.onNodeWithTag("row")
        val before = row.getUnclippedBoundsInRoot()
        row.performTouchInput { down(Offset(80f, 36f)); moveTo(Offset(180f, 36f)) }
        val held = row.getUnclippedBoundsInRoot()
        assertTrue("A real right swipe is held beyond threshold", held.left > before.left)
        for (frame in 1..5) {
            compose.runOnIdle { progress.value = frame }
            compose.onNodeWithText("Position $frame").assertExists()
            assertEquals("Progress must not reset the held translation", held, row.getUnclippedBoundsInRoot())
            compose.runOnIdle { assertEquals("Progress is not a release", 0, commits) }
        }
        row.performTouchInput { up() }
        compose.runOnIdle { assertEquals("Only observed final release commits", 1, commits) }
        assertEquals(before, row.getUnclippedBoundsInRoot())
    }

    @Test fun acceptedActionGuardSurvivesOwnKeyAndBusyChangeButNotReplacementOrDisposal() {
        val identity = mutableStateOf("A")
        val key = mutableStateOf<PlaybackInteractionKey?>(PlaybackInteractionKey())
        val enabled = mutableStateOf(true)
        val shown = mutableStateOf(true)
        var guard: (() -> Boolean)? = null
        compose.setContent {
            MaterialTheme {
                if (shown.value) ContextualSwipeRow(identity.value, key.value, enabled.value, "Add to queue",
                    { _, captured -> guard = captured }, Modifier.width(320.dp)) { modifier, _ ->
                    Box(modifier.fillMaxWidth().height(72.dp).testTag("row"))
                }
            }
        }
        compose.onNodeWithTag("row").performTouchInput { swipe(Offset(80f, 36f), Offset(200f, 36f)) }
        compose.runOnIdle { assertTrue(guard!!.invoke()); key.value = PlaybackInteractionKey(); enabled.value = false }
        compose.runOnIdle { assertTrue("Own mutation/busy does not revoke caller lifetime", guard!!.invoke()); identity.value = "B" }
        compose.runOnIdle { assertFalse(guard!!.invoke()); identity.value = "A"; enabled.value = true }
        compose.runOnIdle { assertFalse("A→B→A never revives old caller", guard!!.invoke()) }
        compose.onNodeWithTag("row").performTouchInput { swipe(Offset(80f, 36f), Offset(200f, 36f)) }
        compose.runOnIdle { assertTrue(guard!!.invoke()); shown.value = false }
        compose.runOnIdle { assertFalse(guard!!.invoke()) }
    }
}
