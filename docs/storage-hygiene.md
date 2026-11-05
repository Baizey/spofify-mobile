# Storage practices — compatibility pointer and historical note

Retained for instructions in historical task records. Current storage, BUILD/NATIVE ownership, budgets, delegation accounting and cleanup practices live in hmem: **Build/native ownership, storage and cleanup**, project `c49a1930-06b9-46bf-96ae-882f60f2fe6b` in the Mobile workspace. Use the [hmem bootstrap](../AGENTS.md#hmem-bootstrap) and read its current details; do not maintain policy here.

## Historical incident note — not current environment evidence

A 2026-11-04 investigation reported accumulated Robolectric runtime extractions and write failures surfaced as `Unknown system error -122, write`. Linux errno **122** is **EDQUOT: Disk quota exceeded**, distinct from disk-full `ENOSPC`. Temporary-output capture can fail too. Incident-time directory counts, free-space readings and harness-source observations do not establish today's quota headroom or a single proven crash cause. This note preserves source context, not a claim that any harness defect was fixed.
