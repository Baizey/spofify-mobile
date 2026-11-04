# Listener parity and native Android direction

## Scope and evidence

Target the website's **music/listening experience**, not its management shell. Native Android UX should provide equivalent capabilities, not duplicate desktop layout or browser-specific limitations.

Quick source scan observed spofify-go main at `ebb604a` and mobile at `fb075a3` plus local draft changes. The backend is being developed live: this is a planning snapshot, not a deployment claim or frozen API specification. Both code and domain docs were checked; `docs/specs` tracking can lag integrated code. Recheck affected contracts before implementation. No backend files, accounts, services or data were modified.

Primary evidence in spofify-go:
- `docs/frontend.md` — Personal Library, search and playback sections.
- `docs/library.md`, `docs/personal-library.md`, `docs/derived-playlists.md`, `docs/playback-sync.md`.
- `internal/web/static/library.js`, `library-search.js`, `library-collections.js`, `personal-library.js`, `playlist-recipe.js`, `player.js`, `now-playing.js`, `playback-devices.js`, `playback-sync.js`.
- `internal/web/personal.go`, `derived.go`, `playback.go`, `auth.go`, `origins.go`.

Android baseline: `MainActivity.kt`, `LibraryViewModel.kt`, `PlaybackContexts.kt`, `playback/PlaybackConnection.kt`, `playback/PlaybackService.kt`, and `core/SpofifyClient.kt` / `Models.kt`.

## Product direction: listening, not a catalog dump

The server can contain far too much music for an all-artists/albums/songs landing page to be useful. The user explicitly rejected that top-level Library model. Main navigation is **Home / Search / Playlists**:

- **Home:** bounded recently played, a real Liked Songs shortcut/count, and a small set of personal playlists. Each shelf loads and recovers independently. No invented recommendations.
- **Search:** query-driven grouped artists/albums/songs with bounded results and intentional pagination. An empty query is a prompt, not a request for the complete catalog.
- **Playlists:** manual and rules-based personal listening collections, not every album on the server.
- Artist, album and track details remain reachable intentionally from search, recent listening, playlists and linked credits. Release groups and complete playback contexts remain useful there.

## Existing website features to bring across

The table records the initial-draft baseline. Implementation is now proceeding in parallel; it is not a claim that every listed gap has already passed integration or device validation.

| Area | Android draft | Remaining listener parity |
| --- | --- | --- |
| Home | Recent songs and shortcuts | Real playlist shelves and liked count; independently recovering shelves; successful-write invalidation without replacing usable content. No fabricated recommendations. |
| Search | One mixed, available-only result page | Artists / Albums / Songs groups, independent totals/pagination/retry, explicit type choice and Include missing. Keep IME/debounce/cancellation and selected state coherent. No admin tracking/download filters. |
| Music details/navigation | Basic artist/album child lists | Open track details independently from Play; navigable artist/album credits; meaningful year/duration/availability; artist Albums / Singles & EPs / Appears on groups; retained Back/page/scroll state. |
| Personal actions | Like in expanded Now Playing | Like/unlike and Add to playlist from rows, details and player; create a playlist from the add flow; current playing/paused row feedback. |
| Manual playlists | Read and play | Create, rename/describe, delete, add/remove/reorder entries. Keep duplicate occurrence IDs, expectedRevision conflicts and uncertain-write recovery. Reorder requires the complete bounded order, not one visible page. |
| Rules/derived playlists | Hidden by kind=manual | List and consume existing rules playlists first: evaluation cursors, stale state, signed occurrence selection and fenced POST playback. Then builder, preview, edit rules and editable manual copy. These features exist in current website code. |
| Queue | Replace context, select timeline index | Play next / Add to queue from tracks and contexts; remove/reorder upcoming occurrences; clear remainder without dropping the current song. First make Up next reflect actual shuffle traversal rather than the original full timeline. |
| Now Playing | Seek/shuffle/repeat/likes and local transport | Context identity distinct from track credits, linked credits, Add to playlist, richer queue actions and playing-state feedback. Retain native system volume rather than copying every desktop control. |
| Playback devices | Local Media3 only | Same-account player registration/naming, selected-output controls and acknowledged handoff of the exact prepared queue/position/shuffle/history occurrence. This is the existing coordination protocol, not a Chromecast picker or a fresh playlist fetch. |
| Freshness/lifecycle | Request guards and service ownership exist | Catalog revision revalidation and targeted personal collection updates; preserve usable views, scroll, selection and playback across background refresh/navigation. |

Embedded artwork and generated fallback already exist on both sides. Authenticated notification/lock-screen artwork is an additional Android gap; do not give a bare protected URL to an unauthenticated system image loader.

Normal listening must not expose acquisition, sources, jobs, server settings, user administration or provenance. A listener's playlist editing is music functionality, not server management.

## Phone interactions

These are proposed native interactions, not claims of implemented gestures:

| Context | Interaction | Semantics / constraints |
| --- | --- | --- |
| Mini-player | Tap identity or swipe up | Expand Now Playing without preparing/restarting audio. Keep transport taps separate. |
| Expanded Now Playing | Swipe down or Android Back | Collapse only; preserve song, position, queue and play/pause state. Nested scrolling and sheet dismissal must cooperate. |
| Expanded cover area | Horizontal swipe | Candidate: left Next, right Previous/restart, matching visible buttons. Confine to cover area; do not intercept seeking, vertical scrolling or Android edge Back. |
| Music row | Long press / visible More | Same contextual actions: Play next, Add to queue, Like, Add to playlist, Go to artist/album. No gesture-only functionality. |
| Music row | Short directional swipe | Candidate: Add to queue with visible icon/label and completion feedback. Never autoplay, enqueue on every drag update or silently mutate a saved playlist. Support occurrence-aware Undo where valid. |
| Upcoming queue row | Swipe to remove | Remove only that queued occurrence; never the library track or a saved playlist entry. Keep an explicit Remove action and valid Undo. Do not treat the current/played section as upcoming. |
| Queue/manual playlist | Drag a dedicated handle | Reorder occurrences; maintain accessible Move up/down alternatives. Queue edits exit shuffle according to the shared contract. Saved playlist reorders require a complete snapshot/revision and explicit conflict recovery. |

No swipe-to-delete a saved playlist/library song by default. Do not globally intercept horizontal gestures or add swipe navigation that conflicts with Back, list scrolling or media controls. Use direction thresholds, touch slop and at most one committed action; gestures supplement labeled buttons, TalkBack actions and appropriate haptic feedback.

### Player dismissal decision

The user-facing **Close player** button has been removed. Pause and collapse are the normal controls. The internal stop operation remains necessary for logout, session expiry, server changes and safety cleanup; dismissing the UI must never invoke it.

## Plain HTTP is supported

Both `http://` and `https://` server origins are in scope. Do not silently upgrade to HTTPS or depend on browser secure-context features for ordinary listening.

The Android app permits cleartext access to concrete private/loopback IPs, IPv6 ULA and shared-space/Tailscale IPs. Sign-in shows a plain-language HTTP warning rather than an unexplained acknowledgement checkbox. API, artwork and audio/range requests use the same selected origin and authenticated client.

Current backend policy rejects **HTTP hostnames** (for example `http://music.local:8080`) and public HTTP; it requires an allowed exact origin and does not mix HTTP with HTTPS origins in one configured allowlist. These are backend constraints too, not just Android validation. If hostname-based HTTP is required, coordinate server policy before widening client admission. HTTP exposes passwords/session cookies/media to the network; disclose that clearly without presenting all non-HTTPS use as unsupported. Never disable TLS certificate verification as a workaround.

Evidence: mobile `core/ServerAddress.kt`, sign-in UI, `res/xml/network_security_config.xml`; Go `internal/web/auth.go:authTransportAllowed`, `origins.go`, `docs/authentication.md`.

## Future website direction — do not mistake specs for shipped APIs

| Spec area | Current boundary / mobile preparation |
| --- | --- |
| FEAT-001 / FEAT-015 playlist/source integration | Manual and derived personal playlists already exist. Saved-source copy and linked one-way synchronization are separate proposed contracts with ownership/provider/retention gates. Do not expose admin source routes as a listener substitute. |
| FEAT-002 / 003 / 009 / 010 / 017 / 020 UX work | Grouped search and compact transport already have integrated slices; navigation, empty-search and music-first detail refinements are evolving. Follow current source and user decisions rather than blindly importing stale tracking status or desktop chrome. |
| FEAT-004 artwork | Existing embedded art remains the baseline. Managed local artwork/overrides and approved provider enrichment are future server work. Keep identity-based authenticated art and stable fallback; no arbitrary external image fetching. |
| FEAT-005 waveform | Waveform display/seeking needs bounded, versioned analysis products and backend contracts. Do not reinterpret this as approved automatic loudness normalization. A benchmark is not the production endpoint. |
| FEAT-006 lyrics | Plain then synchronized lyrics need lawful content/identity/format contracts; provider sourcing and truthful remote timing have separate gates. No lyrics API was found in the scanned implementation. |
| FEAT-007 similarity | Proposed local sonic neighbors/mixes require model/runtime/rights and resource decisions. Not a shipped recommendation/radio API; do not fabricate results or analyze the full library on a phone. |
| FEAT-008 artist top tracks | Popularity/provider access and meaning remain decision gates. Personal recents are not global popularity or Spotify Top tracks. Render only an approved, source-labeled result when available. |

## Remembered sign-in

The old HTTP acknowledgement checkbox is not a remember-me control. It is replaced by a visible HTTP warning and a clearly labeled **Remember sign-in on this device** setting, default on for testing. Successful sign-in can save one exact server origin, username and password in a separate Android Keystore-encrypted record. Passwords are never placed in saved UI state, logs or plaintext preferences. Server-origin or username edits clear the form password. Sign out clears the session but keeps the remembered login; **Forget saved login** deletes the credential record and turns remembering off without destroying an active session.

## Parallel implementation and integration gates

The user requested all independently implementable lanes in parallel. Each lead uses an actual dedicated worktree/topic branch with its own index and build outputs, not file assignments inside a shared checkout. Scope boundaries cover core catalog/shared transport; search/home/details and root UI integration; personal/derived collections; local player/queue/gestures; device protocol/coordination; encrypted login storage. Follow [worktree-review.md](worktree-review.md): coordinate shared interfaces, independently review the exact committed candidate (and re-review corrections), request serialized build and main squash slots, then let the granted lead squash into **main** only with an explicit candidate/scope/expected-HEAD grant and clean checkout/index checks. Temporary integration branches are optional staging, not the final destination. The parent owns combined automated/native validation and evidence-preserving, content-accounted cleanup of finished/idle/crashed lanes; a source checkpoint does not waive the native gate. Backend-blocked feature contracts remain coordination work, not fabricated UI capabilities.

Dependency order within and between lanes:

1. **Daily listening foundation:** richer details/credit navigation, artist release groups, search filters/grouping, retained navigation; shared More menus and correct upcoming/shuffle representation.
2. **Contextual actions and native gestures:** enqueue/play-next, queue editing, row likes/Add to playlist; expand/collapse, cover transport and non-destructive queue gestures with explicit alternatives.
3. **Manual playlist editing:** end-to-end create/add/edit/reorder/remove, duplicate identity and conflict/uncertain-write UX.
4. **Derived playlist parity:** consumption/playback before rules authoring/preview/edit/copy.
5. **Cross-device playback:** a separate Media3/service integration with ownership, handoff, disconnect and background tests—not just a picker screen.
6. **New backend-backed music features:** consume artwork/lyrics/waveform/similarity/top-track contracts as they actually land and are coordinated.

Offline downloads, Android Auto and durable queue recovery are separate possible Android enhancements, not existing website parity requirements. Real-device gesture, accessibility, background audio and Bluetooth testing remain necessary; source scan and JVM/UI unit tests do not establish them.
