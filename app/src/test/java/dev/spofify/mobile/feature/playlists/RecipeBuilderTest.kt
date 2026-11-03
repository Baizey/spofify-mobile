package dev.spofify.mobile.feature.playlists

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import dev.spofify.mobile.core.ServerAddress
import dev.spofify.mobile.core.SessionStore
import dev.spofify.mobile.core.SpofifyClient
import dev.spofify.mobile.core.StoredSession
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecipeBuilderTest {
    @get:Rule val compose = createComposeRule()
    private val server = MockWebServer().apply { start() }
    private val client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${server.port}"), object : SessionStore {
        override fun read(): StoredSession? = null
        override fun write(value: StoredSession?) = Unit
    })

    @After fun close() { server.shutdown() }

    private fun render(initial: JsonObject = blankRecipe(), enabled: Boolean = true): MutableState<JsonObject> {
        val draft = mutableStateOf(initial)
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    RecipeBuilder(draft.value, { draft.value = it }, listOf("saved_mix" to "Evening mix"), client, enabled)
                }
            }
        }
        return draft
    }

    private fun click(text: String) { compose.onNodeWithText(text).performScrollTo().performClick() }
    private fun advanced() = click("Only keep, order and size (advanced)")

    @Test fun progressivelyDisclosesAdvancedControlsAndDoesNotFetchOnRender() {
        render()
        compose.onNodeWithText("Choose music").assertIsDisplayed()
        compose.onNodeWithText("Duplicates").assertDoesNotExist()
        compose.onNodeWithText("Exact genre label").assertDoesNotExist()
        advanced()
        compose.onNodeWithText("Duplicates").performScrollTo().assertIsDisplayed()
        assertEquals(0, server.requestCount)
    }

    @Test fun addingReorderingAndRemovingSourcesPreservesOccurrenceIdentityAndCapsAtFour() {
        val draft = render()
        val initialSlot = (draft.value["sources"] as JsonArray)[0].jsonObject.text("slotId")
        repeat(3) { click("Add source") }
        compose.onNodeWithText("Add source").assertIsNotEnabled()
        val before = (draft.value["sources"] as JsonArray).map { it.jsonObject.text("slotId") }
        assertEquals(4, before.toSet().size)
        click("Move source 2 up")
        compose.runOnIdle {
            assertEquals(listOf(before[1], before[0], before[2], before[3]), (draft.value["sources"] as JsonArray).map { it.jsonObject.text("slotId") })
        }
        click("Remove source 1")
        compose.runOnIdle {
            assertEquals(initialSlot, (draft.value["sources"] as JsonArray)[0].jsonObject.text("slotId"))
            assertNull(recipeValidationError(draft.value))
        }
    }

    @Test fun invalidTakeTextIsEmittedAndRetainedWhenChangingFirstToLast() {
        val draft = render()
        advanced()
        click("All matches (no limit)")
        compose.onNodeWithText("First N").performClick()
        val label = "Number of songs — Playlist size (after conditions and order)"
        compose.onNodeWithText(label).performScrollTo().performTextReplacement("1e2")
        compose.runOnIdle {
            assertEquals(JsonPrimitive("1e2"), draft.value["take"]!!.jsonObject["count"])
            assertNotNull(recipeValidationError(draft.value))
        }
        click("First N")
        compose.onNodeWithText("Last N").performClick()
        compose.onNodeWithText("1e2").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("last", draft.value["take"]!!.jsonObject.text("side"))
            assertEquals(JsonPrimitive("1e2"), draft.value["take"]!!.jsonObject["count"])
        }
        compose.onNodeWithText(label).performTextReplacement("1001")
        compose.runOnIdle { assertNull(recipeValidationError(draft.value)) }
    }

    @Test fun disabledBuilderCannotChangeTheDraft() {
        val initial = blankRecipe()
        val draft = render(initial, enabled = false)
        compose.onNodeWithText("Add source").assertIsNotEnabled()
        compose.onNodeWithText("Liked songs (newest first)").assertIsNotEnabled()
        advanced()
        compose.onNodeWithText("Add include condition").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(initial, draft.value) }
        assertEquals(0, server.requestCount)
    }

    @Test fun resolvesExistingTypedMusicLabelsOnceWithoutEvaluatingOrChangingTheDraft() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                "/api/library/artists/existing-artist" -> MockResponse().setHeader("Content-Type", "application/json").setBody("""{"item":{"kind":"artist","id":"existing-artist","name":"Existing Ensemble"},"revision":1}""")
                "/api/library/albums/existing-album" -> MockResponse().setHeader("Content-Type", "application/json").setBody("""{"item":{"kind":"album","id":"existing-album","name":"Existing Record"},"revision":1}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val predicate = Json.parseToJsonElement("""{"op":"any","args":[{"op":"artist","artistId":"existing-artist"},{"op":"album","albumId":"existing-album"}]}""").jsonObject
        val initial = blankRecipe().withField("include", predicate)
        val draft = render(initial)
        advanced()
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Existing Ensemble")).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(androidx.compose.ui.test.hasText("Existing Record")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("existing-artist", substring = true).assertDoesNotExist()
        compose.onNodeWithText("existing-album", substring = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(initial, draft.value) }
        assertEquals(2, server.requestCount)
        repeat(2) {
            assertTrue(server.takeRequest(5, TimeUnit.SECONDS)!!.requestUrl!!.encodedPath.startsWith("/api/library/"))
        }
    }

    @Test fun existingIdsHaveSafeFallbackLabelsAndArtistSelectionUsesSavedCatalogSearch() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
        val initial = blankRecipe().withField("include", initialRecipePredicate("artist").withField("artistId", JsonPrimitive("private-opaque-artist")))
        val draft = render(initial)
        compose.waitUntil(10_000) { server.requestCount == 1 }
        val lookup = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/api/library/artists/private-opaque-artist", lookup.requestUrl!!.encodedPath)
        advanced()
        compose.onNodeWithText("Artist unavailable").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("private-opaque-artist", substring = true).assertDoesNotExist()
        click("Choose artist")
        compose.onNodeWithText("Search saved artists").performScrollTo().performTextReplacement("Astral")
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
            """{"items":[{"kind":"artist","id":"saved-artist","name":"Astral Ensemble"},{"kind":"album","id":"wrong-kind","name":"Wrong kind"}],"total":1,"limit":40,"offset":0}"""))
        click("Search saved music")
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Astral Ensemble")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Wrong kind").assertDoesNotExist()
        click("Astral Ensemble")
        compose.onNodeWithText("Astral Ensemble").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("saved-artist", draft.value["include"]!!.jsonObject.text("artistId"))
            assertNull(recipeValidationError(draft.value))
        }
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/api/library", request.requestUrl!!.encodedPath)
        assertEquals("artists", request.requestUrl!!.queryParameter("kind"))
        assertEquals("Astral", request.requestUrl!!.queryParameter("q"))
        assertEquals("all", request.requestUrl!!.queryParameter("scope"))
        assertEquals("all", request.requestUrl!!.queryParameter("availability"))
        assertEquals(2, server.requestCount)
    }
}
