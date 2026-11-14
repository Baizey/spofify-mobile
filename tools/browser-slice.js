// Source-bound synthetic acceptance runner. Fixture AND Firefox run within this
// one foreground process/network namespace. No MCP execution or host bridge.
import http from 'node:http';
import {mkdir,readFile,writeFile,realpath} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {pathToFileURL} from 'node:url';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {createFixture} from './fixture.js';
import {release} from './release.js';
const args=process.argv.slice(2),flags=new Map();
for(let i=0;i<args.length;i+=2){if(!['--run','--source','--playwright','--browser-cache'].includes(args[i])||!args[i+1]||flags.has(args[i]))throw new Error('Explicit runner inputs required');flags.set(args[i],args[i+1]);}
if(flags.size!==4)throw new Error('Usage: --run OWNED_RUN --source SOURCE --playwright INSTALLED_MATCHING_CORE --browser-cache INSTALLED_READONLY_CACHE');
const run=await realpath(flags.get('--run')),source=await realpath(flags.get('--source')),modulePath=await realpath(flags.get('--playwright'));
if(!run.startsWith('/var/home/lsm/.local/state/spofify-agent-runs/'))throw new Error('Owned SSD run required');
for(const name of ['home','xdg-cache','xdg-config','xdg-data','xdg-runtime','tmp','profile','output','fixture','release'])await mkdir(join(run,name),{mode:0o700});
// Preserve all injected trust/security environment. Only normal private browser
// state locations and the caller-supplied read-only installed cache are selected.
Object.assign(process.env,{HOME:join(run,'home'),XDG_CACHE_HOME:join(run,'xdg-cache'),XDG_CONFIG_HOME:join(run,'xdg-config'),XDG_DATA_HOME:join(run,'xdg-data'),XDG_RUNTIME_DIR:join(run,'xdg-runtime'),TMPDIR:join(run,'tmp'),TMP:join(run,'tmp'),TEMP:join(run,'tmp'),PLAYWRIGHT_BROWSERS_PATH:await realpath(flags.get('--browser-cache'))});
const browserManifest=JSON.parse(await readFile(join(modulePath,'browsers.json'),'utf8'));
assert.ok(browserManifest.browsers.some(x=>x.name==='firefox'&&x.revision==='1573'&&x.browserVersion==='159.0'),'Matching installed Firefox159/revision1573 required');
const bundle=await release(source,join(run,'release'));
const origin='http://127.0.0.1:5173';
let fixture,server,browser,context,page,phase='fixture-start',primary=null;
const cleanup={browserClosed:false,serverClosed:false,fixtureClosed:false};
const report={synthetic:true,realGoAcceptance:false,androidAcceptance:false,restartScope:'cold document in same browser context; not browser process or OS restart',bundle,checks:{},pageErrors:[],requestFailures:[]};
const save=async()=>writeFile(join(run,'output','report.json'),JSON.stringify({...report,phase,primary,cleanup},null,2)+'\n');
let deadline;
try{
  fixture=createFixture({source:join(run,'release'),origin,stateRoot:join(run,'fixture')});
  server=http.createServer({requestTimeout:10000,headersTimeout:10000},fixture.handler);server.maxConnections=16;
  await new Promise((done,reject)=>{server.once('error',reject);server.listen({host:'127.0.0.1',port:5173,exclusive:true},done);});
  phase='firefox-default-launch';
  const {firefox}=await import(pathToFileURL(join(modulePath,'index.mjs')).href);
  // One ordinary launch, normal executable resolution and sandbox/security.
  browser=await firefox.launch({headless:true,timeout:30000});
  context=await browser.newContext({viewport:{width:412,height:906}});
  context.setDefaultTimeout(15000);
  await context.addInitScript(()=>{window.__appFetches=0;const original=window.fetch;window.fetch=(...args)=>{window.__appFetches++;return original.apply(window,args);};});
  const attach=p=>{p.on('pageerror',error=>report.pageErrors.push(error.message));p.on('requestfailed',request=>report.requestFailures.push({url:new URL(request.url()).pathname,error:request.failure()?.errorText}));};
  page=await context.newPage();attach(page);
  const tests=(async()=>{
    phase='render-fixture';await page.goto(origin+'/app/');
    await page.getByRole('heading',{name:'Sign in',exact:true}).waitFor();
    report.checks.render=await page.evaluate(()=>({userAgent:navigator.userAgent,secure:isSecureContext,viewport:{width:innerWidth,height:innerHeight},serviceWorker:'serviceWorker'in navigator,indexedDB:'indexedDB'in window,signIn:document.querySelector('#login-form [type=submit]').getBoundingClientRect().toJSON()}));
    assert.match(report.checks.render.userAgent,/Firefox\/159/);
    assert.ok(report.checks.render.signIn.height>=48&&report.checks.render.signIn.width>=48);
    await page.screenshot({path:join(run,'output','render-signin-412x906.png')});await save();
    console.log(JSON.stringify({phase,rendered:true,viewport:report.checks.render.viewport}));
    phase='authenticated-pinned-download';
    await page.locator('input[name=username]').fill('fixture-listener');await page.locator('input[name=password]').fill('synthetic-fixture-only');
    await page.locator('#login-form [type=submit]').click();await page.getByRole('heading',{name:'History',exact:true}).waitFor();
    await page.locator('[data-tab=playlists]').click();await page.locator('[data-row-open="0"]').click();
    await page.getByRole('heading',{name:'Offline sound check',exact:true}).waitFor();await page.locator('[data-action=download]').click();
    await page.waitForFunction(()=>document.querySelector('#download-state')?.textContent.includes('Downloaded'));
    await page.waitForFunction(()=>!!navigator.serviceWorker.controller);
    report.checks.installed=await page.evaluate(async()=>{
      const {openOfflineStore}=await import('./offline-store.js');const store=await openOfflineStore({origin:location.origin,accountId:'fixture-account'});
      const collections=await store.list();store.close();
      const name='spofify-web-offline-v1:'+JSON.stringify([location.origin,'fixture-account']);
      const db=await new Promise((done,reject)=>{const r=indexedDB.open(name);r.onsuccess=()=>done(r.result);r.onerror=()=>reject(r.error);});
      const tx=db.transaction('media','readonly'),media=tx.objectStore('media');
      const records=await new Promise((done,reject)=>{const r=media.getAll();r.onsuccess=()=>done(r.result);r.onerror=()=>reject(r.error);});
      const keys=await new Promise((done,reject)=>{const r=media.getAllKeys();r.onsuccess=()=>done(r.result);r.onerror=()=>reject(r.error);});db.close();
      const bytes=[];for(let i=0;i<records.length;i++){const data=await records[i].blob.arrayBuffer(),digest=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',data)),x=>x.toString(16).padStart(2,'0')).join('');bytes.push({length:data.byteLength,declared:records[i].byteLength,shaMatches:keys[i]==='sha256:'+digest});}
      return {collections:collections.map(c=>({ready:c.ready,intent:c.intent,items:c.items.length,distinct:new Set(c.items.map(i=>i.mediaVersion)).size,byteLength:c.byteLength})),bytes};
    });
    assert.equal(report.checks.installed.collections[0].items,3);assert.equal(report.checks.installed.collections[0].distinct,2);
    assert.ok(report.checks.installed.bytes.every(x=>x.shaMatches&&x.length===x.declared));
    phase='cold-offline-document';
    await page.locator('[data-tab=settings]').click();await page.locator('[data-action=offline]').click();
    const beforeOffline=fixture.summary().requests;
    await context.setOffline(true);await page.close();page=await context.newPage();attach(page);await page.goto(origin+'/app/');
    await page.locator('#mode-bar:not([hidden])').waitFor();
    assert.equal(await page.evaluate(()=>window.__appFetches),0);
    assert.equal(await page.locator('#audio').evaluate(a=>a.paused),true);
    await page.locator('[data-tab=playlists]').click();await page.locator('[data-row-open="0"]').click();
    await page.locator('[data-action=collection-play]').click();
    phase='actual-downloaded-byte-playback';
    await page.waitForFunction(()=>{const a=document.querySelector('#audio');return a.currentTime>1.7&&!a.paused;},null,{timeout:12000});
    await page.locator('#mini-toggle').click();
    report.checks.playback=await page.locator('#audio').evaluate(a=>({sourceScheme:a.currentSrc.split(':')[0],elapsed:a.currentTime,duration:a.duration,paused:a.paused,fetches:window.__appFetches}));
    assert.equal(report.checks.playback.sourceScheme,'blob');assert.equal(report.checks.playback.duration,8);assert.equal(report.checks.playback.fetches,0);
    const payload=await page.evaluate(async()=>{const {openOfflineStore}=await import('./offline-store.js');const s=await openOfflineStore({origin:location.origin,accountId:'fixture-account'});const pending=await s.pendingReceipts();s.close();return pending[0]?.payload;});
    assert.ok(payload,'Durable observed-progress receipt required');assert.equal(Object.keys(payload).length,6);assert.ok(payload.progressedMs>=1000&&payload.progressedMs<=payload.elapsedMs);
    report.checks.receipt=payload;assert.deepEqual(fixture.summary().requests,beforeOffline);report.checks.zeroOfflineServerTraffic=true;
    await page.screenshot({path:join(run,'output','offline-downloaded-412x906.png')});
    await page.locator('#mini-identity').click();await page.screenshot({path:join(run,'output','offline-player-412x906.png')});await page.locator('[data-action=dismiss]').click();
    phase='reconnect-immutable-receipt';await context.setOffline(false);
    await page.waitForTimeout(200);assert.deepEqual(fixture.summary().requests,beforeOffline);
    await page.locator('#mode-bar [data-action=online]').click();
    await page.waitForFunction(async()=>{const {openOfflineStore}=await import('./offline-store.js');const s=await openOfflineStore({origin:location.origin,accountId:'fixture-account'});const n=await s.receiptCounts();s.close();return n.pending===0;});
    assert.equal(fixture.summary().acceptedReceipts,1);assert.deepEqual(fixture.summary().receipts[0],payload);assert.equal(await page.locator('#audio').evaluate(a=>a.paused),true);
    const replay=await page.evaluate(async payload=>{
      const session=await(await fetch('/api/auth/session')).json();
      const mutate=(path,method,body)=>fetch(path,{method,credentials:'same-origin',cache:'no-store',redirect:'error',headers:{'Content-Type':'application/json','X-CSRF-Token':session.csrfToken},body:JSON.stringify(body),referrerPolicy:'same-origin',referrer:location.origin+'/'});
      const first=await(await mutate('/api/me/offline/v1/play-receipts','POST',payload)).json();
      const clear=await mutate('/api/me/history','DELETE',{});
      const afterClear=await(await mutate('/api/me/offline/v1/play-receipts','POST',payload)).json();
      const history=await(await fetch('/api/me/history')).json();return {first,clearStatus:clear.status,afterClear,historyCount:history.total};
    },payload);
    assert.equal(replay.first.duplicate,true);assert.equal(replay.afterClear.duplicate,true);assert.equal(replay.clearStatus,204);assert.equal(replay.historyCount,0);assert.equal(replay.first.receivedAtMs,replay.afterClear.receivedAtMs);
    report.checks.replay=replay;report.checks.fixtureSummary=fixture.summary();
    phase='completed';assert.deepEqual(report.pageErrors,[]);await save();
  })();
  await Promise.race([tests,new Promise((_,reject)=>{deadline=setTimeout(()=>reject(new Error('Foreground browser slice deadline')),120000);})]);
}catch(error){primary={phase,name:error.name,message:error.message,stack:String(error.stack).slice(0,6000)};process.exitCode=1;
  if(page&&!page.isClosed())try{await page.screenshot({path:join(run,'output','failure.png'),timeout:5000});report.failureText=(await page.locator('body').innerText({timeout:2000})).slice(0,3000);}catch{}
}finally{
  clearTimeout(deadline);
  try{if(context)await context.close();if(browser)await browser.close();cleanup.browserClosed=!!browser;}catch(error){cleanup.browserError=error.message;process.exitCode=1;}
  try{if(server){server.closeAllConnections();await new Promise(done=>server.close(done));}cleanup.serverClosed=!!server;}catch(error){cleanup.serverError=error.message;process.exitCode=1;}
  try{if(fixture){report.finalFixture=await fixture.close();cleanup.fixtureClosed=true;}}catch(error){cleanup.fixtureError=error.message;process.exitCode=1;}
  await save();
  const files=['render-signin-412x906.png','offline-downloaded-412x906.png','offline-player-412x906.png'];report.screenshots=[];
  for(const file of files)try{const b=await readFile(join(run,'output',file));report.screenshots.push({file,bytes:b.length,sha256:createHash('sha256').update(b).digest('hex')});}catch(error){if(error.code!=='ENOENT')throw error;}
  await save();console.log(JSON.stringify({phase,primary,cleanup,checks:Object.keys(report.checks),run}));
}
