# Current library playback broker fixture

Linux-only, separately selected real-broker integration for
`spofify.library-playback.v2`. Ordinary portable JVM tests do not need it.
There is no legacy protocol, HTTP playback resolver, binary rebuild/download,
checksum override, sibling-checkout executable or hosted-location fallback.

## Immutable provenance

| Assertion | Value |
| --- | --- |
| Executable SHA-256 | `492a8dd2d834a3e48d10246b0459d12e9bba18322b6c3aa99693760e123a0de4` |
| Executable embedded VCS revision | `92f3e12a1a1f7b02fdd6586786646cf8c14dbca0` |
| Executable embedded VCS modified | `false` |
| Audited server source revision | `c23a9db747b20b492697f141369ad9388e729a53` |
| Identical Git source tree at both revisions | `842a060f65396e3fd1ad5d5c314874ecfaa10ee7` |

The executable is **not** labelled a c23 VCS build: source-tree equivalence is
separate provenance. Its pinned digest binds these audited assertions; the fixture
verifies that digest on every launch. The existing hosted asset `715407812` with
SHA `425e4b…` is obsolete b6 and does not satisfy this fixture. A currently hosted
artifact location has not been established. Selected integration fails closed
until the correct artifact is explicitly provisioned; never reseal the old asset.

## Provisioning and bounded lifecycle

Set both variables explicitly:

- `SPOFIFY_TEST_GO_BROKER_BINARY`: absolute immutable private executable copy.
- `SPOFIFY_TEST_LIBRARY_PLAYBACK_RUNTIME_ROOT`: existing owner-private (0700),
  canonical writable SSD runtime parent; no `/tmp` or `/dev/shm` fallback.

Python 3.11+, Linux subreaper support and installed `/usr/bin/ffmpeg` with MP3
encoding are prerequisites. No browser, provider credentials or Internet access
is needed. The fixture allocates/removes only a unique owned runtime child.
HOME, XDG, temp and Go state are private; the server environment is allowlisted,
with no inherited provider configuration and proxies closed to external traffic.
HTTP and WebSocket both go directly to the same owned `127.0.0.1` listener. There
is no HTTP-only relay that would break WebSocket upgrades.

Preflight checks apparent free bytes, visible inodes, available memory and SQLite
WAL/mmap at the selected location. Probe and seed connections explicitly close
native SQLite handles after transaction completion/failure; a connection context
manager alone is insufficient. This ownership rule is not a guarantee that a
particular host filesystem supports successful cleanup. Quota/inode visibility can remain unknown;
apparent free space is not quota proof. Runtime is monitored at 2 GiB and logs
at 16 MiB; any separately retained evidence must remain within 128 MiB. Go uses
`GOMEMLIMIT=256MiB`, `GOMAXPROCS=2`; this is not a kernel memory hard limit.
The process budget is one Python supervisor, one Go broker, one short FFmpeg
seed child, plus broker-owned descendants. No browser or Gradle launch occurs.

The dedicated supervisor accepts `--seconds 30..120` (default 120). Keep its
private stdin open and read exactly one bounded (16 KiB, 45-second) stdout JSON
handshake. That handshake includes **synthetic passwords** and must never be
logged, written to disk, uploaded or copied into test failure messages. Tickets,
cookies and CSRF are runtime-only too. Diagnostics contain allocation/failure/
cleanup classifications, never raw backend logs or auth payloads.

Stop by writing `stop\n` or closing stdin. Await the **original** supervisor with
20 seconds shutdown margin, success exit, and stderr JSON
`fixtureCleanup:complete, descendants:ECHILD`. Launcher exit alone is not proof.
The subreaper stops/awaits its original children and adopted descendants before
checking ownership and removing runtime. Before deletion it emits a separate
`fixtureStopped:complete`/ECHILD record with original-launcher return codes and
writes `.stopped.json` in the owned runtime. Content-deletion failure preserves
that proof and `.owner`; it is still a failed cleanup, not a green gate. The Kotlin
helper preserves redacted structured diagnostics in its private control child on
nonzero exit. Missing proof retains state; do not force-kill the subreaper and
then recursively delete its runtime.
Use foreground supervision: internal lifetime plus shutdown must fit before an
outer tool timeout of at least 165 seconds. Never launch a top-level background
controller whose shell returns without retained supervision.

## Executable checks

With the private environment/root already configured:

```sh
# Offline lifecycle/pin oracles; no Go process:
python3 -B tools/testing/library-playback-fixture/supervisor_test.py

# Explicitly authorized, foreground, actual-binary protocol smoke:
timeout --signal=TERM --kill-after=20s 140s \
  python3 -B tools/testing/library-playback-fixture/smoke_test.py

# Only after source/build admission, using provisioned private Gradle state:
./gradlew :app:testDebugUnitTest --offline --no-daemon --console=plain \
  -Pspofify.realBrokerIntegration=true \
  --tests 'dev.spofify.mobile.playback.LibraryPlayback*BrokerIntegrationTest'
```

The Kotlin helper retains and awaits the Python supervisor across all
coordinator/client lifetimes in each test. It fails missing/mismatched current
pins rather than skipping or using mocks. Tests use production ticket/socket
transport, codec, coordinator and executor. `LibraryPlaybackBrokerIntegrationTest`
uses a deliberately **fake native endpoint** and distinguishes injected observation
from desired intent/ACK, complete canonical queues with duplicate entry order,
exact selected connection, uncertain source-loss drain, passive reconnect without
command replay, and account fencing.

The separately selected `LibraryPlaybackServiceBrokerIntegrationTest` (in the same
source file) uses actual AppGraph -> current coordinator -> Media3PlaybackEndpoint
-> PlaybackService -> real ExoPlayer, with the existing `PreparedTransportMedia`
shadow replacing only media source/PCM renderer. Its current-protocol journeys
replace the retired AppGraph/RemoteAppGraph/ReplacementAppGraph polling journeys:

| Applicable guarantee | Current executable case |
| --- | --- |
| Mini/full controls, real posted Pause/Resume, MediaController Stop, attributed headset callback and duplicate/repeat/up fencing | `realMiniFullPostedNotificationAndHeadsetIngressControlCurrentWsMediaOnce` |
| First BUFFERING Pause cancels native work before late READY; fresh explicit Resume really progresses | `pauseDuringActualFirstBufferingCancelsStartAndLateReadinessCannotReplay` |
| Paused A42, complete two-row Low receipt/order after High, duplicate occurrence keys, Next, modes, move/remove/clear/rename without native restart/seek | `pausedA42CompleteLowAppendAndStructuralEditsPreserveActualMediaAndPriority` |
| Real renderer failure followed by a fresh retry retaining current event/queue | `actualRendererFailureThenFreshCurrentProtocolRetryProgressesSameOccurrence` |
| Actual ended event advances the canonical queue; explicit Jump creates a new traversal rather than replaying an old receipt | `actualEndedEventAdvancesCanonicalTraversalAndExplicitJumpCreatesFreshOccurrence` |
| Remote mini/full control, real remote clock/PCM, bidirectional source-stop transfer, retained High/Low/current/event, paused return without autoplay | `miniFullRemoteControlsAndBidirectionalTransferUseTwoActualFencedMedia3Outputs` |
| One account coordinator, real service progress/stop, stale notification/noisy/late-focus non-replay, exact new connection, same-cookie configure/restore/service replacement, backend-qualified nonduplicated history and account fences | `actualServiceProgressStopTransferReconnectSameCookieReplacementAndAccountFence` |

The remote-output case uses a **second genuine host ExoPlayer** under the production
current-WS coordinator/executor, not a fake Player or another singleton service.
The lifecycle case additionally uses an incapable/controller-only connection,
whose empty activation ACK is not audio evidence. The process-singleton service
is never presented as two independent service endpoints. Keep
`PreparedTransportMedia` and the unchanged frozen `core/src/test/resources/queue-intents-v1`
corpus bytes when retiring obsolete DTO-based journeys.

Old registration/claim, REST queue preparation/publication, poll epoch/sidecar,
client history-POST and held-HTTP-publication refusal/copy assertions do not have
v2 compatibility aliases. Their applicable safety boundaries are current exact
connection/send-time lease/observation/one-shot command fences (also exercised by
`LibraryPlaybackExecutorTest`, `LibraryPlaybackCoordinatorTest` and core transport
units), not emulated b6 endpoints. The old browser HTMLMediaElement-specific
observation is **not reproduced** by the remote host Media3 case; that is a
separate browser layer, not an implied equivalence pass.

These are authored executable checks, not an assertion that they have run. The
service source substitution does not prove authenticated audio/range delivery,
decoding, acoustic, installed native, hardware, background/FGS or rendered-device
behavior. The transport trace covers WS commands/ACKs/reports, not a complete
HTTP-attempt audit; no claim of such an audit is made. The fixture creates synthetic
catalog/audio directly in its disposable DB following the server seed convention,
without acquisition jobs.
