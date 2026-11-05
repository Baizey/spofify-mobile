package dev.spofify.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.ServerAddress
import dev.spofify.mobile.ui.theme.AppTypography
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Layout assertions only; neither screenshots nor native large-font acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun longCanonicalOriginAndUnicodeUsernameWrapWithoutClippingAt320Font2() {
        val username = "音楽を楽しむリスナー · Élodie Καλημέρα · a deliberately long synthetic listener name"
        val origin = "https://xn--bcher-kva.very-long-synthetic-listener-server-name.private-listening.example.com:8443"
        assertEquals("Fixture must be a canonical credential-free origin", origin, ServerAddress.parse(origin).origin)
        val actions = mutableListOf<String>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                    Box(Modifier.requiredSize(320.dp, 640.dp)) {
                        ProfileScreen(username, origin, busy = false,
                            onCredits = { actions += "credits" },
                            onSignOut = { actions += "sign-out" })
                    }
                }
            }
        }
        listOf(username, origin).forEach { text ->
            scrollTo(text).assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                assertTrue(action(layouts))
            }
            assertEquals(1, layouts.size)
            val layout = layouts.single()
            assertTrue("Essential identity must genuinely wrap: $text", layout.lineCount > 1)
            assertFalse("Essential identity cannot overflow its text bounds", layout.hasVisualOverflow)
            assertTrue("Essential identity must remain complete", (0 until layout.lineCount).none(layout::isLineEllipsized))
        }
        compose.onNodeWithText("Playback devices").assertDoesNotExist()
        listOf("Credits", "Sign out").forEach { label ->
            scrollTo(label).assertIsDisplayed().assertIsEnabled()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        }
        assertEquals(listOf("credits", "sign-out"), actions)
        scrollTo("Username").assertIsDisplayed() // The long content also remains reachable again.
    }

    private fun scrollTo(text: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("profile-screen").performScrollToNode(hasText(text))
        return compose.onNodeWithText(text)
    }
}
