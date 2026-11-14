import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {icon,validateWaveform,validateLyrics,currentLyric} from '../presentation.js';
import {FILES} from '../tools/release.js';
test('public release and service-worker lists contain every local module import',async()=>{
  const sw=await readFile(new URL('../sw.js',import.meta.url),'utf8');
  for(const file of FILES.filter(path=>path.endsWith('.js'))){
    const source=await readFile(new URL('../'+file,import.meta.url),'utf8');
    for(const match of source.matchAll(/from\s+['"]\.\/([^'"]+)['"]/g)){
      const parent=file.includes('/')?file.slice(0,file.lastIndexOf('/')+1):'';
      assert.ok(FILES.includes(parent+match[1]),'Missing release module '+match[1]);
    }
    assert.ok(sw.includes("'./"+file+"'"),'Missing worker shell module '+file);
  }
});
test('decorative waveform requires actual matching duration, generation and full bins',()=>{
  const value={version:1,trackId:'track',state:'ready',sourceGeneration:'source',algorithmVersion:'peak-u8-two-pass-v1-half-up',productVersion:'product',frames:4,sampleRate:4,channels:1,durationSeconds:1,bins:[0,100,128,255]};
  assert.equal(validateWaveform(value,'track',1),value);
  for(const candidate of [{...value,sourceGeneration:''},{...value,bins:[0]},{...value,bins:[0,1,2,256]}])assert.equal(validateWaveform(candidate,'track',1),null);
  assert.equal(validateWaveform(value,'other',1),null);assert.equal(validateWaveform(value,'track',4),null);
});
test('plain lyrics remain untimed; exact actual position chooses authored timed lines',()=>{
  const base={version:1,trackId:'track',recordingVersion:'recording',sourceGeneration:'source',offlineTransferAllowed:false};
  const plain={...base,state:'plain',plainText:'Synthetic fixture text',lines:[]};
  assert.equal(validateLyrics(plain,'track'),plain);assert.equal(currentLyric(plain,100),'Synthetic fixture text');
  const timed={...base,state:'timed',lines:[{startMs:500,text:'First'},{startMs:1000,text:'Second'},{startMs:2000,text:''}]};
  assert.equal(currentLyric(timed,.4),'');assert.equal(currentLyric(timed,1.1),'Second');assert.equal(currentLyric(timed,2.1),'');
  assert.equal(validateLyrics({...plain,lines:timed.lines},'track'),null);
});
test('lyric source helpers do not grant offline transfer or unsafe attribution',()=>{
  const value={version:1,trackId:'track',recordingVersion:'r',sourceGeneration:'s',state:'plain',plainText:'Synthetic fixture',lines:[],offlineTransferAllowed:false};
  assert.equal(validateLyrics({...value,offlineTransferAllowed:true},'track'),null);
  assert.equal(validateLyrics({...value,attribution:{name:'Source',ownership:'provider',url:'https://example.test/?query=private'}},'track'),null);
  assert.equal(validateLyrics(value,'another'),null);assert.match(icon('play'),/^<svg/);
});
