package dev.spofify.mobile.feature.playlists

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.ArtistCredit
import dev.spofify.mobile.core.LibraryItem
import dev.spofify.mobile.core.ServerAddress
import dev.spofify.mobile.core.SessionStore
import dev.spofify.mobile.core.SpofifyClient
import dev.spofify.mobile.core.StoredSession
import dev.spofify.mobile.ui.theme.AppTypography
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Authenticated loopback-only fixture; component evidence is not native/device validation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h568dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistOccurrenceActionsTest {
    private val effectScheduler = TestCoroutineScheduler()
    private val effectDispatcher = StandardTestDispatcher(effectScheduler, name = "PlaylistOccurrenceActions-effects")
    @get:Rule val compose = createComposeRule(effectContext = effectDispatcher)

    @Test fun moreKeepsQueueLowAndHighDistinctReachableAndWithoutMusicalWork() = checkActions()

    private fun checkActions() {
        MockWebServer().use { web ->
            web.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/api/auth/login" -> if (request.method == "POST") json(
                        """{"accountId":"fixture-account","username":"listener","role":"user","csrfToken":"fixture-csrf"}"""
                    ).setHeader("Set-Cookie", "spofify_session=fixture-session; Path=/; HttpOnly; Max-Age=3600")
                    else MockResponse().setResponseCode(405)
                    "/api/me/likes/status" -> if (request.method == "POST" &&
                        request.getHeader("Cookie") == "spofify_session=fixture-session" &&
                        request.getHeader("X-CSRF-Token") == "fixture-csrf") {
                        json("""{"items":[{"trackId":"fixture-track","liked":false}]}""")
                    } else MockResponse().setResponseCode(401)
                    else -> MockResponse().setResponseCode(404)
                }
            }
            web.start(InetAddress.getByName("127.0.0.1"), 0)
            val client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), object : SessionStore {
                override fun read(): StoredSession? = null
                override fun write(value: StoredSession?) = Unit
            })
            val shown = mutableStateOf(true)
            val events = mutableListOf<String>()
            var rendered = false
            try {
                runBlocking { client.login("listener", "synthetic-password") }
                assertNotNull(client.currentSession())
                val track = LibraryItem("track", "fixture-track", "Fixture song", hasAudio = true,
                    artists = (1..12).map { ArtistCredit("fixture-artist-$it", "Credited artist $it") })
                compose.setContent {
                    MaterialTheme(typography = AppTypography) {
                        if (shown.value) PlaylistOccurrenceActions(client, track,
                            onDismiss = {}, onOpen = {}, onAdd = {}, onPersonalChange = {},
                            playbackEnabled = true, contextualLabel = "Queue High",
                            onPlayNow = { events += "play-now" }, onContextual = { events += "contextual" },
                            onEnqueue = { events += "enqueue" }, onPlayNext = { events += "play-next" },
                            onOpenItem = {})
                    }
                }
                rendered = true
                compose.waitUntil(5_000) {
                    // V2 drains the owned standard effect dispatcher before inspecting UI.
                    compose.waitForIdle()
                    compose.onAllNodesWithText("Like song").fetchSemanticsNodes().size == 1
                }
                compose.runOnIdle { assertEquals(emptyList<String>(), events) }
                val swipeLabel = "Queue High"
                compose.onAllNodesWithText("Queue Low").assertCountEquals(1)
                compose.onAllNodesWithText("Play now").assertCountEquals(1)
                compose.onAllNodesWithText(swipeLabel).assertCountEquals(1)
                compose.onAllNodesWithText("Play next").assertCountEquals(1)
                // Long intentional credits force a finite scroll body; both kinds of intent stay reachable.
                compose.onNodeWithText("Credited artist 12").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Queue Low").performScrollTo().assertIsDisplayed()
                    .assertHeightIsAtLeast(48.dp).assertIsEnabled().performClick()
                compose.runOnIdle { assertEquals(listOf("enqueue"), events) }
                compose.onNodeWithText(swipeLabel).performScrollTo().assertIsDisplayed()
                    .assertHeightIsAtLeast(48.dp).assertIsEnabled().performClick()
                compose.runOnIdle { assertEquals(listOf("enqueue", "contextual"), events) }

                val login = web.takeRequest(1, TimeUnit.SECONDS)
                val status = web.takeRequest(1, TimeUnit.SECONDS)
                assertNotNull(login)
                assertNotNull(status)
                assertEquals("/api/auth/login", login!!.path)
                assertEquals("POST", login.method)
                assertEquals("/api/me/likes/status", status!!.path)
                assertEquals("POST", status.method)
                assertEquals("spofify_session=fixture-session", status.getHeader("Cookie"))
                assertEquals("fixture-csrf", status.getHeader("X-CSRF-Token"))
                assertEquals("Only authentication and the approved like-status read reach the fixture", 2, web.requestCount)
            } finally {
                try {
                    if (rendered) {
                        compose.runOnIdle { shown.value = false }
                        compose.waitForIdle()
                    }
                } finally {
                    client.httpClient.dispatcher.cancelAll()
                    client.httpClient.connectionPool.evictAll()
                    client.httpClient.dispatcher.executorService.shutdown()
                }
            }
        }
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
