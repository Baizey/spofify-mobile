package dev.spofify.mobile.feature.library

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.listeningColorScheme
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.LibraryPage
import dev.spofify.mobile.ui.theme.AppTypography
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Presentation-only case extracted from SearchActivationFieldTest, on baseline navigation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-xhdpi")
class SearchFieldPresentationTest {
    @get:Rule val compose = createComposeRule()

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun composingInputSurvivesQueryReconciliationAndCommitsOnlyWhenFinishedThenSubmitsAndClears() {
        val query = mutableStateOf("")
        val commits = mutableListOf<Pair<String, Boolean>>()
        val composing = mutableListOf<Boolean>()
        var connection: InputConnection? = null
        compose.setContent {
            InterceptPlatformTextInput(interceptor = { request, _ ->
                connection = request.createInputConnection(EditorInfo())
                awaitCancellation()
            }) {
                MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                    Box(Modifier.width(280.dp)) {
                        CommittedSearchField(query.value, onCompositionChanged = { composing += it }) { value, immediate ->
                            commits += value to immediate
                            query.value = value
                        }
                    }
                }
            }
        }
        val field = compose.onNodeWithTag("search-query")
        field.performClick()
        compose.runOnIdle { checkNotNull(connection).setComposingText("ca", 1) }
        field.assertTextEquals("ca")
        compose.runOnIdle {
            assertTrue(commits.isEmpty())
            assertEquals(true, composing.last())
            query.value = "restored while composing"
        }
        field.assertTextEquals("ca")
        compose.runOnIdle { checkNotNull(connection).setComposingText("café", 1) }
        field.assertTextEquals("café")
        compose.runOnIdle {
            assertTrue(commits.isEmpty())
            checkNotNull(connection).finishComposingText()
        }
        compose.runOnIdle {
            assertEquals(listOf("café" to false), commits)
            assertEquals(false, composing.last())
        }
        field.performImeAction()
        compose.runOnIdle { assertEquals("café" to true, commits.last()) }
        compose.onNodeWithContentDescription("Clear search").performClick()
        // Placeholder is merged Text, distinct from the cleared EditableText value.
        assertEquals("", field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.runOnIdle {
            assertEquals("" to true, commits.last())
            assertEquals(false, composing.last())
            query.value = "restored"
        }
        field.assertTextEquals("restored")
    }

    @Test fun emptySearchUses56dpDockIn280dpWindowAndGrowsAtFont2() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val holder = CatalogStateHolder(object : CatalogReader {
            override suspend fun browse(request: CatalogRequest) = LibraryPage(emptyList(), 0)
            override suspend fun detail(kind: String, id: String) = LibraryItem(kind, id, id)
            override suspend fun revision() = 0L
        }, scope, initialKind = "all")
        val scale = mutableStateOf(1f)
        var density = 0f
        try {
            compose.setContent {
                val current = LocalDensity.current
                density = current.density
                CompositionLocalProvider(LocalDensity provides Density(current.density, scale.value)) {
                    MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                        val navigation by holder.state.collectAsState()
                        CatalogScreenContent(navigation, holder, {}, { _, _ -> }, {}, {},
                            artwork = { _, modifier -> Box(modifier) },
                            modifier = Modifier.width(280.dp).height(640.dp))
                    }
                }
            }
            fun dock() = compose.onNodeWithTag("search-dock").fetchSemanticsNode().boundsInRoot
            fun field() = compose.onNodeWithTag("search-query").fetchSemanticsNode().boundsInRoot
            val ordinary = dock()
            assertEquals(280f * density, ordinary.width, .5f)
            assertEquals(264f * density, field().width, .5f)
            assertEquals(56f * density, ordinary.height, .5f)
            assertEquals(48f * density, field().height, .5f)
            compose.runOnIdle { scale.value = 2f }
            assertTrue("Large placeholder glyphs must grow the dock", dock().height > ordinary.height)
            assertTrue(field().top >= dock().top && field().bottom <= dock().bottom)
        } finally {
            holder.close()
            scope.cancel()
        }
    }

    @Test fun fieldIs48dpAtDefaultAndGrowsRatherThanClipsAtFont2With48dpClear() {
        var density = 0f
        val scale = mutableStateOf(1f)
        compose.setContent {
            val current = LocalDensity.current
            density = current.density
            CompositionLocalProvider(LocalDensity provides Density(current.density, scale.value)) {
                MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                    Box(Modifier.width(280.dp)) { CommittedSearchField("long Unicode café 音楽 🎵") { _, _ -> } }
                }
            }
        }
        fun fieldBounds() = compose.onNodeWithTag("search-query").fetchSemanticsNode().boundsInRoot
        fun clearBounds() = compose.onNodeWithContentDescription("Clear search").fetchSemanticsNode().boundsInRoot
        val ordinary = fieldBounds()
        assertEquals(48f * density, ordinary.height, .5f)
        assertEquals(48f * density, clearBounds().height, .5f)
        assertEquals(48f * density, clearBounds().width, .5f)
        compose.runOnIdle { scale.value = 2f }
        val enlarged = fieldBounds()
        assertTrue("Scaled glyphs must grow the field", enlarged.height > ordinary.height)
        assertTrue(clearBounds().top >= enlarged.top && clearBounds().bottom <= enlarged.bottom)
        assertEquals(ordinary.width, enlarged.width, .5f)
    }
}
