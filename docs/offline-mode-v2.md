# Offline Mode v2

**Applies to:** Spofify Desktop and Mobile. This is the target client behavior, not a description of what has shipped. It does not change the server's offline v1 API or require both clients to use the same storage or networking implementation.

Offline Mode v2 replaces the previous client offline mode. Backward compatibility with its behavior or stored state is not required.

## 1. Connectivity and mode

| State | Local behavior | Network behavior |
| --- | --- | --- |
| Online | Render saved data first; prefer valid downloaded audio. | Stream when needed. Refresh missing or stale data in the background. |
| Automatically offline | Browse saved data and play downloads. | Recover automatically when the service is reachable. Use bounded retries with backoff; screen navigation must not restart retry loops. |
| Manual Offline Mode | Browse saved data and play downloads, even when connected. | No application network traffic: no streaming, download transfers, artwork fetches, session validation, refresh, or sync. |

- Manual Offline Mode is an explicit user preference that survives app restarts. Read it before starting network work. Connectivity or service failures must never set it. Turning it off allows recovery; it does not imply that the service is reachable.
- Entering manual mode cancels in-flight network work, closes active server connections, and prevents cancelled results from publishing. Local playback is not interrupted. Incomplete downloads do not become available offline.
- Treat device connectivity as a hint. A connected device can still be unable to reach the service. A single failed track, missing resource, or endpoint error must not by itself mark the entire service offline. Local browsing must not depend on determining reachability first.
- On recovery, restart eligible online work and refresh stale data. Do not interrupt local playback, auto-resume a track paused by connection loss, or replay offline commands to a shared player.

## 2. Playlist and Recently Played caches

**Read from disk first. Refresh only when needed.** These caches exist for normal online use as well as offline browsing. Cached screens must not wait on session validation or a data fetch before rendering.

### Stored data

- **Playlists:** Persist the playlist list and successfully fetched playlist details, including track order, duplicate occurrences, and metadata needed to render rows. Persist enough metadata for any playlist with downloaded tracks to open offline, even when only some tracks are downloaded. This does not require prefetching the track list of every playlist.
- **Recently Played:** Persist the last known history. Record qualifying local plays using the same eligibility rule used online. Offline plays appear immediately and survive restart. Merge subsequent server history without erasing unmatched local plays within the normal history retention window. Do not deduplicate distinct plays merely because they use the same track ID. Uploading offline play history is not required by this spec.
- Store caches durably and scope them to the active account. Missing artwork must not block a view. Cached metadata is not evidence that audio is available offline. A cached playlist name without saved details is **not saved on this device**, not an empty playlist.

### Refresh and writes

- Read saved data at startup. When network access is available, check freshness on startup, foregrounding, reconnection, and relevant screen entry. Refresh missing or stale data in the background. Start with a **five-minute freshness window**, measured from the last successful refresh of each resource. Keep the window configurable and testable.
- Reopening a screen within that window does not refetch it. Share concurrent requests for the same account and resource. An existing explicit refresh gesture can bypass freshness, but cannot bypass offline mode. Do not add retry controls for transient failures.
- Apply confirmed online changes to the corresponding cache immediately. A complete successful refresh may replace its snapshot. A failed, cancelled, incomplete, or superseded refresh must not erase a valid snapshot or roll back a newer confirmed edit. Do not advance the freshness timestamp on failure.
- Retain stale snapshots for offline use. Disable server-only edits offline; do not claim success or silently queue mutations. Local download removal and local playback remain available.

## 3. Downloads and playback

- A track is **Downloaded** only once its audio has been fully written, verified against the download contract, and durably recorded as playable by the client. Publish completion only after the file can be opened for playback. Partial transfers and temporary streaming buffers do not count. Downloads survive normal restarts and ordinary metadata or streaming-cache eviction.
- Prefer a valid download whenever playback belongs to the local client, even online. If the file is missing or unreadable, correct the recorded availability. A saved index entry alone does not make a track playable.
- When streaming stops because the service is lost or manual mode is enabled, continue at the current position from a valid local copy if possible. Otherwise, play the remaining buffer for the current track without fetching more audio. Pause with a brief reason if the track cannot continue.
- When the user explicitly selects an unavailable track, show why it cannot play. Do not substitute a different track. During automatic queue advance, skip unavailable entries in queue order and stop if none can play. Repeat must not spin through an unavailable queue indefinitely.
- Keep Downloads easy to reach. Indicate unavailable tracks in saved views without obscuring their metadata.

## 4. Account isolation

- A returning user with an eligible saved session can cold-start offline and access that account's local content without waiting for a network check. The manual offline preference alone does not establish an identity.
- Connection failure is not sign-out. Explicit sign-out, account switching, or confirmed session revocation must remove access to the previous session's local content. Late responses or workers from a retired session must not publish data, write it into the active account's cache, or grant playback access.

## 5. Acceptance tests

Run on both clients. Use an injectable clock, controlled responses, and network request counts where applicable. Cover the relaunch path on a running client as well as in automated tests.

| Scenario | Expected result |
| --- | --- |
| Download some tracks from a playlist, quit, disconnect, and relaunch | The saved playlist and Recently Played open without a network wait. Downloads play; the other tracks are marked unavailable. A cached playlist with no saved track list does not appear empty. |
| Open and reopen the saved screens online with delayed responses | Saved data renders immediately. Fresh resources are not fetched again; stale resources each get one background refresh. Overlapping consumers share it. A failed or partial refresh leaves the old data and timestamp intact. |
| Confirm a playlist edit while an older refresh is in flight | The confirmed edit appears immediately and the older response cannot undo it. |
| Enter manual Offline Mode while connected with network work in flight, then relaunch | Existing connections and transfers stop; no new network requests occur, including at startup. The setting persists. Turning it off permits recovery. |
| Leave the device connected, but make the service unreachable at startup and during use | Local browsing and playback work. The manual preference does not change. The client recovers without a restart when the service returns. |
| Lose connection mid-track; then advance through a mixed queue | A local copy or existing current-track buffer is used when available; otherwise playback pauses. Automatic advance skips unavailable tracks; direct selection does not substitute. An all-unavailable repeating queue stops. |
| Interrupt a download, or delete a completed audio file | A partial file never becomes Downloaded. A missing or unreadable file does not remain eligible for local playback. |
| Play a download offline, quit, then reconnect and refresh history | The play survives restart and refresh. Distinct plays of the same track remain distinct where the history view shows play events. |
| Sign out or switch accounts with a refresh in flight | No cached views or downloads leak across accounts, even when an old request completes late. |
