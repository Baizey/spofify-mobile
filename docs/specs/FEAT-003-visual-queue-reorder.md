# FEAT-003 — Visual Up next reorder with edge autoscroll

## Tracking
- ID: FEAT-003
- Status: in-progress
- Phase: implementation
- Spec readiness: ready
- Owner: queue-reorder lead
- Branch: feat/queue-drag-autoscroll
- Worktree: /var/home/lsm/repos/spofify-mobile-worktrees/queue-reorder
- Depends on: none
- Discovery depends on: none
- Touchpoints: NativePlayer.kt extraction/import/call seam only; NativeUpcomingQueue.kt queue presentation; new queue drag state/geometry helpers; feature-specific queue tests; PlaybackControls.kt and PlaybackQueue.kt existing read-only interfaces
- Next action: gate and independently exact-SHA review extraction, land and hand API/rows to FEAT-001/002; drag awaits row handoff plus verified playback identity/conditional-move seam

## Outcome and scope

Make Up next reorder **visually track the item being dragged**, including journeys beyond the visible viewport. A changing “Move to position” label, accessibility announcement or eventual row jump is not visual reorder. The current row-height/delta target mechanism and handle alone do not fulfill this outcome.

This contract describes intended behavior, not an implemented animation or native acceptance. Scope is the service-owned upcoming traversal, not saved playlist editing, the current audible occurrence or playback history. Duplicate tracks must remain distinct queue occurrences.

## Interaction contract

- Start an intentional reorder through a dedicated handle or an explicit long-press drag region. Do not turn the whole row into a vertical-scroll trap; preserve identity tap/details, More and FEAT-001 horizontal Play next.
- During drag, render a **lifted item following the pointer**, a clear insertion target and neighboring-item reflow/placement animation. Maintain coherent row geometry, clipping and z-order in the viewport. Do not create duplicate accessible items from the visual overlay.
- Holding the pointer near the **top or bottom edge** continuously autoscrolls while the pointer stays there, enabling travel through a long queue without releasing/restarting. Both directions work beyond the initial visible rows and stop on leaving the edge region, cancellation, release or terminal boundary. Derive the insertion location from actual list geometry and scroll, not only a fixed initial row height.
- Use stable occurrence IDs for keys, drag identity and mutation. Two identical tracks can be moved independently. Recompute/reconcile the target against the current authoritative queue, never commit a stale numeric index to a different occurrence.
- Dragging is a local preview. On a valid changed drop, make **one safe reorder commit** through the verified conditional playback boundary required by barrier C; unchanged drop is a no-op. Do not send queue/network writes per pixel, crossing or autoscroll tick. Authoritative publication confirms the result; uncertainty/failure reconciles honestly rather than blindly replaying.
- Preserve the audible occurrence, elapsed position, context and history. Retain documented queue/shuffle semantics (including truthful notice if manual reorder turns shuffle off); do not prepare/restart audio to animate the list.

## Concurrent changes and cancellation

Choose and document a deterministic cancel-or-reconcile policy before implementation. Removal of the dragged occurrence, account/client/authority change, player dismissal, pointer cancellation or queue replacement must cancel safely with no reorder. Automatic traversal, other queue edits and updates during drag must either cancel or reconcile against surviving occurrence IDs/neighbor anchors. No stale ID/index may move a different duplicate or restore an old queue.

Cancel clears overlay, insertion placeholder, translations and autoscroll job without committing. Queue/progress publications must not restart the drag detector or make an item jump under the finger. Do not freeze service-owned playback just to stabilize visual state.

## Accessibility and shared gestures

Provide visible More and custom accessibility **Move up / Move down / Play next** actions, with valid boundary states and stable focus. Announce the moved item/position meaningfully and support reduced-motion preferences. Announcements are not a replacement for the visible lifted-item/insertion/reflow interaction.

Retain at least 48dp intentional targets, compact identity/credits, large-font/Unicode fallback and useful empty/loading/error presentations. FEAT-001 owns physical right-swipe Play next; a queue swipe moves the existing occurrence, never duplicates it. Reorder must not also fire that swipe or row tap. Respect Android edge Back and FEAT-002 parent scrolling/sheet ownership; autoscroll uses the active parent/list state, not a new competing nested scroller.

## Acceptance and planned evidence

1. On a queue much longer than the viewport, visibly lift a middle item, cross several neighbors and observe insertion/reflow animation in both directions. Drop once and verify exact occurrence order; unchanged drop emits no mutation.
2. Hold at bottom to travel beyond the initial viewport, then hold at top to travel back. Autoscroll continues without new pointer movement and stops cleanly at boundaries/release/cancel. Include variable measured heights at font scale 2.0.
3. Verify duplicate tracks retain exact IDs, current audio/position/history and context. Test authoritative removals, traversal, additions/reorder, authentication loss and stale publications during drag against the chosen cancel/reconcile policy.
4. Count mutations: zero during preview/cancel, one on a changed valid drop, no per-pixel network activity or blind retry. Test failure/uncertain commit and final authoritative reconciliation with synthetic fixtures.
5. Exercise vertical scroll, row details/More, horizontal Play next, Back and sheet collapse without cross-firing. Accessible move actions produce the same occurrence-safe result; reduced motion retains clear placement cues.
6. Inspect actual native before/after and interaction captures at narrow/short/landscape and font scale 2.0. Record exact SHA/APK hashes, device/API/viewport, actions, inspected paths, complete visible-row counts, target/row geometry and independent findings. Static semantics or spoken positions alone cannot prove the animation.

## Early extraction and explicit handoffs

The queue and expanded-player core currently coexist in `app/src/main/java/dev/spofify/mobile/feature/player/NativePlayer.kt`. This lane owns an **early behavior-preserving extraction** of queue header, rows and presentation into `NativeUpcomingQueue.kt`; do not combine it with drag redesign. Expose **`LazyListScope` header/items plus an optional bounded wrapper** so FEAT-002 can own **one parent lazy scroller**. Preserve occurrence keys, actions, states and tests during extraction.

Barrier A: agree the API and hand off the reviewed extraction to FEAT-002 (core/host owner) and FEAT-001 (shared-row/all-list owner); end concurrent edits to the original queue block. FEAT-002 then owns expanded core/host; this lane owns queue presentation.

Barrier B: allow FEAT-001 queue-row Play/Pause removal, reclaimed width and swipe/More wiring **after extraction but before drag work**. Accept that row API/geometry/gesture handoff before implementing lifted-item drag and autoscroll. Catalog-row work can run independently. These are explicit shared-file barriers, **not hard epic dependencies** or a reason to serialize all discovery.

## Risks, discovery and completion gate

### Grounded discovery decisions (2026-11-04)

Assignment covers discovery and implementation. Starting source `5c6cf00e42b28e1ebc74a5d892cbbf5e1916263d`; refreshed foundation `3ae4417fd0cb3dd337e310d57ec6a0c690c77c20`. `NativePlayer.kt` currently has a pinned queue header, a bounded `LazyColumn`, stable occurrence-string keys, compact More-only moves and noncompact 48dp drag handles. The current drag only changes a target label using initial row height; it neither lifts nor autoscrolls. Existing duplicate/menu/scroll/Back regressions in `NativePlayerTest` must continue passing. Audio and mutation remain behind `PlaybackControls`; `moveUpcoming(occurrenceId, upcomingIndex)` currently protects occurrence membership/current-item boundaries, but does not itself accept an expected queue/client/session/authority generation. It is not yet a verified conditional authority-fenced drop API. No service/controller/model writes belong to this lane.

- Extraction phase: preserve pinned-header wrapper behavior exactly. Export a `LazyListScope` header+items seam for FEAT-002's one parent scroller, plus the bounded wrapper. Both APIs accept the active `LazyListState`; do not manufacture a second scroller inside the parent seam. Header/empty keys must not collide with occurrence keys. Barrier A ends this lane's `NativePlayer.kt` writes; send the exact integrated SHA/API to both neighboring lanes.
- Barrier B: do not edit row drag/gestures until FEAT-001's extracted-row Play/Pause removal and right-swipe Play next are integrated. Pure state/geometry tests may proceed independently. Non-goals: saved-playlist reorder, service/controller rewrite, acquisition, new APIs/dependencies, expanded-player core redesign or remote transfer.
- Cancellation policy: cancel, never reconcile a live preview, on authoritative upcoming occurrence/order/content changes, current audible occurrence changes, client/session/authority changes, queue/player-owner dismissal/disposal, or pointer cancellation. Hoist active drag and pointer lifecycle plus one bounded lifted-item snapshot to the stable queue/parent owner; ordinary lazy source-row virtualization must not cancel a long-distance drag. Keep one accessible occurrence, never duplicate overlay actions; offscreen actions follow ordinary lazy-list accessibility while the visual snapshot remains semantically hidden. Ignore position/duration/buffering-only publications. Snapshot the authoritative occurrence sequence and identity token at activation; revalidate before drop and resolve the dragged occurrence in that same snapshot. A missing/moved duplicate cannot become a different occurrence. Drag entry barrier C: FEAT-004's playback owner must expose or transfer a verified client/session/authority token and a conditional mutation seam that validates expected identity and queue snapshot at mutation execution, not just before dispatch. Include authority loss with unchanged visible queue and a queue change between preview validation and execution. Extraction and pure geometry tests do not wait on this barrier; drag integration does. No invented backend revision or unauthorized service edit.
- Gesture/geometry: long press a dedicated at-least-48dp handle; keep row scrolling, details and right-swipe separate. Render one pointer-following lifted visual, a measured-size placeholder/insertion cue and animated neighbor placement keyed by occurrence. Clear overlay semantics so only the source row owns actions/focus. Use measured visible item bounds with all parent header indices accounted for, never fixed row pitch or timeline indices.
- Edge loop: a cancellable frame-clock coroutine runs only while dragging, checks a bounded 48dp edge band inside the active viewport, ramps to at most 720dp/s, clamps frame delta to 32ms, and calls the active parent's scroll state. Held pointers require no further move events. At a terminal edge stop requesting scroll until geometry/pointer direction changes; disposal/cancel/drop clears work. Avoid detached jobs, busy loops and unbounded measurement caches.
- Capability treatment (device-owner discovery handoff `53c140c`, final delivery pending): local output can reorder arbitrary upcoming positions. Remote output only supports one-step up/down and Play next (`toIndex = 0`) through the existing directional wire contract. Disable remote drag before activation with an accessible explanation (for example, “Drag reorder is available on this device; use Move up, Move down or Play next for remote playback”); retain those remote menu/TalkBack actions. Do not stage a misleading preview that fails only at drop, synthesize remote absolute-position requests or replay a chain of step mutations. The final owner-provided capability/authority seam must be grounded and tested at barrier C.
- Commit: local preview only; zero writes during movement/autoscroll. Exactly one occurrence-safe conditional move for a changed, still-authorized drop after barrier C; unchanged/cancel is zero. No blind replay after failed/uncertain result. Existing authoritative publication wins. Reorder truthfully retains the existing shuffle-off notice and cannot prepare/play/seek or replace history/context/current audio.
- Reduced motion: honor Compose's motion-duration scale for neighbor/lift effects; pointer tracking and a static high-contrast insertion cue remain immediate and usable when animation is disabled. More and TalkBack Move up/down/Play next retain their occurrence identities and boundary states.

### Evidence plan and material risks

Extraction: new dedicated queue-seam tests prove one shared parent scroll owner with a preceding header, stable duplicate actions, empty/error/Undo states and zero playback calls; existing player tests guard compact/full hosts. Fresh `@GraphicsMode(NATIVE)` component PNGs use `AppTypography` and are opened by implementer/reviewer, but remain DEVELOPMENT evidence only.

Drag: pure geometry/state tests cover variable heights/header indices, held-edge both directions, boundary stop, duplicate-order/drop counts and progress-only versus authority cancellation. Actual Compose touch tests must lift, cross/reflow, hold each edge without move events far enough to virtualize the original row, reverse direction without restarting, release/cancel and verify exact order/mutation count with synthetic controls. Add no-prepare/play/seek assertions and swipe/reorder exclusivity tests. Capture normal/narrow/short/font2 interaction frames and inspect glyphs, row pitch, complete visible rows, insertion/overlay clipping and 48dp controls. Unknown native authority identity exposure is a named interface risk; do not weaken cancellation silently. Current-device/API31/32 audio/background/native rendering remain unverified.

Independent discovery reviewer `subagent-87b61ae5-46ee-4610-9aac-ced3c8634acf` accepted extraction direction and raised the authority-fence and row-virtualization clarifications; both are incorporated above. Under the coordinator's combined discovery/implementation assignment, the lead accepts this bounded contract as ready, with barriers B/C enforced before drag integration. Every source slice requires gates, exact-candidate/base read-only review, correction/re-review, leased main landing and postcheck. Integrated source is recorded as such, not feature done; BUG-001 continues to block native completion.

[BUG-001](BUG-001-native-validation-environment.md) is a **completion/validation gate, never a hard source dependency**. Native AFTER-render, audio and background validation remain **BLOCKED / NOT COMPLETE**. No implementation or rendered acceptance is claimed. The exhausted diagnostic budget permits no further emulator/ADB/discovery, real phone, personal AVD or live-network attempts.
