import {createTransport,StaleOperation} from './transport.js';
import {openOfflineStore,CACHE_STEPS,lookahead} from './offline-store.js';
import {createLocalPlayer} from './local-player.js';
import {createOnlinePlayer} from './online-player.js';
import {attachSwipe} from './gestures.js';
import fontLicense from './assets/license.js';
import {icon} from './presentation.js';
import {createAccountAccess} from './account-access.js';
const $=id=>document.getElementById(id), esc=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const origin=location.origin, admissionKey='spofify.web.admission.v1', audio=$('audio');
const pinned=collections=>collections.filter(collection=>collection.kind!=='temporary'&&collection.intent==='pinned');
const time=value=>Number.isFinite(value)&&value>=0?`${Math.floor(value/60)}:${String(Math.floor(value%60)).padStart(2,'0')}`:'—';
const credit=item=>(item?.artists||[]).map(x=>x.name).filter(Boolean).join(', ')||item?.albumName||'';
const notice=message=>{$('notice').textContent=message||'';$('notice').hidden=!message;};
let admission=null,identity=null,store=null,offline=false,tab='history',route=null,rows=[],saved=[],playerState=null,local=null,online=null,devices=null;
let pageEpoch=0,modeEpoch=0,download=null,palette=null,paletteUncertain=false,query='',searchTimer=null,undo=null,undoTimer=null,undoRequest=0,returnFocus=null;
const channel=typeof BroadcastChannel==='function'?new BroadcastChannel('spofify-auth'):null;
const transport=createTransport({origin,onInvalid:details=>{if(details?.verification){online?.stop();online=null;notice('Check your session');}else accountAccess.deactivate('Sign in again');}});
const accountAccess=createAccountAccess({origin,openStore:accountId=>openOfflineStore({origin,accountId}),onFence:deactivateUI,canPurge:target=>{const current=remembered();return !current||current.accountId===target.accountId&&current.incarnation===target.incarnation;}});
function remembered(){try{const value=JSON.parse(localStorage.getItem(admissionKey));return value?.origin===origin&&typeof value.accountId==='string'&&value.accountId&&typeof value.username==='string'?value:null;}catch{return null;}}
function remember(value){const json=JSON.stringify(value);localStorage.setItem(admissionKey,json);if(localStorage.getItem(admissionKey)!==json)throw new Error('Browser storage unavailable');admission=value;}
function accountSetting(name,value){const key='spofify.web.setting:'+JSON.stringify([origin,identity?.accountId,name]);if(value===undefined){try{return JSON.parse(localStorage.getItem(key));}catch{return null;}}localStorage.setItem(key,JSON.stringify(value));}
function clearUndo(){undoRequest++;undo=null;clearTimeout(undoTimer);if($('notice').querySelector('[data-action=undo]'))notice('');}
function cancelWork(){modeEpoch++;pageEpoch++;clearTimeout(searchTimer);download?.abort();download=null;cacheAbort?.abort();cacheKey='';clearUndo();document.documentElement.classList.remove('search-editing');}
function stopPlayers(){online?.stop();online=null;local?.stop();local=null;playerState=null;devices=null;audio.pause();audio.removeAttribute('src');audio.load();renderMini();}
function deactivateUI(message='Sign in again',target){
  cancelWork();transport.offline();stopPlayers();identity=null;admission=null;offline=false;
  store=null;saved=[];rows=[];palette=null;route=null;
  try{const current=remembered();if(!current||current.accountId===target.accountId&&current.incarnation===target.incarnation){localStorage.removeItem(admissionKey);localStorage.setItem('spofify.web.signed-out','true');}}catch{/* No retained private UI is exposed when browser storage is unavailable. */}
  if($('sheet').open)$('sheet').close();renderLogin();notice(message);
}
channel?.addEventListener('message',event=>accountAccess.broadcast(event.data));
function shell(){
  $('account-label').textContent=identity?.username||'';$('nav').hidden=!identity;$('mini').hidden=!identity;$('mode-bar').hidden=!offline;
  document.querySelectorAll('[data-tab]').forEach(b=>b.setAttribute('aria-current',b.dataset.tab===tab?'page':'false'));
  $('back').hidden=!route;$('header').querySelector('.brand').hidden=!!route;
  document.documentElement.dataset.theme=accountSetting('theme')||'dark';document.documentElement.dataset.palette=palette?.paletteId||accountSetting('palette')?.paletteId||'jade';
}
function renderLogin(){
  $('nav').hidden=$('mini').hidden=true;$('mode-bar').hidden=true;$('account-label').textContent='';$('back').hidden=true;
  $('content').innerHTML=`<h1>Sign in</h1><form id="login-form"><label class="field"><span>Server</span><input name="server" type="url" readonly value="${esc(origin)}"></label><label class="field"><span>Username</span><input name="username" autocomplete="username" required maxlength="128"></label><label class="field"><span>Password</span><input id="password" name="password" type="password" autocomplete="current-password" required></label><button type="button" data-action="reveal" aria-controls="password">Show password</button><button class="primary wide" type="submit">Sign in</button></form>`;
}
async function authenticate(credentials){
  const previous=remembered(),ticket=accountAccess.beginAdmission();
  const epoch=modeEpoch;
  const live=()=>{if(epoch!==modeEpoch)throw new StaleOperation();};
  transport.online();
  if(credentials)await transport.json('/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json','X-Spofify-Login':'1'},body:JSON.stringify(credentials)});
  const session=await transport.json('/api/auth/session');live();
  live();identity=transport.bind(session);
  const candidate=await openOfflineStore({origin,accountId:identity.accountId});
  if(epoch!==modeEpoch){candidate.close();throw new StaleOperation();}
  const incarnation=!credentials&&previous?.accountId===identity.accountId&&previous.incarnation||crypto.randomUUID();
  accountAccess.admit({accountId:identity.accountId,incarnation},candidate,ticket);store=candidate;
  remember({origin,accountId:identity.accountId,username:identity.username,incarnation,offline:false});localStorage.removeItem('spofify.web.signed-out');offline=false;
  saved=pinned(await store.list());live();shell();await renderPage();
  void loadPalette();void setupWorker();
}
async function enterOffline(){
  if(!identity||!store)return;
  if(!navigator.serviceWorker?.controller)throw new Error('Offline app is not ready. Wait, then try again');
  // Commit the durable mode BEFORE fencing network; failed storage must not
  // silently turn the next cold launch into an online request.
  remember({origin,accountId:identity.accountId,username:identity.username,incarnation:accountAccess.current().incarnation,offline:true});
  cancelWork();const epoch=modeEpoch;transport.offline();stopPlayers();offline=true;route=null;const collections=await store.list();if(epoch!==modeEpoch)return;saved=pinned(collections);shell();await renderPage();notice('');
}
async function leaveOffline(){
  audio.pause();await local?.flush();
  cancelWork();stopPlayers();transport.online();
  try{
    const session=await transport.json('/api/auth/session');
    if(session.accountId!==identity?.accountId){accountAccess.deactivate('Account changed. Sign in again');return;}
    identity=transport.bind(session);offline=false;remember({origin,accountId:identity.accountId,username:identity.username,incarnation:accountAccess.current().incarnation,offline:false});
    shell();await renderPage();notice('');
    // Historical uploads are not online commands. They never claim an output,
    // publish the local queue, or start audio. Payloads are immutable in storage.
    const upload=await store.uploadReceipts(transport.request);
    if(upload.diagnostic)notice(upload.diagnostic.status===422?'Check the device clock':'Some plays could not sync');
    await loadPalette();
  }catch(error){if(identity){transport.offline();offline=true;remember({...admission,offline:true});shell();notice(error.status===422?'Check the device clock':'Could not reconnect');}}
}
async function setupWorker(){
  if(!('serviceWorker'in navigator)||offline)return;
  try{
    const registration=await navigator.serviceWorker.register('./sw.js',{scope:'./',updateViaCache:'none'});
    const update=()=>{if(registration.waiting&&navigator.serviceWorker.controller&&!offline){$('notice').innerHTML='Update ready <button data-action="update">Restart</button>'; $('notice').hidden=false;}};
    registration.addEventListener('updatefound',()=>registration.installing?.addEventListener('statechange',update));update();
  }catch{notice('Offline app installation unavailable');}
}
async function loadPalette(){
  if(offline||!identity)return;
  const epoch=modeEpoch,account=identity.accountId;
  try{const value=await transport.json('/api/me/palette');if(epoch!==modeEpoch||identity?.accountId!==account)return;
    if(value.accountId!==account||value.catalogVersion!==1||!['jade','ember','violet'].includes(value.paletteId)||!Number.isSafeInteger(value.revision)||value.revision<0)throw new Error('Palette unavailable');
    palette=value;paletteUncertain=false;accountSetting('palette',value);shell();
  }catch{if(epoch===modeEpoch)paletteUncertain=true;}
}
async function choosePalette(id){
  if(offline||paletteUncertain||!palette)return;
  const epoch=modeEpoch,account=identity.accountId,previous=palette;paletteUncertain=true;
  try{const value=await transport.json('/api/me/palette',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({paletteId:id,expectedRevision:previous.revision,catalogVersion:1})});
    if(epoch!==modeEpoch||identity?.accountId!==account)return;
    if(value.accountId!==account||value.paletteId!==id||value.catalogVersion!==1)throw new Error('Palette response unavailable');
    palette=value;accountSetting('palette',value);paletteUncertain=false;shell();await renderPage();
  }catch{if(epoch===modeEpoch){notice('Check account colours before choosing again');await loadPalette();await renderPage();}}
}
function artMarkup(item){return !offline&&item?.id&&['artist','album','track'].includes(item.kind||'track')?`<img src="/api/library/${(item.kind||'track')+'s'}/${esc(encodeURIComponent(item.id))}/artwork" alt="" loading="lazy" decoding="async">`:'';}
function rowHTML(record,index,{remove=false}={}){
  const item=record.track||record,kind=item.kind||record.kind||'track';
  return `<div class="row" data-row="${index}"><button class="row-body" data-row-open="${index}" aria-label="${esc((kind==='track'?'Play ':'Open ')+item.name)}"><span class="art sm ${kind==='artist'?'artist':''}" aria-hidden="true">${artMarkup(item)}</span><span class="identity"><strong>${esc(item.name||'Unavailable')}</strong><small>${esc(credit(item)||(['manual','derived','playlist'].includes(kind)?'Playlist':kind==='track'?'':kind))}</small></span></button><button class="icon" data-row-menu="${index}" aria-label="More for ${esc(item.name||'this occurrence')}">${icon('more')}</button>${remove?`<button data-queue-remove="${esc(record.key)}" aria-label="Remove ${esc(item.name)} from queue">−</button>`:''}</div>`;
}
function paintRows(records,container='rows'){rows=records;const root=$(container);if(root)root.innerHTML=records.length?records.map((record,i)=>rowHTML(record,i)).join(''):'<p class="empty">No music here</p>';attachRowGestures();}
async function renderPage(){
  if(!identity)return;const epoch=++pageEpoch;shell();
  if(route){await renderDetail(epoch);return;}
  if(tab==='settings'){await renderSettings(epoch);return;}
  if(tab==='search'){
    $('content').innerHTML=`<section class="search-page"><h1>Search</h1><div id="rows" class="search-results"></div><form id="search-form" class="search-field" role="search"><label class="small" for="query">Search music</label><input id="query" type="search" maxlength="200" autocomplete="off" value="${esc(query)}" placeholder="Artists, albums, songs…"></form></section>`;
    if(query.trim())await runSearch(epoch);else{$('rows').innerHTML='';rows=[];}return;
  }
  $('content').innerHTML=`<h1>${tab==='history'?'History':'Playlists'}</h1><div id="rows" class="rows"></div><button id="more" class="wide" hidden>More</button>`;
  try{
    saved=pinned(await store.list());if(epoch!==pageEpoch)return;
    if(offline){
      if(tab==='playlists')paintRows(saved.map(collection=>({...collection,kind:collection.kind,name:collection.name,hasAudio:true,saved:true})));
      else {const history=await store.localHistory();if(epoch!==pageEpoch)return;const tracks=new Map(saved.flatMap(c=>c.items.map(x=>[x.track.id,x.track])));paintRows(history.map(record=>({track:tracks.get(record.payload.trackId)||{id:record.payload.trackId,name:'Unavailable',kind:'track'},receipt:record.payload})).filter(x=>x.track.hasAudio));}
    }else{
      const path=tab==='history'?'/api/me/history':'/api/me/playlists';
      const value=await transport.json(path+'?limit=40&offset=0');if(epoch!==pageEpoch)return;
      const available=value.items.filter(x=>tab!=='history'||x.track?.hasAudio===true);
      if(tab==='playlists'){
        const serverIds=new Set(available.map(x=>x.id));available.push(...saved.filter(x=>x.kind!=='playlist'||!serverIds.has(x.id)).map(x=>({...x,saved:true})));
      }paintRows(available);
      if(value.total>value.items.length){$('more').hidden=false;$('more').onclick=()=>void moreRows(path,value.items.length,epoch);}
    }
  }catch(error){if(epoch===pageEpoch){$('rows').innerHTML='<p class="empty">Could not load music</p><button data-action="reload">Retry</button>';notice(error.message);}}
}
async function moreRows(path,offset,epoch){
  try{const value=await transport.json(path+`?limit=40&offset=${offset}`);if(epoch!==pageEpoch)return;
    paintRows(rows.concat(value.items.filter(x=>tab!=='history'||x.track?.hasAudio===true)));
    $('more').hidden=offset+value.items.length>=value.total;$('more').onclick=()=>void moreRows(path,offset+value.items.length,epoch);
  }catch{if(epoch===pageEpoch)notice('Could not load more music');}
}
async function runSearch(epoch=pageEpoch){
  if(!query.trim()){$('rows').innerHTML='';rows=[];return;}
  try{
    if(offline){const collections=pinned(await store.list());if(epoch!==pageEpoch)return;
      const needle=query.toLocaleLowerCase(),seen=new Set();const matches=collections.flatMap(c=>c.items.map(item=>({...item,collection:c}))).filter(x=>{if(seen.has(x.track.id)||!(`${x.track.name} ${credit(x.track)}`.toLocaleLowerCase().includes(needle)))return false;seen.add(x.track.id);return true;});paintRows(matches);return;}
    // Source order is retained; nearest-bottom strongest search ordering is a
    // presentation reversal, never invented numeric rank/popularity.
    const value=await transport.json('/api/library?kind=all&availability=in-library&limit=40&offset=0&q='+encodeURIComponent(query.trim()));
    if(epoch!==pageEpoch)return;paintRows(['artist','album','track'].flatMap(kind=>value.items.filter(item=>item.kind===kind).reverse()));
  }catch{if(epoch===pageEpoch)$('rows').innerHTML='<p class="empty">Search unavailable</p>';}
}
async function openRecord(record){
  const item=record.track||record;
  if(item.kind==='track'||record.track){
    if(offline){const collection=record.collection||saved.find(c=>c.items.some(x=>x.track.id===item.id));if(!collection)throw new Error('Download unavailable');const index=collection.items.findIndex(x=>record.occurrenceId?x.occurrenceId===record.occurrenceId:x.track.id===item.id);await playLocal(collection,index);}
    else await playOnline({type:'play',track:item,entryId:route?.kind==='playlist'&&route.playlistKind==='manual'?record.id:undefined,occurrenceId:record.occurrenceId,selectionToken:record.selectionToken,context:route?.context});
  }else{route={...item,kind:['manual','derived'].includes(item.kind)?'playlist':item.kind,playlistKind:item.kind,context:contextOf(item)};await renderPage();$('content').scrollTop=0;}
}
function contextOf(item){return {kind:item.kind==='manual'?'playlist':item.kind,id:item.id,name:item.name,...(item.revision?{revision:item.revision}:{}),...(item.fence?{fence:item.fence}:{})};}
async function renderDetail(epoch){
  const target=route, installed=await store.get(target.kind,target.id);if(epoch!==pageEpoch)return;
  $('content').innerHTML=`<section class="collection-head"><span class="art ${target.kind==='artist'?'artist':''}" aria-hidden="true"></span><div><h1>${esc(target.name)}</h1><p class="small">${esc(target.kind==='playlist'?'Playlist':target.kind)}</p></div></section><div class="collection-controls"><button class="primary" data-action="collection-play" aria-label="Play collection">▶</button><button class="icon" data-action="download" aria-label="${installed?'Update download':'Download collection'}">${installed?'✓':'⇩'}</button><button class="icon" data-action="collection-menu" aria-label="Collection actions">⋯</button></div><div id="download-state" class="download-state">${installed?'Downloaded · '+installed.items.length+' songs':''}</div><label class="small" for="filter">Filter songs</label><input id="filter" class="filter" type="search" placeholder="Filter"><div id="rows" class="rows"></div><button id="more" class="wide" hidden>More</button>`;
  try{
    if(offline){if(!installed){paintRows([]);return;}route.installed=installed;paintRows(installed.items);return;}
    if(target.kind==='playlist'){
      const page=await transport.json('/api/me/playlists/'+encodeURIComponent(target.id)+'/entries?limit=40'+(target.playlistKind==='derived'?'':'&offset=0'));if(epoch!==pageEpoch)return;
      if(page.fence)route.context={...route.context,fence:page.fence,revision:page.revision};
      paintRows(page.items.map(x=>({...x,track:x.track,entryId:target.playlistKind==='manual'?x.id:undefined})));
      const next=target.playlistKind==='derived'?page.nextCursor:page.total>page.items.length?page.items.length:null;
      if(next!==null&&next!==undefined){$('more').hidden=false;$('more').onclick=()=>void moreDetail(next,epoch);}
    }else{
      const param=target.kind==='album'?'albumId':'artistId';
      const page=await transport.json(`/api/library?kind=${target.kind==='artist'?'albums':'tracks'}&availability=in-library&limit=40&offset=0&${param}=${encodeURIComponent(target.id)}`);if(epoch!==pageEpoch)return;
      route.context={...route.context,revision:page.revision};paintRows(page.items);
      if(page.total>page.items.length){$('more').hidden=false;$('more').onclick=()=>void moreDetail(page.items.length,epoch);}
    }
  }catch{if(epoch===pageEpoch)notice('Could not load collection');}
}
async function moreDetail(offset,epoch){
  const target=route;let path;
  if(target.kind==='playlist')path=`/api/me/playlists/${encodeURIComponent(target.id)}/entries?limit=40&${target.playlistKind==='derived'?'cursor='+encodeURIComponent(offset):'offset='+offset}`;
  else path=`/api/library?kind=${target.kind==='artist'?'albums':'tracks'}&availability=in-library&limit=40&offset=${offset}&${target.kind==='album'?'albumId':'artistId'}=${encodeURIComponent(target.id)}`;
  try{const page=await transport.json(path);if(epoch!==pageEpoch)return;
    if(page.fence&&route.context.fence!==page.fence)throw new Error('Collection changed');
    paintRows(rows.concat(page.items));
    const next=target.playlistKind==='derived'?page.nextCursor:offset+page.items.length<page.total?offset+page.items.length:null;
    $('more').hidden=next===null||next===undefined;$('more').onclick=()=>void moreDetail(next,epoch);
  }catch{if(epoch===pageEpoch)notice('Collection changed. Reload before continuing');}
}
async function renderSettings(epoch){
  const pending=await store.receiptCounts(),capacity=await store.estimate();if(epoch!==pageEpoch)return;
  const count=accountSetting('cacheCount')||2;
  $('content').innerHTML=`<h1>Settings</h1><button class="preference" data-action="account"><span>Account<small>${esc(identity.username)}</small></span><span>›</span></button><button class="preference" data-action="${offline?'online':'offline'}" role="switch" aria-checked="${offline}"><span>Offline mode</span><span>${offline?'On':'Off'}</span></button><label class="field"><span>Temporary cache · <output id="cache-value">${count}</output> songs</span><input id="cache-count" type="range" min="0" max="4" step="1" value="${Math.max(0,CACHE_STEPS.indexOf(count))}" aria-label="Temporary cache count"></label><button class="preference" data-action="theme"><span>Light theme</span><span>${document.documentElement.dataset.theme==='light'?'On':'Off'}</span></button><button class="preference" data-action="colours"><span>Account colours</span><span>›</span></button><h2>Downloads</h2><p class="small">${saved.length} collections · ${pending.pending} pending plays</p><p class="small">${capacity.persisted?'Persistent storage granted':'Storage may be evicted'}${Number.isFinite(capacity.usage)?' · '+Math.round(capacity.usage/1048576)+' MiB used':''}</p><button class="preference" data-action="persistence"><span>Request persistent storage</span></button><button class="preference" data-action="credits"><span>Credits</span><span>›</span></button>`;
}
async function beginDownload(){
  if(offline||!route||download)return;const target={kind:route.kind,id:route.id,name:route.name},epoch=modeEpoch;
  const abort=download=new AbortController();
  try{
    $('download-state').textContent='Downloading…';
    await store.save(target,transport.request,{intent:'pinned',signal:abort.signal,onProgress:progress=>{if(epoch===modeEpoch&&$('download-state'))$('download-state').textContent=typeof progress==='string'?progress:`Downloading${Number.isFinite(progress.receivedBytes)?' · '+progress.receivedBytes+' bytes':''}`;}});
    if(epoch!==modeEpoch)return;saved=pinned(await store.list());notice('');await renderPage();
  }catch(error){if(epoch===modeEpoch&&$('download-state'))$('download-state').textContent=error.name==='AbortError'?'Download cancelled':'Download failed · '+error.message;}
  finally{if(download===abort)download=null;}
}
let cacheAbort=null,cacheKey='';
function playerChanged(value){playerState=value;renderMini();if($('sheet').open&&$('sheet').classList.contains('player-view'))updatePlayerView();
  if(value?.isBuffering||value?.isLoading){cacheAbort?.abort();cacheKey='';}
  if(!offline&&value?.local&&value.track&&value.isPlaying&&!value.isBuffering&&!value.isLoading&&!download){
    const count=accountSetting('cacheCount')||2;
    const tracks=value.queue.slice(value.position,value.position+1+lookahead(count)).map(item=>({...(item.track||item),kind:'track',hasAudio:true}));
    const key=JSON.stringify([identity?.accountId,...tracks.map(track=>track.id)]);
    if(key!==cacheKey){cacheKey=key;cacheAbort?.abort();const abort=cacheAbort=new AbortController();
      void store.cacheTracks(tracks,transport.request,{signal:abort.signal}).then(()=>store.trimTemporary(count)).catch(()=>{});
    }
  }
}
function renderMini(){
  const track=playerState?.track;$('mini-title').textContent=track?.name||'No song selected';$('mini-credit').textContent=credit(track);
  const art=$('mini-identity').querySelector('.art'),key=JSON.stringify([offline,track?.id]);if(art.dataset.key!==key){art.dataset.key=key;art.innerHTML=artMarkup(track);}
  $('mini-identity').disabled=!track;$('mini-toggle').disabled=!track;$('mini-toggle').innerHTML=icon(playerState?.isPlaying?'pause':'play');$('mini-toggle').setAttribute('aria-label',playerState?.isPlaying?'Pause':'Play');
}
async function playLocal(collection,index){
  online?.stop();online=null;
  if(!local)local=createLocalPlayer({audio,store,accountId:identity.accountId,origin,onState:playerChanged,notice});
  await local.play(collection,index);
}
async function playOnline(value){
  if(offline)throw new Error('Offline mode');
  if(!online){
    local?.stop();local=null;online=createOnlinePlayer({audio,transport,onState:playerChanged,onCoordination:value=>{devices=value;if(value.phase!=='ready')clearUndo();},notice});
    notice('Connecting playback. Tap Play when connected');return;
  }
  clearUndo();const result=await online.control(value);
  if(result?.execution==='uncertain')notice('Playback unconfirmed. Check before trying again');
}
async function control(type,value={}){if(offline){await local?.control({type,...value});}else await playOnline({type,...value});}
function openSheet(title,html,player=false){
  document.documentElement.classList.remove('search-editing');returnFocus=document.activeElement;
  $('sheet').classList.toggle('player-view',player);$('sheet-title').textContent=title;$('sheet-body').innerHTML=html;
  if(!$('sheet').open)$('sheet').showModal();
  if(player){const zone=$('sheet').querySelector('.sheet-head');attachSwipe(zone,{binding:()=>({epoch:modeEpoch}),valid:b=>b.epoch===modeEpoch,onCommit:direction=>{if(direction==='down')dismiss();},axis:'y'});}
}
function dismiss(){if($('sheet').open)$('sheet').close();}
$('sheet').addEventListener('close',()=>{const target=returnFocus;returnFocus=null;target?.isConnected&&target.focus();});
function openPlayer(){
  if(!playerState?.track)return;
  openSheet('Now playing',`<div class="art player-cover" aria-hidden="true"></div><div class="lyrics" id="lyrics"></div><section class="player-identity"><h2 id="player-title"></h2><p id="player-credit" class="small"></p></section><input id="seek" class="seek" type="range" min="0" max="0" step=".1" aria-label="Seek"><div class="times"><span id="elapsed"></span><span id="duration"></span></div><div class="transport"><button class="icon" data-action="shuffle" aria-label="Shuffle">⤨</button><button class="icon" data-action="previous" aria-label="Previous">|◀</button><button id="player-toggle" class="icon play" data-action="toggle" aria-label="Play">▶</button><button class="icon" data-action="next" aria-label="Next">▶|</button><button id="repeat" class="icon" data-action="repeat" aria-label="Repeat off">↻</button></div><div class="player-tools"><button class="icon" data-action="devices" aria-label="Devices">▣</button><button class="icon" data-action="queue" aria-label="Queue">☷</button></div>`,true);updatePlayerView();
}
function updatePlayerView(){
  if(!$('seek'))return;const state=playerState,track=state?.track;
  $('player-title').textContent=track?.name||'';$('player-credit').textContent=credit(track);$('elapsed').textContent=time(state?.elapsed);$('duration').textContent=state?.duration?time(state.duration):'—';
  $('seek').max=state?.duration||0;if(document.activeElement!==$('seek'))$('seek').value=state?.elapsed||0;$('seek').disabled=!state?.duration;
  $('player-toggle').textContent=state?.isPlaying?'Ⅱ':'▶';$('player-toggle').setAttribute('aria-label',state?.isPlaying?'Pause':'Play');$('repeat').setAttribute('aria-label','Repeat '+(state?.repeat||'off'));$('repeat').setAttribute('aria-pressed',String(state?.repeat!=='off'));$('repeat').textContent=state?.repeat==='one'?'↻¹':'↻';
}
function openQueue(){
  const state=playerState;if(!state)return;const upcoming=state.queue.slice(state.position+1);
  openSheet('Queue',`<p class="small">${esc(state.track?.name||'')}</p><div id="queue-rows">${upcoming.map((item,i)=>{const track=item.track||item;return `<div class="row"><button class="row-body" data-jump="${esc(item.key)}"><span class="identity"><strong>${esc(track.name)}</strong><small>${esc(credit(track))}</small></span></button><button data-remove="${esc(item.key)}" aria-label="Remove ${esc(track.name)}">−</button></div>`;}).join('')}</div><button class="wide" data-action="clear">Clear upcoming</button>`);
}
async function removeUpcoming(key){
  if(offline){clearUndo();const capability=local?.removeUpcoming(key);openQueue();if(capability){undo={local:true,capability,epoch:modeEpoch};$('notice').innerHTML='Removed <button data-action="undo" class="undo">Undo</button>';$('notice').hidden=false;undoTimer=setTimeout(clearUndo,Math.max(0,capability.deadline-performance.now()));}return;}
  if(!online)return;clearUndo();const started=performance.now(),epoch=modeEpoch,sequence=undoRequest,result=await online.control({type:'remove',key:Number(key),undo:true});
  if(epoch!==modeEpoch||sequence!==undoRequest)return;openQueue();
  const capability=result?.undo,remaining=Math.min(4000-(performance.now()-started),(capability?.expiresAtMs||0)-Date.now());
  if(result.outcome==='accepted'&&typeof capability?.token==='string'&&remaining>0){undo={token:capability.token,epoch};$('notice').innerHTML='Removed <button data-action="undo" class="undo">Undo</button>';$('notice').hidden=false;undoTimer=setTimeout(clearUndo,remaining);}
}
function attachRowGestures(){document.querySelectorAll('[data-row]').forEach(el=>attachSwipe(el,{binding:()=>({epoch:pageEpoch,record:rows[Number(el.dataset.row)]}),valid:b=>b.epoch===pageEpoch,
  onCommit:async(direction,b)=>{if(direction==='right')await queueRecord(b.record);},axis:'x'}));}
async function queueRecord(record){
  if(offline){const item=record.track?record:saved.flatMap(c=>c.items).find(x=>x.track.id===record.id);if(item)local?.enqueue(item);}
  else await playOnline({type:'queueIntent',intent:record.track||record.kind==='track'?'high':'low',selection:record.track||record.kind==='track'?{kind:'track',track:{...(record.track||record),...(record.entryId?{entryId:record.entryId}:{})},...(route?.context?{context:route.context}:{})}:{kind:'collection',context:contextOf(record)}});
}
async function action(name){
  switch(name){
    case 'offline':await enterOffline();break;case 'online':await leaveOffline();break;
    case 'reload':notice('');await renderPage();break;case 'back':route=null;await renderPage();break;
    case 'reveal':$('password').type=$('password').type==='password'?'text':'password';document.querySelector('[data-action=reveal]').textContent=$('password').type==='password'?'Show password':'Hide password';break;
    case 'player':openPlayer();break;case 'dismiss':dismiss();break;case 'queue':openQueue();break;
    case 'toggle':case 'next':case 'previous':case 'repeat':case 'shuffle':case 'clear':await control(name);if(name==='clear')openQueue();break;
    case 'download':await beginDownload();break;
    case 'collection-play':if(offline){const collection=await store.get(route.kind,route.id);await playLocal(collection,0);}else await playOnline({type:'playCollection',context:route.context});break;
    case 'collection-menu':openSheet(route.name,`<button class="action-row" data-action="download" ${offline?'disabled':''}>Download / Update</button><button class="action-row" data-action="remove-download">Remove download</button>${download?'<button class="action-row" data-action="cancel-download">Cancel download</button>':''}`);break;
    case 'cancel-download':download?.abort();dismiss();break;
    case 'remove-download':if(confirm('Remove downloaded collection?')){await store.remove(route.kind,route.id);saved=pinned(await store.list());dismiss();await renderPage();}break;
    case 'theme':accountSetting('theme',document.documentElement.dataset.theme==='light'?'dark':'light');shell();await renderPage();break;
    case 'persistence':await store.requestPersistence();await renderPage();break;
    case 'account':openSheet('Account',`<p>${esc(identity.username)}</p><p class="small">${esc(origin)}</p><button class="action-row" data-action="signout">Sign out</button>`);break;
    case 'signout':{
      const target=accountAccess.current();
      if(!target||!confirm('Sign out and remove this account’s downloads and pending plays?'))break;
      let message='Signed out';
      if(offline)message='Signed out locally. Server session may remain';
      else try{const response=await transport.request('/api/auth/logout',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'});if(!response.ok)throw new Error();}
      catch{message='Local data removed. Server sign-out unconfirmed';}
      const purged=await accountAccess.purgeConfirmed(target,{confirmed:true,message});
      if(purged&&!accountAccess.current())notice(message);
      if(purged)channel?.postMessage({type:'logout',origin,accountId:target.accountId,incarnation:target.incarnation});break;
    }
    case 'colours':openSheet('Account colours',`${offline?'<p class="small">Offline · last confirmed</p>':''}${['jade','ember','violet'].map(id=>`<button class="action-row" data-palette="${id}" ${offline||paletteUncertain?'disabled':''} aria-pressed="${(palette?.paletteId||accountSetting('palette')?.paletteId)===id}">${id[0].toUpperCase()+id.slice(1)}</button>`).join('')}`);break;
    case 'credits':openSheet('Credits','<button class="action-row" data-action="license">JetBrains Mono · SIL OFL 1.1</button>');break;
    case 'license':openSheet('JetBrains Mono','<pre class="license">'+esc(fontLicense)+'</pre>');break;
    case 'devices':openSheet('Devices',offline?'<p class="empty">Offline · this device</p>':`${devices?.devices?.map(d=>`<button class="action-row" data-device="${esc(d.id)}">${esc(d.name)}${d.id===devices.activeDeviceId?' · selected':''}</button>`).join('')||'<button class="action-row" data-action="connect-playback">Connect playback</button>'}`);break;
    case 'connect-playback':await playOnline({type:'pause'});dismiss();break;
    case 'undo':{const capability=undo;clearUndo();if(capability&&capability.epoch===modeEpoch){if(capability.local)local?.restoreRemoval(capability.capability);else await online?.control({type:'undoRemove',token:capability.token});}openQueue();break;}
    case 'update':if(confirm('Restart to update? Playback will stop.')){stopPlayers();const registration=await navigator.serviceWorker.getRegistration('./');registration?.waiting?.postMessage({type:'activate'});navigator.serviceWorker.addEventListener('controllerchange',()=>location.reload(),{once:true});}break;
  }
}
document.addEventListener('error',event=>{if(event.target instanceof HTMLImageElement&&event.target.closest('#shell, #sheet'))event.target.remove();},true);
document.addEventListener('click',event=>{
  const el=event.target.closest('button');if(!el||el.disabled)return;
  void(async()=>{
    if(el.dataset.tab){pageEpoch++;clearTimeout(searchTimer);document.documentElement.classList.remove('search-editing');tab=el.dataset.tab;route=null;if(tab==='search')query='';await renderPage();if(tab==='search')$('query')?.focus();return;}
    if(el.dataset.action){await action(el.dataset.action);return;}
    if(el.dataset.rowOpen!==undefined){await openRecord(rows[Number(el.dataset.rowOpen)]);return;}
    if(el.dataset.rowMenu!==undefined){const record=rows[Number(el.dataset.rowMenu)];openSheet((record.track||record).name,'<button class="action-row" id="queue-record">Queue</button>');$('queue-record').onclick=()=>{void queueRecord(record).catch(e=>notice(e.message));dismiss();};return;}
    if(el.dataset.palette){dismiss();await choosePalette(el.dataset.palette);return;}
    if(el.dataset.device){await online?.transfer(el.dataset.device);dismiss();return;}
    if(el.dataset.remove){await removeUpcoming(el.dataset.remove);return;}
    if(el.dataset.jump){await control('jump',{key:offline?el.dataset.jump:Number(el.dataset.jump)});dismiss();}
  })().catch(error=>notice(error.message||'Action unavailable'));
});
document.addEventListener('submit',event=>{
  if(event.target.id==='login-form'){event.preventDefault();const data=new FormData(event.target),button=event.target.querySelector('[type=submit]');button.disabled=true;notice('Signing in…');void authenticate({username:data.get('username'),password:data.get('password')}).then(()=>notice('')).catch(()=>{renderLogin();notice('Could not sign in');});}
  if(event.target.id==='search-form')event.preventDefault();
});
document.addEventListener('input',event=>{
  if(event.target.id==='query'){query=event.target.value;const epoch=++pageEpoch;clearTimeout(searchTimer);searchTimer=setTimeout(()=>void runSearch(epoch),180);}
  if(event.target.id==='filter'){const q=event.target.value.toLocaleLowerCase();document.querySelectorAll('[data-row]').forEach(el=>{el.hidden=!el.textContent.toLocaleLowerCase().includes(q);});}
  if(event.target.id==='cache-count'){$('cache-value').textContent=CACHE_STEPS[Number(event.target.value)];}
});
document.addEventListener('change',event=>{
  if(event.target.id==='seek')void control('seek',{value:Number(event.target.value)}).catch(e=>notice(e.message));
  if(event.target.id==='cache-count'){const count=CACHE_STEPS[Number(event.target.value)];accountSetting('cacheCount',count);void store.trimTemporary(count).catch(e=>notice(e.message));}
});
document.addEventListener('focusin',event=>{if(event.target.id==='query')document.documentElement.classList.add('search-editing');});
document.addEventListener('focusout',event=>{if(event.target.id==='query')document.documentElement.classList.remove('search-editing');});
attachSwipe($('mini-identity'),{binding:()=>({epoch:modeEpoch,event:playerState?.occurrence?.eventId}),valid:b=>b.epoch===modeEpoch&&b.event===playerState?.occurrence?.eventId,onCommit:direction=>void control(direction==='left'?'next':'previous'),axis:'x'});
window.addEventListener('pagehide',()=>{cancelWork();transport.offline();stopPlayers();channel?.close();});
window.addEventListener('pageshow',event=>{if(event.persisted)location.reload();});
async function boot(){
  admission=remembered();
  if(localStorage.getItem('spofify.web.signed-out')==='true'){renderLogin();return;}
  if(admission?.offline){const ticket=accountAccess.beginAdmission(),epoch=modeEpoch;offline=true;identity={accountId:admission.accountId,username:admission.username,role:'offline'};const candidate=await openOfflineStore({origin,accountId:identity.accountId});if(epoch!==modeEpoch){candidate.close();return;}const incarnation=admission.incarnation||crypto.randomUUID();accountAccess.admit({accountId:identity.accountId,incarnation},candidate,ticket);store=candidate;remember({...admission,incarnation});const collections=await store.list();if(epoch!==modeEpoch)return;saved=pinned(collections);shell();await renderPage();return;}
  renderLogin();
  try{await authenticate();}catch{if(!identity)renderLogin();}
}
void boot().catch(error=>{renderLogin();notice(error.message||'Storage unavailable');});
