package dev.spofify.mobile.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.spofify.mobile.core.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the shipped client over loopback; no Activity, real account, or external service. */
@RunWith(AndroidJUnit4::class)
class PlaybackDeviceFixtureTest {
    companion object {
        @BeforeClass @JvmStatic fun virtualDeviceOnly() = VirtualAvdOnly.verify()
    }

    private suspend fun api(fixture: UiFixtureServer): PlaybackDevicesApi {
        val store = object : SessionStore {
            override fun read(): StoredSession? = null
            override fun write(value: StoredSession?) = Unit
        }
        val client = SpofifyClient(ServerAddress.parse(fixture.origin), store)
        client.login(UiFixtureServer.USERNAME, UiFixtureServer.PASSWORD)
        return PlaybackDevicesApi(client)
    }

    private fun snapshot() = PlaybackSnapshot(
        queue = listOf(8L to 1L, 9L to 0L).map { (key, order) ->
            PlaybackTrack("track-02", "Orbit Glass", listOf(PlaybackArtist(UiFixtureServer.ARTIST_ID, UiFixtureServer.ARTIST_NAME)),
                UiFixtureServer.ALBUM_NAME, "album-1", UiFixtureServer.AUDIO_DURATION_MS / 1000.0,
                entryId = "entry-$key", occurrenceId = "derived-occurrence-$key", key = key, order = order)
        }, position = 1, context = PlaybackContext("playlist", UiFixtureServer.MANUAL_ID, UiFixtureServer.MANUAL_NAME, 7),
        shuffle = true, repeat = "one", elapsed = 31.2, duration = UiFixtureServer.AUDIO_DURATION_MS / 1000.0,
        playing = true, occurrence = PlaybackOccurrence(9, "track-02", "synthetic-history-nonce", 28.5, false),
    )

    private fun PlaybackDirective.ack(status: String = "ok") = PlaybackAck(deliveryId, epoch, status)
    private suspend fun conflict(call: suspend () -> PlaybackDevicesView): PlaybackDevicesView {
        try { call(); fail("Expected a device conflict") }
        catch (error: ApiException) {
            assertEquals(409, error.status)
            return PlaybackDevicesView.fromJson(requireNotNull(error.responseBody))
        }
        error("Conflict was not returned")
    }
    private fun clean(fixture: UiFixtureServer) {
        assertEquals(emptyList<String>(), fixture.unexpectedRoutes.toList())
        assertEquals(0, fixture.authenticationFailures.get())
        assertEquals(0, fixture.emptyGlobalBrowseCalls.get())
        // Protocol exchanges alone must not open audio or manufacture listening history.
        assertEquals(0, fixture.audioRequests.get())
        assertEquals(0, fixture.historyWrites.get())
    }

    @Test fun firstPhoneOwnsIdleLeaseAndOnlyOwnerPollRenewsIt() = runBlocking {
        UiFixtureServer().use { fixture ->
            fixture.freezePlayerClock()
            val api = api(fixture)
            assertTrue(api.view().devices.isEmpty())
            val phone = api.register("Phone")
            assertEquals(phone.deviceId, phone.activeDeviceId)
            assertEquals(1L, phone.epoch)
            assertEquals(8000L, phone.leaseMs)
            assertEquals(0L, phone.quarantineMs)
            assertNull(phone.state)
            val speaker = api.register("Studio speaker")
            assertEquals(phone.deviceId, speaker.activeDeviceId)
            fixture.advancePlayerClockBy(1000)
            assertEquals(7000L, api.rename(phone.deviceId, "Phone 夜航").leaseMs)
            assertEquals(7000L, api.poll(speaker.deviceId, 1).leaseMs)
            val published = api.poll(phone.deviceId, 1, 4, snapshot())
            assertEquals(8000L, published.leaseMs)
            assertEquals(4L, published.stateSeq)
            assertEquals(snapshot(), published.state)
            fixture.advancePlayerClockBy(250)
            assertEquals(250L, api.view().stateAgeMs)
            val stale = conflict { api.poll(phone.deviceId, 1, 4, snapshot().paused()) }
            assertEquals(4L, stale.stateSeq)
            assertEquals(snapshot(), stale.state)
            assertEquals(7750L, stale.leaseMs)
            assertEquals("Phone 夜航", stale.devices.first().name)
            conflict { api.poll(speaker.deviceId, 1, 5, snapshot()) }
            clean(fixture)
        }
    }

    @Test fun handoffRequiresPrepareReleaseActivateAndPreservesPreparedOccurrences() = runBlocking {
        UiFixtureServer().use { fixture ->
            fixture.freezePlayerClock()
            val api = api(fixture)
            val phone = api.register("Phone").deviceId
            val speaker = api.register("Studio speaker").deviceId
            api.poll(phone, 1, 1, snapshot())
            val selection = api.transfer(phone, 1, "handoff", speaker, 1)
            assertEquals("preparing", selection.transfer?.phase)
            assertTrue(selection.directives.isEmpty())
            assertTrue(api.view().directives.isEmpty()) // GET must not consume deliveries.
            val prepare = api.poll(speaker, 1).directives.single()
            assertEquals("prepare", prepare.type)
            assertEquals(snapshot(), prepare.state)
            assertTrue(api.poll(speaker, 1).directives.isEmpty()) // delivery is once-only.
            val releasing = api.poll(speaker, 1, acks = listOf(prepare.ack()))
            assertEquals(phone, releasing.activeDeviceId)
            assertEquals("releasing", releasing.transfer?.phase)
            val release = api.poll(phone, 1).directives.single()
            assertEquals("release", release.type)
            val noState = conflict { api.poll(phone, 1, acks = listOf(release.ack())) }
            assertEquals("releasing", noState.transfer?.phase)
            val final = snapshot().copy(elapsed = 35.7)
            val released = api.poll(phone, 1, 2, final, listOf(release.ack()))
            assertEquals(2L, released.epoch)
            assertEquals(0L, released.stateSeq)
            assertEquals(speaker, released.activeDeviceId)
            assertEquals(0L, released.quarantineMs)
            assertEquals("activating", released.transfer?.phase)
            val activate = api.poll(speaker, 2).directives.single()
            assertEquals("activate", activate.type)
            assertEquals(final, activate.state)
            val completed = api.poll(speaker, 2, 1, final, listOf(activate.ack()))
            assertNull(completed.transfer)
            assertEquals("ok", completed.commands.single().status)
            assertEquals(listOf(8L, 9L), completed.state!!.queue.map { it.key })
            assertEquals(listOf(1L, 0L), completed.state!!.queue.map { it.order })
            assertEquals(final.occurrence, completed.state!!.occurrence)
            // Exact retained retry is only an outcome lookup, even with the old epoch/sequence.
            assertEquals("ok", api.transfer(phone, 1, "handoff", speaker, 1).commands.single().status)
            assertTrue(api.poll(speaker, 2).directives.isEmpty())
            conflict { api.poll(phone, 1, 3, snapshot()) }
            clean(fixture)
        }
    }

    @Test fun typedCommandsAreOnceOnlyAndPauseCanInterruptPendingExecution() = runBlocking {
        UiFixtureServer().use { fixture ->
            fixture.freezePlayerClock()
            val api = api(fixture)
            val phone = api.register("Phone").deviceId
            val remote = api.register("Controller").deviceId
            api.poll(phone, 1, 1, snapshot())
            val seek = PlaybackDeviceCommand.seek(42.5)
            api.command(remote, 1, "seek", seek)
            assertTrue(api.command(remote, 1, "seek", seek).directives.isEmpty())
            val delivery = api.poll(phone, 1).directives.single()
            assertEquals(seek.json, delivery.command)
            assertTrue(api.poll(phone, 1).directives.isEmpty())
            conflict { api.command(remote, 1, "next", PlaybackDeviceCommand.transport("next")) }
            api.command(remote, 1, "pause", PlaybackDeviceCommand.transport("pause"))
            val pause = api.poll(phone, 1).directives.single()
            assertEquals("pause", pause.command?.get("type")?.toString()?.trim('"'))
            val completed = api.poll(phone, 1, 2, snapshot().paused(), listOf(pause.ack(), delivery.ack()))
            assertEquals(mapOf("seek" to "cancelled", "pause" to "ok"), completed.commands.associate { it.commandId to it.status })
            conflict { api.command(remote, 1, "seek", PlaybackDeviceCommand.seek(9.0)) }
            clean(fixture)
        }
    }

    @Test fun rejectedPrepareOrReleaseKeepsHealthyOwnerAndBlockedActivationMatchesState() = runBlocking {
        UiFixtureServer().use { fixture ->
            fixture.freezePlayerClock()
            val api = api(fixture)
            val phone = api.register("Phone").deviceId
            val remote = api.register("Studio speaker").deviceId
            api.poll(phone, 1, 1, snapshot())
            api.transfer(phone, 1, "reject-prepare", remote, 1)
            val prepare = api.poll(remote, 1).directives.single()
            val rejected = api.poll(remote, 1, acks = listOf(prepare.ack("rejected")))
            assertEquals(phone, rejected.activeDeviceId)
            assertNull(rejected.transfer)
            assertEquals(snapshot(), rejected.state)
            api.transfer(phone, 1, "reject-release", remote, 2)
            val nextPrepare = api.poll(remote, 1).directives.single()
            api.poll(remote, 1, acks = listOf(nextPrepare.ack()))
            val release = api.poll(phone, 1).directives.single()
            val kept = api.poll(phone, 1, 2, snapshot(), listOf(release.ack("rejected")))
            assertEquals(phone, kept.activeDeviceId)
            assertEquals(1L, kept.epoch)
            assertEquals(0L, kept.quarantineMs)
            api.transfer(phone, 1, "blocked-start", remote, 3)
            val finalPrepare = api.poll(remote, 1).directives.single()
            api.poll(remote, 1, acks = listOf(finalPrepare.ack()))
            val finalRelease = api.poll(phone, 1).directives.single()
            api.poll(phone, 1, 3, snapshot(), listOf(finalRelease.ack()))
            val activate = api.poll(remote, 2).directives.single()
            conflict { api.poll(remote, 2, 1, snapshot(), listOf(activate.ack("blocked"))) }
            val blocked = snapshot().paused().copy(error = "blocked")
            val completed = api.poll(remote, 2, 1, blocked, listOf(activate.ack("blocked")))
            assertEquals(remote, completed.activeDeviceId)
            assertEquals("blocked", completed.commands.last().status)
            assertFalse(completed.state!!.playing)
            clean(fixture)
        }
    }

    @Test fun uncertainCommandDrainsLeaseAndRetainsOnlyLatestSelectionForPausedRecovery() = runBlocking {
        UiFixtureServer().use { fixture ->
            fixture.freezePlayerClock()
            val api = api(fixture)
            val phone = api.register("Phone").deviceId
            val remote = api.register("Studio speaker").deviceId
            api.poll(phone, 1, 1, snapshot())
            api.command(remote, 1, "uncertain", PlaybackDeviceCommand.transport("next"))
            api.poll(phone, 1) // execution delivered, ACK lost
            val waiting = api.transfer(phone, 1, "first-choice", remote, 1)
            assertEquals(2L, waiting.epoch)
            assertEquals("", waiting.activeDeviceId)
            assertEquals(8000L, waiting.quarantineMs)
            assertEquals("waiting", waiting.transfer?.phase)
            fixture.advancePlayerClockBy(4000)
            api.poll(phone, 2)
            api.poll(remote, 2)
            val latest = api.transfer(phone, 1, "latest-choice", phone, 2) // fresh intent may name an older epoch
            assertEquals(phone, latest.transfer?.targetDeviceId)
            conflict { api.transfer(phone, 2, "stale-choice", remote, 1) }
            fixture.advancePlayerClockBy(4000)
            val prepare = api.poll(phone, 2).directives.single()
            assertEquals("prepare", prepare.type)
            val activation = api.poll(phone, 2, acks = listOf(prepare.ack()))
            assertEquals(3L, activation.epoch)
            assertEquals(phone, activation.activeDeviceId)
            assertFalse(activation.state!!.playing)
            val activate = activation.directives.single()
            val recovered = api.poll(phone, 3, 1, activation.state, listOf(activate.ack()))
            assertNull(recovered.transfer)
            assertEquals("ok", recovered.commands.last().status)
            assertEquals("cancelled", recovered.commands.first { it.commandId == "first-choice" }.status)
            clean(fixture)
        }
    }

    @Test fun prepareTimeoutPreservesOwnerButUnacknowledgedExecutionFencesAndDrains() = runBlocking {
        UiFixtureServer().use { fixture ->
            fixture.freezePlayerClock()
            val api = api(fixture)
            val phone = api.register("Phone").deviceId
            val remote = api.register("Studio speaker").deviceId
            api.poll(phone, 1, 1, snapshot())
            api.transfer(phone, 1, "prepare-timeout", remote, 1)
            api.poll(remote, 1) // validation delivered, no ACK
            fixture.advancePlayerClockBy(4000)
            api.poll(phone, 1, 2, snapshot())
            api.poll(remote, 1)
            fixture.advancePlayerClockBy(4000)
            val kept = api.poll(phone, 1, 3, snapshot())
            assertEquals(phone, kept.activeDeviceId)
            assertEquals(1L, kept.epoch)
            assertEquals("timeout", kept.commands.single().status)
            assertEquals(0L, kept.quarantineMs)
            api.command(remote, 1, "execution-timeout", PlaybackDeviceCommand.transport("next"))
            val execution = api.poll(phone, 1).directives.single()
            fixture.advancePlayerClockBy(4000)
            api.poll(phone, 1, 4, snapshot())
            api.poll(remote, 1)
            fixture.advancePlayerClockBy(4000)
            val fenced = api.view()
            assertEquals(2L, fenced.epoch)
            assertEquals("", fenced.activeDeviceId)
            assertEquals(4000L, fenced.quarantineMs)
            assertEquals("timeout", fenced.commands.last().status)
            assertFalse(fenced.state!!.playing)
            val stale = conflict { api.poll(phone, 1, 5, snapshot(), listOf(execution.ack())) }
            assertTrue(stale.directives.isEmpty())
            assertEquals(0L, stale.stateSeq)
            assertTrue(api.poll(phone, 2).directives.isEmpty())
            fixture.advancePlayerClockBy(1001)
            // Remote's heartbeat is now too old; a fresh command ID cannot select it.
            conflict { api.transfer(phone, 2, "unavailable", remote, 2) }
            clean(fixture)
        }
    }

    @Test fun leaveReleasesImmediatelyButRegistrationDoesNotSilentlyReclaimOwnership() = runBlocking {
        UiFixtureServer().use { fixture ->
            fixture.freezePlayerClock()
            val api = api(fixture)
            val phone = api.register("Phone").deviceId
            api.poll(phone, 1, 1, snapshot())
            api.leave(phone)
            val left = api.view()
            assertTrue(left.devices.isEmpty())
            assertEquals("", left.activeDeviceId)
            assertEquals(2L, left.epoch)
            assertEquals(0L, left.quarantineMs)
            assertFalse(left.state!!.playing)
            val replacement = api.register("Phone")
            assertNotEquals(phone, replacement.deviceId)
            assertEquals("", replacement.activeDeviceId)
            api.transfer(replacement.deviceId, 2, "explicit-reconnect", replacement.deviceId, 1)
            val prepare = api.poll(replacement.deviceId, 2).directives.single()
            val activated = api.poll(replacement.deviceId, 2, acks = listOf(prepare.ack()))
            assertEquals(3L, activated.epoch)
            assertFalse(activated.state!!.playing)
            clean(fixture)
        }
    }

    @Test fun deviceReadsAndWritesRetainAuthenticationAndCsrfBoundary() {
        UiFixtureServer().use { fixture ->
            val http = OkHttpClient.Builder().followRedirects(false).retryOnConnectionFailure(false).build()
            try {
                val url = "${fixture.origin}/api/me/player"
                http.newCall(Request.Builder().url(url).build()).execute().use { assertEquals(401, it.code) }
                // Even a valid synthetic cookie cannot mutate without the existing CSRF header.
                http.newCall(Request.Builder().url(url)
                    .header("Cookie", "spofify_session=synthetic-ui-cookie")
                    .post("""{"action":"register","name":"Phone"}""".toRequestBody("application/json".toMediaType()))
                    .build()).execute().use { assertEquals(401, it.code) }
                assertEquals(2, fixture.authenticationFailures.get())
                assertTrue(fixture.playerCalls.isEmpty())
                assertTrue(fixture.unexpectedRoutes.isEmpty())
            } finally {
                http.connectionPool.evictAll()
                http.dispatcher.executorService.shutdown()
            }
        }
    }
}
