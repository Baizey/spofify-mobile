import {createProgressTracker} from './progress-journal.js';
import {scopeAudio} from './audio-owner.js';
export function rotate(items,index) { return items.slice(index).concat(items.slice(0,index)); }
export function createLocalPlayer({audio,store,accountId,origin,onState=()=>{},notice=()=>{},locks=globalThis.navigator?.locks}) {
  const scope=scopeAudio(audio);audio=scope.audio;
  let queue=[],position=-1,repeat='off',shuffle=false,generation=0,reader=null,tracker=null,stopped=false,waiting=false;
  let unlock=null,lockHeld=false,requestLock=null,serial=0,error='',eventId='',removal=null,intent=0;
  const originalOrder=new Map(),pendingWrites=new Set();
  const snapshot=()=>({queue,position,track:queue[position]?.track || null,elapsed:audio.currentTime || 0,duration:Number.isFinite(audio.duration)?audio.duration:0,
    isPlaying:!audio.paused&&!audio.ended,repeat,shuffle,local:true,offline:true,error,occurrence:queue[position]?.key ? {eventId}:null});
  const emit=()=>onState(snapshot());
  const clearReader=()=>{audio.pause();audio.removeAttribute('src');audio.load();reader?.release();reader=null;tracker=null;};
  async function ownership() {
    if(lockHeld) return;
    if(!locks) throw new Error('Offline playback requires browser tab locking');
    if(requestLock) return requestLock;
    requestLock=new Promise((resolve,reject)=>{
      void locks.request('spofify-offline:'+JSON.stringify([origin,accountId]),{ifAvailable:true},async lock=>{
        if(stopped){reject(new Error('Player stopped'));return;}
        if(!lock){reject(new Error('Offline playback is open in another tab'));return;}
        lockHeld=true;resolve();await new Promise(done=>{unlock=done;});lockHeld=false;unlock=null;
      }).catch(reject);
    });
    try {await requestLock;} finally {requestLock=null;}
  }
  async function load(index,autoplay=true) {
    if(stopped||index<0||index>=queue.length)return;
    const version=++generation;intent++;removal=null;clearReader();position=index;eventId=crypto.randomUUID();waiting=false;error='';emit();
    try {
      await ownership();if(stopped||version!==generation)return;
      const item=queue[index], acquired=await store.acquire(item.track.id,item.mediaVersion);
      if(stopped||version!==generation){acquired.release();return;}
      reader=acquired;audio.src=acquired.url;audio.preload='auto';audio.load();
      tracker=createProgressTracker({trackId:item.track.id,eventId,append:payload=>store.appendReceipt(payload)});
      if(autoplay){await audio.play();if(stopped||version!==generation)return;}
      emit();
    }catch(e){if(version===generation&&!stopped){error=e.name==='NotAllowedError'?'Tap Play to continue':e.message;audio.pause();emit();notice(error);}}
  }
  const sample=()=>{
    if(!tracker||stopped)return;
    const write=tracker.observe({currentTime:audio.currentTime,playing:!audio.paused&&!audio.ended,seeking:audio.seeking,buffering:waiting});
    pendingWrites.add(write);
    void write.catch(()=>{error='Listening history could not be saved';notice(error);}).finally(()=>pendingWrites.delete(write));
    emit();
  };
  const events=[];
  for(const name of ['playing','pause','waiting','stalled','seeking','seeked','timeupdate','ended','error']){
    const fn=()=>{
      if(stopped)return;
      if(name==='waiting'||name==='stalled')waiting=true;
      if(['playing','pause','seeked','ended'].includes(name))waiting=false;
      if(name==='seeking'||name==='seeked')tracker?.resetObservation();
      sample();
      if(name==='error'&&audio.error){error='Downloaded audio could not play';notice(error);emit();}
      if(name==='ended'){
        if(repeat==='one')void load(position);
        else if(position+1<queue.length)void load(position+1);
        else if(repeat==='all'&&queue.length)void load(0);
      }
    };audio.addEventListener(name,fn);events.push([name,fn]);
  }
  const timer=setInterval(sample,250);
  async function resume(){
    if(audio.ended){await load(position);return false;}
    const version=generation;tracker?.resetObservation();await audio.play();
    return !stopped&&version===generation;
  }
  async function control(value){
    if(stopped)return;
    intent++;removal=null;
    switch(value.type){
      case 'toggle': if(audio.paused||audio.ended){if(!await resume())return;}else audio.pause();break;
      case 'pause':audio.pause();break;
      case 'resume':if(!await resume())return;break;
      case 'next':if(position+1<queue.length)await load(position+1);else{audio.pause();emit();}break;
      case 'previous':if(position>0)await load(position-1);break;
      case 'seek':tracker?.resetObservation();audio.currentTime=Math.max(0,Math.min(audio.duration||0,value.value));break;
      case 'repeat':repeat={off:'all',all:'one',one:'off'}[repeat];break;
      case 'shuffle':{
        shuffle=!shuffle;
        const past=queue.slice(0,position+1),up=queue.slice(position+1);
        if(shuffle)for(let i=up.length-1;i>0;i--){const j=Math.floor(Math.random()*(i+1));[up[i],up[j]]=[up[j],up[i]];}
        else up.sort((a,b)=>originalOrder.get(a.key)-originalOrder.get(b.key));
        queue=past.concat(up);break;
      }
      case 'jump':{const index=queue.findIndex(x=>x.key===value.key);if(index>=0)await load(index);break;}
      case 'remove':{const index=queue.findIndex(x=>x.key===value.key);if(index>position)queue=queue.filter(x=>x.key!==value.key);break;}
      case 'clear':queue=queue.slice(0,position+1);break;
      case 'close':++generation;clearReader();queue=[];position=-1;break;
    }emit();
  }
  return {snapshot,control,
    removeUpcoming(key){
      intent++;removal=null;
      const index=queue.findIndex(item=>item.key===key);if(index<=position)return null;
      const capability={id:crypto.randomUUID(),deadline:performance.now()+4000};
      removal={capability,item:queue[index],before:queue[index-1]?.key||null,after:queue[index+1]?.key||null,intent,eventId};
      queue=queue.filter(item=>item.key!==key);emit();return capability;
    },
    restoreRemoval(capability){
      const saved=removal;
      if(stopped||!saved||saved.capability!==capability||performance.now()>=capability.deadline||saved.intent!==intent||saved.eventId!==eventId)return false;
      removal=null;
      const before=queue.findIndex(item=>item.key===saved.before),after=saved.after===null?queue.length:queue.findIndex(item=>item.key===saved.after);
      if(before<position||after!==before+1||queue.some(item=>item.key===saved.item.key))return false;
      queue.splice(after,0,saved.item);intent++;emit();return true;
    },
    async flush(){sample();await Promise.all([...pendingWrites]);},
    async play(collection,index=0){
      if(stopped)throw new Error('Player stopped');
      if(!collection?.ready||!collection.items.length)throw new Error('Download is not ready');
      queue=rotate(collection.items,index).map(item=>({...item,key:'local:'+ ++serial}));
      originalOrder.clear();queue.forEach((item,i)=>originalOrder.set(item.key,i));shuffle=false;
      await load(0);
    },
    enqueue(item){intent++;removal=null;const entry={...item,key:'local:'+ ++serial};queue.push(entry);originalOrder.set(entry.key,serial);emit();},
    stop(){if(stopped)return;stopped=true;++generation;clearInterval(timer);events.forEach(([name,fn])=>audio.removeEventListener(name,fn));clearReader();scope.retire();unlock?.();}
  };
}
