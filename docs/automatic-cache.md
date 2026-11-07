# Automatic audio cache

Settings exposes installation-local **Maximum songs** (2–10 unique songs) and **Storage**.
Storage is **Minimum** or a positive integer number of decimal GB (1 GB = 1,000,000,000 bytes).
Minimum estimates 50,000,000 bytes per FLAC song plus 100,000,000 bytes headroom: 200 MB
for two songs through 600 MB for ten. This is a simple practical estimate, not a guarantee
for arbitrarily long/high-resolution tracks. Minimum follows song-count edits; fixed GB does
not change implicitly. Neither setting changes queue length or the current-plus-next-five
prefetch policy, grants offline playback authority, or creates a download job.

`AppGraph` owns one `SongCache`. Its credential-free `AutoCacheSettingsStore` uses an
`AtomicFile` under app-private `filesDir`, outside disposable cache media. App backup and
transfer remain excluded. The cache owner serializes durable commits, policy publication,
reservations, FIFO reconciliation and admission. Missing/corrupt records use safe defaults;
legacy song counts clamp into 2–10. Existing media/FIFO manifests retain their format and
are reconciled safely rather than declared corrupt merely because the saved target fell.
The format compatibility bound of 100 entries is not the production admission ceiling.

Edits must fit at most half of the app-data filesystem's total capacity. They separately
validate remaining target growth against usable space with a 16 MiB safety reserve.
Every media allocation rechecks usable space, reservation/chunk needs and the half-capacity
ceiling; filesystem write/ENOSPC failures bypass retention without converting successful
upstream audio into a cache-limit playback error. A configured ceiling is not a preallocation
or guarantee against concurrent filesystem users.

Both song and byte ceilings constrain admission, including partial entries, staging, indexes
and still-owned open files. Successful playback promotions preserve FIFO policy. A lower
target can remain below actual protected/undeletable usage: no active reader/writer or
current-playback entry is deleted, and failed deletions do not reclaim accounting capacity.
Releases and maintenance retry safe cleanup. Targets are never silently raised.

Automatic FIFO only owns `cacheDir/song-cache-v1`. Intentional saved downloads belong to a
separate storage root/retention owner; they must not be inserted as disposable FIFO entries.
They consume the same filesystem's real free space but do not create a combined user budget.
Origin/account authentication, namespace retirement and playback authorization remain unchanged.
