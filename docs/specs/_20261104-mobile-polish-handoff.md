# 2026-11-04 mobile polish — scoped lead handoff

This is a new assignment handoff, not a restoration of the user-deleted feature board, template or historical specifications. Parent is orchestration-only; implementation/review belongs to dedicated worktree leads.

## Baseline and authority

- Inspected baseline: `bb3613ccb9322516b7b255d007dc5572dd02fcb4` (`cleanup`), root `/var/home/lsm/repos/spofify-mobile`, branch `main`. The user's instruction edits and old-spec removals are committed; the earlier user-dirty-main integration blocker is **resolved**, not permission to disregard future dirt.
- New paths owned by this analyzer: this handoff and FEAT-101/102/103 below only. Do not restore `docs/specs/README.md`, `_template.md`, FEAT-000–004 or BUG-001. Do not edit `AGENTS.md` or `AGENT_BEHAVIOUR.md` for these features.
- The user has explicitly requested removal of the stale blanket emulator diagnostic stop. A separate documentation lead (`subagent-a09a1f25-d347-43e3-98d5-4c6c63c82f60`) owns that audit in its own worktree. These specs do **not** preserve the old stop as a current blocker. Leads consume the revised guidance and assess native feasibility afresh; this analyzer runs no emulator/ADB/backend work. Historical failure is not a fresh diagnosis.
- Keep isolated synthetic AVD-only validation, no physical phones/personal AVDs/live accounts/live backend, no wireless/USB discovery, explicit emulator targeting, authenticated exact-origin media, leased offline builds and evidence rules. If a fresh authorized native assessment fails, record that actual reason and incomplete gates; do not call compilation or Robolectric a native pass.
- Dedicated worktrees/topic branches, independent read-only exact-candidate/base review, and continuously leased clean-main integration remain mandatory under `docs/worktree-review.md`. Assignment is not a dirty-main waiver. Unknown writers, dirty destination/index, moved base or out-of-scope paths stop integration; never commit/stash/reset user work. No push/deployment/history rewriting.

## Tasks and delegation order

| Lead | Absolute spec | Ownership / order |
| --- | --- | --- |
| A: list and queue action | `/var/home/lsm/repos/spofify-mobile/docs/specs/FEAT-101-list-playback-queue-action.md` | First shared-file writer: list primitives, playlist occurrence actions, queue API/planner/service and action feedback. |
| B: compact shell and search | `/var/home/lsm/repos/spofify-mobile/docs/specs/FEAT-102-compact-shell-search-type.md` | Can investigate/new isolated components and typography now; takes shared Activity/ViewModel/SearchRoute/PlaylistsRoute files only after A's committed source handoff. Owns those files afterward. |
| C: Android media controls | `/var/home/lsm/repos/spofify-mobile/docs/specs/FEAT-103-android-media-buttons.md` | Can audit/new independent tests now; takes PlaybackService and its shared bridge tests only after A's committed source handoff. Then runs in parallel with B. |

These are **source/file handoff dependencies**, not a requirement to mark A natively done before B/C start. All three have `Depends on: none` in tool metadata intentionally: native acceptance can remain pending while reviewed source is handed off. No metadata lock or automatic assignment. Prefer A's reviewed integration first; if it cannot land yet, explicitly share/merge the exact reviewed A candidate in successor worktrees, record ancestry/accounting, and re-review against the actual integration base. Never independently replay A's patch twice.

A must freeze and hand off exact candidate/base SHAs, interface changes and new tests before B/C edit shared files. B/C must not edit A's remaining files without a new explicit transfer. B and C have no expected mutual source overlap: B owns MainActivity including optional standard `STREAM_MUSIC` volume routing; C requests that tiny host hook through B, not a parallel Activity edit. B owns the existing `NativeUiSmokeTest`; C adds a separate media-controls instrumentation file. Both coordinate synthetic fixture changes rather than racing on `UiFixtureServer`.

## Observed evidence and source findings

- Requested `/tmp/Spectacle.dDGoNk/Screenshot_20261104_195740.png` was absent at inspection (read returned ENOENT); the first `195433` image was also reported missing. Do not claim either was inspected.
- Actually inspected `/tmp/Spectacle.dDGoNk/Screenshot_20261104_200408.png`: cropped dark music list, five visible/partly visible rows, narrow ellipsized titles/credits, conspicuous repeated Play triangles and More. This crop does not show bottom Search/navigation; their appearance is user-reported, while source independently confirms default Material navigation height, 56dp-class outlined extraLarge field, dock padding and tonal elevation. No after-render exists for this assignment. User screenshot files are not analyzer-owned scratch.
- `MusicRow` currently reserves two 48dp trailing actions and swipes generic enqueue. `PlaylistTrackRow` and both upcoming-row variants also expose inline playback. `LibraryViewModel.prepare` announces enqueue before asynchronous service/coordinator success. Generic enqueue strips manual entry identity. These need service+UI ownership together.
- `PlaybackService.enqueueWire(first=false)` currently appends to the entire queue; `first=true` inserts immediately next. There is no explicit manual-versus-contextual marker in `PlaybackTrack`/`PlaybackSnapshot` v1. Do not pretend a boolean swap supplies a manual-priority FIFO or invent wire fields.
- Search activation currently calls `switchTab`, whose same-tab early return prevents reselect. Search has both host and holder detail stacks, plus local IME composing text; reset/focus must cover all three, not just a public query string.
- MediaSessionService, trusted-controller checks, coordinated transport, explicit media key handling and stale-start fences already exist. This is a compatibility audit/fix with regressions, not a second playback stack. Current media callback consumes unknown keys; tests intentionally exclude raw volume mutators. System stream volume is not ExoPlayer gain or remote-device transport.

## Common delivery and validation contract

Each lead reads current `AGENTS.md`, `AGENT_BEHAVIOUR.md`, `docs/mobile-ux.md`, `docs/testing.md`, `docs/ui-validation.md`, `docs/worktree-review.md`, `docs/storage-hygiene.md`, its spec, this handoff, and relevant playback/API docs. Where older documents conflict with current code, use the inspected source contract and flag the discrepancy rather than making unrelated doc edits.

Use the full offline automated gate in `docs/testing.md` under the separate exclusive shared-cache BUILD lease, worktree-local outputs, API 37.0 compile platform. Check `git diff --check`; independently review exact committed SHA/base and relevant actual renders. Native current-APK validation must cover narrow/default and font2 layouts, long/Unicode names, keyboard/Back/scroll, empty/loading/error states and playback continuity. Record APK hashes/revision, device/API/viewport/font scale, screenshots actually opened, complete visible-row counts, row uniformity, IME/chrome budget, actions, findings and remaining limitations. Parent coordinates the combined post-integration Sign-in/Home/Search/Playlists/Now Playing native smoke pass; landing alone is not feature completion.

Before any artifact-heavy run, preflight bytes/inodes/quota uncertainty, choose bounded owned scratch and verify forked Java temp configuration. Every descendant must read storage policy. Handoff lists generated paths/owners, cleanup after processes stop, approximate recovered space, retained evidence and unresolved artifacts. No blind cache/tmp cleanup, no forced worktree cleanup. Keep unfinished refs/evidence until accounted.

Analyzer validation is documentation/source inspection plus spec metadata/whitespace checks only. No application implementation, build, emulator, ADB or backend execution is included.
