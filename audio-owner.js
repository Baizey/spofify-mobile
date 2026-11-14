// Retired asynchronous HTMLMediaElement operations must not pause, seek, load
// or start a successor owner on the one shared element.
export function scopeAudio(audio){
  let live=true;
  const writes=new Set(['pause','load','removeAttribute','setAttribute']);
  const proxy=new Proxy(audio,{get(target,key){
    const value=Reflect.get(target,key,target);
    if(key==='play')return()=>live?target.play():Promise.reject(new DOMException('Retired audio owner','AbortError'));
    if(writes.has(key))return(...args)=>{if(live)return target[key](...args);};
    if(key==='addEventListener')return(...args)=>{if(live)return target.addEventListener(...args);};
    return typeof value==='function'?value.bind(target):value;
  },set(target,key,value){if(live)Reflect.set(target,key,value,target);return true;}});
  return {audio:proxy,retire(){live=false;}};
}
