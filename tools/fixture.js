// Synthetic transport only. No providers, online playback authority, or import-time listener.
// Contracts: spofify-go 38d524e docs/native-offline-collections.md,
// docs/offline-play-receipts.md, docs/account-palette.md and internal/web DTOs.
import http from 'node:http';
import { createHash, createHmac, randomBytes, timingSafeEqual } from 'node:crypto';
import { closeSync, constants, existsSync, fstatSync, fsyncSync, lstatSync, openSync,
  readFileSync, realpathSync, renameSync, unlinkSync, writeFileSync } from 'node:fs';
import { isAbsolute, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { TextDecoder } from 'node:util';

const ACCOUNT = 'fixture-account';
const USERNAME = 'fixture-listener';
const PASSWORD = 'synthetic-fixture-only';
const STATE_FILE = 'fixture-receipts.json';
const RECEIPT_KEYS = ['eventId', 'trackId', 'origin', 'occurredAtMs', 'elapsedMs', 'progressedMs'];
// Explicit public files only; never recursively expose --source, tests, state, or Git.
// release.json is served, not interpreted: it cannot grant additional file paths.
const PUBLIC_FILES = new Map([
  ['index.html', 'text/html; charset=utf-8'], ['styles.css', 'text/css; charset=utf-8'],
  ...['app.js', 'transport.js', 'online-player.js', 'local-player.js', 'audio-owner.js', 'gestures.js',
    'offline-store.js', 'progress-journal.js', 'presentation.js', 'account-access.js', 'sw.js', 'assets/license.js',
    'vendor/audio-adapter.js', 'vendor/playback-sync.js'].map(p => [p, 'text/javascript; charset=utf-8']),
  ['manifest.webmanifest', 'application/manifest+json'], ['release.json', 'application/json; charset=utf-8'],
  ['assets/icon.svg', 'image/svg+xml'], ['assets/icon-192.png', 'image/png'], ['assets/icon-512.png', 'image/png'],
  ['assets/JetBrainsMono.ttf', 'font/ttf'], ['assets/OFL.txt', 'text/plain; charset=utf-8'],
]);
const CSP = "default-src 'self'; script-src 'self'; style-src 'self'; font-src 'self'; img-src 'self'; media-src 'self' blob:; connect-src 'self'; worker-src 'self'; manifest-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";
const PRESETS = [
  ['jade', 'Jade', ['#66e3b4', '#08271d', '#91bfff', '#dfbd78'], ['#08704e', '#ffffff', '#2759a4', '#77551b']],
  ['ember', 'Ember', ['#ffad89', '#31170d', '#c4b0ff', '#a4d7bd'], ['#aa431b', '#ffffff', '#654199', '#23644a']],
  ['violet', 'Violet', ['#c8b5ff', '#22183c', '#6de2d5', '#e8b4c5'], ['#6442a5', '#ffffff', '#0c6d67', '#8b465e']],
].map(([id, name, dark, light]) => {
  const tokens = values => Object.fromEntries(['primary', 'onPrimary', 'secondary', 'tertiary'].map((key, i) => [key, values[i]]));
  return { id, name, dark: tokens(dark), light: tokens(light) };
});
const playlist = { id: 'fixture-playlist', kind: 'manual', name: 'Offline sound check',
  description: 'Synthetic eight-second tones; not real music.', revision: 1 };
const playlistSummary = { ...playlist, countState: 'exact', trackCount: 3, availableCount: 3,
  capabilities: { editMetadata: false, editEntries: false, editDefinition: false,
    play: true, copyToManual: false, delete: false, addTracks: false },
  createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' };

function fail(status, code = 'INVALID_REQUEST') { throw Object.assign(new Error(code), { status, code }); }
function parseOrigin(origin) {
  let u;
  try { u = new URL(origin); } catch { throw new Error('origin must be an exact concrete http(s) origin with a nonzero port'); }
  if (!['http:', 'https:'].includes(u.protocol) || u.origin !== origin || !u.port ||
      !/^[1-9]\d*$/.test(u.port) || Number(u.port) > 65535 ||
      ['0.0.0.0', '[::]', '*'].includes(u.hostname) || !/^[a-z0-9.:[\]-]+$/i.test(u.hostname)) {
    throw new Error('origin must be an exact concrete http(s) origin with a nonzero port');
  }
  return u;
}
function ownedDirectory(path) {
  if (typeof path !== 'string' || !isAbsolute(path) || realpathSync(path) !== resolve(path)) throw new Error('stateRoot must be an existing absolute nonsymlink directory');
  const st = lstatSync(path);
  if (!st.isDirectory() || st.uid !== process.getuid() || (st.mode & 0o077)) throw new Error('stateRoot must be owned by this user and private (0700)');
}
function wav(frequency) {
  const rate = 16000, samples = rate * 8, data = Buffer.alloc(44 + samples * 2);
  data.write('RIFF'); data.writeUInt32LE(data.length - 8, 4); data.write('WAVEfmt ', 8);
  data.writeUInt32LE(16, 16); data.writeUInt16LE(1, 20); data.writeUInt16LE(1, 22);
  data.writeUInt32LE(rate, 24); data.writeUInt32LE(rate * 2, 28); data.writeUInt16LE(2, 32);
  data.writeUInt16LE(16, 34); data.write('data', 36); data.writeUInt32LE(samples * 2, 40);
  for (let i = 0; i < samples; i++) data.writeInt16LE(Math.round(32767 * 0.08 * Math.sin(2 * Math.PI * frequency * i / rate)), 44 + i * 2);
  return data;
}
function query(url, allowed) {
  if (url.search.length > 8192) fail(400);
  const result = Object.create(null);
  for (const [k, v] of url.searchParams) {
    if (!allowed.includes(k) || Object.hasOwn(result, k)) fail(400);
    result[k] = v;
  }
  return result;
}
function integer(value, fallback, min, max) {
  if (value === undefined) return fallback;
  if (!/^\d+$/.test(value) || !Number.isSafeInteger(Number(value)) || Number(value) < min || Number(value) > max) fail(400);
  return Number(value);
}
function page(url, recent = false) {
  const q = query(url, recent ? ['limit'] : ['limit', 'offset']);
  return { limit: integer(q.limit, recent ? 12 : 40, 1, 100), offset: integer(q.offset, 0, 0, 2147483647) };
}
function noQuery(url, target) { if (url.search || target.includes('?')) fail(400); }
function method(req, res, ...allowed) {
  if (!allowed.includes(req.method)) { res.setHeader('Allow', allowed.join(', ')); fail(405, 'METHOD_NOT_ALLOWED'); }
}
// Exact, flat JSON objects: decode key spelling before checking duplicates. Preserve
// numeric lexical forms so 1000.0/1e3 cannot masquerade as integer receipt fields.
async function objectBody(req, allowed, bound = 65536, emptyOK = false) {
  const chunks = []; let size = 0;
  for await (const chunk of req) {
    const b = Buffer.from(chunk); size += b.length;
    if (size > bound) fail(413);
    chunks.push(b);
  }
  let raw;
  try { raw = new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks)); } catch { fail(400); }
  if (emptyOK && !raw.trim()) return {};
  if (!/^application\/json(?:\s*;\s*charset\s*=\s*(?:utf-8|"utf-8"))?\s*$/i.test(req.headers['content-type'] || '')) fail(415);
  try { JSON.parse(raw); } catch { fail(400); }
  const string = '"(?:[^"\\\\\u0000-\u001f]|\\\\(?:["\\\\/bfnrt]|u[0-9a-fA-F]{4}))*"';
  const member = new RegExp(`\\s*(${string})\\s*:\\s*(${string}|-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?|true|false|null)\\s*`, 'y');
  const result = Object.create(null);
  let pos = raw.search(/\S/);
  if (raw[pos++] !== '{') fail(400);
  while (true) {
    while (/\s/.test(raw[pos] || '') && pos < raw.length) pos++;
    if (raw[pos] === '}') { pos++; break; }
    member.lastIndex = pos;
    const match = member.exec(raw);
    if (!match) fail(400);
    const key = JSON.parse(match[1]), value = JSON.parse(match[2]);
    if (!allowed.includes(key) || Object.hasOwn(result, key)) fail(400);
    if (typeof value === 'number' && (!/^-?\d+$/.test(match[2]) || !Number.isSafeInteger(value))) fail(400);
    result[key] = value; pos = member.lastIndex;
    if (raw[pos] === '}') { pos++; break; }
    if (raw[pos++] !== ',') fail(400);
  }
  if (raw.slice(pos).trim()) fail(400);
  return result;
}
function validID(value, max) {
  return typeof value === 'string' && value.trim() === value && Buffer.byteLength(value) >= 1 &&
    Buffer.byteLength(value) <= max && !/[\p{Cc}\p{Cs}]/u.test(value);
}
function validateReceipt(p) {
  if (Object.keys(p).length !== 6 || !validID(p.eventId, 128) || !validID(p.trackId, 512) || p.origin !== 'offline' ||
      !['occurredAtMs', 'elapsedMs', 'progressedMs'].every(k => Number.isSafeInteger(p[k])) ||
      p.elapsedMs < 1000 || p.progressedMs < 1000 || p.progressedMs > p.elapsedMs ||
      p.occurredAtMs < p.elapsedMs || !Number.isFinite(new Date(p.occurredAtMs).getTime())) fail(400);
  return Object.fromEntries(RECEIPT_KEYS.map(k => [k, p[k]]));
}
function equal(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  const left = Buffer.from(a), right = Buffer.from(b);
  return left.length === right.length && timingSafeEqual(left, right);
}

/** Creates a socket-free synthetic handler. Never starts a server. Owns only a
 * newly, exclusively created state receipt file and short-lived atomic temp files.
 * Refuses reuse: retained state is evidence, not an implicit restart/migration API.
 */
export function createFixture({ source, origin, stateRoot }) {
  const base = parseOrigin(origin);
  ownedDirectory(stateRoot);
  if (typeof source !== 'string' || !isAbsolute(source) || !lstatSync(source).isDirectory()) throw new Error('source must be an existing absolute directory');
  const sourceRoot = realpathSync(source), statePath = join(stateRoot, STATE_FILE);
  const fd = openSync(statePath, 'wx', 0o600);
  let state = { version: 1, synthetic: true, origin, lifecycle: 'running', receipts: [], history: [] };
  try { writeFileSync(fd, JSON.stringify(state)); fsyncSync(fd); } finally { closeSync(fd); }
  const directoryFD = openSync(stateRoot, constants.O_RDONLY | constants.O_DIRECTORY);
  fsyncSync(directoryFD);
  const commit = next => {
    const temp = join(stateRoot, `.fixture-${randomBytes(12).toString('hex')}.tmp`);
    let handle;
    try {
      handle = openSync(temp, 'wx', 0o600);
      writeFileSync(handle, JSON.stringify(next)); fsyncSync(handle); closeSync(handle); handle = undefined;
      renameSync(temp, statePath); fsyncSync(directoryFD); state = next;
    } finally {
      if (handle !== undefined) closeSync(handle);
      if (existsSync(temp)) unlinkSync(temp);
    }
  };
  const tracks = new Map([['fixture-tone-a', 440], ['fixture-tone-b', 660]].map(([id, hz]) => {
    const bytes = wav(hz), sha256 = createHash('sha256').update(bytes).digest('hex');
    return [id, { bytes, sha256, track: { kind: 'track', id, name: `Synthetic ${hz} Hz tone`,
      artists: [{ id: 'fixture-generator', name: 'Synthetic signal generator' }], durationMs: 8000,
      hasAudio: true, coverage: { tracks: 1, inLibrary: 1, albums: 0 } } }];
  }));
  const entries = ['fixture-tone-a', 'fixture-tone-b', 'fixture-tone-a'].map((id, position) => ({
    id: `fixture-entry-${position + 1}`, position, track: tracks.get(id).track,
  }));
  const fence = `fixture-v1-${randomBytes(16).toString('hex')}`, signingKey = randomBytes(32);
  const sign = s => createHmac('sha256', signingKey).update(s).digest('base64url');
  const cursor = data => { const s = Buffer.from(JSON.stringify(data)).toString('base64url'); return `of1_${s}.${sign(s)}`; };
  const sessions = new Map(), active = new Set();
  const ledger = new Map();
  const counters = { requests: {}, acceptedReceipts: 0, duplicateReceipts: 0, conflictingReceipts: 0, futureReceipts: 0 };
  let closing = false, closed = false, closePromise;
  // Fixed method/path categories, never raw paths, query strings, headers or credentials.
  const count = (req, path) => {
    const category = path.startsWith('/app/') ? 'shell' : path === '/__fixture/summary' ? 'summary' :
      path.startsWith('/api/auth/') ? 'auth' : path.includes('/manifest') ? 'manifest' :
      path.endsWith('/media') ? 'media' : path.endsWith('/audio') ? 'audio' :
      path.endsWith('/play-receipts') ? 'receipts' : path.startsWith('/api/me/player/') ? 'player-unavailable' :
      ['/api/me/history', '/api/me/recent', '/api/me/history/recent'].includes(path) ? 'history' :
      path.startsWith('/api/me/playlists') ? 'playlists' : path === '/api/me/palette' ? 'palette' : 'other';
    const verb = ['GET', 'HEAD', 'POST', 'PUT', 'DELETE', 'OPTIONS'].includes(req.method) ? req.method : 'OTHER';
    const key = `${verb} ${category}`; counters.requests[key] = (counters.requests[key] || 0) + 1;
  };
  const summary = () => structuredClone({ synthetic: true, onlinePlaybackAcceptance: false,
    lifecycle: closed ? 'stopped' : closing ? 'stopping' : 'running', ...counters,
    receiptCount: state.receipts.length, historyCount: state.history.length,
    // Deliberate bounded diagnostic view, NOT a bound/GC on the durable replay ledger.
    receipts: state.receipts.slice(-100).map(r => r.payload), receiptsTruncated: state.receipts.length > 100 });
  function send(req, res, status, value) {
    res.statusCode = status;
    const body = value === undefined ? Buffer.alloc(0) : Buffer.isBuffer(value) ? value : Buffer.from(JSON.stringify(value));
    if (value !== undefined && !Buffer.isBuffer(value)) res.setHeader('Content-Type', 'application/json; charset=utf-8');
    // A 304 has no body; if Content-Length were sent it would have to describe
    // the selected 200 representation, not an invented zero-length recording.
    if (status !== 304 && status !== 204) res.setHeader('Content-Length', body.length);
    res.end(req.method === 'HEAD' || status === 304 || status === 204 ? undefined : body);
  }
  async function dispatch(req, res) {
    res.setHeader('Cache-Control', 'no-store'); res.setHeader('Content-Security-Policy', CSP);
    res.setHeader('X-Content-Type-Options', 'nosniff'); res.setHeader('Referrer-Policy', 'no-referrer');
    res.setHeader('Cross-Origin-Opener-Policy', 'same-origin'); res.setHeader('Cross-Origin-Resource-Policy', 'same-origin');
    try {
      if (closing) fail(503, 'FIXTURE_STOPPED');
      const target = req.url;
      if (typeof target !== 'string' || !target.startsWith('/') || target.startsWith('//') || /[\\\u0000-\u0020\u007f#]/.test(target)) fail(400);
      const url = new URL(target, origin), path = url.pathname;
      count(req, path);
      const singleton = new Set(['host', 'origin', 'sec-fetch-site', 'x-csrf-token', 'x-spofify-login', 'x-spofify-session-mode', 'content-type']);
      const seenHeaders = new Set();
      for (let i = 0; i < (req.rawHeaders || []).length; i += 2) {
        const name = req.rawHeaders[i].toLowerCase();
        if (singleton.has(name) && seenHeaders.has(name)) fail(name === 'host' ? 421 : 403, 'DUPLICATE_SECURITY_HEADER');
        seenHeaders.add(name);
      }
      if (req.headers.host !== base.host || url.origin !== origin) fail(421, 'UNTRUSTED_HOST');
      if (req.headers.origin !== undefined && req.headers.origin !== origin) fail(403, 'ORIGIN_REJECTED');
      if (req.headers['sec-fetch-site'] !== undefined && !['same-origin', 'same-site', 'none'].includes(req.headers['sec-fetch-site'])) fail(403, 'CROSS_SITE_REJECTED');
      // URL normalization must not turn traversal/encoded action routes into grants.
      if (target.split('?')[0] !== path || path.includes('//') || path.includes('%')) fail(400);
      if (path.startsWith('/app/')) {
        method(req, res, 'GET', 'HEAD'); noQuery(url, target);
        const name = path === '/app/' ? 'index.html' : path.slice(5);
        if (!PUBLIC_FILES.has(name)) fail(404, 'NOT_FOUND');
        const file = join(sourceRoot, name);
        let bytes;
        try {
          if (realpathSync(file) !== file) fail(404, 'NOT_FOUND');
          const h = openSync(file, constants.O_RDONLY | constants.O_NOFOLLOW);
          try { if (!fstatSync(h).isFile()) fail(404, 'NOT_FOUND'); bytes = readFileSync(h); } finally { closeSync(h); }
        } catch { fail(404, 'NOT_FOUND'); }
        res.setHeader('Cache-Control', 'public, max-age=0, must-revalidate');
        res.setHeader('Content-Type', PUBLIC_FILES.get(name));
        res.setHeader('Service-Worker-Allowed', '/app/');
        return send(req, res, 200, bytes);
      }
      const cookieName = base.protocol === 'https:' ? '__Host-fixture_session' : 'fixture_session';
      const cookies = (req.headers.cookie || '').split(';').map(s => s.trim()).filter(s => s.startsWith(`${cookieName}=`));
      const token = cookies.length === 1 ? cookies[0].slice(cookieName.length + 1) : '';
      const session = sessions.get(token);
      const cookie = value => `${cookieName}=${value}; Path=/; HttpOnly; SameSite=Strict${base.protocol === 'https:' ? '; Secure' : ''}${value ? '' : '; Max-Age=0'}`;
      if (path === '/api/auth/login') {
        method(req, res, 'POST'); noQuery(url, target);
        if (req.headers.origin !== origin || req.headers['x-spofify-login'] !== '1') fail(403);
        if (req.headers['x-spofify-session-mode'] !== undefined) fail(400, 'UNSUPPORTED_SESSION_MODE');
        const body = await objectBody(req, ['username', 'password'], 4096);
        if (body.username !== USERNAME || body.password !== PASSWORD || Object.keys(body).length !== 2) fail(401, 'INVALID_CREDENTIALS');
        const nextToken = randomBytes(32).toString('hex'), csrfToken = randomBytes(32).toString('hex');
        // One synthetic session at a time; memory does not grow with login attempts.
        sessions.clear();
        const next = { accountId: ACCOUNT, username: USERNAME, role: 'user', csrfToken };
        sessions.set(nextToken, next); res.setHeader('Set-Cookie', cookie(nextToken));
        return send(req, res, 200, next);
      }
      if (!session) fail(401, 'AUTHENTICATION_REQUIRED');
      if (!['GET', 'HEAD', 'OPTIONS'].includes(req.method) &&
          (req.headers.origin !== origin || !equal(req.headers['x-csrf-token'], session.csrfToken))) fail(403, 'REQUEST_VERIFICATION_FAILED');
      if (path === '/api/auth/session') { method(req, res, 'GET'); noQuery(url, target); return send(req, res, 200, session); }
      if (path === '/api/auth/logout') {
        method(req, res, 'POST'); noQuery(url, target); sessions.delete(token);
        res.setHeader('Set-Cookie', cookie('')); return send(req, res, 204);
      }
      if (path === '/__fixture/summary') { method(req, res, 'GET'); noQuery(url, target); return send(req, res, 200, summary()); }
      if (path === '/api/me/player/connect' || path === '/api/me/player/socket') fail(503, 'FIXTURE_HAS_NO_ONLINE_PLAYBACK_ACCEPTANCE');
      if (path === '/api/me/palette') {
        method(req, res, 'GET'); noQuery(url, target);
        return send(req, res, 200, { accountId: ACCOUNT, paletteId: 'jade', revision: 0, catalogVersion: 1, defaultPaletteId: 'jade', presets: PRESETS });
      }
      if (path === '/api/me/playlists' || path === '/api/me/playlists/fixture-playlist/entries') {
        method(req, res, 'GET'); const { limit, offset } = page(url);
        const items = path.endsWith('/entries') ? entries : [playlistSummary];
        return send(req, res, 200, { items: items.slice(offset, offset + limit), total: items.length, limit, offset });
      }
      if (path === '/api/me/playlists/fixture-playlist') {
        method(req, res, 'GET'); noQuery(url, target); return send(req, res, 200, playlistSummary);
      }
      if (path === '/api/me/offline/v1/playlists/fixture-playlist/manifest') {
        method(req, res, 'GET'); const q = query(url, ['limit', 'cursor']);
        let limit = integer(q.limit, 40, 1, 100), offset = 0, expiresAt = Date.now() + 900000;
        if (q.cursor !== undefined) {
          if (q.cursor.length > 4096 || !q.cursor.startsWith('of1_')) fail(400, 'INVALID_CURSOR');
          const [data, signature, extra] = q.cursor.slice(4).split('.');
          if (extra !== undefined || !equal(signature, sign(data))) fail(400, 'INVALID_CURSOR');
          let c; try { c = JSON.parse(Buffer.from(data, 'base64url').toString()); } catch { fail(400, 'INVALID_CURSOR'); }
          if (c.fence !== fence || c.account !== ACCOUNT || c.target !== playlist.id || c.expiresAt < Date.now() ||
              !Number.isInteger(c.offset) || c.offset < 1 || c.offset >= 3 || (q.limit !== undefined && limit !== c.limit)) fail(400, 'INVALID_CURSOR');
          ({ limit, offset, expiresAt } = c);
        }
        const nextOffset = offset + limit;
        const nextCursor = nextOffset < 3 ? cursor({ account: ACCOUNT, target: playlist.id, fence, limit, offset: nextOffset, expiresAt }) : null;
        return send(req, res, 200, { version: 1, playlist, fence, items: entries.slice(offset, nextOffset).map(({ id, ...e }) => ({ occurrenceId: id, ...e })),
          total: 3, recordedAudioTotal: 3, limit, nextCursor });
      }
      const media = /^\/api\/me\/offline\/v1\/tracks\/([^/]+)\/(media|audio)$/.exec(path);
      if (media) {
        method(req, res, ...(media[2] === 'media' ? ['GET'] : ['GET', 'HEAD']));
        const item = tracks.get(media[1]); if (!item) fail(404, 'MEDIA_UNAVAILABLE');
        const version = `sha256:${item.sha256}`;
        if (media[2] === 'media') {
          noQuery(url, target);
          return send(req, res, 200, { version: 1, trackId: media[1], mediaVersion: version, sha256: item.sha256,
            byteLength: item.bytes.length, format: 'wav', contentType: 'audio/wav',
            downloadUrl: `/api/me/offline/v1/tracks/${media[1]}/audio?version=${encodeURIComponent(version)}` });
        }
        const q = query(url, ['version']);
        if (!/^sha256:[a-f0-9]{64}$/.test(q.version || '')) fail(400);
        if (q.version !== version) fail(409, 'MEDIA_CHANGED');
        const etag = `"${version}"`, length = item.bytes.length;
        res.setHeader('ETag', etag); res.setHeader('Accept-Ranges', 'bytes'); res.setHeader('Content-Type', 'audio/wav');
        if ((req.headers['if-none-match'] || '').split(/\s*,\s*/).some(s => s === etag || s === `W/${etag}` || s === '*')) return send(req, res, 304);
        if (req.headers.range && (!req.headers['if-range'] || req.headers['if-range'] === etag)) {
          const r = /^bytes=(\d*)-(\d*)$/.exec(req.headers.range);
          let start, end;
          if (r && (r[1] || r[2])) {
            start = r[1] ? Number(r[1]) : Math.max(0, length - Number(r[2]));
            end = r[1] && r[2] ? Math.min(Number(r[2]), length - 1) : length - 1;
          }
          if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start >= length || start > end || (!r[1] && Number(r[2]) === 0)) {
            res.setHeader('Content-Range', `bytes */${length}`); fail(416, 'INVALID_RANGE');
          }
          res.setHeader('Content-Range', `bytes ${start}-${end}/${length}`);
          return send(req, res, 206, item.bytes.subarray(start, end + 1));
        }
        return send(req, res, 200, item.bytes);
      }
      if (path === '/api/me/offline/v1/play-receipts') {
        method(req, res, 'POST'); noQuery(url, target);
        const payload = validateReceipt(await objectBody(req, RECEIPT_KEYS));
        const old = ledger.get(payload.eventId);
        if (old) {
          if (!RECEIPT_KEYS.every(k => old.payload[k] === payload[k])) { counters.conflictingReceipts++; fail(409, 'RECEIPT_CONFLICT'); }
          counters.duplicateReceipts++;
          return send(req, res, 200, { eventId: payload.eventId, receivedAtMs: old.receivedAtMs, duplicate: true });
        }
        const receivedAtMs = Date.now();
        if (payload.occurredAtMs > receivedAtMs + 300000) { counters.futureReceipts++; fail(422, 'FUTURE_RECEIPT'); }
        const receipt = { payload, receivedAtMs };
        const history = [...state.history, payload.eventId].sort((a, b) => {
          const pa = a === payload.eventId ? payload : ledger.get(a).payload;
          const pb = b === payload.eventId ? payload : ledger.get(b).payload;
          return pb.occurredAtMs - pa.occurredAtMs || (a < b ? -1 : a > b ? 1 : 0);
        }).slice(0, 10000);
        commit({ ...state, receipts: [...state.receipts, receipt], history });
        ledger.set(payload.eventId, receipt); counters.acceptedReceipts++;
        return send(req, res, 200, { eventId: payload.eventId, receivedAtMs, duplicate: false });
      }
      if (['/api/me/history', '/api/me/recent', '/api/me/history/recent'].includes(path)) {
        const recent = path !== '/api/me/history'; method(req, res, ...(recent ? ['GET'] : ['GET', 'DELETE']));
        if (req.method === 'DELETE') {
          noQuery(url, target); await objectBody(req, [], 65536, true);
          commit({ ...state, history: [] }); return send(req, res, 204);
        }
        const { limit, offset } = page(url, recent), seen = new Set();
        const rows = state.history.map(id => ledger.get(id)).filter(r => {
          if (!recent) return true;
          if (seen.has(r.payload.trackId)) return false; seen.add(r.payload.trackId); return true;
        });
        const items = rows.slice(offset, offset + limit).map(({ payload: p, receivedAtMs }) => {
          const track = tracks.get(p.trackId)?.track;
          return { id: p.eventId, playedAt: new Date(p.occurredAtMs).toISOString(), origin: 'offline', receivedAt: new Date(receivedAtMs).toISOString(),
            track: track || { id: p.trackId, kind: 'track', name: '', hasAudio: false, coverage: { tracks: 1, inLibrary: 0, albums: 0 } },
            ...(!track ? { metadataMissing: true } : {}) };
        });
        return send(req, res, 200, { items, total: rows.length, limit, offset });
      }
      fail(404, 'NOT_FOUND');
    } catch (err) {
      if (!res.headersSent) send(req, res, err.status || 500, { error: { code: err.status ? err.code : 'FIXTURE_STORAGE_OR_INTERNAL_FAILURE', message: err.status ? err.code : 'Synthetic fixture operation failed' } });
      else res.destroy?.();
    }
  }
  const handler = (req, res) => {
    const done = dispatch(req, res); active.add(done); done.finally(() => active.delete(done)); return done;
  };
  const close = () => {
    if (!closePromise) {
      closing = true;
      closePromise = (async () => {
        try { await Promise.allSettled([...active]); commit({ ...state, lifecycle: 'stopped', stoppedAt: new Date().toISOString() }); closed = true; sessions.clear(); }
        finally { closeSync(directoryFD); }
        return summary();
      })();
    }
    return closePromise;
  };
  return { handler, close, summary };
}

// CLI is deliberately foreground, explicit-main only. Authorization to run/bind
// must be obtained by its caller; importing or testing this module never binds.
const CLI_ORIGIN = 'http://127.0.0.1:5173';
const USAGE = `usage: node tools/fixture.js --root <existing owned absolute 0700 dir> --listen ${CLI_ORIGIN} --source <absolute public release dir>`;
async function main(args) {
  if (args.length === 1 && args[0] === '--help') { process.stdout.write(`${USAGE}\n`); return; }
  const flags = new Map();
  for (let i = 0; i < args.length; i += 2) {
    const key = args[i], value = args[i + 1];
    if (!['--root', '--listen', '--source'].includes(key) || !value || value.startsWith('--') || flags.has(key)) throw new Error(USAGE);
    flags.set(key, value);
  }
  if (flags.size !== 3) throw new Error('all of --root, --listen and --source are required');
  const origin = flags.get('--listen'), base = parseOrigin(origin);
  if (origin !== CLI_ORIGIN) throw new Error('Only the explicitly authorized http://127.0.0.1:5173 origin is permitted; never choose another port');
  const fixture = createFixture({ source: flags.get('--source'), origin, stateRoot: flags.get('--root') });
  const server = http.createServer({ requestTimeout: 10000, headersTimeout: 10000, maxHeaderSize: 16384 }, fixture.handler);
  server.maxConnections = 16;
  let stopPromise, startupDone = false, earlyStop;
  const stop = reason => {
    // An intentionally closed stdin may arrive while listen is pending. Remember
    // it and join startup before closing, never leave a late-bound orphan server.
    if (!startupDone) { earlyStop ??= reason; return Promise.resolve(); }
    if (!stopPromise) stopPromise = (async () => {
      let clean = true;
      const deadline = setTimeout(() => { clean = false; server.closeAllConnections(); }, 5000);
      try {
        await new Promise(resolveClose => server.close(() => resolveClose()));
        await fixture.close();
      } catch { clean = false; process.exitCode = 1; }
      finally { clearTimeout(deadline); process.stdin.pause(); }
      process.stderr.write(`${JSON.stringify({ event: 'stopped', stopped: true, clean, reason, synthetic: true,
        stateRetained: true, stateRoot: flags.get('--root'), onlinePlaybackAcceptance: false, summary: fixture.summary() })}\n`);
    })();
    return stopPromise;
  };
  process.once('SIGTERM', () => void stop('SIGTERM'));
  process.once('SIGINT', () => void stop('SIGINT'));
  process.stdin.once('end', () => void stop('stdin-EOF'));
  process.stdin.once('error', () => void stop('stdin-error'));
  // A retained supervisor's open pipe needs no keepalive bytes. Only actual EOF
  // (not lack of input or an idle timer) ends the foreground fixture lifetime.
  process.stdin.resume();
  try {
    await new Promise((resolveListen, reject) => {
      server.once('error', reject);
      server.listen({ host: base.hostname.replace(/^\[|\]$/g, ''), port: Number(base.port), exclusive: true }, resolveListen);
    });
    startupDone = true;
    if (earlyStop) { await stop(earlyStop); return; }
    process.stdout.write(`${JSON.stringify({ event: 'ready', synthetic: true, origin, appUrl: `${origin}/app/`,
      credentials: { username: USERNAME, password: PASSWORD }, accountId: ACCOUNT,
      sourceRoot: realpathSync(flags.get('--source')), stateRoot: flags.get('--root'), stateFile: join(flags.get('--root'), STATE_FILE),
      manifestUrl: `${origin}/api/me/offline/v1/playlists/fixture-playlist/manifest`,
      receiptsUrl: `${origin}/api/me/offline/v1/play-receipts`, summaryUrl: `${origin}/__fixture/summary`,
      onlinePlaybackAcceptance: false, warning: 'Private synthetic fixture readiness; no online playback acceptance.' })}\n`);
  } catch (err) { startupDone = true; process.exitCode = 1; await stop(err.code === 'EADDRINUSE' ? 'origin-occupied' : 'listen-failed'); }
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2)).catch(() => { process.stderr.write(`Synthetic fixture configuration failed; no readiness.\n${USAGE}\n`); process.exitCode = 1; });
}
