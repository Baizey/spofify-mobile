# Isolated worktrees, exact-SHA review and self-integration

**Finished work must land in `main`.** Each assigned lead uses an actual dedicated Git worktree/topic branch, its own index and outputs. Temporary integration branches are not the destination. Read `AGENTS.md`, `AGENT_BEHAVIOUR.md`, assigned task specs when present under `docs/specs/`, and any local handoff. Verify absolute worktree, branch, HEAD and `git status --short`; record base SHA and assigned paths. Task metadata records coordination; it is not a lock or permission to edit another scope. Do not recreate deleted historical specs or board scaffolding.

## Standing conditional authority — assigned scope only

The user grants assigned leads **standing conditional self-integration authority** for their assigned scope, and separate shared-cache build-lease acquisition. Assignment is not broad write permission, self-approval, permission for conflicting main writes, push/deployment or history rewriting. An unassigned helper/reviewer has no integration authority.

Use **normal Git internal locking**, not a custom Git/MAIN guard, flock, semaphore or persistent reservation, for worktree administration, local commits, refs and integration. Briefly communicate root writes with other leads/the parent; coordinate against any root build before changing its source. This is communication, not another lock protocol or a prerequisite for independent local commits. Stop on an unknown/uncoordinated writer, dirty destination, unexpected branch/index/source changes or failed checks; never overwrite another lane's work. Check the actual state on every attempt.

## Preserve isolated work

Inventory commits/dirty/staged/untracked/needed ignored evidence before migration/reassignment. Owners preserve agreed dirty work in scoped checkpoints; never sweep another lane into a commit, lose changes or replay already-captured deltas. Outputs (`app/build`, `core/build`, root build) stay worktree-local. Do not modify shared SDK or sibling checkout configuration. Never reset/revert/stash/restore snapshots, rewrite/amend external work or force cleanup. Do not make source fixes in main.

Temporary artifacts also require explicit ownership and bounded retention under [storage hygiene](storage-hygiene.md). Every lead and descendant must preflight temporary/output quota, use verified owned scratch, preserve required evidence and clean disposable files only after their processes stop. Include generated paths, cleanup and retained/unresolved artifacts in handoffs; the coordinator accounts for abandoned/crashed runs. Worktree isolation and BUILD serialization alone do not prevent shared `/tmp` quota exhaustion.

## Independent exact-SHA review

1. Run scoped checks and `git diff --check`; commit only assigned paths. Record full candidate and validated main-base SHAs, scope and evidence. A source checkpoint is not native acceptance.
2. Obtain a fresh independent **read-only** review of that exact committed candidate against that base. Provide requirements, tests and blockers. Reviewer inspects committed diff/source and relevant real renders; no edits/staging/commits. Before-only screenshots or source/Robolectric results are not native after-render approval.
3. Fix non-nit findings in a new scoped commit, run gates, and re-review the new full SHA until clean. Approval never transfers to changed content or a new base. Record reviewer identity, exact SHA, findings and disposition outside the frozen candidate if necessary to avoid self-reference.
4. Clean source review may permit landing with explicitly blocked native gates, but never claiming rendered/audio/background completion. Feature done requires actual applicable completion evidence.

## Shared-resource guards and advisory leases

Resolve `COMMON=$(git rev-parse --path-format=absolute --git-common-dir)`: the **absolute shared Git common directory**, not a per-worktree gitdir or cwd-relative `.git`. Installed pi.lot `docs/security.md` and `docs/policy.md` state that backing-file POSIX/`flock` locks do **not** coordinate with host processes or separate Bash mounts. Thus `flock` alone is insufficient here. Outside that harness, ordinary OS `flock` remains a cooperative descriptor-lifetime lock; neither mechanism excludes nonparticipants. Shared-cache corruption was observed, but concurrency is only a plausible cause, not an established one.

For **BUILD and NATIVE only**, every participating writer, including host-side writers, must acquire **both** the host-atomic directory guard and the persistent exclusive flock. Git/main operations are not guarded resources:

| Resource | Guard under `$COMMON` | Persistent flock under `$COMMON` |
| --- | --- | --- |
| BUILD: shared Gradle cache/build activity | `spofify-build.guard` | `spofify-gradle.lock` |
| NATIVE: shared AVD/ADB/test ports and runner state | `spofify-native.guard` | `spofify-native.lock` |

1. Acquire the guard first with a **successful atomic `mkdir "$COMMON/<resource>.guard"`**, never `mkdir -p` or a check-then-create. Existing guard means busy, not ownership: use a bounded wait/retry or stop/report; do not steal, rename or remove another owner's guard. Other acquisition failures also stop work.
2. After successful creation, record a unique owner/run token in the guard with worktree, candidate and command identification; retain that token in the owner's evidence. Then acquire the corresponding flock with a finite/nonblocking wait (for example `flock -x -w 60 <fd>`), keeping its descriptor open in the working shell. No protected work starts until **both** acquisitions succeed and uncoordinated writers have been excluded.
3. Keep both throughout the protected operation and all descendant writers. Before release, confirm the actual Gradle/daemon or native/ADB/emulator writers for that operation have stopped; launcher exit, timeout or cancellation alone does not prove shutdown. Release the flock, then compare the guard's recorded token to the retained own token and remove only that matching own record and empty guard directory. Never unlink/replace persistent lock files: that creates split locks; their existence is not ownership.
4. On interruption, uncertain child shutdown, missing/mismatched token or unknown ownership, preserve the guard and evidence and stop/report for **explicit coordinated recovery**. Age, PID absence in one tool namespace and apparent inactivity do not authorize stale-guard removal. A failed flock acquisition permits only the same owner-checked guard release after confirming no protected writers started/remain; no unconditional cleanup trap.

**Build/native separation is mandatory** (each resource means guard **plus** flock):

- All Gradle runs, including root/main postchecks, hold **BUILD only**, for the absolute cache in [testing.md](testing.md). Own-worktree outputs stay local. No overlapping builds, including implicit runner builds.
- Before a root build, coordinate with the parent/other leads so its source is not mutated during compilation; verify the intended SHA and clean source/index before and after. Do not hold a custom MAIN/Git lock through a build. A changed SHA/source invalidates the intended-candidate result and requires reporting/revalidation, not a green claim.
- Native validation runs **standalone after releasing BUILD**, reusing exact APKs with `--skip-build` under **NATIVE only**; do not nest it inside BUILD. Retain NATIVE until the runner's actual native processes stop.

Record ownership, command/candidate/results and release/recovery status. Parent/coordinator still owns combined validation accounting; these shared-resource requirements do not batch individual feature delivery. No build or native run is required for documentation-only changes that do not affect app behavior. See [testing.md](testing.md).

## Main workflow: reviewed squash with normal Git locking

Destination is `/var/home/lsm/repos/spofify-mobile`, branch `main`. Identify the reviewed candidate, **main base validated and reviewed**, scope, checks and independent review identity. Briefly coordinate the root-write timing with other leads/the parent, including any active root build; use normal Git internal locking throughout, with no custom guard/flock or persistent reservation. Local commits/worktree/ref operations do not wait for a MAIN resource.

1. Verify destination's exact absolute path, `main` branch, full HEAD equal the validated/reviewed expected base, and completely clean tree/index (`git status --porcelain --untracked-files=all`). Check for unknown writers. Unexpected dirt/branch/index: stop without cleanup. If main has moved, do **not** squash the stale candidate.
2. On moved main, stop the integration attempt. In the lead's **own** topic worktree normally merge current main (no rebase/history rewriting). Resolve only owned scoped conflicts; stop/escalate other conflicts. Re-run appropriate gates and obtain independent exact-SHA review of the new candidate against the new base; then recheck destination state before integrating. Never fix/merge source into main to catch up.
3. Verify topic ref still equals the exact reviewed candidate and review approves that SHA/base/scope; inspect full `git diff <base>..<candidate>` and changed paths. Check dependency/interface tips and account already-landed content. Failures, changed source or unresolved findings stop integration. Review records cannot be replaced by task status metadata.
4. Run `git merge --squash <exact-candidate>`. Inspect **the entire staged diff**, path list and `git diff --cached --check`; compare to the approved base-to-candidate delta. No unrelated changes or ad-hoc conflict resolution. Unexpected/conflicted stage: stop/report, preserve state and freeze other writers; no reset/stash/revert/restore in main.
5. Commit one focused squash identifying full candidate, base and reviewer identity/reviewed SHA. Verify resulting source/patch accounting, intended paths, commit and clean tree/index immediately afterward. Record full main SHA. Do not label unintegrated work done in anticipation of this step.
6. Run post-integration scoped checks on main; report combined/native gates honestly. Root Gradle uses **BUILD only**, with source-mutation coordination and intended-SHA/clean-source checks before and after as above. If post-checks require fixes, return to the owning topic branch for new checks/review/landing. No fixes in main. A separately reviewed scoped follow-up may record verified done metadata after initial integration, avoiding self-referential SHA fiction.

No push, deployment, force operations or history rewriting are authorized. Conditional authority applies only to assigned scope; unowned changes and unknown writers require coordination, not improvisation.

## Evidence, accounting and non-forced cleanup

Assigned leads may finish cleanup for their accounted scope after post-integration checks; the coordinator accounts for every active/idle/crashed lane. Do not delete unfinished work just because a lane is idle.

1. Freeze writers; inventory all topic commits, staged/dirty/untracked files, ignored artifacts/configuration and running jobs. Record base/candidate/review/main SHAs, commands/results, remaining gates and any intentional supersession. Copy needed non-sensitive test/review logs, candidate/recovery Git bundle and patches outside the worktree; verify hashes/bundle validity. Never publish secrets/private data.
2. Verify main content or patch equivalence for **every relevant delta**, including migration checkpoints/previously landed slices. Squash ancestry or `branch --merged` alone proves nothing. Use scoped source comparison, stable patch IDs/range-diff and final-file inspection; explain metadata supersession. Any unaccounted work retains ref/worktree for resolution.
3. Confirm no active writer, pending work or unpreserved dirty/untracked/ignored evidence. From outside the worktree, recheck path and status then `git worktree remove <verified-path>` **without `--force`**. A refusal means stop/inspect, never filesystem deletion. Ignored artifacts must be preserved/handled explicitly, not swept away by force.
4. Delete only a specifically accounted topic ref after removal, with expected-tip checking, e.g. `git update-ref -d refs/heads/<topic> <verified-tip>`. Never wildcard-delete or delete main. Report removed worktree/ref, retained evidence and honest blockers.

## Native validation boundary

Normal isolated synthetic AVD validation is allowed under [ui-validation.md](ui-validation.md). Earlier emulator failures are historical evidence, not a permanent prohibition or proof of current environment health; see [testing.md](testing.md#native-evidence-status). Source review, APK builds, component images and landing do not waive rendered/audio/background acceptance. Report the actual candidate's evidence and remaining limitations. Any genuinely hardware-specific check goes through the user; agents do not target physical phones, personal AVD data or live accounts/backends.
