# Prepared transport regression gates

The default offline unit gate remains portable: ordinary tests, including the real
ExoPlayer/synthetic-PCM mini/full/posted-notification regressions, do not require a
Go checkout, a private binary or browser installation. Tests that start external
processes use JUnit category `dev.spofify.mobile.testing.RealBrokerIntegration`.
They are excluded by default, not silently skipped after startup.

## Mandatory provisioned integration gate

For prepared-transport delivery/CI acceptance, provision the immutable Go broker
artifact from revision `b6eb54ffdc91e07e2ce7824b8be5f90afa8b05eb`, SHA-256
`425e4bb46efcf1c25e195d92d4d1845ed2389cd35e6ebf9ad8981cd80c2c70f4`.
Supply its readable path explicitly in `SPOFIFY_TEST_GO_BROKER_BINARY`.
No test downloads or rebuilds it, writes into a Go checkout, or substitutes a mock
when provisioning is missing. Missing/mismatched prerequisites are hard failures
when this category is selected.

The isolated runner requires Linux/Python 3.11+ and installed FFmpeg with an MP3
encoder. The selected-website tests additionally require an installed Playwright
module (`SPOFIFY_TEST_PLAYWRIGHT_MODULE`) and compatible Chromium executable
(`SPOFIFY_TEST_CHROMIUM_PATH`); neither is downloaded. They execute the real pinned
website and inspect HTMLMediaElement progress, not a recording receiver.
`SPOFIFY_TEST_WEBSITE_RUNTIME_ROOT` optionally selects its supervisor-owned scratch
parent. Both process launchers require exit success and descendant-cleanup receipts.
The browser supervisor's deterministic Python-only failure-path tests run separately:
`python3 -B tools/testing/prepared-transport-broker/website_supervisor_test.py`.
They use tiny owned subprocesses, not a browser, to verify disappearing scratch
entries, exceptional shutdown, ECHILD and untouched parent/sibling resources.
`python3 -B tools/testing/prepared-transport-broker/handoff_barrier_test.py` separately
checks the bounded handoff request choreography using synthetic in-process inputs;
it does not start a broker or establish real protocol/execution acceptance.

`SPOFIFY_TEST_GO_BROKER_RUNTIME_ROOT` optionally selects a supervisor-owned,
mmap-capable runtime base. Each fixture creates and removes only its unique owned
child, never that parent or another feature's runtime. SQLite on a FUSE-backed
runtime is not assumed supported; the fixture performs bounded setup and reports
failure. See the [fixture documentation](../tools/testing/prepared-transport-broker/README.md)
for provisioning, lifecycle, synthetic catalog, proxy faults and protocol checks.

After the repository's BUILD guard/lease and storage preflight, run:

```sh
# Environment paths are provisioned by the host/CI, not committed machine defaults.
export SPOFIFY_TEST_GO_BROKER_BINARY=/path/to/verified/pinned/spofify
export SPOFIFY_TEST_GO_BROKER_RUNTIME_ROOT=/path/to/owned/mmap-capable/base
export SPOFIFY_TEST_PLAYWRIGHT_MODULE=/path/to/installed/playwright
export SPOFIFY_TEST_CHROMIUM_PATH=/path/to/installed/chromium
export SPOFIFY_TEST_WEBSITE_RUNTIME_ROOT=/path/to/owned/mmap-capable/base
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug \
  :app:assembleDebugAndroidTest :app:lintDebug \
  -Pspofify.realBrokerIntegration=true --offline --no-daemon --console=plain
```

`true` runs **all ordinary and integration tests**. A malformed boolean property
fails configuration. An ordinary fast-unit pass is not this integration gate.
For a focused integration failure/configuration probe, select its exact test
method with `--tests`; do not rerun unrelated tests merely to check missing
provisioning. The accepted gate must have zero skipped tests and complete
prerequisites for every landed feature using the common category.

## CI provisioning is explicit

The Android workflow opts into the full integration category. Repository variable
`SPOFIFY_TEST_GO_BROKER_ARTIFACT_URL` must point to the immutable HTTPS artifact;
the job performs a bounded private download and checks the frozen SHA before
making it executable. An absent URL or wrong digest fails acceptance, rather than
running only fast units. This change does not publish an artifact, set a remote
variable, or establish that remote CI has run.

The workflow also requires separately provisioned installed Playwright/Chromium
paths via repository variables matching the browser environment names above,
plus Node and FFmpeg. Path variables do not install these prerequisites: the
chosen runner must actually supply them. A stock runner without that provisioning
fails explicitly. No browser download or unverified remote-CI success is implied;
local provisioned integration evidence and remote CI are separate.

## Evidence boundaries

The service is the real production service/player/session. Its test-only builder
shadow supplies Media3 SilenceMediaSource and a bounded PCM-consuming renderer;
Media3 itself drives prepare/stop, READY, isPlaying, listeners and position. These
are synthetic pipeline observations, not Android codec/device-sink or acoustic
proof. MediaController connection setup is bound to the actual service session;
it does not prove Android component-binding lifecycle behavior. Positive headset
callback-ingress coverage uses the actual installed callback and a genuinely
connected same-process controller identity. Installed Robolectric does not route
framework media-button dispatch through its deep-proxy session manager: this is
not framework legacy-caller attribution, hardware key delivery or acoustic proof.

The external broker is real, persistent and authenticated. A proxy can hold/drop an
already accepted response, or hold real unchanged handoff requests across a real
release/epoch advance. It cannot invent an owner, command, rejection, execution ACK
or lease. The deterministic handoff test separately holds the fresh own poll and
proves that an error reconciliation view alone supplies no authority or output.
A transparent ingress observer forwards every member, argument and result to the
exact AppGraph-created Media3 endpoint; it only records bounded policy booleans.
It fabricates no successful endpoint state. Paused-state protocol conformance alone
is not successful Resume: actual position/PCM, retained occurrence and no refused
click replay are required.

A rejected empty inbox poll during an exactly matched accepted handoff is distinct
from a lost mutation or ACK. The API boundary retains only a typed, allowlisted
stale-epoch fact from a validated error view. The coordinator neither clears prior
uncertainty nor grants authority from that view. A one-shot witness preserves
cancellation/target/session/ABA obligations through the fresh poll, suspended
handoff validation and actual endpoint execution. Only the real fresh owner poll
and ordinary activation/start guards can authorize output. Source-less recovery,
passive reconnect, unknown writes and all nonmatching cases remain fail-closed.

Replacement coverage uses one persistent broker and the same stored cookie across
actual graph reconfiguration and service destruction/creation. The independent
same-cookie observer negotiates extensions itself. No fresh login, catalog Now or
replacement music shortcuts the old-row disposal, passive silence, actual picker
paused activation and retained-current mini/full Resume/history checks.

BUG-103 adds `TransientPlaybackRecoveryAppGraphTest`: a real accepted ordinary
playing heartbeat response is lost after actual A PCM/position plus High/Low setup.
Scalar witnesses establish no command/ACK/directive in that fault. Healthy same-owner
polls stay silent; a fresh actual mini Play resumes exact retained A, while fresh
actual More High inserts without starting unchanged current. Short-held response
continuity and an old pre-failure More capture are separate regressions. Focused
coordinator tests supplement these host paths with metadata/semantic/origin/lifecycle
and pending-work refusal checks; their recording endpoint is not broker acceptance.
Initial single-track setup uses the public VM Now path: actual catalog More Now
correctly expands its complete album context, not a one-row replacement oracle.

Installed-Android rendering, native audio/background/FGS delivery and AVD-to-website
acceptance remain separate mandatory gates. Current Pixel 10 Pro validation is
not complete: the assigned native assessment found its AVD/recent boot marker but
the existing control route was unavailable from the tool environment. No current
boot failure or app inspection was observed; an approved accessible ADB route is
pending. The recorded preboot-emulator failure is historical, not a verdict about
that AVD; no JVM/broker result turns into a native pass. Follow [testing.md](testing.md), [ui-validation.md](ui-validation.md)
and [worktree-review.md](worktree-review.md) for resource fencing and exact-candidate
review/integration.
