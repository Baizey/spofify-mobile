package dev.spofify.mobile.core.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class AccountChannelTest {
    private class Socket : PlaybackConnection {
        val input=Channel<String>(32);override val messages=input.receiveAsFlow();override var isOpen=true
        val sent=mutableListOf<JsonObject>()
        override fun send(text:String):Boolean {if(!isOpen)return false;sent+=playbackJson.parseToJsonElement(text).jsonObject;return true}
        override fun close(){isOpen=false;input.close()}
        fun frame(s:String){check(input.trySend(s).isSuccess)}
    }
    private class Audio:AudioExecutor {
        override val observations=MutableSharedFlow<AudioObservation>(extraBufferCapacity=16)
        var facts=AudioFacts();val operations=mutableListOf<AudioBinding>();var authorized:()->Boolean={false}
        var nextResult = AudioResult.OK
        var preparing: CompletableDeferred<Unit>? = null
        override fun pause(){facts=facts.copy(isPlaying=false)}
        override fun facts()=facts
        override suspend fun prepare(binding:AudioBinding,track:PlaybackTrack?,elapsed:Double):AudioResult=error("unused")
        override suspend fun execute(binding:AudioBinding,track:PlaybackTrack?,elapsed:Double,desiredPlaying:Boolean,isAuthorized:()->Boolean):AudioResult {
            operations += binding
            authorized = isAuthorized
            val result = nextResult.also { nextResult = AudioResult.OK }
            facts = AudioFacts(binding, elapsed, 32.0)
            preparing?.await()
            facts = facts.copy(isPlaying = result == AudioResult.OK && desiredPlaying,
                error = when (result) { AudioResult.BLOCKED -> "blocked"; AudioResult.MEDIA -> "media"; else -> "" })
            return result
        }
    }
    private fun snapshot(id:String,owner:String,second:Long=2):String {
        fun row(key:Long)=PlaybackTrack("duplicate","same", emptyList(),"","",32.0,key,key,"low")
        val s=AccountState(listOf(row(1),row(second)),AccountCursor(1,"root",desired=true),"off",false)
        return fields("type" to "snapshot","connectionId" to id,"owner" to owner,"devices" to JsonArray(emptyList()),"player" to playbackJson.encodeToJsonElement(s)).toString()
    }
    @Test fun previewIsReadOnlyUntilPublicationAndDoesNotLockStableRows()=runTest {
        val socket=Socket();val audio=Audio();val c=AccountChannel(PlaybackTransport{_,_->socket},audio,backgroundScope,object:PlaybackClock{override fun monotonicMs()=0L;override fun wallTimeMs()=0L})
        val stable=FIRST_PREVIEW_KEY-2;c.connect("Phone","here");runCurrent();socket.frame(snapshot("owner","owner",stable));runCurrent();socket.frame("""{"type":"grant","basis":0,"ms":8000,"handle":"owner"}""");runCurrent()
        repeat(2){assertTrue(c.queueTrack(PlaybackTrackReference("duplicate")))}
        val before=c.state.value;val keys=before.queue.map { it.key };val n=socket.sent.size;val starts=audio.operations.size
        assertEquals(4,keys.distinct().size);assertTrue(keys.take(2).all(::isPublishedQueueKey));assertTrue(keys.drop(2).none(::isPublishedQueueKey));assertFalse(c.jump(keys[2]));assertFalse(c.remove(keys[2]));assertFalse(c.moveUpcoming(keys[3],null));assertFalse(c.moveUpcoming(stable,keys[2]));runCurrent();assertSame(before,c.state.value);assertEquals(n,socket.sent.size);assertEquals(starts,audio.operations.size)
        assertTrue(c.remove(stable));assertFalse(c.state.value.queue.any {it.key==stable});assertEquals(stable,socket.sent.last().getValue("key").jsonPrimitive.long)
        val lastPublic=FIRST_PREVIEW_KEY-1;assertFalse(lastPublic in keys);val newRow=before.queue.first().copy(key=lastPublic,order=3)
        val update=AccountDelta(queue=AccountQueueEdit("insert",rows=listOf(newRow)),source="owner");socket.frame(fields("type" to "player","player" to playbackJson.encodeToJsonElement(update)).toString());runCurrent();assertTrue(c.jump(lastPublic));assertEquals(lastPublic,c.state.value.current!!.key);c.close()
    }
    @Test fun pauseSamplesPhysicalTailOnStillLiveRunBeforeSendingChoice()=runTest {
        for (scenario in listOf("playing","buffering","wrong binding","stopped")) {
            val socket=Socket();val audio=Audio();val c=AccountChannel(PlaybackTransport{_,_->socket},audio,backgroundScope,object:PlaybackClock{override fun monotonicMs()=0L;override fun wallTimeMs()=0L})
            c.connect("Phone","here");runCurrent();socket.frame(snapshot("owner","owner"));runCurrent();socket.frame("""{"type":"grant","basis":0,"ms":8000,"handle":"owner"}""");runCurrent()
            val binding=audio.operations.single();audio.facts=AudioFacts(binding,30.0,32.0,isPlaying=true);audio.observations.tryEmit(AudioObservation(binding,audio.facts));runCurrent()
            val n=socket.sent.size;audio.facts=audio.facts.copy(binding=if(scenario=="wrong binding")binding.copy(instructionId="stale")else binding,elapsed=30.4,isPlaying=scenario!="stopped",isBuffering=scenario=="buffering");assertTrue(c.pause())
            val sent=socket.sent.drop(n);assertEquals("pause",sent.last().getValue("type").jsonPrimitive.content)
            if(scenario=="playing" || scenario=="stopped") {val f=sent.dropLast(1).single();assertEquals("fact",f.getValue("type").jsonPrimitive.content);assertEquals("root",f.getValue("chain").jsonPrimitive.content);assertEquals(0L,f.getValue("run").jsonPrimitive.long);assertEquals(30.4,f.getValue("elapsed").jsonPrimitive.double,0.0);assertEquals(scenario=="playing",f.getValue("playing").jsonPrimitive.boolean)} else assertEquals(1,sent.size)
            runCurrent();c.close()
        }
    }
    private fun Socket.grant() = frame("""{"type":"grant","basis":0,"ms":8000,"handle":"owner"}""")
    private fun Socket.update(delta: AccountDelta) = frame(fields("type" to "player", "player" to playbackJson.encodeToJsonElement(delta)).toString())
    private fun Socket.facts() = sent.filter { it["type"]?.jsonPrimitive?.content == "fact" }
    private fun TestScope.connect(socket: Socket, audio: Audio, owner: String = "owner"): AccountChannel {
        val clock = object : PlaybackClock {
            override fun monotonicMs() = testScheduler.currentTime
            override fun wallTimeMs() = 0L
        }
        return AccountChannel(PlaybackTransport { _, _ -> socket }, audio, backgroundScope, clock).also {
            it.connect("Phone", "here"); runCurrent()
            socket.frame(snapshot("owner", owner)); runCurrent()
            if (owner == "owner") { socket.grant(); runCurrent() }
        }
    }

    @Test fun preparingIsNotPlayingButCanBePaused() = runTest {
        val audio = Audio().apply { preparing = CompletableDeferred() }
        val socket = Socket()
        val channel = connect(socket, audio)
        assertFalse(channel.state.value.isPlaying)
        assertTrue(channel.state.value.wantsPause)
        assertTrue(channel.toggle())
        assertEquals("pause", socket.sent.last()["type"]?.jsonPrimitive?.content)
        channel.close()
    }

    @Test fun localRefusalSurvivesSameRunEchoAndOnePlayStartsANewRun() = runTest {
        val audio = Audio().apply { nextResult = AudioResult.BLOCKED }
        val socket = Socket()
        val channel = connect(socket, audio)
        val refused = audio.operations.single()
        val view = channel.state.value
        assertTrue(view.desiredPlaying)
        assertFalse(view.isPlaying)
        assertFalse(view.wantsPause)
        assertEquals("blocked", view.playback?.error)
        assertEquals("blocked", socket.facts().single()["error"]?.jsonPrimitive?.content)
        socket.update(AccountDelta(cursor = AccountCursor(1, "root", elapsed = 0.2, desired = true, playing = true), observed = true))
        socket.grant(); runCurrent()
        assertFalse(channel.state.value.isPlaying)
        assertFalse(channel.state.value.wantsPause)
        assertEquals(1, audio.operations.size)
        assertTrue(channel.toggle())
        assertEquals("resume", socket.sent.last()["type"]?.jsonPrimitive?.content)
        runCurrent()
        assertTrue(channel.state.value.isPlaying)
        assertEquals("", channel.state.value.playback?.error)
        assertEquals(2, audio.operations.size)
        audio.observations.tryEmit(AudioObservation(refused, AudioFacts(binding = refused, error = "blocked")))
        runCurrent()
        assertTrue(channel.state.value.isPlaying)
        channel.close()
    }

    @Test fun failedSpeculativeSelectionIsNotRestartedOnItsMatchingConfirmation() = runTest {
        val audio = Audio()
        val socket = Socket()
        val channel = connect(socket, audio)
        val track = channel.state.value.current!!.copy(key = 3, order = 3)
        audio.nextResult = AudioResult.MEDIA
        assertTrue(channel.playTrack(PlaybackTrackReference(track.id)))
        runCurrent()
        assertEquals("media", channel.state.value.playback?.error)
        socket.update(AccountDelta(queue = AccountQueueEdit("replace", rows = listOf(track)),
            cursor = AccountCursor(3, "confirmed", desired = true), source = "owner"))
        runCurrent()
        assertEquals(3L, channel.state.value.current?.key)
        assertEquals("media", channel.state.value.playback?.error)
        assertFalse(channel.state.value.wantsPause)
        assertEquals(2, audio.operations.size)
        channel.close()
    }

    @Test fun terminalMediaFailureAfterPlayingPublishesAnOrdinaryStoppedFact() = runTest {
        val audio = Audio()
        val socket = Socket()
        val channel = connect(socket, audio)
        assertTrue(channel.state.value.isPlaying)
        val binding = audio.operations.single()
        audio.facts = AudioFacts(binding, elapsed = 6.0, duration = 32.0, error = "media")
        audio.observations.tryEmit(AudioObservation(binding, audio.facts))
        runCurrent()
        assertFalse(channel.state.value.isPlaying)
        assertFalse(channel.state.value.wantsPause)
        assertEquals("media", channel.state.value.playback?.error)
        val stopped = socket.facts().last()
        assertFalse(stopped.getValue("playing").jsonPrimitive.boolean)
        assertFalse(stopped.getValue("ended").jsonPrimitive.boolean)
        assertNull(stopped["error"])
        assertEquals(6.0, stopped.getValue("elapsed").jsonPrimitive.double, 0.0)
        channel.close()
    }

    @Test fun reentrantNewerRunWinsBeforeNativeExecution() = runTest {
        lateinit var player: AccountPlayer
        val audio = Audio()
        val rows = listOf(
            PlaybackTrack("first", "First", emptyList(), "", "", 32.0, 1, 1, "low"),
            PlaybackTrack("second", "Second", emptyList(), "", "", 32.0, 2, 2, "low"),
        )
        var supersede = true
        player = AccountPlayer(audio, backgroundScope, { true }, publish = { state ->
            if (state.cursor.key == 1L && supersede) {
                supersede = false
                player.update(AccountDelta(cursor = AccountCursor(2, "chain", visit = 1, desired = true)))
            }
        }, report = {})
        player.snapshot(AccountState(rows, AccountCursor(1, "chain", desired = true), "off", false), output = true)
        runCurrent()
        assertEquals(listOf("second"), audio.operations.map { it.trackId })
        player.close()
    }

    @Test fun remoteDesiredWithoutReportedSoundDoesNotBecomeALocalFailure() = runTest {
        val audio = Audio()
        val socket = Socket()
        val channel = connect(socket, audio, owner = "desktop")
        assertFalse(channel.state.value.isPlaying)
        assertTrue(channel.state.value.wantsPause)
        assertTrue(audio.operations.isEmpty())
        assertTrue(channel.toggle())
        assertEquals("pause", socket.sent.last()["type"]?.jsonPrimitive?.content)
        channel.close()
    }

    @Test fun noAckNoReplayReattachesLocallyAdvancedOccurrence()=runTest {
        val audio=Audio();var socket=Socket();val clock=object:PlaybackClock {override fun monotonicMs()=testScheduler.currentTime;override fun wallTimeMs()=0L}
        val c=AccountChannel(PlaybackTransport{_,_->socket},audio,backgroundScope,clock)
        c.connect("Phone","here");runCurrent();socket.frame(snapshot("old","old"));runCurrent();socket.frame("""{"type":"grant","basis":0,"ms":15000,"handle":"private"}""");runCurrent()
        assertEquals(1,audio.operations.size);assertTrue(c.queueTrack(PlaybackTrackReference("duplicate")));assertEquals(setOf("type","placement","selection"),socket.sent.last().keys)
        socket.close();runCurrent();assertTrue(audio.authorized());val src=audio.operations.single();val end=AudioFacts(src,32.0,32.0,ended=true);audio.observations.tryEmit(AudioObservation(src,end));runCurrent()
        assertEquals(2,audio.operations.size);assertEquals(2L,c.state.value.current!!.key)
        advanceTimeBy(9_000);runCurrent();assertTrue(audio.authorized())
        socket=Socket();c.connect("Phone","here");runCurrent();socket.frame(snapshot("fresh","old"));runCurrent();val present=socket.sent.single();assertEquals("private",present["handle"]!!.jsonPrimitive.content);assertEquals(2L,present["actual"]!!.jsonObject["key"]!!.jsonPrimitive.long)
        socket.frame(fields("type" to "player","player" to fields("cursor" to present["actual"])).toString());socket.frame("""{"type":"outputs","owner":"fresh","devices":[]}""");socket.frame("""{"type":"grant","basis":9000,"ms":15000,"handle":"new"}""");runCurrent();assertEquals(2,audio.operations.size);assertFalse(socket.sent.any{it["type"]?.jsonPrimitive?.content=="add"})
        socket.close();runCurrent();advanceTimeBy(14_999);runCurrent();assertTrue(audio.authorized())
        advanceTimeBy(1);runCurrent();assertFalse(audio.authorized());c.close()
    }
}
