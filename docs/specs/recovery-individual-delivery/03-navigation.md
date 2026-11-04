# Recovery 03 — compact bottom navigation

Outcome: smaller Home / Search / Playlists bar, independent of Search activation and playback.
Branch suggestion: `recover/compact-navigation`. No prerequisites. Exact source: [00-extraction](00-extraction.md), section 03.

## Scope
- Recover compact selectable row/items from `ui/ListeningChrome.kt`, matching background/no tonal slab.
- Replace only nav layout in MainActivity; retain baseline `switchTab(tab)` semantics. No new Search reset/focus behavior, lifecycle hook, volume routing or copy.
- Recover two compact geometry tests. Native shared checks must measure nav only, not require the new Search field.

## Minimum acceptance / blockers
- Approximately 56dp content excluding genuine system inset at default font; every full-height destination at least 48dp, selected/accessible tab semantics and reselect click delivery intact.
- Narrow/landscape safe insets counted once; font2 grows rather than clips. Existing editing hide/show and full-width mini-player/last-row reachability retained.
- Inspect actual default/narrow/font2 nav renders and taps/Back with and without IME. Geometry-only source tests are not rendered approval.
- Full leased offline gate, independent exact-SHA/base/render review and own merge as ready. No identified compile prerequisite; extracted tests/new layout still unvalidated. Shared MainActivity edits require scoped conflict resolution, not a combined feature batch.
