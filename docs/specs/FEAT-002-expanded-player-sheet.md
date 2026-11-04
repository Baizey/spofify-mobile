# FEAT-002 — Restore an expanded player sheet with stable scrolling

## Tracking
- ID: FEAT-002
- Status: todo
- Phase: discovery
- Spec readiness: draft
- Owner: unassigned
- Branch: unassigned
- Worktree: unassigned
- Depends on: none
- Discovery depends on: none
- Touchpoints: NativePlayer.kt expanded core and host; MainActivity.kt expanded-player hosting; NativePlayerGestures.kt; planned NativeUpcomingQueue.kt integration; NativePlayerTest.kt
- Next action: agree the sheet and single-scroll-owner design, obtain queue extraction API handoff, and specify a genuine terminal-bottom oscillation regression

## Product decision and outcome

The user liked the **previous sheet**, not a fixed fullscreen Dialog. Restore a native expanded-sheet experience, kept expanded while in use, with initially visible musical identity and transport. This contract **supersedes the production fixed-fullscreen Dialog at main4183**, including conflicting fullscreen-host guidance in existing mobile UX/validation documentation for this feature. It does not waive accessibility, Back, inset or native evidence requirements. Removing the Dialog without restoring a coherent sheet is not acceptance.

This is a draft contract, not a claim that a sheet replacement or oscillation fix exists. The previous sheet is an interaction reference, not proof that its old sizing/scroll implementation should be copied unchanged.

## Layout and presentation contract

- On opening, show title, credits, seek control, elapsed/duration times and **Previous / Play-Pause / Next without scrolling**. Keep usable 48dp targets. Adapt/shrink/remove decorative artwork and secondary content first; do not push core transport below the fold.
- Keep the sheet expanded rather than snapping between competing partial heights as artwork/progress/queue content changes. A deliberate collapse/dismissal and native Back remain possible. Do not substitute a fixed fullscreen `Dialog` as the solution.
- Prefer **one sheet lazy flow: core header, extras and queue header/items, at all supported sizes**. Normal scrolling reaches Up next; no mandatory separate queue disclosure/surface is needed just because its header starts below the fold. Use one coherent scroll owner and stable viewport, never a separately unconstrained queue scroller inside it or the old draggable-sheet + whole-content scroll + nested-queue feedback loop.
- Core visibility is strict in the **initial** sheet view. After intentional scrolling to extras, primary content may naturally move offscreen; it is not always pinned. Superseded always-pinned/fullscreen/dedicated-queue-view requirements must not be carried forward.
- Sheet drag, content scroll and queue reorder must have explicit ownership. The sheet shell must not continuously derive anchors/height from changing queue measurements, progress ticks or artwork completion. Preserve lazy-list state/keys across ordinary publications.
- Support narrow/short windows, genuine landscape and font scale 2.0. Artwork and secondary copy yield first to retain initially visible identity/seek/times/transport. A below-fold queue header at an extreme size is reached by scrolling, not a reason for a separate queue surface or a cramped permanent transport/queue split.
- Handle status/navigation/gesture/IME insets once, without blank chrome or double-padding. Preserve bundled typography, Unicode/emoji fallback, stable error/notice presentation and accessible contextual More actions.

## Actual bottom-release regression

Fix the reported **few-pixel oscillation after release at the real bottom**. Merely disabling overscroll, changing spring cosmetics, using a fitting queue or hiding the affected area does not establish a fix.

The regression must create enough synthetic content for genuine overflow, demonstrate an actual visible-index/offset change from touch scrolling, reach the terminal boundary and release all pointers. Through repeated playback progress updates and delayed artwork completion, observe the sheet viewport, visible terminal-row geometry and list offsets with no alternating few-pixel movement or sheet-anchor churn. Primary header items may be disposed/offscreen there; do not require their bounds at the bottom. Record layout/scroll observations and eventual rendered evidence. Distinguish normal finite overscroll settling/entry animation from persistent post-release feedback.

## Playback and navigation invariants

Opening/expanding/collapsing, scrolling, opening/dismissing extras, Back, navigation and configuration changes are presentation only: **no prepare, autoplay, stop, seek, replace or reorder commands**. Preserve service-owned audio, exact current occurrence, queue, history and elapsed-position continuity. Seeking and transport are the only corresponding intentional controls; presentation must not replay their callbacks.

Native Back dismisses transient menus/IME first where applicable, then collapses the player, then returns to underlying route behavior. No duplicate navigation Back button, competing handlers or double-pop. Preserve the underlying query/results/scroll and selected route. Music Previous is a distinct visible transport action.

## Acceptance and evidence plan

1. Inspect the actual sheet host, expansion/dismiss gestures and Back from the production entry path, not only component content. Demonstrate no fullscreen-Dialog substitution.
2. Capture opening and scrolled states at narrow, short, landscape and font scale 2.0 with long title/credits, unavailable art and playback error/notice. Core identity/seek/times/transport are initially usable without scrolling and targets remain 48dp.
3. Exercise genuine terminal-bottom release as specified above, with a long queue and repeated publications. Inspect recorded after-renders/interaction evidence for the reported movement, not just an overscroll setting.
4. Assert presentation/navigation emit no playback mutations and preserve exact occurrence/order/position across collapse, reopen, rotation and scrolling to Up next. Cover Back ordering and insets.
5. Review with FEAT-001 swipe and FEAT-003 long-held edge-autoscroll drag: each gesture retains its owner, the sheet stays coherent and no nested-scroll feedback returns.
6. Record exact candidate SHA/APK hashes, synthetic fixture, device/API/viewport/font scale, inspected screenshots and actions, automated results, independent review and corrections. Source/component tests are supporting evidence only.

## Ownership and sequencing barriers

This feature owns **expanded-player core and host**, principally `NativePlayer.kt` and its `MainActivity.kt` entry. FEAT-003 owns queue presentation. Because these currently share `NativePlayer.kt`, FEAT-003 first extracts queue presentation into `NativeUpcomingQueue.kt` without behavior changes and exposes **`LazyListScope` header/items plus an optional bounded wrapper**. The extraction must allow this feature to use **one parent lazy scroller**, not force another queue `LazyColumn` inside it.

Agree and hand off the extraction/API before overlapping edits; then this feature owns core/host while the queue lane owns the extracted file. FEAT-001 queue-row changes land after extraction and are handed off before FEAT-003 drag work. Coordinate viewport/list-state/gesture contracts explicitly. This is touchpoint sequencing, not a hard whole-feature dependency; discovery and nonoverlapping work can proceed in parallel.

## Risks, discovery and blocked validation

Open discovery: establish the previous-sheet interaction baseline, stable viewport/scroll design and FEAT-003 queue extraction API handoff.

Resolve sheet implementation, initial control budget at extreme sizes and stable scroll/anchor ownership before marking ready; normal scrolling reaches queue content. Main risks are restoring the old feedback loop, optimizing only a tall/default viewport, suppressing symptoms instead of the bottom-release cause, and accidentally issuing playback work during recomposition/navigation.

[BUG-001](BUG-001-native-validation-environment.md) gates **completion/validation only**, never source implementation. Native AFTER-render, audio and background validation are **BLOCKED / NOT COMPLETE**. No after-render is approved. No further emulator startup/configuration, ADB/discovery, real-phone/personal-AVD or live-network work is authorized under the exhausted diagnostic budget.
