# Authoritative online playback

Online playback uses `spofify.library-playback.v2`, defined by the server's
`docs/library-playback-session.md`, `internal/playback/types.go` and executable
socket/resolver contracts. Catalog, personal collection edits, authenticated audio
Range/artwork and history GET/DELETE remain HTTP. There is no playback polling,
collection `/playback` preparation, client queue upload or client history POST.

One authenticated `AppGraph` coordinator owns one ticket/socket lifecycle. A
one-use authenticated ticket is obtained with `POST /api/me/player/connect` and
sent only in the first WebSocket hello, using the exact Origin, cookie and
required subprotocol. Tickets, cookies, CSRF and signed selection tokens are not
logged, persisted, put in URLs or included in diagnostic representations. A new
socket has new command/report counters and cannot replay an uncertain command.
Account/authentication and deliberate offline-mode transitions permanently fence
its pending callbacks and output authority. The ordinary HTTP session renewal is
independent of musical commands and never selects, prepares or resumes output.

Controllers submit identifiers: Track with optional exact collection context,
Collection, or an existing queue Occurrence key. Manual entries retain `entryId`;
derived rows retain `targetId`, ordered-path `occurrenceId`, signed selection
token, revision and fence. Collection Now+Shuffle is one atomic command. The
server resolves the complete ordered selection, creates insertion keys/events,
and commits all-or-error byte-bounded state. Browsing prefixes and local queue
projections are never canonical online selections.

State snapshots are structural/intent observations. Scalar playback samples are
ordered independently and applied only to their exact structural/output/event
binding. `desiredPlaying`, command acceptance and execution ACKs do not prove
actual playback. The Devices surface observes the socket's logical groups;
local execution additionally requires the exact `selectedConnectionId`, not
merely a matching logical device ID.

## Sole audio executor

`PlaybackService` remains the only Media3 audio owner. The coordinator's executor
handles targeted prepare/release/activate/execute and stage/cancel-next controls:

- Prepare is silent and grants no audio authority. Release and failed/stopped
  execution cancel pending native work and stop output before acknowledging.
- Each ACK/report shares the socket's increasing client sequence. A finite lease
  expires at the retained monotonic **send time** for its echoed sequence plus
  `durationMs`, never at grant receipt plus duration. A missing/expired basis
  cannot grant or renew authority.
- Startup, snapshots, reconnection, renewal, focus gain and UI presentation do not
  independently start audio. Every native continuation rechecks its operation,
  output/event, account/socket and finite authority.
- Socket/auth loss or expiry cancels pending work and pauses output immediately.
  Closing a socket is not source-stop proof: the server owns any remaining finite
  lease drain before another executor activates.
- Actual Media3 observations report progress; intended position, loading a track,
  seeking, queue presentation and command receipts are not listening history.
  The server alone qualifies online history.
- Automatic cache filling uses a separate bounded current-plus-next-five data
  plan. A proven prior local execution may retain cache-only lineage while
  stopped and selected; this is not an audio lease. It cannot authorize decoder
  reads, prepare/play, focus, reports or history, and account/socket/output/event
  changes revoke it. Startup/reconnect/snapshots cannot create that lineage.
- Only actual native ended may admit an uncanceled staged next under the source's
  unexpired lease. The source ended report is sent first. Admission deduplication
  preserves the original pending/successful/failed operation when its reserved
  IDs are later confirmed; confirmation cannot reload or start it twice. A late
  source lease cannot extend the admitted-next deadline.

## Deliberate local listening

`AppGraph.setOfflineMode`, `PlaybackService.enterOfflineMode`,
`retireLocalEligibility`, `replaceLocal` and `enqueueLocal` are the separate
network-free local admission boundary. Saved-media references and local queue
state never become server authority or an online queue/history upload. Local
history storage and reconciliation have their own contract; this transport does
not invent a historical playback-report path. Cache and saved-audio namespace,
byte-budget, integrity and retirement checks continue to apply.

## Evidence boundaries

Portable codec/coordinator/executor units establish strict shape, ordering,
account, cancellation and lease behavior using explicit synthetic boundaries.
A source-pinned private real-server fixture adds actual authenticated HTTP/WS
contract evidence. Neither a mocked endpoint nor JVM Media3 playback proves
installed Android rendering, physical sound, OEM keys, focus or background/FGS
operation. Those need separately admitted native evidence. Historical b6 HTTP
broker fixtures are not v2 acceptance inputs and must not be relabeled as current.
