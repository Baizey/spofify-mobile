package dev.spofify.mobile

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LikeButtonTest {
    @get:Rule val compose = createComposeRule()
    @Test fun unknownStatusIsNotAnActionableUnlikedHeartAndFailureOffersExplicitRetry() {
        val liked = mutableStateOf<Boolean?>(null)
        val failed = mutableStateOf(false)
        var toggles = 0
        var retries = 0
        compose.setContent { MaterialTheme { LikeButton(liked.value, false, failed.value, { toggles++ }, { retries++ }) } }
        compose.onNodeWithContentDescription("Checking like status").assertIsNotEnabled()
        compose.runOnIdle { failed.value = true }
        compose.onNodeWithContentDescription("Retry like status").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(0, toggles); assertEquals(1, retries); liked.value = false; failed.value = false }
        compose.onNodeWithContentDescription("Like song").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, toggles) }
    }
}
