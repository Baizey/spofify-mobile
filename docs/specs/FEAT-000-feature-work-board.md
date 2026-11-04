# FEAT-000 — Mobile task-tracking foundation

## Tracking

- ID: FEAT-000
- Status: in-progress
- Phase: implementation
- Spec readiness: ready
- Owner: foundation-lead
- Branch: docs/mobile-feature-board
- Worktree: /var/home/lsm/repos/spofify-mobile-worktrees/spec-foundation
- Depends on: none
- Discovery depends on: none
- Touchpoints: docs/specs/**, scripts/specs.py, scripts/test_specs.py, AGENTS.md, README.md, docs/worktree-review.md, docs/testing.md lease guidance, .gitignore Python caches, .github/workflows/android.yml existing-CI stdlib board gate
- Next action: gate and independently review exact candidate; self-squash under main lease; post-check and separately review verified completion record

## User problem and outcomes

The user requests a small repository-owned spec/task system **before new feature implementation**, similar to the read-only sibling backend's board. Leads should see what is assigned, genuinely runnable, shared or blocked without a hosted service/database or a stale metadata lock. Kotlin/Compose/Media3 application behavior must not change; Python is development tooling only.

## Scope and decisions

- Markdown metadata is the only status source, stable mobile FEAT/BUG IDs independent of backend IDs.
- todo/in-progress/review/blocked/done, discovery versus implementation, draft versus accepted ready. Done requires reviewed integration and post-verification, never a speculative candidate.
- Read-only standard-library CLI `board|ready|check`, `--json`, script-relative default root and explicit fixture `--root`. Validate full inventory before any output of tasks.
- Strict malformed/unknown/duplicate metadata, IDs/dependencies/cycles and active ownership validation. Hard dependencies are distinct from touchpoint ordering and native completion gates.
- Existing Android CI gets only a Python stdlib tests/check step; no new actions/dependencies or Android SDK/BOM/task/artifact changes. Historical worktree paths are metadata, not filesystem existence checks (including in CI).
- No runtime code, task claiming, hosted system, third-party packages, network/build/device commands or backend modifications. Sibling docs/tool/tests were read in full before adaptation.
- Update workflow to written assignment-bound conditional self-integration, exact-SHA independent review and separate cooperative OS main/Gradle leases. No self-approval/parallel merges/main fixes/history rewriting/push/deploy.

## Acceptance criteria

- AC-01: Deterministic board shows status/phase/owner/waits; JSON includes metadata, runnable and waiting. Verify synthetic CLI fixtures and initial real inventory.
- AC-02: Ready lists only eligible todo tasks; discovery draft means ready to assign discovery, not implementation. Current-phase prerequisites must be done and implementation spec ready. Test active/review/blocked/done/draft/dependency negative cases.
- AC-03: Invalid or empty inventories fail closed across all commands; malformed/unknown/duplicate/missing fields/IDs, missing/self/cyclic/duplicate dependency references and invalid ownership/state are rejected. Unit/CLI negative tests.
- AC-04: All commands are read-only, stdlib-only, callable from any cwd, support explicit roots. Verify subprocess runs, deterministic outputs and byte/mode/mtime fixture equality under read-only fixture permissions.
- AC-05: Initial 001–003 faithfully preserve user outcomes as todo/discovery/draft; 004 records existing active recovery rather than a new feature; BUG-001 records native validation stop without hard-blocking safe source work. Independent contract review.
- AC-06: Written standing conditional authority preserves exact-candidate review, continuously locked destination fences/squash inspection/commit, changed-base own-branch normal merge/re-gate/re-review, separate builds and evidence/accounted non-forced cleanup. Independent workflow review.

## Risks and recovery

| ID | Failure / hidden risk | Expected safe behavior | Verification |
| --- | --- | --- | --- |
| R-01 | Malformed/stale metadata advertises unsafe work | Validate entire inventory; no partial task output; assignment still explicit | Negative unit/CLI fixtures; policy review |
| R-02 | Board or advisory lock mistaken for scheduler/absolute protection | No automatic claims; cooperative-lock limits, stop on unknown writer | Source and workflow review |
| R-03 | Discovery/merge mistaken for completion | Separate phase/readiness/status; no done until verified main; native gaps explicit | State tests, inventory review, completion follow-up |
| R-04 | Shared-file overlap creates fake epic dependencies | Record extraction/row/drag handoffs and owners, not whole-feature prerequisites | 001–003 cross-spec review |
| R-05 | Main moves or frozen review is silently reused | Release lease, normal-merge own branch, re-gate/re-review exact new SHA | Transaction guidance and actual integration record |

## Validation and completion gates

`python3 -m unittest discover -s scripts -p 'test_specs.py' -v`; each CLI command in text/JSON; outside-cwd invocation; read-only fixture tests; `git diff --check`; independent exact-SHA review, corrections and re-review; leased main squash; post-integration scoped checks and source/patch accounting. No Gradle/AVD needed: no application/native/runtime changes. Native AFTER/audio/background remains **BLOCKED / NOT COMPLETE** under BUG-001; this tool does not satisfy or reopen that gate.

## Evidence and delivery log

Assignment/base: `4183ed3ba98d4cea77c116ca9311c7eb2696fe20`, dedicated branch/worktree above. Required mobile guidance and sibling backend docs/specs README, template, FEAT-000 plus scripts/specs.py and test_specs.py were read in full (paginated tool/test reads). Tool/tests adapted without backend modification or execution. Candidate not yet reviewed/integrated: deliberately not done. Exact review/test/candidate/main evidence will be archived outside this worktree; a separately reviewed follow-up can record actual verified completion without self-referential SHA claims.
