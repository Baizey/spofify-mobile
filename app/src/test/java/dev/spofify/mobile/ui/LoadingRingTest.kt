package dev.spofify.mobile.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Host-only primitive regression; this is not an Android rendered-app acceptance screenshot. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LoadingRingTest {
    @get:Rule val compose = createComposeRule(effectContext = object : MotionDurationScale {
        override val scaleFactor: Float = 0f
    })

    @Test fun motionOffRetainsStaticCurrentColorRingAndStableCompositionIdentity() {
        val update = mutableStateOf(0)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            // Force ordinary parent publications without replacing/re-keying the loading composition.
            val ignored = update.value
            CompositionLocalProvider(LocalContentColor provides if (ignored >= 0) Color.Magenta else Color.Cyan) {
                LoadingRing(Modifier.size(20.dp).testTag("ring"))
            }
        }
        compose.mainClock.advanceTimeByFrame()
        val ring = compose.onNodeWithTag("ring")
        val identity = ring.fetchSemanticsNode().id
        val before = ring.captureToImage().toPixelMap()
        repeat(12) { frame ->
            compose.runOnIdle { update.value = frame + 1 }
            compose.mainClock.advanceTimeBy(100)
            assertEquals(identity, ring.fetchSemanticsNode().id)
        }
        val after = ring.captureToImage().toPixelMap()
        assertEquals(before.width, after.width)
        assertEquals(before.height, after.height)
        var coloredPixels = 0
        for (y in 0 until before.height) for (x in 0 until before.width) {
            assertEquals("Reduced-motion ring pixel ($x,$y)", before[x, y], after[x, y])
            val pixel = after[x, y]
            if (pixel.alpha > .5f && pixel.red > .8f && pixel.blue > .8f && pixel.green < .2f) coloredPixels++
        }
        assertTrue("A static ring remains visible in currentColor, not an empty canvas", coloredPixels > 20)
    }
}
