# Temporary storage, quota failures and cleanup

## Standing requirement — coordinator and every lead

The user requires agents to clean up after themselves **and ensure their subagents do the same**. Temporary storage is a shared resource: serialized Gradle alone does not prevent accumulated Robolectric runtime extractions from exhausting a quota. This policy applies to builds, unit tests, component captures, diagnostics and recovery artifacts. It does not authorize native execution under [BUG-001](specs/BUG-001-native-validation-environment.md).

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

Every assignment must explicitly include this policy and require the child to read `AGENTS.md` and this document. Pi.lot children do not automatically load ambient repository instruction files; supplying a path is not proof it was read. Name scratch ownership, resource budget, allowed evidence retention and cleanup responsibility in the assignment.

Every lead's final handoff must report:

1. Generated temporary/output paths and ownership, including delegated descendants.
2. Checks performed and any quota/headroom uncertainty.
3. Cleanup actually completed after processes stopped, with approximate reclaimed size when measurable.
4. Evidence deliberately retained, its location and any unresolved cleanup/blocker.

The coordinator verifies the combined accounting. A completed task or an idle agent is not proof of cleanup. Do not mark cleanup complete merely because a process exited or a directory was moved.

## Incident learning — 2026-11-04

### Confirmed in this investigation

- Linux errno **122** is **EDQUOT: Disk quota exceeded**. Pi/Node surfaced it as `Unknown system error -122, write`; this is distinct from ordinary disk-full `ENOSPC`.
- `~/.pi/agent/crashes.json` records three Pi **1.5.0** uncaught write exceptions on 2026-11-04 at **16:38:05, 16:41:00 and 16:42:36 UTC**. Their stacks identify only the write error, not a specific file/caller.
- The installed `dist/core/tools/output-accumulator.js` calls `stream.write(...)` during `append`/`ensureTempFile`, but attaches its stream error listener only in `closeTempFile`. `dist/utils/output-files.js` creates that stream in `os.tmpdir()` without an earlier listener. A streaming quota error therefore has a credible unhandled-error path. This is source inspection, **not** an intentional crash reproduction or proof that this writer caused every recorded crash. No harness patch was made.
- A tool in this session also failed to save truncated diagnostic output with errno -122, demonstrating that the impact was not limited to terminal presentation.

### Historical report versus current measurement

The preceding investigation reported `/tmp` mounted with `usrquota`, **146 Robolectric native-runtime directories totaling about 23.1 GiB**, and a runtime write roughly 100 ms before the crash. These are incident-time reported findings, not a permanent current count or independently re-established timing correlation here.

The bounded follow-up found **23** `/tmp/robolectric-nativeruntime*` directories totaling **4,092,207,104 allocated bytes (about 3.81 GiB)**. Its sandbox view showed `/tmp` at about **5.5 GiB used / 26 GiB available**, while the home disk had terabytes free. `findmnt` exposed a FUSE-mediated view and `quota -s` did not provide a usable quota report, so current host per-user quota headroom was **not established**. No runtime directories were deleted by this investigation; counts may change as other work runs.

### Operational conclusion

Accumulated temporary runtime extractions are a credible quota-pressure source, and Pi's streamed-output error handling is a separate robustness concern. Prevent recurrence through bounded owned scratch and verified cleanup, not repeated builds or a blanket `/tmp` purge. Quota recovery does not prove the harness defect is fixed. If a crash report is desired, Pi's interactive `pi -r` then `/bug` workflow attaches the saved crash details; do not claim a report was submitted automatically.
