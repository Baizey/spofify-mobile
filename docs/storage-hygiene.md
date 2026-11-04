# Temporary storage, quota failures and cleanup

## Standing requirement — coordinator and every lead

The user requires agents to clean up after themselves **and ensure their subagents do the same**. Temporary storage is a shared resource: serialized Gradle alone does not prevent accumulated Robolectric runtime extractions from exhausting a quota. This policy applies to builds, unit tests, native AVD validation, component captures, diagnostics and recovery artifacts. Follow [ui-validation.md](ui-validation.md) for isolated synthetic AVD safety checks.

### Before artifact-heavy work

- Check the temporary and output filesystems, available bytes/inodes and any visible user/project quota. Check `/tmp` separately from the home/repository disk. `df` free space does not establish per-user quota headroom, and sandbox/FUSE views may hide the host mount or quota; report that uncertainty rather than claiming a pass.
- Identify a unique, explicitly owned per-run scratch directory, the run/worktree/owner, expected storage budget and cleanup responsibility before starting. Bound concurrent jobs, runtime extractions, logs and captures. Reuse only supported caches under their existing leases; do not alter shared SDK/cache configuration or delete caches blindly.
- Configure the processes that actually create temporary files. `TMPDIR` alone does **not** reliably redirect Java/Robolectric. Set and verify the test JVM's `java.io.tmpdir` through supported runner configuration; changing only the Gradle launcher's property may miss forked test JVMs. Moving scratch requires checking the destination's quota too, not merely shifting unbounded growth elsewhere.
- Keep tool output bounded. Pi can spill long tool output to OS-temp files, so a full `/tmp` quota can break diagnostic capture as well as tests. Store necessary bounded logs in the owned, quota-checked evidence location and return a short summary/path instead of flooding the tool stream.

### During work and on exit

- Monitor actual scratch growth against the declared budget. Stop artifact-producing work on unexpected growth, `EDQUOT` or `ENOSPC`; preserve small diagnostics and investigate before retrying. Do not reproduce quota exhaustion in the active Pi process or assume a larger limit is the fix.
- Arrange cleanup on success, failure and cancellation. A crash can bypass traps/finalizers: the coordinator must account for abandoned runs and idle/crashed descendants explicitly.
- Before removal, verify the exact owned path and that its processes/descendants have stopped. Preserve required source, test reports, relevant captures, review records and minimal diagnostic/recovery evidence outside scratch; verify those retained files. Follow [worktree evidence/accounting rules](worktree-review.md#evidence-accounting-and-non-forced-cleanup) for source/worktree cleanup.
- Remove only verified disposable artifacts owned by that run. Never blanket-delete `/tmp`, use broad `robolectric*` deletion, follow symlinks outside owned scratch, delete active files or sweep another lane's evidence/cache. Legacy unowned directories need ownership/liveness inventory and explicit coordination first.
- Retain the small evidence needed for review/recovery, not every redundant runtime extraction or duplicate archive indefinitely. Record intentional retention and remaining cleanup; do not discard unfinished work to save space.

### Delegation and handoff contract

Every assignment must explicitly include this policy and require the child to read `AGENTS.md` and this document. Do not assume delegated agents have read ambient repository instructions; supplying a path is not proof it was read. Name scratch ownership, resource budget, allowed evidence retention and cleanup responsibility in the assignment.

Every lead's final handoff must report:

1. Generated temporary/output paths and ownership, including delegated descendants.
2. Checks performed and any quota/headroom uncertainty.
3. Cleanup actually completed after processes stopped, with approximate reclaimed size when measurable.
4. Evidence deliberately retained, its location and any unresolved cleanup/blocker.

The coordinator verifies the combined accounting. A completed task or an idle agent is not proof of cleanup. Do not mark cleanup complete merely because a process exited or a directory was moved.

## Historical incident lesson

A 2026-11-04 investigation reported accumulated Robolectric runtime extractions and write failures surfaced as `Unknown system error -122, write`. Linux errno **122** is **EDQUOT: Disk quota exceeded**, distinct from disk-full `ENOSPC`. Temporary-output capture can fail too, so keep diagnostic output bounded. Incident-time directory counts, free-space readings and harness-source observations do not establish today's quota headroom or a single proven crash cause.

The durable lesson is bounded owned scratch, current capacity/quota checks and verified cleanup—not repeated builds, moving unbounded growth elsewhere or a blanket `/tmp` purge. Safe storage recovery does not by itself prove any harness defect fixed. Preserve small relevant evidence and report current uncertainty.
