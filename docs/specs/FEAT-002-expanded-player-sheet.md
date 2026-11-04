# FEAT-002 — Restore an expanded player sheet with stable scrolling

## Tracking
- ID: FEAT-002
- Status: in-progress
- Phase: implementation
- Spec readiness: ready
- Owner: expanded-sheet-lead
- Branch: fix/expanded-player-sheet
- Worktree: /var/home/lsm/repos/spofify-mobile-worktrees/expanded-sheet
- Depends on: none
- Discovery depends on: none
- Touchpoints: NativePlayer.kt expanded core and host; MainActivity.kt expanded-player hosting; NativePlayerGestures.kt; planned NativeUpcomingQueue.kt integration; NativePlayerTest.kt
- Next action: implement external-viewport core budget helpers and tests; await FEAT-003 extraction handoff before core/host edits; replace fullscreen/disclosure tests and adapt the strict native journey

## Product decision and outcome

The user liked the **previous sheet**, not a fixed fullscreen Dialog. Restore a native expanded-sheet experience, kept expanded while in use, with initially visible musical identity and transport. This contract **supersedes the production fixed-fullscreen Dialog at main4183**, including conflicting fullscreen-host guidance in existing mobile UX/validation documentation for this feature. It does not waive accessibility, Back, inset or native evidence requirements. Removing the Dialog without restoring a coherent sheet is not acceptance.

This ready contract is accepted under the coordinator's explicit discovery-and-implementation assignment after independent discovery review. It is not a claim that a sheet replacement or oscillation fix exists. The previous sheet is an interaction reference, not proof that its old sizing/scroll implementation should be copied unchanged.

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

## Accepted discovery decisions and delivery slices

Independent read-only discovery review by `subagent-e0ddc8e1-61e9-4bef-a395-521a4885706e` inspected source at `3ae4417fd0cb3dd337e310d57ec6a0c690c77c20`. It recommended the external-viewport sheet direction and identified the control budget, mixed-list queue indices, reopening, Back/insets and genuine touch-overflow assertions below as readiness conditions. These are resolved contract decisions, not claims of passing implementation tests. Exact committed slices still require fresh exact-SHA review before landing.

1. **Host and width:** use Material3 `ModalBottomSheet`, `skipPartiallyExpanded = true`, native drag handle and native dismiss/Back behavior. The sheet shell fills the externally available height; its anchors must not depend on queue content, list offset, progress, errors or artwork completion. Use the available phone/window width, including genuine landscape, rather than imposing an unrelated narrow default sheet width. A Material sheet's internal dialog window is not the forbidden fixed-fullscreen player replacement.
2. **Insets and opening:** hide/clear the underlying Search IME/focus on deliberate expansion. The sheet owns top/bottom safe-drawing/IME inset handling exactly once; do not retain the old content `safeDrawing` wrapper on top of sheet inset consumption. Define viewport cases as window dp, explicitly subtracting safe areas and handle/padding before calculating the core budget. A deliberate opening starts at the core/top. Ordinary publications preserve the same parent list state; resize/rotation retain presentation without issuing playback work, while any deliberate close/reopen returns to top.
3. **Core budget:** retain separate title and credit lines, a 48dp seek target, elapsed/duration and three 48dp transport targets. At extreme height/font scale, omit decorative artwork and secondary controls first; allow elapsed/duration to share the transport band's side slots rather than requiring another scaled text band. A stable compact feedback slot must not replace credits or hide errors behind extras. Use production AppTypography/JetBrainsMono font measurements and native-graphics component tests, not unscaled hard-coded text-height assumptions. A failed measured budget requires a layout correction, never clipped text/targets or silently hidden feedback.
4. **One flow and queue seam:** the parent owns `LazyListState` and one `LazyColumn`: core header, secondary extras, queue header/empty/occurrence rows. Queue extraction exposes `LazyListScope` content and an optional standalone bounded wrapper. Header keys are namespaced and rows use exact occurrence keys; queue-local reorder indices must be translated independently of preceding parent header items. Share the parent state and finite viewport with queue edge autoscroll. Do not edit queue row/swipe/drag ownership during core/host implementation.
5. **Gesture and Back:** normal vertical content motion scrolls the parent list; the native sheet handles deliberate collapse, dedicated queue handles own reorder, and seek/horizontal row/cover gestures retain their existing areas. Keep the underlying-route Back exclusion while expanded. Production-host tests must dismiss an open menu (and IME when relevant) before collapsing the sheet, and collapse exactly once before underlying navigation. No competing player BackHandler or duplicate app navigation Back control.
6. **Regression evidence:** use an actually overflowing long queue; prove touch-driven visible-index/offset movement before reaching the absolute end and releasing. Allow finite entry/overscroll settling, then sample sheet viewport, parent first-visible index/offset and visible terminal-row bounds through many progress publications and delayed visible queue-artwork completion. Never require naturally disposed core controls at the bottom. Treat stable source/component observations as development evidence, not proof of native oscillation causality.
7. **Slices:** the accepted contract/board slice may land first with Python and documentation checks only. New isolated core-budget helpers/tests can proceed before extraction. Core/host integration waits for the extracted queue API on main; then adapt old expanded-core tests and NativeUiSmoke without weakening its guards, fixture totals, exact occurrence/queue/order/position/duration/progression and no-extra-prepare assertions. Run relevant/full Android unit/APK/lint gates under BUILD, exact-SHA review, leased integration and main postchecks. Preserve non-sensitive evidence and recovery accounting outside the worktree before cleanup.

## Risks and blocked validation

Main risks are restoring the old feedback loop, optimizing only a tall/default viewport, suppressing symptoms instead of the bottom-release cause, and accidentally issuing playback work during recomposition/navigation. The mandatory initial matrix is 360×800/font1, 320×320/font2, additional narrow/short and landscape/font2 windows, long Unicode credits and error/empty states. No source implementation or rendered after-evidence is claimed by this readiness update.

[BUG-001](BUG-001-native-validation-environment.md) gates **completion/validation only**, never source implementation. Native AFTER-render, audio and background validation are **BLOCKED / NOT COMPLETE**. No after-render is approved. No further emulator startup/configuration, ADB/discovery, real-phone/personal-AVD or live-network work is authorized under the exhausted diagnostic budget.
