# Backend compatibility boundary

Initial audit: spofify-go main at **9d532f9**. Treat this as a client contract snapshot, not ownership of the server. The sibling repository is live and **read-only**; coordinate all requested changes with its owner. Source inspection is not deployed-server verification.

## Authentication

- POST /api/auth/login: JSON username/password; exact server Origin and X-Spofify-Login: 1.
- GET /api/auth/session: accountId, username, role and csrfToken. This is revalidation, not a public version/capability endpoint.
- POST /api/auth/logout: session cookie, exact Origin and X-CSRF-Token; 204 on success.
- Cookie-only authentication: spofify_session for HTTP, __Host-spofify_session for HTTPS. Cookie naming does not imply backend admission of every HTTP origin. Never Bearer or token-in-query. Current sessions last 12 hours, without refresh tokens.
- Android admits syntactically valid explicit HTTP/HTTPS origins, including hostname/public-IP HTTP, without Login warnings or consent controls. Preserve the selected scheme and exact canonical scheme/host/effective-port binding for API, artwork, audio/ranges, cookies and CSRF. No inferred scheme, DNS classification, redirect following, TLS bypass or automatic upgrade/downgrade. Reject userinfo, non-root paths, queries, fragments, malformed/ambiguous authorities, unsupported schemes and controls before networking. The backend may independently reject transport or require its exact-origin allowlist; report refusal truthfully, not as proof that Android admission or deployed support is equivalent.
- All writes—including POST like-status reads—use session-bound CSRF. Mutations are one-shot and never implicitly replayed.
- A shared 401 clears the local session, private UI and playback. Off-origin requests and redirects cannot carry credentials. Signing out locally still succeeds if remote revocation fails, with a warning.

## Library / media

GET /api/library uses kind, q, scope=all, availability and bounded limit/offset. Main Search requires a nonblank query and loads independent artists/albums/tracks pages (10 each); it never fetches the catalog on an empty query. Artist/album detail children deliberately omit the outer search and use availability=all so missing tracks remain visible. Artist release groups use the shipped releaseGroup filter. GET /api/library/{artists|albums|tracks}/{id} loads listener-safe details; GET /api/library/revision supports quiet catalog revalidation. No provider fetch or acquisition is triggered by these reads.

Artist/album playback: GET /api/library/{artists|albums}/{id}/playback. The returned queue includes available members beyond the current browse page, up to 1,000. A selected track absent from a capped queue is explicitly retained rather than silently playing a different song.

Audio and art are identity-derived paths, encoded **once per opaque path segment**:

- GET /api/library/tracks/{id}/audio, including subsequent Range requests.
- GET /api/library/{artists|albums|tracks}/{id}/artwork; missing art uses a local fallback.

Original bytes are streamed; no transcoding. Recorded availability is not proof the file still exists or is decodable. Errors require an explicit retry/Next, not an infinite skip loop.

Listener tracks use hasAudio. Admin catalog tracks use libraryPath instead; the client normalizes availability without displaying paths. Container playability uses coverage.inLibrary, not hasAudio. Personal APIs always use listener-safe track projections.

## Personal collections

- GET /api/me/recent?limit=40; only actual progression leads to POST /api/me/history {eventId,trackId}. Event IDs identify queue occurrences and history failures are silent/best effort.
- GET /api/me/likes, GET /api/me/likes/playback.
- POST /api/me/likes/status {trackIds}; PUT /api/me/likes {trackId,liked}.
- GET /api/me/playlists lists manual and derived summaries; kind=manual is used for valid Add-to-playlist targets. Derived counts can be null/unevaluated, not zero.
- Manual playlists: POST collection; GET/PATCH/DELETE /{id}; GET/POST /{id}/entries; DELETE /{id}/entries/{entryId}; PUT /{id}/order; GET /{id}/playback. Writes use the exact expectedRevision contract. Entry IDs identify occurrences, not tracks; reorder requires the complete bounded order. A 409 or lost reply is not permission to replay a mutation.
- Derived playlists: GET/PUT /{id}/definition; GET /{id}/evaluation with signed cursor; POST /api/me/playlists/preview; POST /{id}/playback and /{id}/copy with revision/fence and selectionToken where applicable. Recipe edits invalidate preview generations. Playback consumes the approved prepared result, not a client re-evaluation of visible rows.
- Successful confirmed likes/history/playlist writes advance client-local freshness counters so visible personal shelves can refresh independently without discarding usable content.

Administrative source playlists are **not** personal playlists. Personal owners come only from the authenticated account; the app never supplies an owner selector.

## Playback devices and native safety gate

The native protocol adapter consumes GET /api/me/player and its existing POST actions (register, poll, rename, command, transfer, leave). Requests are bounded and one-shot; 404/409 reconciliation may preserve only a strictly validated listener-safe view, never arbitrary server error bodies. Prepared snapshots preserve exact queue keys/order, context, duplicate occurrences and history qualification—not URLs or a freshly fetched playlist.

The application owns the coordinator and ties it to service lifecycle/authentication. The production `Media3PlaybackEndpoint` advertises exact handoff capability, and the device sheet exposes selection/rename controls when registered; it remains view-only without a device registration. These are source-level capabilities, not proof of native audio/background acceptance. Preserve authority-fenced import and app/notification/system transport routing; see [device-playback.md](device-playback.md) for the safety requirements and unverified runtime boundary.

## Connection persistence

Sessions retain their original Android Keystore AES-GCM storage format/namespace. Automatically remember the latest fully successful, still-current client/session authentication as one exact origin/authenticated username/unmodified password in a separate encrypted namespace, excluded from backup/device transfer. No opt-in is required; legacy remember flags, including false, must not suppress future successful saves. Failed, partial, cancelled, stale or expired-auth attempts never replace the record. A post-auth persistence failure preserves the valid session and prior durable login and reports a concise failure-to-save notice.

On first Login display, autofill that valid encrypted triple together, not credentials combined with a later failed server preference. Preserve edits through recomposition/error/retry. Canonical-origin changes (including invalid addresses) or username edits clear mismatched passwords and reveal state; returning to an old identity cannot resurrect cleared secrets. Preserve password whitespace and keep secrets out of saved-instance state, StateFlow, parcels, URLs, logs and plaintext preferences. Invalid/corrupt encrypted data fails closed to manual login without broad key deletion or plaintext fallback.

Sign out invalidates session authority and stops locally owned audio but retains the latest encrypted successful login; expiry likewise retains it for explicit re-authentication. Autofill never submits a password, registers transport or starts audio. Existing session restoration and explicit offline retry remain separate from password autofill; restored sessions never autoplay. Login has no remember checkbox or saved-login Use/Forget controls.

Unknown response fields are ignored for forward compatibility, while missing required fields/content-type mismatches fail with local user-facing errors. No version/capability API currently exists; do not invent one or treat an arbitrary 401 as proof of server identity.
