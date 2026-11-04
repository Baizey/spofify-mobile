# FEAT-004 — Truthful native playback-device participation

## Tracking
- ID: FEAT-004
- Status: review
- Phase: implementation
- Spec readiness: ready
- Owner: device-recovery
- Branch: feat/mobile-device-playback
- Worktree: /var/home/lsm/repos/spofify-mobile-worktrees/playback-devices
- Depends on: none
- Discovery depends on: none
- Touchpoints: PlaybackDevicesApi.kt and protocol tests; PlaybackCoordinator.kt; NativePlaybackEndpoint.kt; PlaybackDevicesSheet.kt; Media3PlaybackEndpoint.kt; PlaybackService.kt; PlaybackConnection.kt; PlaybackSnapshotCodec.kt; PlaybackQueue.kt; MediaSession/system transport integration
- Next action: independently review and fully gate the enabled production participant against the merged foundation base, self-integrate and post-check under the main/build leases; native completion remains blocked by BUG-001

## Current state and evidence boundary

The source candidate is under final review for integration, **not native-complete**. Device-lane phase-A candidate `755860290fdfcaa04aaba28e26da71a550639de1` preserves the earlier `77d45be` history and fixes close publication/disposal, long derived occurrences and the notification lint error. Both independent protocol/native-critical reviewers passed that exact phase-A SHA. Its full leased gate passed **114 core + 358 app**, both APK assembly tasks and lint with **0 errors** (13 warnings + 1 hint). Those counts describe phase A, not automatic approval of the later enabled candidate.

Enablement commit `311b9db` sets production `Media3PlaybackEndpoint.supportsExactHandoff = true`; the actual `AppGraph` service callback now registers the phone through `PlaybackDevicesApi`, not a test-only capability override. `AppGraphPlaybackParticipationTest` exercises authenticated synthetic loopback registration, silent startup with playing server metadata, non-autoplay enqueue, current positive Play with honest foreground denial, incoming Pause/Close and exact ACK publication, and fresh silent registration after service recreation. The enabled real-service bridge and production-graph targeted gate passed **37 tests**. Existing real-service tests now use the production endpoint directly without capability overrides.

Foundation main `5c6cf00` and factual follow-up `3ae4417` have been normal-merged into the device branch. Device checkpoint `77d45be` had merged main `4183ed3` **into itself**, not the reverse: the device implementation was **not integrated into main `4183ed3`** or shipped by foundation. Git ancestry confirms `4183ed3` is an ancestor of `77d45be`, not the reverse. Integration of this reviewed device delta is still a separate leased transaction. Exact final candidate/base, full results, reviewer identities, APK hashes and main post-check evidence are retained outside the frozen candidate under ignored `.tooling/` evidence; no self-referential approval SHA is invented here.

## Outcome and service/authority contract

An Android service may participate as a truthful output only when it can transfer the exact prepared listening state and enforce live exclusive ownership across **every** start path. Until then retain the honest read-only/unsupported device UI and no fake native capability registration.

- Use the application's one Media3/MediaSessionService output, never a screen-owned second player. Service lifetime, not an open device sheet, determines participation.
- Import/export the **complete prepared queue**, traversal/order/shuffle baseline, duplicate occurrence keys, context and per-occurrence derived context, current occurrence, elapsed position and explicit playing intent. Preserve occurrence history/progress nonce/reported state. A long derived queue cannot be reduced to its visible page, a track-ID list, truncated context or a fresh saved-playlist fetch.
- Snapshot validation/preparation has no queue replacement, audio load/start or history side effect. Incoming URLs/paths are never trusted playback sources; resolve approved track IDs through the exact-origin authenticated client. Keep credentials/private paths out of snapshots, logs and UI.
- Activation and explicit commands require current service/auth/lifecycle/epoch/generation/lease authority. Fence every asynchronous continuation and all app, notification, MediaSession/system and restored start paths before any prepare/start or audible-history write. Remote-selected output is not local audible progress/history.
- Losing ownership, lease, session/account/origin, service scope or connection certainty synchronously invalidates pending starts and suspends actual buffered output. Delayed work/ACKs cannot resurrect old playback or affect another account. Recovery/reconnect/startup never autoplays; renewed intentional authorization is required.
- Preserve exact source-release position/state during handoff and report truthful blocked/media/rejected results. Do not substitute optimistic local UI state for actual output or ownership.

## Critical recovery acceptance

### Close and ACK integrity

A close/release interruption must cancel stale starts and suspend output while preserving the valid protocol completion/ACK path. Define and test the required final snapshot/ACK ordering; tearing down work or clearing pending ACK state must not falsely report success, lose the required close ACK or leave the peer waiting indefinitely. Cover delayed responses, duplicate delivery, close during activation/selection, auth/service teardown and generation changes with deterministic synthetic fixtures. An internal safety close is not a new ordinary user-facing “Close player” action.

### Long derived occurrences

Round-trip a genuinely long prepared derived queue, including duplicate tracks with different occurrence/context identities and history flags. Capability is true for **publication-safe bounded state**: individual occurrence IDs may use their full 4096-byte bound, but a canonical complete snapshot must fit the protocol's 2MiB outbound body with a 16KiB proven envelope/state-growth reserve. Structurally valid oversized incoming state remains decodable so preparation/activation can return a rejected ACK **before mutation**, preserving prior exact queue/history. Validate final rekeyed replacements, combined enqueue and undo restoration before installing/removing media or allocating live keys. Unsupported state is rejected, never silently shortened or substituted; this aggregate admission bound does not turn the working participant into a fake disabled endpoint. Include a full 1,000-row long-derived round-trip, next/current identity and history, not just short DTO equality.

### Capability barrier

The capability is now **true in the source candidate**, after phase-A critical corrections, independent exact-SHA audits and regression gates. Exact import/export and start-path fences remain prerequisites, not optional behavior. Final cumulative protocol/native-critical review and full automated gates must approve the enabled candidate before integration. Registration follows the real service lifetime; login, browsing and device-sheet presence alone do not advertise an output. No UI-only toggle, fake endpoint or test capability override substitutes for production registration. Incomplete native/audio/background evidence remains explicit even after source integration.

## Remaining acceptance and risks

Test rapid selections with latest-intent semantics, stale/lost lease, blocked/media output, cancellation, duplicate/out-of-order directives, one-shot uncertain writes and source/destination races. No blind remote write replay. Show that read-only browsing does not register an unavailable phone or create local history. Retain usable accessible device status, 48dp actions and native Back without turning the sheet into debug controls.

Main risks are split authority between app/system controls, ACK loss during teardown, silent derived-context truncation, duplicate identity collapse, and historical test/build evidence being mistaken for native handoff proof. Backend changes are out of scope; coordinate any protocol change rather than modifying or probing the live backend.

## Evidence and completion gate

Named automated evidence maps to the contract:

- `PlaybackSnapshotCodecTest`, `PlaybackSnapshotMediaTest`, `PlaybackServiceBridgeTest`: exact traversal/baseline/duplicates/context/history import, fractions, long occurrences, current positive Play and no token leakage/refetch.
- `PlaybackCommandResolverTest`, core `DerivedPlaylistsApiTest` and `PreparedPlaybackAuthorityTest`: original derived revision/fence/occurrence/selection envelope, original auth generation and bounded identity. Long occurrence stays in its 4096-byte field, never manual `entryId` (512 bytes).
- `PlaybackCoordinatorTest`, `PlaybackDeviceIdentityTest`: ownership, conservative leases, stable foreign-owner polling, latest selection, atomic execution result/ACK and origin/account-bound encrypted installation/name metadata.
- `PlaybackServiceBridgeTest`: actual service Close is silent/empty immediately; final ACK/state get one 4000ms publication attempt before disposal, with a 4500ms service fail-safe. Queued replacement holds retain cleanup, including stale/rejected/pre-entry-cancel paths, and have a 9000ms bounded watchdog. Old completions/timers cannot destroy installed successor state.
- `PlaybackForegroundNotificationTest`, `PlaybackAudioFocusTest`, `CoordinatedSessionPlayerTest`: genuine session-bound MediaStyle, honest FGS denial, scoped transport and no focus-gain autoplay. Notification-permission exemption is narrow, not an Android background-start exemption.
- `AppGraphPlaybackParticipationTest`: actual enabled production graph/API/coordinator/service registration and commands, silent startup/recreation, no audio/history/catalog calls; no injected protocol or endpoint. Full 1,000-row long-derived state publishes exactly; >4MiB incoming prepare/activate is decoded but ACK-rejected without changing it.
- Core `PlaybackPublicationTest` proves the 16KiB reserve covers worst escaped context/history/scalars and maximum poll identity/16ACK envelope. `PlaybackServiceBridgeTest` rejects combined enqueue overflow from two individually safe 500-row derived batches before any queue/key mutation; a subsequent small enqueue still receives the next live key.
- `PlaybackDeviceFixtureTest`: shipped protocol synthetic fixture authentication, transfer phases, exact release/activation state and commands. Compiled instrumentation is not an executed native test.

Record exact final candidate/base SHAs, fresh core/app counts, APK hashes, lint results and independent reviewer identities externally. A changed candidate or main base requires re-gating/re-review. Integration follows standing assignment-bound conditional authority and the separate advisory build/main leases in [worktree-review.md](../worktree-review.md). Only verified main integration/post-checks may be described as integrated source; source completion does not mark this task native-done.
Merging main into this device topic is not main integration or a completion certificate.

[BUG-001](BUG-001-native-validation-environment.md) is the **completion/validation gate only, never a hard source dependency**. Native AFTER-render, audio and background validation are **BLOCKED / NOT COMPLETE**. No successful native handoff/background/audio behavior is claimed. A future authorized isolated synthetic AVD journey must exercise device status, transfer/lease loss, app/system controls, lifecycle and inspected renders; genuine hardware-only limits remain explicitly unverified, not a reason to access a phone.

No further emulator startup/configuration, ADB/discovery, real phone, personal AVD, live backend/account/network or network-diagnostic attempts are authorized under the exhausted one-shot budget. This document does not grant builds, network access, device work or capability enablement.
