package dev.spofify.mobile.ui

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.session.MediaSession
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowInsets
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import dev.spofify.mobile.LibraryViewModel
import dev.spofify.mobile.MainActivity
import dev.spofify.mobile.SpofifyApplication
import dev.spofify.mobile.playback.PlaybackService
import dev.spofify.mobile.playback.PlaybackState
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Separate, fresh-pm-clear journey; do not run after NativeUiSmokeTest in the same app process.
 * Requires VirtualAvdTestRunner and an explicitly selected isolated synthetic AVD. No external
 * server, physical device, second output, direct service transport helper, or injected UI model.
 *
 * Keys go through Android input/AudioManager; seeks go through a real Media3 MediaController;
 * notification commands send the actual posted PendingIntent. These are SYNTHETIC delivery
 * checks against a single-output synthetic v2 socket subset, NOT real-server authority,
 * transfer/planned-next acceptance, acoustic, physical-headset/AVRCP, OEM or visual approval.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
@androidx.annotation.OptIn(UnstableApi::class)
class NativeMediaControlsTest {
    companion object {
        @JvmStatic @BeforeClass fun virtualDeviceOnly() = VirtualAvdOnly.verify()
        private const val TIMEOUT_MS = 20_000L
    }

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val audio get() = context.getSystemService(AudioManager::class.java)
    private lateinit var model: LibraryViewModel
    private var controller: MediaController? = null
    private val evidence: File get() {
        VirtualAvdOnly.requireVerified()
        return File(checkNotNull(context.getExternalFilesDir(null)), "ui-evidence")
            .apply { check(isDirectory || mkdirs()) { "Cannot create native evidence directory" } }
    }
    private val expandPlayer = SemanticsMatcher("Expand Now Playing action") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == "Expand Now Playing"
    }

    @Test fun syntheticSystemMediaControlsPreserveServiceAuthority() {
        VirtualAvdOnly.requireVerified()
        UiFixtureServer().use { fixture ->
            var failure: Throwable? = null
            try {
                writeRunInfo()
                signInAndPlay(fixture)
                connectController()
                val initial = awaitPlaying()
                val originalQueue = initial.queue
                assertEquals(UiFixtureServer.TRACK_NAMES.take(6), originalQueue.map { it.second })
                assertEquals(5, initial.upcoming.size)
                assertControllerContract()
                assertNotification(initial, playing = true)
                screenshot("01-foreground-playing")

                // Native input sends both down/up. A mistaken double dispatch must not toggle
                // Pause straight back to Play, or skip two occurrences on a single Next.
                mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                val paused = awaitPaused()
                assertIdentity(initial, paused, "Pause")
                assertPausedFor(paused, 800)
                seekTo(30_000)
                val prepared = awaitPosition(30_000)
                mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                val resumed = awaitPlaying()
                assertIdentity(prepared, resumed, "positive Play resumes the prepared occurrence")
                assertTrue("Play must not reset the prepared position", resumed.positionMs >= 29_000)
                assertAdvancing(resumed, "foreground Play")
                mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                val toggledPause = awaitPaused()
                assertPausedFor(toggledPause, 800)
                mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                assertAdvancing(awaitPlaying(), "Play/Pause toggle")
                headsetHook()
                val hookPaused = awaitPaused()
                assertPausedFor(hookPaused, 800) // Includes any platform single-tap delay.
                headsetHook()
                assertAdvancing(awaitPlaying(), "synthetic single headset-hook")
                note("headsetHook=synthetic single down/up only; no physical multi-click claim")

                mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                val second = awaitTrack(UiFixtureServer.TRACK_NAMES[1])
                assertTrue(second.playing)
                assertNotEquals(initial.occurrence, second.occurrence)
                assertEquals(originalQueue, second.queue)
                assertEquals(initial.upcoming.first(), second.occurrence)
                assertNotification(second, playing = true)

                // Establish a deterministic restart-vs-previous boundary without racing the
                // AVD's dispatch speed against the player's three-second Previous threshold.
                mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                awaitPaused()
                seekTo(30_000)
                val beforeRestart = awaitPosition(30_000)
                mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                val restarted = awaitPlaying()
                assertIdentity(beforeRestart, restarted, "Previous after the restart threshold")
                assertTrue("Previous must restart, not retain the old offset", restarted.positionMs < 10_000)
                mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                awaitPaused()
                seekTo(0)
                awaitPosition(0)
                mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                val firstAgain = awaitTrack(UiFixtureServer.TRACK_NAMES.first())
                assertEquals(initial.occurrence, firstAgain.occurrence)
                assertEquals(originalQueue, firstAgain.queue)

                // Real AudioManager media dispatch, including a held/repeated DOWN and UP.
                // Only the initial DOWN may advance traversal. Observe a quiet tail as well.
                heldNext()
                val held = awaitTrack(UiFixtureServer.TRACK_NAMES[1])
                assertEquals(second.occurrence, held.occurrence)
                assertSelectionFor(held, 1_000)
                mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                awaitPaused()
                verifySupportedControllerSeeks()
                mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                verifyMusicVolumeKeys(fixture)
                screenshot("02-foreground-volume-and-transport")

                // Observe the actual posted notification, including its MediaSession token.
                // Sending its PendingIntent is platform delivery, not an app transport call.
                sendNotificationAction("Pause")
                val notificationPaused = awaitPaused()
                assertNotification(notificationPaused, playing = false)
                sendNotificationAction("Resume")
                val notificationPlaying = awaitPlaying()
                assertIdentity(notificationPaused, notificationPlaying, "notification Resume")
                assertAdvancing(notificationPlaying, "notification Resume")

                val beforeHome = sample()
                shell("input keyevent ${KeyEvent.KEYCODE_HOME}")
                await("Activity must actually leave RESUMED after Home") {
                    !compose.activityRule.scenario.state.isAtLeast(Lifecycle.State.RESUMED)
                }
                val background = sample()
                assertIdentity(beforeHome, background, "Home backgrounding")
                assertTimeline(beforeHome, background, "Home backgrounding")
                assertAdvancing(background, "background service")
                mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                val backgroundPaused = awaitPaused()
                assertIdentity(background, backgroundPaused, "background Pause")
                assertPausedFor(backgroundPaused, 800)
                mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                assertAdvancing(awaitPlaying(), "background user Play")
                mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                val third = awaitTrack(UiFixtureServer.TRACK_NAMES[2])
                assertNotification(third, playing = true)
                shell("cmd statusbar expand-notifications")
                screenshot("03-background-notification")
                shell("cmd statusbar collapse")

                // Screen-off delivery is distinct from a secured physical lock screen. Never
                // set/change a PIN or disable keyguard to manufacture a locked-screen pass.
                shell("input keyevent ${KeyEvent.KEYCODE_SLEEP}")
                await("AVD screen did not turn off") {
                    !context.getSystemService(PowerManager::class.java).isInteractive
                }
                mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                val screenOffPaused = awaitPaused()
                assertIdentity(third, screenOffPaused, "screen-off Pause")
                mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                assertAdvancing(awaitPlaying(), "screen-off user Play")
                shell("input keyevent ${KeyEvent.KEYCODE_WAKEUP}")
                await("AVD screen did not wake") {
                    context.getSystemService(PowerManager::class.java).isInteractive
                }
                note("keyguardLockedAfterWake=${context.getSystemService(KeyguardManager::class.java).isKeyguardLocked}")
                screenshot("04-woken-system-surface")
                shell("wm dismiss-keyguard") // Isolated, non-secure test AVD only; no lock settings changed.
                compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                waitFor(expandPlayer)
                val beforeRecreation = sample()
                val token = notificationToken()
                compose.activityRule.scenario.recreate()
                waitFor(expandPlayer)
                instrumentation.runOnMainSync {
                    model = ViewModelProvider(compose.activity)[LibraryViewModel::class.java]
                }
                val recreated = awaitPlaying()
                assertIdentity(beforeRecreation, recreated, "Activity recreation")
                assertTimeline(beforeRecreation, recreated, "Activity recreation")
                assertEquals("Activity recreation must not replace the service session", token, notificationToken())
                assertAdvancing(recreated, "recreated Activity")
                assertEquals("Lifecycle changes must retain all prepared occurrences", originalQueue, recreated.queue)
                mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                val finalPaused = awaitPaused()
                assertNotification(finalPaused, playing = false)
                compose.onNode(expandPlayer).performClick()
                waitFor(hasTestTag("now-playing"))
                screenshot("05-recreated-paused")
                mediaKey(KeyEvent.KEYCODE_MEDIA_STOP)
                assertIdentity(finalPaused, awaitPaused(), "Stop remains non-destructive Pause")
                assertPausedFor(finalPaused, 800)

                assertEquals("Transport/lifecycle must not replace the synthetic queue", 1, fixture.queueReplacements.get())
                assertTrue("Synthetic socket failures: ${fixture.socketFailures}", fixture.socketFailures.isEmpty())
                assertEquals("No query-free catalog browse", 0, fixture.emptyGlobalBrowseCalls.get())
                assertEquals("Authenticated cookie/CSRF must reach the fixture", 0, fixture.authenticationFailures.get())
                assertTrue("Unexpected fixture routes: ${fixture.unexpectedRoutes}", fixture.unexpectedRoutes.isEmpty())
                note("result=passed synthetic delivery assertions; screenshot inspection still required")
            } catch (error: Throwable) {
                failure = error
                runCatching { note("result=failed (${error.javaClass.simpleName}); no native acceptance implied") }
                runCatching { screenshot("99-failure") }
                throw error
            } finally {
                // Stop through the OS before disconnecting the observer. Closing the Activity
                // also releases its existing binding before the loopback fixture goes away.
                var cleanupFailure: Throwable? = null
                fun cleanup(action: () -> Unit) {
                    try { action() } catch (error: Throwable) {
                        val prior = cleanupFailure
                        if (prior == null) cleanupFailure = error else prior.addSuppressed(error)
                    }
                }
                cleanup { shell("input keyevent ${KeyEvent.KEYCODE_WAKEUP}") }
                cleanup { shell("cmd statusbar collapse") }
                if (controller != null) cleanup { mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE); awaitPaused() }
                cleanup { instrumentation.runOnMainSync { controller?.release(); controller = null } }
                cleanup { compose.activityRule.scenario.close() }
                // Disposal only, not a media-dispatch assertion: a paused started service can
                // otherwise outlive the Activity and retain its synthetic socket.
                cleanup { onMain { (context.applicationContext as SpofifyApplication).graph.clear() } }
                cleanup {
                    note("audioRequests=${fixture.audioRequests.get()}\nqueueReplacements=${fixture.queueReplacements.get()}\n" +
                        "qualifiedHistoryEvents=${fixture.qualifiedHistoryEvents.get()}\nauthenticationFailures=${fixture.authenticationFailures.get()}\n" +
                        "unexpectedRoutes=${fixture.unexpectedRoutes.size}")
                }
                cleanupFailure?.let { error ->
                    val primary = failure
                    if (primary != null) primary.addSuppressed(error) else throw error
                }
            }
        }
    }

    private fun signInAndPlay(fixture: UiFixtureServer) {
        waitFor(hasText("Server URL")) // A restored login is an isolation error, not a fallback.
        compose.onNodeWithText("Server URL").performScrollTo().performClick().performTextReplacement(fixture.origin)
        compose.onNodeWithText("Username").performScrollTo().performClick().performTextReplacement(UiFixtureServer.USERNAME)
        compose.onNodeWithText("Password").performScrollTo().performClick().performTextReplacement(UiFixtureServer.PASSWORD)
        if (Build.VERSION.SDK_INT >= 30) await("Sign-in IME must open before native Back") {
            onMain { compose.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNode(hasText("Sign in") and hasClickAction()).performScrollTo().performClick()
        waitFor(hasText("Recently played"))
        assertEquals(0, fixture.audioRequests.get())
        compose.onNodeWithText("Playlists").performClick()
        waitFor(hasText(UiFixtureServer.MANUAL_NAME))
        compose.onNodeWithText(UiFixtureServer.MANUAL_NAME).performClick()
        waitFor(hasText("Reorder all occurrences"))
        val play = hasText("Play") and hasClickAction()
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodes(play and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodes(play and isEnabled()).onFirst().performScrollTo().performClick()
        waitFor(expandPlayer)
        instrumentation.runOnMainSync {
            model = ViewModelProvider(compose.activity)[LibraryViewModel::class.java]
        }
        await("Synthetic audio was not requested") { fixture.audioRequests.get() > 0 }
        compose.onNode(expandPlayer).performClick()
        waitFor(hasTestTag("now-playing"))
        // Collapse before Home/recreation so an Activity-owned Dialog is not the subject of
        // lifecycle control. The production service and prepared queue remain authoritative.
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodes(hasTestTag("now-playing")).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun connectController() {
        val future = onMain {
            MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
                .buildAsync()
        }
        try {
            controller = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (error: Throwable) {
            onMain { MediaController.releaseFuture(future) }
            throw error
        }
    }

    private data class Sample(
        val title: String?, val occurrence: String?, val queue: List<Pair<String, String>>,
        val upcoming: List<String>, val playing: Boolean, val positionMs: Long,
        val durationMs: Long, val sampledAtMs: Long, val volume: Float?,
    )

    // Read the real connected service/controller on its application looper, even while the
    // Activity is stopped. Compose idling is deliberately not a background-playback oracle.
    private fun sample(): Sample = onMain {
        val player = checkNotNull(controller)
        check(player.isConnected) { "Production MediaSession controller disconnected" }
        val state: PlaybackState = model.playback.state.value
        val item = player.currentMediaItem
        Sample(item?.mediaMetadata?.title?.toString(), state.currentEntryId,
            (0 until player.mediaItemCount).map { index ->
                val entry = player.getMediaItemAt(index)
                entry.mediaId to entry.mediaMetadata.title.toString()
            }, state.upcoming.map { it.occurrenceId }, player.isPlaying, player.currentPosition,
            player.duration, SystemClock.elapsedRealtime(),
            if (player.isCommandAvailable(Player.COMMAND_GET_VOLUME)) player.volume else null)
    }

    private fun assertControllerContract() = onMain {
        val player = checkNotNull(controller)
        listOf(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM).forEach {
            assertTrue("Required session command unavailable: $it", player.isCommandAvailable(it))
        }
        listOf(Player.COMMAND_SET_VOLUME, Player.COMMAND_SET_SPEED_AND_PITCH,
            Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS).forEach {
            assertFalse("Raw output/queue mutator must remain unavailable: $it", player.isCommandAvailable(it))
        }
        note("playerGainObservable=${player.isCommandAvailable(Player.COMMAND_GET_VOLUME)}")
        if (!player.isCommandAvailable(Player.COMMAND_GET_VOLUME)) {
            note("gainLimit=production controller does not publish GET_VOLUME; raw ExoPlayer gain is NOT established by its default getter")
        }
    }

    private fun awaitPlaying(): Sample {
        await("Expected actual controller isPlaying and matching UI-facing occurrence") {
            val current = sample()
            current.playing && !current.occurrence.isNullOrBlank() && onMain {
                val state = model.playback.state.value
                state.isPlaying && state.item?.name == current.title
            }
        }
        return sample()
    }
    private fun awaitPaused(): Sample {
        await("Expected paused controller") { !sample().playing && !onMain { checkNotNull(controller).playWhenReady } }
        return sample()
    }
    private fun awaitTrack(title: String): Sample {
        await("Expected selected synthetic track $title") {
            val current = sample()
            current.title == title && current.playing && onMain { model.playback.state.value.item?.name == title }
        }
        return sample()
    }
    private fun awaitPosition(position: Long): Sample {
        await("Expected controller seek to $position ms") { kotlin.math.abs(sample().positionMs - position) <= 1_000 }
        return sample()
    }
    private fun assertIdentity(before: Sample, after: Sample, label: String) {
        assertEquals("$label title", before.title, after.title)
        assertNotNull("$label requires a real prepared occurrence", before.occurrence)
        assertEquals("$label occurrence", before.occurrence, after.occurrence)
        assertEquals("$label physical timeline", before.queue, after.queue)
        assertEquals("$label upcoming occurrence traversal", before.upcoming, after.upcoming)
        assertEquals("$label duration", before.durationMs, after.durationMs)
    }
    private fun assertTimeline(before: Sample, after: Sample, label: String) {
        val elapsed = after.sampledAtMs - before.sampledAtMs
        assertTrue("$label reset/froze/jumped position",
            kotlin.math.abs(after.positionMs - before.positionMs - elapsed) < 2_000)
    }
    private fun assertAdvancing(before: Sample, label: String) {
        assertTrue("$label must begin playing", before.playing)
        await("$label must advance actual service position") {
            val after = sample()
            assertIdentity(before, after, label)
            assertTimeline(before, after, label)
            after.playing && after.positionMs >= before.positionMs + 350
        }
    }
    private fun assertPausedFor(before: Sample, milliseconds: Long) = observeFor(milliseconds) {
        val after = sample()
        assertIdentity(before, after, "paused observation")
        assertFalse(after.playing)
        assertEquals("Paused position must remain stable", before.positionMs.toDouble(), after.positionMs.toDouble(), 250.0)
    }
    private fun assertSelectionFor(before: Sample, milliseconds: Long) = observeFor(milliseconds) {
        assertIdentity(before, sample(), "held-key quiet tail")
    }

    private fun seekTo(milliseconds: Long) = onMain {
        val player = checkNotNull(controller)
        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
        player.seekTo(milliseconds) // Actual service MediaSession, not PlaybackControls.seek().
    }
    private fun verifySupportedControllerSeeks() {
        seekTo(30_000)
        var before = awaitPosition(30_000)
        if (onMain { checkNotNull(controller).isCommandAvailable(Player.COMMAND_SEEK_FORWARD) }) {
            val increment = onMain { checkNotNull(controller).seekForwardIncrement }
            onMain { checkNotNull(controller).seekForward() }
            val after = awaitPosition((before.positionMs + increment).coerceAtMost(before.durationMs))
            assertIdentity(before, after, "controller seekForward")
            note("seekForward=delivered through MediaSession")
            before = after
        } else note("seekForward=not published; not claimed")
        if (onMain { checkNotNull(controller).isCommandAvailable(Player.COMMAND_SEEK_BACK) }) {
            val increment = onMain { checkNotNull(controller).seekBackIncrement }
            onMain { checkNotNull(controller).seekBack() }
            assertIdentity(before, awaitPosition((before.positionMs - increment).coerceAtLeast(0)), "controller seekBack")
            seekTo(0)
            val zero = awaitPosition(0)
            onMain { checkNotNull(controller).seekBack() }
            assertPausedFor(zero, 500)
            note("seekBack=delivered through MediaSession; lower bound observed at zero")
        } else note("seekBack=not published; not claimed")
        seekTo(30_000)
        awaitPosition(30_000)
    }

    private fun verifyMusicVolumeKeys(fixture: UiFixtureServer) {
        val originallyMuted = audio.isStreamMute(AudioManager.STREAM_MUSIC)
        var restoreIndex: Int? = null
        var failure: Throwable? = null
        try {
            if (originallyMuted) {
                volumeKey(KeyEvent.KEYCODE_VOLUME_MUTE)
                await("Unmute test music stream") { !audio.isStreamMute(AudioManager.STREAM_MUSIC) }
            }
            restoreIndex = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val min = if (Build.VERSION.SDK_INT >= 28) audio.getStreamMinVolume(AudioManager.STREAM_MUSIC) else 0
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            assertTrue("AVD must expose at least two adjustable local music steps", max - min >= 2)
            val before = awaitPlaying()
            val replacements = fixture.queueReplacements.get()
            val admissionCount = fixture.playerCalls.count { it == "hello" }
            val musicalRequests = fixture.playerCalls.count { it == "command" }
            // Leave a real step in both directions even if the original setting was a boundary.
            val middle = (min + (max - min) / 2).coerceIn(min + 1, max - 1)
            setMusicIndexWithKeys(middle)
            volumeKey(KeyEvent.KEYCODE_VOLUME_UP)
            await("Volume Up must change OS STREAM_MUSIC, not player gain") {
                audio.getStreamVolume(AudioManager.STREAM_MUSIC) > middle
            }
            val raised = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            volumeKey(KeyEvent.KEYCODE_VOLUME_DOWN)
            await("Volume Down must change OS STREAM_MUSIC") {
                audio.getStreamVolume(AudioManager.STREAM_MUSIC) < raised
            }
            volumeKey(KeyEvent.KEYCODE_VOLUME_MUTE)
            await("Volume Mute must mute OS STREAM_MUSIC") { audio.isStreamMute(AudioManager.STREAM_MUSIC) }
            val muted = sample()
            assertIdentity(before, muted, "OS mute is not musical transport")
            assertTrue("OS mute must not pause the player", muted.playing)
            volumeKey(KeyEvent.KEYCODE_VOLUME_MUTE)
            await("Restore unmuted music stream") { !audio.isStreamMute(AudioManager.STREAM_MUSIC) }
            assertAdvancing(sample(), "OS volume changes")
            val after = sample()
            assertIdentity(before, after, "volume keys")
            if (before.volume != null) assertEquals("OS volume must not change player gain", before.volume, after.volume)
            assertEquals("Volume keys must not replace the queue", replacements, fixture.queueReplacements.get())
            assertEquals("Volume keys must not admit another socket", admissionCount, fixture.playerCalls.count { it == "hello" })
            assertEquals("Volume keys must not send musical socket commands", musicalRequests,
                fixture.playerCalls.count { it == "command" })
            note("volumeEvidence=foreground Android input changed STREAM_MUSIC up/down/mute while controller progress continued; acoustic output not measured")
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                if (audio.isStreamMute(AudioManager.STREAM_MUSIC)) {
                    volumeKey(KeyEvent.KEYCODE_VOLUME_MUTE)
                    await("Unmute before restoring music index") { !audio.isStreamMute(AudioManager.STREAM_MUSIC) }
                }
                restoreIndex?.let(::setMusicIndexWithKeys)
                if (originallyMuted) {
                    volumeKey(KeyEvent.KEYCODE_VOLUME_MUTE)
                    await("Restore original muted music stream") { audio.isStreamMute(AudioManager.STREAM_MUSIC) }
                }
            } catch (restoreFailure: Throwable) {
                if (failure != null) failure.addSuppressed(restoreFailure) else throw restoreFailure
            }
        }
    }

    private fun setMusicIndexWithKeys(target: Int) {
        // Bounded by the exposed stream range; never change app gain or any OS setting directly.
        repeat(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) + 2) {
            val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (current == target) return
            volumeKey(if (current < target) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN)
            await("Music-stream key must take effect") { audio.getStreamVolume(AudioManager.STREAM_MUSIC) != current }
        }
        assertEquals("Restore/establish exact music stream index", target, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    }
    private fun volumeKey(key: Int) = shell("input keyevent $key").let { Unit }
    private fun mediaKey(key: Int) {
        require(key in setOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_STOP))
        shell("input keyevent $key")
    }
    private fun headsetHook() {
        VirtualAvdOnly.requireVerified()
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK, 0))
    }
    private fun heldNext() {
        VirtualAvdOnly.requireVerified()
        val downTime = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, 0))
        SystemClock.sleep(80)
        audio.dispatchMediaKeyEvent(KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, 1))
        audio.dispatchMediaKeyEvent(KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT, 0))
    }

    private fun playbackNotification(): Notification? = context.getSystemService(NotificationManager::class.java)
        .activeNotifications.singleOrNull { it.notification.category == Notification.CATEGORY_TRANSPORT }?.notification
    @Suppress("DEPRECATION")
    private fun notificationToken(): MediaSession.Token = checkNotNull(
        playbackNotification()?.extras?.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION),
    ) { "Production MediaStyle notification must publish a real platform MediaSession token" }
    private fun assertNotification(state: Sample, playing: Boolean) {
        await("Notification must publish current track and transport state") {
            val notification = playbackNotification()
            notification != null && notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == state.title &&
                notification.actions?.any { it.title.toString() == if (playing) "Pause" else "Resume" } == true
        }
        val notification = checkNotNull(playbackNotification())
        assertTrue("Notification must expose real artist metadata", notification.extras.getCharSequence(Notification.EXTRA_TEXT)
            ?.contains(UiFixtureServer.ARTIST_NAME) == true)
        val platform = android.media.session.MediaController(context, notificationToken())
        assertEquals("Notification token belongs to this app", context.packageName, platform.packageName)
        await("Platform session metadata and playback state must agree with the service") {
            platform.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) == state.title &&
                platform.playbackState?.state == if (playing) android.media.session.PlaybackState.STATE_PLAYING
                else android.media.session.PlaybackState.STATE_PAUSED
        }
        if (playing) assertTrue("Playing notification must belong to a foreground service",
            notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
        assertEquals("Local output route", android.media.session.MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL,
            checkNotNull(platform.playbackInfo).playbackType)
        note("notificationMetadata=${state.title}; playing=$playing; platformToken=present; route=local")
    }
    private fun sendNotificationAction(title: String) {
        await("Posted notification action $title") { playbackNotification()?.actions?.any { it.title.toString() == title } == true }
        checkNotNull(playbackNotification()?.actions?.singleOrNull { it.title.toString() == title }?.actionIntent).send()
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(TIMEOUT_MS) {
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
    }
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (!condition()) {
            if (SystemClock.elapsedRealtime() >= deadline) throw AssertionError("Timed out: $label")
            SystemClock.sleep(50)
        }
    }
    private fun observeFor(milliseconds: Long, assertion: () -> Unit) {
        val deadline = SystemClock.elapsedRealtime() + milliseconds
        do { assertion(); SystemClock.sleep(50) } while (SystemClock.elapsedRealtime() < deadline)
    }
    private fun <T> onMain(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
    private fun shell(command: String): String {
        VirtualAvdOnly.requireVerified()
        return ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
    }
    private fun screenshot(name: String) {
        VirtualAvdOnly.requireVerified()
        // No Compose waitForIdle: background/system surfaces need not have a resumed root.
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Native screenshot unavailable" }
        try {
            File(evidence, "media-$name.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }
    private fun note(text: String) = File(evidence, "media-controls-info.txt").appendText("$text\n")
    private fun writeRunInfo() {
        val metrics = context.resources.displayMetrics
        File(evidence, "media-controls-info.txt").writeText(
            "suite=NativeMediaControlsTest\ndata=synthetic on-device loopback only\n" +
                "semantics=synthetic-single-output-ui-not-real-server-authority-or-planned-next-acceptance\n" +
                "device=${Build.MODEL}\nhardware=${Build.HARDWARE}\nro.kernel.qemu=1\napi=${Build.VERSION.SDK_INT}\n" +
                "viewport=${metrics.widthPixels}x${metrics.heightPixels}\ndensity=${metrics.density}\n" +
                "fontScale=${context.resources.configuration.fontScale}\n" +
                "apkRevision=${InstrumentationRegistry.getArguments().getString("apkRevision") ?: "not supplied"}\n" +
                "apkHashes=must be supplied/verified by isolated host runner\n" +
                "screenshotSource=UiAutomation.takeScreenshot; manual inspection required\n" +
                "delivery=input keyevent, AudioManager held-next, real MediaController seek, posted notification PendingIntent\n" +
                "limits=No acoustic recording/listening assertion; no physical headset/earbud firmware multi-click, Bluetooth AVRCP/absolute-volume, codec/route sound or OEM power-management proof.\n" +
                "limits=Screen-off is not proof of a secured lock screen; keyguard state recorded separately. Activity recreation is not process/service death. No auth-expiry or remote-owner injection in this fixture journey.\n" +
                "limits=Player gain asserted only if production controller publishes GET_VOLUME; an unavailable getter's default value is not evidence.\n",
        )
    }
}
