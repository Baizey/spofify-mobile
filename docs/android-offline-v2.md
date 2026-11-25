# Android Offline Mode v2 implementation

See the shared [Offline Mode v2 specification](offline-mode-v2.md).

## Account data

Connection admission and top-level `sync` supply the complete account playlist list and the newest 40 server play occurrences. History identity is `(event ID, origin)`, not track ID. Unmatched local plays remain separately durable and are merged into the view. There is no REST refresh path.

A watched playlist uses `syncPlaylist`. Continuations use the current token and accepted row count; Go can restart at offset zero with a new token. Android validates the binding, offset, summary, count, ordering and occurrence identities. A scoped `missing` for the active transfer has the local effect of confirmed deletion without disconnecting. A partial, invalid, disconnected, or superseded transfer cannot advance freshness. When a remove delta does not describe exact resulting positions, Android keeps the accepted rows while performing a scoped repair.

Completing the wire transfer is not the same as obtaining a ready rules evaluation. A complete ready result replaces rows and advances freshness. An evaluation that isn't ready updates the status and invalidates detail freshness without replacing previously accepted rows. If there are no saved rows, a complete, validated stored projection may be shown as stale; a playlist with no materialization is not an empty playlist. Neither case authorizes shared selection, copy, or new downloads. Verified local downloads remain the authority for isolated playback.

## Downloads

Android preserves the previous playlist download until directory sync succeeds and the replacing job is still authorized. A failed or retired replacement cannot become playable through a later read. Transient save failures get at most two retries; a permanent failure stops the worker and does not retry on network policy changes.

## Integration test

`bash scripts/test-current-go-offline.sh /path/to/spofify-go` builds the pinned provider-free Go fixture in disposable storage. It checks socket opening and explicit `sync`, manual and derived occurrence order, exact manual removal positions, scoped `missing`, one direct track download, and disk reopen. It needs loopback TCP and cached Go/Gradle dependencies. Set `SPOFIFY_GO_FIXTURE_PORT` to an available port if the harness requires a fixed loopback grant. The JVM test does not exercise Media3 or render Android UI.
