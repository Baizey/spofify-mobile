# Spofify web listener

Vanilla HTML/CSS/JavaScript PWA. No package installation or bundler is required.
`node --test test/*.test.js tests/*.test.mjs` runs the file-only unit tests. Browser tests require
an explicitly authorized provider-free test origin; unit tests are not rendering,
service-worker, storage durability, physical audibility, or Android acceptance.

## Run the synthetic preview from the normal repository

After the reviewed checkpoint is integrated into `main`, run these commands in
**your ordinary terminal** (not the agent sandbox). They need installed Node 22+
only: no `npm install`, Go build, real account, provider or real music.

```sh
cd /var/home/lsm/repos/spofify-mobile
umask 077
PREVIEW=$(mktemp -d /var/home/lsm/.local/state/spofify-preview.XXXXXXXX)
mkdir "$PREVIEW/release" "$PREVIEW/state"
node tools/release.js "$PWD" "$PREVIEW/release"
node tools/fixture.js --source "$PREVIEW/release" --root "$PREVIEW/state" --listen http://127.0.0.1:5173
```

Leave the last command running in the foreground. Open
**http://127.0.0.1:5173/app/** in a fresh browser profile/context. Sign in as
`fixture-listener` with password `synthetic-fixture-only` (test data only).
If port 5173 is occupied, stop and identify its owner; do not reuse unknown traffic
or choose a different port. Ctrl-C stops the fixture; wait for its stopped receipt.
Use a fresh `PREVIEW` directory for another run; retained state is not reused.

The preview provides one playlist, three occurrences and two generated eight-second
WAV tones. In Playlists, open **Offline sound check** and Download. Wait for
**Downloaded** and initial service-worker installation, then use Settings →
Offline mode. Browse the same collection, play a song, pause and reopen `/app/`
while disconnected. Go online explicitly to reconcile pending plays. These are
manual test steps, **not a claim they have passed**. Browser storage may be evicted.

This checkpoint is a **synthetic preview, not real-Go online playback or Android
runtime acceptance**. The fixture deliberately has no online playback executor,
general online catalogue search, palette writes or production lyric/history
summary APIs. Online Play can report unavailable; downloaded local Play is the
intended vertical-slice path. No rendered app/offline service-worker/media result
has yet been established: the sandbox Firefox launch failed natively; host MCP
routing could not serve a worker script. Full final-design presentation,
large/lossless media calibration and durable large-collection staging, exact
history/lyrics integration, browser acceptance and independent review remain open.
The source-only lyric/waveform DTO helpers are not wired as completed features.

## Same-origin delivery

The public shell is mounted at **`/app/`**. API and WebSocket requests use the
existing Go origin, session cookie, CSRF and server playback authority. There is
no configurable cross-origin API proxy, bearer URL, permissive CORS mode, or
client queue upload. The admin root is separate and is not imported here.

Build a release with installed Node, into a fresh empty directory:

```
node tools/release.js SOURCE EXISTING_EMPTY_OUTPUT
```

The generated `release.json` describes the exact public asset allowlist, hashes,
byte sizes and content-derived release version. Only this allowlist is delivered;
never expose the repository, fixture code, tests, Git directory or private state.
A coordinated Go integration must mount these public GET/HEAD assets *before*
the authentication redirect, with exact configured Host/Origin/Fetch Metadata
checks still applied. APIs and authenticated media remain on the ordinary router.
Serve `/app/sw.js` as JavaScript, `Cache-Control: no-cache`, and scope `/app/`
(the default directory scope is sufficient). Serve HTML and the manifest with
revalidation. No blanket SPA fallback or `Service-Worker-Allowed: /` is needed.
The app route CSP needs `media-src 'self' blob:` for verified downloaded Blobs;
keep existing script/style/connect/Host/Origin/CSRF restrictions. API/media
responses must remain `no-store`. This repository does not install or deploy that
Go mount. In development the same public release can be served by the test-only
fixture; it is not real-Go acceptance.

The service worker caches only enumerated public release files. A new worker
stages a separate content-versioned cache, waits while the current release is
active, and activates only after the user confirms Restart. It never caches an
authenticated API, artwork, ticket, cookie or audio response. Offline startup
requires a successfully installed/controlling worker. Browser storage can still
be evicted; persistent-storage requests are not guarantees or backups. Firefox
desktop need not offer an install UI even when the PWA manifest is valid; Android
installation, background execution and OS media controls require device testing.

## Playback and storage

One audio element is shared between two mutually exclusive execution modes.
Online playback reuses the Go controller's server-issued execution identities,
ACK-based finite leases, staged-next rules and uncertain-command fences. Going
online does not upload a local queue, connect an executor or start audio.
Explicit Play admits the existing online session; musical actions are not replayed
on reconnect. The first connection may need a second explicit Play once ready.

Deliberate offline mode is persisted before network cancellation. Cold startup
opens only the last genuinely authenticated account/origin namespace and waits
for explicit Play. No passwords, tickets or CSRF values are persisted. Automatic
401/authentication loss and account change fence access and close the namespace without
erasing pinned bytes or pending receipts. Those remain inaccessible until that
same account is genuinely authenticated again. Only confirmed account-scoped
Sign out (including consent to discard pending plays) or Remove download purges
retained data. Broadcast fencing is origin/account/incarnation scoped; a local
admission record is not a server credential. A copied browser profile is not a
secure enclave, DRM system or proof of ongoing authorization.

IndexedDB owns account-isolated collection snapshots, verified full media Blobs,
retention references and immutable v1 offline receipts. Download admission is an
authenticated action, not a playback report. Partial/failed refreshes do not
replace a healthy complete collection. Pinned downloads and disposable temporary
cache intent remain distinct. Blob readers retain their representation through
removal; local output takes a Web Lock to avoid two offline tabs executing for
the same namespace. Actual observed progression—not seeks or selection—qualifies
one durable receipt; uncertain upload retries keep the exact same payload and ID.
Server duplicate ACKs must not recreate history after clear. Local byte admission
limits and browser quota errors are explicit failures, not complete downloads.
The current WebCrypto/Blob admission bounds are 32 MiB per representation,
128 MiB per staged collection and 8 MiB of manifest metadata. Files above those
bounds are refused explicitly. Cancelled transfers restart from zero; partial
bytes are not published or advertised as resumable. Receipt upload batches are
100, independently of the exact indexed pending total; there is no lifetime
receipt or playlist-count claim.

## Reused source and design

`vendor/playback-sync.js` is from spofify-server commit
`38d524e5c6967fae0d1bbe7ed620dc35267d50a1` (schema 41), with only the existing
server `remove.undo` / `undoRemove.token` command translation added. Its queue,
lease, instruction admission and report machinery are not reimplemented.
`vendor/audio-adapter.js` extracts that revision's `createAudioAdapter` unchanged.
The bundled JetBrains Mono variable font and full `assets/OFL.txt` come from the
same installed source and cover regular/medium/semibold/bold weights.

Presentation follows the immutable accepted reference SHA-256
`a18c75c85f8215a40229dc6effc966e096eb6c0808e85878fa8bd36c9376c33a`:
semantic Jade/Ember/Violet account palettes, History/Search/Playlists/Settings,
48px controls, lower search input, flat mini-player and centered five-column
transport. Illustrative reference statistics, waveform bins, lyrics and download
readiness are never copied as real data. Lyrics offline transfer is not allowed;
no offline timed lyrics are invented.
