package dev.spofify.mobile.feature.library

import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.spofify.mobile.listeningColorScheme
import dev.spofify.mobile.ui.theme.AppTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Field-side development evidence; the real nav path and software IME still need native tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-xhdpi")
class SearchActivationFieldTest {
    @get:Rule(order = 0) val touchMode = object : TestWatcher() {
        override fun starting(description: Description) {
            InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        }
        override fun finished(description: Description) {
            InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        }
    }
    @get:Rule(order = 1) val compose = createComposeRule()

    @Test fun repeatedActivationClearsLocalDraftEvenWhenAcceptedQueryWasAlreadyEmpty() {
        val accepted = mutableStateOf("old")
        val activation = mutableStateOf<Long?>(null)
        val consumed = mutableListOf<Long>()
        val commits = mutableListOf<String>()
        compose.setContent {
            MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                CommittedSearchField(accepted.value, activationId = activation.value,
                    onActivationConsumed = { consumed += it }) { query, _ -> commits += query }
            }
        }
        val field = compose.onNodeWithTag("search-query")
        field.performClick()
        // OutlinedTextField may publish a selection-only value when clicked. Activation must
        // not publish a draft; isolate text edits from that harmless unchanged-query callback.
        compose.runOnIdle { commits.clear() }
        field.performTextReplacement("unpublished draft")
        compose.runOnIdle { accepted.value = ""; activation.value = 1 }
        field.assertEmptyEditableText().assertIsFocused()
        compose.runOnIdle { assertEquals(listOf(1L), consumed) }
        field.performTextReplacement("another local draft")
        compose.runOnIdle { activation.value = 2 }
        field.assertEmptyEditableText().assertIsFocused()
        compose.runOnIdle {
            assertEquals(listOf(1L, 2L), consumed)
            // Activation resets presentation; the owning state holder cleared accepted reads.
            assertEquals(listOf("unpublished draft", "another local draft"), commits)
            activation.value = null
        }
        field.assertEmptyEditableText().assertIsFocused()
        compose.runOnIdle { assertEquals(listOf(1L, 2L), consumed) }
    }

    @Test fun activationOverridesLiveComposingDraftWithoutPublishingIt() {
        val accepted = mutableStateOf("")
        val activation = mutableStateOf<Long?>(null)
        val compositions = mutableListOf<Boolean>()
        val commits = mutableListOf<String>()
        var view: View? = null
        compose.setContent {
            val current = LocalView.current
            SideEffect { view = current }
            MaterialTheme(typography = AppTypography) {
                CommittedSearchField(accepted.value, onCompositionChanged = { compositions += it },
                    activationId = activation.value) { text, _ -> commits += text }
            }
        }
        val field = compose.onNodeWithTag("search-query")
        field.performClick()
        compose.runOnIdle {
            val connection = checkNotNull(checkNotNull(view).onCreateInputConnection(EditorInfo()))
            assertTrue(connection.setComposingText("unfinished composition", 1))
        }
        compose.waitForIdle()
        // Material decorates live composing text with an underline annotation.
        assertEquals("unfinished composition",
            field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.runOnIdle {
            assertTrue(compositions.last())
            assertTrue("Uncommitted composition must not reach reads", commits.isEmpty())
            accepted.value = ""
            activation.value = 1L
        }
        field.assertEmptyEditableText().assertIsFocused()
        compose.runOnIdle {
            assertEquals(false, compositions.last())
            assertTrue("Reset presentation must not publish an old composing draft", commits.isEmpty())
        }
    }

    @Test fun consumedActivationCannotReplayOnLifecycleResumeButNewerIdentityStillDelivers() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        val activation = mutableStateOf<Long?>(1L)
        val consumed = mutableListOf<Long>()
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MaterialTheme(typography = AppTypography) {
                    CommittedSearchField("", activationId = activation.value,
                        onActivationConsumed = { consumed += it }) { _, _ -> }
                }
            }
        }
        val field = compose.onNodeWithTag("search-query")
        field.assertIsFocused()
        compose.runOnIdle { assertEquals(listOf(1L), consumed) }
        field.performTextReplacement("preserved local draft")
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.waitForIdle()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(1L), consumed) }
        assertEquals(AnnotatedString("preserved local draft"),
            field.fetchSemanticsNode().config[SemanticsProperties.EditableText])
        compose.runOnIdle { activation.value = 2L }
        field.assertEmptyEditableText().assertIsFocused()
        compose.runOnIdle { assertEquals(listOf(1L, 2L), consumed) }
    }

    private fun SemanticsNodeInteraction.assertEmptyEditableText(): SemanticsNodeInteraction = apply {
        assertEquals(AnnotatedString(""), fetchSemanticsNode().config[SemanticsProperties.EditableText])
    }
}
