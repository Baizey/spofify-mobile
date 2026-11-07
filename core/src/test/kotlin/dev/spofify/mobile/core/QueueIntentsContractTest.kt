package dev.spofify.mobile.core

import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class QueueIntentsContractTest {
    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream("/queue-intents-v1/$name")).use { it.readBytes() }
    private val corpus: JsonObject get() = Json.parseToJsonElement(resource("queue-intents-v1.json").toString(Charsets.UTF_8)).jsonObject
    private fun rows(name: String) = corpus.getValue(name).jsonArray.map { it.jsonObject }
    private fun raw(row: JsonObject) = row.getValue("raw").jsonPrimitive.content
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Incompatible player JSON was accepted") } catch (_: IllegalArgumentException) { }
    }
    private fun state(name: String) = rows("states").single { it.getValue("name").jsonPrimitive.content == name }.let {
        PlaybackSnapshot.fromJson(it.getValue("state").jsonObject) to QueueIntentState.fromJson(it.getValue("metadata").jsonObject)
    }
    private fun result(name: String) = QueueIntentResult.fromJson(rows("results").single { it.getValue("name").jsonPrimitive.content == name }.getValue("result").jsonObject)
    private fun response(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun view(capable: Boolean = true, state: PlaybackSnapshot? = null, metadata: QueueIntentState? = null): PlaybackDevicesView = PlaybackDevicesView(
        deviceId = "pc", devices = listOf(PlaybackDevice("pc", "PC", true)), activeDeviceId = "pc", epoch = 7, leaseMs = 8000,
        state = state, stateSeq = if (state == null) 0 else 1, stateAgeMs = 0, transfer = null, directives = emptyList(), commands = emptyList(),
        queueIntents = if (capable) QueueIntentsView(1, listOf(QueueIntentReceiver("pc", 1)), metadata?.let {
            QueueIntentBinding("pc", it.receiverId, it.revision, state?.let { s -> s.queue.getOrNull(s.position)?.key }, state?.occurrence?.eventId)
        }, metadata, emptyList(), emptyList()) else null,
    ).validated()

    @Test fun frozenRawBytesRemainPinned() {
        val expected = mapOf("queue-intents-v1.json" to "621f5e5eea8632ea732c7369ae03bc442db53ec82e3d0edd7a9052a24547f44b",
            "queue-intents-pause-receipt-v1.json" to "dd614300d5f33e4ac06103fea5f118420e7bd92a4bcb130b3770509a5d07adfc")
        expected.forEach { (name, digest) -> assertEquals(name, digest, MessageDigest.getInstance("SHA-256").digest(resource(name)).joinToString("") { "%02x".format(it) }) }
    }

    @Test fun everyFrozenRequestIncludingRawDuplicateAndCaseNegativesHitsProductionDecoder() {
        rows("requests").forEach { row ->
            val accepted = try { PlaybackRequestContract.fromRawJson(raw(row)); true } catch (_: IllegalArgumentException) { false }
            assertEquals(row.getValue("name").jsonPrimitive.content, row.getValue("valid").jsonPrimitive.boolean, accepted)
        }
    }

    @Test fun guardedTransferShapeDoesNotGrantExactSelfAuthorityAndStillRejectsRawAmbiguity() {
        val raw = """{"action":"transfer","deviceId":"phone","epoch":7,"commandId":"claim","targetDeviceId":"other-output","selectionSeq":1,"requireUnselected":true}"""
        val shaped = PlaybackRequestContract.fromRawJson(raw)
        // This must reach broker admission (409), not become a malformed-body 400.
        assertEquals(JsonPrimitive("other-output"), shaped["targetDeviceId"])
        assertNotEquals(shaped["deviceId"], shaped["targetDeviceId"])
        listOf("false", "null", "0", "1", "\"true\"", "[]", "{}").forEach { value ->
            rejects { PlaybackRequestContract.fromRawJson(raw.replace("\"requireUnselected\":true", "\"requireUnselected\":$value")) }
        }
        rejects { PlaybackRequestContract.fromRawJson(raw.replace("\"requireUnselected\":true", "\"requireUnselected\":true,\"requireUnselected\":true")) }
        rejects { PlaybackRequestContract.fromRawJson(raw.replace("\"requireUnselected\":true", "\"requireUnselected\":true,\"RequireUnselected\":true")) }
        rejects { PlaybackRequestContract.fromRawJson(raw.replace("requireUnselected", "RequireUnselected")) }
    }

    @Test fun everyFrozenLegacyWholeViewIncluding409RemainsStrictRecursively() {
        rows("legacyViews").forEach { row ->
            val decoded = PlaybackDevicesView.fromRawJson(raw(row))
            assertNull(decoded.queueIntents)
            assertFalse(decoded.toJson().containsKey("queueIntents"))
            rejects { PlaybackDevicesView.fromRawJson(raw(row).replaceFirst("{", "{\"unknown\":0,")) }
        }
        val populated = raw(rows("legacyViews").single { it.getValue("name").jsonPrimitive.content == "populated" })
        rejects { PlaybackDevicesView.fromRawJson(populated.replace("\"version\":1", "\"version\":1,\"Version\":1")) }
        rejects { PlaybackDevicesView.fromRawJson(populated.replace("\"queue\":[{", "\"queue\":[{\"url\":\"https://invalid\",")) }
    }

    @Test fun frozenStatesAndAllTerminalResultAxesDecodeWithoutKotlinOnlyGoldenValues() {
        rows("states").forEach {
            val s = PlaybackSnapshot.fromJson(it.getValue("state").jsonObject)
            val m = QueueIntentState.fromJson(it.getValue("metadata").jsonObject, s)
            assertEquals(m, QueueIntentState.fromJson(m.toJson(), s))
        }
        rows("results").forEach {
            val r = QueueIntentResult.fromJson(it.getValue("result").jsonObject)
            assertEquals(r, QueueIntentResult.fromJson(r.toJson()))
        }
        val (s, m) = state("confirmed-P1-final")
        result("corrected-P1-queued-B-keeps-paused-A").validated(s, m, "ok")
        assertFalse(s.playing); assertEquals(42.0, s.elapsed, 0.0); assertEquals(101L, s.queue[s.position].key)
        assertEquals(listOf(102L), m.highKeys)
    }

    @Test fun sharedSemanticFinalOrdersAndUpcomingMembershipAreAdmissibleTogether() {
        // Consume the SAME semantic records as receiver ordering tests; no second core
        // implementation of placement is used as an independent expected-order oracle.
        rows("semantics").forEach { row ->
            val labels = row.getValue("expectedQueue").jsonArray.map { it.jsonPrimitive.content }
            val current = row.getValue("expectedCurrent").jsonPrimitive.content
            val queue = labels.mapIndexed { i, label -> PlaybackTrack(label, label, emptyList(), "", "", 120.0, key = i + 1L, order = i + 1L) }
            val s = PlaybackSnapshot(queue = queue, position = labels.indexOf(current))
            val keys = labels.withIndex().associate { it.value to it.index + 1L }
            val m = QueueIntentState("receiver", 1, "tracked", queue.map { QueueIntentOrigin(it.key, "legacy") }, row.getValue("expectedHigh").jsonArray.map { keys.getValue(it.jsonPrimitive.content) })
            m.validated(s)
            PlaybackPublication.validatePoll("pc", 7, 1, s, emptyList(), m)
        }
    }

    @Test fun commandConstructionRoundTripsAllSharedSelectionUnionsAndProtectsSignedAuthority() {
        rows("requests").filter { it.getValue("valid").jsonPrimitive.boolean }.forEach { row ->
            val request = PlaybackRequestContract.fromRawJson(raw(row))
            if (request["command"] == null) return@forEach
            val command = PlaybackDeviceCommand.fromJson(request.getValue("command").jsonObject)
            val rebuilt = PlaybackDeviceCommand.queueIntent(requireNotNull(command.intent), requireNotNull(command.expected), requireNotNull(command.selection))
            val decoded = PlaybackDeviceCommand.fromRawJson(rebuilt.json.toString())
            assertEquals(command.intent, decoded.intent)
            assertEquals(command.expected, decoded.expected)
            assertEquals(command.selection, decoded.selection)
            assertFalse(command.toString().contains("synthetic_signature"))
            assertFalse(command.selection.toString().contains("synthetic_signature"))
        }
    }

    @Test fun invalidStateAlignmentBindingVersionAndRetainedStatusesFailAtomically() {
        val (s, m) = state("confirmed-P1-final")
        rejects { m.copy(origins = m.origins.reversed()).validated(s) }
        rejects { m.copy(highKeys = listOf(101)).validated(s) }
        rejects { m.copy(highKeys = listOf(102, 102)).validated(s) }
        rejects { m.copy(highKeys = listOf(106, 102)).validated(s) }
        val good = view(state = s, metadata = m)
        rejects { good.copy(queueIntents = good.queueIntents!!.copy(version = 2)).validated() }
        rejects { good.copy(queueIntents = good.queueIntents!!.copy(binding = good.queueIntents.binding!!.copy(currentEventId = "another"))).validated() }
        rejects { good.copy(queueIntents = good.queueIntents!!.copy(binding = good.queueIntents.binding!!.copy(revision = m.revision + 1))).validated() }
        val retained = result("corrected-P1-queued-B-keeps-paused-A")
        rejects { good.copy(commands = listOf(PlaybackOutcome("cmd", "pending")), queueIntents = good.queueIntents!!.copy(results = listOf(QueueIntentCommandResult("cmd", retained)))).validated() }
        rejects { PlaybackDevicesView.fromRawJson(good.toJson().toString()) }
        assertEquals(good, PlaybackDevicesView.fromRawJson(good.toJson().toString(), true))
    }

    @Test fun handoffCoverageIsRequiredOnlyForRegisteredCapableRecipientAndNotForCommands() {
        val (snapshot, metadata) = state("confirmed-P1-final")
        val capable = view(state = snapshot, metadata = metadata)
        for (type in listOf("prepare", "activate")) {
            val directive = PlaybackDirective("delivery", 7, type, "handoff", state = snapshot)
            val extension = capable.queueIntents!!.copy(binding = null)
            val incomplete = capable.copy(directives = listOf(directive), queueIntents = extension)
            rejects { incomplete.validated() }
            val explicitLegacy = incomplete.copy(queueIntents = extension.copy(directives = listOf(QueueIntentDirective("delivery", null))))
            assertEquals(snapshot, explicitLegacy.validated().directives.single().state)
            assertNull(explicitLegacy.queueIntents!!.directives.single().state)
            // Negotiated controllers/legacy registrations and fully unopted v1 retain
            // their old handoff shapes. Capability belongs to the recipient, not origin.
            incomplete.copy(queueIntents = extension.copy(receivers = emptyList())).validated()
            incomplete.copy(queueIntents = null).validated()
        }
        val command = PlaybackDirective("ordinary", 7, "command", "pause", PlaybackDeviceCommand.transport("pause").json)
        capable.copy(directives = listOf(command)).validated()
        rejects { capable.copy(directives = listOf(command), queueIntents = capable.queueIntents!!.copy(directives = listOf(QueueIntentDirective("ordinary", null)))).validated() }
    }

    @Test fun rawHttpCapablePrepareDirectActivateAndFirstPlayCannotStageWithMissingSidecar() = runBlocking {
        val (retained, retainedMetadata) = state("confirmed-P1-final")
        val (empty, emptyMetadata) = state("empty-owner")
        // A direct activate has no observed prepare. First-Play may stage blank only
        // AFTER validating the raw retained handoff, never erase it before decoding.
        val cases = listOf("prepare", "direct-activate", "first-play-prepare", "first-play-activate", "blank-first-play-activate")
        val web = MockWebServer(); web.start()
        try {
            val api = PlaybackDevicesApi(SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), MemorySessionStore()))
            web.enqueue(response(view().toJson().toString())); web.enqueue(response(view().toJson().toString()))
            api.register("PC", 1); web.takeRequest(); web.takeRequest()
            cases.forEach { case ->
                val type = if (case.endsWith("prepare")) "prepare" else "activate"
                val source = if (case.startsWith("blank")) empty else retained
                val sourceMetadata = if (case.startsWith("blank")) emptyMetadata else retainedMetadata
                for (sidecarMetadata in listOf(sourceMetadata, null)) {
                    val directive = PlaybackDirective("delivery", 7, type, "handoff", state = source)
                    val complete = view(state = source, metadata = sourceMetadata).let { base ->
                        base.copy(transfer = PlaybackTransfer("handoff", "", "pc", if (type == "prepare") "preparing" else "activating"),
                            directives = listOf(directive), queueIntents = base.queueIntents!!.copy(binding = null,
                                directives = listOf(QueueIntentDirective("delivery", sidecarMetadata))))
                    }
                    val positive = complete.toJson()
                    val missing = JsonObject(positive + ("queueIntents" to JsonObject(positive.getValue("queueIntents").jsonObject +
                        ("directives" to JsonArray(emptyList())))))
                    var staged = 0
                    web.enqueue(response(missing.toString()))
                    try {
                        api.poll("pc", 7)
                        staged++ // Represents the earliest consumer/blank-staging boundary.
                        fail("Missing $case sidecar reached handoff staging")
                    } catch (_: IllegalArgumentException) { }
                    assertEquals(case, 0, staged)
                    assertEquals("queue-intents-v1", web.takeRequest().getHeader("X-Spofify-Playback-Extensions"))
                    web.enqueue(response(positive.toString()))
                    val accepted = api.poll("pc", 7)
                    staged++
                    web.takeRequest()
                    assertEquals(case, 1, staged)
                    assertEquals(case, source, accepted.directives.single().state)
                    assertEquals(case, sidecarMetadata, accepted.queueIntents!!.directives.single().state)
                    // Explicit null is valid legacy provenance: all music/history is
                    // preserved through decoding, before any caller chooses blank staging.
                }
            }
        } finally { web.shutdown() }
    }

    @Test fun resultValidationRejectsCountRevisionStatusAndPlaybackLies() {
        val (s, m) = state("confirmed-P1-final")
        val queued = result("corrected-P1-queued-B-keeps-paused-A")
        assertEquals(1001, queued.copy(acceptedCount = 1001).validated().acceptedCount)
        assertEquals(5000, queued.copy(acceptedCount = 5000).validated().acceptedCount)
        rejects { queued.copy(acceptedCount = -1).validated() }
        rejects { queued.copy(commitRevision = 9007199254740992).validated() }
        rejects { queued.copy(playbackKey = 101).validated() }
        rejects { queued.copy(commitRevision = 31).validated(s, m, "ok") }
        rejects { queued.validated(s, m, "blocked") }
        rejects { queued.copy(outcome = "playing", playback = "started", playbackKey = 101).validated(s, m, "ok") }
        rejects { result("empty-selection-blocked-start").validated(s, m, "blocked") }
        rejects { queued.copy(outcome = "no_op", acceptedCount = 0).validated() }
    }

    @Test fun actualPlayerPauseOnlyReceiptIsJointlyValidatedAndExactCurrentQueueOnlyCannotBorrowIt() {
        val fixture = Json.parseToJsonElement(resource("queue-intents-pause-receipt-v1.json").toString(Charsets.UTF_8)).jsonObject
        val initial = PlaybackRequestContract.fromRawJson(fixture.getValue("initialPoll").toString())
        val terminal = PlaybackRequestContract.fromRawJson(fixture.getValue("terminalPoll").toString())
        val command = PlaybackDeviceCommand.fromJson(fixture.getValue("commandRequest").jsonObject.getValue("command").jsonObject)
        val snapshot = PlaybackSnapshot.fromJson(terminal.getValue("state").jsonObject)
        val metadata = QueueIntentState.fromJson(terminal.getValue("queueIntentState").jsonObject)
        val ack = terminal.getValue("acks").jsonArray.single().jsonObject
        val receipt = QueueIntentResult.fromJson(ack.getValue("queueIntentResult").jsonObject)
        QueueIntentReceipt.validate(command, receipt, "ok", snapshot, metadata,
            PlaybackSnapshot.fromJson(initial.getValue("state").jsonObject), QueueIntentState.fromJson(initial.getValue("queueIntentState").jsonObject))
        PlaybackPublication.validatePoll("pc", 7, 2, snapshot, listOf(PlaybackAck(ack.getValue("deliveryId").jsonPrimitive.content, 7, "ok", receipt)), metadata)
        val existing = PlaybackDeviceCommand.queueIntent("high", command.expected!!.copy(currentKey = 100, currentEventId = "history_old"), command.selection!!)
        rejects { QueueIntentReceipt.validate(existing, receipt, "ok", snapshot, metadata,
            PlaybackSnapshot.fromJson(initial.getValue("state").jsonObject), QueueIntentState.fromJson(initial.getValue("queueIntentState").jsonObject)) }
    }

    @Test fun jointPublicationIncludesEveryMetadataAckAndEnvelopeByteBeforeMutation() {
        val (s, m) = state("confirmed-P1-final")
        val r = result("corrected-P1-queued-B-keeps-paused-A")
        val body = PlaybackPublication.pollBody("pc", 7, 1, s, listOf(PlaybackAck("delivery", 7, "ok", r)), m)
        assertEquals(m.toJson(), body["queueIntentState"])
        assertEquals(r.toJson(), body.getValue("acks").jsonArray.single().jsonObject["queueIntentResult"])
        val expensive = s.queue.first().copy(name = "\\".repeat(512), albumName = "\\".repeat(512), artists = List(16) { PlaybackArtist("", "\\".repeat(512)) })
        val huge = s.copy(queue = List(1000) { expensive.copy(key = it + 1L, order = it + 1L) }, occurrence = null)
        val hugeMetadata = m.copy(origins = huge.queue.map { QueueIntentOrigin(it.key, "low") }, highKeys = emptyList())
        rejects { PlaybackPublication.validatePoll("pc", 7, 1, huge, emptyList(), hugeMetadata) }
        rejects { PlaybackPublication.validatePoll("pc", 7, 1, s, listOf(PlaybackAck("delivery", 7, "ok", r)), null) }
    }

    @Test fun boundedInboundViewAboveRequestLimitReachesReceiverButCannotBePublished() = runBlocking {
        val track = PlaybackTrack("X", "x".repeat(512), List(2) { PlaybackArtist("", "x".repeat(512)) },
            "x".repeat(512), "", 120.0, key = 1, order = 1)
        val snapshot = PlaybackSnapshot(queue = List(1000) { track.copy(key = it + 1L, order = it + 1L) }, position = 0)
        val raw = view(capable = false, state = snapshot).toJson().toString()
        val bytes = raw.toByteArray(Charsets.UTF_8).size
        assertTrue(bytes > MAX_PLAYBACK_BODY_BYTES)
        assertTrue(bytes < 16 * 1024 * 1024)
        val web = MockWebServer(); web.start()
        try {
            val api = PlaybackDevicesApi(SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), MemorySessionStore()))
            web.enqueue(response(raw))
            assertEquals(snapshot, api.view().state)
            assertEquals("GET", web.takeRequest().method)
            // The receiver, not the read transport, owns publication-safe import admission.
            rejects { snapshot.publicationValidated() }
            val outbound = buildJsonObject {
                put("action", "poll"); put("deviceId", "pc"); put("epoch", 7); put("stateSeq", 1)
                put("state", snapshot.toJson()); put("acks", JsonArray(emptyList()))
            }.toString()
            rejects { PlaybackRequestContract.fromRawJson(outbound) }
            try { api.poll("pc", 7, 1, snapshot); fail("Oversized state reached outbound transport") }
            catch (_: IllegalArgumentException) { }
            assertEquals(1, web.requestCount)
            // A larger success budget never expands safe-conflict retention's inherited 2MiB cap.
            web.enqueue(response(raw, 409))
            try { api.command("pc", 7, "conflict", PlaybackDeviceCommand.transport("pause")); fail() }
            catch (error: ApiException) { assertNull(error.responseBody) }
            assertEquals(2, web.requestCount)
        } finally { web.shutdown() }
    }

    @Test fun musicalReserveAloneCannotAdmitExtendedPublicationNearLimit() {
        val seed = PlaybackTrack("X", "x".repeat(512), listOf(PlaybackArtist("", "x".repeat(512)), PlaybackArtist("", "")), "x".repeat(512), "", 120.0, key = 1, order = 1)
        fun candidate(length: Int) = PlaybackSnapshot(queue = List(1000) { i -> seed.copy(key = i + 1L, order = i + 1L,
            artists = listOf(seed.artists[0], PlaybackArtist("", "x".repeat(length)))) }, position = 0)
        val target = MAX_PLAYBACK_BODY_BYTES - PLAYBACK_PUBLICATION_RESERVE_BYTES - 512
        var low = 0; var high = 512
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (candidate(mid).toJson().toString().toByteArray(Charsets.UTF_8).size <= target) low = mid else high = mid - 1
        }
        val snapshot = candidate(low).publicationValidated()
        val metadata = QueueIntentState("receiver", 1, "tracked", snapshot.queue.map { QueueIntentOrigin(it.key, "legacy") }, emptyList())
        rejects { PlaybackPublication.validatePoll("pc", 7, 1, snapshot, emptyList(), metadata) }
    }

    @Test fun exactReadOnlyProbePrecedesExtendedRegisterAndJointPollAndAllRequestsKeepOptIn() = runBlocking {
        val web = MockWebServer(); web.start()
        try {
            val api = PlaybackDevicesApi(SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), MemorySessionStore()))
            web.enqueue(response(view().toJson().toString()))
            web.enqueue(response(view().toJson().toString()))
            api.register("PC", 1)
            val probe = web.takeRequest(); assertEquals("GET", probe.method); assertEquals("/api/me/player", probe.path)
            assertEquals("queue-intents-v1", probe.getHeader("X-Spofify-Playback-Extensions")); assertEquals(0L, probe.bodySize)
            val register = web.takeRequest(); assertEquals("POST", register.method)
            assertEquals("queue-intents-v1", register.getHeader("X-Spofify-Playback-Extensions"))
            assertEquals(JsonPrimitive(1), Json.parseToJsonElement(register.body.readUtf8()).jsonObject["queueIntentsVersion"])
            val (s, m) = state("confirmed-P1-final")
            web.enqueue(response(view(state = s, metadata = m).toJson().toString()))
            api.poll("pc", 7, 1, s, listOf(PlaybackAck("delivery", 7, "ok", result("corrected-P1-queued-B-keeps-paused-A"))), m)
            val poll = web.takeRequest(); assertEquals("queue-intents-v1", poll.getHeader("X-Spofify-Playback-Extensions"))
            assertEquals(m.toJson(), Json.parseToJsonElement(poll.body.readUtf8()).jsonObject["queueIntentState"])
            assertEquals(3, web.requestCount)
        } finally { web.shutdown() }
    }

    @Test fun oldServerAbsentSidecarDisablesBeforeAnyExtendedMutationAndControllerDoesNotAdvertise() = runBlocking {
        val web = MockWebServer(); web.start()
        try {
            val api = PlaybackDevicesApi(SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), MemorySessionStore()))
            web.enqueue(response(view(false).toJson().toString()))
            try { api.register("PC", 1); fail("Unsupported server accepted capability") } catch (_: IllegalStateException) { }
            assertEquals("GET", web.takeRequest().method); assertEquals(1, web.requestCount)
            web.enqueue(response(view(false).toJson().toString())); api.register("PC")
            val register = web.takeRequest(); assertNull(register.getHeader("X-Spofify-Playback-Extensions"))
            assertFalse(Json.parseToJsonElement(register.body.readUtf8()).jsonObject.containsKey("queueIntentsVersion"))
            assertEquals(2, web.requestCount)
        } finally { web.shutdown() }
    }

    @Test fun controllerNegotiatesWithoutAdvertisingAndSendsExactlyOneIntentCommand() = runBlocking {
        val web = MockWebServer(); web.start()
        try {
            val api = PlaybackDevicesApi(SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), MemorySessionStore()))
            web.enqueue(response(view().toJson().toString())); api.probeQueueIntents(); web.takeRequest()
            web.enqueue(response(view().toJson().toString())); api.register("PC")
            val registered = web.takeRequest()
            assertEquals("queue-intents-v1", registered.getHeader("X-Spofify-Playback-Extensions"))
            assertFalse(Json.parseToJsonElement(registered.body.readUtf8()).jsonObject.containsKey("queueIntentsVersion"))
            val request = rows("requests").single { it.getValue("name").jsonPrimitive.content == "low-row" }
            val command = PlaybackDeviceCommand.fromJson(PlaybackRequestContract.fromRawJson(raw(request)).getValue("command").jsonObject)
            web.enqueue(response(view().toJson().toString())); api.command("pc", 7, "one-command", command)
            val sent = web.takeRequest()
            assertEquals(command.json, Json.parseToJsonElement(sent.body.readUtf8()).jsonObject["command"])
            assertEquals("queue-intents-v1", sent.getHeader("X-Spofify-Playback-Extensions"))
            assertEquals(3, web.requestCount)
        } finally { web.shutdown() }
    }

    @Test fun unknownProbeVersionFailsBeforeCapabilityWrite() = runBlocking {
        val web = MockWebServer(); web.start()
        try {
            val api = PlaybackDevicesApi(SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), MemorySessionStore()))
            web.enqueue(response(view().toJson().toString().replace("\"queueIntents\":{\"version\":1", "\"queueIntents\":{\"version\":2")))
            try { api.register("PC", 1); fail("Unknown extension version accepted") } catch (_: IllegalArgumentException) { }
            assertEquals("GET", web.takeRequest().method); assertEquals(1, web.requestCount)
        } finally { web.shutdown() }
    }

    @Test fun rawDuplicateOnRealTransportAndUnsafeOpted409CannotBecomeReconciliation() = runBlocking {
        val web = MockWebServer(); web.start()
        try {
            val api = PlaybackDevicesApi(SpofifyClient(ServerAddress.parse("http://127.0.0.1:${web.port}"), MemorySessionStore()))
            web.enqueue(response(view().toJson().toString())); api.probeQueueIntents(); web.takeRequest()
            web.enqueue(response(view().toJson().toString().replaceFirst("{", "{\"epoch\":7,")))
            try { api.view(); fail("Duplicate raw response accepted") } catch (_: ApiException) { }
            web.takeRequest()
            web.enqueue(response(view().toJson().toString(), 409))
            try { api.command("pc", 7, "cmd", PlaybackDeviceCommand.transport("pause")); fail() }
            catch (e: ApiException) { assertNotNull(e.responseBody); assertNotNull(PlaybackDevicesView.fromJson(e.responseBody!!).queueIntents) }
            web.takeRequest()
            web.enqueue(response(view().toJson().toString().replaceFirst("{", "{\"Epoch\":7,"), 409))
            try { api.command("pc", 7, "other", PlaybackDeviceCommand.transport("pause")); fail() }
            catch (e: ApiException) { assertNull(e.responseBody) }
            assertEquals(4, web.requestCount)
        } finally { web.shutdown() }
    }
}
