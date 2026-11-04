# Recovery 01 — modest shared typography

Outcome: slightly smaller shared text, independently deliverable; no playback/chrome dependency.
Branch suggestion: `recover/modest-typography`. Source/base and precise extraction: [00-extraction](00-extraction.md), section 01. Original FEAT-102 type requirements remain useful; its batch workflow does not.

## Scope
- Recover only `ui/theme/Typography.kt` scale and corresponding `TypographyTest` changes from R.
- Preserve bundled JetBrains Mono faces, hierarchy, weights, tracking, Android fallback, sp scaling and complete offline license. Body primary 13–14sp, secondary 12sp; do not shrink existing small labels.
- No row actions, navigation, Search reset, copy or service changes. Use baseline-compatible native fixtures rather than the combined smoke diff.

## Minimum acceptance
- Exact role sizes/line heights, hierarchy and scaled-sp tests pass; fonts/license still packaged.
- Current-APK default/narrow/font2 renders inspected for Sign-in, Home, Search, Playlists, dialogs and Now Playing; long/Unicode text, controls and 48dp targets remain usable. Compare baseline where available; record row count/hierarchy/clipping findings.
- Full leased offline automated gate, independent exact-SHA/base review and feature-owned merge per current policy. Native failures remain explicitly incomplete, not passed by unit tests.

No known source prerequisite; no build/native proof yet. A clean extraction is not feature readiness. This lead owns any type-layout regressions and validation; do not defer them to a final integrator or wait for other polish features.
