import test from 'node:test';
import assert from 'node:assert/strict';
import {createTransport,localPath,StaleOperation} from '../transport.js';
const origin='https://spofify.test', session={accountId:'a',username:'listener',role:'user',csrfToken:'test-csrf'};
test('canonical exact-origin URL validation rejects aliases and credentials',()=>{
  for(const path of ['https://other.test/api','//other.test/api','/\\other.test','/api#x','/api x','/api\n'])assert.throws(()=>localPath(path,origin));
  assert.equal(localPath('/api/library/tracks/id?x=1',origin),'/api/library/tracks/id?x=1');
});
test('explicit offline makes no fetch calls, including auth and logout',async()=>{
  let calls=0;const transport=createTransport({origin,fetch:async()=>{calls++;return new Response('{}');}});
  for(const path of ['/api/auth/session','/api/me/player/connect','/api/auth/logout'])await assert.rejects(transport.request(path,{method:'POST'}));
  assert.equal(calls,0);
});
test('write CSRF and Firefox exact-origin referrer; no credential redirects or cache',async()=>{
  let observed;const transport=createTransport({origin,fetch:async(path,options)=>{observed={path,...options};return new Response('{}');}});
  transport.online();transport.bind(session);await transport.json('/api/me/palette',{method:'PUT',body:'{}'});
  assert.equal(observed.headers.get('X-CSRF-Token'),'test-csrf');assert.equal(observed.redirect,'error');assert.equal(observed.cache,'no-store');assert.equal(observed.credentials,'same-origin');assert.equal(observed.referrer,origin+'/');assert.equal(observed.referrerPolicy,'same-origin');
});
test('offline aborts an outstanding response and fences late headers',async()=>{
  let resolve,signal;const transport=createTransport({origin,fetch:async(_,options)=>{signal=options.signal;return new Promise(done=>{resolve=done;});}});
  transport.online();transport.bind(session);const pending=transport.request('/api/library');transport.offline();assert.equal(signal.aborted,true);resolve(new Response('{}'));await assert.rejects(pending,StaleOperation);
});
test('account change fences response body consumption',async()=>{
  let release;const stream=new ReadableStream({start(controller){release=()=>{controller.enqueue(new TextEncoder().encode('{"secret":"old"}'));controller.close();};}});
  const transport=createTransport({origin,fetch:async()=>new Response(stream)});transport.online();transport.bind(session);
  const response=await transport.request('/api/me/history'),body=response.json();transport.bind({...session,accountId:'b'});release();await assert.rejects(body,StaleOperation);
});
test('observed 401 invalidates account and prevents later calls',async()=>{
  let invalidated=0,calls=0;const transport=createTransport({origin,onInvalid:()=>invalidated++,fetch:async()=>{calls++;return new Response('{}',{status:401});}});
  transport.online();transport.bind(session);await assert.rejects(transport.json('/api/auth/session'));assert.equal(invalidated,1);assert.equal(transport.accountId(),'');await assert.rejects(transport.request('/api/library'));assert.equal(calls,1);
});
test('uncertain writes are not replayed',async()=>{
  let calls=0;const transport=createTransport({origin,fetch:async()=>{calls++;throw new Error('lost after commit');}});transport.online();transport.bind(session);
  await assert.rejects(transport.json('/api/me/palette',{method:'PUT',body:'{}'}));assert.equal(calls,1);
});
test('403 retires write CSRF, never weakens verification',async()=>{
  let calls=0;const transport=createTransport({origin,fetch:async()=>{calls++;return new Response('{}',{status:403});}});transport.online();transport.bind(session);
  await assert.rejects(transport.request('/api/me/palette',{method:'PUT'}));await assert.rejects(transport.request('/api/me/palette',{method:'PUT'}));assert.equal(calls,1);
});
