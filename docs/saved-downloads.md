# Saved collection download contract

Saved targets retain their real kind: manual playlist, derived playlist, album or
artist. A saved album or artist is not a server playlist. Saving is an explicit
local retention/download operation; it does not select an output, prepare audio,
start playback or record listening history.

## Native offline API v1

All requests use the account client's existing exact-origin authenticated
transport. Redirects are not followed. The manifest routes are:

- `GET /api/me/offline/v1/playlists/{id}/manifest`
- `GET /api/me/offline/v1/albums/{id}/manifest`
- `GET /api/me/offline/v1/artists/{id}/manifest`

Opaque IDs are encoded as individual path segments. Requests carry `limit`
(1–100) and, after the first page, the returned opaque `cursor`. Every page must
match the requested target kind/ID, initial fence, counts and contiguous ordered
positions. Stable occurrence IDs preserve duplicate tracks as separate rows.
Missing audio remains a row. Only a terminal `nextCursor: null` completes a
membership snapshot; it does not complete the media download. A stale/invalid
cursor discards that staging attempt, never appends a different generation.

Playlist envelopes retain `playlist: {id,kind,name,description,revision}`. Album
and artist envelopes instead contain `collection: {kind,id,name}`. Both include
`version`, `fence`, `items`, `total`, `recordedAudioTotal`, `limit` and `nextCursor`.
Each item contains `occurrenceId`, zero-based `position` and the existing listening
track DTO. Catalog/private filesystem paths are not local download destinations.

Prepare each distinct track with
`GET /api/me/offline/v1/tracks/{id}/media`. The descriptor binds `trackId`,
`mediaVersion: sha256:<digest>`, lowercase full-file `sha256`, positive
`byteLength`, format/MIME and an authenticated relative `downloadUrl`. A digest
is integrity metadata, not authentication. Descriptor URLs and session secrets
must not enter durable saved-library metadata.

The returned audio route supports authenticated GET/HEAD and ranges with a
mandatory expected version. Resume sends `Range: bytes=N-` and the strong
`If-Range: "sha256:<digest>"`. Append only a 206 with the exact start/end/total,
ETag and response length. A 200 starts from zero, never appends. Short, extra,
malformed or mismatched bytes are not playable complete files. A changed-version
409 discards obsolete partial bytes and requires preparation again; 404 retains
an unavailable row; an observed 401 fences authenticated work.

Before promotion, verify the entire file's SHA-256 and length. Successful HTTP,
an ETag, an automatic-cache stable track ID or a verified prefix is insufficient.

## Local ownership

Saved membership, complete representations and partial staging have separate
lifetimes. Metadata and media live in backup-excluded app-private storage scoped
by canonical origin and immutable account ID, with opaque path names. Exact
verified representations can be shared by multiple saved collections. Reader
leases defer physical deletion; unsaving one collection cannot remove another
collection's retained bytes or an active reader's file.

An explicit Update stages a coherent replacement and keeps the previous playable
generation and its retention references until the replacement is ready. Initial
partial availability must remain labeled partial; unavailable rows prevent a
false complete state. Empty complete membership is not an endless download.

Intentional saved retention is not disposable FIFO caching. There is no hidden
saved-collection count quota or silent saved eviction. Admission must account for
real media, partial, staging and index bytes, available disk and bounded transfer
reservations. Failed cleanup remains counted and inaccessible when retired.
Explicit offline mode cancels/defers network transfers until online use is
restored; restoring online does not itself start playback. Local browsing and
play authorization are a separate policy, not authority conferred by stored bytes.

## Focused transport verification

Portable `:core:test` runs synthetic authenticated transport/storage tests without
starting a backend. The separately selected production HTTP gate is
`:core:test -Pspofify.offlineApiIntegration=true --tests
 dev.spofify.mobile.core.OfflineCollectionsGoIntegrationTest` (join the command on
one line). Selection is strict: it requires a supervisor-owned private
`SPOFIFY_TEST_OFFLINE_FIXTURE_DESCRIPTOR` and the exact source pin in
`SPOFIFY_TEST_OFFLINE_SOURCE_REVISION`; missing or mismatched inputs fail, not skip.
The descriptor belongs to the source-pinned server's synthetic `offline-fixture`
process, not a personal server. Its supervisor owns launch, binary/source hash
verification, stdin/EOF shutdown, process join and private runtime cleanup.
Transport/storage evidence does not establish Android rendering, codec/audio
output, playback authority or production deployment.
