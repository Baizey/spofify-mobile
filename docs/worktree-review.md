# Isolated worktrees, exact-SHA review and leased self-integration

**Finished work must land in `main`.** Each assigned lead uses an actual dedicated Git worktree/topic branch, its own index and outputs. Temporary integration branches are not the destination. Read `AGENTS.md`, relevant [specs](specs/README.md) and any local handoff. Verify absolute worktree, branch, HEAD and `git status --short`; record base SHA and assigned paths. Board metadata records coordination; it is not a lock or permission to edit another scope.

## Standing conditional authority — assigned scope only

The user grants assigned leads **standing conditional self-integration authority** for their assigned scope, and separate shared-cache build-lease acquisition. A lead need not return for an individual parent integration/build grant once all conditions below are met. This supersedes the previous manual parent-slot roundtrip, **not** independent review or serialized access. Assignment is not broad write permission, self-approval, permission for parallel main writes, push/deployment or history rewriting. An unassigned helper/reviewer has no integration authority.

Stop on an unknown/uncoordinated writer, dirty destination, unexpected branch/index/source changes or failed checks. Advisory locks are cooperative: they cannot prevent nonparticipants, IDE/background processes, another clone using a different common directory, or preexisting commands ignoring the lease. Establish that no such writer is active before proceeding; never assume an unlocked file proves safety. The earlier manual slot 12 has finished; there is no inherited slot or background build authority. Check the actual current state on every attempt.

## Preserve isolated work

Inventory commits/dirty/staged/untracked/needed ignored evidence before migration/reassignment. Owners preserve agreed dirty work in scoped checkpoints; never sweep another lane into a commit, lose changes or replay already-captured deltas. Outputs (`app/build`, `core/build`, root build) stay worktree-local. Do not modify shared SDK or sibling checkout configuration. Never reset/revert/stash/restore snapshots, rewrite/amend external work or force cleanup. Do not make source fixes in main.

Temporary artifacts also require explicit ownership and bounded retention under [storage hygiene](storage-hygiene.md). Every lead and descendant must preflight temporary/output quota, use verified owned scratch, preserve required evidence and clean disposable files only after their processes stop. Include generated paths, cleanup and retained/unresolved artifacts in handoffs; the coordinator accounts for abandoned/crashed runs. Worktree isolation and BUILD serialization alone do not prevent shared `/tmp` quota exhaustion.

## Independent exact-SHA review

1. Run scoped checks and `git diff --check`; commit only assigned paths. Record full candidate and validated main-base SHAs, scope and evidence. A source checkpoint is not native acceptance.
2. Obtain a fresh independent **read-only** review of that exact committed candidate against that base. Provide requirements, tests and blockers. Reviewer inspects committed diff/source and relevant real renders; no edits/staging/commits. Before-only screenshots or source/Robolectric results are not native after-render approval.
3. Fix non-nit findings in a new scoped commit, run gates, and re-review the new full SHA until clean. Approval never transfers to changed content or a new base. Record reviewer identity, exact SHA, findings and disposition outside the frozen candidate if necessary to avoid self-reference.
4. Clean source review may permit landing with explicitly blocked native gates, but never claiming rendered/audio/background completion. Feature done requires actual applicable completion evidence.

## Two separate OS advisory leases

`flock` is installed. Resolve the **absolute shared Git common directory**, not a per-worktree gitdir or cwd-relative `.git`. Every participating writer uses these same two persistent lock paths; never unlink/replace lock files (that creates split locks). Locks release with process/descriptor lifetime, including crashes; lock-file existence is not ownership.

```sh
COMMON=$(git rev-parse --path-format=absolute --git-common-dir)
# Run each lease in one shell so its descriptor remains open throughout work.
(
  flock -x 9
  # Main fences, exact-candidate checks, squash inspection and commit HERE.
) 9>"$COMMON/spofify-main.lock"
# Separate shell/lease for Gradle, not inside the main lease:
(
  flock -x 8
  GRADLE_USER_HOME=/var/home/lsm/repos/spofify-mobile/.tooling/gradle \
    ./gradlew <assigned-tasks> --offline --no-daemon --console=plain
) 8>"$COMMON/spofify-gradle.lock"
```

Use finite/nonblocking acquisition if coordination is uncertain; stop/report rather than waiting blindly. No overlapping Gradle builds, including implicitly building native runners. Assigned leads may acquire the build lease themselves, only for own-worktree outputs and the absolute cache above. **Lock order is mandatory:** own-worktree Gradle holds **only BUILD**, releases it before attempting MAIN, and never waits for MAIN while holding BUILD. Root/main post-integration Gradle must freeze main source by acquiring **MAIN first, then BUILD**, hold both throughout the build and release BUILD before MAIN. Never acquire build→main; that can deadlock against main→build and lets compilation read changing main source. Keep main's tree/index/source fenced and unchanged during root validation; no parallel integration. Record command/candidate/results and release; parent/coordinator still owns combined validation accounting. No build is required for pure board/docs foundation changes. See [testing.md](testing.md).

## Main lease: continuously fenced squash transaction

Destination is `/var/home/lsm/repos/spofify-mobile`, branch `main`. Before acquiring, identify the reviewed candidate, **main base validated and reviewed**, scope, checks and independent review identity. Hold exclusive `spofify-main.lock` **continuously from before all destination fences through squash staged inspection, commit and immediate verification**. A brief lock around commit alone is unsafe.

1. Verify destination's exact absolute path, `main` branch, full HEAD equal the validated/reviewed expected base, and completely clean tree/index (`git status --porcelain --untracked-files=all`). Check for unknown writers. Unexpected dirt/branch/index: stop without cleanup. If main has moved, do **not** squash the stale candidate.
2. On moved main, release the lease. In the lead's **own** topic worktree normally merge current main (no rebase/history rewriting). Resolve only owned scoped conflicts; stop/escalate other conflicts. Re-run appropriate gates and obtain independent exact-SHA review of the new candidate against the new base; then reacquire and repeat all fences. Never fix/merge source into main to catch up.
3. Verify topic ref still equals the exact reviewed candidate and review approves that SHA/base/scope; inspect full `git diff <base>..<candidate>` and changed paths. Check dependency/interface tips and account already-landed content. Failures, changed source or unresolved findings stop integration. Review records cannot be replaced by a board status.
4. Run `git merge --squash <exact-candidate>`. Inspect **the entire staged diff**, path list and `git diff --cached --check`; compare to the approved base-to-candidate delta. No unrelated changes or ad-hoc conflict resolution. Unexpected/conflicted stage: stop/report, preserve state and freeze other writers; no reset/stash/revert/restore in main.
5. Commit one focused squash identifying full candidate, base and reviewer identity/reviewed SHA. Verify resulting source/patch accounting, intended paths, commit and clean tree/index while still leased. Record full main SHA and release. Do not label unintegrated work done in anticipation of this step.
6. Run post-integration scoped checks on main; report combined/native gates honestly. If root Gradle is required, retain/reacquire MAIN and then acquire BUILD in that order so main source stays frozen throughout; own-worktree builds use BUILD only and release before integration. If post-checks require fixes, return to the owning topic branch for new checks/review/leased landing. No fixes in main. A separately reviewed scoped follow-up may record verified done metadata after initial integration, avoiding self-referential SHA fiction.

No push, deployment, force operations or history rewriting are authorized. Conditional authority applies only to assigned scope; unowned changes and unknown writers require coordination, not improvisation.

## Evidence, accounting and non-forced cleanup

Assigned leads may finish cleanup for their accounted scope after post-integration checks; the coordinator accounts for every active/idle/crashed lane. Do not delete unfinished work just because a lane is idle.

1. Freeze writers; inventory all topic commits, staged/dirty/untracked files, ignored artifacts/configuration and running jobs. Record base/candidate/review/main SHAs, commands/results, remaining gates and any intentional supersession. Copy needed non-sensitive test/review logs, candidate/recovery Git bundle and patches outside the worktree; verify hashes/bundle validity. Never publish secrets/private data.
2. Verify main content or patch equivalence for **every relevant delta**, including migration checkpoints/previously landed slices. Squash ancestry or `branch --merged` alone proves nothing. Use scoped source comparison, stable patch IDs/range-diff and final-file inspection; explain metadata supersession. Any unaccounted work retains ref/worktree for resolution.
3. Confirm no active writer, pending work or unpreserved dirty/untracked/ignored evidence. From outside the worktree, recheck path and status then `git worktree remove <verified-path>` **without `--force`**. A refusal means stop/inspect, never filesystem deletion. Ignored artifacts must be preserved/handled explicitly, not swept away by force.
4. Delete only a specifically accounted topic ref after removal, with expected-tip checking, e.g. `git update-ref -d refs/heads/<topic> <verified-tip>`. Never wildcard-delete or delete main. Report removed worktree/ref, retained evidence and honest blockers.

## Current native blocker and historical accounting (2026-11-04)

- Starting foundation base is `4183ed3ba98d4cea77c116ca9311c7eb2696fe20`; always fence actual expected main, never reuse this as a permanent grant. The device topic at `77d45be` had merged main `4183ed3` **into the device branch**, not the reverse: that device implementation was **not integrated into main `4183ed3`**, nor shipped by the foundation landing. It is existing topic-branch work, not a new unimplemented epic; recovery owns its remaining review/validation work, with the historical checkpoint recorded (see [FEAT-004](specs/FEAT-004-native-device-participation.md)).
- External checkpoint `92111dc7f46581330f5e01ec3cd8c832d317ea30` captured prior broad UI work. Migration `5c63cf3` captured row-test (compact-rows) and palette (search-chrome) deltas. Preserve main history; do not replay. Earlier integration staging, including `8c79539848274f0620006591ce99f107788d1475`, is not permission to merge obsolete source.
- **Native AFTER rendering/audio/background: BLOCKED / NOT COMPLETE.** Isolated software emulator segfaulted before app launch; single diagnostic budget exhausted. No more emulator/configuration/startup, ADB/discovery, real-phone/personal-AVD/live-account/backend or external-network attempts. Existing approved synthetic in-process/loopback unit fixtures remain allowed; this grants no external networking. A new environment/attempt needs explicit coordination. [BUG-001](specs/BUG-001-native-validation-environment.md) gates completion/validation, never blanket source work.
- Before-only image `/var/home/lsm/repos/spofify-mobile/.tooling/ui-testing/reviews/20261104-user-before.png` is not approved after-render evidence. Native-graphics tests, APKs, clean source review and landing cannot waive rendered acceptance. Parent/coordinator reports combined/native gates truthfully.
