# Recovery 04 — compact matching-background Search input

Outcome: smaller Search field/dock matching the app background, independently of tab reset/focus changes.
Branch suggestion: `recover/compact-search-field`. No prerequisites. Exact hunks/tests: [00-extraction](00-extraction.md), section 04.

## Scope
- Recover BasicTextField decoration, baseline/focus cue, text/cursor colors, 48dp field/clear targets, modest dock padding and exact-background Surface.
- Preserve baseline composing TextFieldValue reconciliation, debounce, submit, clear, Back/edit-end and chrome hiding behavior. No activation identity/root-reset API is required.
- Extract only field geometry case from SearchActivationFieldTest into a presentation-specific test; preserve existing field/editing tests.

## Minimum acceptance / blockers
- At default font, 48dp minimum field and approximately 56dp dock, no raised/pale fill/heavy pill; readable placeholder/text and perceptible focus cue. Large fonts grow without clipping.
- Query remains lower-thumb/above IME, editing removes nav/unneeded toolbar including reserved space, restoring editing preserves query/results/scroll/playback. Clear and submit are reachable and accessible.
- Test composition, debounce, clear/submit and existing Back behavior against **baseline navigation**. Do not import 05 just to make tests pass.
- Inspect current-APK closed/open IME default/narrow/font2 renders and geometry/contrast; run full leased offline gate and independent exact-SHA/base/render review, then own merge.

No identified source dependency; focus/IME behavior and BasicTextField semantics are unvalidated. Later 05 merges only its activation seam, not a whole SearchRoute replacement.
