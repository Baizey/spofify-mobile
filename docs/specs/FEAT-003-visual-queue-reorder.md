# FEAT-003 — Visual Up next reorder with edge autoscroll

## Tracking
- ID: FEAT-003
- Status: todo
- Phase: discovery
- Spec readiness: draft
- Owner: unassigned
- Branch: unassigned
- Worktree: unassigned
- Depends on: none
- Discovery depends on: none
- Touchpoints: NativePlayer.kt queue extraction only; planned NativeUpcomingQueue.kt queue presentation; NativePlayerGestures.kt; PlaybackControls.kt; PlaybackQueue.kt; NativePlayerTest.kt; PlaybackQueueTest.kt
- Next action: define and hand off a behavior-preserving queue extraction first; accept FEAT-001 queue-row changes before implementing drag and edge autoscroll

## Outcome and scope

Make Up next reorder **visually track the item being dragged**, including journeys beyond the visible viewport. A changing “Move to position” label, accessibility announcement or eventual row jump is not visual reorder. The current row-height/delta target mechanism and handle alone do not fulfill this outcome.

This draft describes intended behavior, not an implemented animation or accepted interaction. Scope is the service-owned upcoming traversal, not saved playlist editing, the current audible occurrence or playback history. Duplicate tracks must remain distinct queue occurrences.

## Interaction contract

- Start an intentional reorder through a dedicated handle or an explicit long-press drag region. Do not turn the whole row into a vertical-scroll trap; preserve identity tap/details, More and FEAT-001 horizontal Play next.
- During drag, render a **lifted item following the pointer**, a clear insertion target and neighboring-item reflow/placement animation. Maintain coherent row geometry, clipping and z-order in the viewport. Do not create duplicate accessible items from the visual overlay.
- Holding the pointer near the **top or bottom edge** continuously autoscrolls while the pointer stays there, enabling travel through a long queue without releasing/restarting. Both directions work beyond the initial visible rows and stop on leaving the edge region, cancellation, release or terminal boundary. Derive the insertion location from actual list geometry and scroll, not only a fixed initial row height.
- Use stable occurrence IDs for keys, drag identity and mutation. Two identical tracks can be moved independently. Recompute/reconcile the target against the current authoritative queue, never commit a stale numeric index to a different occurrence.
- Dragging is a local preview. On a valid changed drop, make **one safe reorder commit** through existing authority-fenced controls; unchanged drop is a no-op. Do not send queue/network writes per pixel, crossing or autoscroll tick. Authoritative publication confirms the result; uncertainty/failure reconciles honestly rather than blindly replaying.
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

Open discovery: agree the stable occurrence/revision policy, lifted-item/insertion design and shared extraction/row-gesture handoffs.

Resolve drag activation region, edge-zone/speed, overlay geometry, reduced-motion treatment and concurrent-update policy. Risks include index drift with duplicates, stale preview overwriting authoritative playback, inaccessible ghost rows, edge-scroll jobs surviving cancellation and nested-scroll conflict with the sheet.

[BUG-001](BUG-001-native-validation-environment.md) is a **completion/validation gate, never a hard source dependency**. Native AFTER-render, audio and background validation remain **BLOCKED / NOT COMPLETE**. No implementation or rendered acceptance is claimed. The exhausted diagnostic budget permits no further emulator/ADB/discovery, real phone, personal AVD or live-network attempts.
