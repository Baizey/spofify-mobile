// Explicit private IndexedDB storage, never an HTTP cache. A published manifest
// and all its Blob references commit together. No native data is read/migrated.
export const CACHE_STEPS = Object.freeze([2, 5, 10, 100, 1000]);
export const lookahead = count => Math.min(count, 3);
// WebCrypto digest requires a complete buffer. Reject larger files/collections
// honestly; one admission per JS realm bounds staging plus digest working memory.
export const MAX_MEDIA_BYTES = 32 * 1024 * 1024;
export const MAX_COLLECTION_BYTES = 128 * 1024 * 1024;
const JSON_BYTES = 2 * 1024 * 1024;
const MANIFEST_BYTES = 8 * 1024 * 1024;
const RECEIPT_BATCH = 100;
const API = '/api/me/offline/v1';
const routes = { playlist: 'playlists', album: 'albums', artist: 'artists' };
const groups = new WeakMap();
let admitting = false;

function failure(code, status) {
  return Object.assign(new Error(code), { code, ...(status === undefined ? {} : { status }) });
}
function requireValue(ok, code = 'INVALID_OFFLINE_RESPONSE') { if (!ok) throw failure(code); }
function validID(value, max = 512) {
  return typeof value === 'string' && value.length > 0 && value === value.trim() &&
    !/[\x00-\x1f\x7f-\x9f]/u.test(value) && new TextEncoder().encode(value).length <= max;
}
function collectionKey(kind, id) {
  requireValue((Object.hasOwn(routes, kind) || kind === 'temporary') && validID(id), 'INVALID_COLLECTION');
  if (kind === 'playlist') requireValue(/^[A-Za-z0-9_-]{1,128}$/.test(id), 'INVALID_COLLECTION');
  return JSON.stringify([kind, id]);
}
const request = req => new Promise((resolve, reject) => {
  req.onsuccess = () => resolve(req.result);
  req.onerror = () => reject(req.error);
});
async function transaction(db, names, mode, run, signal) {
  const tx = db.transaction(names, mode);
  const done = new Promise((resolve, reject) => {
    tx.oncomplete = resolve;
    tx.onabort = () => reject(tx.error || failure('ABORTED'));
    tx.onerror = () => {}; // onabort owns the transaction failure.
  });
  const abort = () => { try { tx.abort(); } catch { /* already complete */ } };
  signal?.addEventListener('abort', abort, { once: true });
  try {
    if (signal?.aborted) throw failure('ABORTED');
    const value = await run(name => tx.objectStore(name));
    await done;
    return value;
  } catch (error) {
    abort();
    await done.catch(() => {});
    throw error;
  } finally { signal?.removeEventListener('abort', abort); }
}
async function openDatabase(indexedDB, name) {
  const req = indexedDB.open(name, 1);
  req.onupgradeneeded = () => {
    for (const store of ['meta', 'collections', 'media']) req.result.createObjectStore(store);
    const receipts = req.result.createObjectStore('receipts');
    receipts.createIndex('pending', 'pending');
    receipts.createIndex('occurredAtMs', 'payload.occurredAtMs');
  };
  return request(req);
}

async function readBytes(response, max, check, signal, exact) {
  const guard = () => {
    if (signal?.aborted) throw failure('ABORTED');
    check();
  };
  guard();
  requireValue(response.body?.getReader, 'STREAMING_UNAVAILABLE');
  const reader = response.body.getReader();
  // Cancellation unblocks a stalled read on account/mode switch.
  const abort = () => { reader.cancel().catch(() => {}); };
  signal?.addEventListener('abort', abort, { once: true });
  let complete = false, length = 0;
  const chunks = [];
  const buffer = exact === undefined ? undefined : new Uint8Array(exact);
  try {
    for (;;) {
      guard();
      const { done, value } = await reader.read();
      guard();
      if (done) { complete = true; break; }
      requireValue(value instanceof Uint8Array && length + value.byteLength <= max, 'BODY_TOO_LARGE');
      if (buffer) buffer.set(value, length); else chunks.push(value);
      length += value.byteLength;
    }
    requireValue(exact === undefined || length === exact, 'MEDIA_LENGTH_MISMATCH');
    if (buffer) return buffer;
    const result = new Uint8Array(length);
    let offset = 0;
    for (const chunk of chunks) { result.set(chunk, offset); offset += chunk.byteLength; }
    return result;
  } finally {
    signal?.removeEventListener('abort', abort);
    if (!complete) await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}
async function consumeError(response, check, signal) {
  // Drain ordinary errors so transport completion timers finish. Oversized or
  // aborted bodies are cancelled, not left running. Do not retain raw diagnostics.
  try { await readBytes(response, 65536, check, signal); }
  catch { try { await response.body?.cancel(); } catch { /* reader already cancelled */ } }
}
async function fetchResponse(transport, path, options, check) {
  check();
  const response = await transport(path, { cache: 'no-store', redirect: 'error', ...options });
  try { check(); } catch (error) { await consumeError(response, () => {}, options.signal); throw error; }
  if (response.status !== 200) {
    await consumeError(response, check, options.signal);
    throw failure('OFFLINE_HTTP_ERROR', response.status);
  }
  return response;
}
async function jsonResponse(transport, path, options, check, accountBytes) {
  const response = await fetchResponse(transport, path, options, check);
  const bytes = await readBytes(response, JSON_BYTES, check, options.signal);
  accountBytes?.(bytes.byteLength);
  try { return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes)); }
  catch { throw failure('INVALID_OFFLINE_JSON'); }
}

function projectTrack(track) {
  requireValue(track && track.kind === 'track' && validID(track.id) &&
    typeof track.name === 'string' && track.hasAudio === true);
  const result = { kind: 'track', id: track.id, name: track.name, hasAudio: true, artists: [] };
  if (track.artists !== undefined) {
    requireValue(Array.isArray(track.artists));
    result.artists = track.artists.map(artist => {
      requireValue(artist && validID(artist.id) && typeof artist.name === 'string');
      return { id: artist.id, name: artist.name };
    });
  }
  // Positive projection only. Never retain download URLs, credentials, provider
  // diagnostics, filesystem paths or arbitrary response fields.
  for (const key of ['albumId', 'albumName', 'albumType', 'releaseDate', 'relation']) {
    if (typeof track[key] === 'string') result[key] = track[key];
  }
  for (const key of ['durationMs', 'discNumber', 'trackNumber']) {
    if (Number.isSafeInteger(track[key]) && track[key] >= 0) result[key] = track[key];
  }
  if (Array.isArray(track.genres) && track.genres.every(v => typeof v === 'string')) result.genres = track.genres;
  if (track.coverage && ['tracks', 'inLibrary', 'albums'].every(k => Number.isSafeInteger(track.coverage[k]) && track.coverage[k] >= 0)) {
    result.coverage = Object.fromEntries(['tracks', 'inLibrary', 'albums'].map(k => [k, track.coverage[k]]));
  }
  return result;
}
function receiptPayload(payload) {
  const fields = ['eventId', 'trackId', 'origin', 'occurredAtMs', 'elapsedMs', 'progressedMs'];
  requireValue(payload && Object.keys(payload).length === 6 && fields.every(k => Object.hasOwn(payload, k)), 'INVALID_RECEIPT');
  requireValue(validID(payload.eventId, 128) && validID(payload.trackId) && payload.origin === 'offline' &&
    ['occurredAtMs', 'elapsedMs', 'progressedMs'].every(k => Number.isSafeInteger(payload[k])) &&
    payload.progressedMs >= 1000 && payload.elapsedMs >= payload.progressedMs &&
    payload.occurredAtMs >= payload.elapsedMs, 'INVALID_RECEIPT');
  return Object.fromEntries(fields.map(k => [k, payload[k]]));
}

export async function openOfflineStore({ origin, accountId, indexedDB = globalThis.indexedDB, crypto = globalThis.crypto }) {
  const parsed = new URL(origin);
  requireValue(['http:', 'https:'].includes(parsed.protocol) && !parsed.username && !parsed.password &&
    parsed.pathname === '/' && !parsed.search && !parsed.hash && validID(accountId), 'INVALID_NAMESPACE');
  requireValue(indexedDB?.open && crypto?.subtle?.digest && crypto?.randomUUID, 'OFFLINE_STORAGE_UNAVAILABLE');
  origin = parsed.origin;
  const name = `spofify-web-offline-v1:${JSON.stringify([origin, accountId])}`;
  const db = await openDatabase(indexedDB, name);
  db.onversionchange = () => db.close();
  let namespaces = groups.get(indexedDB);
  if (!namespaces) { namespaces = new Map(); groups.set(indexedDB, namespaces); }
  let group = namespaces.get(name);
  if (!group) { group = { handles: new Set(), readers: new Map() }; namespaces.set(name, group); }
  const epoch = await transaction(db, ['meta'], 'readwrite', async stores => {
    const previous = await request(stores('meta').get('state'));
    if (previous && !previous.retired) return previous.epoch;
    const next = crypto.randomUUID();
    stores('meta').put({ epoch: next, retired: false }, 'state');
    return next;
  });
  let retired = false, uploading = false;
  const controllers = new Set();
  const handle = { epoch, closed: false,
    stop() { retired = true; for (const c of controllers) c.abort(); },
    close() { db.close(); this.closed = true; },
  };
  group.handles.add(handle);
  function check(signal) {
    if (retired) throw failure('STORE_RETIRED');
    if (signal?.aborted) throw failure('ABORTED');
  }
  async function fenced(stores, signal) {
    check(signal);
    const state = await request(stores('meta').get('state'));
    requireValue(state && !state.retired && state.epoch === epoch, 'STORE_RETIRED');
    check(signal);
  }
  function operation(signal) {
    check(signal);
    const controller = new AbortController();
    const abort = () => controller.abort();
    signal?.addEventListener('abort', abort, { once: true });
    controllers.add(controller);
    return { signal: controller.signal, end() { signal?.removeEventListener('abort', abort); controllers.delete(controller); } };
  }
  async function readCollections() {
    check();
    return transaction(db, ['meta', 'collections'], 'readonly', async s => {
      await fenced(s);
      return request(s('collections').getAll());
    });
  }
  async function drop(s, key) {
    const collection = await request(s('collections').get(key));
    if (!collection) return;
    for (const version of new Set(collection.items.map(item => item.mediaVersion))) {
      const media = await request(s('media').get(version));
      if (!media) continue;
      media.refs = media.refs.filter(ref => ref !== key);
      if (media.refs.length) s('media').put(media, version); else s('media').delete(version);
    }
    s('collections').delete(key);
  }
  async function remove(kind, id) {
    const key = collectionKey(kind, id);
    check();
    await transaction(db, ['meta', 'collections', 'media'], 'readwrite', async s => {
      await fenced(s);
      s('meta').put(crypto.randomUUID(), key);
      await drop(s, key);
    });
  }
  async function stageMedia(items, transport, signal, onProgress) {
    const guard = () => check(signal);
    const mediaByTrack = new Map(), staged = new Map();
    const totalTracks = new Set(items.map(item => item.track.id)).size;
    let byteLength = 0;
    for (const item of items) {
      guard();
      if (mediaByTrack.has(item.track.id)) { item.mediaVersion = mediaByTrack.get(item.track.id); continue; }
      const trackId = item.track.id;
      const media = await jsonResponse(transport, `${API}/tracks/${encodeURIComponent(trackId)}/media`, { signal }, guard);
      requireValue(media.version === 1 && media.trackId === trackId && /^[a-f0-9]{64}$/.test(media.sha256) &&
        media.mediaVersion === `sha256:${media.sha256}` && Number.isSafeInteger(media.byteLength) && media.byteLength > 0 &&
        typeof media.contentType === 'string' && /^audio\/[a-z0-9.+-]+$/.test(media.contentType));
      requireValue(media.byteLength <= MAX_MEDIA_BYTES, 'MEDIA_SIZE_LIMIT');
      const url = new URL(media.downloadUrl, origin);
      requireValue(typeof media.downloadUrl === 'string' && url.origin === origin && !url.username && !url.password && !url.hash &&
        url.pathname.startsWith(`${API}/tracks/`) &&
        decodeURIComponent(url.pathname.slice(`${API}/tracks/`.length)) === `${trackId}/audio` &&
        [...url.searchParams].length === 1 && url.searchParams.get('version') === media.mediaVersion, 'INVALID_MEDIA_URL');
      if (!staged.has(media.mediaVersion)) {
        byteLength += media.byteLength;
        requireValue(byteLength <= MAX_COLLECTION_BYTES, 'COLLECTION_SIZE_LIMIT');
        const existing = await transaction(db, ['meta', 'media'], 'readonly', async s => {
          await fenced(s, signal);
          return request(s('media').get(media.mediaVersion));
        });
        guard();
        if (existing) {
          requireValue(existing.byteLength === media.byteLength && existing.blob?.size === media.byteLength, 'CORRUPT_LOCAL_MEDIA');
          staged.set(media.mediaVersion, { blob: existing.blob, byteLength: existing.byteLength });
        } else {
          const response = await fetchResponse(transport, url.pathname + url.search, { signal }, guard);
          try {
            requireValue(response.headers.get('etag') === `"${media.mediaVersion}"`, 'MEDIA_ETAG_MISMATCH');
            const length = response.headers.get('content-length');
            requireValue(length === null || (/^[0-9]+$/.test(length) && Number(length) === media.byteLength), 'MEDIA_LENGTH_MISMATCH');
          } catch (error) { await consumeError(response, guard, signal); throw error; }
          const bytes = await readBytes(response, media.byteLength, guard, signal, media.byteLength);
          const hash = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
          guard();
          requireValue(Array.from(hash, b => b.toString(16).padStart(2, '0')).join('') === media.sha256, 'MEDIA_DIGEST_MISMATCH');
          staged.set(media.mediaVersion, { blob: new Blob([bytes], { type: media.contentType }), byteLength: bytes.byteLength });
        }
      } else requireValue(staged.get(media.mediaVersion).byteLength === media.byteLength, 'MEDIA_LENGTH_MISMATCH');
      item.mediaVersion = media.mediaVersion;
      mediaByTrack.set(trackId, media.mediaVersion);
      await onProgress?.({ phase: 'verified', verifiedTracks: mediaByTrack.size, totalTracks, byteLength });
      guard();
    }
    return { staged, byteLength };
  }
  async function install(s, key, collection, staged, signal) {
    await drop(s, key);
    for (const version of new Set(collection.items.map(item => item.mediaVersion))) {
      const data = staged.get(version);
      const existing = await request(s('media').get(version));
      const refs = existing?.refs || [];
      if (!refs.includes(key)) refs.push(key);
      s('media').put({ ...data, refs }, version);
    }
    check(signal);
    const sequence = ((await request(s('meta').get('sequence'))) || 0) + 1;
    s('meta').put(sequence, 'sequence');
    s('meta').put(sequence, `order:${key}`);
    s('collections').put(collection, key);
  }
  const store = {
    close() { handle.stop(); handle.close(); group.handles.delete(handle); },
    list: readCollections,
    async get(kind, id) {
      const key = collectionKey(kind, id);
      check();
      return transaction(db, ['meta', 'collections'], 'readonly', async s => {
        await fenced(s);
        return (await request(s('collections').get(key))) || null;
      });
    },
    async save({ kind, id, name: requestedName }, transport, { intent = 'pinned', signal, onProgress } = {}) {
      requireValue(Object.hasOwn(routes, kind), 'INVALID_COLLECTION');
      const key = collectionKey(kind, id);
      requireValue(intent === 'pinned' || intent === 'temporary', 'INVALID_INTENT');
      requireValue(typeof requestedName === 'string', 'INVALID_COLLECTION');
      check(signal);
      if (admitting) throw failure('OFFLINE_ADMISSION_BUSY');
      admitting = true;
      const op = operation(signal), guard = () => check(op.signal), token = crypto.randomUUID();
      try {
        await transaction(db, ['meta'], 'readwrite', async s => {
          await fenced(s, op.signal);
          s('meta').put(token, key);
        }, op.signal);
        const items = [], occurrences = new Set(), cursors = new Set();
        let cursor = null, snapshot, manifestBytes = 0, collectionName = requestedName;
        do {
          const path = `${API}/${routes[kind]}/${encodeURIComponent(id)}/manifest?limit=100${cursor === null ? '' : `&cursor=${encodeURIComponent(cursor)}`}`;
          const page = await jsonResponse(transport, path, { signal: op.signal }, guard, bytes => {
            manifestBytes += bytes;
            requireValue(manifestBytes <= MANIFEST_BYTES, 'MANIFEST_SIZE_LIMIT');
          });
          const identity = kind === 'playlist' ? page.playlist : page.collection;
          requireValue(page.version === 1 && typeof page.fence === 'string' && page.fence.length > 0 &&
            Number.isSafeInteger(page.total) && page.total >= 0 &&
            page.recordedAudioTotal === page.total && page.limit === 100 && Array.isArray(page.items) &&
            page.items.length <= page.limit && (page.nextCursor === null || (typeof page.nextCursor === 'string' && page.nextCursor.length > 0 && page.nextCursor.length <= 4096)) &&
            identity?.id === id && typeof identity.name === 'string' &&
            (kind === 'playlist' ? ['manual', 'derived'].includes(identity.kind) : identity.kind === kind));
          const signature = JSON.stringify([page.fence, page.total, identity]);
          if (snapshot === undefined) { snapshot = signature; collectionName = identity.name; }
          requireValue(signature === snapshot, 'STALE_MANIFEST');
          for (const item of page.items) {
            requireValue(item && validID(item.occurrenceId, 4096) && !occurrences.has(item.occurrenceId) && item.position === items.length);
            occurrences.add(item.occurrenceId);
            items.push({ occurrenceId: item.occurrenceId, position: item.position, track: projectTrack(item.track) });
          }
          requireValue(items.length <= page.total && (page.nextCursor === null ? items.length === page.total : page.items.length > 0 && items.length < page.total));
          cursor = page.nextCursor;
          if (cursor !== null) { requireValue(!cursors.has(cursor), 'CURSOR_LOOP'); cursors.add(cursor); }
        } while (cursor !== null);
        const { staged, byteLength } = await stageMedia(items, transport, op.signal, onProgress);
        const fence = JSON.parse(snapshot)[0];
        const collection = { kind, id, name: collectionName, fence, items, ready: true, intent, byteLength };
        await transaction(db, ['meta', 'collections', 'media'], 'readwrite', async s => {
          await fenced(s, op.signal);
          requireValue(await request(s('meta').get(key)) === token, 'SAVE_SUPERSEDED');
          const old = await request(s('collections').get(key));
          // A cache refresh cannot silently downgrade an explicitly pinned set.
          if (old?.intent === 'pinned') collection.intent = 'pinned';
          await install(s, key, collection, staged, op.signal);
        }, op.signal);
        return collection;
      } finally { op.end(); admitting = false; }
    },
    // Input is the caller's exact current + lookahead(count) projections, not a
    // catalog prefix or a synthetic server collection. At most four per call.
    async cacheTracks(tracks, transport, { signal, onProgress } = {}) {
      requireValue(Array.isArray(tracks) && tracks.length <= 4, 'INVALID_CACHE_TRACKS');
      const unique = [...new Map(tracks.map(track => [track?.id, projectTrack(track)])).values()];
      requireValue(new TextEncoder().encode(JSON.stringify(unique)).byteLength <= MANIFEST_BYTES, 'MANIFEST_SIZE_LIMIT');
      check(signal);
      if (admitting) throw failure('OFFLINE_ADMISSION_BUSY');
      admitting = true;
      const op = operation(signal), token = crypto.randomUUID();
      try {
        const keys = unique.map(track => collectionKey('temporary', track.id));
        await transaction(db, ['meta'], 'readwrite', async s => {
          await fenced(s, op.signal);
          for (const key of keys) s('meta').put(token, key);
        }, op.signal);
        const items = unique.map(track => ({ occurrenceId: `cache:${track.id}`, position: 0, track }));
        const { staged } = await stageMedia(items, transport, op.signal, onProgress);
        const collections = items.map(item => ({ kind: 'temporary', id: item.track.id, name: item.track.name,
          fence: item.mediaVersion, items: [item], ready: true, intent: 'temporary',
          byteLength: staged.get(item.mediaVersion).byteLength }));
        await transaction(db, ['meta', 'collections', 'media'], 'readwrite', async s => {
          await fenced(s, op.signal);
          // Farthest lookahead first, current last: a subsequent capacity trim
          // must not evict the just-cached current track ahead of its lookahead.
          for (let i = collections.length - 1; i >= 0; i--) {
            requireValue(await request(s('meta').get(keys[i])) === token, 'SAVE_SUPERSEDED');
            await install(s, keys[i], collections[i], staged, op.signal);
          }
        }, op.signal);
        return collections;
      } finally { op.end(); admitting = false; }
    },
    async acquire(trackId, mediaVersion) {
      requireValue(validID(trackId), 'INVALID_TRACK');
      check();
      const selected = await transaction(db, ['meta', 'collections', 'media'], 'readwrite', async s => {
        await fenced(s);
        const collections = await request(s('collections').getAll());
        let item;
        for (const collection of collections) {
          item = collection.items.find(i => i.track.id === trackId && (mediaVersion === undefined || i.mediaVersion === mediaVersion));
          if (item) {
            if (collection.intent === 'temporary') {
              const sequence = ((await request(s('meta').get('sequence'))) || 0) + 1;
              s('meta').put(sequence, 'sequence');
              s('meta').put(sequence, `order:${collectionKey(collection.kind, collection.id)}`);
            }
            break;
          }
        }
        if (!item) throw failure('OFFLINE_MEDIA_NOT_FOUND');
        const media = await request(s('media').get(item.mediaVersion));
        requireValue(media?.blob && media.blob.size === media.byteLength, 'CORRUPT_LOCAL_MEDIA');
        return { version: item.mediaVersion, blob: media.blob };
      });
      check();
      const url = URL.createObjectURL(selected.blob);
      group.readers.set(selected.version, (group.readers.get(selected.version) || 0) + 1);
      let released = false;
      return { url, release() {
        if (released) return;
        released = true;
        URL.revokeObjectURL(url);
        const count = group.readers.get(selected.version) - 1;
        if (count) group.readers.set(selected.version, count); else group.readers.delete(selected.version);
      } };
    },
    remove,
    async trimTemporary(count) {
      requireValue(Number.isSafeInteger(count) && count >= 0, 'INVALID_CACHE_COUNT');
      check();
      return transaction(db, ['meta', 'collections', 'media'], 'readwrite', async s => {
        await fenced(s);
        const collections = await request(s('collections').getAll());
        const temporary = collections.filter(c => c.intent === 'temporary');
        const order = new Map();
        for (const c of temporary) {
          const key = collectionKey(c.kind, c.id);
          order.set(key, (await request(s('meta').get(`order:${key}`))) || 0);
        }
        temporary.sort((a, b) => order.get(collectionKey(a.kind, a.id)) - order.get(collectionKey(b.kind, b.id)));
        const pinned = new Set(collections.filter(c => c.intent === 'pinned').flatMap(c => c.items.map(i => i.track.id)));
        const tracks = () => new Set(temporary.flatMap(c => c.items.map(i => i.track.id)).filter(id => !pinned.has(id)));
        // Count distinct temporary track identities, not occurrences or bytes.
        // Collections stay complete; play-promoted FIFO may undershoot the target.
        let removed = 0;
        while (tracks().size > count) {
          const index = temporary.findIndex(c => !c.items.some(i => group.readers.has(i.mediaVersion)));
          if (index === -1) break;
          const [victim] = temporary.splice(index, 1);
          const key = collectionKey(victim.kind, victim.id);
          s('meta').put(crypto.randomUUID(), key);
          await drop(s, key);
          removed++;
        }
        return { removed, retainedTracks: tracks().size };
      });
    },
    async retire() {
      if (handle.closed) return;
      // Synchronous local fence first, then the persistent transaction prevents
      // writes from other tabs/old handles publishing after the purge commits.
      for (const other of group.handles) if (other.epoch === epoch) other.stop();
      await transaction(db, ['meta', 'collections', 'media', 'receipts'], 'readwrite', async s => {
        const state = await request(s('meta').get('state'));
        if (state?.epoch !== epoch) return;
        for (const name of ['meta', 'collections', 'media', 'receipts']) s(name).clear();
        s('meta').put({ epoch, retired: true }, 'state');
      });
      for (const other of group.handles) {
        if (other.epoch === epoch) { other.close(); group.handles.delete(other); }
      }
      // Existing object URLs are snapshots: removal/purge never interrupts a
      // reader. The audio owner must stop/release its leases at account switch.
    },
    async estimate() {
      check();
      const storage = globalThis.navigator?.storage;
      const [estimate, persisted] = await Promise.all([
        storage?.estimate ? storage.estimate() : undefined,
        storage?.persisted ? storage.persisted() : undefined,
      ]);
      check();
      return { usage: estimate?.usage ?? null, quota: estimate?.quota ?? null,
        persisted: persisted ?? null, scope: 'browser-origin', evictionPossible: persisted !== true,
        maxMediaBytes: MAX_MEDIA_BYTES, maxCollectionBytes: MAX_COLLECTION_BYTES };
    },
    async requestPersistence() {
      check();
      const storage = globalThis.navigator?.storage;
      const result = storage?.persist ? await storage.persist() : false;
      check();
      return result;
    },
    async appendReceipt(payload) {
      check();
      const immutable = receiptPayload(payload);
      await transaction(db, ['meta', 'receipts'], 'readwrite', async s => {
        await fenced(s);
        const old = await request(s('receipts').get(immutable.eventId));
        requireValue(!old || JSON.stringify(old.payload) === JSON.stringify(immutable), 'RECEIPT_CONFLICT');
        if (!old) s('receipts').put({ payload: immutable, diagnostic: null, pending: 1, acked: false, ack: null }, immutable.eventId);
      });
    },
    // Exact totals independent of the bounded journal/history read batches.
    async receiptCounts() {
      check();
      return transaction(db, ['meta', 'receipts'], 'readonly', async s => {
        await fenced(s);
        const receipts = s('receipts');
        const [pending, total] = await Promise.all([
          request(receipts.index('pending').count(1)),
          request(receipts.count()),
        ]);
        return { pending, total };
      });
    },
    async pendingReceipts() {
      check();
      return transaction(db, ['meta', 'receipts'], 'readonly', async s => {
        await fenced(s);
        const records = await request(s('receipts').index('pending').getAll(1, RECEIPT_BATCH));
        return records.map(({ payload, diagnostic }) => ({ payload, diagnostic }));
      });
    },
    // Latest actual local occurrences, before AND after upload. No invented
    // metadata/history rows; ACK state never changes the original six fields.
    async localHistory() {
      check();
      return transaction(db, ['meta', 'receipts'], 'readonly', async s => {
        await fenced(s);
        return new Promise((resolve, reject) => {
          const records = [];
          const cursor = s('receipts').index('occurredAtMs').openCursor(undefined, 'prev');
          cursor.onerror = () => reject(cursor.error);
          cursor.onsuccess = () => {
            const row = cursor.result;
            if (!row || records.length === RECEIPT_BATCH) { resolve(records); return; }
            const { payload, diagnostic, acked, ack } = row.value;
            records.push({ payload, diagnostic, acked, ack });
            row.continue();
          };
        });
      });
    },
    async acknowledgeReceipt(eventId) {
      check();
      await transaction(db, ['meta', 'receipts'], 'readwrite', async s => {
        await fenced(s);
        const current = await request(s('receipts').get(eventId));
        if (current) s('receipts').put({ ...current, pending: undefined, acked: true, diagnostic: null }, eventId);
      });
    },
    async uploadReceipts(transport, { signal } = {}) {
      check(signal);
      if (uploading) throw failure('RECEIPT_UPLOAD_BUSY');
      uploading = true;
      const op = operation(signal), guard = () => check(op.signal);
      let uploaded = 0;
      try {
        const batch = await store.pendingReceipts();
        for (const record of batch) {
          guard();
          const payload = receiptPayload(record.payload);
          try {
            const ack = await jsonResponse(transport, `${API}/play-receipts`, {
              method: 'POST', headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify(payload), signal: op.signal,
            }, guard);
            requireValue(ack?.eventId === payload.eventId && Number.isSafeInteger(ack.receivedAtMs) &&
              ack.receivedAtMs >= 0 && typeof ack.duplicate === 'boolean', 'INVALID_RECEIPT_ACK');
            await transaction(db, ['meta', 'receipts'], 'readwrite', async s => {
              await fenced(s, op.signal);
              const current = await request(s('receipts').get(payload.eventId));
              requireValue(current && JSON.stringify(current.payload) === JSON.stringify(payload), 'RECEIPT_CONFLICT');
              s('receipts').put({ ...current, pending: undefined, acked: true, diagnostic: null, ack }, payload.eventId);
            }, op.signal);
            uploaded++;
          } catch (error) {
            guard();
            const diagnostic = { status: error.status ?? null, code: error.code || 'RECEIPT_TRANSPORT_FAILED' };
            await transaction(db, ['meta', 'receipts'], 'readwrite', async s => {
              await fenced(s, op.signal);
              const current = await request(s('receipts').get(payload.eventId));
              if (current) s('receipts').put({ ...current, diagnostic }, payload.eventId);
            }, op.signal);
            // No fallback, retimestamp, immediate retry, or skipping past an auth
            // failure/conflict. Caller schedules a later bounded retry explicitly.
            return { uploaded, diagnostic };
          }
        }
        return { uploaded, diagnostic: null };
      } finally { op.end(); uploading = false; }
    },
  };
  return store;
}
