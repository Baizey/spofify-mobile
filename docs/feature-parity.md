# Listener parity and native Android direction

## Scope and evidence

Target the website's **music/listening experience**, not its management shell. Native Android UX should provide equivalent capabilities, not duplicate desktop layout or browser-specific limitations.

The original planning scan observed spofify-go at `ebb604a` and mobile at `fb075a3`. The backend is being developed live: this is a planning snapshot, not a deployment claim, current gap inventory or frozen API specification. Recheck affected source/contracts before implementation; old task status can lag integrated code. Backend source is read-only and source inspection does not authorize service/account/data access.

Primary evidence in spofify-go:
- `docs/frontend.md` — Personal Library, search and playback sections.
- `docs/library.md`, `docs/personal-library.md`, `docs/derived-playlists.md`, `docs/playback-sync.md`.
- `internal/web/static/library.js`, `library-search.js`, `library-collections.js`, `personal-library.js`, `playlist-recipe.js`, `player.js`, `now-playing.js`, `playback-devices.js`, `playback-sync.js`.
- `internal/web/personal.go`, `derived.go`, `playback.go`, `auth.go`, `origins.go`.

Android baseline: `MainActivity.kt`, `LibraryViewModel.kt`, `PlaybackContexts.kt`, `playback/PlaybackConnection.kt`, `playback/PlaybackService.kt`, and `core/SpofifyClient.kt` / `Models.kt`.

## Product direction: listening, not a catalog dump

The server can contain far too much music for an all-artists/albums/songs landing page to be useful. The user explicitly rejected that top-level Library model. Main navigation is **Home / Search / Playlists / Profile**:

- **Home:** bounded recently played, a real Liked Songs shortcut/count, and a small set of personal playlists. Each shelf loads and recovers independently. No invented recommendations.
- **Search:** query-driven grouped artists/albums/songs with bounded results and intentional pagination. An empty query is a prompt, not a request for the complete catalog.
- **Playlists:** manual and rules-based personal listening collections, not every album on the server.
- **Profile:** authenticated username and canonical server origin, Credits, Playback devices and Sign out; no saved-login controls or account popup. Credits is nested and exposes the complete bundled JetBrains Mono notice offline. System Back dismisses the notice before popping Credits; Profile root uses normal Android root Back.
- Ordinary tab restoration retains Search query/results/paging/scroll; explicit Search activation/reselect clears to its empty root with a one-shot focus intent. Leaving for Profile cancels pending activation. These presentation transitions preserve selected-output playback and never issue transport commands.
- Artist, album and track details remain reachable intentionally from search, recent listening, playlists and linked credits. Release groups and complete playback contexts remain useful there.

## Listener parity requirements

These are capability and safety requirements, not an implementation-status table. Check current source and candidate-specific validation evidence for what is implemented and approved. Presentation follows the newer product decisions in [mobile-ux.md](mobile-ux.md), not an older desktop control inventory.

| Area | Required behavior |
| --- | --- |
| Home | Real playlist shelves and liked count; independently recovering shelves; successful-write invalidation without replacing usable content. No fabricated recommendations. |
| Search | Query-required Artists → Albums → Songs ordering, independent paging/retry and generous See more. No visible main-Search type/count rows, persistent filters or Include missing checkbox. Preserve IME/debounce/cancellation and selection; intentional detail views may use supported API filters. |
| Music details/navigation | Open track details independently from Play; navigable artist/album credits; meaningful year/duration/availability; artist Albums / Singles & EPs / Appears on groups; retained Back/page/scroll state. |
| Personal actions | Like/unlike and Add to playlist from rows, details and player; create a playlist from the add flow; current playing/paused row feedback. |
| Manual playlists | Create, rename/describe, delete, add/remove/reorder entries. Keep duplicate occurrence IDs, expectedRevision conflicts and uncertain-write recovery. Reorder requires the complete bounded order, not one visible page. |
| Rules/derived playlists | Evaluation cursors, stale state, signed occurrence selection and fenced POST playback; builder, preview, rules editing and editable manual copy use agreed derived contracts. |
| Queue | Play next / Add to queue from tracks and contexts; remove/reorder upcoming occurrences; clear remainder without dropping the current song. Up next reflects actual shuffle traversal, not the original full timeline. |
| Now Playing | Context identity distinct from track credits, linked credits, Add to playlist, queue actions and playing-state feedback. Use the expanded sheet contract; retain native system volume rather than copying every desktop control. |
| Playback devices | Same-account player registration/naming, selected-output controls and acknowledged handoff of the exact prepared queue/position/shuffle/history occurrence. This is the coordination protocol, not a Chromecast picker or a fresh playlist fetch. Enabled source is not native runtime acceptance; see [device-playback.md](device-playback.md). |
| Freshness/lifecycle | Catalog revision revalidation and targeted personal collection updates; preserve usable views, scroll, selection and playback across background refresh/navigation. |

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

Accept syntactically valid explicit `http://` and `https://` server origins, including hostname, public/private literal-IP and IPv6 HTTP. Preserve the chosen scheme: no inferred scheme, DNS-based classification, automatic upgrade/downgrade or HTTPS-to-HTTP retry. Reject userinfo, non-root paths, queries, fragments, malformed/ambiguous authorities, unsupported schemes and controls before networking.

Login has no HTTP warning, acknowledgement or opt-in controls. HTTP exposes passwords/session cookies/media without encryption; admission does not weaken exact canonical scheme/host/effective-port isolation. API, artwork and every audio/range request use the same authenticated client; authenticated redirects remain refused and TLS certificate verification stays intact.

The inspected backend policy rejects HTTP hostnames and public HTTP, requires an allowed exact origin and does not mix HTTP with HTTPS origins in one configured allowlist. A configured backend may independently enforce those restrictions: Android admission is not deployed backend support. Surface refusal concisely and coordinate any server policy change separately; do not retain Android's private-IP-only restriction or modify the live Go repository.

Contract touchpoints: mobile `core/ServerAddress.kt`, sign-in UI, `res/xml/network_security_config.xml`; backend snapshot references `internal/web/auth.go:authTransportAllowed`, `origins.go`, `docs/authentication.md`. Source references are not deployed compatibility evidence.

## Future website direction — do not mistake specs for shipped APIs

The FEAT identifiers here name backend planning areas, not required files in the mobile `docs/specs/` directory. Source snapshots are not deployed capability verification.

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

Automatically replace one separate Android Keystore-encrypted origin/authenticated-username/unmodified-password record only after fully successful, still-current authentication. No opt-in or legacy remember flag may gate future successful saves; failed, partial, cancelled and stale attempts never replace it. Keep sessions in their existing encrypted format and exclude both records from backup/device transfer. A post-auth save failure preserves the valid session and prior durable record and produces a brief truthful notice.

On first form display, autofill the latest valid successful triple together, regardless of a later failed server preference; never combine its credentials with another origin. Preserve user edits through recomposition/error/retry, clear mismatched passwords and reveal state on canonical-origin or username changes, and never resurrect a cleared password on returning to an old identity. Passwords stay out of saved UI state, StateFlow, parcels, URLs, logs and plaintext preferences. Corrupt encrypted data fails closed to manual login without plaintext fallback.

Sign out/expiry retain that encrypted record for explicit re-authentication; sign out still invalidates session authority and stops locally owned audio. Autofill never auto-submits or starts playback and does not replace existing cookie/session restoration or offline retry. The minimal Login has no checkbox, saved-for/Use/Forget controls, HTTP warning or font-license entry. Preserve the full bundled offline notice through the existing signed-in Account route, without depending on the peer Profile feature.

## Parallel implementation and integration gates

Each assigned lead uses a dedicated worktree/topic branch with its own index and build outputs, not file assignments inside a shared checkout. Follow [worktree-review.md](worktree-review.md): coordinate shared interfaces, independently review the exact committed candidate and corrections, and use the separate BUILD and MAIN advisory leases under standing conditional assigned-scope authority. Main integration requires the reviewed exact base and a clean destination; stop on another lane's dirt rather than staging, stashing or deleting it. The parent owns combined automated/native validation and evidence-preserving, content-accounted cleanup; a source checkpoint does not waive the native gate. Backend-blocked feature contracts remain coordination work, not fabricated UI capabilities.

Capability dependencies to consider when assigning work (not a current task queue):

1. **Daily listening foundation:** richer details/credit navigation, artist release groups, query-driven grouped search, retained navigation; shared More menus and correct upcoming/shuffle representation.
2. **Contextual actions and native gestures:** enqueue/play-next, queue editing, row likes/Add to playlist; expand/collapse, cover transport and non-destructive queue gestures with explicit alternatives.
3. **Manual playlist editing:** end-to-end create/add/edit/reorder/remove, duplicate identity and conflict/uncertain-write UX.
4. **Derived playlist parity:** consumption/playback before rules authoring/preview/edit/copy.
5. **Cross-device playback:** a separate Media3/service integration with ownership, handoff, disconnect and background tests—not just a picker screen.
6. **New backend-backed music features:** consume artwork/lyrics/waveform/similarity/top-track contracts as they actually land and are coordinated.

Offline downloads, Android Auto and durable queue recovery are separate possible Android enhancements, not existing website parity requirements. Native gestures, accessibility and background audio need isolated AVD evidence; source scans and JVM/UI unit tests do not establish them. Report genuinely hardware-specific Bluetooth/codec/vendor limits separately and coordinate any exceptional check through the user, never direct agent access to real phones.
