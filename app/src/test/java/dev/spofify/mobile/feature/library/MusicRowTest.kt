package dev.spofify.mobile.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
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
import dev.spofify.mobile.playback.PlaybackInteractionKey
import dev.spofify.mobile.playback.PlaybackListAction
import dev.spofify.mobile.playback.defaultQueueAction
import dev.spofify.mobile.ui.theme.AppTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
                    val more = compose.onNodeWithContentDescription("More actions for ${fixture.name}")
                    row.assertWidthIsEqualTo(hostWidth).assertHeightIsAtLeast(72.dp)
                    art.assertWidthIsEqualTo(52.dp).assertHeightIsEqualTo(52.dp)
                    more.assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
                    compose.onNodeWithContentDescription("Play ${fixture.name}").assertDoesNotExist()
                    compose.onNodeWithContentDescription("Pause ${fixture.name}").assertDoesNotExist()
                    compose.onAllNodes(hasClickAction()).assertCountEquals(2)
                    val context = "fixture=$fixtureIndex, kind=${fixture.kind}, credits=${fixture.artists.size}, " +
                        "playable=${fixture.playable}, fontScale=$fontScale, hostWidth=$hostWidth, " +
                        "subtitle=${compactMusicSubtitle(fixture)}"
                    val geometry = linkedMapOf("row" to row.bounds(), "art" to art.bounds(),
                        "more" to more.bounds())
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
                    val moreBounds = geometry.getValue("more")
                    assertEquals("Artwork stays centered in the row: $context", rowBounds.center.y, artBounds.center.y, 1f)
                    assertEquals(artBounds.center.y, moreBounds.center.y, 1f)
                    for ((slot, bounds) in geometry.filterKeys { it != "row" }) {
                        assertTrue("Slot stays inside row: $context, slot=$slot, row=$rowBounds, actual=$bounds",
                            bounds.left >= rowBounds.left - 1f && bounds.top >= rowBounds.top - 1f &&
                                bounds.right <= rowBounds.right + 1f && bounds.bottom <= rowBounds.bottom + 1f)
                    }
                    assertEquals("Reserved copy starts after artwork: $context", artBounds.right +
                        with(compose.density) { 10.dp.toPx() }, titleBounds.left, 1f)
                    assertEquals("Title reclaims the full removed playback slot up to More minus 10dp: $context",
                        moreBounds.left - with(compose.density) { 10.dp.toPx() }, titleBounds.right, 1f)
                    assertEquals("More stays flush with the row end: $context", rowBounds.right, moreBounds.right, 1f)
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
                        "row=$rowBounds, artwork=$artBounds, more=$moreBounds, " +
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
                        assertTrue("Metadata respects the same reclaimed copy slot: $context",
                            metadataBounds.right <= moreBounds.left - with(compose.density) { 10.dp.toPx() } + 1f &&
                                metadataBounds.bottom <= rowBounds.bottom)
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
            compose.onAllNodes(hasClickAction()).assertCountEquals(2) // Body and More; no inline transport/Like/credit buttons.
        }
    }

    @Test fun bodyMoreLongPressAndCustomPlaybackActionsRemainDistinctAndNeverAutoplay() {
        var opened: LibraryItem? = null
        val played = mutableListOf<Pair<LibraryItem, Boolean>>()
        val key = PlaybackInteractionKey()
        val contextual = mutableListOf<Pair<LibraryItem, PlaybackInteractionKey>>()
        var more = 0
        var legacyEnqueues = 0
        var liked = 0
        compose.setContent {
            MaterialTheme(typography = AppTypography) {
                Box(Modifier.width(320.dp)) {
                    MusicRow(track, { opened = it }, { item, shuffle -> played += item to shuffle }, { more++ }, { legacyEnqueues++ },
                        artwork = { _, modifier -> Box(modifier) }, modifier = Modifier.testTag("row"),
                        playback = CatalogPlaybackFeedback(isPlaying = true, interactionKey = key,
                            onContextual = { item, binding, isCurrent ->
                                assertTrue("Dispatched contextual action is still current", isCurrent())
                                contextual += item to binding
                            }),
                        like = CatalogLikeState(liked = false), onToggleLike = { liked++ })
                }
            }
        }
        compose.runOnIdle { assertTrue(played.isEmpty()); assertTrue(contextual.isEmpty()); assertEquals(null, opened) }
        compose.onAllNodes(hasClickAction()).assertCountEquals(2)
        compose.onNodeWithText(track.name).performClick()
        compose.runOnIdle { assertEquals(null, opened); assertEquals(listOf(track to false), played); assertTrue(contextual.isEmpty()) }
        compose.onNodeWithContentDescription("Play ${track.name}").assertDoesNotExist()
        compose.onNodeWithContentDescription("Pause ${track.name}").assertDoesNotExist()
        compose.onNodeWithContentDescription("More actions for ${track.name}").performClick()
        compose.onNodeWithText(track.name).performTouchInput { longClick() }
        val actions = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
            .fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle {
            assertEquals(listOf(track to false), played)
            assertTrue(contextual.isEmpty())
            assertEquals(2, more)
            assertTrue(actions.single { it.label == "Play now" }.action())
            assertEquals(listOf(track to false, track to false), played)
            assertTrue(contextual.isEmpty())
            assertTrue(actions.single { it.label == "Queue" }.action())
            assertEquals(listOf(track to key), contextual)
            assertTrue(actions.single { it.label == "More actions" }.action())
            assertTrue(actions.single { it.label == "Like song" }.action())
            assertEquals(0, legacyEnqueues); assertEquals(3, more); assertEquals(1, liked)
        }
    }

    @Test fun unavailableCurrentAndBufferingKeepSemanticsWithoutTransportOrGeometryChanges() {
        val item = mutableStateOf(track)
        val key = PlaybackInteractionKey()
        var toggles = 0
        var plays = 0
        var more = 0
        var contextual = 0
        val feedback = CatalogPlaybackFeedback(track.id, isPlaying = true, onToggle = { toggles++ },
            interactionKey = key, onContextual = { _, _, isCurrent ->
                assertTrue("Dispatched contextual action is still current", isCurrent())
                contextual++
            })
        val playback = mutableStateOf(feedback)
        compose.setContent {
            MaterialTheme(typography = AppTypography) {
                Box(Modifier.width(320.dp)) {
                    MusicRow(item.value, {}, { _, _ -> plays++ }, { more++ }, {},
                        artwork = { _, modifier -> Box(modifier) }, modifier = Modifier.testTag("row"),
                        playback = playback.value)
                }
            }
        }
        fun actionNode() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
        fun actions() = actionNode().fetchSemanticsNode().config[SemanticsActions.CustomActions]
        val before = compose.onNodeWithTag("row").bounds()
        val moreBefore = compose.onNodeWithContentDescription("More actions for ${track.name}").bounds()
        val titleBefore = compose.onNodeWithText(track.name, useUnmergedTree = true).bounds()
        compose.runOnIdle { item.value = track.copy(hasAudio = false) }
        compose.onNodeWithContentDescription("More actions for ${track.name}").performClick()
        assertFalse(actions().any { it.label == "Play now" || it.label == "Queue" })
        compose.onNodeWithText(compactMusicSubtitle(item.value), useUnmergedTree = true).assertHasNoClickAction()
        assertEquals(before, compose.onNodeWithTag("row").bounds())
        assertEquals(moreBefore, compose.onNodeWithContentDescription("More actions for ${track.name}").bounds())
        val states = listOf(
            feedback.copy(isPlaying = false) to "Paused",
            feedback to "Playing",
            feedback.copy(isPlaying = false, buffering = true, playWhenReady = true) to "Buffering",
            feedback.copy(isPlaying = false, buffering = true, playWhenReady = false) to "Buffering",
            feedback.copy(buffering = true) to "Buffering",
        )
        for ((state, description) in states) {
            compose.runOnIdle { item.value = track; playback.value = state }
            actionNode().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description))
            assertEquals(1, actions().count { it.label == "Play now" })
            compose.onNodeWithContentDescription("Play ${track.name}").assertDoesNotExist()
            compose.onNodeWithContentDescription("Pause ${track.name}").assertDoesNotExist()
            compose.onAllNodes(hasClickAction()).assertCountEquals(2)
            assertEquals(before, compose.onNodeWithTag("row").bounds())
            assertEquals(moreBefore, compose.onNodeWithContentDescription("More actions for ${track.name}").bounds())
            assertEquals(titleBefore, compose.onNodeWithText(track.name, useUnmergedTree = true).bounds())
        }
        val playNow = actions().single { it.label == "Play now" }
        compose.runOnIdle {
            assertTrue(playNow.action())
            assertEquals(0, toggles); assertEquals(1, plays); assertEquals(1, more); assertEquals(0, contextual)
            playback.value = feedback.copy(trackId = "another-track")
        }
        assertFalse("Another track's playback state must not mark this row current",
            actionNode().fetchSemanticsNode().config.contains(SemanticsProperties.StateDescription))
    }

    @Test fun swipeAndAccessibilityQueueKeepTheSameLabelAndItemDefaultAcrossKinds() {
        val item = mutableStateOf(track)
        val key = PlaybackInteractionKey()
        val queued = mutableListOf<Pair<String, PlaybackListAction>>()
        var bodyOpens = 0
        compose.setContent {
            MaterialTheme(typography = AppTypography) {
                MusicRow(item.value, { bodyOpens++ }, { _, _ -> }, {}, {},
                    artwork = { _, modifier -> Box(modifier) }, modifier = Modifier.width(320.dp).testTag("queue-row"),
                    playback = CatalogPlaybackFeedback(interactionKey = key,
                        onContextual = { selected, captured, isCurrent ->
                            assertSame(key, captured)
                            assertTrue(isCurrent())
                            queued += selected.kind to selected.defaultQueueAction()
                        }))
            }
        }
        for (kind in listOf("track", "album", "artist")) {
            compose.runOnIdle { item.value = track.copy(kind = kind, coverage = Coverage(inLibrary = 2)) }
            val actions = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
                .fetchSemanticsNode().config[SemanticsActions.CustomActions]
            assertEquals(1, actions.count { it.label == "Queue" })
            assertFalse(actions.any { it.label == "Queue High" || it.label == "Queue Low" })
            compose.runOnIdle { assertTrue(actions.single { it.label == "Queue" }.action()) }
            compose.onNodeWithTag("queue-row").performTouchInput {
                swipe(start = Offset(width * .25f, centerY), end = Offset(width * .75f, centerY))
            }
            compose.waitForIdle()
        }
        compose.runOnIdle {
            assertEquals(listOf("track" to PlaybackListAction.QUEUE_HIGH, "track" to PlaybackListAction.QUEUE_HIGH,
                "album" to PlaybackListAction.QUEUE_LOW, "album" to PlaybackListAction.QUEUE_LOW,
                "artist" to PlaybackListAction.QUEUE_LOW, "artist" to PlaybackListAction.QUEUE_LOW), queued)
            assertEquals("Queue actions do not activate the row body", 0, bodyOpens)
        }
    }

    @Test fun contextualActionUsesActiveIntentAndRequiresInteractionKeyAndCallback() {
        val key = PlaybackInteractionKey()
        val contextual = mutableListOf<Pair<LibraryItem, PlaybackInteractionKey>>()
        val base = CatalogPlaybackFeedback(interactionKey = key,
            onContextual = { item, binding, isCurrent ->
                assertTrue("Dispatched contextual action is still current", isCurrent())
                contextual += item to binding
            })
        val playback = mutableStateOf(base)
        var plays = 0
        var legacyEnqueues = 0
        compose.setContent {
            MaterialTheme(typography = AppTypography) {
                MusicRow(track, {}, { _, _ -> plays++ }, {}, { legacyEnqueues++ },
                    artwork = { _, modifier -> Box(modifier) }, modifier = Modifier.width(320.dp),
                    playback = playback.value)
            }
        }
        val cases = listOf(
            Triple(base, "Queue", true),
            Triple(base.copy(buffering = true), "Queue", true),
            Triple(base.copy(playWhenReady = true), "Queue", true),
            Triple(base.copy(isPlaying = true), "Queue", true),
            Triple(base.copy(buffering = true, playWhenReady = true), "Queue", true),
            Triple(base.copy(isPlaying = true, interactionKey = null), "Queue", false),
            Triple(base.copy(isPlaying = true, onContextual = null), "Queue", false),
        )
        var acceptedCallbacks = 0
        for ((feedback, label, enabled) in cases) {
            compose.runOnIdle { playback.value = feedback }
            assertEquals(label, feedback.contextualLabel)
            val actions = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
                .fetchSemanticsNode().config[SemanticsActions.CustomActions]
            assertEquals("Play now remains one deliberate action regardless of output state", 1,
                actions.count { it.label == "Play now" })
            assertEquals("Queue needs a valid binding, never a sampled playing flag", enabled,
                actions.any { it.label == "Queue" })
            if (enabled) {
                compose.runOnIdle { assertTrue(actions.single { it.label == "Queue" }.action()) }
                acceptedCallbacks++
            }
            compose.onAllNodes(hasClickAction()).assertCountEquals(2)
            compose.runOnIdle {
                assertEquals(List(acceptedCallbacks) { track to key }, contextual)
                assertEquals(0, plays)
                assertEquals(0, legacyEnqueues)
            }
        }
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
        compose.onAllNodes(hasClickAction()).assertCountEquals(2)
    }

    @Test fun occurrenceHostMoreAndRetainedAccessibilityCallbacksRespectIdentityDisposalAndLatestEnablement() {
        val identity = mutableStateOf("entry-A")
        val shown = mutableStateOf(true)
        val enabled = mutableStateOf(true)
        val key = PlaybackInteractionKey()
        val feedback = mutableStateOf(CatalogPlaybackFeedback(interactionKey = key))
        var more = 0
        var play = 0
        var high = 0
        var current: (() -> Boolean)? = null
        compose.setContent {
            MaterialTheme(typography = AppTypography) {
                if (shown.value) MusicRow(track, {}, { _, _ -> fail("Use scoped Play") }, { fail("Use scoped More") }, {},
                    artwork = { _, modifier -> Box(modifier) }, modifier = Modifier.width(320.dp),
                    rowIdentity = identity.value, currentOccurrence = false,
                    playbackEnabled = enabled.value, actionsEnabled = enabled.value,
                    playback = feedback.value.copy(onContextual = { _, captured, guard ->
                        assertSame(key, captured); assertTrue(guard()); high++
                    }), onShowScopedActions = { _, guard -> more++; current = guard },
                    onPlayScoped = { _, guard -> assertTrue(guard()); play++ })
            }
        }
        fun actions() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
            .fetchSemanticsNode().config[SemanticsActions.CustomActions]
        val retained = actions()
        compose.onNodeWithContentDescription("More actions for ${track.name}").performClick()
        compose.runOnIdle { assertTrue(current!!.invoke()); feedback.value = feedback.value.copy(buffering = true) }
        compose.runOnIdle {
            assertTrue("Benign buffering keeps the occurrence lifetime", current!!.invoke())
            retained.single { it.label == "Queue" }.action()
            assertEquals(1, high)
            enabled.value = false
        }
        compose.onNodeWithContentDescription("More actions for ${track.name}").assertIsNotEnabled()
        compose.runOnIdle {
            retained.forEach { it.action() }
            assertEquals(1, more); assertEquals(0, play); assertEquals(1, high)
            enabled.value = true; feedback.value = feedback.value.copy(interactionKey = PlaybackInteractionKey())
        }
        compose.runOnIdle {
            assertFalse("Output replacement retires open More", current!!.invoke())
            retained.forEach { it.action() }
            assertEquals(1, more); assertEquals(0, play); assertEquals(1, high)
            feedback.value = feedback.value.copy(interactionKey = key)
        }
        compose.runOnIdle {
            assertFalse("Returning to output A cannot revive old More", current!!.invoke())
            retained.forEach { it.action() }
            assertEquals(1, more); assertEquals(0, play); assertEquals(1, high)
        }
        compose.onNodeWithContentDescription("More actions for ${track.name}").performClick()
        compose.runOnIdle { assertTrue(current!!.invoke()); identity.value = "entry-B" }
        compose.runOnIdle {
            assertFalse("Replacing a duplicate entry retires open More", current!!.invoke())
            retained.forEach { it.action() }
            assertEquals(2, more); assertEquals(0, play); assertEquals(1, high)
        }
        compose.onNodeWithContentDescription("More actions for ${track.name}").performClick()
        compose.runOnIdle { assertTrue(current!!.invoke()); shown.value = false }
        compose.runOnIdle { assertFalse("Disposal retires the new row too", current!!.invoke()) }
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
