const paths={history:'M12 4a8 8 0 1 1-7 4M4 3v6h6M12 7v5l3 2',search:'m20 20-5-5M17 10a7 7 0 1 1-14 0 7 7 0 0 1 14 0',playlists:'M8 5h13M8 12h13M8 19h13M3 5h.01M3 12h.01M3 19h.01',settings:'M12 8a4 4 0 1 1 0 8 4 4 0 0 1 0-8M10 3h4l1 3 3 1 3 3v4l-3 1-1 3-3 3h-4l-1-3-3-1-3-3v-4l3-1 1-3z',play:'m9 5 11 7-11 7z',pause:'M8 5v14M16 5v14',previous:'M5 5v14M19 5 8 12l11 7z',next:'M19 5v14M5 5l11 7-11 7z',shuffle:'M3 6h3c5 0 7 12 12 12h3m-4-4 4 4-4 4M3 18h3c2 0 3-2 4-4m4-4c1-2 2-4 4-4h3m-4-4 4 4-4 4',repeat:'M4 10V8a3 3 0 0 1 3-3h13m-4-3 4 3-4 3M20 14v2a3 3 0 0 1-3 3H4m4-3-4 3 4 3',devices:'M3 4h18v12H3zM8 20h8M12 16v4',queue:'M3 6h18M3 12h18M3 18h12',download:'M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5',check:'m5 12 4 4L20 5',more:'M5 12h.01M12 12h.01M19 12h.01',close:'m6 6 12 12M18 6 6 18',back:'m14 5-7 7 7 7',lyrics:'M4 4h16v13H9l-5 4zM8 8h8M8 12h6'};
export const icon=name=>`<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="${paths[name]||paths.more}"/></svg>`;
const bytes=text=>new TextEncoder().encode(text).length;
const validText=(text,max)=>typeof text==='string'&&bytes(text)<=max;
export function validateWaveform(value,trackId,duration){
  if(value?.version!==1||value.trackId!==trackId||value.algorithmVersion!=='peak-u8-two-pass-v1-half-up'||typeof value.sourceGeneration!=='string')return null;
  if(value.state!=='ready')return null;
  const {frames,sampleRate,channels,bins,durationSeconds}=value;
  if(!Number.isSafeInteger(frames)||frames<1||!Number.isSafeInteger(sampleRate)||sampleRate<1||sampleRate>192000||![1,2].includes(channels)||!Array.isArray(bins)||bins.length!==Math.min(frames,2048)||!bins.every(x=>Number.isInteger(x)&&x>=0&&x<=255)||!value.sourceGeneration||typeof value.productVersion!=='string'||!value.productVersion||!Number.isFinite(durationSeconds)||Math.abs(durationSeconds-frames/sampleRate)>1e-6)return null;
  if(!Number.isFinite(duration)||duration<=0||Math.abs(duration-durationSeconds)>Math.max(.25,durationSeconds/bins.length))return null;
  return value;
}
export function validateLyrics(value,trackId){
  if(value?.version!==1||value.trackId!==trackId||typeof value.recordingVersion!=='string'||typeof value.sourceGeneration!=='string'||value.offlineTransferAllowed!==false||!['pending','plain','timed','instrumental','not_found','unavailable','error'].includes(value.state)||!Array.isArray(value.lines)||value.lines.length>2048)return null;
  if(value.plainText!==undefined&&!validText(value.plainText,65536))return null;
  let prior=-1;
  for(const line of value.lines){if(!Number.isSafeInteger(line.startMs)||line.startMs<prior||line.startMs<0||line.startMs>86400000||!validText(line.text,4096))return null;prior=line.startMs;}
  if(value.state==='plain'&&(!value.plainText||value.lines.length))return null;
  if(value.state==='timed'&&(!value.lines.length||!value.recordingVersion||!value.sourceGeneration))return null;
  if(!['plain','timed'].includes(value.state)&&(value.lines.length||value.plainText))return null;
  if(value.attribution){const a=value.attribution;if(!validText(a.name,4096)||!['library','provider'].includes(a.ownership))return null;if(a.url){try{const url=new URL(a.url);if(url.protocol!=='https:'||url.search||url.hash||url.username||url.password)return null;}catch{return null;}}}
  return value;
}
export function currentLyric(value,elapsed){
  if(!value)return '';
  if(value.state==='plain')return value.plainText;
  if(value.state!=='timed'||!Number.isFinite(elapsed)||elapsed<0)return '';
  let selected='';for(const line of value.lines){if(line.startMs>elapsed*1000)break;selected=line.text;}return selected;
}
