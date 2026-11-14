import test from 'node:test';
import assert from 'node:assert/strict';
import {scopeAudio} from '../audio-owner.js';
import {validateState} from '../online-player.js';
test('retired native promises cannot pause, seek or replace the successor audio owner',async()=>{
  let pauses=0,loads=0,plays=0,finish;
  const native={src:'old',currentTime:0,pause(){pauses++;},load(){loads++;},play(){plays++;return new Promise(done=>{finish=done;});},addEventListener(){},removeEventListener(){},removeAttribute(){this.src='';}};
  const first=scopeAudio(native);const late=first.audio.play().then(()=>{first.audio.pause();first.audio.currentTime=0;first.audio.src='retired';});
  first.audio.pause();first.retire();const next=scopeAudio(native);next.audio.src='current';next.audio.currentTime=17;finish();await late;
  assert.equal(pauses,1);assert.equal(plays,1);assert.equal(loads,0);assert.equal(native.src,'current');assert.equal(native.currentTime,17);await assert.rejects(first.audio.play(),{name:'AbortError'});
});
const empty=()=>({version:1,queue:[],highKeys:[],position:-1,repeat:'off',shuffle:false,desiredPlaying:false,isPlaying:false,isBuffering:false,isLoading:false,elapsed:0,duration:0,error:'',truncated:false,notice:'',context:null,occurrence:null});
test('online presentation rejects malformed structural identity rather than inventing a queue',()=>{
  assert.equal(validateState(empty()).position,-1);
  for(const value of [{...empty(),position:0},{...empty(),truncated:true},{...empty(),repeat:'forever'},{...empty(),highKeys:[1]}])assert.throws(()=>validateState(value));
  const item={id:'track',key:1,order:0,name:'Example',artists:[],duration:0,intent:'now'};
  assert.equal(validateState({...empty(),queue:[item],position:0}).queue.length,1);
  assert.throws(()=>validateState({...empty(),queue:[item,item],position:0}));
});
