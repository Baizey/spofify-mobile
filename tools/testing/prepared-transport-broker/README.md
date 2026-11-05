# Prepared transport: real pinned broker test fixture

Test machinery only; no production dependency, Node/browser/framework, downloads,
live account, discovery, Go build/cache/configuration/runtime write or native probe.
Uses the existing binary read-only, verifies SHA-256 independently, and copies it
once to the explicitly owned fixture root:

- Revision: `b6eb54ffdc91e07e2ce7824b8be5f90afa8b05eb`
- SHA-256: `425e4bb46efcf1c25e195d92d4d1845ed2389cd35e6ebf9ad8981cd80c2c70f4`
- Required input: `SPOFIFY_TEST_GO_BROKER_BINARY`, pointing to an independently
  provisioned executable with that exact SHA-256. There is **no host/sibling binary
  fallback**, including when a reusable copy already exists.
- Retained copy: `$SPOFIFY_TEST_FIXTURE_ROOT/spofify-b6eb54f`; optional root defaults
  to this worktree's `.tooling/prepared-transport-broker`.
- Optional `SPOFIFY_TEST_GO_BROKER_RUNTIME_ROOT`: existing writable parent for
  unique scratch. If omitted, Linux prefers writable `/dev/shm` with sufficient
  capacity, otherwise Python `tempfile.gettempdir()`; the fallback filesystem must
  support SQLite's mmap/WAL requirements.
- Prerequisites: Linux Python 3.11+, `/usr/bin/ffmpeg` with MP3 encoder, readable
  provisioned binary, and writable retained/runtime roots with capacity checked.

## Launch and JVM composition

Explicitly provision before selecting the real-broker integration category:

```sh
export SPOFIFY_TEST_GO_BROKER_BINARY="/absolute/path/to/provisioned/spofify-b6eb54f"
export SPOFIFY_TEST_FIXTURE_ROOT="$PWD/.tooling/prepared-transport-broker" # optional
# Only for this environment's documented repository/tmp FUSE mmap constraint:
export SPOFIFY_TEST_GO_BROKER_RUNTIME_ROOT=/dev/shm
python3 -B tools/testing/prepared-transport-broker/broker_fixture.py --seconds 300
```

The selected real-broker integration is mandatory acceptance, not a default-unit
prerequisite. Missing/invalid provisioning is a **hard failure**, never a skip,
mock, fallback binary, download or build. The JVM process helper inherits these
variables and fails clearly when the required binary setting/file is absent.
The Python runner validates the exact SHA-256 even when reusing its private copy.
Omit `SPOFIFY_TEST_GO_BROKER_RUNTIME_ROOT` for automatic Linux scratch selection.
The chosen parent is preflighted; each fixture allocates, records and removes only
its own unique child, never the parent or a peer. There is no shared mutable broker
or database. Missing/invalid binary provisioning fails before creating directories,
copying files, allocating runtime or launching processes. Parent owns category selection and environment
export in its existing runner; these helpers do not edit shared Gradle settings.

Run from a dedicated child process with **stdin kept open**. The first stdout
line is private JSON (under 16 KiB) containing `baseUrl`, `username`, `password`,
`tracks` (`A`, `H1`, `C1`, `C2`, `B1`, `B2` with actual imported IDs), `albumId`,
`artistId`, broker pins, `controlToken`, and remaining internal lifetime. Do not
include the handshake in test reports/logs or put credentials in a URL. Read this
line with a finite timeout (45 seconds is ample); do not call an unbounded
`BufferedReader.readLine()` on the test thread. Keep stderr separate, bounded,
and drained; it contains only concise cleanup diagnostics on normal success.

New JVM adapter:
`app/src/test/java/dev/spofify/mobile/playback/PreparedTransportBrokerFixture.kt`.

```kotlin
val fixture = PreparedTransportBrokerFixture.fromHandshakeJson(privateHandshakeLine)
// On the normal Robolectric main thread/coroutine test setup:
val sessions = PreparedTransportBrokerFixture.MemorySessions()
val graph = fixture.authenticatedGraph(context, sessions)
val client = requireNotNull(graph.client)
val a = fixture.tracks.getValue("A")
val high = fixture.tracks.getValue("H1")
// Use real graph/ViewModel/Connection/service paths and real seeded IDs.
// No registration callback, endpoint, authority or start success is overridden.
graph.configure(fixture.server) // SAME broker/store/cookie; NO successor login.
// ... actual service destroy/create, registration/picker and retained-Resume checks ...
graph.clear()
```

`authenticatedClient()` separately provides a genuine production SpofifyClient
login for controller-side requests. Every invocation creates a new memory-only
session store unless one is explicitly passed. `authenticatedGraph` uses a
memory-only login store and preserves production coordinator/service callbacks.
It does not select, claim, prepare, register or start music on the test's behalf.
The test owns its graph teardown and JVM `java.io.tmpdir`/Gradle BUILD lease.
Both `authenticated*` helpers always log in. Do not call them for a same-cookie
successor: retain the store and use actual `AppGraph.configure`/service replacement
without logging in again. See `PreparedTransportReplacementAppGraphTest` for the
complete old-row disposal, passive-silence, picker, retained-Resume and history chain.

**Keep one runner alive across all tested client/service lifetimes.** Do not
restart it for every graph. When finished, write `stop\n` and flush, or close the
runner's stdin. Await runner exit with a cleanup margin (normally < 15 seconds;
20-second wait after stop). On failed setup, allow its internal finite deadline
plus 20 seconds if necessary. Do not delete its scratch merely because a launcher
exited or was forcibly killed. Exit zero and the stderr `fixtureCleanup:complete`
receipt establish normal awaited cleanup. A cleanup exception preserves scratch
and must be reported. Outer timeout wrappers must exceed `--seconds` by at least
15 seconds; they supplement rather than replace the internal deadline/reaper.
`--seconds` must be 15–600 (default 120).

The proxy is already the handshake's base URL. Both origin/Host and all auth
cookies/CSRF reach real Go; it connects only to the runner's loopback upstream.
Its downstream HTTP/1.0 forward and control responses explicitly advertise
`Connection: close`, matching the handler's actual one-response socket lifetime.
Cached OkHttp 5.5.1 bytecode confirms that a response `Connection: close` invokes
`noNewExchangesOnConnection`; fixed-length bodies alone do not take that path.
This is fixture-only connection framing, not a production/broker bug claim.
The parent's unchanged-test before/after composition establishes causality;
the earlier Python client checks did not reproduce OkHttp socket reuse.
The small free-port bind/start window can fail honestly if another process races
for the port; no fixed shared port or discovery is used.

## Hold/drop a real accepted Pause publication

Control endpoints are on `baseUrl` and require header
`X-Fixture-Token: <private controlToken>`. Use a separate bounded HTTP client:

1. `POST /__fixture/arm` with JSON `{"mode":"hold"}` or `{"mode":"drop"}`.
2. Drive the **production** Pause action/publication.
3. `GET /__fixture/status` until `accepted:true` (finite test deadline).
4. Its `status:200` and `stateSeq` witness are recorded **after fully reading a
   real Go response**, not merely after request arrival. The bounded scalar witness
   also records request action/epoch/playing, ACK count, command presence, response
   epoch and directive count. This distinguishes an ordinary playing heartbeat
   from command/ACK traffic without retaining identities or payloads. A separate authenticated
   `GET /api/me/player` can verify Go has the paused state before client delivery.
5. With a hold active, test admission/feedback and then
   `POST /__fixture/release` with `{}`. No automatic retry/replay is installed.
6. A drop closes the connection without forwarding any HTTP response. Acceptance
   witness remains available through status. After completion another arm is allowed.

Only the next real HTTP-200 `/api/me/player` POST `poll` containing a state whose
`playing` is false is eligible. Arm immediately before the intended Pause and
confirm its sequence/state independently: an unrelated nonplaying publication
can otherwise match. `hold` lasts at most 12 seconds, and a hold timeout becomes a
lost response, never fabricated success. Tests must release before their actual
production HTTP request deadline to distinguish held from lost. The optional `target` control remains narrowly allowlisted: default `paused-poll`,
`claim` (literal conditional self transfer), or `queue-start` (playing owner-state
publication). FEAT-114 arms the default immediately before an empty High admission,
so its first accepted paused publication is the activation ACK; it separately drops
accepted claims and post-start queue publications. All faults act only after fully
reading real Go success, forwarding unchanged bytes or losing that real response.
The proxy does not manufacture registrations, directives, epochs, leases, bindings,
ACKs, state or command outcomes. Maximum concurrent proxy handlers: 32; request 2 MiB;
response under 8 MiB. No request bodies/auth headers are retained in diagnostics.

## Deterministic real handoff epoch boundary

The same token protects these one-shot controls:

1. `POST /__fixture/arm-handoff` with `{}` immediately before the actual phone
   output selection. The fixture observes its real accepted, source-present transfer.
2. It holds the real source release-ACK request until an empty old-epoch target poll
   is held before upstream dispatch. It forwards the ACK unchanged; only Go's actual
   successful release/epoch advance releases that original target request unchanged.
3. Go—not the fixture—returns `409` with the exact `stale epoch` rejection. The
   subsequent empty fresh-epoch own poll is separately held before dispatch.
4. `GET /__fixture/handoff-status` exposes bounded booleans/status only. While
   `freshPollHeld` is true and `freshPollForwarded` false, the app test proves that
   the error view granted no authority, endpoint activation, PCM, focus or FGS.
5. `POST /__fixture/release-handoff` with `{}` forwards that fresh request unchanged.
   Real activation/ACK and actual native pipeline progress must then succeed.

Each barrier is bounded to two seconds and fails closed on timeout/cancellation;
there is no fabricated response, fallback, automatic mutation retry or protocol
rewrite. Private source/target/command IDs exist only in bounded fixture memory.
`GET /__fixture/trace` exposes at most 64 allowlisted events: action/type, status,
epoch/phase, playing flags, metadata presence/revision, and exact stale-epoch bool.
No cookies, credentials, URLs, names, raw payloads or delivery/command IDs are emitted.
The test checks the same queue, High/Low metadata, occurrence and backend history
through playing website-to-phone and paused phone-to-website handoffs.

`python3 -B tools/testing/prepared-transport-broker/handoff_barrier_test.py` checks
both request orderings, cancellation/timeouts, privacy and unchanged bytes with
synthetic in-process inputs. Those checks are **not** real broker acceptance; the
provisioned AppGraph test supplies that separate execution evidence.

## Fixed request metrics

Fixture control `GET /__fixture/metrics` requires the same private `X-Fixture-Token`
and returns only six fixed integer counters. JVM adapter:
`val before = broker.metrics()` / `val after = broker.metrics()`.

- `registrations`: player register attempts.
- `transfers`: all player transfer attempts.
- `claims`: the subset of transfers carrying literal `requireUnselected:true`.
- `polls`: player poll attempts.
- `collectionPlaybackReads`: album/artist/manual-or-derived-playlist/likes playback
  snapshot requests (GET or POST).
- `historyWrites`: POST attempts to the history endpoint.

Counters increment at the forwarding attempt, before any request barrier, including
failed/rejected/lost requests. They never claim acceptance or receiver execution. Use deltas and actual
broker/receiver state to assert no refetch/re-registration/extra history attempt.
No raw paths, bodies, selection IDs or auth material are retained; parsing is
transient and Go remains the only admission/validation authority. Attempt counters
are never a substitute for actual receiver ACKs, position or consumed PCM.

## Executed conformance and limitations

After the explicit environment provisioning above (standalone conformance consumes
the same binary/root settings; it has no separate host-only fallback):

```sh
PYTHONDONTWRITEBYTECODE=1 timeout --signal=TERM --kill-after=15s 115s \
  python3 -B tools/testing/prepared-transport-broker/conformance.py
```

This has its own 100-second fixture deadline and awaited cleanup. Verified against
the pinned actual executable: distinct authenticated sessions on one immutable
account; read-only extension probe; initial startup quarantine; session-bound
register/poll refusal across sessions; prepare/activate and prepare/release/activate
with state-bearing ACKs; binding-preserving publications/renewals; accepted hold
and lost response; 5-second availability vs retained registration; delivered
ordinary command's 8-second missing-ACK timeout while owner heartbeat renews its
lease; resulting epoch fence/quarantine and drain without automatic ownership;
30-second registration expiry; leave. Wall-clock checks use small explicit
boundary margins, not a substitute virtual clock. No stronger virtual-time
conformance, BR-001 receipt, full queue-intent effect, or exact expiry-at-millisecond
claim is made. The conformance receivers only publish **paused synthetic state**;
there is no real audio, website receiver, Media3, successful startOwned, AppGraph
execution or native rendered/audio/background acceptance claim in this lane.

## Storage and descendant accounting

Preflight observed repository apparent free space ~3.18 TB; repository inode
counts are not exposed. `/tmp` apparent free ~27 GB and ~1 million free inodes.
`/dev/shm` apparent free ~33 GB and ~8 million inodes. `quota -s` could not resolve
mountpoint descriptors: **user/project quota headroom unknown**. Recheck for each
artifact-heavy parent run. Runner additionally requires 2 GiB apparent headroom
both at the configured retained binary root and configured runtime parent before
starting. The runtime filesystem must support the pinned SQLite backend.

Pinned Go SQLite failed at `open_database` on this environment's repository FUSE
mount; two failed disposable runs were fully reaped/removed. Our current environment
therefore explicitly exports `SPOFIFY_TEST_GO_BROKER_RUNTIME_ROOT=/dev/shm`.
Without an override the helper prefers suitable Linux shared memory, falling back
to the normal temporary directory only if needed. A fallback lacking SQLite mmap/WAL
support fails explicitly; it never changes the broker or claims a conformance pass. Runtime is uniquely allocated beneath the configured
parent as `spofify-transport-broker-*` (mode 0700), never in Go or shared caches.
Cleanup verifies the same resolved parent and exact owned leaf before removal.
HOME/TMPDIR/GOTMPDIR/data/library/cwd are all this exact owned scratch. A watchdog checks deadline/storage every 250 ms and stops production at
2 GiB scratch or 16 MiB combined logs; this is a monitored bound, not a filesystem
quota. HTTP requests are bounded to four seconds; FFmpeg to 15 seconds. Only six
120-second sine MP3 files are imported (no provider configuration is inherited).
The verified reusable binary occupies ~27 MiB, below the 128 MiB retention budget.

The dedicated runner sets Linux `PR_SET_CHILD_SUBREAPER`, awaits its own child
processes and adopted descendants until `waitpid` returns ECHILD, joins all proxy
handlers/watchdog threads, and only then removes its exact owned scratch. Normal
cleanup retains just the reusable verified binary, not sessions/database/audio.
Unexpected descendants/thread liveness preserve scratch and fail rather than
claim cleanup. No foreign directory/cache walk or broad temporary cleanup is used.
