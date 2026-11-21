# Unified mobile search

The Search tab uses the authenticated application API `GET /search`, backed by the
server’s private Typesense index. It never connects to Typesense, reads an index
key, configures the service or starts indexing. The backend contract is
`spofify-go/docs/search.md`. Library browsing, details and artwork retain their
existing endpoints. Legacy `LibraryApi.search` remains available for compatibility;
the Search tab does not call it or fall back to it.

## Ownership and ranking

`core.search.SearchClient` owns validated account/session-bound requests and safe
failures. `SearchViewModel` owns the editable query, debounce, accepted snapshot
prefix, continuation cursors and interrupted/retry requests. `SearchResultSnapshot`
validates accepted and staged coherent prefixes. A changed query or paused screen
cancels pending work and retires its generation; stale successes, failures and
cleanup cannot change the successor request. Paused state also blocks new paging
admission, including reentrant requests from scroll observers during disposal.

After the 300 ms debounce, the tab requests one unfiltered mixed-kind first page
with a limit of 25. It retains the exact returned order: there are no local type
boosts, per-kind quotas, substring sorting or client transliteration. The existing
reverse-layout list places the first, highest-ranked result nearest the search
field; subsequent weaker pages extend upward without reordering the prefix. An
accepted first page for a new user query resets the list anchor, preventing the
previous loading-status row from anchoring the viewport at the weak end. A local
presentation token, not a server timestamp/version, owns that reset; cursor appends
and quiet cache recovery keep their scroll position. Artwork, detail navigation,
queue actions, row feedback and IME-aware chrome remain owned by the existing
native components. A new focus intent clears the old query
before lifecycle recovery can issue an obsolete read.

## Snapshot continuation

The first request sends `query` and `limit`; the optional core API `kind` filter uses
singular values. Every continuation sends `cursor` alone. Cursors stay opaque and
in memory, scoped to the authenticated account/session; they are not credentials
or playback authority. The client does not decode them or manufacture offsets.

`resultCount` is a bounded retained-result count (up to 300), not the complete
catalogue match count. `expiresAt` identifies the server snapshot; device-clock
comparisons do not decide whether a cursor is valid. Continuations must preserve
that timestamp and count, add unique kind/ID identities, advance the cursor and
account for the retained count. Cyclic cursors, empty nonterminal pages, changed
snapshot metadata and incomplete terminal pages never publish a partial append.

Paging is automatic near the weak end of the reverse list, with a five-row
prefetch margin and no normal **More results** button. The scroll observer requires
a measured current layout, a continuation cursor, an active view and no loading
or error state. Cursor/count changes re-arm it even if a fast response conflates
a loading frame. It does not repeatedly retry failures or fetch against stale
layout measurements.

Cache expiry is internal protocol maintenance, not a user-facing search state.
An expired cursor quietly starts a fresh search and stages coherent pages through
the previously loaded depth plus another page, or until the fresh results end.
The old rows remain visible while this work runs; only the complete fresh prefix
replaces them. Fresh pages never append to the old snapshot. Stable row keys and
an unchanged presentation token preserve the viewport when those results still
exist; catalogue changes that remove the visible row can necessarily change its
position. No local-clock deadline clears results.

Each operation permits only one automatic recovery: an expiry rebuild or one
backed-off repeat of a classified transient online read under the same session.
A transient continuation repeats its cursor; a known expired cursor is never
reissued. An interrupted rebuild can resume coherent staging to the same target
depth. Terminal failure retains the old prefix and shows a concise status without
an action. Returning to a completed or failed view does not rerun it. No query
read occurs while the view is paused.

## Errors and trust boundaries

Index-not-ready, unavailable, busy, invalid-response and connection errors are
distinct from successful empty results. UI notices use fixed client text, never
backend diagnostics. Exhausted cache recovery uses a generic load failure rather
than an expiration notice. Busy, index-not-ready, rate-limited and `Retry-After`
responses do not trigger recovery. There is no old-search fallback; index
maintenance remains an explicit server administrator operation.

The GET transport prevents automatic 408/421/503 status follow-ups. Search explicitly
opts into bounded JSON 503 diagnostics, at most 8 KiB, so stable search codes survive.
The default for other readers still discards 503 bodies; non-JSON, oversized,
media/artwork and 408/421 bodies also remain discarded. The policy travels with the
request, not a URL allowlist or a global transport setting. Advisory retry headers
do not trigger a client retry loop. Current 401 handling still retires the session.

Both roles consume the same positive search projection, including an explicit
`hasAudio`; administrator path-based availability fallback belongs only to the
legacy Library projection. Manual artist credits without an identity retain their
names but do not become navigation targets. Required identities and supplied
nonempty artist IDs remain validated. Shared metadata projection keeps these rules
consistent between Library and Search.

Snapshot coverage describes discovery, not guaranteed current playback admission.
Artist discovery may include appearances even when an own-release playback queue
is empty. Playback commands continue to resolve against current server state;
search never selects an output, fabricates playback success or replays commands.

## Verification boundary

Contract tests exercise local HTTP fixtures, parsing, account retirement, safe
error handling and absence of status replay. View-model tests cover order, debounce,
continuation consistency, atomic cache recovery, automatic scroll paging,
lifecycle recovery and stale/reentrant asynchronous ownership. These tests do not measure production relevance or latency,
prove index readiness, or replace device acceptance. No live-server performance
claim follows from fewer requests or synthetic fixtures.
