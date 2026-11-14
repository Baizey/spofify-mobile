// Socket-free transport/contract tests, not browser or online-player acceptance.
import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { createHash } from 'node:crypto';
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Readable } from 'node:stream';

// A hard guard: even accidental future listeners fail these tests before binding.
const originalListen = http.Server.prototype.listen;
http.Server.prototype.listen = function () { throw new Error('Tests must never listen'); };
const { createFixture } = await import('../tools/fixture.js');
test.after(() => { http.Server.prototype.listen = originalListen; });
const ORIGIN = 'http://fixture.invalid:43189';
const HOST = 'fixture.invalid:43189';
const RECEIPTS = '/api/me/offline/v1/play-receipts';
const MANIFEST = '/api/me/offline/v1/playlists/fixture-playlist/manifest';
const STATE = 'fixture-receipts.json';
const PASSWORD = 'synthetic-fixture-only';

function setup(t) {
  const root = mkdtempSync(join(tmpdir(), 'spofify-synthetic-'));
  const source = join(root, 'public'), stateRoot = join(root, 'state');
  mkdirSync(source, { mode: 0o700 }); mkdirSync(stateRoot, { mode: 0o700 });
  writeFileSync(join(source, 'index.html'), '<!doctype html><title>Synthetic public shell</title>');
  writeFileSync(join(source, 'styles.css'), 'body{color:green}');
  writeFileSync(join(source, 'sw.js'), '// synthetic service worker');
  writeFileSync(join(source, 'package.json'), '{"privateSecret":"never serve"}');
  const fixture = createFixture({ source, stateRoot, origin: ORIGIN });
  t.after(async () => { await fixture.close(); rmSync(root, { recursive: true, force: true }); });
  return { ...fixture, root, source, stateRoot };
}
async function request(f, path, { method = 'GET', headers = {}, body, raw, rawHeaders, onEnd } = {}) {
  let bytes = raw === undefined ? (body === undefined ? undefined : Buffer.from(JSON.stringify(body))) : Buffer.from(raw);
  const req = Readable.from(bytes === undefined ? [] : [bytes]);
  Object.assign(req, { method, url: path, headers: { host: HOST, ...(bytes === undefined ? {} : { 'content-type': 'application/json' }), ...headers }, rawHeaders });
  const values = new Map();
  const res = {
    statusCode: 200, headersSent: false, ended: false,
    setHeader(key, value) { assert.equal(this.headersSent, false); values.set(key.toLowerCase(), value); },
    getHeader(key) { return values.get(key.toLowerCase()); },
    end(value) { this.headersSent = true; this.ended = true; this.bytes = Buffer.from(value || []); onEnd?.(this); },
    destroy() { throw new Error('Unexpected response destruction'); },
  };
  await f.handler(req, res);
  assert.equal(res.ended, true);
  const text = res.bytes.toString();
  return { status: res.statusCode, headers: Object.fromEntries(values), bytes: res.bytes, text,
    json: values.get('content-type')?.startsWith('application/json') && text ? JSON.parse(text) : undefined };
}
async function login(f, extra = {}) {
  const r = await request(f, '/api/auth/login', { method: 'POST', headers: { origin: ORIGIN, 'x-spofify-login': '1', ...extra }, body: { username: 'fixture-listener', password: PASSWORD } });
  assert.equal(r.status, 200, r.text);
  assert.deepEqual(Object.keys(r.json).sort(), ['accountId', 'csrfToken', 'role', 'username']);
  assert.equal(r.json.role, 'user');
  const headers = { cookie: r.headers['set-cookie'].split(';')[0], origin: ORIGIN, 'x-csrf-token': r.json.csrfToken };
  return { headers, session: r.json, response: r };
}
const receipt = (overrides = {}) => ({ eventId: 'fixture-event-1', trackId: 'fixture-tone-a', origin: 'offline',
  occurredAtMs: Date.now() - 5000, elapsedMs: 1500, progressedMs: 1000, ...overrides });
const post = (f, auth, payload, extra = {}) => request(f, RECEIPTS, { method: 'POST', headers: auth.headers, body: payload, ...extra });

// Tests intentionally use no fetch, HTTP clients, localhost, ports, subprocesses,
// browser mocks or dependencies. Every response comes directly from the handler.
test('import creates no listener; strict roots/origins and exclusive state ownership', async t => {
  const f = setup(t);
  assert.deepEqual(readdirSync(f.stateRoot), [STATE]);
  assert.equal(statSync(join(f.stateRoot, STATE)).mode & 0o777, 0o600);
  assert.equal(JSON.parse(readFileSync(join(f.stateRoot, STATE))).lifecycle, 'running');
  assert.throws(() => createFixture({ source: f.source, stateRoot: f.stateRoot, origin: ORIGIN }), /EEXIST/);
  for (const origin of ['http://fixture.invalid:0', 'http://fixture.invalid', 'http://0.0.0.0:43189', 'http://[::]:43189',
    'http://fixture.invalid:43189/', 'http://fixture.invalid:43189/path', 'http://user@fixture.invalid:43189',
    'http://fixture.invalid:43189#', 'http://fixture.invalid:43189?x', 'http://*.invalid:43189', 'file:///tmp/fixture']) {
    assert.throws(() => createFixture({ source: f.source, stateRoot: f.stateRoot, origin }));
  }
  chmodSync(f.stateRoot, 0o755);
  assert.throws(() => createFixture({ source: f.source, stateRoot: f.stateRoot, origin: ORIGIN }), /private/);
  chmodSync(f.stateRoot, 0o700);
  assert.throws(() => createFixture({ source: f.source, stateRoot: 'relative', origin: ORIGIN }));
});

test('public explicit /app/ shell has strict PWA CSP; no account, source or traversal exposure', async t => {
  const f = setup(t);
  for (const path of ['/app/', '/app/index.html', '/app/styles.css', '/app/sw.js']) {
    const r = await request(f, path);
    assert.equal(r.status, 200, path);
    assert.match(r.headers['cache-control'], /^public/);
    assert.match(r.headers['content-security-policy'], /script-src 'self'; style-src 'self'/);
    assert.match(r.headers['content-security-policy'], /media-src 'self' blob:/);
    assert.doesNotMatch(r.headers['content-security-policy'], /unsafe-inline|unsafe-eval|\*/);
    assert.equal(r.headers['service-worker-allowed'], '/app/');
    assert.equal(r.headers['set-cookie'], undefined);
    assert.equal(Number(r.headers['content-length']), r.bytes.length);
  }
  const head = await request(f, '/app/index.html', { method: 'HEAD' });
  assert.equal(head.status, 200); assert.equal(head.bytes.length, 0); assert.ok(Number(head.headers['content-length']) > 0);
  for (const path of ['/app/package.json', '/app/.git/config', '/app/tools/fixture.js', '/app/test/fixture.test.js', '/app/fixture-receipts.json', '/app/unknown.js']) {
    assert.equal((await request(f, path)).status, 404, path);
  }
  for (const path of ['/app/../api/auth/session', '/app/%2e%2e/index.html', '/app/%69ndex.html', '/app//index.html', '/app\\index.html']) {
    assert.equal((await request(f, path)).status, 400, path);
  }
  assert.equal((await request(f, '/app/?x=1')).status, 400);
  assert.equal((await request(f, '/app/', { method: 'POST' })).status, 405);
  assert.equal((await request(f, '/app/', { headers: { host: 'evil.invalid:43189' } })).status, 421);
});

test('generated release without package.json exposes exactly the parent public bundle allowlist', async t => {
  const f = setup(t);
  rmSync(join(f.source, 'package.json'));
  mkdirSync(join(f.source, 'assets')); mkdirSync(join(f.source, 'vendor'));
  const files = ['index.html', 'styles.css', 'app.js', 'transport.js', 'online-player.js', 'local-player.js', 'audio-owner.js',
    'gestures.js', 'offline-store.js', 'progress-journal.js', 'vendor/audio-adapter.js', 'vendor/playback-sync.js',
    'manifest.webmanifest', 'assets/JetBrainsMono.ttf', 'assets/OFL.txt', 'assets/license.js', 'assets/icon.svg',
    'assets/icon-192.png', 'assets/icon-512.png', 'sw.js', 'release.json'];
  for (const file of files) {
    const contents = file === 'release.json' ? '{"synthetic":true}' : `synthetic public ${file}`;
    writeFileSync(join(f.source, file), contents);
    const r = await request(f, `/app/${file}`);
    assert.equal(r.status, 200, file); assert.equal(r.text, contents);
    assert.equal(r.headers['set-cookie'], undefined);
  }
  assert.equal((await request(f, '/app/release.json')).headers['content-type'], 'application/json; charset=utf-8');
  assert.equal((await request(f, '/app/manifest.webmanifest')).headers['content-type'], 'application/manifest+json');
});

test.skip('symlink root/static-file rejection: environment denied symlink creation; no retry attempted', () => {});

test('synthetic auth real session shape, Strict cookie, exact Host/Origin/CSRF and logout', async t => {
  const f = setup(t);
  assert.equal((await request(f, '/api/auth/session')).status, 401);
  assert.equal((await request(f, '/__fixture/summary')).status, 401);
  assert.equal((await request(f, '/api/auth/login', { method: 'POST', body: { username: 'fixture-listener', password: PASSWORD } })).status, 403);
  assert.equal((await request(f, '/api/auth/login', { method: 'POST', headers: { origin: ORIGIN, 'x-spofify-login': '1' }, body: { username: 'fixture-listener', password: 'not-valid' } })).status, 401);
  const auth = await login(f);
  assert.match(auth.response.headers['set-cookie'], /HttpOnly; SameSite=Strict/);
  assert.equal(auth.response.headers['cache-control'], 'no-store');
  const session = await request(f, '/api/auth/session', { headers: auth.headers });
  assert.deepEqual(session.json, auth.session);
  assert.equal((await request(f, '/api/auth/session?', { headers: auth.headers })).status, 400);
  for (const [headers, expected] of [
    [{ host: 'evil.invalid:43189' }, 421], [{ host: `${HOST}, ${HOST}` }, 421],
    [{ origin: 'http://evil.invalid:43189' }, 403], [{ origin: 'null' }, 403],
    [{ 'sec-fetch-site': 'cross-site' }, 403], [{ 'sec-fetch-site': 'garbage' }, 403],
    [{ cookie: `${auth.headers.cookie}; ${auth.headers.cookie}` }, 401],
  ]) assert.equal((await request(f, '/api/auth/session', { headers: { ...auth.headers, ...headers } })).status, expected);
  assert.equal((await request(f, '/api/auth/session', { headers: auth.headers,
    rawHeaders: ['Host', HOST, 'hOsT', HOST] })).status, 421);
  assert.equal((await post(f, { headers: { ...auth.headers, origin: undefined } }, receipt())).status, 403);
  assert.equal((await post(f, { headers: { ...auth.headers, 'x-csrf-token': 'é'.repeat(64) } }, receipt())).status, 403);
  assert.equal((await post(f, auth, receipt(), { rawHeaders: ['Origin', ORIGIN, 'Origin', ORIGIN] })).status, 403);
  const again = await login(f);
  assert.equal((await request(f, '/api/auth/session', { headers: auth.headers })).status, 401);
  assert.equal((await request(f, '/api/auth/logout', { method: 'POST', headers: again.headers })).status, 204);
  assert.equal((await request(f, '/api/auth/session', { headers: again.headers })).status, 401);
});

test('manual playlist and entries preserve three exact occurrences including duplicate track', async t => {
  const f = setup(t), { headers } = await login(f);
  const r = await request(f, '/api/me/playlists?limit=1&offset=0', { headers });
  assert.equal(r.status, 200); assert.equal(r.json.total, 1);
  assert.deepEqual(r.json.items.map(({ id, kind, name, revision, availableCount, trackCount, countState }) =>
    ({ id, kind, name, revision, availableCount, trackCount, countState })), [{ id: 'fixture-playlist', kind: 'manual',
    name: 'Offline sound check', revision: 1, availableCount: 3, trackCount: 3, countState: 'exact' }]);
  const e = await request(f, '/api/me/playlists/fixture-playlist/entries?limit=2&offset=1', { headers });
  assert.equal(e.json.total, 3); assert.deepEqual(e.json.items.map(i => i.position), [1, 2]);
  const all = await request(f, '/api/me/playlists/fixture-playlist/entries', { headers });
  assert.equal(new Set(all.json.items.map(i => i.id)).size, 3);
  assert.equal(new Set(all.json.items.map(i => i.track.id)).size, 2);
  assert.equal(all.json.items[0].track.id, all.json.items[2].track.id);
  for (const suffix of ['?limit=0', '?limit=101', '?offset=-1', '?limit=2&limit=2', '?accountId=other']) {
    assert.equal((await request(f, `/api/me/playlists${suffix}`, { headers })).status, 400, suffix);
  }
  assert.equal((await request(f, '/api/me/playlists', { method: 'POST', headers, body: {} })).status, 405);
});

test('manifest pages have coherent fence/count/order; signed limit-bound cursors reject tampering', async t => {
  const f = setup(t), { headers } = await login(f);
  let path = `${MANIFEST}?limit=1`, fence; const rows = [];
  let firstCursor;
  while (path) {
    const r = await request(f, path, { headers }); assert.equal(r.status, 200, r.text);
    assert.equal(r.headers['cache-control'], 'no-store');
    const p = r.json;
    assert.equal(p.version, 1); assert.equal(p.total, 3); assert.equal(p.recordedAudioTotal, 3); assert.equal(p.limit, 1);
    if (!fence) fence = p.fence; else assert.equal(p.fence, fence);
    rows.push(...p.items);
    if (!firstCursor) firstCursor = p.nextCursor;
    path = p.nextCursor ? `${MANIFEST}?cursor=${encodeURIComponent(p.nextCursor)}` : null;
  }
  assert.deepEqual(rows.map(i => i.position), [0, 1, 2]);
  assert.equal(new Set(rows.map(i => i.occurrenceId)).size, 3);
  assert.equal(new Set(rows.map(i => i.track.id)).size, 2);
  assert.ok(rows.every(i => i.track.hasAudio && i.track.durationMs === 8000));
  const q = encodeURIComponent(firstCursor);
  for (const suffix of [`?cursor=${q}&limit=2`, `?cursor=${q.slice(0, -1)}!`, '?cursor=forged', '?limit=2&limit=2', '?owner=other']) {
    assert.equal((await request(f, MANIFEST + suffix, { headers })).status, 400, suffix);
  }
  assert.equal((await request(f, `${MANIFEST}?cursor=${q}&limit=1`, { headers })).status, 200);
  const actualNow = Date.now;
  try { Date.now = () => actualNow() + 900001; assert.equal((await request(f, `${MANIFEST}?cursor=${q}`, { headers })).status, 400); }
  finally { Date.now = actualNow; }
});

test('PCM media is audible synthetic 8-second sine with actual digest, length, validators and ranges', async t => {
  const f = setup(t), { headers } = await login(f);
  const hashes = [];
  for (const id of ['fixture-tone-a', 'fixture-tone-b']) {
    const path = `/api/me/offline/v1/tracks/${id}/media`;
    const descriptor = await request(f, path, { headers }); assert.equal(descriptor.status, 200);
    const m = descriptor.json, audio = await request(f, m.downloadUrl, { headers });
    assert.equal(audio.status, 200); assert.equal(audio.headers['cache-control'], 'no-store');
    assert.equal(m.contentType, 'audio/wav'); assert.equal(m.format, 'wav'); assert.equal(m.trackId, id);
    assert.equal(m.byteLength, audio.bytes.length); assert.equal(Number(audio.headers['content-length']), m.byteLength);
    assert.equal(createHash('sha256').update(audio.bytes).digest('hex'), m.sha256);
    assert.equal(audio.headers.etag, `"sha256:${m.sha256}"`); hashes.push(m.sha256);
    const b = audio.bytes;
    assert.equal(b.toString('ascii', 0, 4), 'RIFF'); assert.equal(b.toString('ascii', 8, 12), 'WAVE');
    assert.equal(b.readUInt16LE(20), 1); assert.equal(b.readUInt16LE(22), 1); assert.equal(b.readUInt16LE(34), 16);
    const rate = b.readUInt32LE(24); assert.equal(b.readUInt32LE(40) / 2 / rate, 8);
    let peak = 0, energy = 0;
    for (let i = 44; i < b.length; i += 2) { const v = b.readInt16LE(i) / 32767; peak = Math.max(peak, Math.abs(v)); energy += v * v; }
    assert.ok(peak > 0.079 && peak < 0.081); assert.ok(Math.sqrt(energy / ((b.length - 44) / 2)) > 0.05);
    const head = await request(f, m.downloadUrl, { method: 'HEAD', headers });
    assert.equal(head.bytes.length, 0); assert.equal(Number(head.headers['content-length']), m.byteLength);
    assert.equal(head.headers.etag, audio.headers.etag);
    const partial = await request(f, m.downloadUrl, { headers: { ...headers, range: 'bytes=44-143', 'if-range': audio.headers.etag } });
    assert.equal(partial.status, 206); assert.equal(partial.headers['content-range'], `bytes 44-143/${b.length}`);
    assert.equal(Number(partial.headers['content-length']), 100); assert.deepEqual(partial.bytes, b.subarray(44, 144));
    const suffix = await request(f, m.downloadUrl, { headers: { ...headers, range: 'bytes=-20' } });
    assert.equal(suffix.status, 206); assert.deepEqual(suffix.bytes, b.subarray(-20));
    const noAppend = await request(f, m.downloadUrl, { headers: { ...headers, range: 'bytes=44-', 'if-range': '"wrong"' } });
    assert.equal(noAppend.status, 200); assert.equal(noAppend.bytes.length, b.length);
    const unchanged = await request(f, m.downloadUrl, { headers: { ...headers, 'if-none-match': audio.headers.etag } });
    assert.equal(unchanged.status, 304); assert.equal(unchanged.bytes.length, 0); assert.equal(unchanged.headers['content-length'], undefined);
    for (const range of ['bytes=999999999-', 'bytes=20-10', 'bytes=-0', 'bytes=0-1,3-4', 'garbage']) {
      const r = await request(f, m.downloadUrl, { headers: { ...headers, range } });
      assert.equal(r.status, 416, range); assert.equal(r.headers['content-range'], `bytes */${b.length}`);
    }
    assert.equal((await request(f, path + '?x=1', { headers })).status, 400);
    assert.equal((await request(f, m.downloadUrl.replace(m.sha256, '0'.repeat(64)), { headers })).status, 409);
    assert.equal((await request(f, m.downloadUrl + '&version=bad', { headers })).status, 400);
    assert.equal((await request(f, m.downloadUrl.split('?')[0], { headers })).status, 400);
    assert.equal((await request(f, m.downloadUrl)).status, 401);
  }
  assert.notEqual(hashes[0], hashes[1]);
});

test('receipt ACK follows atomic durable commit; typed duplicate, conflict and clear preserve lifetime ledger', async t => {
  const f = setup(t), auth = await login(f), p = receipt();
  let atAck;
  const first = await post(f, auth, p, { onEnd: () => { atAck = JSON.parse(readFileSync(join(f.stateRoot, STATE))); } });
  assert.equal(first.status, 200); assert.equal(first.json.duplicate, false);
  assert.deepEqual(atAck.receipts, [{ payload: p, receivedAtMs: first.json.receivedAtMs }]);
  assert.deepEqual(atAck.history, [p.eventId]); assert.deepEqual(readdirSync(f.stateRoot), [STATE]);
  const reordered = `{ "progressedMs":1000,"elapsedMs":1500,"occurredAtMs":${p.occurredAtMs},"origin":"off\\u006cine","trackId":"fixture-tone-a","eventId":"${p.eventId}" }`;
  const duplicate = await post(f, auth, undefined, { raw: reordered });
  assert.equal(duplicate.status, 200); assert.deepEqual(duplicate.json, { ...first.json, duplicate: true });
  for (const changes of [{ trackId: 'fixture-tone-b' }, { origin: 'online' }, { elapsedMs: 1600 }, { progressedMs: 1100 }, { occurredAtMs: p.occurredAtMs + 1 }]) {
    assert.equal((await post(f, auth, { ...p, ...changes })).status, changes.origin ? 400 : 409);
  }
  const history = await request(f, '/api/me/history?limit=10&offset=0', { headers: auth.headers });
  assert.equal(history.json.total, 1);
  assert.deepEqual(history.json.items[0], { id: p.eventId, playedAt: new Date(p.occurredAtMs).toISOString(), origin: 'offline',
    receivedAt: new Date(first.json.receivedAtMs).toISOString(), track: { kind: 'track', id: 'fixture-tone-a', name: 'Synthetic 440 Hz tone',
      artists: [{ id: 'fixture-generator', name: 'Synthetic signal generator' }], durationMs: 8000, hasAudio: true, coverage: { tracks: 1, inLibrary: 1, albums: 0 } } });
  assert.equal((await request(f, '/api/me/history', { method: 'DELETE', headers: auth.headers })).status, 204);
  const originalNow = Date.now;
  try {
    Date.now = () => 1000; // ledger lookup precedes future-time validation
    const retry = await post(f, auth, p); assert.equal(retry.status, 200); assert.equal(retry.json.receivedAtMs, first.json.receivedAtMs);
    assert.equal(retry.json.duplicate, true);
  } finally { Date.now = originalNow; }
  assert.equal((await request(f, '/api/me/history', { headers: auth.headers })).json.total, 0);
  const saved = JSON.parse(readFileSync(join(f.stateRoot, STATE)));
  assert.equal(saved.receipts.length, 1); assert.deepEqual(saved.history, []);
  assert.equal((await post(f, auth, { ...p, elapsedMs: 2000 })).status, 409);
  const inspected = await request(f, '/__fixture/summary', { headers: auth.headers });
  assert.equal(inspected.json.acceptedReceipts, 1); assert.equal(inspected.json.duplicateReceipts, 2);
  assert.deepEqual(inspected.json.receipts, [p]); assert.equal(inspected.headers['cache-control'], 'no-store');
});

test('receipt fields are strictly exact, required, typed and bounded; no accidental ACK', async t => {
  const f = setup(t), auth = await login(f), p = receipt();
  for (const key of Object.keys(p)) {
    const missing = { ...p }; delete missing[key]; assert.equal((await post(f, auth, missing)).status, 400, `missing ${key}`);
    assert.equal((await post(f, auth, { ...p, [key]: null })).status, 400, `null ${key}`);
  }
  for (const changes of [{ eventId: '' }, { eventId: ' padded' }, { eventId: 'control\n' }, { eventId: 'x'.repeat(129) },
    { trackId: '' }, { trackId: 'x'.repeat(513) }, { trackId: '\ud800' }, { origin: 'online' },
    { elapsedMs: 999 }, { progressedMs: 999 }, { progressedMs: 1501 }, { occurredAtMs: 1000 },
    { occurredAtMs: -1 }, { occurredAtMs: 8640000000000001 }, { elapsedMs: '1500' }, { progressedMs: true },
    { accountId: 'other' }, { EventId: 'case' }]) assert.equal((await post(f, auth, { ...p, ...changes })).status, 400, JSON.stringify(changes));
  const serialized = JSON.stringify(p);
  for (const raw of ['[]', 'null', '1', serialized + '{}', serialized.replace('1500', '1500.0'), serialized.replace('1500', '1.5e3'),
    serialized.replace('1500', '9223372036854775808'), serialized.replace('"eventId":', '"eventId":"duplicate","eventId":'),
    serialized.replace('"eventId":', '"eventId":"duplicate","event\\u0049d":'), serialized.replace('"elapsedMs":1500', '"elapsedMs":{}')]) {
    assert.equal((await post(f, auth, undefined, { raw })).status, 400, raw);
  }
  assert.equal((await post(f, auth, undefined, { raw: Buffer.from([0xff]) })).status, 400);
  assert.equal((await post(f, auth, undefined, { raw: ' '.repeat(65537) })).status, 413);
  assert.equal((await post(f, auth, p, { headers: { ...auth.headers, 'content-type': 'text/plain' } })).status, 415);
  assert.equal((await request(f, RECEIPTS + '?x=1', { method: 'POST', headers: auth.headers, body: p })).status, 400);
  assert.equal((await request(f, RECEIPTS, { headers: auth.headers })).status, 405);
  assert.equal(f.summary().receiptCount, 0);
});

test('future boundary, no past-age cutoff, missing metadata and recent distinct-track ordering', async t => {
  const f = setup(t), auth = await login(f), now = 1800000000000, originalNow = Date.now;
  try {
    Date.now = () => now;
    assert.equal((await post(f, auth, receipt({ eventId: 'future-rejected', occurredAtMs: now + 300001 }))).status, 422);
    assert.equal(f.summary().receiptCount, 0);
    assert.equal((await post(f, auth, receipt({ eventId: 'future-inclusive', occurredAtMs: now + 300000 }))).status, 200);
    assert.equal((await post(f, auth, receipt({ eventId: 'ancient', occurredAtMs: 1500, trackId: 'missing-track' }))).status, 200);
    assert.equal((await post(f, auth, receipt({ eventId: 'tone-again', occurredAtMs: now - 2000 }))).status, 200);
  } finally { Date.now = originalNow; }
  const history = await request(f, '/api/me/history', { headers: auth.headers });
  assert.deepEqual(history.json.items.map(i => i.id), ['future-inclusive', 'tone-again', 'ancient']);
  const unavailable = history.json.items[2]; assert.equal(unavailable.metadataMissing, true);
  assert.deepEqual(unavailable.track, { id: 'missing-track', kind: 'track', name: '', hasAudio: false, coverage: { tracks: 1, inLibrary: 0, albums: 0 } });
  for (const path of ['/api/me/recent', '/api/me/history/recent']) {
    const r = await request(f, path, { headers: auth.headers });
    assert.equal(r.status, 200); assert.equal(r.json.total, 2); assert.equal(r.json.limit, 12);
    assert.deepEqual(r.json.items.map(i => i.id), ['future-inclusive', 'ancient']);
  }
  assert.equal((await request(f, '/api/me/recent?offset=1', { headers: auth.headers })).status, 400);
});

test('Jade palette matches exact version 1 catalog and does not permit mutations', async t => {
  const f = setup(t), { headers } = await login(f);
  const r = await request(f, '/api/me/palette', { headers });
  assert.equal(r.status, 200); assert.equal(r.json.accountId, 'fixture-account');
  assert.equal(r.json.paletteId, 'jade'); assert.equal(r.json.revision, 0); assert.equal(r.json.catalogVersion, 1); assert.equal(r.json.defaultPaletteId, 'jade');
  assert.deepEqual(r.json.presets, [
    { id: 'jade', name: 'Jade', dark: { primary: '#66e3b4', onPrimary: '#08271d', secondary: '#91bfff', tertiary: '#dfbd78' }, light: { primary: '#08704e', onPrimary: '#ffffff', secondary: '#2759a4', tertiary: '#77551b' } },
    { id: 'ember', name: 'Ember', dark: { primary: '#ffad89', onPrimary: '#31170d', secondary: '#c4b0ff', tertiary: '#a4d7bd' }, light: { primary: '#aa431b', onPrimary: '#ffffff', secondary: '#654199', tertiary: '#23644a' } },
    { id: 'violet', name: 'Violet', dark: { primary: '#c8b5ff', onPrimary: '#22183c', secondary: '#6de2d5', tertiary: '#e8b4c5' }, light: { primary: '#6442a5', onPrimary: '#ffffff', secondary: '#0c6d67', tertiary: '#8b465e' } },
  ]);
  assert.equal((await request(f, '/api/me/palette?accountId=other', { headers })).status, 400);
  assert.equal((await request(f, '/api/me/palette', { method: 'PUT', headers, body: {} })).status, 405);
});

test('summary is bounded/redacted metadata; unknown application mutations never call a real app', async t => {
  const f = setup(t), auth = await login(f);
  for (let i = 0; i < 150; i++) {
    await request(f, `/unrecognized-${i}?secret=${PASSWORD}`, { headers: { ...auth.headers, authorization: 'secret-token' } });
  }
  assert.equal((await request(f, '/api/me/player/connect', { method: 'POST', headers: auth.headers })).status, 503);
  const socket = await request(f, '/api/me/player/socket', { headers: auth.headers });
  assert.equal(socket.status, 503); assert.match(socket.json.error.code, /NO_ONLINE_PLAYBACK_ACCEPTANCE/);
  assert.equal((await request(f, '/api/me/history', { method: 'POST', headers: auth.headers, body: {} })).status, 405);
  assert.equal((await request(f, '/api/provider/start', { method: 'POST', headers: auth.headers, body: {} })).status, 404);
  const s = f.summary(); assert.equal(s.onlinePlaybackAcceptance, false);
  assert.equal(s.requests['GET other'], 150); assert.ok(Object.keys(s.requests).length < 10);
  assert.doesNotMatch(JSON.stringify(s), new RegExp(`${PASSWORD}|secret-token|csrfToken|fixture_session|unrecognized-`));
  s.requests['GET other'] = -1; assert.equal(f.summary().requests['GET other'], 150);
});

test('failed durable rename never ACKs or accepts a receipt; exact retry can subsequently commit', async t => {
  const f = setup(t), auth = await login(f), p = receipt(), statePath = join(f.stateRoot, STATE);
  // Fault injection touches only this test's new synthetic state, never app data.
  rmSync(statePath); mkdirSync(statePath);
  try {
    const failed = await post(f, auth, p);
    assert.equal(failed.status, 500); assert.doesNotMatch(failed.text, /spofify-synthetic-|EISDIR/);
    assert.equal(f.summary().receiptCount, 0); assert.equal(f.summary().acceptedReceipts, 0);
    assert.deepEqual(readdirSync(f.stateRoot), [STATE]);
  } finally { rmSync(statePath, { recursive: true }); writeFileSync(statePath, '{}', { mode: 0o600, flag: 'wx' }); }
  const retry = await post(f, auth, p);
  assert.equal(retry.status, 200); assert.equal(retry.json.duplicate, false);
  assert.equal(JSON.parse(readFileSync(statePath)).receipts.length, 1);
});

test('diagnostic receipt window is bounded, but never prunes durable replay evidence', async t => {
  const f = setup(t), auth = await login(f), original = receipt({ eventId: 'bounded-0' });
  assert.equal((await post(f, auth, original)).status, 200);
  for (let i = 1; i <= 100; i++) assert.equal((await post(f, auth, { ...original, eventId: `bounded-${i}` })).status, 200);
  const s = f.summary(); assert.equal(s.receipts.length, 100); assert.equal(s.receiptCount, 101); assert.equal(s.receiptsTruncated, true);
  assert.equal(s.receipts[0].eventId, 'bounded-1'); assert.equal(s.receipts[99].eventId, 'bounded-100');
  assert.equal(JSON.parse(readFileSync(join(f.stateRoot, STATE))).receipts.length, 101);
  assert.equal((await request(f, '/api/me/history', { method: 'DELETE', headers: auth.headers, body: {} })).status, 204);
  assert.equal((await post(f, auth, original)).json.duplicate, true); assert.equal(f.summary().historyCount, 0);
});

test('concurrent exact receipt retries serialize to one durable acceptance', async t => {
  const f = setup(t), auth = await login(f), p = receipt();
  const responses = await Promise.all(Array.from({ length: 12 }, () => post(f, auth, p)));
  assert.ok(responses.every(r => r.status === 200));
  assert.equal(responses.filter(r => !r.json.duplicate).length, 1);
  assert.equal(new Set(responses.map(r => r.json.receivedAtMs)).size, 1);
  assert.equal(f.summary().acceptedReceipts, 1); assert.equal(f.summary().duplicateReceipts, 11);
  assert.equal(JSON.parse(readFileSync(join(f.stateRoot, STATE))).receipts.length, 1);
});

test('close joins pending handlers, commits stopped state, is idempotent and rejects later requests', async t => {
  const f = setup(t), auth = await login(f);
  const pending = post(f, auth, receipt());
  const closed = f.close();
  const [r, summary] = await Promise.all([pending, closed]);
  assert.equal(r.status, 200); assert.equal(summary.lifecycle, 'stopped');
  assert.equal(await f.close(), summary);
  const state = JSON.parse(readFileSync(join(f.stateRoot, STATE)));
  assert.equal(state.lifecycle, 'stopped'); assert.ok(state.stoppedAt); assert.equal(state.receipts.length, 1);
  assert.equal((await request(f, '/api/auth/session', { headers: auth.headers })).status, 503);
  assert.ok(existsSync(join(f.stateRoot, STATE)));
});
