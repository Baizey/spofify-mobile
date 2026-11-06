package dev.spofify.mobile.feature.playlists

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.spofify.mobile.core.*
import dev.spofify.mobile.feature.library.*
import dev.spofify.mobile.ui.theme.AppTypography
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

/** Real browsing hosts, not copied row constants or a screenshot/native-acceptance substitute. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistSharedRowHostTest {
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher(TestCoroutineScheduler()))

    @Test fun recentSearchAndBothPlaylistKindsShareActualCopyArtMoreBoundsAndAdjoiningPitchAtNarrowFont2() {
        val tracks = listOf(
            LibraryItem("track", "one", "音楽 🌙 a long title with collaborators", hasAudio = true,
                artists = listOf(ArtistCredit("a", "宇多田ヒカル 🌙 long credit"), ArtistCredit("b", "Björk")), durationMs = 185000),
            LibraryItem("track", "two", "Unavailable song", hasAudio = false),
        )
        val encoded = tracks.map { ApiJson.encodeToString(it) }
        val passivePreparations = AtomicInteger()
        MockWebServer().use { web ->
            fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            fun summary(derived: Boolean) = """{"id":"${if (derived) "derived" else "manual"}","name":"Listening mix","kind":"${if (derived) "derived" else "manual"}","countState":"${if (derived) "unevaluated" else "exact"}","trackCount":${if (derived) "null" else "2"},"availableCount":${if (derived) "null" else "1"},"revision":5}"""
            web.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    if (path.endsWith("/playback")) passivePreparations.incrementAndGet()
                    return when {
                        path == "/api/auth/login" -> json("""{"accountId":"fixture","username":"listener","role":"user","csrfToken":"synthetic"}""")
                            .setHeader("Set-Cookie", "spofify_session=synthetic; Path=/; HttpOnly; Max-Age=3600")
                        path == "/api/library/revision" -> json("""{"revision":1}""")
                        path == "/api/library" -> json("""{"items":[${if (request.requestUrl!!.queryParameter("kind") == "tracks") encoded.joinToString(",") else ""}],"total":${if (request.requestUrl!!.queryParameter("kind") == "tracks") 2 else 0},"offset":0,"limit":40,"revision":1}""")
                        path == "/api/me/playlists/manual" -> json(summary(false))
                        path == "/api/me/playlists/derived" -> json(summary(true))
                        path == "/api/me/playlists/manual/entries" -> json("""{"items":[${encoded.mapIndexed { i, track -> """{"id":"entry-$i","position":$i,"track":$track}""" }.joinToString(",")}],"total":2,"offset":0,"limit":40}""")
                        path == "/api/me/playlists/derived/evaluation" -> json("""{"items":[${encoded.mapIndexed { i, track -> """{"occurrenceId":"path-$i","selectionToken":"token.$i","position":$i,"track":$track}""" }.joinToString(",")}],"total":2,"availableTotal":1,"limit":40,"nextCursor":null,"revision":5,"fence":"fence","complete":true}""")
                        path == "/api/me/playlists" -> json("""{"items":[],"total":0,"offset":0,"limit":40}""")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            web.start()
            val client = SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), object : SessionStore {
                override fun read(): StoredSession? = null
                override fun write(value: StoredSession?) = Unit
            })
            runBlocking { client.login("listener", "synthetic") }
            val host = mutableStateOf("recent")
            val scale = mutableFloatStateOf(1f)
            val shown = mutableStateOf(true)
            var rendererCalls = 0
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale.floatValue)) {
                    MaterialTheme(typography = AppTypography) {
                        if (shown.value) key(host.value, scale.floatValue) {
                            val artwork: @Composable (LibraryItem, Modifier) -> Unit = { _, modifier ->
                                SideEffect { rendererCalls++ }; Box(modifier)
                            }
                            Box(Modifier.requiredSize(320.dp, 900.dp)) {
                                when (host.value) {
                                    "recent" -> {
                                        val scope = rememberCoroutineScope()
                                        val holder = remember { HomeStateHolder(object : HomeReader {
                                            override suspend fun recent() = tracks
                                            override suspend fun likedCount() = 0
                                            override suspend fun playlists() = emptyList<PersonalPlaylistSummary>()
                                        }, scope) }
                                        LibraryHome(holder, {}, { _, _ -> fail("Browsing must not Play") }, {}, {}, {}, {}, artwork)
                                    }
                                    "search" -> SearchRoute(client, initialQuery = "fixture", onOpenItem = {},
                                        onPlayItem = { _, _ -> fail("Browsing must not Play") }, onShowActions = {}, onEnqueueItem = {}, artwork = artwork)
                                    else -> PlaylistsRoute(client, {}, { _, _, _ -> fail("Browsing must not prepare") }, {},
                                        initialPlaylistId = host.value, artwork = artwork)
                                }
                            }
                        }
                    }
                }
            }
            try {
                for (fontScale in listOf(1f, 2f)) {
                    var reference: List<List<Rect>>? = null
                    for (source in listOf("recent", "search", "manual", "derived")) {
                        compose.runOnIdle { host.value = source; scale.floatValue = fontScale }
                        compose.waitUntil(10_000) {
                            compose.waitForIdle()
                            compose.onAllNodesWithContentDescription("More actions for ${tracks.last().name}").fetchSemanticsNodes().size == 1
                        }
                        val rows = tracks.map { track ->
                            val row = compose.onNode(hasText(track.name) and SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).fetchSemanticsNode().boundsInRoot
                            val title = compose.onNodeWithText(track.name, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                            val more = compose.onNodeWithContentDescription("More actions for ${track.name}").fetchSemanticsNode().boundsInRoot
                            val index = tracks.indexOf(track)
                            val art = compose.onAllNodesWithContentDescription("Song", useUnmergedTree = true)[index].fetchSemanticsNode().boundsInRoot
                            val credit = compose.onNodeWithText(compactMusicSubtitle(track), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                            // Compare local slots, leaving meaningful shelf/header spacing host-owned.
                            listOf(row, art, title, credit, more).map { it.translate(-row.left, -row.top) }
                        }
                        reference?.let { expected ->
                            for (i in rows.indices) for (slot in rows[i].indices) {
                                val before = expected[i][slot]; val after = rows[i][slot]
                                val context = "$source font=$fontScale row=$i slot=$slot"
                                assertEquals(context, before.left, after.left, 1f); assertEquals(context, before.top, after.top, 1f)
                                assertEquals(context, before.width, after.width, 1f); assertEquals(context, before.height, after.height, 1f)
                            }
                        } ?: run { reference = rows }
                        val first = compose.onNodeWithContentDescription("More actions for ${tracks.first().name}").fetchSemanticsNode().boundsInRoot
                        val second = compose.onNodeWithContentDescription("More actions for ${tracks.last().name}").fetchSemanticsNode().boundsInRoot
                        assertEquals("No inter-song gap in $source at font $fontScale", rows[0][0].height, second.top - first.top, 1f)
                        assertEquals("Unavailable/missing art uses the same pitch", rows[0][0].height, rows[1][0].height, 1f)
                    }
                }
                compose.runOnIdle { assertEquals(0, passivePreparations.get()); assertTrue(rendererCalls > 0) }
            } finally {
                compose.runOnIdle { shown.value = false }; compose.waitForIdle()
                client.httpClient.dispatcher.cancelAll(); client.httpClient.connectionPool.evictAll()
                client.httpClient.dispatcher.executorService.shutdown()
            }
        }
    }
}
