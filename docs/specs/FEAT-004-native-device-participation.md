# FEAT-004 — Truthful native playback-device participation

## Tracking
- ID: FEAT-004
- Status: in-progress
- Phase: implementation
- Spec readiness: ready
- Owner: device-recovery
- Branch: feat/mobile-device-playback
- Worktree: /var/home/lsm/repos/spofify-mobile-worktrees/playback-devices
- Depends on: none
- Discovery depends on: none
- Touchpoints: PlaybackDevicesApi.kt and protocol tests; PlaybackCoordinator.kt; NativePlaybackEndpoint.kt; PlaybackDevicesSheet.kt; Media3PlaybackEndpoint.kt; PlaybackService.kt; PlaybackConnection.kt; PlaybackSnapshotCodec.kt; PlaybackQueue.kt; MediaSession/system transport integration
- Next action: device-recovery actively fixes close-ACK and long-derived-occurrence critical review findings plus the lint error; independently review the new exact candidate before any enablement or integration

## Current state and evidence boundary

Implementation/recovery is active, **not complete**. The supplied checkpoint is device-lane HEAD `77d45be`, already merged into `main4183`. This records reported lineage, not a new Git verification or a new implementation in this documentation task.

At that checkpoint, **114 core + 344 app tests and debug/instrumentation APK builds passed**; **lint has 1 error**. Critical independent-review findings remain on **close-ACK handling** and **long-derived occurrence preservation**. The new recovery effort is actively fixing these findings; do not describe it as idle, awaiting a lane, or reviewed clean. Report fresh exact-SHA evidence after corrections rather than carrying forward historical counts as a current pass.

The production `Media3PlaybackEndpoint.supportsExactHandoff` remains **false**. Registration/exact native handoff must not be advertised or enabled merely because a coordinator, picker, export method, synthetic endpoint test or APK exists. Source contract readiness does not mean implementation acceptance.

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

Round-trip a genuinely long prepared derived queue, including duplicate tracks with different occurrence/context identities and history flags. Prove exact identity/order/context survives the supported wire/codec bounds or that unsupported input is rejected **before mutation with capability kept disabled**. Never silently truncate, regenerate IDs or refetch a saved collection to fake an exact import. Include current/next selection and history after round-trip, not just DTO equality on a short queue.

### Capability barrier

Keep `supportsExactHandoff = false` until complete exact import/export, all start-path authority fences and critical review corrections are established. Enabling capability requires an explicit reviewed candidate and validation accounting; no UI-only toggle or recovery checkpoint bypass. Incomplete native/audio/background evidence must remain explicit even if source corrections land.

## Remaining acceptance and risks

Test rapid selections with latest-intent semantics, stale/lost lease, blocked/media output, cancellation, duplicate/out-of-order directives, one-shot uncertain writes and source/destination races. No blind remote write replay. Show that read-only browsing does not register an unavailable phone or create local history. Retain usable accessible device status, 48dp actions and native Back without turning the sheet into debug controls.

Main risks are split authority between app/system controls, ACK loss during teardown, silent derived-context truncation, duplicate identity collapse, and historical test/build evidence being mistaken for native handoff proof. Backend changes are out of scope; coordinate any protocol change rather than modifying or probing the live backend.

## Evidence and completion gate

After correction, record exact candidate/base SHAs, the two critical regression results, current core/app counts, APK hashes and a clean lint result or explicit remaining blocker. Obtain independent read-only review of the **new exact SHA**; a changed candidate requires re-review. Integration follows standing assignment-bound conditional self-integration authority, separate exclusive advisory build and main-squash leases, and independent exact-SHA review per [worktree-review.md](../worktree-review.md). Meet its preconditions before acting; the merged checkpoint is not a completion certificate.

[BUG-001](BUG-001-native-validation-environment.md) is the **completion/validation gate only, never a hard source dependency**. Native AFTER-render, audio and background validation are **BLOCKED / NOT COMPLETE**. No successful native handoff/background/audio behavior is claimed. A future authorized isolated synthetic AVD journey must exercise device status, transfer/lease loss, app/system controls, lifecycle and inspected renders; genuine hardware-only limits remain explicitly unverified, not a reason to access a phone.

No further emulator startup/configuration, ADB/discovery, real phone, personal AVD, live backend/account/network or network-diagnostic attempts are authorized under the exhausted one-shot budget. This document does not grant builds, network access, device work or capability enablement.
