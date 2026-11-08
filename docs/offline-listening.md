# Deliberate local listening

Offline mode is a persisted user choice, not a connectivity detector. It admits
no app-originated server work, including authentication, output registration,
cleanup/leave, artwork, audio and saved-download transfers. Both mode edges retire
the client's process-local admission epoch. Queued calls, late response parsing,
rolling-cookie publication and operation results cannot borrow a later epoch;
entering mode cancels captured ordinary and isolated cleanup calls. Exiting does
not replay them. Existing server-side work cannot be recalled.

The raw response-body guard prevents further admitted network consumption.
Already-prefetched Okio bytes can remain readable through a retained buffered
source; cancellation cannot erase bytes already in a consumer. Every asynchronous
consumer must additionally fence its decoded state, local writes and final
publication by captured operation/mode/account incarnation, including ABA. Core
API response delivery does this before parsing, after parsing and on coroutine
return. Artwork loader disposal, sole-player output draining and the saved-owner
transfer/install fences are separate responsibilities, not HTTP success claims.

## Local eligibility, not a restored session

A separate encrypted record contains only canonical origin, immutable account ID
and username, established after genuine successful authentication. It permits
retained-library access after process death and ordinary cookie expiry; it is
never a Session, cookie, receiver, server lease or remembered password. Cold launch
restores that library and waits for deliberate Play. It neither probes the server
nor starts audio in deliberate offline mode.

An observed authenticated-scope revision can only deny that record; it cannot
create eligibility. A separate durable account/denial epoch commits before a
non-null accepted session is persisted, closing delayed-callback/process-death
and account-ABA gaps. Protected epoch zero is not a legacy missing epoch. An
unavailable eligibility read is not confirmed absence and cannot seed a missing
journal or erase protected migration evidence.

Logout, actual origin/account replacement and observed authentication rejection
immediately retire reader identity. Tombstoning and encrypted-record revocation
are independent durable denial paths. The eligibility-only Keystore key may be
destroyed if deleting its ciphertext fails; session/login keys are untouched.
Failure of every durable denial mechanism blocks local regrant in-process and is
reported as cleanup failure after teardown, not successful durable logout. As with
any unavailable persistent storage, process-death safety cannot be asserted if
all durable revocation mechanisms fail.

## Verified saved-owner adapter

The retained download owner alone owns saved metadata, representation hashes,
length/version verification, partials, references, active leases, deletion and byte
accounting. Automatic FIFO cache bytes are not an offline-library source.

`OfflineLibraryAccess` is a read/presentation adapter, not a second store.
An `OfflineSavedCollection` retains ordered stable occurrences, truthful completion
and a verified media-reference map. Each opaque `OfflineMediaReference` captures
the real scope, target, immutable installed snapshot, occurrence and expected
representation descriptor in its opener. `SavedOfflineLibraryAdapter` uses the
owner's exact `SavedMediaRequest` and unopened, lease-free verified DataSource;
it never wraps or duplicates a `SavedMediaLease`. Membership is checked on open,
not repeatedly on an accepted stream, so Unsave does not revoke an existing lease.
One adapter-scoped, cancellation-independent initialization awaits ordered owner
readiness; revision-triggered reads reuse that access capability, not metadata
snapshots. This avoids a read/bind/revision feedback loop. Only explicit Retry
replaces a failed initialization; revisions never do, and successful access is
not reset. Internal retirement yields a stale empty result without swallowing
actual caller cancellation. Retirement fences this adapter only, never purges saved storage. It exposes no durable file path, server
URL or authority. The graph checks
eligibility, adapter identity and mode epoch before and after opening. The player
retains those exact references rather than resolving a later representation by
track ID. Partial rows are not playable files; incomplete collections use an
explicit **Play available** action and never claim full completion.

## Sole-player authority and reconnect

The existing MediaSessionService remains the only player. Local-only requests
have distinct eligibility/request/occurrence fences, use only verified local
sources and never acquire server leadership. Normal transport, exact upcoming
occurrence edits, seek, repeat, shuffle, focus/noisy interruption and notification
trust boundaries remain service-owned.

Mode changes pause/fence buffered output without commanding a separately playing
remote. Startup, restore, mode edges and focus gain never autoplay. An offline
queue is explicitly local-only: receiver snapshot/intent exports cannot publish
it, and it earns no server history or uncertain backfill. Exiting mode preserves
that provenance and waits for a fresh ordinary online Play through normal online
authority admission. Connectivity alone changes nothing.
