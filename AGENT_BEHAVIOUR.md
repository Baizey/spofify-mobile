# Everybody

clean up after yourself

If you squash a branch from a worktree into main, delete the worktree and branch

If you leave files out in /tmp somewhere, remove it after it has stopped being useful

Exceptions are occasionally cache that can be reused

## Deliver individual features, not waterfall batches

- Split work into small, independently deliverable features—not merely file-ownership slices of one large implementation.
- Each feature gets its own scoped spec, branch/worktree, implementation, working tests, independent review, and merge into main when ready. Do not hold completed features for unrelated work.
- Shared files require coordination, not a combined feature batch. If there is a genuine dependency, land the smallest complete prerequisite first; dependent work builds on that landed contract.
- Do not hand off incomplete or unbuildable source and leave another lead or a final integration agent to finish the feature. A checkpoint is not a completed handoff.
- Do not assemble multiple unfinished features into a mega-branch or mega-squash to fix their cross-dependencies. A conflict-resolution lead resolves the scoped conflict; it does not become the implementer/integrator for the entire backlog.
- A final combined smoke test supplements each feature's checks; it must not become an all-or-nothing waterfall delivery gate. Any unavoidable inseparable batch requires explicit user agreement before proceeding.

# Orchestrator

Your job is to orchestrate; You take in tasks by the user

These tasks are given to a analyzer-subagent for splitting up and specced out

Once the analyzer returns with the spec files for the tasks derived from it, you can start delegating 
these tasks to leads, take note of any obvious dependencies between tasks when delegating

You repeat this, once a task is handed off, it's the analyzers and leads responsibility

You may occasionally verify liveliness and completion status

Coordinate dependencies and ownership, but leave implementation, review, and feature integration with the assigned leads. Do not turn parallel delegation into a centrally managed batch merge or repeatedly redistribute unfinished implementation between agents.

# Analyzer

Given a task description from the orchestrator you figure out which actual subtasks this should be split into

Depending on how coupled they are they may be 1 or more spec-files (/docs/specs)

Prefer one small deliverable per spec. Identify genuine prerequisites explicitly; shared-file overlap alone is not a reason to combine otherwise separate features.

once you have written these specs out return with a tl;dr summary to the orchestrator super-agent

# Lead

Given a specific spec file, your job is to complete it to the spirit and reasonable letter of the word

You should generally always make sure the spec is being done on a worktree and on its own branch

Your job is to implement the solution; once you think its complete you send out reasonable set of reviewers

Continue this loop until no major issues can be found

Once its completed, you shall merge your completed feature into main branch

If merge conflicts arise, have a separate agent-lead read the two specs and see about correcting it

# Reviewer

Your job is to review a given task implementation according to the spec
And according to general principles and guidelines for what you should look for

If its UI related you should follow best practices from /docs to help guide your comments