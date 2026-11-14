import test from 'node:test';
import assert from 'node:assert/strict';
import { webcrypto } from 'node:crypto';
import { openOfflineStore, CACHE_STEPS, lookahead, MAX_MEDIA_BYTES } from '../offline-store.js';
import {createAccountAccess} from '../account-access.js';
import {createTransport} from '../transport.js';

// Focused IndexedDB *contract double*, NOT browser IndexedDB, rendering, durable
// disk, service-worker, HTTP-origin or audio acceptance. Transactions serialize,
// request events yield, aborted writes roll back, and Blobs are structured-cloned.
function memoryIDB() {
  const databases = new Map();
  const factory = { failStore: null, databases,
    open(name) {
      const req = {};
      setImmediate(() => {
        let state = databases.get(name);
        const fresh = !state;
        if (!state) { state = { stores: new Map(), indexes: new Map(), queue: [], active: false }; databases.set(name, state); }
        const db = {
          createObjectStore(key) {
            state.stores.set(key, new Map());
            state.indexes.set(key, new Map());
            return { createIndex(name, keyPath) { state.indexes.get(key).set(name, keyPath); } };
          }, close() {},
          transaction(names, mode) {
            const tasks = [];
            let running = false, ended = false, active = false, snapshot;
            const tx = {
              error: null,
              abort() { if (ended) throw new Error('TransactionInactive'); finish(true); },
              objectStore(name) {
                assert.ok(names.includes(name));
                const enqueue = fn => {
                  const r = {};
                  if (ended) throw new Error('TransactionInactive');
                  tasks.push(() => {
                    try { r.result = fn(); r.onsuccess?.({ target: r }); }
                    catch (error) { r.error = error; r.onerror?.({ target: r }); tx.error = error; finish(true); }
                  });
                  pump();
                  return r;
                };
                const write = fn => {
                  assert.equal(mode, 'readwrite');
                  return enqueue(() => {
                    if (factory.failStore === name) { factory.failStore = null; throw new Error('Injected write failure'); }
                    return fn(state.stores.get(name));
                  });
                };
                return {
                  index(indexName) {
                    const keyPath = state.indexes.get(name).get(indexName).split('.');
                    const indexedRows = query => [...state.stores.get(name)].map(([primary, value]) => ({
                      primary, value, key: keyPath.reduce((v, key) => v?.[key], value),
                    })).filter(row => row.key !== undefined && (query === undefined || row.key === query))
                      .sort((a, b) => a.key < b.key ? -1 : a.key > b.key ? 1 : a.primary < b.primary ? -1 : 1);
                    return {
                      count: query => enqueue(() => indexedRows(query).length),
                      getAll: (query, count) => enqueue(() => indexedRows(query).slice(0, count).map(row => structuredClone(row.value))),
                      openCursor(query, direction) {
                        const r = {};
                        let rows, position = 0;
                        const advance = () => {
                          tasks.push(() => {
                            if (!rows) { rows = indexedRows(query); if (direction === 'prev') rows.reverse(); }
                            const row = rows[position++];
                            r.result = row ? { value: structuredClone(row.value), continue: advance } : null;
                            r.onsuccess?.();
                          });
                          pump();
                        };
                        advance();
                        return r;
                      },
                    };
                  },
                  count: () => enqueue(() => state.stores.get(name).size),
                  get: key => enqueue(() => structuredClone(state.stores.get(name).get(key))),
                  getAll: (_query, count) => enqueue(() => [...state.stores.get(name)].sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0).slice(0, count).map(([, value]) => structuredClone(value))),
                  put: (value, key) => write(map => { map.set(key, structuredClone(value)); return key; }),
                  delete: key => write(map => map.delete(key)),
                  clear: () => write(map => map.clear()),
                };
              },
            };
            function next() { state.active = false; state.queue.shift(); state.queue[0]?.(); }
            function finish(aborted) {
              if (ended) return;
              ended = true;
              if (aborted && snapshot) state.stores = snapshot;
              setImmediate(() => { if (aborted) tx.onabort?.(); else tx.oncomplete?.(); next(); });
            }
            function pump() {
              if (!active || running || ended) return;
              running = true;
              setImmediate(() => {
                running = false;
                if (ended) return;
                if (tasks.length) { tasks.shift()(); pump(); }
                else finish(false);
              });
            }
            const start = () => {
              state.active = true; active = true;
              snapshot = structuredClone(state.stores);
              pump();
            };
            state.queue.push(start);
            if (!state.active && state.queue.length === 1) start();
            return tx;
          },
        };
        req.result = db;
        if (fresh) req.onupgradeneeded?.();
        req.onsuccess?.();
      });
      return req;
    },
  };
  return factory;
}
const bytes = new Uint8Array([0, 1, 2, 3, 4]);
const sha = Buffer.from(await webcrypto.subtle.digest('SHA-256', bytes)).toString('hex');
const version = `sha256:${sha}`;
const responseJSON = (value, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
const track = (id = 'track') => ({ kind: 'track', id, name: `Song ${id}`, artists: [{ id: 'artist', name: 'Artist' }], hasAudio: true,
  libraryPath: '/private/not-stored', downloadUrl: 'https://not-stored.invalid', token: 'not-stored' });
const entry = (position, id = 'track') => ({ occurrenceId: `occurrence-${position}`, position, track: track(id) });
function manifest(items = [entry(0)], overrides = {}) {
  return { version: 1, playlist: { id: 'p', kind: 'manual', name: 'Playlist', description: '', revision: 1 },
    fence: 'fence', items, total: items.length, recordedAudioTotal: items.length, limit: 100, nextCursor: null, ...overrides };
}
function media(id = 'track', overrides = {}) {
  return { version: 1, trackId: id, mediaVersion: version, sha256: sha, byteLength: bytes.length,
    format: 'wav', contentType: 'audio/wav', downloadUrl: `/api/me/offline/v1/tracks/${encodeURIComponent(id)}/audio?version=${encodeURIComponent(version)}`, ...overrides };
}
function transportFixture({ page = manifest(), prepare, audio } = {}) {
  const calls = [];
  const transport = async (path, options) => {
    calls.push({ path, options });
    if (path.includes('/manifest?')) return responseJSON(typeof page === 'function' ? page(path) : page);
    if (path.endsWith('/media')) return responseJSON(prepare || media(decodeURIComponent(path.split('/').at(-2))));
    if (path.includes('/audio?')) return audio ? audio() : new Response(bytes, { headers: { ETag: `"${version}"`, 'Content-Length': String(bytes.length) } });
    throw new Error(`Unexpected request: ${path}`);
  };
  return { calls, transport };
}
async function setup(overrides = {}) {
  const indexedDB = overrides.indexedDB || memoryIDB();
  const options = { origin: 'https://music.invalid', accountId: 'account', indexedDB, crypto: webcrypto, ...overrides };
  return { indexedDB, options, store: await openOfflineStore(options) };
}
const target = { kind: 'playlist', id: 'p', name: 'Requested name' };
const payload = (eventId = 'event') => ({ eventId, trackId: 'track', origin: 'offline', occurredAtMs: 100000, elapsedMs: 1500, progressedMs: 1000 });

test('frozen cache contract', () => {
  assert.deepEqual(CACHE_STEPS, [2, 5, 10, 100, 1000]);
  assert.ok(Object.isFrozen(CACHE_STEPS));
  assert.equal(lookahead(2), 2);
  assert.equal(lookahead(1000), 3);
});

test('canonical-origin+immutable-account namespaces isolate bytes and receipts', async () => {
  const { store, options } = await setup({ origin: 'https://MUSIC.invalid:443/' });
  await store.save(target, transportFixture().transport);
  await store.appendReceipt(payload());
  const same = await openOfflineStore({ ...options, origin: 'https://music.invalid' });
  assert.equal((await same.list()).length, 1);
  assert.equal((await same.pendingReceipts()).length, 1);
  for (const changes of [{ accountId: 'other' }, { origin: 'https://other.invalid' }]) {
    const other = await openOfflineStore({ ...options, ...changes });
    assert.deepEqual(await other.list(), []);
    assert.deepEqual(await other.pendingReceipts(), []);
    await assert.rejects(other.acquire('track'), /OFFLINE_MEDIA_NOT_FOUND/);
  }
  for (const origin of ['https://user:password@music.invalid', 'https://music.invalid/subpath', 'file:///']) {
    await assert.rejects(openOfflineStore({ ...options, origin }), /INVALID_NAMESPACE/);
  }
});

test('complete coherent pages preserve duplicate occurrences; bytes deduplicate and projections are positive', async () => {
  const { store, options, indexedDB } = await setup();
  const fixture = transportFixture({ page: path => path.includes('cursor=next') ? manifest([entry(1), entry(2, 'other')], { total: 3, recordedAudioTotal: 3 }) :
    manifest([entry(0)], { total: 3, recordedAudioTotal: 3, nextCursor: 'next' }) });
  const progress = [];
  const collection = await store.save(target, fixture.transport, { onProgress: p => progress.push(p) });
  assert.equal(collection.ready, true);
  assert.equal(collection.name, 'Playlist');
  assert.equal(collection.byteLength, bytes.length);
  assert.deepEqual(collection.items.map(i => i.position), [0, 1, 2]);
  assert.equal(fixture.calls.filter(c => c.path.endsWith('/media')).length, 2);
  assert.equal(fixture.calls.filter(c => c.path.includes('/audio?')).length, 1);
  assert.equal(progress.length, 2);
  const stored = JSON.stringify(await store.list());
  assert.ok(!stored.includes('not-stored'));
  const dbState = [...indexedDB.databases.values()][0];
  assert.equal(dbState.stores.get('media').size, 1);
  const cold = await openOfflineStore(options); // new handle, no transport passed
  const lease = await cold.acquire('track', version);
  assert.ok(lease.url.startsWith('blob:'));
  await cold.remove('playlist', 'p');
  // Node object-URL snapshot semantics, not browser playback/network proof.
  const blob = await import('node:buffer').then(({ resolveObjectURL }) => resolveObjectURL(lease.url));
  assert.deepEqual(new Uint8Array(await blob.arrayBuffer()), bytes);
  lease.release(); lease.release();
  assert.equal(await cold.get('playlist', 'p'), null);
});

test('album and artist manifest routes and identities use the actual v1 contract', async () => {
  const { store } = await setup();
  for (const kind of ['album', 'artist']) {
    const id = 'library:id';
    const fixture = transportFixture({ page: manifest([entry(0)], { collection: { kind, id, name: 'Collection' }, playlist: undefined }) });
    const saved = await store.save({ kind, id, name: 'Collection' }, fixture.transport);
    assert.ok(fixture.calls[0].path.startsWith(`/api/me/offline/v1/${kind}s/library%3Aid/manifest?`));
    assert.equal(saved.kind, kind);
  }
});

test('bad page fence/count/order/cursor/identity never replaces a healthy collection', async () => {
  const { store } = await setup();
  const healthy = await store.save(target, transportFixture().transport);
  for (const changes of [
    { total: 2 }, { recordedAudioTotal: 9 }, { limit: 40 }, { nextCursor: '' },
    { playlist: { id: 'wrong', kind: 'manual', name: 'Wrong' } },
    { items: [entry(1)] }, { items: [entry(0), entry(0)], total: 2, recordedAudioTotal: 2 },
  ]) {
    await assert.rejects(store.save(target, transportFixture({ page: manifest([entry(0)], changes) }).transport));
    assert.deepEqual(await store.get('playlist', 'p'), healthy);
  }
  const changedFence = transportFixture({ page: path => manifest([entry(path.includes('cursor=next') ? 1 : 0)], {
    fence: path.includes('cursor=next') ? 'changed' : 'fence', total: 2, recordedAudioTotal: 2,
    nextCursor: path.includes('cursor=next') ? null : 'next',
  }) });
  await assert.rejects(store.save(target, changedFence.transport), /STALE_MANIFEST/);
  assert.deepEqual(await store.get('playlist', 'p'), healthy);
});

test('length, digest, ETag, unsafe media URL and explicit size bound fail without readiness', async () => {
  for (const fixture of [
    transportFixture({ audio: () => new Response(new Uint8Array([9, 9, 9, 9, 9]), { headers: { ETag: `"${version}"` } }) }),
    transportFixture({ audio: () => new Response(new Uint8Array([0]), { headers: { ETag: `"${version}"` } }) }),
    transportFixture({ audio: () => new Response(new Uint8Array(6), { headers: { ETag: `"${version}"` } }) }),
    transportFixture({ audio: () => new Response(bytes, { headers: { ETag: '"wrong"' } }) }),
    transportFixture({ prepare: media('track', { downloadUrl: 'https://evil.invalid/audio' }) }),
    transportFixture({ prepare: media('track', { byteLength: MAX_MEDIA_BYTES + 1 }) }),
  ]) {
    const { store } = await setup();
    await assert.rejects(store.save(target, fixture.transport));
    assert.deepEqual(await store.list(), []);
  }
});

test('failed atomic transaction rolls back all media and manifest references', async () => {
  const { store, indexedDB } = await setup();
  indexedDB.failStore = 'collections';
  await assert.rejects(store.save(target, transportFixture().transport), /Injected write failure/);
  assert.deepEqual(await store.list(), []);
  assert.equal([...indexedDB.databases.values()][0].stores.get('media').size, 0);
  await store.save(target, transportFixture().transport);
  indexedDB.failStore = 'collections';
  await assert.rejects(store.remove('playlist', 'p'), /Injected write failure/);
  assert.equal((await store.list()).length, 1);
  const lease = await store.acquire('track'); lease.release();
});

test('abort cancels a stalled raw stream and cannot publish bytes', async () => {
  const { store } = await setup();
  const controller = new AbortController();
  let started, cancelled = false, sent = false;
  const ready = new Promise(resolve => { started = resolve; });
  const fixture = transportFixture({ audio: () => new Response(new ReadableStream({
    pull(c) { if (!sent) { sent = true; c.enqueue(bytes.slice(0, 1)); } else started(); },
    cancel() { cancelled = true; },
  }, { highWaterMark: 0 }), { headers: { ETag: `"${version}"` } }) });
  const saving = store.save(target, fixture.transport, { signal: controller.signal });
  await ready;
  controller.abort();
  await assert.rejects(saving, /ABORTED/);
  assert.equal(cancelled, true);
  assert.deepEqual(await store.list(), []);
});

test('remove supersedes staging; retire fences every old handle and purges only its namespace', async () => {
  const { store, options } = await setup();
  const old = await openOfflineStore(options);
  await store.appendReceipt(payload());
  await assert.rejects(store.save(target, transportFixture().transport, {
    onProgress: () => { throw new Error('cancel progress'); },
  }), /cancel progress/);
  const saving = store.save(target, transportFixture().transport, { onProgress() { old.retire(); } });
  await assert.rejects(saving, /STORE_RETIRED|ABORTED/);
  await assert.rejects(store.list(), /STORE_RETIRED/);
  const fresh = await openOfflineStore(options);
  assert.deepEqual(await fresh.list(), []);
  assert.deepEqual(await fresh.pendingReceipts(), []);
  await fresh.save(target, transportFixture().transport);
  await old.retire(); // must not purge a freshly opened generation
  assert.equal((await fresh.list()).length, 1);
});

test('remove during staging invalidates publication and preserves no partial set', async () => {
  const { store } = await setup();
  let removing;
  const saving = store.save(target, transportFixture().transport, { onProgress() { removing = store.remove('playlist', 'p'); } });
  await assert.rejects(saving, /SAVE_SUPERSEDED/);
  await removing;
  assert.deepEqual(await store.list(), []);
});

test('persistent epoch fences a separate-context handle even when local abort registry is not shared', async () => {
  const { store, options, indexedDB } = await setup();
  const separateContextFactory = { open: (...args) => indexedDB.open(...args) };
  const remote = await openOfflineStore({ ...options, indexedDB: separateContextFactory });
  await assert.rejects(remote.save(target, transportFixture().transport, { onProgress: () => store.retire() }), /STORE_RETIRED/);
  const fresh = await openOfflineStore(options);
  assert.deepEqual(await fresh.list(), []);
  await assert.rejects(remote.appendReceipt(payload()), /STORE_RETIRED/);
  await fresh.save(target, transportFixture().transport);
  await remote.retire();
  assert.equal((await fresh.list()).length, 1);
});

test('temporary trimming protects pins and reader leases; temporary refresh cannot downgrade pins', async () => {
  const { store } = await setup();
  await store.save(target, transportFixture().transport);
  await store.save(target, transportFixture().transport, { intent: 'temporary' });
  assert.equal((await store.get('playlist', 'p')).intent, 'pinned');
  const make = (id, trackId) => transportFixture({ page: manifest([entry(0, trackId)], { playlist: { id, kind: 'manual', name: id } }),
    prepare: media(trackId) });
  // Use distinct bytes for temporary content rather than pin-shared digests.
  const otherBytes = new Uint8Array([5, 6, 7]);
  const otherSha = Buffer.from(await webcrypto.subtle.digest('SHA-256', otherBytes)).toString('hex');
  const otherVersion = `sha256:${otherSha}`;
  const temporary = make('t', 'temporary-track');
  const transport = async (path, options) => {
    if (path.endsWith('/media')) return responseJSON(media('temporary-track', { sha256: otherSha, mediaVersion: otherVersion, byteLength: 3,
      downloadUrl: `/api/me/offline/v1/tracks/temporary-track/audio?version=${encodeURIComponent(otherVersion)}` }));
    if (path.includes('/audio?')) return new Response(otherBytes, { headers: { ETag: `"${otherVersion}"` } });
    return temporary.transport(path, options);
  };
  await store.save({ kind: 'playlist', id: 't', name: 't' }, transport, { intent: 'temporary' });
  const lease = await store.acquire('temporary-track');
  assert.equal((await store.trimTemporary(0)).removed, 0);
  lease.release();
  assert.equal((await store.trimTemporary(0)).removed, 1);
  assert.equal((await store.get('playlist', 'p')).intent, 'pinned');
});

test('failed HTTP bodies are consumed and typed status retained without readiness', async () => {
  const { store } = await setup();
  let bodyFinished = false;
  await assert.rejects(store.save(target, async () => new Response(new ReadableStream({ start(c) {
    c.enqueue(new TextEncoder().encode('{"error":{"code":"STALE_EVALUATION"}}')); c.close(); bodyFinished = true;
  } }), { status: 409 })), error => error.status === 409);
  assert.equal(bodyFinished, true);
  assert.deepEqual(await store.list(), []);
});

test('immutable journal rejects changed local IDs, persists across handles and exact ACK clears pending', async () => {
  const { store, options } = await setup();
  const p = payload();
  await store.appendReceipt(p);
  p.occurredAtMs = 999999;
  await store.appendReceipt(payload());
  await assert.rejects(store.appendReceipt({ ...payload(), progressedMs: 1100 }), /RECEIPT_CONFLICT/);
  await assert.rejects(store.appendReceipt({ ...payload(), extra: true }), /INVALID_RECEIPT/);
  const cold = await openOfflineStore(options);
  assert.deepEqual(await cold.pendingReceipts(), [{ payload: payload(), diagnostic: null }]);
  const sent = [];
  const result = await cold.uploadReceipts(async (path, options) => {
    assert.equal(path, '/api/me/offline/v1/play-receipts');
    assert.equal(options.method, 'POST');
    sent.push(JSON.parse(options.body));
    return responseJSON({ eventId: 'event', receivedAtMs: 200000, duplicate: true });
  });
  assert.deepEqual(sent, [payload()]);
  assert.deepEqual(result, { uploaded: 1, diagnostic: null });
  assert.deepEqual(await cold.pendingReceipts(), []);
});

test('422/409 and malformed ACK retain exact payload + diagnostics; lost ACK retries identical body', async () => {
  const { store } = await setup();
  await store.appendReceipt(payload());
  const bodies = [];
  for (const status of [422, 409, 401, 404, 500]) {
    const result = await store.uploadReceipts(async (_path, options) => {
      bodies.push(options.body);
      return responseJSON({ error: 'private diagnostics not retained' }, status);
    });
    assert.equal(result.diagnostic.status, status);
    const [record] = await store.pendingReceipts();
    assert.deepEqual(record.payload, payload());
    assert.equal(record.diagnostic.status, status);
    assert.ok(!JSON.stringify(record).includes('private diagnostics'));
  }
  for (const ack of [{ eventId: 'wrong', receivedAtMs: 2, duplicate: false }, { eventId: 'event', duplicate: false }]) {
    await store.uploadReceipts(async () => responseJSON(ack));
    assert.equal((await store.pendingReceipts()).length, 1);
  }
  await store.uploadReceipts(async (_path, options) => { bodies.push(options.body); throw new Error('Lost response after commit'); });
  await store.uploadReceipts(async (_path, options) => {
    bodies.push(options.body);
    return responseJSON({ eventId: 'event', receivedAtMs: 200000, duplicate: true });
  });
  assert.equal(new Set(bodies).size, 1);
  assert.deepEqual(await store.pendingReceipts(), []);
});

test('uploads are sequential and bounded to 100 immutable receipts per explicit call', async () => {
  const { store } = await setup();
  for (let i = 0; i < 101; i++) await store.appendReceipt(payload(`event-${String(i).padStart(3, '0')}`));
  assert.equal((await store.pendingReceipts()).length, 100);
  assert.deepEqual(await store.receiptCounts(), { pending: 101, total: 101 });
  let active = 0, maximum = 0;
  const result = await store.uploadReceipts(async (_path, options) => {
    maximum = Math.max(maximum, ++active);
    await Promise.resolve();
    active--;
    return responseJSON({ eventId: JSON.parse(options.body).eventId, receivedAtMs: 200000, duplicate: false });
  });
  assert.equal(result.uploaded, 100);
  assert.equal(maximum, 1);
  assert.equal((await store.pendingReceipts()).length, 1);
  assert.deepEqual(await store.receiptCounts(), { pending: 1, total: 101 });
});

test('cacheTracks admits exact current/lookahead without manifest requests, shares pinned bytes and survives a new handle', async () => {
  const { store, options, indexedDB } = await setup();
  await store.save(target, transportFixture().transport);
  const fixture = transportFixture();
  const cached = await store.cacheTracks([track(), track('next'), track('next')], fixture.transport);
  assert.equal(cached.length, 2);
  assert.ok(cached.every(c => c.kind === 'temporary' && c.ready && c.intent === 'temporary'));
  assert.equal(fixture.calls.filter(c => c.path.includes('/manifest')).length, 0);
  assert.equal(fixture.calls.filter(c => c.path.includes('/audio?')).length, 0);
  assert.equal([...indexedDB.databases.values()][0].stores.get('media').size, 1);
  const cold = await openOfflineStore(options);
  const lease = await cold.acquire('next');
  assert.ok(lease.url.startsWith('blob:'));
  lease.release();
  await cold.trimTemporary(0);
  assert.equal((await cold.get('playlist', 'p')).intent, 'pinned');
  assert.equal(await cold.get('temporary', 'next'), null);
  await assert.rejects(cold.cacheTracks(Array.from({ length: 5 }, (_, i) => track(String(i))), fixture.transport), /INVALID_CACHE_TRACKS/);
  await assert.rejects(cold.save({ kind: 'temporary', id: 'x', name: 'x' }, fixture.transport), /INVALID_COLLECTION/);
});

test('per-track cache capacity counts track identities, with play-promoted FIFO and reader protection', async () => {
  const { store } = await setup();
  await store.cacheTracks([track('a'), track('b'), track('c')], transportFixture().transport);
  const lease = await store.acquire('c');
  lease.release(); // promotes c; b should now leave first, even with identical bytes
  const trimmed = await store.trimTemporary(2);
  assert.equal(trimmed.retainedTracks, 2);
  assert.equal(await store.get('temporary', 'b'), null);
  assert.ok(await store.get('temporary', 'a'));
  assert.ok(await store.get('temporary', 'c'));
  const active = await store.acquire('a');
  assert.equal((await store.trimTemporary(0)).retainedTracks, 2); // shared leased bytes remain protected
  active.release();
  assert.equal((await store.trimTemporary(0)).retainedTracks, 0);
});

test('cache admission is atomic and healthy previous versions survive corrupt refresh', async () => {
  const { store } = await setup();
  const healthy = await store.cacheTracks([track('a')], transportFixture().transport);
  const badSha = 'f'.repeat(64), badVersion = `sha256:${badSha}`;
  const bad = transportFixture({ prepare: media('a', { sha256: badSha, mediaVersion: badVersion,
    downloadUrl: `/api/me/offline/v1/tracks/a/audio?version=${encodeURIComponent(badVersion)}` }),
    audio: () => new Response(bytes, { headers: { ETag: `\"${badVersion}\"` } }) });
  await assert.rejects(store.cacheTracks([track('a')], bad.transport), /MEDIA_DIGEST_MISMATCH/);
  assert.deepEqual(await store.get('temporary', 'a'), healthy[0]);
});

test('ACKed receipts remain actual local history and cannot be rewritten or requeued', async () => {
  const { store, options } = await setup();
  await store.appendReceipt(payload());
  await store.uploadReceipts(async () => responseJSON({ eventId: 'event', receivedAtMs: 200000, duplicate: false }));
  const cold = await openOfflineStore(options);
  assert.deepEqual(await cold.localHistory(), [{ payload: payload(), diagnostic: null, acked: true,
    ack: { eventId: 'event', receivedAtMs: 200000, duplicate: false } }]);
  await cold.appendReceipt(payload());
  assert.deepEqual(await cold.pendingReceipts(), []);
  await assert.rejects(cold.appendReceipt({ ...payload(), occurredAtMs: 200000 }), /RECEIPT_CONFLICT/);
  const later = { ...payload('second'), occurredAtMs: 200000 };
  await cold.appendReceipt(later);
  assert.equal((await cold.localHistory())[0].payload.eventId, 'second');
  await cold.acknowledgeReceipt('second');
  assert.deepEqual(await cold.pendingReceipts(), []);
  assert.equal((await cold.localHistory())[0].acked, true);
  await cold.retire();
  const next = await openOfflineStore(options);
  assert.deepEqual(await next.localHistory(), []);
});

test('receiptCounts is fenced, namespace-isolated and counts pending versus retained ACK history truthfully', async () => {
  const { store, options } = await setup();
  assert.deepEqual(await store.receiptCounts(), { pending: 0, total: 0 });
  await store.appendReceipt(payload());
  await store.appendReceipt(payload()); // immutable duplicate does not inflate totals
  await store.appendReceipt(payload('second'));
  await store.uploadReceipts(async () => responseJSON({ error: 'clock' }, 422));
  assert.deepEqual(await store.receiptCounts(), { pending: 2, total: 2 });
  await store.acknowledgeReceipt('event');
  const cold = await openOfflineStore(options);
  assert.deepEqual(await cold.receiptCounts(), { pending: 1, total: 2 });
  const other = await openOfflineStore({ ...options, accountId: 'other' });
  assert.deepEqual(await other.receiptCounts(), { pending: 0, total: 0 });
  await store.retire();
  await assert.rejects(cold.receiptCounts(), /STORE_RETIRED/);
  const fresh = await openOfflineStore(options);
  assert.deepEqual(await fresh.receiptCounts(), { pending: 0, total: 0 });
});

test('quota/persistence unsupported is truthful rather than made-up success', async () => {
  const { store } = await setup();
  const estimate = await store.estimate();
  assert.equal(estimate.scope, 'browser-origin');
  assert.equal(estimate.quota, null);
  assert.equal(estimate.persisted, null);
  assert.equal(estimate.evictionPossible, true);
  assert.equal(await store.requestPersistence(), false);
});

async function accessFixture(){
  const {store,options}=await setup({accountId:'A'});
  await store.save(target,transportFixture().transport);
  await store.appendReceipt(payload('A-event'));
  let fenced=0;
  const openStore=accountId=>openOfflineStore({...options,accountId});
  const access=createAccountAccess({origin:options.origin,openStore,onFence(){fenced++;}});
  const ticket=access.beginAdmission(),token=access.admit({accountId:'A',incarnation:'session-A'},store,ticket);
  return {store,options,access,token,openStore,fenced:()=>fenced};
}
test('401 closes access synchronously but pins and exact pending evidence survive same-account reauthentication',async()=>{
  const {store,options,access,openStore,fenced}=await accessFixture();
  const before=await store.pendingReceipts();
  const transport=createTransport({origin:options.origin,fetch:async()=>responseJSON({},401),onInvalid:()=>access.deactivate('Sign in again')});
  transport.online();transport.bind({accountId:'A',username:'a',role:'user',csrfToken:'test'});
  await assert.rejects(transport.request('/api/auth/session'));
  assert.equal(fenced(),1);assert.equal(access.current(),null);
  await assert.rejects(store.list(),/STORE_RETIRED/);await assert.rejects(store.acquire('track'),/STORE_RETIRED/);
  const ticket=access.beginAdmission(),restored=await openStore('A');
  access.admit({accountId:'A',incarnation:'session-A2'},restored,ticket);
  assert.equal((await restored.list())[0].intent,'pinned');assert.equal((await restored.list())[0].ready,true);
  assert.deepEqual(await restored.pendingReceipts(),before);
  const reader=await restored.acquire('track');reader.release();restored.close();
});
test('account switching neither exposes nor uploads nor deletes the previous partition',async()=>{
  const {store,access,openStore}=await accessFixture(),pending=await store.pendingReceipts();
  const ticket=access.beginAdmission(),other=await openStore('B');
  access.admit({accountId:'B',incarnation:'session-B'},other,ticket);
  assert.deepEqual(await other.list(),[]);assert.deepEqual(await other.pendingReceipts(),[]);
  let uploads=0;await other.uploadReceipts(async()=>{uploads++;throw new Error('Wrong account upload');});assert.equal(uploads,0);
  await assert.rejects(store.list(),/STORE_RETIRED/);
  const old=await openStore('A');assert.equal((await old.list()).length,1);assert.deepEqual(await old.pendingReceipts(),pending);old.close();other.close();
});
test('scoped broadcasts close only their current incarnation and never purge pins or receipts',async()=>{
  const {access,openStore,fenced}=await accessFixture();
  for(const message of [{type:'logout'},{type:'logout',origin:'https://other.invalid',accountId:'A',incarnation:'session-A'},{type:'session-changed',origin:'https://music.invalid',accountId:'B',incarnation:'session-A'}])assert.equal(access.broadcast(message),false);
  assert.equal(fenced(),0);
  assert.equal(access.broadcast({type:'session-changed',origin:'https://music.invalid',accountId:'A',incarnation:'session-A'}),true);
  assert.equal(access.current(),null);
  const ticket=access.beginAdmission(),restored=await openStore('A');access.admit({accountId:'A',incarnation:'session-A2'},restored,ticket);
  assert.equal(access.broadcast({type:'logout',origin:'https://music.invalid',accountId:'A',incarnation:'session-A'}),false);
  assert.equal(access.current().store,restored);assert.equal((await restored.list()).length,1);assert.equal((await restored.pendingReceipts()).length,1);restored.close();
});
test('purge requires explicit consent and the exact captured account; late logout cannot purge a successor',async()=>{
  const {store,access,token,openStore}=await accessFixture();
  assert.equal(await access.purgeConfirmed(token),false);assert.equal((await store.list()).length,1);
  access.deactivate('Expired');
  const ticket=access.beginAdmission(),other=await openStore('B');await other.appendReceipt(payload('B-event'));access.admit({accountId:'B',incarnation:'session-B'},other,ticket);
  assert.equal(await access.purgeConfirmed(token,{confirmed:true}),false);
  assert.equal((await other.pendingReceipts())[0].payload.eventId,'B-event');
  const old=await openStore('A');assert.equal((await old.list()).length,1);assert.equal((await old.pendingReceipts()).length,1);old.close();
  const target=access.current();assert.equal(await access.purgeConfirmed(target,{confirmed:true}),true);
  const fresh=await openStore('B');assert.deepEqual(await fresh.receiptCounts(),{pending:0,total:0});fresh.close();
});
test('confirmed logout after 401 may purge only its inactive namespace, with stale reopen fenced',async()=>{
  const {access,token,openStore}=await accessFixture();access.deactivate('Expired');
  assert.equal(await access.purgeConfirmed(token,{confirmed:true}),true);
  const fresh=await openStore('A');assert.equal((await fresh.list()).length,0);assert.equal((await fresh.pendingReceipts()).length,0);fresh.close();
  let release;const deferred=new Promise(done=>{release=done;});
  const old=await openStore('A');await old.appendReceipt(payload('preserved'));
  const raced=createAccountAccess({origin:'https://music.invalid',openStore:async id=>{await deferred;return openStore(id);},onFence(){}});
  const first=raced.beginAdmission(),captured=raced.admit({accountId:'A',incarnation:'old'},old,first);raced.deactivate();
  const pending=raced.purgeConfirmed(captured,{confirmed:true});
  const newer=raced.beginAdmission(),successor=await openStore('B');raced.admit({accountId:'B',incarnation:'new'},successor,newer);release();
  assert.equal(await pending,false);assert.equal(raced.current().store,successor);
  const preserved=await openStore('A');assert.equal((await preserved.pendingReceipts())[0].payload.eventId,'preserved');preserved.close();successor.close();
});
test('shared successor incarnation blocks stale confirmed purge and scopes the synchronous fence target',async()=>{
  const {store,openStore}=await accessFixture();let current='old',fenced;
  const access=createAccountAccess({origin:'https://music.invalid',openStore,onFence(_message,target){fenced=target;},canPurge:target=>target.incarnation===current});
  const ticket=access.beginAdmission(),old=access.admit({accountId:'A',incarnation:'old'},store,ticket);
  current='new';assert.equal(await access.purgeConfirmed(old,{confirmed:true}),false);assert.equal(fenced,old);assert.equal(access.current(),null);
  const preserved=await openStore('A');assert.equal((await preserved.list()).length,1);assert.equal((await preserved.pendingReceipts()).length,1);preserved.close();
});
