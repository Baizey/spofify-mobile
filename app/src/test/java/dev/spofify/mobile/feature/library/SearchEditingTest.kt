package dev.spofify.mobile.feature.library

import android.view.View
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.Key
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.semantics.SemanticsActions
import dev.spofify.mobile.ui.theme.AppTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SearchEditingTest {
    // Compose test clicks dispatch to the owner view, bypassing ViewRoot's touch-mode entry.
    // Set the window-session mode before the activity attaches; a later @Before is too late.
    @get:Rule(order = 0) val touchMode = object : TestWatcher() {
        override fun starting(description: Description) {
            InstrumentationRegistry.getInstrumentation().setInTouchMode(!description.methodName.startsWith("nonTouch"))
        }
        override fun finished(description: Description) {
            InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        }
    }
    @get:Rule(order = 1) val compose = createComposeRule()

    @Test fun observedImeClosingClearsFocusButTheNextHiddenImeFocusIsNotImmediatelyCleared() {
        val events = mutableListOf<Boolean>()
        var view: View? = null
        var observedImeBottom = -1
        compose.setContent {
            MaterialTheme(typography = AppTypography) {
                val currentView = LocalView.current
                val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
                SideEffect { view = currentView; observedImeBottom = imeBottom }
                CommittedSearchField("music", onSearchEditingChanged = { events += it }) { _, _ -> }
            }
        }
        fun dispatchIme(visible: Boolean) {
            // Synthetic insets exercise the focus lifecycle, not real-device IME rendering.
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), if (visible) Insets.of(0, 0, 0, 300) else Insets.NONE)
                .setVisible(WindowInsetsCompat.Type.ime(), visible).build()
            ViewCompat.dispatchApplyWindowInsets(checkNotNull(view), insets)
        }
        val query = compose.onNodeWithContentDescription("Search your music")
        compose.runOnIdle { assertTrue("Tap-initiated editing must run in touch mode", checkNotNull(view).isInTouchMode) }
        query.performClick()
        query.assertIsFocused()
        compose.runOnIdle { assertEquals(listOf(true), events); dispatchIme(true) }
        query.assertIsFocused()
        compose.runOnIdle { assertEquals(300, observedImeBottom); assertEquals(listOf(true), events); dispatchIme(false) }
        compose.runOnIdle { assertEquals(0, observedImeBottom) }
        query.assertIsNotFocused()
        compose.runOnIdle { assertEquals(listOf(true, false), events) }
        query.performClick()
        compose.waitForIdle()
        query.assertIsFocused()
        compose.runOnIdle { dispatchIme(false) }
        query.assertIsFocused()
        compose.runOnIdle { assertEquals(listOf(true, false, true), events) }
    }

    @Test fun nonTouchBackActuallyEndsEditingWhenTheQueryIsTheOnlyFocusTarget() {
        assertNonTouchEditingEnds(submit = false)
    }

    @Test fun nonTouchSubmitActuallyEndsEditingWhenTheQueryIsTheOnlyFocusTarget() {
        assertNonTouchEditingEnds(submit = true)
    }

    @Test fun nonTouchRouteFocusClearActuallyEndsEditingWhenTheQueryIsTheOnlyFocusTarget() {
        assertNonTouchEditingEnds(submit = null)
    }

    private fun assertNonTouchEditingEnds(submit: Boolean?) {
        val events = mutableListOf<Boolean>()
        var dispatcher: OnBackPressedDispatcher? = null
        var view: View? = null
        var clearFocus: (() -> Unit)? = null
        compose.setContent {
            MaterialTheme(typography = AppTypography) {
                val currentDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                val currentView = LocalView.current
                SideEffect { dispatcher = currentDispatcher; view = currentView }
                val focus = LocalFocusManager.current
                SideEffect { clearFocus = { focus.clearFocus() } }
                // Empty Search has no clear action; while editing, the shell also hides its bars.
                // Do not add another focus target that would conceal Android owner's refocus.
                CommittedSearchField("", onSearchEditingChanged = { events += it }) { _, _ -> }
            }
        }
        val query = compose.onNodeWithContentDescription("Search your music")
        compose.runOnIdle { assertTrue("Hardware-keyboard editing must run outside touch mode", !checkNotNull(view).isInTouchMode) }
        query.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        query.assertIsFocused()
        compose.runOnIdle { assertEquals(true, events.last()) }
        when (submit) {
            true -> query.performImeAction()
            false -> compose.runOnIdle {
                assertTrue("IME-hidden Back handler must be active", checkNotNull(dispatcher).hasEnabledCallbacks())
                checkNotNull(dispatcher).onBackPressed()
            }
            null -> compose.runOnIdle { checkNotNull(clearFocus).invoke() }
        }
        query.assertIsNotFocused()
        compose.runOnIdle {
            assertEquals(false, events.last())
            assertTrue("Ending editing must not switch a hardware keyboard into touch mode", !checkNotNull(view).isInTouchMode)
        }
        query.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        query.assertIsFocused()
        compose.runOnIdle { assertEquals(true, events.last()) }
    }

    @Test fun nonTouchTabTraversalSkipsRestingAncestorBeforeAndAfterEditingEnds() {
        var dispatcher: OnBackPressedDispatcher? = null
        var view: View? = null
        val events = mutableListOf<Boolean>()
        compose.setContent {
            MaterialTheme(typography = AppTypography) {
                val currentDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                val currentView = LocalView.current
                SideEffect { dispatcher = currentDispatcher; view = currentView }
                Column {
                    Button(onClick = {}) { Text("Before") }
                    CommittedSearchField("", onSearchEditingChanged = { events += it }) { _, _ -> }
                    Button(onClick = {}) { Text("After") }
                }
            }
        }
        compose.runOnIdle { assertTrue(!checkNotNull(view).isInTouchMode) }
        val before = compose.onNodeWithText("Before")
        val after = compose.onNodeWithText("After")
        val query = compose.onNodeWithContentDescription("Search your music")
        fun tab(backward: Boolean = false) {
            compose.onRoot().performKeyInput {
                if (backward) keyDown(Key.ShiftLeft)
                pressKey(Key.Tab)
                if (backward) keyUp(Key.ShiftLeft)
            }
        }
        before.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        before.assertIsFocused()
        tab(); query.assertIsFocused()
        tab(); after.assertIsFocused()
        tab(backward = true); query.assertIsFocused()
        tab(backward = true); before.assertIsFocused()
        tab(); query.assertIsFocused()
        compose.runOnIdle { checkNotNull(dispatcher).onBackPressed() }
        query.assertIsNotFocused()
        compose.runOnIdle { assertEquals(false, events.last()) }
        tab(); query.assertIsFocused()
        tab(); after.assertIsFocused()
        tab(backward = true); query.assertIsFocused()
        compose.runOnIdle { checkNotNull(dispatcher).onBackPressed() }
        query.assertIsNotFocused()
        tab(backward = true); before.assertIsFocused()
        tab(); query.assertIsFocused()
        tab(backward = true); before.assertIsFocused()
    }

    @Test fun focusSurvivesInitialHiddenImeAndCallbackReplacementUntilBackOrSubmit() {
        val events = mutableListOf<String>()
        val callback = mutableStateOf<(Boolean) -> Unit>({ events += "first:$it" })
        val shown = mutableStateOf(true)
        var dispatcher: OnBackPressedDispatcher? = null
        var view: View? = null
        compose.setContent {
            MaterialTheme(typography = AppTypography) {
                val currentDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                val currentView = LocalView.current
                SideEffect { dispatcher = currentDispatcher; view = currentView }
                if (shown.value) CommittedSearchField("music", onSearchEditingChanged = callback.value) { _, _ -> }
            }
        }
        val query = compose.onNodeWithContentDescription("Search your music")
        compose.runOnIdle { assertTrue("Tap-initiated editing must run in touch mode", checkNotNull(view).isInTouchMode) }
        query.performClick()
        compose.waitForIdle()
        query.assertIsFocused()
        compose.runOnIdle { assertEquals(listOf("first:true"), events); callback.value = { events += "second:$it" } }
        compose.waitForIdle()
        query.assertIsFocused()
        compose.runOnIdle {
            assertEquals(listOf("first:true"), events)
            assertTrue("IME-hidden Back handler must be active", checkNotNull(dispatcher).hasEnabledCallbacks())
            checkNotNull(dispatcher).onBackPressed()
        }
        query.assertIsNotFocused()
        compose.runOnIdle { assertEquals("second:false", events.last()) }
        query.performClick().performImeAction()
        query.assertIsNotFocused()
        compose.runOnIdle { assertEquals(listOf("second:true", "second:false"), events.takeLast(2)) }
        query.performClick()
        compose.runOnIdle { shown.value = false }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("second:false", events.last()) }
    }
}
