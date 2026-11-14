const VERSION='spofify-web-shell-v1';
const FILES=['./','./index.html','./styles.css','./app.js','./transport.js','./online-player.js','./local-player.js','./gestures.js','./audio-owner.js','./presentation.js','./account-access.js','./offline-store.js','./progress-journal.js','./vendor/audio-adapter.js','./vendor/playback-sync.js','./manifest.webmanifest','./assets/JetBrainsMono.ttf','./assets/OFL.txt','./assets/icon.svg','./assets/icon-192.png','./assets/icon-512.png','./assets/license.js'];
const ALLOWED=new Set(FILES.map(path=>new URL(path,self.registration.scope).href));
self.addEventListener('install',event=>{
  // The namespace contains ONLY public release assets. Never put a session,
  // authenticated metadata, artwork or audio response in Cache Storage.
  event.waitUntil((async()=>{const cache=await caches.open(VERSION);await cache.addAll(FILES.map(path=>new Request(new URL(path,self.registration.scope),{credentials:'omit',cache:'reload'})));})());
});
self.addEventListener('activate',event=>{
  event.waitUntil((async()=>{for(const name of await caches.keys())if(name.startsWith('spofify-web-shell-')&&name!==VERSION)await caches.delete(name);await self.clients.claim();})());
});
self.addEventListener('message',event=>{if(event.data?.type==='activate')void self.skipWaiting();});
self.addEventListener('fetch',event=>{
  if(event.request.method!=='GET'||!ALLOWED.has(event.request.url))return;
  event.respondWith((async()=>{const cache=await caches.open(VERSION),hit=await cache.match(event.request.url);return hit||new Response('App update incomplete. Reconnect and reload.',{status:503,headers:{'Content-Type':'text/plain'}});})());
});
