# Recovery 02 — quiet empty/root surfaces

Outcome: remove redundant empty/help prose while preserving recovery and creation paths.
Branch suggestion: `recover/quiet-root-copy`. No feature prerequisites. Use [00-extraction](00-extraction.md), section 02, for exact R hunks.

## Scope
- Home: omit empty Recent tutorial shelf; remove playlist tutorial paragraph, retain Liked Songs and genuine playlists/navigation.
- Search: remove empty-query paragraph; field placeholder/accessibility label is sufficient. Keep nonblank no-results, loading and failures distinct.
- Playlists: remove generic root headline/tutorial and empty disabled paging row; keep Create playlist/Refresh and real paging.
- Likes: concise `No liked songs yet.`
- Do not import row/action/Search reset/type changes. The obsolete derived-dialog Play-button instruction belongs to 07 because it changes with that affordance, not here. Retain warnings, stale/revision/uncertain-write confirmations and legal text.

## Minimum acceptance / open work
- Add standalone empty/populated/loading/error/partial-failure root tests; extract only copy assertions from combined host tests (no activation API dependency).
- Confirm removed prose absent, create/retry/navigation remain discoverable, errors never masquerade as empty; empty Search sends no match-all read.
- Inspect current-APK root renders at narrow/default/font2, with actionable controls and useful recovery intact.
- Full leased offline gate and independent exact-SHA/render review, then this lead merges when ready. No playback or shell gate dependency. No validation pass is inherited from R.
