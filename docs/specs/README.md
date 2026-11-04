# Mobile feature work board

Repository-owned contracts and task tracking, not a hosted service, database or app dependency. **Kotlin / Compose / Media3 remain the only application stack. Python is standard-library-only development tooling.** Specs describe intended work; they are not evidence that behavior shipped. Mobile IDs are independent of identically numbered backend tasks. The sibling backend remains read-only.

```sh
python3 scripts/specs.py board
python3 scripts/specs.py ready
python3 scripts/specs.py check
python3 scripts/specs.py board --json
python3 -m unittest discover -s scripts -p 'test_specs.py' -v
# Any cwd works with an absolute script path. Isolated fixtures:
python3 /path/to/mobile/scripts/specs.py ready --root /path/to/fixture --json
```

The default root is the script's repository, never the current directory. `--root` names a repository-shaped directory containing `docs/specs`. All three commands validate the **entire** inventory first. Invalid/missing/empty inventories exit 1 and emit errors, never a partial ready list; argument errors exit 2. JSON failures contain `errors`; success contains `count` and `specs`, including metadata, `runnable` and `waiting`. Files are read in filename order, errors and output deterministic. No writes, task claiming, Git operations or network access occur. `check --json` returns the full validated inventory. Readiness is eligibility for assignment, not permission to implement or merge.

## One metadata source

Copy [_template.md](_template.md) to `FEAT-NNN-short-name.md` or `BUG-NNN-short-name.md`; keep the ID stable and use a matching first-line `# ID — Title`. Task files live directly in this folder; `README.md`, underscore-prefixed templates and nested research directories are excluded. Do not add another maintained board/status database.

Exactly one canonical `## Tracking` section contains only blank lines and unindented `- Field: value` lines. All template fields except optional `Discovery depends on` are required; that field defaults to `none` only if absent. Unknown/duplicate/malformed/empty fields are errors, as are alternate/duplicate Tracking headings. End metadata with an unindented `##` section. Prose, checklists and evidence belong outside Tracking. Dependencies are comma-separated stable IDs or exactly `none`; duplicate/invalid/missing/self/cyclic references (including mixed-phase cycles) fail validation.

- **todo**: unstarted, awaiting assignment to its next action.
- **in-progress**: assigned lead actively working.
- **review**: independent review underway.
- **blocked**: named obstruction and next decision/evidence; other safe slices can proceed.
- **done**: accepted implementation independently reviewed, integrated into main and post-checked. Not merely discovery, a source checkpoint or an approved draft.

`Phase` is `discovery` or `implementation`; `Spec readiness` is `draft` or `ready`. Discovery may be todo/draft and runnable: it means investigate, **not implement**. Implementation must have an accepted ready contract before active/review/done status. Discovery cannot be done. In-progress/review requires an assigned owner, non-main topic branch and absolute dedicated worktree path. These are metadata consistency checks, not proof of a running process, assignment or completed gate. Owner/branch/worktree values record assignment/provenance, not filesystem existence or live-process checks; CI need not have local lead worktrees. Historical values may remain on completed tasks after cleanup. Existing Android CI runs the stdlib tests and metadata check without new actions/dependencies or changes to Android build tasks.

## Hard prerequisites versus touchpoints

`Depends on` means real **implementation** prerequisites. `Discovery depends on` means genuine **discovery** prerequisites. `ready` selects only todo tasks whose current-phase prerequisites are done; implementation additionally needs ready spec readiness. Blocked/active/review/done tasks are never advertised as available.

Shared-file overlap, optional enhancements and native validation gaps are **not automatically hard dependencies**. Record ownership, interfaces and handoff barriers under Touchpoints and delivery slices. Split a genuinely independently required milestone instead of blocking on an entire epic. [BUG-001](BUG-001-native-validation-environment.md) is an explicit native completion/validation gate, never a blanket prerequisite preventing source implementation.

For the initial UI contracts: [FEAT-002](FEAT-002-expanded-player-sheet.md) owns expanded core/host; [FEAT-003](FEAT-003-visual-queue-reorder.md) owns queue presentation and first extracts it behavior-preservingly from `NativePlayer.kt` into `NativeUpcomingQueue` with LazyListScope header/items and optional bounded wrapper. [FEAT-001](FEAT-001-track-row-swipe.md) owns shared track rows and all-list wiring. Queue-row swipe wiring occurs **after extraction handoff and before drag changes**. FEAT-002 can then compose one parent lazy scroller; coordinate the shared file before editing. These are explicit barriers, not fake hard epic dependencies. Discovery for 001–003 remains unassigned/draft.

## Assignment, review and leases

The assigning coordinator establishes lead/scope/base and accepts spec readiness. Editing stale metadata cannot claim/reassign work. Discovery leads edit assigned contracts only until implementation assignment. Dedicated worktrees isolate indexes/outputs, not semantics. The board is **not a lock, scheduler or integration authority**.

[Worktree/review policy](../worktree-review.md) governs standing conditional self-integration for assigned scope: independent read-only exact-SHA review, scoped gates, and an exclusive OS advisory main lease continuously covering all destination fences, squash inspection and commit. This is not self-approval or parallel merging. Separate main/Gradle leases use `spofify-main.lock` and `spofify-gradle.lock` under the **absolute Git common directory**. Own-worktree builds hold BUILD only and release it before attempting MAIN; root/main post-integration Gradle acquires MAIN then BUILD and holds both to freeze source, never build→main. No repeated parent grant is needed for an assigned lead satisfying those conditions; assignment never grants unrelated writes. Unknown/uncoordinated writers require stopping. Changed main base requires normal merge in the lead's own branch, re-gating and new exact-SHA review. Never fix/reset/stash/revert/restore in main. No push/deploy/history rewriting.

## Discovery and completion gates

- Record explicit user outcomes, current evidence versus assumptions, non-goals, decisions, clear acceptance checks and measurable interaction/resource criteria appropriate to the feature. Favor reversible scoped choices; escalate product/privacy/irreversible ambiguity.
- Model cancellation, stale/concurrent queue/account/authority changes, offline/uncertain writes, duplicate/derived occurrence identity, accessibility, bounded resources and safe recovery where relevant. Each material risk needs a named test or honest manual gate.
- Review discovery independently before accepting ready. For implementation map acceptance/risks to focused tests and actual rendered/interaction evidence, not source assertions. Preserve 48dp targets, Unicode/font fallback, font scaling and native Back/insets.
- Native AFTER rendering/audio/background checks remain **BLOCKED / NOT COMPLETE** under BUG-001; no emulator/ADB/discovery/network/real-phone attempts are authorized. Compilation, unit tests, source review or historical APKs do not waive these gates. Foundation-only tooling/docs changes need Python/source checks, not Gradle or native execution.
- Commit coherent checkpoints, review exact candidates, correct and re-review new SHAs, integrate under lease, post-check main. Record evidence/accounting outside frozen candidates so no self-referential approval SHA is invented. A separately reviewed follow-up may mark a verified integrated task done.
- Preserve non-sensitive tests/reviews/candidate/recovery bundles outside the worktree; verify final source/patch accounting before non-forced cleanup. No private `.env`, credentials, library data, live accounts or backend mutations.

The modest parser/tests are adapted from the fully read sibling `spofify-go/scripts/specs.py` and `test_specs.py`; this is not a cross-repository scheduler. Backend source was not modified or executed.
