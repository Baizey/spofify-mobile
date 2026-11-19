package dev.spofify.mobile.core.playback

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

private const val MAX_OUTPUT_LEASE_MS = 15_000L

/** Player-looper bound for one authenticated account lifetime. The session transport enforces
 * account/origin on each socket operation. Only UI state and the bounded output capability survive loss. */
class AccountChannel(private val transport: PlaybackTransport, audio: AudioExecutor, private val scope: CoroutineScope,
    private val clock: PlaybackClock = SystemPlaybackClock, private val current: () -> Boolean = { true }) : AutoCloseable {
    @Serializable private data class Device(val id:String,val deviceId:String,val name:String,val canPlay:Boolean)
    private val view = MutableStateFlow(PlaybackView())
    val state = view.asStateFlow()
    private var socket:PlaybackConnection?=null
    private var worker:Job?=null
    private var expired:Job?=null
    private var closed=false
    private var life=0L
    private var handle=""
    private var deadline=0L
    private var owner=""
    private var own=""
    private var devices=listOf<Device>()
    private var server:AccountState?=null
    private var actual:AccountState?=null
    private var detached:AccountCursor?=null
    private var awaitingAttach=false
    private var n=0L
    private val personalListeners=mutableSetOf<(JsonObject?)->Unit>()
    fun onPersonal(fn:(JsonObject?)->Unit):AutoCloseable { personalListeners+=fn;return AutoCloseable { personalListeners-=fn } }
    fun sendPersonal(command:JsonObject)=send(fields("type" to "personal","command" to command))
    private fun allowed()=!closed&&current()&&handle.isNotEmpty()&&clock.monotonicMs()<deadline
    private val replica=AccountPlayer(audio,scope,::allowed,::publish) { fact ->
        if (view.value.connection == PlaybackConnectionState.READY && owner == own && allowed() && !awaitingAttach) send(JsonObject(playbackJson.encodeToJsonElement(fact).jsonObject+fields("type" to "fact")))
    }
    private fun publish(s:AccountState){
        if(actual?.queue!==s.queue||actual?.cursor?.sameRun(s.cursor)!=true) n++
        actual=s;val q=s.presentation();val c=s.cursor;val p=PlaybackStatus(0,n,0,"${c.chain}:${c.visit}",true,c.elapsed,q.duration,c.playing,false,false,"")
        view.value=view.value.copy(connectionId=own,snapshot=PlaybackSnapshot(view.value.sessionId,n,0,q,p,devices.distinctBy{it.deviceId}.map{PlaybackDevice(it.deviceId,it.name,true,it.canPlay)},devices.find{it.id==owner}?.deviceId.orEmpty(),owner,null,0),playback=p)
    }
    private fun loseOutput(){handle="";deadline=0;detached=null;awaitingAttach=false;expired?.cancel();replica.output(false)}
    fun connect(name:String,deviceId:String):Job {
        if(closed||!current())return scope.launch {}
        val gen=++life;worker?.cancel();socket?.close();socket=null
        view.value=view.value.copy(connection=PlaybackConnectionState.CONNECTING,deviceId=deviceId)
        return scope.launch {
            var pulse:Job?=null
            try {
                val conn=withTimeout(10000){transport.connect(name,deviceId)}
                if(gen!=life||closed){conn.close();return@launch};socket=conn
                var initial=true
                val timeout=launch {delay(10000);if(initial)conn.close()}
                try { conn.messages.collect {text ->
                    if(gen!=life||!current())return@collect
                    val m=playbackJson.parseToJsonElement(text).jsonObject;val type=m.getValue("type").jsonPrimitive.content
                    require(!initial||type=="snapshot")
                    when(type){
                        "snapshot"->{val old=own;own=m.getValue("connectionId").jsonPrimitive.content;owner=m.getValue("owner").jsonPrimitive.content;devices=playbackJson.decodeFromJsonElement(m.getValue("devices"));server=playbackJson.decodeFromJsonElement(m.getValue("player"));awaitingAttach=detached!=null&&allowed()&&owner==old
                            if(!awaitingAttach){loseOutput();replica.snapshot(server!!,false)}
                            view.value=view.value.copy(connection=PlaybackConnectionState.READY,sessionId=deviceId,connectionId=own);actual?.let(::publish);initial=false;timeout.cancel();pulse?.cancel();pulse=launch{while(isActive){presence();delay(2000)}} }
                        "outputs"->{val was=owner==own;owner=m.getValue("owner").jsonPrimitive.content;devices=playbackJson.decodeFromJsonElement(m.getValue("devices"));if(was&&owner!=own&&!awaitingAttach){loseOutput();send(fields("type" to "release"))};actual?.let(::publish);if(owner==own&&!allowed())presence()}
                        "grant"->{val ms=m.getValue("ms").jsonPrimitive.long;val basis=m.getValue("basis").jsonPrimitive.long;val now=clock.monotonicMs();if(ms in 1..MAX_OUTPUT_LEASE_MS&&basis<=now&&basis+ms>now&&owner==own){handle=m.getValue("handle").jsonPrimitive.content;deadline=basis+ms;expired?.cancel();expired=scope.launch{delay((deadline-clock.monotonicMs()).coerceAtLeast(0));if(!allowed()){loseOutput();server?.let{replica.snapshot(it,false)}}};if(awaitingAttach)replica.snapshot(server!!,true)else replica.output(true);detached=null;awaitingAttach=false}}
                        "player"->{val d=playbackJson.decodeFromJsonElement<AccountDelta>(m.getValue("player"));val s=requireNotNull(server);server=s.copy(queue=d.queue?.apply(s.queue)?:s.queue,cursor=d.cursor?:s.cursor,repeat=d.repeat?:s.repeat,shuffle=d.shuffle?:s.shuffle,collectionFirst=if(d.queue==null)s.collectionFirst else if(d.queue.kind=="replace"||s.queue.isEmpty())d.queue.collectionFirst else 0);if(!awaitingAttach)replica.update(d,d.source==own)}
                        "personal"->personalListeners.toList().forEach{it(m.getValue("update").jsonObject)}
                        else->error("Unknown account frame")
                    }
                } } finally {timeout.cancel();conn.close()}
            }catch(cancel:CancellationException){throw cancel}catch(_:Exception){}finally{pulse?.cancel();if(gen==life&&!closed){socket=null;if(allowed()&&detached==null)detached=server?.cursor;view.value=view.value.copy(connection=PlaybackConnectionState.DISCONNECTED);personalListeners.toList().forEach{it(null)}}}
        }.also{worker=it}
    }
    private fun presence(){val v=fields("type" to "presence","basis" to clock.monotonicMs());val base=detached;val a=actual?.cursor;send(if(awaitingAttach&&allowed()&&base!=null&&a!=null)JsonObject(v+fields("handle" to handle,"base" to playbackJson.encodeToJsonElement(base),"actual" to playbackJson.encodeToJsonElement(a)))else v)}
    private fun send(v:JsonObject):Boolean {val live=socket?:return false;if(closed||!current()||!live.isOpen)return false;return try{live.send(v.toString()).also{if(!it)live.close()}}catch(_:Exception){live.close();false}}
    private fun issue(choice:JsonObject):Boolean {
        val v=if(choice["shuffle"]?.jsonPrimitive?.booleanOrNull==true)JsonObject(choice+fields("seed" to kotlin.random.Random.nextLong(1L shl 32))) else choice
        if (!addressableAccountChoice(v)) return false
        if(allowed())replica.local(v)
        if(view.value.connection!=PlaybackConnectionState.READY)return false
        return send(v)
    }
    private fun action(type:String)=issue(fields("type" to type))
    fun pause()=action("pause");fun resume()=action("resume");fun toggle()=if(actual?.cursor?.desired==true)pause()else resume()
    fun next()=action("next");fun previous()=action("previous");fun stop()=action("close")
    fun seek(seconds:Double)=issue(fields("type" to "seek","value" to seconds));fun jump(key:Long)=issue(fields("type" to "jump","key" to key));fun remove(key:Long)=issue(fields("type" to "remove","key" to key));fun moveUpcoming(key:Long,after:Long?)=issue(fields("type" to "move","key" to key,"after" to (after?:0)))
    fun clearUpcoming()=action("clear");fun setShuffle(enabled:Boolean)=issue(fields("type" to "shuffle","shuffle" to enabled));fun toggleShuffle()=setShuffle(actual?.shuffle!=true);fun setRepeat(mode:String)=issue(fields("type" to "repeat","repeat" to mode));fun cycleRepeat()=setRepeat(LocalPlaybackQueue.nextRepeat(actual?.repeat))
    fun selectDevice(deviceId:String)=send(fields("type" to "select","deviceId" to deviceId));fun renameDevice(name:String,deviceId:String)=send(fields("type" to "rename","name" to name,"deviceId" to deviceId))
    private fun selection(track:PlaybackTrackReference?,ctx:PlaybackContext?)=buildJsonObject{put("kind",if(track==null)"collection" else "track");if(track!=null)put("track",playbackJson.encodeToJsonElement(track.copy(selectionToken=null)));if(ctx!=null)put("context",playbackJson.encodeToJsonElement(ctx.copy(revision=null,fence=null)))}
    fun playTrack(track:PlaybackTrackReference,context:PlaybackContext?=null)=issue(fields("type" to "play","selection" to selection(track,context)))
    fun playCollection(context:PlaybackContext,shuffle:Boolean=false)=issue(fields("type" to "play","selection" to selection(null,context),"shuffle" to shuffle))
    fun queueTrack(track:PlaybackTrackReference,context:PlaybackContext?=null,next:Boolean=false)=issue(fields("type" to "add","selection" to selection(track,context),"placement" to if(next)"next" else "low"))
    fun queueCollection(context:PlaybackContext,next:Boolean=false)=issue(fields("type" to "add","selection" to selection(null,context),"placement" to if(next)"next" else "low"))
    override fun close(){if(closed)return;replica.close();if(owner==own&&own.isNotEmpty()&&send(fields("type" to "release")))socket?.finish();closed=true;life++;expired?.cancel();worker?.cancel();socket?.close();socket=null;personalListeners.toList().forEach{it(null)};personalListeners.clear();view.value=view.value.copy(connection=PlaybackConnectionState.CLOSED)}
}
