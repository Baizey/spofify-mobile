package dev.spofify.mobile.core.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

internal const val FIRST_PREVIEW_KEY = 1L shl 52
fun isPublishedQueueKey(key: Long): Boolean = key in 1L until FIRST_PREVIEW_KEY
internal fun addressableAccountChoice(command: JsonObject): Boolean {
    val type = command["type"]?.jsonPrimitive?.content
    if (type !in setOf("jump", "remove", "move")) return true
    if (!isPublishedQueueKey(command["key"]?.jsonPrimitive?.longOrNull ?: 0)) return false
    if (type != "move") return true
    val anchor = command["after"]?.jsonPrimitive?.longOrNull ?: return false
    return anchor == 0L || isPublishedQueueKey(anchor)
}

@Serializable internal data class AccountCursor(val key: Long = 0, val chain: String = "", val visit: Long = 0,
    val run: Long = 0, val elapsed: Double = 0.0, val desired: Boolean = false, val playing: Boolean = false) {
    fun sameRun(other: AccountCursor) = key == other.key && chain == other.chain && visit == other.visit && run == other.run
}
@Serializable internal data class AccountState(val queue: List<PlaybackTrack>, val cursor: AccountCursor,
    val repeat: String, val shuffle: Boolean, val collectionFirst: Long = 0) {
    fun presentation(): PlaybackQueueState {
        val pos = queue.indexOfFirst { it.key == cursor.key }
        require((pos == -1) == queue.isEmpty())
        val row = queue.getOrNull(pos)
        return PlaybackQueueState(queue = queue, position = pos, context = row?.playbackContext, repeat = repeat,
            shuffle = shuffle, elapsed = cursor.elapsed, desiredPlaying = cursor.desired, isPlaying = cursor.playing,
            highKeys = queue.drop(pos + 1).filter { it.intent != "low" }.map { it.key }, duration = row?.duration ?: 0.0,
            occurrence = row?.let { PlaybackOccurrence(it.key, it.id, "${cursor.chain}:${cursor.visit}", 0.0, false) })
    }
}
@Serializable internal data class AccountQueueEdit(val kind: String, val rows: List<PlaybackTrack> = emptyList(),
    val keys: List<Long> = emptyList(), val before: Long = 0, val rebaseAfter: Long = 0, val collectionFirst: Long = 0) {
    fun apply(queue: List<PlaybackTrack>): List<PlaybackTrack> = when (kind) {
        "replace" -> rows
        "insert" -> { val i = if (before == 0L) queue.size else queue.indexOfFirst { it.key == before }; require(i >= 0)
            queue.take(i) + rows + queue.drop(i) }
        "remove" -> keys.toHashSet().let { gone -> queue.filterNot { it.key in gone } }
        "order" -> queue.associateBy { it.key }.let { byKey ->
            require(keys.size == queue.size && keys.toSet().size == keys.size)
            val pos = keys.indexOf(rebaseAfter)
            require(rebaseAfter == 0L || pos >= 0)
            keys.mapIndexed { index, key -> byKey.getValue(key).let { if (pos >= 0) it.copy(intent = if (index > pos) "high" else it.intent, order = index.toLong() + 1) else it } } }
        else -> error("Unknown queue change")
    }
}
@Serializable internal data class AccountDelta(val queue: AccountQueueEdit? = null, val cursor: AccountCursor? = null,
    val repeat: String? = null, val shuffle: Boolean? = null, val observed: Boolean = false, val source: String = "")
@Serializable internal data class AccountFact(val type: String = "fact", val key: Long, val chain: String, val visit: Long,
    val run: Long, val elapsed: Double, val playing: Boolean, val buffering: Boolean, val ended: Boolean, val landed: Long? = null,
    val error: String? = null)

/** One output lifetime. Transport sends facts best-effort, never waits or replays. Uses the same native
 * operation/authorization/early-ended machinery as local playback. Nonowners have no media operations. */
internal class AccountPlayer(audio: AudioExecutor, scope: CoroutineScope, private val allowed: () -> Boolean,
    private val publish: (AccountState) -> Unit, private val report: (AccountFact) -> Unit) {
    private var owner = false
    private var state: AccountState? = null
    private var published: AccountState? = null
    private data class Failure(val cursor: AccountCursor, val error: String)
    private var failure: Failure? = null
    private var execution = 0L

    private val engine = LocalPlaybackController(audio, object : LocalPlaybackLibrary {
        override fun resolve(track: PlaybackTrackReference?, context: PlaybackContext?) = null
        override suspend fun hasAudio(track: PlaybackTrack, isAuthorized: () -> Boolean) = isAuthorized()
    }, scope, { owner && allowed() }, onNaturalEnd = ::ended, onObserved = ::observed,
        onFailure = ::failed, allowPreview = ::confirmedSuccessor)

    private fun Failure.matches(cursor: AccountCursor): Boolean = this.cursor.sameRun(cursor) && this.cursor.desired == cursor.desired
    fun playbackError(cursor: AccountCursor): String? = failure?.takeIf { owner && it.matches(cursor) }?.error

    // No preview on a speculative Play/Add/Shuffle or a locally predicted visit awaiting its echo.
    // The ordinary EOF path is unchanged; preloading never emits a command or fact.
    private fun confirmedSuccessor(track: PlaybackTrack): Boolean {
        val live = state ?: return false
        val confirmed = published ?: return false
        return owner && allowed() && live.queue === confirmed.queue && live.repeat == confirmed.repeat &&
            live.cursor.sameRun(confirmed.cursor) && confirmed.cursor.desired && isPublishedQueueKey(track.key)
    }

    fun snapshot(next: AccountState, output: Boolean) { if(next.collectionFirst!=0L)cache(next.queue,next.collectionFirst); published = next; adopt(next, output) }
    private fun cache(rows: List<PlaybackTrack>, first: Long) {
        val ordered=rows.sortedBy { it.order }; val i=ordered.indexOfFirst { it.key==first }; if(i>=0)complete=ordered.drop(i)+ordered.take(i)
    }
    private fun shuffled(rows: List<PlaybackTrack>, seed: Long): List<PlaybackTrack> {
        val a=rows.toMutableList();var n=seed
        for(i in a.lastIndex downTo 1){n=(n*1664525+1013904223) and 0xffffffffL; val j=(n*(i+1) ushr 32).toInt();val t=a[i];a[i]=a[j];a[j]=t};return a
    }
    private var complete = emptyList<PlaybackTrack>()
    private var temporary = 9_007_199_254_740_991L
    private fun sameContext(a: PlaybackContext?, b: PlaybackContext?) = a?.kind == b?.kind && a?.id == b?.id
    private fun sameRow(a: PlaybackTrack?, b: PlaybackTrack?) = a != null && b != null && a.id == b.id && a.entryId == b.entryId && a.targetId == b.targetId && a.occurrenceId == b.occurrenceId && sameContext(a.playbackContext,b.playbackContext)
    fun update(d: AccountDelta, ownSelection: Boolean = false) {
        val prev = requireNotNull(published)
        val next = prev.copy(queue = d.queue?.apply(prev.queue) ?: prev.queue, cursor = d.cursor ?: prev.cursor,
            repeat = d.repeat ?: prev.repeat, shuffle = d.shuffle ?: prev.shuffle)
        published = next
        val local = requireNotNull(state)
        d.queue?.takeIf { it.collectionFirst != 0L }?.let { edit ->
            cache(edit.rows,edit.collectionFirst)
        }
        val lc=local.cursor; val pc=next.cursor
        if (owner && ownSelection && (d.queue?.kind == "replace" || prev.queue.isEmpty() && d.queue?.kind == "insert") && lc.chain.startsWith("local:") && lc.visit==0L && lc.run==0L && lc.desired==pc.desired && pc.elapsed==0.0 && sameRow(local.queue.find { it.key==lc.key }, next.queue.find { it.key==pc.key })) {
            adopt(next.copy(cursor=pc.copy(elapsed=lc.elapsed,playing=lc.playing)),true,confirm=true); return
        }
        if (owner && d.observed && d.cursor != null) {if(local.cursor.sameRun(d.cursor))adopt(local.copy(cursor=d.cursor),true);return}
        // An earlier add or progress tick isn't a new Play. Keep the local run
        // until the server publishes a traversal change or a cursor correction.
        if (owner && d.cursor == null && d.queue?.kind != "replace" && !local.cursor.sameRun(prev.cursor)) {
            adopt(next.copy(queue = if (local.cursor.key==0L || local.cursor.chain.startsWith("local:")) local.queue else next.queue,
                cursor = local.cursor), true)
        } else adopt(next, owner)
    }
    fun output(value: Boolean) { state?.let { adopt(it, value) } }
    private fun adopt(next: AccountState, output: Boolean, confirm: Boolean = false, natural: Boolean = false,
        terminalEnd: Boolean = false) {
        val old = state
        val start = !owner || old == null || !old.cursor.sameRun(next.cursor) || old.cursor.desired != next.cursor.desired
        if (start || !output) execution++
        val revision = execution
        if (owner && !output) engine.stop()
        failure = failure?.takeIf { output }?.let { stopped ->
            when {
                stopped.matches(next.cursor) -> stopped
                // The exact speculative selection was confirmed without starting another decoder.
                confirm && old != null && stopped.matches(old.cursor) -> stopped.copy(cursor = next.cursor)
                else -> null
            }
        }
        owner = output
        state = next
        publish(next)
        // StateFlow publication can run collectors inline. Never execute a superseded run;
        // a same-run queue edit may update the rows while this operation still owns the start.
        if (owner && execution == revision) {
            val queue = requireNotNull(state).presentation()
            if (terminalEnd) engine.finishNaturalEnd(queue)
            else if (failure == null) engine.adopt(queue, start && !confirm, natural)
        }
    }
    private var preview = 0L
    fun local(command: JsonObject) {
        val s = state ?: return
        if (!owner || !allowed() || !addressableAccountChoice(command)) return
        val q = s.presentation(); val c = s.cursor
        fun step(index: Int?) = index?.let { c.copy(key = q.queue[it].key, visit = c.visit + 1, run = 0, elapsed = 0.0, desired = true, playing = false) }
            ?: c.copy(run = c.run + 1, desired = false, playing = false)
        val next = when (command.getValue("type").jsonPrimitive.content) {
            "pause" -> {
                val measured = if (c.desired) engine.sampleBeforePause() else null
                // Publish the physical tail on the outgoing binding, before invalidating it.
                measured?.let(::observed)
                s.copy(cursor = c.copy(run = c.run + 1, desired = false, playing = false, elapsed = measured?.elapsed ?: c.elapsed))
            }
            "resume" -> s.copy(cursor = c.copy(run = c.run + 1, desired = true, playing = false))
            "seek" -> s.copy(cursor = c.copy(run = c.run + 1, elapsed = command.getValue("value").jsonPrimitive.double, playing = false))
            "next" -> s.copy(cursor = step(LocalPlaybackQueue.successor(q, false)))
            "previous" -> if (q.current == null) return else s.copy(cursor = step(LocalPlaybackQueue.predecessor(q, engine.state.value.elapsed)))
            "jump" -> s.copy(cursor = step(q.queue.indexOfFirst { it.key == command.getValue("key").jsonPrimitive.long }.takeIf { it >= 0 } ?: return))
            "repeat" -> s.copy(repeat = command.getValue("repeat").jsonPrimitive.content)
            "close" -> s.copy(queue=emptyList(),cursor=AccountCursor(),shuffle=false)
            "clear" -> s.copy(queue=s.queue.take(q.position+1))
            "remove" -> s.copy(queue=LocalPlaybackQueue.remove(q,command.getValue("key").jsonPrimitive.long).queue)
            "shuffle" -> { val mode=command.getValue("shuffle").jsonPrimitive.boolean;if(mode==s.shuffle)return
                val (priority,regular)=s.queue.drop(q.position+1).partition { it.intent!="low" }
                s.copy(queue=s.queue.take(q.position+1)+priority+(if(mode)shuffled(regular,command["seed"]?.jsonPrimitive?.long?:0) else regular.sortedBy { it.order }),shuffle=mode) }
            "move" -> {
                val key=command.getValue("key").jsonPrimitive.long; val after=command.getValue("after").jsonPrimitive.long
                val i=s.queue.indexOfFirst { it.key==key };val a=if(after==0L)q.position else s.queue.indexOfFirst { it.key==after }
                if(i<=q.position || i==a || after!=0L && a<=q.position)return
                val rows=s.queue.toMutableList();val row=rows.removeAt(i);rows.add(if(a<i)a+1 else a,row)
                s.copy(queue=rows.mapIndexed { ix,r->r.copy(order=ix+1L,intent=if(ix>q.position)"high" else r.intent) },shuffle=false)
            }
            "play", "add" -> {
                val sel = command.getValue("selection").jsonObject
                val ctx = sel["context"]?.let { playbackJson.decodeFromJsonElement<PlaybackContext>(it) }
                val ref = sel["track"]?.let { playbackJson.decodeFromJsonElement<PlaybackTrackReference>(it) }
                val add = command.getValue("type").jsonPrimitive.content == "add"
                val full = complete.takeIf { ctx!=null && it.isNotEmpty() && sameContext(it.first().playbackContext,ctx) }
                val candidates=full?:s.queue
                val selected=if(ref==null) full?:return else {
                    val i=candidates.indexOfFirst { it.id==ref.id && (ctx==null || sameContext(it.playbackContext,ctx)) && (ref.entryId==null || it.entryId==ref.entryId) && (ref.occurrenceId==null || it.occurrenceId==ref.occurrenceId) }; if(i<0)return
                    if(!add && full!=null){if(command["shuffle"]?.jsonPrimitive?.boolean==true)full else full.drop(i)+full.take(i)} else listOf(candidates[i])
                }
                val placement=if(add)command.getValue("placement").jsonPrimitive.content else "low"
                val order=if(add)s.queue.maxOfOrNull { it.order }?:0L else 0L
                if (selected.size.toLong() > temporary - FIRST_PREVIEW_KEY + 1) return
                val rows=selected.mapIndexed { i,r->r.copy(key=temporary--,order=order+i+1,intent=placement,playbackContext=if(ctx==null)null else r.playbackContext,entryId=if(ctx==null)null else r.entryId,targetId=if(ctx==null)null else r.targetId,occurrenceId=if(ctx==null)null else r.occurrenceId) }
                if(add){var at=if(placement=="low")s.queue.size else q.position+1;if(placement=="high")while(at<s.queue.size&&s.queue[at].intent!="low")at++
                    s.copy(queue=s.queue.take(at)+rows+s.queue.drop(at),cursor=if(q.position<0)AccountCursor(rows.first().key,"local:${++preview}")else c)
                }else{val shuffle=command["shuffle"]?.jsonPrimitive?.boolean==true;val traversal=if(shuffle)shuffled(rows,command["seed"]?.jsonPrimitive?.long?:0) else rows
                    s.copy(queue=traversal,cursor=AccountCursor(traversal.first().key,"local:${++preview}",desired=true),shuffle=shuffle)
                }
            }
            else -> return
        }
        adopt(next, true)
    }
    private fun ended(previous: PlaybackQueueState, facts: AudioFacts) {
        val old = state ?: return
        if (!owner || !allowed() || !old.cursor.desired || facts.binding?.trackId != old.queue.getOrNull(previous.position)?.id) return
        val next = LocalPlaybackQueue.successor(previous, naturalEnd = true)
        val cursor = if (next == null) old.cursor.copy(run = old.cursor.run + 1, desired = false, playing = false, elapsed = 0.0)
            else AccountCursor(old.queue[next].key, old.cursor.chain, old.cursor.visit + 1, desired = true)
        // Report the source end before any synchronous successor facts. No response needed for adopt().
        if (old.cursor.key < (1L shl 52) && !old.cursor.chain.startsWith("local:")) report(fact(old.cursor, facts, next?.let { old.queue[it].key } ?: 0L))
        adopt(old.copy(cursor = cursor), true, natural = next != null && confirmedSuccessor(old.queue[next]), terminalEnd = next == null)
    }
    private fun observed(facts: AudioFacts) {
        val s = state ?: return
        val c = s.cursor
        if (owner && allowed() && !facts.ended && facts.error.isEmpty() && safeSeconds(facts.elapsed)) {
            val next = s.copy(cursor = c.copy(elapsed = facts.elapsed, playing = facts.isPlaying))
            state = next
            publish(next)
            if (c.key < FIRST_PREVIEW_KEY && !c.chain.startsWith("local:")) report(fact(c, facts, null))
        }
    }

    private fun failed(error: String, facts: AudioFacts?) {
        val s = state ?: return
        if (!owner || !allowed() || s.cursor.key == 0L) return
        val c = s.cursor
        val elapsed = facts?.elapsed?.takeIf(::safeSeconds) ?: c.elapsed
        val refusal = error == "blocked" && facts?.error == "blocked" && !facts.isPlaying && !facts.ended
        val next = s.copy(cursor = c.copy(elapsed = elapsed, playing = false))
        // Keep canonical desired intent unchanged. The failure belongs to this exact run on
        // this output only; a same-run server echo or lease renewal is not another Play.
        val stopped = Failure(next.cursor, error)
        failure = stopped
        state = next
        publish(next)
        if (failure === stopped && published?.cursor?.sameRun(c) == true && (refusal || c.playing)) {
            // The protocol accepts observed output refusal, but never a fabricated media error
            // or EOF. An ordinary stopped sample clears any previously published playing fact.
            report(fact(c, AudioFacts(elapsed = elapsed, error = if (refusal) "blocked" else ""), null))
        }
    }

    private fun fact(c: AccountCursor, f: AudioFacts, landed: Long?) = AccountFact(key = c.key, chain = c.chain,
        visit = c.visit, run = c.run, elapsed = f.elapsed, playing = !f.ended && f.isPlaying, buffering = f.isBuffering,
        ended = landed != null, landed = landed, error = f.error.takeIf { it == "blocked" })
    fun close() { owner = false; engine.retire() }
}
