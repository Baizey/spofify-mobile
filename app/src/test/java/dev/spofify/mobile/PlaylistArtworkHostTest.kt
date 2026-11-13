package dev.spofify.mobile

import dev.spofify.mobile.feature.devices.LibraryPlaybackActionBinding
import dev.spofify.mobile.playback.PlaybackActionResult
import dev.spofify.mobile.playback.PlaybackActionStatus
import dev.spofify.mobile.playback.PlaybackInteractionKey
import dev.spofify.mobile.playback.PlaybackListAction
import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import dev.spofify.mobile.core.*
import dev.spofify.mobile.playback.PlaybackControls
import dev.spofify.mobile.playback.PlaybackState
import dev.spofify.mobile.ui.theme.AppTypography
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/** Real SpofifyApp -> playlist More, including MainActivity's configured artwork renderer.
 * Host exception/render/Back evidence only, not installed process-death or native acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaylistArtworkHostTest {
    @get:Rule val compose = createComposeRule(effectContext = object : MotionDurationScale {
        override val scaleFactor: Float = 0f
    })

    @Test fun manualCollectionMoreWithProductionArtworkStaysOpenAndBackKeepsDetail() = checkMenu(false, false)
    @Test fun derivedCollectionMoreWithProductionArtworkStaysOpenAndBackKeepsDetail() = checkMenu(true, false)
    @Test fun emptyManualCollectionMoreNeedsNoTrackOrArtworkEndpoint() = checkMenu(false, true)
    @Test fun emptyDerivedCollectionMoreNeedsNoTrackOrArtworkEndpoint() = checkMenu(true, true)

    private fun checkMenu(derived: Boolean, empty: Boolean) {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val track = LibraryItem("track", "song", "Synthetic song", hasAudio = true)
        val summary = PersonalPlaylistSummary("fixture", "Playlist fixture",
            kind = if (derived) PlaylistKind.DERIVED else PlaylistKind.MANUAL,
            countState = if (derived) PlaylistCountState.UNEVALUATED else PlaylistCountState.EXACT,
            trackCount = if (derived) null else if (empty) 0 else 1,
            availableCount = if (derived) null else if (empty) 0 else 1,
            capabilities = PlaylistCapabilities(editEntries = !derived, editDefinition = derived,
                copyToManual = derived, addTracks = !derived), revision = 5)
        val web = MockWebServer()
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when (request.requestUrl!!.encodedPath) {
                    "/api/auth/login" -> json("""{"accountId":"account","username":"listener","role":"user","csrfToken":"synthetic"}""")
                        .setHeader("Set-Cookie", "spofify_session=synthetic; Path=/; HttpOnly; Max-Age=3600")
                    "/api/library/revision" -> json("""{"revision":1}""")
                    "/api/me/playlists" -> typed(PersonalPlaylistPage(listOf(summary), 1, limit = 40))
                    "/api/me/playlists/fixture" -> typed(summary)
                    "/api/me/playlists/fixture/entries" -> if (derived) typed(DerivedEvaluationPage(
                        if (empty) emptyList() else listOf(DerivedEvaluationEntry("path", "opaque.signature", 0, track)),
                        if (empty) 0 else 1, if (empty) 0 else 1, 40, null, 5, "synthetic-fence", true))
                    else typed(PersonalEntriesPage(if (empty) emptyList() else listOf(PersonalPlaylistEntry("entry", 0, track)),
                        if (empty) 0 else 1, offset = 0, limit = 40))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        web.start(InetAddress.getByName("127.0.0.1"), 0)
        val app = ApplicationProvider.getApplicationContext<Application>()
        val graph = AppGraph(app, object : SessionStore {
            private var value: StoredSession? = null
            override fun read() = value
            override fun write(value: StoredSession?) { this.value = value }
        }, object : LoginStore {
            override fun read(): RememberedLogin? = null
            override fun write(value: RememberedLogin?) = Unit
        })
        val store = ViewModelStore()
        val shown = mutableStateOf(true)
        var mounted = false
        try {
            val client = graph.configure(ServerAddress.parse("http://127.0.0.1:${web.port}"))
            assertNotNull("Production artwork must not be bypassed by a null loader", graph.imageLoader)
            // The renderer must avoid unsupported kinds, not weaken the strict URL API.
            assertThrows(IllegalArgumentException::class.java) {
                client.artworkUrl(LibraryItem("playlist", "fixture", "Playlist fixture"))
            }
            runBlocking { client.login("listener", "synthetic-password") }
            lateinit var model: LibraryViewModel
            compose.runOnIdle {
                model = LibraryViewModel(app, graph, NoPlayback())
                store.put("playlist-artwork", model)
            }
            compose.waitUntil(10_000) { compose.runOnIdle { !model.state.value.restoring && model.state.value.session != null } }
            compose.runOnIdle {
                checkNotNull(model.playlistBrowseMemory()).openPlaylist("fixture")
                model.switchTab(Tab.PLAYLISTS)
            }
            mounted = true
            compose.setContent {
                MaterialTheme(colorScheme = listeningColorScheme(), typography = AppTypography) {
                    if (shown.value) SpofifyApp(model)
                }
            }
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                compose.onAllNodesWithText(if (empty) "No songs in this playlist." else "Synthetic song")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("More actions for Playlist fixture").performClick()
            compose.onNodeWithTag("music-actions").assertIsDisplayed()
            compose.onNodeWithText("Edit details").performScrollTo().assertIsEnabled()
            capture("${if (derived) "derived" else "manual"}-${if (empty) "empty" else "populated"}")
            val dialog = compose.runOnIdle { ShadowDialog.getLatestDialog() as ComponentDialog }
            compose.runOnIdle { dialog.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            compose.onNodeWithTag("music-actions").assertDoesNotExist()
            compose.onNodeWithContentDescription("More actions for Playlist fixture").assertIsDisplayed()
            compose.runOnIdle { assertEquals(Tab.PLAYLISTS, model.state.value.tab) }
            assertTrue("No invented playlist-artwork or playback-preparation request", requests.none {
                val path = it.requestUrl!!.encodedPath
                path.endsWith("/playback") || (path.endsWith("/artwork") && !path.startsWith("/api/library/tracks/"))
            })
            assertTrue("Opening/dismissing the menu never writes saved data", requests.none {
                it.method != "GET" && it.requestUrl!!.encodedPath != "/api/auth/login"
            })
        } finally {
            try {
                if (mounted) {
                    compose.runOnUiThread { shown.value = false }
                    compose.waitForIdle()
                }
            } finally {
                try { compose.runOnUiThread { store.clear(); graph.clear() } }
                finally { web.shutdown() }
            }
        }
    }

    private fun capture(name: String) {
        val directory = File(System.getProperty("java.io.tmpdir"), "playlist-artwork-renders").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            check(compose.onNodeWithTag("music-actions").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private inline fun <reified T> typed(value: T) = json(ApiJson.encodeToString(value))
    private class NoPlayback : PlaybackControls {
        override val state = MutableStateFlow(PlaybackState())
        override fun queueIntent(selection: LibraryPlaybackSelection, client: SpofifyClient,
            action: PlaybackListAction, expectedKey: PlaybackInteractionKey?, expectedBinding: LibraryPlaybackActionBinding?,
            shuffle: Boolean?, isCurrent: () -> Boolean, onResult: (PlaybackActionResult) -> Unit) {
            error("Opening More cannot play or queue")
        }
        override fun connect() = Unit
        override fun release() = Unit
        override fun toggle() = Unit
        override fun next() = Unit
        override fun previous() = Unit
        override fun select(index: Int) = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun toggleShuffle() = Unit
        override fun cycleRepeat() = Unit
        override fun stop() = Unit
    }
}
