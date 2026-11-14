import test from 'node:test';
import assert from 'node:assert/strict';
import {createLocalPlayer,rotate} from '../local-player.js';
// Unit doubles only: these tests do not establish browser audio or durability.
class AudioDouble extends EventTarget{
  src='';currentTime=0;duration=8;paused=true;ended=false;seeking=false;error=null;
  pause(){this.paused=true;this.dispatchEvent(new Event('pause'));}
  play(){this.paused=false;this.dispatchEvent(new Event('playing'));return Promise.resolve();}
  load(){this.currentTime=0;}
  removeAttribute(name){if(name==='src')this.src='';}
}
const collection=()=>({ready:true,items:[1,2,3,4,5].map(i=>({occurrenceId:'o'+i,track:{id:'t'+i,name:'Track '+i,kind:'track',hasAudio:true},mediaVersion:'v'+i}))});
function setup({locks}={}){
  const audio=new AudioDouble(),readers=[],state=[];
  const store={async acquire(id){const record={id,released:false};readers.push(record);return {url:'blob:'+id,release(){record.released=true;}};},async appendReceipt(){throw new Error('No actual progress in this unit double');}};
  const player=createLocalPlayer({audio,store,accountId:'a',origin:'https://spofify.test',onState:value=>state.push(value),locks:locks||{request(_name,_options,fn){return fn({});}}});
  return {audio,player,readers,state};
}
test('selected third collection occurrence rotates one complete round; no autoplay on construction',async()=>{
  assert.deepEqual(rotate([1,2,3,4,5],2),[3,4,5,1,2]);
  const {audio,player,readers}=setup();
  try{assert.equal(audio.paused,true);assert.equal(readers.length,0);await player.play(collection(),2);assert.deepEqual(player.snapshot().queue.map(x=>x.track.id),['t3','t4','t5','t1','t2']);assert.equal(audio.src,'blob:t3');}finally{player.stop();}
});
test('remove/Undo restores exact local occurrence once under unchanged adjacent anchors',async()=>{
  const {player}=setup();try{await player.play(collection());const key=player.snapshot().queue[2].key,capability=player.removeUpcoming(key);assert.ok(capability);assert.equal(player.snapshot().queue.length,4);assert.equal(player.restoreRemoval(capability),true);assert.equal(player.snapshot().queue[2].key,key);assert.equal(player.restoreRemoval(capability),false);}finally{player.stop();}
});
test('only latest local removal is undoable; musical control invalidates old capability',async()=>{
  const {player}=setup();try{await player.play(collection());const keys=player.snapshot().queue.map(x=>x.key),first=player.removeUpcoming(keys[1]),second=player.removeUpcoming(keys[2]);assert.equal(player.restoreRemoval(first),false);assert.equal(player.restoreRemoval(second),true);const next=player.removeUpcoming(keys[3]);await player.control({type:'pause'});assert.equal(player.restoreRemoval(next),false);}finally{player.stop();}
});
test('local reader release and occurrence identity change on Next; repeat uses new play event',async()=>{
  const {audio,player,readers}=setup();try{await player.play(collection());const first=player.snapshot().occurrence.eventId;await player.control({type:'next'});assert.equal(readers[0].released,true);assert.notEqual(player.snapshot().occurrence.eventId,first);await player.control({type:'seek',value:4});assert.equal(audio.currentTime,4);assert.equal(player.snapshot().position,1);}finally{player.stop();assert.equal(readers.every(x=>x.released),true);}
});
test('late Web Lock acquisition after disposal does not retain an orphan owner',async()=>{
  let admit;const {player,readers}=setup({locks:{request(_name,_options,fn){return new Promise(resolve=>{admit=()=>resolve(fn({}));});}}});
  const pending=player.play(collection());player.stop();await admit();await pending;assert.equal(readers.length,0);
});
test('late fulfilled Play for A is inert after B starts within the same local owner',async()=>{
  let resolveA,entered;const playingA=new Promise(done=>{entered=done;});let calls=0;
  class DelayedAudio extends AudioDouble{play(){super.play();if(++calls===1){entered();return new Promise(done=>{resolveA=done;});}return Promise.resolve();}}
  const audio=new DelayedAudio(),readers=[],states=[];
  const store={async acquire(id){const lease={id,released:false};readers.push(lease);return {url:'blob:'+id,release(){lease.released=true;}};},async appendReceipt(){}};
  const player=createLocalPlayer({audio,store,accountId:'a',origin:'https://spofify.test',locks:{request(_n,_o,fn){return fn({});}},onState:s=>states.push(s)});
  try{
    const first=player.play(collection());await playingA;await player.control({type:'next'});
    const event=player.snapshot().occurrence.eventId,count=states.length;assert.equal(audio.src,'blob:t2');assert.equal(audio.paused,false);
    resolveA();await first;
    assert.equal(audio.paused,false);assert.equal(audio.src,'blob:t2');assert.equal(player.snapshot().occurrence.eventId,event);assert.equal(player.snapshot().position,1);assert.equal(readers[0].released,true);assert.equal(readers[1].released,false);assert.equal(states.length,count);
  }finally{player.stop();}
});
for(const command of ['toggle','resume'])test('ended '+command+' creates a new qualified traversal; ordinary pause/resume preserves identity',async()=>{
  // Unit-only media clock: position progresses continuously while playing, so
  // the real sampler observes progression rather than discrete seek-like jumps.
  class ReplayAudio extends AudioDouble{
    constructor(){super();let position=0,anchor=performance.now();Object.defineProperty(this,'currentTime',{get:()=>position+(this.paused||this.ended?0:(performance.now()-anchor)/1000),set:value=>{position=value;anchor=performance.now();}});}
    pause(){this.currentTime=this.currentTime;super.pause();}
    play(){this.currentTime=this.currentTime;return super.play();}
    load(){this.paused=true;this.ended=false;this.currentTime=0;}
  }
  const audio=new ReplayAudio(),receipts=[];
  const store={async acquire(){return {url:'blob:synthetic',release(){}};},async appendReceipt(payload){receipts.push(structuredClone(payload));}};
  const player=createLocalPlayer({audio,store,accountId:'a',origin:'https://spofify.test',locks:{request(_n,_o,fn){return fn({});}}});
  const advance=async()=>{await new Promise(done=>setTimeout(done,1300));audio.dispatchEvent(new Event('timeupdate'));await player.flush();};
  try{
    const single=collection();single.items=single.items.slice(0,1);await player.play(single);
    const first=player.snapshot().occurrence.eventId;await advance();assert.equal(receipts.length,1);assert.equal(receipts[0].eventId,first);
    await player.control({type:'pause'});await player.control({type:'resume'});assert.equal(player.snapshot().occurrence.eventId,first);await player.flush();assert.equal(receipts.length,1);
    audio.currentTime=8;audio.ended=true;audio.paused=true;audio.dispatchEvent(new Event('ended'));
    await player.control({type:command});const second=player.snapshot().occurrence.eventId;assert.notEqual(second,first);assert.ok(audio.currentTime<.1);
    await advance();assert.equal(receipts.length,2);assert.equal(receipts[1].eventId,second);assert.ok(receipts.every(p=>p.progressedMs>=1000&&p.progressedMs<=p.elapsedMs));
    await player.flush();assert.equal(receipts.length,2);
  }finally{player.stop();}
});
