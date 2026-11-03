package dev.spofify.mobile

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SearchFieldTest {
    @get:Rule val compose = createComposeRule()
    @Test fun resettingAndRestoringTheSearchRouteKeepsTheInputInSync() {
        val query = mutableStateOf("foo")
        compose.setContent { MaterialTheme { SearchField(query.value) { query.value = it } } }
        compose.onNodeWithText("foo").assertIsDisplayed()
        compose.runOnIdle { query.value = "" }
        compose.onNodeWithText("foo").assertDoesNotExist()
        compose.onNodeWithText("Search your music").performTextReplacement("bar")
        compose.runOnIdle { assertEquals("bar", query.value); query.value = "foo" }
        compose.onNodeWithText("foo").assertIsDisplayed()
    }
}
