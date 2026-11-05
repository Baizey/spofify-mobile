# FEAT-106 — Playback-device popup: aligned actions and contextual recovery

## Tracking

- ID: FEAT-106
- Status: blocked
- Phase: implementation
- Spec readiness: ready
- Owner: unassigned
- Branch: unassigned
- Worktree: unassigned
- Depends on: none
- Touchpoints: playback-device popup action alignment and contextual connection recovery
- Next action: Source delivered in 9d6be64bb8058de22fc46915c8fae274dfb3dda8; native UI/render/audio acceptance remains BLOCKED / NOT COMPLETE. Validate current successor FEAT-109 whole-row selection/native dismissal and FEAT-112 quiet refresh, including genuine recovery and narrow/font2 states; historical visible Close/Play-from-here controls below are superseded, not controls to restore or a source implementation TODO.

## One independently deliverable feature

Base: `9ac4e257db84669a6793e742bff06a428043f358` (clean actual `main`). Topic: `feat/device-popup-layout`; worktree: `/var/home/lsm/repos/spofify-mobile-device-popup`.

Deliver one scoped implementation, tests, independent exact-SHA review, guarded gate and focused squash into main. This analyzer handoff is **spec-only**: no source/build/native/integration approval. No prerequisite on list/FIFO recovery 07, mini-player swipe or shared-song-list work; do not import or wait for unfinished branches. Shared-file overlap alone is not a dependency.

### User evidence, preserved and inspected

The analyzer opened the actual supplied image, not merely its description. Original: `/tmp/Spectacle.FGBxYb/Screenshot_20261104_234508.png`. Exact retained copy in the root checkout (ignored, intentionally outside the topic worktree):

`/var/home/lsm/repos/spofify-mobile/.tooling/feature-delivery/device-popup/user-before-original.png`

Both SHA-256: `7ec70ed720da0e10f41b061bac7e9fea53c0be0fb1ce70503d844b659919b263` (64,391 bytes). Retain this copy through implementation/review; transient Spectacle paths have disappeared previously. It is **before-only evidence**, not an approved after-render.

The 401×480 image shows the dark Playback devices dialog, redundant “Choose an output. Reconnecting never starts audio automatically.” text and an unconditional Retry connection. Name/status begin around x27, Play on Phone around x40; Rename begins around x201 for Phone and x221 for Home PC. The action's Material padding and content-sized preceding label cause the reported misalignment. Keep meaningful dialog context and Close.

## Scope and inspected contracts

Primary seam: `app/src/main/java/dev/spofify/mobile/feature/devices/PlaybackDevicesSheet.kt`. Keep a small shared device-row/action layout local to this feature, with a renderable state/callback seam for tests if useful. Do not introduce a generic app-wide row framework, redesign other screens or change typography globally.

The real state owner is adjacent `PlaybackCoordinator.kt` / `PlaybackDevicesState`, not a guessed network-status API:

- `deviceId` empty means view-only; it does **not** mean the last authenticated read failed. Successful `retry()` without an active service publishes `connected=true`, empty registration ID and a validated view, without registration/heartbeat/start.
- Registered `retry()` uses the existing serialized `exchange { register() / poll() }`. Registration alone sets `connecting=true`; successful `consume()` clears it. `connected` reflects accepted connection/authority freshness, not whether music is playing or a device list exists.
- `failed()` fences output, sets disconnected, preserves uncertainty guidance; 401 calls `authLost()`, 404 clears registration, 409 can publish a validated **display-only** reconciliation (`grantAuthority=false`). Lease expiry also disconnects. A rename response after a conflict must not restore authority.
- Current read-only requests do not expose in-flight state; a failed read only changes `message` and may leave an older `connected=true` view. Thus `!connected && !connecting` alone is insufficient for all required cases. `message.isNotEmpty()`, `deviceId.isEmpty()`, device count, `needsTap`, musical `view.state.error`, and raw `stateAgeMs` alone are not recovery predicates. Do not invent an age threshold.
- `consume()` puts the redundant intro in `message` when no output is active; other messages communicate transfer/quarantine/uncertain actions. The sheet's current view-only status replacement can hide actionable messages. Preserve meaningful guidance explicitly rather than hiding all messages or matching arbitrary prose substrings.

Allowed supporting coordinator changes are **observational presentation metadata only** where the existing fields cannot distinguish initial/in-flight, healthy read-only, stale/failed verification and authentication-required states. Use a minimal typed recovery reason/in-flight projection (or equivalent small explicit model), set at existing request/lifecycle outcomes and fenced against obsolete completions. Do not change the meaning of existing authority fields just to simplify UI. No new requests, timing/backoff, transport mutex policy, registration, replay, transfer, persistence or endpoint behavior.

## Required UI and unchanged behavior

1. **Canonical start edge.** Device name/title, This device/availability/status text and the visible playback action's **text content** share one horizontal start edge in each card. Account for Material TextButton content padding/alignment, not merely matching outer button bounds. Retain an explicit minimum 48dp interactive target, adequate separation and ripple/focus surface even with reduced start content padding.
2. **Rename fixed at the end.** Reserve a right/end-anchored slot independent of device-name/action length; Rename is one line, never squeezed, wrapped or displaced by a long title. Use weighted, bounded title/status content, deliberate title wrapping/ellipsis and full accessible identity, not an unbounded Row. All cards at the same viewport/font scale have the same Rename text end edge and reserved trailing geometry.
3. **Responsive, not tiny.** Prove 320dp width at font scale 2 with production JetBrains Mono and long Unicode/emoji names. Two single-line action labels need not be forced into one impossible-width row: at insufficient width move the full-width, start-aligned playback action to a separate line, keeping Rename in its stable end-anchored header/trailing slot and title content bounded. Choose layout from available width/font, not a particular device-name length, so cards remain consistent. Preserve legible scaled text and 48dp targets; do not shrink fonts, clip/truncate either action label, overlap targets or enable horizontal scrolling to conceal overflow. Status can wrap when necessary.
4. **Exact visible copy:** `Play from here` on every eligible device action, with no device-name suffix or alternate visible wording. Keep device-specific accessible context (e.g. “Play from here on Home PC”), so equal visible labels remain distinguishable without duplicate/conflicting semantics. Give Rename device context as well; do not expose opaque IDs/private paths. Preserve disabled/selected/active semantics and TalkBack traversal.
5. **Same intent/admission:** that action calls `coordinator.select(device.deviceId)` exactly once, not local Play/Resume/Continue. Preserve its exact current enablement: available, registered, not selected, and `!active || selectedDeviceId.isNotEmpty()`. Preserve choice responsiveness during pending transfer/quarantine and selected/unconfirmed state; UI cannot fabricate trusted active/playing output. `Continue playback here` remains the separate needs-tap recovery, with its current callback/admission.
6. **Remove only redundant introduction.** Suppress the generic “Choose an output / Reconnecting never starts audio automatically” introduction on a normal picker, including an otherwise healthy connection with no active output. Keep Playback devices/Close, meaningful view-only capability information, loading/empty/error states, This device, Available/Unavailable, Selecting/Selected, Needs a tap, Playing and Active output · Paused. Keep transfer/quarantine, authentication, service-unavailable, safety, uncertain-write/no-replay and rename validation/failure guidance, including after a failed read with retained cards. Do not remove all of `state.message` or all view-only guidance as “prose.” Only necessary state guidance should consume space.
7. Rename editor, Save/Cancel, trim and 1–64-character validation, accepted-name persistence and stale-session/uncertain-write safeguards remain unchanged. No optimistic confirmed name, duplicate pending write or blind retry. Preserve existing view-only action restrictions. Keep Close/system Back, sheet scrolling and gesture/safe-drawing/IME insets usable; dismissing is presentation only.

### Retry visibility matrix (explicit recovery, not a permanent command)

`Retry connection` keeps **the existing `coordinator.retry()` callback**, one invocation per deliberate click; never redirect it to a playback command.

| Real state | Retry connection |
| --- | --- |
| Healthy registered connection, including empty/no-active-output, paused/playing, active/unavailable cards | Absent from rendering and accessibility tree |
| Successful read-only authenticated view, despite empty `deviceId` | Absent; retain truthful view-only capability information |
| Initial verification/registration or an ordinary in-progress verification/recovery request | Absent while the relevant connection request is in progress; show bounded existing loading feedback, without a duplicate retry affordance |
| Actual failed registration/poll/read, recoverable disconnected service/permission expiry, 404 lost registration, or 409 display-only reconciliation awaiting authoritative verification | Present when no relevant connection attempt is in flight and existing retry can usefully verify/reconnect; keep the actual safety/error guidance |
| A failed read with retained formerly healthy cards, or uncertain rename requiring device re-verification | Present for that explicit verification-needed condition; do not mistake cached `connected=true`/nonempty devices for current health |
| Known authentication-required, closed/ended owner scope or unsupported service capability which retry cannot repair | Do not offer a knowingly ineffective connection retry; retain sign-in/service/capability guidance and existing recovery path |
| Only musical blocked/media error, needs-tap, ordinary pending transfer/quarantine, or rename input validation while connection is healthy | Absent; preserve the appropriate existing action/warning, not generic network recovery |

Track request completion/cancellation so recovery returns after failure and disappears on successful relevant verification; busy cannot remain stuck. A background healthy heartbeat must not make Retry flash into existence. Existing periodic service reconciliation/backoff remains untouched: add **no automatic retries or autoplay**, no UI timer/poll loop and no new registration on sheet open. Preserve the existing single initial authenticated view read when `view == null`; do not multiply it through recomposition. Recovery state never grants authority or replays an uncertain write. For combined states, auth/capability restrictions and actual connection in-flight status take precedence over showing Retry; a genuinely failed connection takes precedence over merely having cards or a musical error.

## Tests and rendered acceptance

Add focused Compose/Robolectric presentation tests and coordinator-state regressions only where observational metadata is added; use synthetic protocols/fixtures. Relevant existing coverage: `feature/devices/PlaybackCoordinatorTest.kt`, `PlaybackDeviceIdentityTest.kt`, `playback/AppGraphPlaybackParticipationTest.kt`, `androidTest/.../PlaybackDeviceFixtureTest.kt`, `NativeUiSmokeTest.kt` and `UiFixtureServer`.

- Healthy registered/read-only, no-active-output and empty states: no redundant intro and no Retry node. Loading and ordinary connection-in-progress: progress remains, Retry absent. Populated active, selected, unavailable, needs-tap and transfer/quarantine statuses remain truthful.
- Exercise failed initial read, failed retained-view read, registration/poll failure, lease expiry, lost registration, conflict display-only, auth-required and recovery-success transitions. Verify the matrix and no stuck busy state, late-client/lifecycle publication or UI-induced extra protocol call. One deliberate Retry invokes the unchanged callback once. A musical error/invalid rename alone never qualifies as connection failure.
- Geometry assertions use **text/layout content bounds**, not only button semantics rectangles: equal name/status/playback-label left edges and constant Rename end edge across short/long names, enabled/disabled and pending states (allow at most 1dp rounding). At 320dp/font2 and normal phone size, long Unicode/emoji name/status content must not move/wrap Rename, and `Play from here` must be complete on one line. Measure all action targets at least 48×48dp; assert no target overlap. Verify device-specific accessible names for equal visible labels.
- Selection calls the same target once with the same enablement and no musical/connection side effects on open/dismiss/recompose. Test rename edit, Cancel (no write), valid Save (one existing write), invalid input, pending/error/unconfirmed responses and account replacement; preserve no false-success/blind-replay protections. Update the smoke test's old `hasText("Play on ", substring = true)` matcher to the exact new label so its read-only restriction assertion cannot silently miss the action.
- Run and inspect actual isolated production-sheet renders: healthy short/long cards, loading, empty/error/retained-view failure and recovery, active/unavailable, rename editor/error with IME, narrow 320dp and font2. Exercise clicks, Close, system Back (IME first), scroll/last-row reachability and insets. Implementer and independent reviewer must open affected screenshots and compare with the preserved before-image. Record equal start edges, stable Rename, complete labels, targets and meaningful state guidance.
- Run the required Sign-in/Home/query-driven Search/Playlists/Now Playing smoke and applicable synthetic device journey; preserve selection/transfer/ownership safety without claiming a new handoff capability. Answer `mobile-ux.md` rendered-review questions with relevant measurements (device-card count/geometry, chrome and IME budget, long text/targets); identify unaffected music-row questions as such rather than claiming untested passes.

## Delivery, resource fences and honest gates

Lead and every descendant must read root `AGENTS.md`, `AGENT_BEHAVIOUR.md` and full `docs/mobile-ux.md`, `worktree-review.md`, `testing.md`, `ui-validation.md`, `storage-hygiene.md`; `docs/device-playback.md` governs unchanged output contracts. Preserve exact-origin authenticated API/art/audio/range clients, signed-in/account fences, Keystore encryption, no plaintext credentials/logs/private paths, backup exclusions and no image disk caching. No backend changes or writes in the live sibling repository.

Before any future shared effect, coordinate actual writers and use **both** an atomic `mkdir` owner-token guard under the absolute Git common directory and the matching persistent flock, continuously held: `spofify-build.guard` + `spofify-gradle.lock`, `spofify-main.guard` + `spofify-main.lock`, `spofify-native.guard` + `spofify-native.lock`. Flocks alone do not coordinate all Bash mount views. Record unique owner token/process/worktree and expected base/candidate; finite acquisition, stop on unknown/stale ownership and never steal/delete another owner's guard or replace lock files. Release only one's own verified token after children stop. Own-worktree Gradle holds BUILD only; root validation acquires MAIN then BUILD, never BUILD→MAIN. Native's build phase is separately leased; run native with `--skip-build` and its NATIVE reservation/explicit isolated synthetic AVD/ports.

Run the complete offline gate from this worktree under BUILD using the shared absolute Gradle cache: `:core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` (`--offline --no-daemon --console=plain`, API37 compile platform). Preflight bytes/inodes and visible quota on repo and `/tmp`; document uncertainty. Bound owned scratch/log/capture budget, set and verify forked test-JVM `java.io.tmpdir` (TMPDIR alone is insufficient), stop on EDQUOT/ENOSPC, retain necessary evidence and clean only verified owned disposable outputs after processes stop. Report retained paths, cleanup and unresolved resource limits.

Known current native blocker: isolated API37 x86_64/software emulator 37.2.13 segfaulted before boot, with `/dev/kvm` unavailable. No identical repeat is required; this is not a universal emulator ban. A genuinely different supported isolated setup may be used under policy; never physical/personal devices, live accounts/backends or device discovery. Absent a successful current-APK isolated run **and inspected after screenshots**, report **native UI BLOCKED / NOT COMPLETE**. Before-only evidence, component captures, compilation and source/unit review do not waive that gate.

Freeze the implementation candidate, run `git diff --check`, obtain independent read-only exact-SHA/base source and relevant render review, fix/recheck/re-review non-nits, then use the MAIN transaction fences for this feature alone. If main moved, merge current main normally into this topic (no rebase), rerun gates/review; genuine cross-scope conflicts go to a separate resolver. Never force-clean/reset/stash someone else's work or discard original recovery refs/worktrees. No push/deployment or final integration batch. Combined smoke supplements, not replaces, each feature's checks. After authorized landing, verify all deltas/evidence accounted before non-forced cleanup of this feature only. A reviewed source landing with blocked native gates is not rendered feature completion.
