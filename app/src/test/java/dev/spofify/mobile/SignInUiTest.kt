package dev.spofify.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.ui.theme.AppTypography
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synthetic composition/semantics regression checks, NOT native IME or visual approval. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h568dp-xhdpi")
class SignInUiTest {
    @get:Rule val compose = createComposeRule()
    private val state = mutableStateOf(LibraryState(restoring = false))
    private val saved = mutableStateOf<RememberedLogin?>(null)
    private val submissions = mutableListOf<Triple<String, String, String>>()
    private var reconnects = 0
    private val progress = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
    private val submitMatcher = hasClickAction() and
        (hasText("Sign in") or progress or hasAnyDescendant(progress))
    private fun submit() = compose.onNode(submitMatcher)
    // Disabled fields retain their editable value/label but intentionally remove SetText.
    private fun field(label: String) = compose.onNode(hasText(label) and
        SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
    private fun password() = field("Password")

    private fun mount(fontScale: Float = 1f, completeSynchronously: Boolean = false) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme(typography = AppTypography) {
                    Box(Modifier.requiredSize(320.dp, 568.dp)) {
                        SignIn(state.value, saved.value, signIn = { server, username, password ->
                            submissions += Triple(server, username, password)
                            state.value = state.value.copy(busy = true)
                            if (completeSynchronously) {
                                // Both publications happen inside the callback, before Compose
                                // can render busy. The form must still unlock after completion.
                                state.value = state.value.copy(busy = false, error = "Sign-in failed.")
                            }
                        }, reconnect = {
                            reconnects++
                            state.value = state.value.copy(busy = true)
                        })
                    }
                }
            }
        }
    }

    private fun fill(server: String = "https://music.example", username: String = "listener", secret: String = " synthetic password ") {
        field("Server URL").performScrollTo().performTextReplacement(server)
        field("Username").performScrollTo().performTextReplacement(username)
        password().performScrollTo().performTextReplacement(secret)
    }

    private fun assertMasked() {
        password().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithContentDescription("Show password").assertExists()
        compose.onNodeWithContentDescription("Hide password").assertDoesNotExist()
    }

    private fun assertNoClutter() {
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        for (copy in listOf(
            "Your music.", "Connect to Spofify", "Use your server's full origin", "HTTP is not encrypted",
            "Remember sign-in", "Saves the server", "Saved for", "Use saved login", "Forget saved login",
            "Use an existing Spofify account", "Spotify", "Font license", "Connect & listen",
        )) compose.onAllNodesWithText(copy, substring = true).assertCountEquals(0)
    }

    @Test fun startsWithOnlyTheModestHeadingLabeledFieldsRevealAndDisabledSignIn() {
        mount()
        compose.onNode(hasText("Sign in") and !hasClickAction()).assertIsDisplayed()
        for (label in listOf("Server URL", "Username", "Password")) field(label).performScrollTo().assertIsDisplayed()
        submit().performScrollTo().assertIsDisplayed().assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Show password").assertHeightIsAtLeast(48.dp)
        assertMasked()
        assertNoClutter()
        compose.runOnIdle { assertTrue(submissions.isEmpty()); assertEquals(0, reconnects) }
    }

    @Test fun explicitHttpAndHttpsNeedNoWarningCheckboxOrExtraAction() {
        mount()
        for (origin in listOf("http://music.example:8080", "http://8.8.8.8", "http://192.168.1.10:8080", "https://music.example")) {
            fill(server = origin)
            submit().performScrollTo().assertIsEnabled()
            assertNoClutter()
        }
        compose.runOnIdle { assertTrue(submissions.isEmpty()) } // Editing/autofill never authenticates.
    }

    @Test fun invalidOrIncompleteInputsGuardBothButtonAndImeDone() {
        mount()
        for ((server, username, secret) in listOf(
            Triple("https://music.example/api", "listener", "synthetic"),
            Triple("music.example", "listener", "synthetic"),
            Triple("https://music.example", "", "synthetic"),
            Triple("https://music.example", "listener", ""),
        )) {
            fill(server, username, secret)
            submit().performScrollTo().assertIsNotEnabled()
            // Invoke the installed action as well as checking enabled semantics: disabled-button
            // presentation alone must not leave an unguarded keyboard authentication path.
            password().performScrollTo().performImeAction()
            compose.runOnIdle { assertTrue(submissions.isEmpty()) }
        }
        field("Server URL").performScrollTo().performTextReplacement("https://music.example/api")
        compose.onNodeWithText("Enter only an HTTP(S) origin, without credentials, path, query or fragment")
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun imeNextAdvancesFocusAndDoneSubmitsExactlyTheUnmodifiedFieldsMasked() {
        mount()
        fill()
        field("Server URL").assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Next))
            .performScrollTo().performClick().performImeAction()
        field("Username").assertIsFocused()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Next)).performImeAction()
        password().assertIsFocused().assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Done))
        compose.onNodeWithContentDescription("Show password").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Hide password").assertExists()
        password().performImeAction()
        assertMasked()
        compose.runOnIdle { assertEquals(listOf(Triple("https://music.example", "listener", " synthetic password ")), submissions) }
    }

    @Test fun sameFrameDoneAndButtonCannotStartDuplicateAuthenticationAndBusyStillGuardsCallbacks() {
        mount()
        fill()
        password().performScrollTo().performClick()
        // Capture both callbacks before any recomposition, then exercise a rapid double delivery.
        // The synchronous host publishes busy, but Compose has not consumed it on the second call.
        val done = checkNotNull(password().fetchSemanticsNode().config.getOrNull(SemanticsActions.OnImeAction)?.action)
        val click = checkNotNull(submit().fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.action)
        compose.runOnIdle { done(); click(); done(); assertEquals(1, submissions.size) }
        submit().performScrollTo().assertIsNotEnabled()
        field("Server URL").assertIsNotEnabled()
        field("Username").assertIsNotEnabled()
        password().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Show password").assertIsNotEnabled()
        compose.runOnIdle { click(); done(); assertEquals(1, submissions.size) }
        // A real completed failed attempt can be corrected and deliberately submitted again.
        compose.runOnIdle { state.value = state.value.copy(busy = false, error = "Sign-in failed.") }
        password().performScrollTo().performTextReplacement("corrected synthetic password")
        submit().performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, submissions.size) }
    }

    @Test fun synchronousFailureBeforeBusyRendersBlocksSameFrameDuplicatesButAllowsADeliberateRetry() {
        mount(completeSynchronously = true)
        val edited = Triple("https://edited.example:8443", "edited-listener", " edited synthetic password ")
        fill(edited.first, edited.second, edited.third)
        compose.onNodeWithContentDescription("Show password").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Hide password").assertExists()
        password().performScrollTo().performClick()
        val done = checkNotNull(password().fetchSemanticsNode().config.getOrNull(SemanticsActions.OnImeAction)?.action)
        val click = checkNotNull(submit().fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.action)
        compose.runOnIdle {
            done()
            click()
            done()
            assertEquals(listOf(edited), submissions)
            assertFalse(state.value.busy)
            assertEquals("Sign-in failed.", state.value.error)
        }
        compose.waitForIdle()
        compose.onNodeWithText("Sign-in failed.").performScrollTo().assertIsDisplayed()
        field("Server URL").assertTextContains(edited.first).assertIsEnabled()
        field("Username").assertTextContains(edited.second).assertIsEnabled()
        password().assertIsEnabled()
        assertMasked()
        // No text replacement or reveal toggle repairs the latch: the retained form itself must
        // be enabled after settling, and the next intentional tap must submit the exact edits.
        submit().performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()
        assertMasked()
        submit().performScrollTo().assertIsEnabled()
        compose.runOnIdle { assertEquals(listOf(edited, edited), submissions) }
    }

    @Test fun wholeSavedTripleWinsOnceButUserEditsSurviveServerBusyErrorAndSavedRecordPublications() {
        saved.value = RememberedLogin("https://saved.example", "saved-listener", "saved synthetic password")
        state.value = state.value.copy(server = "https://failed-later.example")
        mount()
        field("Server URL").assertTextContains("https://saved.example")
        field("Username").assertTextContains("saved-listener")
        compose.onNodeWithContentDescription("Show password").performScrollTo().performClick()
        password().assertTextContains("saved synthetic password")
        fill("https://edited.example:8443", "edited-listener", " edited synthetic password ")
        for (publication in listOf(
            state.value.copy(server = "https://another-failed.example", busy = true),
            state.value.copy(server = "https://another-failed.example", error = "Network unavailable.", canRetryConnection = true),
        )) {
            compose.runOnIdle {
                state.value = publication
                saved.value = RememberedLogin("https://replacement.example", "replacement", "different synthetic password")
            }
            field("Server URL").assertTextContains("https://edited.example:8443")
            field("Username").assertTextContains("edited-listener")
        }
        compose.onNodeWithContentDescription("Show password").performScrollTo().performClick()
        password().assertTextContains(" edited synthetic password ")
        compose.onNodeWithText("Retry saved connection").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, reconnects); assertTrue(submissions.isEmpty()) }
        field("Server URL").assertTextContains("https://edited.example:8443")
        field("Username").assertTextContains("edited-listener")
        password().assertTextContains(" edited synthetic password ")
    }

    @Test fun identityEditsClearRevealAndNeverResurrectTheSavedSecret() {
        saved.value = RememberedLogin("https://saved.example", "listener", "saved synthetic password")
        mount()
        compose.onNodeWithContentDescription("Show password").performScrollTo().performClick()
        field("Username").performScrollTo().performTextReplacement("other-listener")
        assertMasked()
        field("Username").performTextReplacement("listener")
        password().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        submit().performScrollTo().assertIsNotEnabled()
        password().performScrollTo().performTextReplacement("new synthetic password")
        compose.onNodeWithContentDescription("Show password").performScrollTo().performClick()
        field("Server URL").performScrollTo().performTextReplacement("https://saved.example:8443")
        assertMasked()
        field("Server URL").performTextReplacement("https://saved.example")
        password().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        submit().performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(error = "Try again.") }
        submit().assertIsNotEnabled()
        compose.runOnIdle { assertTrue(submissions.isEmpty()) }
    }

    @Test fun narrowFont2FieldsErrorRetryAndSubmitStayScrollReachableWithProductionTypography() {
        state.value = state.value.copy(error = "Cannot connect. Try again.", canRetryConnection = true)
        mount(fontScale = 2f)
        fill("https://long-synthetic-server.example:8443", "listener-長い名前", "font2 synthetic password")
        for (label in listOf("Server URL", "Username", "Password")) {
            field(label).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithContentDescription("Show password").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Cannot connect. Try again.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Retry saved connection").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val button = submit().performScrollTo().assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
        val viewport = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val target = button.fetchSemanticsNode().boundsInRoot
        assertTrue("Submit must fit inside the narrow host", target.left >= viewport.left && target.right <= viewport.right &&
            target.top >= viewport.top && target.bottom <= viewport.bottom)
        assertNoClutter()
        password().performScrollTo().performClick().performImeAction()
        compose.runOnIdle {
            assertEquals(listOf(Triple("https://long-synthetic-server.example:8443", "listener-長い名前", "font2 synthetic password")), submissions)
        }
    }
}
