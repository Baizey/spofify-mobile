# FEAT-112 — Playback devices: quiet routine refresh

## Tracking

- ID: FEAT-112
- Status: blocked
- Phase: implementation
- Spec readiness: ready
- Owner: unassigned
- Branch: unassigned
- Worktree: unassigned
- Depends on: none
- Touchpoints: retained playback-device sheet geometry, known-empty state and routine verification feedback
- Next action: Source delivered in 65123ab5f14d933cb17810135f38020d0028abce; native UI/render/audio acceptance remains BLOCKED / NOT COMPLETE. Validate current retained populated/known-empty refresh without geometry/scroll/rename churn and with truthful null-view/error recovery on an isolated synthetic AVD; preserve FEAT-109 whole-row behavior, not a source implementation TODO.

## Assignment / authority
- **User/parent authorized implementation; parent lead owns Sheet/tests.** This source-analysis child remains docs-only and freezes after handoff. Retain `fix/device-refresh-jitter`, `/var/home/lsm/repos/spofify-mobile-device-refresh-jitter`.
- Landed base: `9d6be64bb8058de22fc46915c8fae274dfb3dda8`; fresh from clean main, no unfinished imports. Independent of Profile (`f8f0313c`) and Login (`da9555bf`), no dependency/batch.
- Lead/descendants: read current root AGENTS.md, AGENT_BEHAVIOUR.md, full [UX](../mobile-ux.md), [testing](../testing.md), [review](../worktree-review.md), [native](../ui-validation.md), [storage](../storage-hygiene.md), [devices](../device-playback.md), and root `.tooling/ui-testing/profile-login-assessment/ASSESSMENT.md`. Full procedures govern.

## Source findings — not rendered reproduction
- `app/src/main/java/dev/spofify/mobile/feature/devices/PlaybackDevicesSheet.kt`: in-flow progress on `connecting || verifyingConnection` above weighted non-fill list/content-driven sheet may churn geometry.
- Empty text also disappears/reappears with busy flags despite non-null view establishing known-empty content.
- `PlaybackCoordinator.kt`: verifyConnection counts queued/active checks around register/poll; heartbeat then delay(1000). Retry wraps read-only view or service register/poll. Registration sets connecting/backoff; requests bounded. Busy need not mean no usable view.
- consume/exchange/failed/read-only failure/conflict reconciliation publish view/message/recovery. Retry requires verification-needed AND not verifying; cached connected is not success.
- Unchanged header/view-only/Continue/Retry and genuine guidance should stay stable; real transitions may move geometry. Landed Row squash `efb5b755f9c342631378a02fb47da3464525b651` (included in catch-up main `4364bb9f87b75b77ef2bebbe8d5afdc3799cbc7c`) supersedes the former visible Close/Play from here and routine switching-prose requirements: retain native dismissal, whole-row selection, independent Rename/editor actions, target-row ring and typed `PROGRESS` suppression. Preserve polite-live-region auth/error/action/rename and genuine transfer/quarantine guidance; no string-based or blanket guidance filtering.
- Device-ID keys/edit memory already exist; no phantom-ID evidence. Hypothesis only: no new captures, measured “3px”/cadence, native repro or proven cause.

## Accepted contract / authorized implementation scope
- Distinguish retained populated/known-empty view from null-view first load and actual recovery, using existing view/recovery/busy observations, not connected alone.
- Same-view routine verification: no flash/bar/blank reserve, list/card/sheet viewport movement, scroll theft, rename draft/focus reset or per-second busy announcement. Keep known-empty text; null is not empty success.
- First load/recovery/error/Retry: truthful accessible progress/actions despite cached cards, no hidden errors. Retry explicit/once, unavailable pending, returns on failure, absent after success.
- Real view/identity/guidance/recovery/needs-tap changes remain visible. Preserve landed Row alignment, whole-row selection and independent Rename/editor actions, 48dp targets, narrow/font2, admission/status truth, native dismissal/Continue/Save/Cancel and exact-ID/draft callbacks; its Close/Play removal does not supersede the routine-refresh stability contract.
- Preserve stable keys, add/remove/reorder and interaction identity. Passive rendering never registers/selects/renames/retries/continues/dismisses or prepares/starts/seeks/stops playback. No autoplay.
- Scope: Sheet + focused `app/src/test/java/dev/spofify/mobile/feature/devices/PlaybackDevicesSheetTest.kt` (or adjacent test) + spec. Minimal presentation projection.
- Coordinator: only proven metadata insufficiency, coordinated narrowly observational tweak/tests. No heartbeat/lease/authority/request/registration/backoff/timer/protocol changes, app/core redesign, MainActivity/Profile/Login/priority-spec/Go edits or cleanup.

## Controlled host regressions
- Cycle same view/unchanged IDs busy on/off, short frame-visible/long delays; both flags, registered/view-only, populated/known-empty vs null-view.
- Assert no progress/busy announcement/blank strip; stable content/list/card/host viewport bounds busy/settled, lazy index/offset/visible anchors.
- Overflow/touch-scroll/release through many cycles: preserve offsets/anchors. Rename typing retains draft, selection/focus/identity, no remount.
- Repeat 320dp/font2/production typography, long/Unicode names; retain targets/alignment, selected/pending/unavailable admission, keyed add/remove.
- Cover null-view initial progress/no false empty, cached failure/message/Retry, pending Retry with progress/no duplicate dispatch, failed completion, successful recovery, auth-required/unsupported/owner-ended and needs-tap Continue. No blanket message filtering.
- Passive callbacks stay quiet; explicit actions dispatch once with exact IDs/drafts; no rendering-induced playback/registration.
- New routine-refresh assertions must fail exact base above and pass candidate; record commands/results without modifying main. **Preserve existing first-load/recovery progress assertions**: quietStates includes busy null-view and busy cached recovery, both still need progress. Adapt only with focused proof, never relax indiscriminately.
- Compose/Robolectric/native-graphics metrics or component images are host evidence, NOT native approval. No tests run for this spec.

## Implementation gates / resources
- Own full offline API37.0 compile-platform gate in topic, shared `GRADLE_USER_HOME=/var/home/lsm/repos/spofify-mobile/.tooling/gradle`:
  `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --offline --no-daemon --console=plain`.
- Storage: output+/tmp capacity/quota preflight, bounded owned scratch, verify forked JVM java.io.tmpdir (not TMPDIR alone), monitor/stop EDQUOT/ENOSPC, preserve evidence/account descendants. No cache purges/foreign cleanup.
- Follow canonical [Git coordination and BUILD/NATIVE safeguards](../worktree-review.md): normal Git locking/brief root-write communication, BUILD-only root/own-worktree builds and standalone NATIVE with exact hashed APKs/`--skip-build`. Retain finite acquisition, owner-token and no-stealing rules.
- Hold through actual descendant writer shutdown, not launcher exit; owner-match release, retain lock files. Uncertainty preserves guard/evidence for coordinated recovery; no unconditional trap.
- Commit/diff-check; independent read-only exact full candidate/base SHA review of source/tests/renders. Fix non-nits/new commits, rerun/re-review; SHA/base changes invalidate approval.
- Squash per canonical review procedure: normal Git locking, brief root-write coordination, clean main/exact reviewed base+topic, full staged/approved diff, commit/verification. Moved main: stop, merge into topic, rerun/re-review. No main fixes/conflict cleanup. Scoped main postchecks/full SHA; parent combined smoke.
- Cleanup per review policy: account deltas/processes/artifacts, preserve verified recovery/review evidence, non-forced worktree removal/expected-tip ref deletion only after completion.

## Native blocker / required gate
- Steward: sole API37 Play x86_64/emulator37.2.13/no KVM; headless software/Vulkan-off/CPU-max/RAM-runtime failed preboot; no actionable new supported probe.
- No child launches, identical retries, uncoordinated downloads/provisioning, physical/personal/live-PC/backend fallback. New probes need changed supported prerequisites/actionable evidence and bounded approval.
- Actual current-APK before/after captures: initial/populated/known-empty/delayed routine refresh/failure/Retry; scroll/rename/focus, 320dp/font2, Back/IME, music continuity. Required normal **Sign-in/Home/query-driven Search/Playlists/Now Playing smoke**.
- Record SHA/APK hashes/device/API/viewport/fonts, paths/actions/findings; implementer+independent reviewer inspect after-captures/UX checklist; parent combined smoke. Without after evidence: **UI validation BLOCKED / NOT COMPLETE**. Source approval/landing is not native completion.

## Spec-only accounting
- Creation/consolidation were spec-only: brief (<100KB), no scratch/build/test/native/captures/secrets. Checkout retained. No BUILD/NATIVE; MAIN only synchronous Git administration, matching release.
- Preflight: repo ~2.9TiB, /tmp ~26GiB free; repo inodes unavailable, quota unknown. Report base/tip/status/resources/retention; no other-lane cleanup.
