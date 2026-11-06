package dev.spofify.mobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.listeningColorScheme
import dev.spofify.mobile.playback.PlaybackActionResult
import dev.spofify.mobile.playback.PlaybackActionStatus
import dev.spofify.mobile.playback.PlaybackFeedback
import dev.spofify.mobile.ui.theme.AppTypography
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-xhdpi")
class PlaybackFeedbackHostTest {
    @get:Rule val compose = createComposeRule()
    private fun success() = PlaybackFeedback.from(PlaybackActionResult(PlaybackActionStatus.ADDED, 1))

    @Test fun baseTimeoutAndIdenticalReplacementCannotBeClearedByOldTimer() {
        compose.mainClock.autoAdvance = false
        val presentation = PlaybackFeedbackPresentation()
        val consumed = mutableListOf<Long>()
        val first = success()
        presentation.offer(first)
        compose.setContent {
            PlaybackFeedbackTimer(presentation) { consumed.add(it) }
            PlaybackFeedbackHost(presentation.current, announcementOwner = presentation)
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertEquals(first.id, presentation.current?.id) }
        val repeat = success()
        compose.runOnIdle { presentation.offer(repeat) }
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle {
            assertEquals(repeat.id, presentation.current?.id)
            assertFalse(consumed.contains(first.id))
            presentation.consume(first.id)
            assertEquals(repeat.id, presentation.current?.id)
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle {
            assertNull(presentation.current)
            assertEquals(listOf(repeat.id), consumed)
        }
    }

    @Test fun accessibilityExtensionUsesTruthfulFlagsAndGuidanceKeepsLongPolicy() {
        compose.mainClock.autoAdvance = false
        val presentation = PlaybackFeedbackPresentation()
        val calls = mutableListOf<Long>()
        val manager = object : AccessibilityManager {
            override fun calculateRecommendedTimeoutMillis(originalTimeoutMillis: Long, containsIcons: Boolean,
                containsText: Boolean, containsControls: Boolean): Long {
                assertFalse(containsIcons)
                assertTrue(containsText)
                assertFalse(containsControls)
                calls.add(originalTimeoutMillis)
                return originalTimeoutMillis * 2
            }
        }
        presentation.offer(success())
        compose.setContent {
            CompositionLocalProvider(LocalAccessibilityManager provides manager) {
                PlaybackFeedbackTimer(presentation) {}
                PlaybackFeedbackHost(presentation.current)
            }
        }
        compose.mainClock.advanceTimeBy(1600)
        compose.runOnIdle { assertNotNull(presentation.current); assertEquals(listOf(1500L), calls) }
        compose.mainClock.advanceTimeBy(1600)
        compose.runOnIdle { assertNull(presentation.current) }
        val guidance = PlaybackFeedback.guidance("Choose a device and retry explicitly.")
        compose.runOnIdle { presentation.offer(guidance); presentation.offer(success()) }
        compose.mainClock.advanceTimeBy(3100)
        compose.runOnIdle { assertEquals(guidance.id, presentation.current?.id); assertEquals(10000L, calls.last()) }
    }

    @Test fun windowRemountDoesNotRestartTimerOrRepeatAnnouncementAndLargeTextGrows() {
        compose.mainClock.autoAdvance = false
        val expanded = mutableStateOf(false)
        val presentation = PlaybackFeedbackPresentation()
        val event = success()
        presentation.offer(event)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                    PlaybackFeedbackTimer(presentation) {}
                    Box(Modifier.width(180.dp)) {
                        if (expanded.value) {
                            PlaybackFeedbackHost(presentation.current, announcementOwner = presentation)
                        } else {
                            PlaybackFeedbackHost(presentation.current, announcementOwner = presentation)
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(900)
        compose.onNodeWithText("Track queued").assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(presentation.claimAnnouncement(event.id))
            expanded.value = true
        }
        compose.mainClock.advanceTimeBy(700)
        compose.runOnIdle { assertNull(presentation.current) }
    }

    @Test fun rootSuccessWaitsForBusySnackbarAndKeepsOnlyLatest() = busySnackbarCase(false, false)
    @Test fun expandedSuccessWaitsForBusySnackbarAndKeepsOnlyLatest() = busySnackbarCase(true, false)
    @Test fun rootGuidanceReceivesVisibleLongDurationAfterBusySnackbar() = busySnackbarCase(false, true)
    @Test fun expandedGuidanceReceivesVisibleLongDurationAfterBusySnackbar() = busySnackbarCase(true, true)

    private fun busySnackbarCase(expanded: Boolean, guidance: Boolean) {
        compose.mainClock.autoAdvance = false
        val presentation = PlaybackFeedbackPresentation()
        val snackbar = SnackbarHostState()
        lateinit var scope: CoroutineScope
        val consumed = mutableListOf<Long>()
        val windowTag = if (expanded) "expanded-feedback-window" else "root-feedback-window"
        compose.setContent {
            scope = rememberCoroutineScope()
            CompositionLocalProvider(LocalAccessibilityManager provides null) {
                PlaybackFeedbackTimer(presentation, visible = snackbar.currentSnackbarData == null) {
                    consumed.add(it)
                }
                // Same priority and single visible-window routing as SpofifyApp.
                if (expanded) {
                    Box(Modifier.testTag("expanded-feedback-window")) {
                        if (snackbar.currentSnackbarData != null) SnackbarHost(snackbar)
                        else PlaybackFeedbackHost(presentation.current, announcementOwner = presentation)
                    }
                } else {
                    Box(Modifier.testTag("root-feedback-window")) {
                        if (snackbar.currentSnackbarData != null) SnackbarHost(snackbar)
                        else PlaybackFeedbackHost(presentation.current, announcementOwner = presentation)
                    }
                }
            }
        }
        compose.runOnIdle {
            scope.launch { snackbar.showSnackbar("Resolve this error first", duration = SnackbarDuration.Indefinite) }
        }
        compose.mainClock.advanceTimeByFrame()
        val first = if (guidance) PlaybackFeedback.guidance("Choose an output and retry.") else success()
        val latest = success()
        compose.runOnIdle {
            presentation.offer(first)
            presentation.offer(latest)
        }
        val expected = if (guidance) first else latest
        // Longer than both policies: a hidden event must not expire, announce or displace error.
        compose.mainClock.advanceTimeBy(12_000)
        compose.onNodeWithText("Resolve this error first").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(expected.id, presentation.current?.id)
            assertTrue(consumed.isEmpty())
            snackbar.currentSnackbarData!!.dismiss()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag(windowTag).assertIsDisplayed()
        compose.onNodeWithText(expected.message).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(expected.baseTimeoutMillis - 100)
        compose.runOnIdle { assertEquals(expected.id, presentation.current?.id) }
        compose.mainClock.advanceTimeBy(200)
        compose.runOnIdle {
            assertNull(presentation.current)
            assertEquals(listOf(expected.id), consumed)
        }
    }

    @Test fun discardedOrInvalidEventsDoNotResurface() {
        val presentation = PlaybackFeedbackPresentation()
        val event = success()
        presentation.offer(event)
        presentation.discardSuccess()
        presentation.offer(event)
        assertNull(presentation.current)
        val invalid = PlaybackFeedback.from(PlaybackActionResult(PlaybackActionStatus.PLAYING)) { false }
        presentation.offer(invalid)
        assertNull(presentation.current)
    }
}
