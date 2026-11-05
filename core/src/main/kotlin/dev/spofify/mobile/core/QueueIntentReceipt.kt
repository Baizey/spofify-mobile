package dev.spofify.mobile.core

/** Receiver-side terminal admission with the captured command and pre-operation state.
 * Poll publication validates its actual state atomically; this additional check proves
 * the receipt describes this command, not an unrelated later sample. */
object QueueIntentReceipt {
    fun validate(command: PlaybackDeviceCommand, result: QueueIntentResult, status: String,
        snapshot: PlaybackSnapshot, metadata: QueueIntentState,
        base: PlaybackSnapshot, baseMetadata: QueueIntentState) {
        require(command.type == "queueIntent")
        val expected = requireNotNull(command.expected)
        val selection = requireNotNull(command.selection)
        val intent = requireNotNull(command.intent)
        baseMetadata.validated(base)
        result.validated(snapshot, metadata, status)
        require(metadata.receiverId == expected.receiverId && metadata.revision >= expected.revision)
        val revision = result.commitRevision
        if (revision == null) {
            if (result.outcome != "stale") require(metadata.revision == expected.revision && sameSemantics(base, baseMetadata, snapshot, metadata))
            return
        }
        require(result.acceptedCount > 0 && revision >= expected.revision && revision <= metadata.revision)
        val placement = if (intent == "now") if (selection is QueueIntentSelection.Occurrence) "existing" else "replace" else intent
        require(result.placement == placement)
        if (selection is QueueIntentSelection.Occurrence) require(result.acceptedCount == 1)
        if (selection is QueueIntentSelection.Track && intent != "now") require(result.acceptedCount <= 1)
        if (result.playback == "superseded") {
            require(intent == "now" || expected.currentKey == null)
            // Only independent newer semantic edits permit a receipt older than actual state.
            if (revision < metadata.revision) return
            require(revision > expected.revision && !snapshot.playing && snapshot.occurrence != null && snapshot.position >= 0)
            val current = snapshot.queue[snapshot.position]
            require(current.key != expected.currentKey && snapshot.occurrence.eventId != expected.currentEventId)
            if (intent != "now" && expected.currentKey == null) require(snapshot.position == 0)
            if (selection is QueueIntentSelection.Track) require(matches(current, selection))
            if (intent == "now" && selection is QueueIntentSelection.Collection) require(snapshot.position == 0)
            effect(command, result, base, snapshot, metadata)
            return
        }
        require(revision == metadata.revision && revision > expected.revision)
        effect(command, result, base, snapshot, metadata)
        if (intent != "now" && expected.currentKey != null) require(result.playback == "unchanged")
        if (intent != "now" && expected.currentKey == null) require(result.playback != "unchanged")
        if (result.playback == "unchanged") {
            require(snapshot.queue.getOrNull(snapshot.position)?.key == expected.currentKey && snapshot.occurrence?.eventId == expected.currentEventId && intent != "now")
        }
        result.playbackKey?.let {
            require(it != expected.currentKey)
            if (intent == "now" && selection is QueueIntentSelection.Occurrence) require(it == selection.key)
        }
    }

    private fun matches(track: PlaybackTrack, selection: QueueIntentSelection.Track): Boolean {
        val requested = selection.track
        fun field(name: String) = (requested[name] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
        return track.id == field("id") && (field("entryId").isEmpty() || track.entryId == field("entryId")) &&
            (field("targetId").isEmpty() || track.targetId == field("targetId") && track.occurrenceId == field("occurrenceId"))
    }
    private fun effect(command: PlaybackDeviceCommand, result: QueueIntentResult, base: PlaybackSnapshot, snapshot: PlaybackSnapshot, metadata: QueueIntentState) {
        val selection = requireNotNull(command.selection); val intent = requireNotNull(command.intent)
        if (intent == "now" && selection !is QueueIntentSelection.Occurrence) {
            require(snapshot.queue.size == result.acceptedCount && snapshot.position >= 0 && metadata.highKeys.isEmpty() && metadata.provenance == "tracked")
            metadata.origins.forEachIndexed { i, origin -> require(origin.intent == if (i == snapshot.position) "now" else "low") }
            if (selection is QueueIntentSelection.Track) require(matches(snapshot.queue[snapshot.position], selection))
            return
        }
        val old = base.queue.mapIndexed { i, t -> t.key to i }.toMap()
        val high = metadata.highKeys.toHashSet()
        val selectedKey = (selection as? QueueIntentSelection.Occurrence)?.key
        var added = 0; var selected = false; var previous = -1
        val seen = hashSetOf<Long>()
        snapshot.queue.forEachIndexed { i, t ->
            seen.add(t.key)
            val index = old[t.key]
            if (index != null) {
                if (t.key != selectedKey) { require(index >= previous); previous = index }
            } else {
                added++; require(metadata.origins[i].intent == intent)
                if (i > snapshot.position) require((t.key in high) == (intent in setOf("high", "next")))
            }
            if (t.key == selectedKey) {
                selected = true; require(metadata.origins[i].intent == intent)
                if (intent == "low") require(t.key !in high)
                if (i > snapshot.position) require((t.key in high) == (intent in setOf("high", "next")))
            }
        }
        base.queue.forEachIndexed { i, t -> require(t.key in seen || i < base.position) }
        if (selectedKey != null) {
            require(selected && added == 0 && snapshot.queue.size == base.queue.size)
            if (intent == "now") require(snapshot.queue.getOrNull(snapshot.position)?.key == selectedKey)
        } else require(added == result.acceptedCount)
    }
    fun sameSemantics(a: PlaybackSnapshot, am: QueueIntentState, b: PlaybackSnapshot, bm: QueueIntentState): Boolean {
        fun musical(s: PlaybackSnapshot) = s.copy(elapsed = 0.0, duration = 0.0, playing = false, waiting = false, loading = false, error = "",
            occurrence = s.occurrence?.copy(progress = 0.0, reported = false))
        return musical(a) == musical(b) && am.provenance == bm.provenance && am.origins == bm.origins && am.highKeys == bm.highKeys
    }
}
