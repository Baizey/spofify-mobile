# Recovery 05 — Search tab is a fresh search intent

Outcome: every explicit Search tab tap/reselect returns to empty root, focuses the real input and requests software IME.
Branch suggestion: `recover/search-tab-activation`. No feature prerequisites. Exact extraction: [00-extraction](00-extraction.md), section 05 and native-fixture map. Original FEAT-102 activation contract applies, not its source-handoff workflow.

## Scope
- Recover transient activation state/consume/cancel in LibraryViewModel, fresh CatalogState root/read fences, field-owned lifecycle-active delivery and host nav/expansion handling.
- Must work on baseline Material NavigationBar and OutlinedTextField if 03/04 are not landed; adapt the small call sites/test tags rather than depend on those features.
- Clear accepted query, local draft/composition, paging/results/scroll and both host/inner stacks. Fence obsolete responses; empty query never fetches all catalog.
- One-shot client/account/lifecycle identity: no replay on rotation/resume/programmatic tab return. Player expansion dismisses underlying focus/IME, preserves query and does not refocus on collapse.

## Minimum acceptance / blockers
- Recover SearchRootResetTest, non-geometry SearchActivationFieldTest, SearchNavigationHostTest and held-response fixture counters; remove unrelated copy/compact-nav/new-row expectations from this branch's tests.
- Actual nav tap/reselect from Home/Playlists/root/saved host and inner detail, same empty query/composition, stale response/revision, pause-before-delivery and auth transition tested.
- Ordinary Back/tab/programmatic return preserves state; Back dismisses editing before popping. No playback commands from navigation.
- Fresh current-APK native nav tap must show actual software IME **without a corrective field tap**; verify reselect, root/reset/scroll, Back, expansion and recreation at narrow/font2. Mock keyboard calls alone do not pass.
- Full leased offline gate, independent exact-SHA/base/render review, own merge when ready. No identified playback/appearance compile prerequisite; split fixture assumptions and lifecycle/focus behavior remain unvalidated.
