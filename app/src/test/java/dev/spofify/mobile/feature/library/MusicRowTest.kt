package dev.spofify.mobile.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.ArtistCredit
import dev.spofify.mobile.core.Coverage
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.ui.theme.AppTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Geometry and interaction regressions, not a replacement for rendered native UI review. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w480dp-h960dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MusicRowTest {
    @get:Rule val compose = createComposeRule()

    private val credits = listOf(
        ArtistCredit("artist-one", "A very long credited artist name"),
        ArtistCredit("artist-two", "宇多田ヒカル 🌙"),
        ArtistCredit("artist-three", "Björk"),
        ArtistCredit("artist-four", "Анна · collaborator"),
    )
    private val track = LibraryItem("track", "track-id", "A long Unicode title 音楽 🌙 that must remain one line",
        artists = credits, albumId = "album-id", albumName = "The album", hasAudio = true,
        durationMs = 185_000, releaseDate = "2024-02-03")

    @Test fun rowPitchArtworkAndActionsAreUniformAcrossKindsCreditCountsAndFontScales() {
        val item = mutableStateOf(track)
        val scale = mutableStateOf(1f)
        val width = mutableStateOf(320.dp)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value)) {
                MaterialTheme(typography = AppTypography) {
                    Box(Modifier.width(width.value)) {
                        MusicRow(item.value, {}, { _, _ -> }, {}, {},
                            artwork = { _, modifier -> Box(modifier.testTag("art")) },
                            modifier = Modifier.testTag("row"))
                    }
                }
            }
        }
        val cases = listOf("artist", "album", "track").flatMap { kind ->
            listOf(0, 1, 4).map { count ->
                track.copy(kind = kind, artists = credits.take(count), coverage = Coverage(inLibrary = 4))
            }
        } + listOf(
            track.copy(artists = emptyList(), durationMs = 0, releaseDate = null, albumId = null, albumName = null),
            track.copy(hasAudio = false),
            track.copy(kind = "artist", artists = emptyList(), genres = listOf("Art pop", "電子音楽"),
                durationMs = 0, releaseDate = null, coverage = Coverage(inLibrary = 1)),
        ) + listOf("音楽 🌙", "🌙 Égj", "العربية", "हिन्दी").map { fallback ->
            // Put fallback glyphs before the ellipsis boundary in both roles. A trailing
            // Unicode suffix alone can disappear before it exercises native font metrics.
            track.copy(name = "$fallback ${track.name}",
                artists = listOf(ArtistCredit("fallback-artist", "$fallback ${credits.first().name}")))
        }
        var normalHeight = 0f
        for (fontScale in listOf(1f, 1.35f, 2f)) {
            for (hostWidth in listOf(320.dp, 360.dp)) {
                var expected: Map<String, Rect>? = null
                var baselineTitle: Rect? = null
                for ((fixtureIndex, fixture) in cases.withIndex()) {
                    compose.runOnIdle { item.value = fixture; scale.value = fontScale; width.value = hostWidth }
                    val row = compose.onNodeWithTag("row")
                    val art = compose.onNodeWithContentDescription(musicTypeLabel(fixture.kind), useUnmergedTree = true)
                    val title = compose.onNodeWithText(fixture.name, useUnmergedTree = true)
                    val play = compose.onNodeWithContentDescription("Play ${fixture.name}")
                    val more = compose.onNodeWithContentDescription("More actions for ${fixture.name}")
                    row.assertWidthIsEqualTo(hostWidth).assertHeightIsAtLeast(72.dp)
                    art.assertWidthIsEqualTo(52.dp).assertHeightIsEqualTo(52.dp)
                    play.assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
                    more.assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
                    if (fixture.playable) play.assertIsEnabled() else play.assertIsNotEnabled()
                    val context = "fixture=$fixtureIndex, kind=${fixture.kind}, credits=${fixture.artists.size}, " +
                        "playable=${fixture.playable}, fontScale=$fontScale, hostWidth=$hostWidth, " +
                        "subtitle=${compactMusicSubtitle(fixture)}"
                    val geometry = linkedMapOf("row" to row.bounds(), "art" to art.bounds(),
                        "play" to play.bounds(), "more" to more.bounds())
                    val titleBounds = title.bounds()
                    expected?.let { baseline ->
                        for ((slot, after) in geometry) {
                            val before = baseline.getValue(slot)
                            val diagnostic = "$context, slot=$slot, baseline=$before, actual=$after, title=$titleBounds"
                            assertEquals("height: $diagnostic", before.height, after.height, 1f)
                            assertEquals("width: $diagnostic", before.width, after.width, 1f)
                            assertEquals("left: $diagnostic", before.left, after.left, 1f)
                            assertEquals("top: $diagnostic", before.top, after.top, 1f)
                        }
                    } ?: run { expected = geometry }
                    // Compare stable row/art/action slots, not title text-layout rectangles: native
                    // fallback metrics can change how two text lines center inside the row.
                    val rowBounds = geometry.getValue("row")
                    val artBounds = geometry.getValue("art")
                    val playBounds = geometry.getValue("play")
                    val moreBounds = geometry.getValue("more")
                    assertEquals("Artwork stays centered in the row: $context", rowBounds.center.y, artBounds.center.y, 1f)
                    assertEquals(artBounds.center.y, playBounds.center.y, 1f)
                    assertEquals(playBounds.center.y, moreBounds.center.y, 1f)
                    for ((slot, bounds) in geometry.filterKeys { it != "row" }) {
                        assertTrue("Slot stays inside row: $context, slot=$slot, row=$rowBounds, actual=$bounds",
                            bounds.left >= rowBounds.left - 1f && bounds.top >= rowBounds.top - 1f &&
                                bounds.right <= rowBounds.right + 1f && bounds.bottom <= rowBounds.bottom + 1f)
                    }
                    assertEquals("Reserved copy starts after artwork: $context", artBounds.right +
                        with(compose.density) { 10.dp.toPx() }, titleBounds.left, 1f)
                    assertTrue("Text cannot overlap Play: $context", titleBounds.right <= playBounds.left)
                    assertTrue(playBounds.right <= moreBounds.left)
                    assertTrue(moreBounds.right <= rowBounds.right)
                    assertTrue(titleBounds.top >= rowBounds.top && titleBounds.bottom <= rowBounds.bottom)
                    val titleLayout = assertSingleLine(title, context, mustEllipsize = true)
                    baselineTitle?.let { baseline ->
                        if (kotlin.math.abs(baseline.top - titleBounds.top) > 1f) {
                            println("MusicRow text-position diagnostic [$context]: " +
                                "baselineTitle=$baseline, actualTitle=$titleBounds, row=$rowBounds, " +
                                "layoutSize=${titleLayout.size}")
                        }
                    } ?: run { baselineTitle = titleBounds }
                    if (fixture == cases.first()) println("MusicRow title metrics [$context]: " +
                        "row=$rowBounds, artwork=$artBounds, play=$playBounds, more=$moreBounds, " +
                        "intrinsicWidth=${titleLayout.multiParagraph.maxIntrinsicWidth}, " +
                        "paragraphWidth=${titleLayout.multiParagraph.width}, lineRight=${titleLayout.getLineRight(0)}, " +
                        "size=${titleLayout.size}, constraints=${titleLayout.layoutInput.constraints}, " +
                        "ellipsized=${titleLayout.isLineEllipsized(0)}")
                    val subtitle = compactMusicSubtitle(fixture)
                    if (subtitle.isNotBlank()) {
                        val metadata = compose.onNodeWithText(subtitle, useUnmergedTree = true)
                        metadata.assertHasNoClickAction()
                        assertSingleLine(metadata, "metadata: $context")
                        val metadataBounds = metadata.bounds()
                        assertEquals(titleBounds.left, metadataBounds.left, 1f)
                        assertTrue(titleBounds.bottom <= metadataBounds.top + 1f)
                        assertTrue(metadataBounds.right <= playBounds.left && metadataBounds.bottom <= rowBounds.bottom)
                    } else {
                        // The slot is reserved by layout, not a fake blank Text announcement.
                        compose.onAllNodes(hasText(" "), useUnmergedTree = true).assertCountEquals(0)
                        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
                            useUnmergedTree = true).assertCountEquals(1)
                    }
                }
                val height = expected!!.getValue("row").height
                if (fontScale == 1f) {
                    compose.onNodeWithTag("row").assertHeightIsEqualTo(72.dp)
                    normalHeight = height
                }
                if (fontScale == 2f) assertTrue("Rows grow uniformly rather than clipping large text", height > normalHeight)
            }
        }
    }

    @Test fun typeCuesAreAccessibleOnceWithoutVisibleLabelsOrPerArtistButtons() {
        val item = mutableStateOf(track)
        compose.setContent {
            MaterialTheme(typography = AppTypography) {
                Box(Modifier.width(320.dp)) {
                    MusicRow(item.value, {}, { _, _ -> }, {}, {},
                        artwork = { _, modifier -> Box(modifier.semantics { contentDescription = "decorative cover" }) },
                        like = CatalogLikeState(liked = true), onToggleLike = {})
                }
            }
        }
        for (kind in listOf("artist", "album", "track")) {
            compose.runOnIdle { item.value = track.copy(kind = kind, coverage = Coverage(inLibrary = 1)) }
            val type = musicTypeLabel(kind)
            compose.onAllNodesWithContentDescription(type, useUnmergedTree = true).assertCountEquals(1)
            compose.onNodeWithText(type).assertDoesNotExist()
            // Clearing retains diagnostic children in the unmerged tree, but consumers of
            // the cleared/merged accessibility contract must receive only the entity type.
            compose.onNodeWithContentDescription("decorative cover", useUnmergedTree = true).assertExists()
            compose.onNodeWithContentDescription("decorative cover").assertDoesNotExist()
            val cue = compose.onNodeWithContentDescription(type, useUnmergedTree = true).fetchSemanticsNode()
            assertTrue("Artwork explicitly clears supplied image semantics", cue.config.isClearingSemantics)
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(type)))
            compose.onNodeWithText(compactMusicSubtitle(item.value), useUnmergedTree = true).assertHasNoClickAction()
            credits.forEach { compose.onNodeWithText(it.name).assertDoesNotExist() }
            compose.onAllNodes(hasClickAction()).assertCountEquals(3) // Open, Play, More; no inline Like/credit buttons.
        }
    }

    @Test fun openPlayMoreLongPressAndAccessibleActionsRemainDistinctAndNeverAutoplay() {
        var opened: LibraryItem? = null
        val played = mutableListOf<Pair<LibraryItem, Boolean>>()
        var more = 0
        var queued = 0
        var liked = 0
        compose.setContent {
            MaterialTheme(typography = AppTypography) {
                Box(Modifier.width(320.dp)) {
                    MusicRow(track, { opened = it }, { item, shuffle -> played += item to shuffle }, { more++ }, { queued++ },
                        artwork = { _, modifier -> Box(modifier) }, modifier = Modifier.testTag("row"),
                        like = CatalogLikeState(liked = false), onToggleLike = { liked++ })
                }
            }
        }
        compose.runOnIdle { assertTrue(played.isEmpty()); assertEquals(null, opened) }
        compose.onNodeWithText(track.name).performClick()
        compose.runOnIdle { assertEquals(track, opened); assertTrue(played.isEmpty()) }
        compose.onNodeWithContentDescription("Play ${track.name}").performClick()
        compose.onNodeWithContentDescription("More actions for ${track.name}").performClick()
        compose.onNodeWithText(track.name).performTouchInput { longClick() }
        val actions = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
            .fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle {
            assertEquals(listOf(track to false), played)
            assertEquals(2, more)
            assertTrue(actions.single { it.label == "Add to queue" }.action())
            assertTrue(actions.single { it.label == "More actions" }.action())
            assertTrue(actions.single { it.label == "Like song" }.action())
            assertEquals(1, queued); assertEquals(3, more); assertEquals(1, liked)
        }
    }

    @Test fun unavailableAndBufferingKeepNamedActionsAndStableGeometry() {
        val item = mutableStateOf(track)
        val playback = mutableStateOf(CatalogPlaybackFeedback())
        var toggles = 0
        var plays = 0
        var more = 0
        compose.setContent {
            MaterialTheme(typography = AppTypography) {
                Box(Modifier.width(320.dp)) {
                    MusicRow(item.value, {}, { _, _ -> plays++ }, { more++ }, {},
                        artwork = { _, modifier -> Box(modifier) }, modifier = Modifier.testTag("row"),
                        playback = playback.value)
                }
            }
        }
        val before = compose.onNodeWithTag("row").bounds()
        val playBefore = compose.onNodeWithContentDescription("Play ${track.name}").bounds()
        compose.runOnIdle { item.value = track.copy(hasAudio = false) }
        compose.onNodeWithContentDescription("Play ${track.name}").assertIsNotEnabled()
        compose.onNodeWithContentDescription("More actions for ${track.name}").performClick()
        val actions = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
            .fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertFalse(actions.any { it.label == "Add to queue" })
        assertEquals(before, compose.onNodeWithTag("row").bounds())
        assertEquals(playBefore, compose.onNodeWithContentDescription("Play ${track.name}").bounds())
        compose.runOnIdle {
            item.value = track
            playback.value = CatalogPlaybackFeedback(track.id, isPlaying = true, buffering = true, onToggle = { toggles++ })
        }
        compose.onNodeWithContentDescription("Pause ${track.name}").assertIsEnabled().performClick()
        assertEquals(before, compose.onNodeWithTag("row").bounds())
        compose.runOnIdle { assertEquals(1, toggles); assertEquals(0, plays); assertEquals(1, more) }
    }

    @Test fun likeAccessibilityActionUsesCurrentStateAndIsSuppressedWhilePending() {
        val like = mutableStateOf(CatalogLikeState(liked = true))
        var toggles = 0
        compose.setContent {
            MaterialTheme(typography = AppTypography) {
                MusicRow(track, {}, { _, _ -> }, {}, {}, artwork = { _, modifier -> Box(modifier) },
                    modifier = Modifier.width(320.dp).testTag("row"), like = like.value,
                    onToggleLike = { toggles++ })
            }
        }
        fun actions() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
            .fetchSemanticsNode().config[SemanticsActions.CustomActions]
        val before = compose.onNodeWithTag("row").bounds()
        val unlike = actions().single { it.label == "Unlike song" }
        compose.runOnIdle { assertTrue(unlike.action()) }
        compose.runOnIdle { assertEquals(1, toggles); like.value = CatalogLikeState(liked = true, pending = true) }
        assertFalse(actions().any { it.label == "Like song" || it.label == "Unlike song" })
        assertEquals(before, compose.onNodeWithTag("row").bounds())
        compose.onAllNodes(hasClickAction()).assertCountEquals(3)
    }

    @Test fun intentionalCreditsRetainDistinctOpaqueIdsAndMissingIdsStayPlainText() {
        val item = track.copy(artists = listOf(ArtistCredit("opaque:one", "Same name"),
            ArtistCredit("opaque:two", "Same name"), ArtistCredit("", "Unknown identity")))
        val opened = mutableListOf<LibraryItem>()
        compose.setContent { MaterialTheme(typography = AppTypography) { MusicCredits(item, { opened += it }) } }
        compose.onAllNodesWithText("Same name")[0].assertHeightIsAtLeast(48.dp).performClick()
        compose.onAllNodesWithText("Same name")[1].assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Unknown identity", useUnmergedTree = true).assertHasNoClickAction()
        compose.onNodeWithText(item.albumName!!).assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle {
            assertEquals(listOf("artist" to "opaque:one", "artist" to "opaque:two", "album" to "album-id"),
                opened.map { it.kind to it.id })
        }
    }

    @Test fun inlineMetadataIsBoundedUsefulAndDoesNotInventUnknownValues() {
        assertEquals("A very long credited artist name, 宇多田ヒカル 🌙, Björk, Анна · collaborator · 2024 · 3:05",
            compactMusicSubtitle(track))
        assertEquals("Unavailable", compactMusicSubtitle(track.copy(artists = emptyList(), durationMs = 0,
            releaseDate = null, hasAudio = false)))
        assertEquals("", compactMusicSubtitle(track.copy(artists = emptyList(), durationMs = 0, releaseDate = null)))
        assertEquals("Art pop · 電子音楽", compactMusicSubtitle(LibraryItem("artist", "artist", "Name",
            genres = listOf("Art pop", "電子音楽", "Not another line"), coverage = Coverage(inLibrary = 1))))
        assertEquals("", compactMusicSubtitle(track.copy(artists = listOf(ArtistCredit("one", " "),
            ArtistCredit("two", "\t")), durationMs = 0, releaseDate = null)))
        assertEquals("Art pop · 電子音楽", compactMusicSubtitle(LibraryItem("artist", "artist", "Name",
            genres = listOf(" ", "Art pop", "", "電子音楽"), coverage = Coverage(inLibrary = 1))))
    }

    private fun SemanticsNodeInteraction.bounds(): Rect = fetchSemanticsNode().boundsInRoot

    private fun assertSingleLine(node: SemanticsNodeInteraction, context: String,
        mustEllipsize: Boolean = false): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> assertTrue(action(layouts)) }
        val layout = layouts.single()
        val diagnostic = "$context, text=${layout.layoutInput.text.text}, size=${layout.size}, " +
            "constraints=${layout.layoutInput.constraints}, intrinsicWidth=${layout.multiParagraph.maxIntrinsicWidth}, " +
            "paragraphWidth=${layout.multiParagraph.width}, lineRight=${layout.getLineRight(0)}, " +
            "exceededMaxLines=${layout.multiParagraph.didExceedMaxLines}, lineCount=${layout.lineCount}, " +
            "ellipsized=${layout.isLineEllipsized(0)}"
        assertEquals(diagnostic, 1, layout.lineCount)
        assertEquals(diagnostic, 1, layout.layoutInput.maxLines)
        assertEquals(diagnostic, TextOverflow.Ellipsis, layout.layoutInput.overflow)
        // didOverflowHeight also includes ellipsis/max-line truncation; check physical
        // paragraph height instead so correctly ellipsized text is not mistaken for clipping.
        assertTrue("Paragraph height fits the text layout: $diagnostic",
            layout.multiParagraph.height <= layout.size.height.toFloat() + 1f)
        assertEquals("Text layout height fits its actual node: $diagnostic", layout.size.height.toFloat(), node.bounds().height, 1f)
        assertEquals("Text layout width fits its actual node: $diagnostic", layout.size.width.toFloat(), node.bounds().width, 1f)
        // Native Paint must measure our deliberately long identity as overflowing at
        // every fixture width/scale; a wrong renderer or unconstrained slot must fail.
        if (mustEllipsize) {
            assertTrue("Forced long identity must overflow with native font metrics: $diagnostic",
                layout.multiParagraph.maxIntrinsicWidth > layout.multiParagraph.width + 1f)
            assertTrue("Forced long identity must ellipsize: $diagnostic", layout.isLineEllipsized(0))
        } else if (layout.multiParagraph.maxIntrinsicWidth > layout.multiParagraph.width + 1f) {
            assertTrue("Measured overflow must ellipsize rather than clip: $diagnostic", layout.isLineEllipsized(0))
        }
        return layout
    }
}
