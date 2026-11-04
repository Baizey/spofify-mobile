# Isolated worktrees, exact-SHA review and squash integration

**Finished work must land in `main`.** Temporary integration branches are optional staging only, never the final destination. Each implementation lead must use an **actual dedicated Git worktree and topic branch**, not a shared checkout with file assignments. Read `AGENTS.md` and the local `.tooling/lead-handoff.md` first. Verify `pwd`, `git branch --show-current`, `git rev-parse HEAD` and `git status --short`; record the starting base SHA and assigned paths. Work only in that scope. Coordinate shared interfaces before changing them.

## Preserve work before migration

Freeze old shared-checkout writers. Inventory existing commits, dirty paths and staged changes without modifying them. The owner must preserve agreed dirty work in scoped checkpoint commit(s) on a migration branch before creating or moving worktrees. Do not sweep another lane's changes into a checkpoint. Record which deltas belong to which lane and apply each only once. Already-captured external commits remain intact; do not replay their content as new work.

Each worktree has its own index, source checkout and build outputs. Do not share or redirect `app/build`, `core/build`, root `build` or other generated output directories across worktrees. Point each untracked `local.properties` at the provisioned shared SDK; do not modify the SDK or sibling checkout configuration. Leads make focused commits on their own branches without a centralized Git writer. Never reset, revert, stash, restore snapshots or rewrite external commits/staged work. No history rewriting, unauthorized main writes or push. Main squash writes require the explicit slot below.

## Independent exact-commit review

1. Run appropriate scoped checks, including `git diff --check`, and commit only assigned paths. Record the full candidate SHA and base SHA. A checkpoint/source commit is not native acceptance.
2. Obtain a **fresh independent, read-only reviewer** for that exact candidate SHA. Supply the base, scope, requirements, check results and evidence/blockers. Review committed content/diff, not an uncommitted moving tree. The reviewer must not edit source, stage files or commit. UI reviews must also inspect relevant actual renders; a before-only image or Robolectric render is not AVD approval.
3. Fix non-nit findings in a new scoped commit, yielding a new candidate SHA, then obtain re-review of that SHA. Repeat until the source/documentation review is clean. Never transfer approval from an older SHA to corrected content.
4. Report reviewer job/identity, reviewed full SHA, findings and disposition. A clean source review can be ready for integration while native validation remains explicitly blocked; it does not mean visual completion.

## Shared-cache build slots are separate

Do not start Gradle until the parent explicitly grants a serialized shared-cache build slot for the requested worktree, candidate and tasks. One active slot at a time; report completion and release before another lane starts. A source assignment, review pass or integration slot does not grant a build slot.

Run from the lead's own worktree, with its own outputs and the provisioned SDK:

```sh
GRADLE_USER_HOME=/var/home/lsm/repos/spofify-mobile/.tooling/gradle \
  ./gradlew <approved-tasks> --offline --no-daemon --console=plain
```

Record actual commands, SHA, results and failures; never infer a pass from an older checkpoint or historical test count. The parent owns the combined test/build/lint and native smoke gate after integration. See [testing.md](testing.md) for tasks and [ui-validation.md](ui-validation.md) for native requirements. Do not run a native runner that implicitly builds without an applicable build slot.

## Request, then execute a serialized main squash slot

The parent serializes **all writes to the shared destination checkout**; only the explicitly granted lead performs its squash. Request a **MAIN squash slot** only after scoped source/review gates are ready, supplying:

- Lead worktree/topic branch; full candidate and starting base SHAs.
- Exact changed paths and a short purpose; scoped tests/checks with results.
- Independent reviewer job/identity and exact reviewed SHA; unresolved findings, if any.
- Remaining blockers, especially native AVD evidence not complete.

Wait for the parent to grant the destination checkout, branch **`main`**, approved candidate SHA/scope and **expected main HEAD SHA**. Never self-grant, merge early or modify main/sibling source without that grant. A previous temporary-integration grant does not authorize main writes. Then:

1. In the granted destination checkout, verify its absolute path, `main` branch and full HEAD match the grant. Confirm `git status --porcelain --untracked-files=all` is empty, including no staged/untracked surprises. If anything differs, **abort and report**; never clean up, stash or overwrite unexpected work.
2. Confirm the candidate still equals the approved, independently reviewed SHA and inspect `git diff <baseSHA>..<candidateSHA> --name-only` for scope. Check overlapping main changes before proceeding; account for any content already landed so it is not replayed. Abort on unexpected source, staged changes or conflicts; do not improvise a resolution into the approved candidate. Obtain a corrected candidate and re-review if needed.
3. Only while holding the slot, run `git merge --squash <candidateSHA>`. Inspect the entire staged diff and path list against the approved scope and candidate, preserving already-landed concurrent changes, then run `git diff --cached --check`. Do not include unrelated changes. If the staged result is unexpected, stop and tell the parent; do not reset/restore/stash it or continue to commit.
4. Create one focused **main** commit identifying the candidate and reviewer SHA in the commit message. Verify the resulting commit, paths and clean tree; report the main full SHA, checks and remaining blockers, and release the slot. The parent owns the subsequent combined gate; landing alone is not validation completion.

Do not perform source fixes in the shared destination checkout. Combined validation failures return to the owning topic worktree for a new candidate and exact-SHA review. A grant does not authorize extra edits, history rewriting or push. If the parent explicitly uses a temporary integration branch, apply the same checkout/branch/expected-HEAD/clean-index fences there, then separately request main landing of an exact reviewed candidate. Never treat a temporary commit as finished or replay an obsolete candidate.

## Finish and clean every lane

The parent tracks every active, idle or crashed worktree to a final disposition; do not abandon unfinished lanes. Inventory each lane's commits, status (including staged/untracked files), ignored evidence and running jobs. Stop/freeze its writers before reassignment or removal. Preserve dirty work in owner-approved scoped checkpoints and copy needed ignored artifacts (logs, screenshots, handoffs and configuration) to an approved local evidence location outside the worktree before removal; verify preservation without publishing secrets. Resume/reassign unfinished work through scoped checks, exact-SHA review and authorized main squash landing.

After scoped and parent-owned combined validation, request explicit cleanup authorization. Record failures/blockers truthfully: the blocked AVD gate is not a pass, and cleanup must not erase the evidence or manufacture a completion claim. For each worktree/topic ref:

1. Record base/candidate/review SHAs, main landing SHA(s), scoped/combined results and remaining blockers. Verify actual main content or patch-equivalence for **every** relevant commit/delta, including migration checkpoints and previously landed slices. Squash landing does not make topic commits ancestors of main; neither `git branch --merged` nor `git merge-base --is-ancestor` alone proves content accounting. Use scoped diff/patch comparison (for example `git range-diff`/stable patch IDs) plus final-file inspection, explaining any intentional supersession. If anything is unaccounted, preserve the ref/worktree and resolve it; never delete it as merely idle.
2. Confirm no active job, pending work, unpreserved dirty/untracked changes or needed ignored evidence remains. Outside that worktree, recheck path and status, then use `git worktree remove <verified-path>` **without `--force`**. If Git refuses, stop and inspect; do not force or use filesystem deletion.
3. Delete only the specifically verified, accounted topic refs after their worktrees are removed, using a parent-approved expected-tip check (for example `git update-ref -d refs/heads/<topic> <verified-tip>`). Never wildcard-delete refs, delete main, rewrite main history or push. Temporary integration/migration refs also require full content accounting and explicit cleanup approval; keep any ref still needed to preserve evidence or unfinished work.
4. Report removed worktrees/refs, retained evidence paths and any retained blockers. Finished work is in main, not only on a detached/staging/topic branch.

## Current migration and native blocker (2026-11-04)

- Final destination checkout: `/var/home/lsm/repos/spofify-mobile` on `main`; the parent grants its current expected HEAD for each slot. Leads use their assigned worktrees under `/var/home/lsm/repos/spofify-mobile-worktrees/`. Earlier `integration/mobile-ux-review-20261104` commits (including policy staging `8c79539848274f0620006591ce99f107788d1475`) are temporary, not final landing or authorization to merge an obsolete candidate.
- Main/external checkpoint `92111dc7f46581330f5e01ec3cd8c832d317ea30` already captured prior broad UI work. Preserve it as main history while adding authorized focused squash commits; do not rewrite or replay it. Two remaining dirty deltas were checkpointed at `5c63cf3` on `migration/mobile-ux-handoff-20261104`; only the row-test delta belongs to compact-rows and only the palette delta to search-chrome. The local handoff records lane assignments.
- **Actual native AVD after-rendering: BLOCKED / NOT COMPLETE.** The software emulator segfaults before app launch; the single diagnostic budget is exhausted. No emulator startup/configuration retries, ADB/discovery attempts, physical devices, personal AVDs, live-backend/accounts or network diagnosis attempts are authorized in this migration. This native diagnostic stop **does not forbid existing synthetic in-process/loopback unit fixtures** (such as MockWebServer) used by approved tests; it grants no new external network permissions. A new native environment/attempt requires explicit coordination, not an implied exception from the general validation instructions.
- Before-only image: `/var/home/lsm/repos/spofify-mobile/.tooling/ui-testing/reviews/20261104-user-before.png`. Inspect it when relevant, but do not call it an after-render. Production-font/native-graphics Robolectric checks, source checkpoints and clean reviews do **not** waive native validation. Do not re-baseline bad layout to obtain a pass.
- The parent owns combined validation and must report the blocked native gate truthfully. Integration readiness is not visual completion; no lane may claim the rendered app approved without actual current-APK evidence.
