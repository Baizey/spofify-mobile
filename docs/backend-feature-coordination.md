# Listener backend feature coordination

## Status and ownership

Proposal only: coordinate contracts with the backend owner before implementing new
production requests, DTO assumptions or backend-dependent UI claims.
Backend inspection snapshot: spofify-go `10cc74c`; deployed capability is unverified.
Spec tracking can lag code. A discovery document or benchmark is not a shipped API.
This document does not change `api-contract.md` or the parent-owned parity plan.

Native catalog/search/playlists/player/devices lanes may proceed in parallel.
Future-feature lanes can prepare transport-free UI, bounded parsers and synthetic
contract fixtures, but must not enable speculative requests or fake results.
Backend implementation, migrations, provider enrollment and runtime installation
belong to separately coordinated backend work.

## Product boundary

- Default destinations: Home, query-required Search, and personal Playlists.
- Home uses bounded recent listening, Liked songs/count and personal playlists.
- Do not make a giant global Library listing the default experience.
- Open artist/album details intentionally from results, credits or saved music.
- Empty Search must not issue match-all search reads; retain focused detail or Home.
- There is no shipped recommendation engine: no fake Spotify recommendations.
- Playback belongs to MediaSessionService; Pause/Collapse retain the queue.
- Support configured HTTP LAN/Tailscale literal-IP origins as well as HTTPS.
  Hostname/public-HTTP admission needs backend coordination, not silent HTTPS-only
  client policy. HTTP exposes credentials/session traffic without encryption.
- Use the exact-origin authenticated client for API, artwork and audio ranges;
  never forward credentials on redirects or embed them in URLs.

## Current authority, not future promises

Current listener routing allows Library list/detail/revision, artist/album playback,
track audio and entity artwork. Personal routes provide likes, history, manual
playlists and implemented derived/rules evaluation/copy protocols.
Derived support exists in the backend. The initial mobile client filtered manual
playlists; native derived parity is now being implemented in its own parallel lane.

Current playlist summaries already expose `kind`, `countState`, `revision` and
per-playlist `capabilities`; unevaluated derived counts are null, not zero.
These are not a general server feature/version discovery mechanism.
No general version/capability discovery API was found.

Existing artwork:
`GET/HEAD /api/library/{artists|albums|tracks}/{id}/artwork`.
Existing media:
`GET/HEAD /api/library/tracks/{id}/audio`, including byte ranges.
Identity segments must be encoded once; artwork routes reject query parameters.
Artwork is embedded local JPEG/PNG with authenticated fallback behavior, not a
managed portrait/provider pipeline. Audio is original bytes, without transcoding.
Recorded `hasAudio` does not establish file existence, decoder support or offline use.

No listener API for waveform, lyrics, sonic similarity, artist popularity/top
tracks, saved-source copy or linked playlists was found in the inspected routes.
Do not implement proposed spec route names as if they exist.

## Shared contract proposal — names and transport remain open

Agree a narrowly scoped authenticated feature-discovery mechanism with the backend
owner; do not repurpose `/api/auth/session` or administrative status responses.
Recommended capability information: feature identifier, contract major version,
supported modes, read permission and bounded limits; availability can differ by
server configuration, account, entity and content rights.
Do not send private provider identity, filesystem paths, model paths or diagnostics.

Version feature DTOs independently from catalog revision. Define supported required
fields, nullable/unknown counts, enums and unknown-version behavior before coding.
Unknown major versions fail closed to normal listening, not guessed interpretation.
Absence of discovery on older servers means unsupported/unknown, not an empty result.
Do not blindly probe invented routes or treat every 404 as “no content.”

Separate capability support from per-entity product state and network request state.
Agree explicit states as applicable: available, none-available/not-found,
not-analyzed, pending, unsupported, disabled, needs-review, temporarily-unavailable,
rights-unavailable and expired. Pending is not empty; missing score is not zero.
A timeout does not prove no content exists. Never leave an indefinite spinner.
GET enrichment reads must not start provider lookups, compute, acquisition or jobs.
Fence results by account, entity/occurrence, request and content/source generation.
Agree typed safe errors, payload bounds, cancellation, retention and invalidation.
Network/storage errors must leave ordinary playback usable.

## FEAT-004 — artwork

Native-capable now: authenticated embedded artwork plus local generated fallback;
artist/album images use a deterministic representative, not guaranteed portraits.
Web tracks normally inherit their supplied album context.
Managed assets, pins, variants and provider enrichment are not established contracts.

Needed future projection: effective entity/context identity, selection revision,
asset role, supported variant identities/dimensions/MIME, availability, provenance
display credit/link and policy-required expiry. Names are display text, not identity.
Agree track override versus occurrence-album precedence before adding overrides.
Use server-owned authenticated media references, never arbitrary provider URLs.
Coordinate rights, transformations, attribution placement, quota and invalidation;
benchmark tooling alone clears none of these. No admin artwork editor in listener UX.

## FEAT-005 — waveform analysis

No production waveform read contract found; synthetic benchmark tooling exists.
Scope is amplitude visualization and seeking, not LUFS, normalization or gain changes.
Needed DTO: track/source generation, algorithm/version, product state, decoded
duration, envelope encoding/count/range and bounded bins (proposal: at most 2,048).
Define media-duration mismatch tolerance and stale-source rejection.
Read on explicit expanded-player use, not per catalog row; keep ordinary seek.
Backend owns safe local extraction, worker/runtime limits and publication.
Target-host resource, cancellation and actual player/render evidence remain gates.

## FEAT-006 — plain and synchronized lyrics

No lyrics content/read API found. Coordinate lawful plain text first; timed local
lyrics, provider sourcing and remote highlighting are independently gated.
Needed DTO: track/document revision, state, language, plain text, explicit
instrumental assertion, recording-match status, attribution and expiry obligations.
Timed extension needs parser version, grouped cue times/text, offset convention
and validated recording/source generation. Never interpret absent text as instrumental.
Use literal text, bounded content and readable fallback; no provider HTML.
Local highlighting follows confirmed Media3 position, not a separate timer.
Remote timing additionally needs authoritative epoch/sequence/sample freshness and
waiting/seeking/handoff quality. Until agreed, remote output gets plain lyrics.
Rights and retention approval precede provider use or durable content caching.

## FEAT-007 — sonic similarity

No embedding, neighbor or recommendation API found; no approved production model
or runtime established by this scan. Do not substitute remote Spotify recommendations.
Proposed first product: bounded “Similar in your library” for one seed track.
Needed DTO: seed/source identity, model and index generations, coverage counters,
product state and ordered listener-safe tracks with explicit ranking semantics.
Distances are not percentages, popularity or confidence without calibration.
Backend must clear model/weight/runtime licenses, eligible content provenance,
offline installation, quality benchmarks and resource/index lifecycle first.
Local “new music” meaning remains open; external discovery/acquisition is not implied.
Finite mixes and explicit manual-snapshot save require separately agreed semantics.

## FEAT-008 — artist top tracks

No provider ranking/popularity API found. Artist Play is saved eligible releases,
not hits. Retained recent starts are not global popularity or completed listens.
Provider entitlement, market and permitted-use constraints remain unresolved;
spec research records Development Mode top-track/popularity restrictions.
Needed DTO: exact artist/provider identity, source/meaning, market/context,
ordered exact-track matches, nullable score, observation/expiry and capability state.
Keep source rank distinct from saved-result order and expose saved-music scope.
Do not fuzzy-transfer scores across editions. Flames require one approved accessible
meaning and current source evidence; otherwise omit them.
“Recently played by you” is an optional separately approved product, never a fallback
silently labelled Top tracks. History remains private, retained and best-effort.

## FEAT-001 / FEAT-015 — sources versus personal playlists

Existing manual/derived personal playlists are account-owned listening features.
Tracked sources, refresh/acquisition and Plex copy are separate admin authorities.
FEAT-015 is a proposed admin-to-own-account saved-snapshot copy, not listener sharing.
No production source-copy/linked-listener API was found; exclude admin source routes.
Existing saved snapshots are filtered accepted occurrences, not guaranteed full,
current Spotify occurrence lists. Duplicate order and metadata-only entries matter.

Before any listener source feature, agree explicit publication/audience authority,
provider subject/provenance, revocation, retention and consent. Opaque IDs are not grants.
Copy proposals need atomic occurrence-preserving writes, bounded quotas, preview
fences and durable bounded outcome resolution; a lost reply must not be replayed.
Linked proposals additionally need a distinct kind/capabilities, unavailable rows,
source generation/freshness, apply revision and explicit removal/empty-list policy.
Source fetch time and local apply time are different. Never trigger acquisitions.
Rights to independently retain Spotify-derived ordering/metadata remain a blocker.

## Coordination gates and open decisions

1. Backend owner confirms current shipped/deployed contracts and assigns each feature.
2. Agree discovery/versioning, closed DTO/state/error examples and listener authorization.
3. Resolve per-feature rights, provenance, attribution, runtime and retention gates.
4. Add synthetic contract fixtures in parallel native lanes; production adapters wait
   for agreed contracts and actual backend delivery, not merely draft documents.
5. Verify stale/account/rights/pending/none/error behavior and existing audio continuity.
6. Keep gestures non-destructive: expand/collapse, accessible contextual menus and
   optional upcoming-queue drag handles; no destructive swipe requirement.

Authority checked: backend `internal/web/{listening,personal,derived,library_artwork}.go`,
relevant domain docs and FEAT-001/004–008/015 specs; mobile `docs/api-contract.md`.
No Gradle runs, tests, services, real data, backend edits or Git mutations performed.
