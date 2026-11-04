# FEAT-001 — Track-row Play next swipe

## Tracking
- ID: FEAT-001
- Status: in-progress
- Phase: implementation
- Spec readiness: ready
- Owner: track-list interaction lead
- Branch: feat/track-swipe-next
- Worktree: /var/home/lsm/repos/spofify-mobile-worktrees/track-swipes
- Depends on: none
- Discovery depends on: none
- Touchpoints: MusicRow.kt; LibraryHome.kt; SearchRoute.kt; MainActivity.kt; LibraryViewModel.kt; NativePlayerGestures.kt; planned NativeUpcomingQueue.kt; PlaybackControls.kt; PlaybackQueue.kt
- Next action: implement shared catalog rows and tests; obtain occurrence/context-safe enqueue API from FEAT-004 owner and extracted queue-row handoff from FEAT-003 before playlist/Up next integration

## Outcome and scope

Give titles back the width currently occupied by inline Play/Pause and make a physical rightward swipe mean **Play next** consistently. This covers **all track rows**: Search, Home/Recent, Up next (including compact/disclosed variants), playlists, album/artist/track details and any other track-list surface. Audit custom rows as well as shared `MusicRow` callers. Mini-player and expanded transport Play/Pause remain deliberate controls; collection-level playback is not removed by this contract.

This contract is accepted for implementation under the coordinator's discovery-and-implementation assignment; it is not evidence of implemented or completed behavior. It specifically supersedes the requirement to reserve an inline Play/Pause slot on track rows in older row guidance. More and any necessary reorder handle keep their real targets; the deleted playback slot must not survive as invisible padding, spacer or reserved width.

## Behavior contract

- A committed **physical right swipe** invokes Play next, including in RTL layouts; it does not mean append or remove. Give appropriate visible feedback and name the action “Play next,” not the existing generic “Add to queue.”
- Catalog/Recent/detail rows insert a new playable queue occurrence immediately next in actual traversal. Preserve known source context, including derived-collection occurrence identity. Do not confuse catalog track IDs with existing queue-occurrence IDs.
- An Up next swipe moves **that existing occurrence** to next. It never enqueues a second copy of the track. Already-next is a safe no-op; equal track IDs elsewhere remain separate occurrences.
- Enqueue/move never autoplays, including an empty queue, paused output, startup/restored state or reconnect. Preserve the current audible occurrence, elapsed position, history and session/authority fencing. An empty queue may gain a pending next occurrence, but no prepare/start is authorized by the gesture.
- Tapping the identity row opens intentional details, not immediate playback. Any exception requires a documented surface-specific reason and review; preserving an old accidental Play tap is not sufficient.
- Provide Play next through visible More and a TalkBack custom action. Retain intentional **Play now** through More/accessibility; playback must never become gesture-only. Unavailable actions are truthfully disabled/explained, not silently treated as successful.
- Keep compact two-line identity/inline credits, uniform row pitch at each font scale and full rich-ID navigation through details/More. Retain JetBrains Mono and Android Unicode/emoji fallback. Every interactive target remains at least 48dp.

## Gesture arbitration and safety

Direction locking and touch slop must let ordinary vertical scrolling win. Do not intercept edge Back, seek, a dedicated drag handle, long-press reorder or a parent sheet gesture. A left swipe must not accidentally invoke right-swipe Play next; any retained remove action is separate, documented and occurrence-aware.

Commit at most once per completed qualifying gesture. Below-threshold release, reversal/cancellation, multi-touch, pointer loss, navigation, item replacement and lost session/authority do not mutate the queue. Clear transient translation/feedback on cancellation. Bind callbacks to stable row identity and current authenticated generation, not a stale index/client captured at pointer-down. Mutation follows the service-owned queue path, never a second screen-owned player or direct remote write.

## Acceptance and planned evidence

1. Enumerate every track-row surface and show that none retains inline Play/Pause or its invisible slot. Compare title width and complete visible-row counts before/after at the same viewport/font scale.
2. Cover playing, paused and empty queues: a right swipe and More/custom-action Play next have identical insertion/move semantics and issue no autoplay. Verify duplicate tracks, already-next and unavailable items.
3. Cover a long derived context and duplicate playlist entries: preserve occurrence/context IDs, the current audible item and history, not just visible track order.
4. Verify one mutation per release; cancellation/vertical scroll/edge Back/drag arbitration produces zero. Account change, concurrent queue update and row recycling cannot target another occurrence.
5. Native inspection must cover Search/Recent/playlist/detail and normal/compact Up next, loading/error/refresh states, narrow/short/landscape and font scale 2.0 with long Unicode credits. Check 48dp targets, More/TalkBack, row uniformity and retained query/scroll/playback after details/Back.
6. Record exact candidate SHA, test results, APK hashes, device/API/viewport/font scale, inspected before/after paths, actions and independent review findings. No evidence is claimed by this specification.

## Shared-file handoffs (not hard feature dependencies)

`NativePlayer.kt` currently combines player core and queue rows. FEAT-003 first makes a behavior-preserving queue extraction into `app/src/main/java/dev/spofify/mobile/feature/player/NativeUpcomingQueue.kt`, exposing header/items through `LazyListScope` and an optional bounded wrapper. Explicitly hand off the reviewed extraction/API and stop overlapping edits before this feature changes queue rows.

This feature can discover and implement shared catalog-row/all-list wiring independently. Its **queue-row changes occur after extraction and before FEAT-003 starts drag work**. Hand off stable row identity, More/custom-action API, gesture regions and tests. FEAT-002 owns expanded-player core/host; do not edit its layout while changing queue rows. These are file-level sequencing barriers, not fake epic-wide `Depends on` edges.

## Risks, open discovery and completion gate

### Grounded discovery and bounded delivery

Discovery base: `3ae4417fd0cb3dd337e310d57ec6a0c690c77c20` (normal refresh of assigned `5c6cf00e42b28e1ebc74a5d892cbbf5e1916263d`). Independent discovery review is required before this accepted implementation contract lands; exact candidate/base review records live outside the frozen candidate.

Inventory of current production row surfaces:

- `MusicRow`: Home/Recently played in `LibraryHome`; Search results, progressive results and nested artist/album/track detail groups through `SearchRoute` / `CatalogContent`; `MainActivity.LikedSongs`. Track identity opens details; intentional collection Play remains. Only track inline transport is removed, reclaiming its full 48dp without replacement padding.
- `PlaylistsRoute.PlaylistTrackRow`: manual saved occurrences and derived evaluated occurrences are bespoke rows. The complete-order editor is also a bespoke track presentation, with a separate reorder handle and Move up/down controls; its identity/actions must remain deliberate and must not turn the handle into a swipe target.
- `NativePlayer.UpcomingRow` and `CompactUpcomingRow`: FEAT-003 must first extract these to `NativeUpcomingQueue`; FEAT-001 temporarily owns extracted row/action/swipe work, then returns the file before drag work begins. Neither FEAT-002 host nor the pre-extraction queue block is edited here.
- `PlaylistOccurrenceActions` is a separate derived-occurrence More surface: preserve personal actions while adding equivalent intentional playback actions using the same selected-occurrence resolution as swipe.
- `PlaylistEditor` recipe-preview occurrences are intentionally read-only draft evidence, not authoritative playable selections: no inline playback, queue action or swipe is added there. Complete-order editor rows keep navigation/More but disable occurrence playback/queue mutation while the editable order is unsaved; the dedicated handle and Move controls retain ownership.

Concrete decisions: physical-right direction is independent of layout direction; exclude 24dp at both physical edges; lock only after platform touch slop with rightward distance greater than 1.5 times vertical distance; commit on an observed final release at 72dp minimum; cap feedback translation at 120dp. A backward reversal exceeding touch slop after horizontal lock cancels the whole gesture, even if subsequently dragged right again. Vertical intent, consumption by another gesture, any second pointer, pointer loss/cancel, replacement of the full bound item or session/authority loss cancel and reset. More/custom actions share the same callback, not a different append implementation.

Service/API barrier: `LibraryViewModel.enqueueFrom` currently clears `entryId`; playlist-specific occurrence selection and queue context cannot be honestly preserved by simply routing the existing generic callback. `PlaybackControls.enqueue` accepts items but no per-occurrence source context; global context must not be overwritten to simulate this. Derived occurrence IDs are not manual entry IDs; do not force a potentially 4,096-character derived identity through a 512-character manual-entry field. FEAT-004 owns playback/core/LibraryViewModel until explicit transfer. Request a generation/client/account-fenced prepared single-occurrence enqueue entry point that accepts the selected prepared `PlaybackQueue` item and its source context, preserves derived/manual occurrence metadata, and inserts without prepare/start in empty or paused state. Derived selection must resolve the exact rendered revision/fence/selection token once using existing preparation, select the exact returned occurrence and enqueue only that single new local occurrence, not the complete prepared collection. Do not refetch mutable selection, guess IDs or invent backend APIs. Signed selection authority stays transient and out of snapshots. UI may prepare read-only metadata, but never invoke Play to implement enqueue. Stale/busy/unavailable selections expose no successful queue action. Service capacity/shuffle/history policy remains service-owned; UI must not retire history or toggle shuffle to implement swipe. Empty-queue pending representation and mutation-result truthfulness must be verified with that owner, not invented in the UI.

Delivery slices are shared catalog row/gesture and measured development renders; prepared playlist occurrence wiring after service API; extracted Up next occurrence move wiring before FEAT-003 drag; combined gates and exact-SHA independent review/integration. Native acceptance remains blocked, so an integrated source slice is never marked done.

Discover the exact swipe threshold/feedback and caller-specific context mapping; validate them with the eventual rendered interaction, not just source. Principal risks are append accidentally substituted for Play next, duplicate queue entries, stale indices/authority, lingering blank width and horizontal interception of vertical/Back/drag gestures.

[BUG-001](BUG-001-native-validation-environment.md) is a **completion/validation gate, never a hard source dependency**. Source work and permitted synthetic tests may progress under separate authorization. Native AFTER-render, audio and background validation remain **BLOCKED / NOT COMPLETE**. The diagnostic budget is exhausted: no further emulator/ADB/discovery, real phone, personal AVD or live-network attempts are authorized.
