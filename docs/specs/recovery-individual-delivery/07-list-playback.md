# Recovery 07 — list Play removal + contextual right swipe

**User-approved significant-overlap grouping this time:** inline Play removal and its replacement start/queue gesture share row affordances, exact occurrence actions, More/accessibility and authoritative start/insertion behavior. No other polish features are bundled.
Branch suggestion: `recover/contextual-list-playback`. Prerequisite: landed 06 guarded-start correction; no dependency on typography/nav/copy/Search activation or the system-media feature. Extract from R using [00-extraction](00-extraction.md), section 07; original FEAT-101 behavioral/security contract remains applicable, not its waterfall handoff.

## Required complete outcome
- Remove inline Play/Pause and reserved slot from shared/collection, manual/derived playlist and normal/compact upcoming rows; retain deliberate detail/header/transport actions. Row identity opens details; More/TalkBack provide deliberate Play now/contextual alternative and rich credits.
- Physical right while authoritative idle/paused/ended/failed starts the exact selected prepared item/occurrence; active or buffering-toward-playback appends FIFO **after pending manual occurrences, before context tail**, without disturbing current/elapsed/history.
- A → M1 → M2 → C1 → C2; swipe X then Y gives A → M1 → M2 → X → Y → C1 → C2. Upcoming promotion moves exact occurrence, never duplicates it. Explicit Add never starts; explicit Play next stays distinct.
- Correct manual provenance through progression/return/shuffle/reorder/remove/Undo/clear/import. No guessed wire provenance, destructive capacity relief, backend schema change or remote local fallback. Contextual/manual FIFO remote operation stays honestly unsupported; supported explicit remote Play next retains one-command terminal acknowledgement.
- Preserve cancellation authority/session/route and at-most-once gesture-release fences, vertical scrolling, edge Back/handles, 48dp targets and truthful terminal feedback.

## Extraction blockers and minimum acceptance
- Recover complete C-supplied `playNextOccurrence` Controls/Connection/Service seam and its queue tests **into this branch**; A/B alone were incomplete. Recover acknowledged coordinator/result observer machinery, not media keys/projection. Move queue-specific tests out of PlaybackMediaButtonsTest as mapped.
- Include R corrective tickets/exact additive receipts, completion-after-publication/admission, More host captured-key/lifetime, transition-time manual prune and overflow occurrence-ID fixture. All are **unvalidated fixes**, not closed findings.
- Prove two committed contextual actions captured before delayed first preparation survive **their own** exact key change, but held gesture/external edit/route/auth changes do not rebind; use real ViewModel→Connection→Coordinator→Service regression, not fake acknowledgements alone.
- Run complete service/planner/row/gesture/playlist/bridge tests in the extraction map; test capacity/publication, duplicate/signed derived exact selection, idle states, full/limited/no-op/stale/remote outcomes and preserved transport/history.
- R NativePlayer uses the composable queue wrapper with host lifetime already remembered: no observed missing lazy-call argument; do not implement stale historical handoff prose.
- Fresh isolated synthetic current-APK row/More/swipe/queue/Back/drag validation, narrow/font2 inspected renders; full leased offline gate and independent exact-SHA/base/render/security review. This lead fixes, validates and merges the feature when ready, never hands unfinished work to a final integrator.
