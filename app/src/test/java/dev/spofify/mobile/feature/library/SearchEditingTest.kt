package dev.spofify.mobile.feature.library

import android.view.View
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
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
import dev.spofify.mobile.ui.theme.AppTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.ExternalResource
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SearchEditingTest {
    // Compose test clicks dispatch to the owner view, bypassing ViewRoot's touch-mode entry.
    // Set the window-session mode before the activity attaches; a later @Before is too late.
    @get:Rule(order = 0) val touchMode = object : ExternalResource() {
        override fun before() { InstrumentationRegistry.getInstrumentation().setInTouchMode(true) }
        override fun after() { InstrumentationRegistry.getInstrumentation().setInTouchMode(false) }
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
