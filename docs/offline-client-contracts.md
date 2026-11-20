# Offline client contract slice

`core.offline.OfflineClient` is an account/session-fenced client for the documented native offline v1 API. It is not a downloader, saved-music repository, offline authentication state or local player. No offline mode or download-ready UI is enabled by this slice.

## Implemented

- Complete playlist/album/artist manifest reads. Every page must retain its collection metadata, fence, counts and limit. Positions must be contiguous, occurrence IDs unique, and cursors advancing. Only the complete snapshot is returned. Manual duplicate track occurrences are preserved.
- Valid playlist manifest pages reporting fewer recorded-audio occurrences than total occurrences fail as `SOURCE_UNAVAILABLE`. This covers both playlist kinds on servers that include unavailable entries; no playable prefix is returned or staged.
- Local default rejection limits of 16 MiB aggregate manifest responses and 100,000 occurrences. These are configurable client bounds, not server catalog-size guarantees; exceeding either rejects the whole read rather than returning a misleading prefix.
- Current media descriptor preparation: expected track, version/digest agreement, positive length, coordinated format/MIME and the exact documented relative download route. A descriptor neither authorizes arbitrary URLs nor proves downloaded bytes or codec playability.
- Exact immutable six-field offline receipt submission and matching ACK validation. IDs and actual-progression evidence must come from a future durable recorder/outbox. This client generates neither and never retries a mutation itself.
- Shared bounded strict-JSON structural validation, retaining each consumer's own byte, depth and domain limits. Responses with duplicate/escaped-alias keys, invalid UTF-8 or excessive nesting are rejected.

Successes and failures both check coroutine activity and the live account/session identity before leaving the client. Credentials remain in SessionRepository. Wire diagnostics are not exposed to presentation.

## Not yet implemented

Verified streaming transfers, ETag/Content-Range checks, private durable storage, crash-safe promotion, shared-track retention, download cancellation/retry/progress, disk-budget policy, offline startup/access grants, local-only playback, receipt recording/outbox and upload scheduling remain separate work. Those owners must be implemented before Saved music or offline mode is exposed as working.

Offline GET transport retains the default suppression of HTTP 408/421/503 replay and discards those bodies. A bodyless 503 is reported as unavailable, not guessed to be a particular media/evaluation timeout. The separate Search client explicitly opts into bounded JSON 503 codes; that opt-in does not change offline or media reads. No backend probe or fixture execution was used for this slice; verification is local JVM contract/fencing tests and Android compilation.
