# FEAT-NNN — Feature title

## Tracking

- ID: FEAT-NNN
- Status: todo
- Phase: discovery
- Spec readiness: draft
- Owner: unassigned
- Branch: unassigned
- Worktree: unassigned
- Depends on: none
- Discovery depends on: none
- Touchpoints: not yet mapped
- Next action: assign discovery

## User problem and outcomes

Separate explicit requests from recommendations/assumptions. Describe observable user benefit. Mobile IDs are independent of backend IDs.

## Current behavior and evidence

Name source/docs/tests and exact snapshots where known; distinguish observed evidence from unverified rendering/audio/background behavior. Do not claim an existing feature is absent without checking it.

## Scope and decisions

Semantics, non-goals, account/authority boundaries, reversible decisions and open user decisions. Discovery/draft is not permission to implement.

## Delivery slices and touchpoint handoffs

Name owned files/interfaces, concrete subtasks, shared-file barriers and exit gates. Only real hard prerequisites belong in metadata; overlap and validation gates belong here. Keep independently useful work unblocked.

## Acceptance criteria

- AC-01: Observable outcome and named automated/manual verification.

## Risks and recovery

| ID | Failure / hidden risk | Expected safe behavior | Verification |
| --- | --- | --- | --- |
| R-01 | Cancellation / stale or concurrent state | No stale mutation; bounded recovery | Named focused fixture |

Cover applicable occurrence identity/duplicates/derived contexts, session/authority, offline/uncertain writes, lifecycle/navigation, accessibility/font/insets, performance and privacy. Explain exclusions; avoid generic promises.

## Validation and completion gates

Map acceptance/risk IDs to tests, measured budgets and actual native render/interaction evidence. Use synthetic fixtures only. Record BUG-001 as completion/validation gate, not a blanket source dependency. Never claim blocked native AFTER/audio/background checks passed. Include exact-SHA independent review, leased main landing and post-checks per [worktree-review.md](../worktree-review.md).

## Open questions

Owner, investigation/decision, affected slice and recommendation. Unresolved material questions prevent implementation-ready acceptance, not all useful discovery.

## Evidence and delivery log

Initially discovery only, no implementation/integration/deployment claim. Later append real commands/results, exact source/review/main SHAs, evidence paths, limitations and cleanup accounting. Final approval SHA belongs in an external record or separately reviewed follow-up, never self-referential fiction. Do not mark done before verified integration.
