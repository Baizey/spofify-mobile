# FEAT-103 — Reliable Android headset and system media controls

## Tracking
- ID: FEAT-103
- Status: todo
- Phase: implementation
- Spec readiness: ready
- Owner: unassigned
- Branch: unassigned
- Worktree: unassigned
- Depends on: none
- Touchpoints: PlaybackService SessionCallback; CoordinatedSessionPlayer; audio focus/foreground notification; manifest only if justified; separate media-control unit and instrumentation tests; device-playback evidence
- Next action: assign lead C; audit existing Media3 paths now, wait for FEAT-101 committed service/shared-test handoff before modifying them

## Scope, dependency and starting facts

Read `_20261104-mobile-polish-handoff.md`, common docs and `docs/device-playback.md`. Playback already belongs to a real Media3 `MediaSessionService`, with `CoordinatedSessionPlayer`, trusted-controller admission, MediaStyle notification, explicit media-button callback, lifecycle registration and stale-start fences. Audit/fix this existing path; do not bolt hardware handling onto the Activity or create another player/session.

FEAT-101 edits the same service and bridge tests. C may inspect and add isolated test scaffolding first but must wait for A's frozen committed source/API handoff before service/shared-test edits. After that C runs in parallel with B, which owns Activity/search/chrome. No hard feature-done dependency: pending native acceptance is not a reason to prevent source handoff.

Current likely gaps are not yet established native bugs: `onMediaButtonEvent` explicitly maps basic media keys but returns true for unknown keys; it ignores repeated/down-up events and bypasses default delayed headset tap logic. Session/wrapper allowlists deliberately exclude all volume mutators. `MainActivity` has no explicit music-volume stream selection. Diagnose the correct integration before loosening allowlists or changing lifecycle/export rules.

## Required behavior

- Standard Android headset/earbud/system **Play**, **Pause**, **Play/Pause**, **Next**, **Previous**, and supported seek/fast-forward/rewind commands operate the same authoritative occurrence/traversal as app transport, including background/locked-screen service control when Android permits it. Previous retains established restart-versus-previous semantics; seek is bounded. Do not claim every headset supplies every key or a universal multi-click convention.
- Media Play resumes the existing prepared current occurrence without resetting position/queue/history; it is not the FEAT-101 fresh catalog-selection action. If no valid prepared item/authentication/authority exists, fail silently/safely or expose a truthful standard state; do not restore/autoplay a durable queue or fetch a new catalog context. Stop remains safe pause semantics unless separately required, not logout/Close or destructive queue clear.
- Normal hardware **volume up/down/mute** controls the Android music stream/current local output through platform audio mechanisms, preserving normal OS UI and route behavior. This is not player gain, queue transport, an app slider, remote device volume, Bluetooth pairing or a reason to request Bluetooth scanning/recording/accessibility privileges. Do not globally swallow volume events in `onMediaButtonEvent` or Activity dispatch. Holding a volume key may repeat naturally; media NEXT held/repeated must not unexpectedly skip a whole queue.
- Use platform/Media3 handling where it is correct. If custom key mapping is required for authority/stale-start safety, keep it narrow, document consumed versus delegated keys, and test action/repeat/deduplication. Key down/up must not double-trigger. Unknown keys must not be blindly converted to commands or bypass trust. Headset hook behavior must be compatible with normal Android intent delivery without leaving deferred stale Play callbacks; any supported single/double-tap policy must be documented and tested rather than assumed from KEYCODE constants.
- Transport requests from MediaController, notification and media-button Intent agree with app controls. Buffering/error/paused state maps truthfully to session metadata/commands; Next/Previous availability reflects actual queue boundaries/repeat. Volume capability exposure, if needed at all, must reflect actual local device support and be narrowly scoped; do not re-enable every raw `ForwardingPlayer` mutator because a test excludes device volume.
- Every start/transport still passes current client/session/service/coordinator/output authority checks at actual dispatch. Local hardware controls must never accidentally start local audio while a remote device owns playback. Supported remote transport follows existing coordinated routing; local system stream-volume changes do not claim to change a remote speaker. Focus loss/noisy/unplug/logout/auth expiry stop buffered local output and invalidate stale starts; later focus gain/reconnect/service recreation never auto-resumes.
- Background user media actions can use Android's genuine media-interaction allowance only through the existing fenced path. Denied foreground start/audio focus yields blocked/safe state, no broad background exemption, wake loop, deferred retry/autoplay, registration lie or bypass of remote ownership. Activity death/recreation must not be required for controls or create a second output.

## Implementation boundaries and ownership

C owns after A's handoff:
- media-controller/key dispatch/admission portions of `app/src/main/java/dev/spofify/mobile/playback/PlaybackService.kt`;
- `playback/CoordinatedSessionPlayer.kt`, justified scoped `TraversalPlayer`/audio focus/foreground notification fixes;
- `app/src/main/AndroidManifest.xml` only if actual Media3/platform requirements justify a narrowly reviewed change;
- related existing wrapper/focus/notification/service-bridge tests, preferably new `PlaybackMediaButtonsTest.kt` for independent focused cases;
- a new dedicated native media-controls instrumentation test file. B owns the existing `NativeUiSmokeTest.kt`; request fixture changes explicitly, do not race on `UiFixtureServer.kt`;
- a scoped `docs/device-playback.md` behavior/evidence update if necessary, coordinated with the separate documentation cleanup lead (do not reinsert stale blanket stops).

B owns `MainActivity.kt`. Request the standard music stream host hook there through B if the audit needs it; do not edit the Activity concurrently. Preserve A's contextual list/queue implementation, result API and regression tests. No changes to core player wire schema, queue insertion policy, UI chrome/typography, credential persistence, backend or unrelated player sheet.

Service export/receiver declarations require source/platform evidence: `android:exported=false` is currently intentional and not proof of a bug. Do not add an exported raw-command surface, weaken `trusted(controller)`, enable arbitrary URL/media-item commands, or open catalog preparation to foreign controllers to make an integration test easier. No extra Bluetooth/device permissions unless a separately approved requirement establishes them; ordinary media buttons/stream volume do not need pairing management.

## Acceptance and automated regressions

1. Table-drive actual service callback/controller paths for PLAY, PAUSE, PLAY_PAUSE, HEADSETHOOK, NEXT/PREVIOUS and SKIP variants, seek forward/back commands where supported, STOP, malformed/missing events, unknown keys, down/up/repeat and event times. Assert exact command counts and unchanged queue/current/elapsed/history when a command is rejected. Do not test only an extracted key-to-string map.
2. Exercise trusted platform/system controllers as well as same-UID UI, and deny untrusted controllers/media injection. Cover available-command publications and listener filtering; unsupported raw media/URI/prepare/speed/release/queue mutations stay blocked. If a narrow device-volume command is legitimately allowed, tests distinguish it from player gain and remote volume; preserve all other prohibitions.
3. Use real coordinated service/connection fixtures to cover active/paused/buffering/ended/error/empty, Next end/Previous restart, duplicate occurrences, shuffle/repeat and exact wire key-to-physical-index mapping. Basic media controls do not refetch a mutable playlist or replace the queue.
4. Hold media requests across pause/focus loss/noisy, delayed Media3 continuation, queue replacement, logout/same-account reauth, service replacement, coordinator/output transfer and pending foreground/focus denial. Old starts must fail closed, no fallback direct output or stale admission reuse. Current fresh authorized request remains usable after stale cancellation.
5. Validate default Android music-stream behavior/host hook without a global key handler; volume changes do not emit musical commands or alter ExoPlayer gain. Test unknown media Intent handling leaves legitimate platform behavior available without leaking execution to an untrusted source. Do not equate a mocked AudioManager with verified acoustic output.
6. Compile native tests and run the common full offline automated gate with BUILD lease, worktree-local outputs and scratch accounting. Obtain fresh independent read-only exact-SHA/base review with security-focused attention to controller admission, unknown-key delegation and asynchronous background starts.

## Native validation and honest limits

The user requested removal of the stale diagnostic stop; consume the documentation lead's revised native instructions and **assess feasibility afresh**, not an inherited permanent blocker. This spec itself records no new emulator result and grants no real-device/live-backend access. Once using the approved isolated synthetic AVD workflow, verify the exact APK hashes and explicitly selected emulator; discovery stays disabled.

On the current APK exercise app foreground/background, locked screen, notification/system MediaController and synthetic Android media key events while audio is playing and paused. Verify Play/Pause/Next/Previous, available seek controls, ordinary emulator volume behavior, route/session metadata, foreground notification, auth loss and activity recreation. Capture/inspect relevant player/system surfaces and record actual audible/progress/background evidence separately; screenshots alone do not establish sound or key delivery. Synthetic key injection should use normal Android/session dispatch rather than invoking app methods directly, but remains synthetic delivery evidence.

An emulator cannot prove physical earbud firmware multi-click mapping, actual Bluetooth AVRCP/absolute-volume negotiation, codec/route acoustic behavior or OEM power management. Report those precise uncertainties, never 'all headsets verified'. If a specific remaining issue genuinely needs hardware, coordinate exceptional verification **through the user**; do not connect to/install on phones or treat hardware as a routine prerequisite. A fresh unavailable/broken AVD or audio sink means the applicable native gate is incomplete with its actual evidence, not a substituted unit-test pass or stale blanket prohibition.

## Security, non-goals and delivery

No arbitrary audio/art URLs, off-origin auth, logged tokens/private paths, unauthenticated loaders, saved playback intent, acquisition/admin requests, backend changes, forced reconnection, device discovery/pairing UI, custom AVRCP stack, speech recording, universal codec/multi-device/volume parity promise or automatic startup resume.

Use exact reviewed candidate/base, clean-main MAIN lease and separately serialized BUILD lease per current worktree docs. Earlier user docs cleanup is committed; recheck actual root state before integration and stop on unexpected writers/dirt. Parent is orchestration-only and owns combined smoke accounting, not implementation. Retain independent reviews and minimal reproducible synthetic evidence; report scratch/output ownership, verified cleanup and unresolved artifacts. Final report separates source integration, native/system delivery, audio/background evidence and genuinely hardware-specific unverified behavior.
