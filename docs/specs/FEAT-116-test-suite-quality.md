# FEAT-116 — Verify mobile test guarantees and prune proven duplication

## Tracking

- ID: FEAT-116
- Status: in-progress
- Phase: implementation
- Spec readiness: ready
- Owner: test-quality lead (e7b3acc7)
- Branch: test/test-suite-quality-prune
- Worktree: /var/home/lsm/repos/spofify-mobile-test-quality-prune
- Depends on: none
- Touchpoints: test-quality evidence; PlaylistOccurrenceActionsTest; TypographyTest only for the first implementation slice
- Next action: Verify the two scoped reductions, obtain one focused exact-SHA/base review, then land and account cleanup. Do not implement the deferred audit candidates as a batch. See the [removal ledger and validation evidence](research/FEAT-116-test-quality-validation.md).

## Purpose / authority

User wants **verify and prune**, not more tests or a target test count. Keep intended feature/user-flow coverage, hard-to-reproduce playback-device guarantees and meaningful frontend-owned bounded work/resources. Otherwise prefer limited regressions against stable public boundaries, not private implementation shape. Small pure algorithms, strict decoders, mocks and full musical snapshots can be valuable when they enforce those contracts.

Analysis base is clean landed main **`5b2422f645bc461af3e8419cd6af3399b33fc940`**. This specification commit contains documentation/evidence only, not implementation or acceptance. Analyzer may commit only its own docs in the named specification worktree; no root/main edits/integration, Go writes, push or history rewriting.

The guarantee-based [KEEP / CONSOLIDATE / REMOVE / GAP audit](research/FEAT-116-test-quality-audit.md) is the coverage/pruning rationale. Its [source index](research/FEAT-116-test-source-index.md) is navigation only: **1077 JVM declarations (163 core + 914 app) plus 18 instrumentation declarations**, not a coverage metric or current run result. Inventory includes actual core/app/androidTest and owned corpus/proxy/PCM/broker/browser/cleanup fixture categories. No test/build/native/broker/browser/network execution was performed in this analysis.

## One small READY implementation slice

Only these test files are authorized for behavior-neutral pruning:

| File | Change | Required kept evidence |
| --- | --- | --- |
| `app/src/test/java/dev/spofify/mobile/feature/playlists/PlaylistOccurrenceActionsTest.kt` | Consolidate `idleMoreKeepsExplicitNonStartingAddDistinctFromPlayNowSwipeIntent` and `activeMoreKeepsExplicitAddDistinctFromContextualAddSwipeIntent` into **one neutral-named test** of Queue Low/High action separation, reachability and no musical work. Both invoke the identical helper; their only different `contextualLabel` argument is unused by the production component. Retain a single helper body and pass today's `"Queue High"` argument; remove only the obsolete test-only variation. | Preserve the existing long-credit terminal reachability, 48dp/enabled actions, no callback on composition, exact once-only Low/High callback sequence, actual authenticated like-status Cookie/CSRF and exactly-two-request assertions. Keep `PlaylistMoreHostTest.manualMorePreservesExactDuplicateEntryForAllFourIntentsWithoutControllerPreparation`, derived/collection equivalents and `MusicActionsContentTest.fourPriorityActionsAreIndependentOfSampledTransportState` unchanged. Do **not** preserve a misleading “nonstarting” product claim: only unchanged exact current is nonstarting. |
| `app/src/test/java/dev/spofify/mobile/ui/theme/TypographyTest.kt` | Remove only `displayHeadlineTitleAndBodyHierarchyIsRetained`. Every size/leading inequality is already strictly implied by the same class's exact role map. Remove an import only if it becomes unused. | Keep `compactScaleHasIntentionalSizesAndComfortableLineHeights` and its complete role map/breathing-room assertions, `allFifteenMaterialStylesUseTheBundledFamily`, `materialWeightsTrackingAndShapingRemainUnchanged`, `everyFontSizeAndLineHeightUsesScaledSp`, resource/face mapping and font-license/large-font host tests unchanged. Do not remove both the implication and its stronger oracle. |

This is not a quota of two fewer tests; it is two concrete redundancy proofs. If landed main changes either premise, **retain the case and report the mismatch** instead of expanding scope or forcing the reduction. No new cases are required just to compensate for fewer declarations. Do not edit production signatures to remove an unused argument, retune typography or redesign menus.

## Acceptance and protected guarantees

1. A short removal ledger in the implementation evidence maps every removed execution/assertion to the kept method/assertion and its public guarantee. No unique negative/positive assertion disappears; any disputed edge stays. Same inputs/callbacks/state versus a genuinely distinct boundary must be established from source, not names or method counts.
2. Other tests and all production source, corpus bytes, fixture behavior, Gradle/category/CI configuration and executable pins stay unchanged. No weakening assertions/limits, assumptions, skips, arbitrary waits, fake success, fallback manipulation or broad shared-fixture rewrite. No benchmark framework or test-count threshold.
3. Preserve meaningful positive and critical negative coverage listed in the audit: actual host/broker/PCM/website execution; remote no-steal/unavailable; authority/session/service/queue/current/ABA and benign-heartbeat validity; transient/uncertain one-shot failure/no unsafe retry; exact-current start versus unchanged paused/idle/error state; truthful queued/blocked/media/superseded receipts/history; service lifecycle/registration and successor-safe Close/cleanup; privacy/exact-origin/auth/encrypted login.
4. Diagnostic queue-intent timing loops, login overlap, authority-minting fixtures, reflection/whole-style/palette assertions and synthetic Android broker overlap are **deferred review candidates**, not first-slice deletion permission. Keep unique safety coverage until an actual replacement demonstrates the same guarantee. Frozen core decoder/planner/service corpus reuse is not redundant merely because vectors repeat.
5. Future caching **FEAT-115** and stuck-connection **BUG-103** analyses/features/tests remain independent. Do not import unfinished branches, delete their future tests, invent dependencies, or combine caching/connectivity/UX changes into this task.

## Validation / review / delivery for the implementation lead

Read `AGENTS.md`, `AGENT_BEHAVIOUR.md` Lead/Reviewer and full `docs/testing.md`, `device-playback.md`, `prepared-transport-testing.md`, `worktree-review.md`, `storage-hygiene.md`; use the audit's evidence boundaries. Read `mobile-ux.md`/`ui-validation.md` for any claimed native evidence. Use an independent implementation topic/worktree (suggested `test/test-suite-quality-prune`, `../spofify-mobile-test-quality-prune`), not root/main source edits or someone else's worktree.

- Before pruning, verify retained tests and source-equivalence premises on the actual landed base. This deletion-only slice needs no fabricated RED test or production mutation. Run focused retained/changed test classes when runtime work is assigned, then the repository's existing delivery gate; do not repeatedly run the expensive full suite just to prove one metadata/configuration fact. Record commands, exact candidate/base, current results and blockers. Static inventory is not execution evidence.
- Normal Git internal locking only for worktrees/refs/local commits/integration; **no custom Git/MAIN guard, flock, semaphore or persistent reservation**. BUILD/cache and standalone NATIVE requirements remain exactly as documented. Normal implementation build/native work is separate from this analysis, which authorizes none of those runs.
- Portable tests require no machine-specific broker/browser executable. The mandatory provisioned `RealBrokerIntegration` category remains mandatory for actual protocol acceptance (`-Pspofify.realBrokerIntegration=true` includes ordinary and integration); selected missing/mismatched prerequisites fail explicitly. No skip/mock/download/sibling-checkout fallback. Do not change the external binary pin or test backend throughput in a mobile pruning task.
- Use the existing five-task offline API37 compile-platform delivery gate under BUILD as specified in `testing.md`. No new acceptance framework/gate. Exact-APK isolated AVD and inspected renders remain separate from host/PCM/browser checks; this test-only slice must not claim new installed-Android render/audio/background proof. Record applicable existing native gate/blocker honestly; no production UI change is proposed. Never target real phones or turn a native simulation into an Android pass.
- Obtain **one proportionate focused independent read-only review** of the committed delta against its exact base, especially implication/duplicate proof and retained safety coverage. Fix non-nit findings in a new commit and re-review changed content as necessary; no review committee. Follow documented reviewed landing/postchecks and no-overwrite safeguards when the implementation lead has integration assignment. Analyzer handoff itself remains unintegrated docs only.
- Preflight bytes/inodes/visible quotas before artifact-heavy implementation runs; bound owned scratch/logs, verify forked JVM `java.io.tmpdir`, await all descendants before cleanup, retain compact evidence and account for failures/cancellation. Analysis artifacts are bounded to **8MiB**; never inspect/purge foreign worktrees/caches or broadly sweep `/tmp`.

## Analysis evidence / handoff

Retained only this spec and the two linked research Markdown files, well below 8MiB. One tool-output spill created by this analysis was removed by its exact owned path after the command stopped; no scratch/test/build/broker/native outputs or descendants were created. No capacity/quota claim is made from this small docs-only work. No source edits, test deletion or validation pass is implied. Full spec candidate SHA and base are supplied in the analyzer handoff rather than a self-referential SHA inside the frozen commit.
