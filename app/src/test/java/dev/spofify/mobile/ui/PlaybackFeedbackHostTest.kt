package dev.spofify.mobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
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
import dev.spofify.mobile.playback.PlaybackListAction
import dev.spofify.mobile.ui.theme.AppTypography
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
    private fun success() = requireNotNull(PlaybackFeedback.from(
        PlaybackActionResult(PlaybackActionStatus.ADDED, 1), action = PlaybackListAction.QUEUE_HIGH))

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

    @Test fun accessibilityExtensionUsesTruthfulFlags() {
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

    @Test fun rootShowsOnlyQueueAcceptance() = queueOnlyWindow(false)
    @Test fun expandedShowsOnlyQueueAcceptance() = queueOnlyWindow(true)

    private fun queueOnlyWindow(expanded: Boolean) {
        val presentation = PlaybackFeedbackPresentation()
        val windowTag = if (expanded) "expanded-feedback-window" else "root-feedback-window"
        compose.setContent {
            Box(Modifier.testTag(windowTag)) {
                PlaybackFeedbackHost(presentation.current, announcementOwner = presentation)
            }
        }
        // Silent outcomes cannot create a popup in either window.
        for (status in PlaybackActionStatus.entries) {
            compose.runOnIdle {
                PlaybackFeedback.from(PlaybackActionResult(status), action = PlaybackListAction.PLAY_NOW)
                    ?.let(presentation::offer)
                PlaybackFeedback.from(PlaybackActionResult(status), action = PlaybackListAction.QUEUE_HIGH)
                    ?.let(presentation::offer)
            }
        }
        compose.runOnIdle { assertNull(presentation.current) }
        compose.onNodeWithText("Playing track").assertDoesNotExist()
        compose.onNodeWithText("Track queued").assertDoesNotExist()
        compose.runOnIdle { presentation.offer(success()) }
        compose.onNodeWithTag(windowTag).assertIsDisplayed()
        compose.onNodeWithText("Track queued").assertIsDisplayed()
    }

    @Test fun discardedOrInvalidEventsDoNotResurface() {
        val presentation = PlaybackFeedbackPresentation()
        val event = success()
        presentation.offer(event)
        presentation.discardSuccess()
        presentation.offer(event)
        assertNull(presentation.current)
        val invalid = requireNotNull(PlaybackFeedback.from(PlaybackActionResult(PlaybackActionStatus.ADDED, 1),
            isCurrent = { false }, action = PlaybackListAction.QUEUE_HIGH))
        presentation.offer(invalid)
        assertNull(presentation.current)
    }
}
