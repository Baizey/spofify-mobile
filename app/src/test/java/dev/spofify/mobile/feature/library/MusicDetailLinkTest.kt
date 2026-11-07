package dev.spofify.mobile.feature.library

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.ArtistCredit
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.listeningColorScheme
import dev.spofify.mobile.ui.theme.AppTypography
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MusicDetailLinkTest {
    @get:Rule val compose = createComposeRule()
    @Test fun detailsCreditsHavePaddedDisjointTargets() = checkLinks(false, false)
    @Test fun detailsCreditsWrapAtFontTwoRtl() = checkLinks(false, true)
    @Test fun songCreditsAndAlbumHavePaddedDisjointTargets() = checkLinks(true, false)
    @Test fun songCreditsAndAlbumWrapAtFontTwoRtl() = checkLinks(true, true)

    private fun checkLinks(song: Boolean, rtl: Boolean) {
        val item = LibraryItem("track", "song", "Song", artists = listOf(
            ArtistCredit("opaque/first?%", "أم كلثوم"), ArtistCredit("opaque/second#", "Björk 音楽"),
            ArtistCredit("", "Unknown credit")), albumId = "opaque/album?%", albumName = "夜のアルバム")
        val opened = mutableListOf<LibraryItem>()
        var body = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (rtl) 2f else 1f),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                    Surface {
                        Column(Modifier.fillMaxWidth().testTag("credit-host").clickable { body++ }) {
                            if (song) MusicCredits(item, opened::add) else DetailCredits(item, opened::add)
                        }
                    }
                }
            }
        }
        val names = listOf("أم كلثوم", "Björk 音楽") + if (song) listOf("夜のアルバム") else emptyList()
        val targets = names.map { name -> compose.onNode(hasText(name) and hasClickAction()) }
        val bounds = targets.map { it.getUnclippedBoundsInRoot() }
        bounds.forEachIndexed { index, target ->
            targets[index].assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            bounds.drop(index + 1).forEach { other ->
                assertFalse("Neighboring links must not overlap", target.left < other.right && other.left < target.right &&
                    target.top < other.bottom && other.top < target.bottom)
            }
            val glyph = compose.onNodeWithText(names[index], useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue("Ripple/click bounds include horizontal padding", glyph.left - target.left >= 11.dp && target.right - glyph.right >= 11.dp)
            // Hit padding, not glyphs: the child consumes this without playing the parent body.
            targets[index].performTouchInput { click(Offset(2f, centerY)) }
        }
        compose.runOnIdle {
            assertEquals(0, body)
            assertEquals(listOf("opaque/first?%", "opaque/second#") + if (song) listOf("opaque/album?%") else emptyList(), opened.map { it.id })
            assertEquals(listOf("artist", "artist") + if (song) listOf("album") else emptyList(), opened.map { it.kind })
        }
        compose.onNode(hasText("Unknown credit") and hasClickAction(), useUnmergedTree = true).assertDoesNotExist()
        System.getProperty("spofify.polishEvidenceDir")?.let { root ->
            File(root, "credits-${if (song) "song" else "details"}-${if (rtl) "rtl-font2" else "ltr"}.png").outputStream().use {
                check(compose.onNodeWithTag("credit-host").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
    }
}
