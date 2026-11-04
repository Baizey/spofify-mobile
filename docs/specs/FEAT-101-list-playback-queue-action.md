# FEAT-101 — Remove list Play buttons and make right-swipe playback contextual

## Tracking
- ID: FEAT-101
- Status: todo
- Phase: implementation
- Spec readiness: ready
- Owner: unassigned
- Branch: unassigned
- Worktree: unassigned
- Depends on: none
- Touchpoints: MusicRow; playlist rows/actions; NativeUpcomingQueue; MainActivity action wiring; LibraryViewModel playback actions; PlaybackControls/Connection/Service/Queue; scoped tests
- Next action: assign lead A in a dedicated worktree; establish source/file handoff to FEAT-102 and FEAT-103 before shared edits

## Assignment and prerequisites

Read `_20261104-mobile-polish-handoff.md` and its common delivery/security/validation requirements. This spec supersedes the old deleted Play-next-only/no-start-on-empty swipe contract; do not resurrect that specification. This task is service and UI behavior together because insertion policy, occurrence selection and truthful asynchronous feedback cannot safely be fixed independently.

## User outcome and behavioral contract

1. Remove repeated inline Play/Pause buttons from media list rows, reclaiming their full width, with no invisible replacement slot. Cover shared `MusicRow` on Home/Recent, Search/progressive results, artist/album/detail children and Liked songs; bespoke manual/derived playlist rows; normal/compact Up next. Remove collection-row inline Play too where `MusicRow` renders it; intentional collection/detail-header Play/Shuffle and mini/expanded transport remain. Audit read-only preview/order-editor rows without adding playback to unsaved drafts.
2. Ordinary catalog/playlist row identity still opens details. More and TalkBack expose deliberate **Play now** and the contextual swipe-equivalent action. Existing queue rows may retain their occurrence-selecting main tap and expose Play now through More/accessibility. Preserve all rich ID-backed credits and personal actions. Do not replace removed icons with inline text buttons.
3. A committed **physical rightward** swipe is an explicit user music action:
   - **Idle/non-playing:** if the authoritative output has no active playback intent (empty, paused, ended, or failed), start the selected playable song/occurrence via normal authority-fenced Play. In particular a paused queue is not silently treated as actively playing: start the user's selected item, not some other paused current item. An Up next selection starts that exact existing occurrence, not a duplicate. Preserve normal prepared context/selection semantics and honest media/start failures.
   - **Playing or actively buffering toward playback:** do not interrupt/restart/resume/seek. Append the requested occurrence **after all pending manually queued occurrences, before the remaining contextual tail**. Repeated requests are FIFO, not last-in-first-out 'queued next', and not append-to-entire-album. No recommendation/autoplay engine is added; 'tail' means existing context/future automatically supplied content if present.
   - Unknown/unavailable ownership/state cannot authorize a start or fabricated success. Resolve/recheck service/coordinator state at serialized mutation, not a stale UI `isPlaying` flag. Once accepted, no delayed work may turn an invalidated intent into fresh Play.
4. Example: current A, manual M1/M2, context C1/C2; swipe X then Y while active yields A → M1 → M2 → X → Y → C1 → C2. If no manual entries remain, insert before C1. Do not change played history, current occurrence/elapsed, context metadata, shuffle/repeat or queued identity merely to implement insertion. Explicit **Play next** may remain in More as a separately labeled immediate insertion; it must not be conflated with swipe. Explicit **Add to queue** is non-starting, including empty/paused state, and uses the manual FIFO tier.
5. For an Up next swipe while active, move/promote **that existing occurrence** to the pending manual tier's end (no second copy). Already at its correct destination is a no-op. Removing/reordering/playing occurrences must keep manual bookkeeping valid. Catalog duplicates requested intentionally remain distinct; duplicate track IDs never substitute for occurrence IDs. Manual Play next additions count as manual requests; explicitly moving a contextual occurrence to Play next promotes it. Other user reorder operations must have a documented deterministic tier policy, without unexpectedly undoing their displayed order.
6. Reveal/accessible label is contextual: **Play now** versus **Add to queue** (or equivalent concise accurate wording); no unconditional 'Play next'. Keep current/buffering/paused state visible/accessibly described without per-row transport. Commit feedback is based on actual result: Playing, Added to queue, queue limited/full, unavailable, blocked, stale/cancelled, unsupported remote operation. Do not announce 'Playing' merely because Play was requested, or 'Added' before service/remote acceptance. Stale completions must not publish into another account/route. A no-op must not claim an insertion.

## Queue API, occurrence and protocol safety

- Introduce a small explicit contextual action/result boundary as needed, rather than overloading `playNext: Boolean` to hide three meanings. The authoritative output owns the state decision, manual tier and mutation; UI provides explicit intent plus selected identity/prepared authority. Preserve source-compatible defaults only where they fail closed, never legacy fallback that changes semantics.
- Track manual intent by **occurrence identity**, not track ID, current context equality, arbitrary numeric `order` ranges or row indices. Keep capacity 1,000 and combined 2MiB publication preflight before mutation. Partial acceptance must report the actual accepted/limited result. No destructive retirement of current/upcoming to make room.
- `PlaybackTrack`/`PlaybackSnapshot` v1 has no manual-origin field; `enqueue(first)` expresses only immediate-next versus total-tail. Local transient manual bookkeeping is allowed but must reset/prune on replacement, current progression, remove/undo/clear, logout/service recreation and authority loss. Test shuffle toggles and reorder explicitly; do not silently reshuffle manual FIFO behind contextual content.
- Exact imported/handoff snapshots preserve their supplied traversal. They do not convey manual provenance. **Do not guess** provenance or alter strict wire schema, overload another field or claim cross-device preservation. At a new provenance-unknown import, document the conservative local baseline (imported future treated as contextual; newly accepted native manual requests are tracked). If the product requires recovering preexisting remote manual tiers, escalate that unsupported contract rather than pretending it is implemented.
- Remote active output must never cause local playback as a fallback. Use only shipped commands if the exact contextual operation is representable with authoritative guarantees. Otherwise reject this new action with concise truthful feedback and preserve remote state; retain existing supported explicit remote controls. Do not issue a chain of move/next commands to simulate an atomic append. Record this precise limitation; no claim of universal remote parity. Backend protocol changes require separate coordination and are outside this task.
- Generic `enqueueFrom` currently strips `entryId` and prematurely announces success. Manual playlist selections preserve entry ID/source context. Derived rows resolve the exact rendered revision/fence/selection token through existing approved preparation, select the exact returned occurrence, and enqueue only that occurrence, not the complete collection. Never place a derived occurrence ID into the bounded manual-entry field or replay a stale selection. Tokens remain transient and out of logs/snapshots/extras.
- Preserve existing `PlaybackInteractionKey`/request-generation/client/session/output fences. Capture gesture binding at pointer-down and revalidate at commit and actual dispatch, including benign progress versus true identity changes. No screen-owned player or direct UI remote mutation.

## Gesture and accessibility contract

Retain vertical scrolling, system edge Back, long press/More, dedicated queue drag handles and sheet gestures. Physical right is independent of RTL. Use platform touch slop/direction lock; retain current 24dp physical-edge exclusion and 72dp/120dp commit/translation values unless actual interaction evidence warrants a documented change. Require observed final release, at most one commit. Subthreshold, reversal after lock, cancellation, pointer loss, second touch, consumed gestures, row recycling/replacement, navigation, auth/output/queue binding loss cancel/reset with zero mutation. Do not commit simply because the event loop ended. Feedback may change while held, but state changes must never retarget the action to another occurrence.

More buttons/handles and menu actions remain at least 48dp; row pitch stays compact, two-line, cardinality-independent and font-scale-aware. Full credits remain available intentionally and via accessibility. Unavailable/draft/stale/busy rows must not advertise executable playback they cannot honor.

## Ownership and file handoff

First writer for:
- `app/src/main/java/dev/spofify/mobile/feature/library/MusicRow.kt`;
- playlist row/playback/action parts of `feature/playlists/PlaylistsRoute.kt` and `PlaylistOccurrenceActions.kt`;
- `feature/player/NativeUpcomingQueue.kt` (both variants; preserve existing drag/Undo);
- `ui/MusicActionsContent.kt`;
- playback-action wiring only in `MainActivity.kt`, playback methods only in `LibraryViewModel.kt`, callback plumbing only in `SearchRoute.kt`/`LibraryHome.kt`;
- `playback/PlaybackControls.kt`, `PlaybackConnection.kt`, `PlaybackQueue.kt`, `PlaybackService.kt`, and only needed codec/coordinator helpers. Strict core protocol/schema is not assigned for speculative changes.

Own associated row/action/queue/service bridge/ViewModel tests and scoped new tests; coordinate any existing instrumentation smoke/fixture edits. No player sheet redesign, typography/nav/search-reset work, Android media-button overhaul or doc cleanup. Before B/C edits, commit/freeze shared files and give exact API/base/candidate/tests to B (host/search/playlist files) and C (service/shared bridge tests). New media-control tests may be separate files to minimize contention.

## Acceptance/tests

- Inventory all affected list variants; semantics/source regression finds no inline list transport or reserved 48dp playback gap. Row tap, More/Play now and unavailable/current/buffering feedback remain correct. Screenshots later verify actual reclaimed title width and uniform rows.
- Pure planner and real service/connection regressions cover FIFO example, no-manual tail, full/limited queues and oversized combined publication; active, buffering, paused, ended, error, truly empty, disconnected and remote states. Assert output commands, exact realized order, occurrence identity/current position/history and typed feedback, not only callback invocation.
- Cover duplicate tracks/manual entries, derived selection outside visible page, unavailable/stale selection, multiple sequential actions, queue removal/Undo/clear/reorder, repeat/shuffle/progression, replacement/import/provenance reset and silent process recreation. Enqueue never starts; this explicit idle swipe may start; restoration never does.
- Deterministic gesture tests cover once-only release, wrong direction/RTL, reversal, pointer loss/cancel/multitouch, vertical/edge/handle arbitration, full-item replacement and session/authority transition during preparation/queued dispatch. Include A→B→A identity changes and harmless progress updates.
- Assert rejected/stale/limited/remote failure does not emit false Added/Playing. Exercise actual callback/bridge/coordinator result delivery, not a fake synchronous success contract.
- Add/compile synthetic native journeys for Search/Home/Likes/manual+derived playlist/detail and both Up next presentations. After fresh native feasibility assessment under updated docs, inspect actual current-APK screenshots and exercise swipe, More/TalkBack alternatives, Back/scroll/drag and playback continuity at narrow/default/font2. Record limitations exactly; automated checks do not prove native audio/gesture/render behavior.

## Security, non-goals and completion

All API/art/audio/ranges use the existing exact-origin authenticated client; no credentials/URLs/private paths in diagnostics, no admin/acquisition/download API, no durable queue/token/gesture persistence, no implicit write retry. No backend edits, fake autoplay/recommendations, queue handoff protocol redesign, player sheet expansion work or global stylesheet rewrite.

Use common leased offline automated gate, exact-SHA independent review, clean-main fences and storage cleanup handoff. Consume the documentation lead's stop-removal guidance and assess native feasibility afresh; do not retain the stale blanket stop or attempt devices in this analyzer turn. Mark done only after actual applicable integrated/post-check/native evidence; otherwise identify source-ready versus incomplete acceptance honestly.
