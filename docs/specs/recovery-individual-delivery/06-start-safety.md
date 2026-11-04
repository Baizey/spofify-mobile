# Recovery 06 — preserve owned asynchronous-start cleanup

This is an existing adjacent correctness correction discovered in the prior work, **not another requested polish outcome**. Preserve/deliver independently as the smallest complete shared prerequisite instead of burying it in media or throwing it away.
Branch suggestion: `recover/playback-start-safety`. No prerequisite. Exact extraction: [00-extraction](00-extraction.md), section 06; source R, principally C-origin start-attempt blocks.

## Scope
- Only service start-attempt identity/guards, import forwarding with source-compatible default, attempt resets and own-output/focus/foreground cleanup on rejected/cancelled/failed start.
- An old completion must never pause/stop/abandon focus/demote a newer attempt, imported queue or successor service. Rejected snapshots must reflect actual output, not a paused copy hiding playing audio.
- Recover PlaybackStartAttemptTest as a standalone fixture. No list-action enums/manual tier, media-key trust, remote projection, Search/UI or protocol change.

## Minimum acceptance / blockers
- Exercise real start coroutine held at readiness and after raw Play, guard failure/cancellation, foreground/focus/media failure and successor start/import/service with same-valued authority/current occurrence.
- Keep existing startup/restoration/focus/history/close regressions; never autoplay on restoration or focus gain. Defaulted signature must compile all baseline callers.
- Full leased offline gate, independent exact-SHA security/correctness review and applicable isolated synthetic native start/cancel/background observation. State exact limits; controlled JVM output is not acoustic evidence.
- This lead completes/tests/reviews/merges its own correction. 07/08 then consume the **landed** small contract, not an unfinished checkpoint. No new mega-integration role. R's tests/behavior are unvalidated; clean extraction alone is not approval.
