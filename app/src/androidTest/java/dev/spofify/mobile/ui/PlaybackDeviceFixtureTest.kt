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
import java.io.IOException

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
    private fun clean(fixture: UiFixtureServer, invalidPlayerRequests: Int = 0) {
        assertEquals(List(invalidPlayerRequests) { "invalid synthetic player request" }, fixture.unexpectedRoutes.toList())
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

    @Test fun guardedClaimPreparesOnceAndRequiresTerminalActivationAckBeforeCompletion() = runBlocking {
        UiFixtureServer().use { fixture ->
            fixture.freezePlayerClock()
            val api = api(fixture)
            val old = api.register("Old phone").deviceId
            api.poll(old, 1, 1, snapshot())
            api.leave(old)
            val phone = api.register("Phone").deviceId
            val before = api.view()
            assertEquals("", before.activeDeviceId)
            val claim = api.claimUnselected(phone, before.epoch, "first-play", 1)
            assertEquals(before.epoch, claim.epoch)
            assertEquals("", claim.activeDeviceId)
            assertEquals("preparing", claim.transfer?.phase)
            assertEquals("pending", claim.commands.single().status)
            assertTrue(claim.directives.isEmpty())
            assertEquals(claim, api.claimUnselected(phone, before.epoch, "first-play", 1))
            val prepare = api.poll(phone, before.epoch).directives.single()
            assertEquals("prepare", prepare.type)
            assertEquals(snapshot().paused(), prepare.state)
            assertTrue(api.claimUnselected(phone, before.epoch, "first-play", 1).directives.isEmpty())
            assertTrue(api.poll(phone, before.epoch).directives.isEmpty())
            val activating = api.poll(phone, before.epoch, acks = listOf(prepare.ack()))
            assertEquals(before.epoch + 1, activating.epoch)
            assertEquals(phone, activating.activeDeviceId)
            assertEquals("pending", activating.commands.single().status)
            assertEquals("activating", activating.transfer?.phase)
            val activate = activating.directives.single()
            assertEquals("activate", activate.type)
            assertEquals("first-play", activate.commandId)
            assertFalse(activate.state!!.playing)
            // Replacement Play stages empty/paused state, never old retained music.
            val blank = PlaybackSnapshot()
            val completed = api.poll(phone, activating.epoch, 1, blank, listOf(activate.ack()))
            assertNull(completed.transfer)
            assertEquals("ok", completed.commands.single().status)
            assertEquals(blank, completed.state)
            fixture.advancePlayerClockBy(1000)
            val retry = api.claimUnselected(phone, before.epoch, "first-play", 1)
            assertEquals(7000L, retry.leaseMs) // old-epoch lookup must not renew ownership
            assertEquals("ok", retry.commands.single().status)
            assertTrue(retry.directives.isEmpty())
            assertEquals(4, fixture.playerCalls.count { it == "transfer" }) // one admission, three explicit outcome lookups
            clean(fixture)
        }
    }

    @Test fun guardedDenialCannotReplaceSameEpochPreparationOrConsumeSelectionSequence() = runBlocking {
        UiFixtureServer().use { fixture ->
            fixture.freezePlayerClock(); fixture.beginPlayerStartupDrain()
            val api = api(fixture)
            val phone = api.register("Phone").deviceId
            val speaker = api.register("Speaker").deviceId
            fixture.advancePlayerClockBy(8000)
            api.poll(phone, 1); api.poll(speaker, 1)
            api.transfer(speaker, 1, "picker", speaker, 1)
            val before = api.view()
            assertEquals("", before.activeDeviceId)
            assertEquals("preparing", before.transfer?.phase)
            val denied = conflict { api.claimUnselected(phone, 1, "claim", 100) }
            assertEquals(before, denied.copy(deviceId = "", error = ""))
            val prepare = api.poll(speaker, 1).directives.single()
            assertEquals("picker", prepare.commandId)
            val rejected = api.poll(speaker, 1, acks = listOf(prepare.ack("rejected")))
            assertNull(rejected.transfer)
            // The denied high sequence was not consumed; a new explicit claim may use 1.
            val accepted = api.claimUnselected(phone, 1, "new-claim", 1)
            assertEquals("new-claim", accepted.transfer?.commandId)
            assertEquals(mapOf("picker" to "rejected", "new-claim" to "pending"), accepted.commands.associate { it.commandId to it.status })
            clean(fixture)
        }
    }

    @Test fun guardedOwnershipRaceAndExpiredOwnerNeverBecomeTakeoverOrHousekeeping() = runBlocking {
        UiFixtureServer().use { fixture ->
            fixture.freezePlayerClock(); fixture.beginPlayerStartupDrain()
            val api = api(fixture)
            val phone = api.register("Phone").deviceId
            val speaker = api.register("Speaker").deviceId
            fixture.advancePlayerClockBy(8000)
            api.poll(phone, 1); api.poll(speaker, 1)
            val emptyObservation = api.view()
            api.transfer(speaker, 1, "winner", speaker, 1)
            val prepare = api.poll(speaker, 1).directives.single()
            val activation = api.poll(speaker, 1, acks = listOf(prepare.ack()))
            api.poll(speaker, 2, 1, PlaybackSnapshot(), listOf(activation.directives.single().ack()))
            conflict { api.claimUnselected(phone, emptyObservation.epoch, "stale-claim", 100) }
            fixture.advancePlayerClockBy(6000) // selected, but unavailable, is still selected
            val unavailable = conflict { api.claimUnselected(phone, 2, "unavailable-owner", 100) }
            assertEquals(speaker, unavailable.activeDeviceId)
            assertFalse(unavailable.devices.single { it.deviceId == speaker }.available)
            fixture.advancePlayerClockBy(2001) // guard rejects BEFORE ordinary lease expiry pruning
            val expired = conflict { api.claimUnselected(phone, 2, "expired-owner", 100) }
            assertEquals(speaker, expired.activeDeviceId)
            assertEquals(2L, expired.epoch)
            assertEquals(0L, expired.leaseMs)
            assertEquals(0L, expired.quarantineMs)
            assertEquals(listOf("winner"), expired.commands.map { it.commandId })
            // Ordinary picker takeover remains unchanged, accepts old epoch and lower sequence.
            val picker = api.transfer(phone, 1, "explicit-choice", phone, 1)
            assertEquals("explicit-choice", picker.transfer?.commandId)
            clean(fixture)
        }
    }

    @Test fun startupQuarantineDenialDoesNotConsumeIntentAndOnlyExpiredDeadlineAdmitsClaim() = runBlocking {
        UiFixtureServer().use { fixture ->
            fixture.freezePlayerClock(); fixture.beginPlayerStartupDrain()
            val api = api(fixture)
            val phone = api.register("Phone").deviceId
            assertEquals(8000L, conflict { api.claimUnselected(phone, 1, "startup", 100) }.quarantineMs)
            fixture.advancePlayerClockBy(7999)
            assertEquals(1L, conflict { api.claimUnselected(phone, 1, "last-millisecond", 100) }.quarantineMs)
            fixture.advancePlayerClockBy(1)
            val idle = api.poll(phone, 1)
            assertEquals("", idle.activeDeviceId)
            assertTrue(idle.commands.isEmpty())
            assertNull(idle.transfer)
            val accepted = api.claimUnselected(phone, 1, "explicit-after-drain", 1)
            assertEquals("preparing", accepted.transfer?.phase)
            assertEquals(0L, accepted.quarantineMs)
            clean(fixture)
        }
    }

    @Test fun guardParticipatesInFingerprintAndExactRetriesNeverDeliverAgain() = runBlocking {
        for (originalGuarded in listOf(false, true)) UiFixtureServer().use { fixture ->
            fixture.freezePlayerClock(); fixture.beginPlayerStartupDrain()
            val api = api(fixture)
            val phone = api.register("Phone").deviceId
            fixture.advancePlayerClockBy(8000); api.poll(phone, 1)
            if (originalGuarded) api.claimUnselected(phone, 1, "same-id", 1)
            else api.transfer(phone, 1, "same-id", phone, 1)
            val before = api.view()
            val mismatch = conflict {
                if (originalGuarded) api.transfer(phone, 1, "same-id", phone, 1)
                else api.claimUnselected(phone, 1, "same-id", 1)
            }
            assertEquals(before, mismatch.copy(deviceId = "", error = ""))
            conflict { api.claimUnselected(phone, 1, "same-id", 2) }
            val prepare = api.poll(phone, 1).directives.single()
            assertEquals("same-id", prepare.commandId)
            if (originalGuarded) assertTrue(api.claimUnselected(phone, 1, "same-id", 1).directives.isEmpty())
            else assertTrue(api.transfer(phone, 1, "same-id", phone, 1).directives.isEmpty())
            assertTrue(api.poll(phone, 1).directives.isEmpty())
            clean(fixture)
        }
    }

    @Test fun lostGuardedActivationAckReplyIsUncertainAndNotAutomaticallyReplayed() = runBlocking {
        UiFixtureServer().use { fixture ->
            fixture.freezePlayerClock(); fixture.beginPlayerStartupDrain()
            val api = api(fixture)
            val phone = api.register("Phone").deviceId
            fixture.advancePlayerClockBy(8000); api.poll(phone, 1)
            api.claimUnselected(phone, 1, "claim", 1)
            val prepare = api.poll(phone, 1).directives.single()
            val activation = api.poll(phone, 1, acks = listOf(prepare.ack()))
            val activate = activation.directives.single()
            fixture.loseNextPlayerAckResponse()
            val pollsBefore = fixture.playerCalls.count { it == "poll" }
            try { api.poll(phone, 2, 1, PlaybackSnapshot(), listOf(activate.ack())); fail("Lost ACK reply reported success") }
            catch (_: IOException) { }
            assertEquals(pollsBefore + 1, fixture.playerCalls.count { it == "poll" })
            assertEquals(1, fixture.playerCalls.count { it == "transfer" })
            // Read-only reconciliation observes success but is not authorization to replay Play.
            val observed = api.view()
            assertNull(observed.transfer)
            assertEquals("ok", observed.commands.single().status)
            assertEquals(PlaybackSnapshot(), observed.state)
            assertTrue(api.poll(phone, 2).directives.isEmpty())
            clean(fixture)
        }
    }

    @Test fun unacknowledgedGuardedActivationExpiresWithoutRedeliveryOrAutomaticReclaim() = runBlocking {
        UiFixtureServer().use { fixture ->
            fixture.freezePlayerClock(); fixture.beginPlayerStartupDrain()
            val api = api(fixture)
            val phone = api.register("Phone").deviceId
            fixture.advancePlayerClockBy(8000); api.poll(phone, 1)
            api.claimUnselected(phone, 1, "claim", 1)
            val prepare = api.poll(phone, 1).directives.single()
            val activation = api.poll(phone, 1, acks = listOf(prepare.ack()))
            assertEquals("activate", activation.directives.single().type)
            fixture.advancePlayerClockBy(4000)
            assertTrue(api.poll(phone, 2).directives.isEmpty()) // missing ACK is not redelivered
            fixture.advancePlayerClockBy(4000)
            val expired = api.view()
            assertEquals("", expired.activeDeviceId)
            assertEquals(3L, expired.epoch)
            assertEquals(4000L, expired.quarantineMs)
            assertEquals("timeout", expired.commands.single().status)
            assertNull(expired.transfer)
            assertTrue(expired.directives.isEmpty())
            conflict { api.claimUnselected(phone, 3, "no-reclaim-during-drain", 2) }
            assertEquals(2, fixture.playerCalls.count { it == "transfer" })
            clean(fixture)
        }
    }

    @Test fun guardedEnvelopeAcceptsOnlyLiteralTrueOnTransferAndRejectsAmbiguity() = runBlocking {
        UiFixtureServer().use { fixture ->
            fixture.freezePlayerClock(); fixture.beginPlayerStartupDrain()
            val api = api(fixture)
            val phone = api.register("Phone").deviceId
            val base = """{"action":"transfer","deviceId":"$phone","epoch":1,"commandId":"claim","targetDeviceId":"$phone","selectionSeq":1,"requireUnselected":true}"""
            val malformed = listOf("false", "null", "0", "1", "\"true\"", "[]", "{}").map {
                base.replace("\"requireUnselected\":true", "\"requireUnselected\":$it")
            } + listOf(
                base.replace("\"requireUnselected\":true", "\"requireUnselected\":true,\"requireUnselected\":true"),
                base.replace("requireUnselected", "RequireUnselected"),
                base.replace("\"selectionSeq\":1", "\"selectionSeq\":null"),
                """{"action":"register","name":"Other","requireUnselected":true}""",
                """{"action":"poll","deviceId":"$phone","epoch":1,"requireUnselected":true}""",
                """{"action":"leave","deviceId":"$phone","requireUnselected":true}""",
                """{"action":"rename","deviceId":"$phone","name":"Other","requireUnselected":true}""",
                """{"action":"command","deviceId":"$phone","epoch":1,"commandId":"claim","command":{"type":"resume"},"requireUnselected":true}""",
            )
            val http = OkHttpClient.Builder().followRedirects(false).retryOnConnectionFailure(false).build()
            try {
                fun send(body: String, expected: Int) {
                    http.newCall(Request.Builder().url("${fixture.origin}/api/me/player")
                        .header("Cookie", "spofify_session=synthetic-ui-cookie")
                        .header("X-CSRF-Token", "synthetic-ui-csrf").header("Origin", fixture.origin)
                        .post(body.toRequestBody("application/json".toMediaType())).build()).execute().use { assertEquals(expected, it.code) }
                }
                for (body in malformed) send(body, 400)
                // A well-shaped guard still must target the exact issuer, not another ID.
                send(base.replace("\"targetDeviceId\":\"$phone\"", "\"targetDeviceId\":\"other-output\""), 409)
            } finally { http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
            val unchanged = api.view()
            assertEquals(listOf(phone), unchanged.devices.map { it.deviceId })
            assertEquals("Phone", unchanged.devices.single().name)
            assertTrue(unchanged.commands.isEmpty())
            assertNull(unchanged.transfer)
            assertEquals(8000L, unchanged.quarantineMs)
            clean(fixture, malformed.size)
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
