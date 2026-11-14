import {createAudioAdapter} from './vendor/audio-adapter.js';
import {scopeAudio} from './audio-owner.js';
const finite = value => Number.isFinite(value) && value >= 0;
const positive = value => Number.isSafeInteger(value) && value > 0;
export function validateState(state) {
  if (state?.version !== 1 || !Array.isArray(state.queue) || !Array.isArray(state.highKeys) || !Number.isSafeInteger(state.position) ||
      (state.queue.length ? state.position < 0 || state.position >= state.queue.length : state.position !== -1) ||
      !['off','all','one'].includes(state.repeat) || !['shuffle','desiredPlaying','isPlaying','isBuffering','isLoading'].every(k => typeof state[k] === 'boolean') ||
      !finite(state.elapsed) || !finite(state.duration) || !['','blocked','media'].includes(state.error) || state.truncated !== false || typeof state.notice !== 'string') throw new Error('Invalid playback state');
  const keys = new Set(), orders = new Set();
  for (const item of state.queue) {
    if (!positive(item.key) || keys.has(item.key) || !Number.isSafeInteger(item.order) || item.order < 0 || orders.has(item.order) ||
        typeof item.id !== 'string' || !item.id || typeof item.name !== 'string' || !Array.isArray(item.artists) || !finite(item.duration) || !['low','high','next','now'].includes(item.intent)) throw new Error('Invalid queue');
    keys.add(item.key); orders.add(item.order);
  }
  const high = new Set(state.highKeys), expected = state.queue.slice(state.position+1).filter(item => high.has(item.key)).map(item => item.key);
  if (expected.length !== state.highKeys.length || expected.some((key,i) => key !== state.highKeys[i])) throw new Error('Invalid priority');
  const event = state.occurrence, selected = state.queue[state.position];
  if (event && (!selected || event.key !== selected.key || event.trackId !== selected.id || typeof event.eventId !== 'string' || !event.eventId || !finite(event.progress) || typeof event.reported !== 'boolean')) throw new Error('Invalid occurrence');
  return state;
}
export function createOnlinePlayer({audio,transport,onState=()=>{},onCoordination=()=>{},notice=()=>{}}) {
  const scope=scopeAudio(audio);audio=scope.audio;
  let factsListener = null, command = () => Promise.resolve({outcome:'rejected'}), state = null, playback = null, options = {}, coordination = {};
  const emit = () => onState(state ? {...state,...playback,track:state.queue[state.position] || null,local:options.local,name:options.name,coordination} : null);
  const adapter = createAudioAdapter({audio,onFacts:(facts,event)=>factsListener?.(facts,event)});
  const player = {canPlay:true, validateState,
    setSessionState(value,opts) {state=validateState(value); options=opts; playback=opts.playback; emit();},
    setSessionPlayback(value,opts) {playback=value; options=opts; emit();},
    setCoordinationState(value) {coordination=value; emit();},
    setFactListener(fn) {factsListener=fn;}, setCommandHandler(fn) {command=fn;},
    prepareAudio:adapter.prepare,executeAudio:adapter.execute,getActualFacts:adapter.facts,suspend:adapter.suspend};
  const auth={ready:Promise.resolve(true),accountId:transport.accountId,fetch:transport.request};
  const sync=window.SpofifyPlaybackSync.create({request:transport.request,player,auth,notice,deviceIdProvider:window.SpofifyPlaybackSync.browserDeviceId});
  const unsubscribe=sync.subscribe(onCoordination);
  return {control:value=>command(value),getSnapshot:()=>sync.getSnapshot(),retry:sync.retry,transfer:sync.transfer,rename:sync.rename,
    stop() {unsubscribe(); sync.stop(); adapter.stop(); scope.retire();}};
}
