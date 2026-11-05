# Prepared transport real website receiver (test only)

`website_receiver.cjs` runs only an explicitly owned private browser against the
synthetic loopback fixture. It does not start a live backend, download dependencies,
or provide native Android/acoustic approval. Successful `progressed` means actual
headless desktop HTMLMediaElement time advanced, not merely a playing flag.

## Explicit offline provisioning

The opt-in JVM integration category requires provisioned prerequisites. Missing
prerequisites are hard failures when selected, never assume/skip/fallback:

```sh
export SPOFIFY_TEST_GO_BROKER_BINARY=/absolute/path/to/required-pinned-broker
export SPOFIFY_TEST_GO_BROKER_RUNTIME_ROOT=/absolute/path/to/owned-mmap-capable-parent
export SPOFIFY_TEST_PLAYWRIGHT_MODULE=/absolute/path/to/installed/playwright
export SPOFIFY_TEST_CHROMIUM_PATH=/absolute/path/to/installed/chromium-executable
# Optional private website scratch parent; default is /dev/shm.
export SPOFIFY_TEST_WEBSITE_RUNTIME_ROOT=/absolute/path/to/owned-runtime-parent
```

The exact Chromium variable is **`SPOFIFY_TEST_CHROMIUM_PATH`**, not
`SPOFIFY_TEST_CHROMIUM_BINARY`. No host-specific installed paths are defaults.
The broker executable must match revision
`b6eb54ffdc91e07e2ce7824b8be5f90afa8b05eb` and SHA-256
`425e4bb46efcf1c25e195d92d4d1845ed2389cd35e6ebf9ad8981cd80c2c70f4`.
Python 3 and Node must already be installed on PATH. See `README.md` for broker
provisioning, resource preflight and separate Gradle BUILD lease requirements.

`PreparedTransportRemoteAppGraphTest` uses the existing `RealBrokerIntegration`
category, selected with `-Pspofify.realBrokerIntegration=true`. Ordinary default
unit tests exclude that category and do not require browser provisioning.
No Gradle task or lease is acquired by the standalone receiver itself.

## Private JSON-lines protocol

Launch `node /absolute/path/to/website_receiver.cjs` with piped stdin/stdout and
separately bounded/drained stderr. Keep stdin open for the desired receiver
lifetime. Never log the private start message or broker handshake.

- Start: `{"op":"start","fixture":<private broker handshake object>,"seconds":180}`.
  Lifetime is 15–600 seconds. The response appears only after a genuine website
  login, one DOM album Play gesture and measured actual media progression.
- Observe: `{"op":"observe"}` returns read-only positive player/sync state and
  actual media paused/currentTime/readiness/error values.
- Progress: `{"op":"progress","milliseconds":600}` returns `before`, `after`
  and `progressed`; interval must be 200–2000 milliseconds.
- Stop: `{"op":"stop"}`. EOF also requests shutdown.

Responses use `{op,ok,result}` or a stable sanitized failure classification.
They contain no origin, credentials, cookies, CSRF, media URLs or control tokens.
Only the real website executes/ACKs commands; the receiver helper does not synthesize
execution ACKs or override player/media/authority/clock behavior. The production
JVM controller owns remote commands and must await real broker terminal outcomes
before evaluating receiver progress. Broker and website lifetimes are independent.

## Shutdown and limits

The Node entry launches its own embedded Python **subreaper** supervisor, which
launches the Node worker/browser. On stop, await the original entry's exit zero
and the separate stderr receipt
`{"receiverCleanup":"complete","descendants":"ECHILD",...}`. Browser close
or a stop response alone is not cleanup proof. The supervisor removes only its
owned unique scratch after all original/adopted descendants have been reaped.
The JVM bridge does not force-kill either launcher or delete scratch. On missing
receipt/timeout report cleanup unverified and preserve ownership for recovery.

Preflight runtime/output bytes, inodes and visible quotas; free bytes do not
establish quota headroom. Scratch is monitored against 2 GiB and a finite supervisor
lifetime. Protocol permits at most 1,000 messages of at most 32 KiB each. Callers
must bound/drain stdout/stderr, keep logs within 16 MiB and retained evidence within
128 MiB, and account for cleanup after failures/cancellation. No screenshots or
runtime logs are retained by this helper.

## AppGraph test boundary

The new remote test composes real mini/full controls over production AppGraph,
ViewModel, Connection, bound MediaController and service, with actual website media
progress and broker outcomes. It also checks exact queue/binding/history-event
identity and an empty/silent local raw player with no local history credit.
Compilation/execution belongs to the lead's scoped BUILD run.

Holding the website receiver's already-accepted Pause ACK response does **not**
establish a phone-own-pending Pause: Go atomically accepted paused state and terminal
ACK, and the phone can observe that via its own poll. The held-response test names
this boundary honestly. It is not evidence of pending phone Resume rejection,
no-auto-replay admission behavior, or native Android rendering/audio/background.
