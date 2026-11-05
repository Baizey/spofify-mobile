# Native Android music UX

## Authority and purpose

Read this before designing or changing a screen. These decisions apply to every agent working on this repository. `AGENTS.md` makes rendered native UI validation a completion gate; see [ui-validation.md](ui-validation.md).

The user reported that adding features had significantly degraded the visuals. Functional coverage is not permission to flatten the interface into lists of stock buttons, duplicated headings and exposed configuration. Fix visual regressions before expanding the surface further.

This is a native Kotlin/Compose/Media3 listener, not a small website. Preserve the coherent music-first experience while adding capabilities. Website parity means appropriate listening capabilities, **not identical controls or desktop information density**.

## Explicit product decisions

These are this app's requirements, not claims that every Android app must use the same layout.

### Listening destinations, not a catalog dump

- Main navigation is **Home / Search / Playlists**.
- Home contains bounded recently played, a Liked Songs shortcut/count and a small selection of personal playlists. Shelves recover independently.
- Do not present all artists, albums or tracks as a top-level Library to browse. The server catalog is too large for that to be a useful starting point.
- Empty Search is a useful prompt and issues no match-all catalog request. Entering a query yields artists, albums and songs.
- Artist/album/track pages remain reachable intentionally from results, recent listening, playlists and credits. A context's complete playback queue is distinct from its visible result page.
- No fabricated recommendation engine, popularity, top tracks or radio. Personal recents are not Spotify recommendations or global popularity.

### Thumb-first search, without ten knobs

- Place the Search input in the **lower thumb zone**, above the persistent chrome when not editing, rather than copying a website's top search bar.
- While actively editing the query, **hide bottom navigation and unnecessary top toolbar chrome, including its blank reserved space**. Restore them when editing ends; a nonempty query alone is not editing. Keep query/results/scroll and playback intact across the transition.
- Keep the field reachable with the IME open. Let results use the space above it; do not cover the field, last result or transport with insets or retain invisible chrome spacers.
- Start with one query and sensibly ordered grouped results. Do not require users to tune type, scope and availability controls first.
- No persistent filter-chip strip, Include missing checkbox or small Previous/Next control cluster on the main search screen. Internal API filters can still support intentional detail views.
- Use bounded useful matches and generous progressive **See more** actions. Keep Artists → Albums → Songs ordering, but main Search has **no visible result-type title/count rows**. Use existing artwork geometry or small overlay icons to distinguish types without extra height; retain type in TalkBack context. This supersedes earlier guidance to keep visible search group labels.
- Show concise contextual loading and a clear retry for a failed/partial read without hiding healthy matches. When matches exist, omit a wall of per-empty-group messages. Recovery controls must remain generous and reachable.
- Stable exact/prefix/name matching can improve a bounded candidate set. Do not claim global relevance ranking from one alphabetically paged response, invent unsupported server sort parameters or scan the whole catalog on the phone. Coordinate backend ranking improvements if needed.

### Context without redundant titles

- Selected navigation and surrounding content often already explain where the user is. Avoid repeating `spofify`, Home, Search or Playlists as large generic headings/app bars.
- Remove decorative filler headings that push music and primary actions away from the user's hand.
- Keep meaningful **song, album, artist and playlist names**, necessary detail-group headers and action-dialog context. Main Search's type/count rows are the specific exception above, not a ban on identity or accessible labels.
- Empty chrome is not a solution: removing an app-bar title should not leave an unnecessary blank bar. Account and other necessary actions must remain discoverable.

### Android navigation is native

- Use system Back, edge Back and three-button Back. Do not duplicate them with in-app Back arrows or text buttons.
- Back order is contextual: dismiss IME first; then an open menu/dialog/sheet; then pop the current nested destination; at a root, allow normal Android behavior.
- Now Playing collapses through Back or its sheet gesture. Collapse/navigation never stops audio, resets position or replaces the queue.
- Preserve the preceding query, results, paging and scroll when returning from details or switching destinations. Returning is not a new search or full reset.
- Do not install competing BackHandlers that double-pop, swallow root navigation or jump behind an open sheet. Prefer platform/Compose navigation behavior; support predictive Back correctly where applicable.
- Previous/restart in the **music transport** is not navigation Back and remains a visible playback control. Cancel/Save in an editor are also distinct actions.

## Website lessons, adapted rather than copied

The live Go repository is **read-only**. Its [AGENTS shared UI standards](../../spofify-go/AGENTS.md#shared-ui-standards) and [frontend contract](../../spofify-go/docs/frontend.md#shared-music-item-ui-standard) establish reusable principles, not Android pixel values or a layout to clone. These are sibling-repository references, not files supplied by this checkout; verify affected contracts before relying on them.

| Concrete website lesson | Native application / what not to generate |
| --- | --- |
| Shared `mediaCard`/`--music-*` tokens, square repeated surfaces and artwork; reserved action slots even without Play ([music-item standard](../../spofify-go/docs/frontend.md#shared-music-item-ui-standard)) | Share Compose media primitives across screens, not per-view geometry. Keep square collections/rows/artwork and coherent focus/ripple surfaces; native circular artist avatars are the explicit artwork exception. Keep More geometry stable for unavailable items; track rows remove the inline Play/Pause slot entirely and reclaim its width. No random card shadows or floating pill rows. Android targets are **48dp**, not the website's 44px slots. |
| Compact grouped rows and one scrolling flow ([search](../../spofify-go/docs/frontend.md#library-search)) | One compact row rhythm with aligned art/title and **one inline credit/metadata line**. Do not turn every artist into a TextButton or vertical row. Artist typography is modest, not title-sized. Do not copy web heading/count rows, multi-column layouts, row heights or pagination knobs. |
| Whole-item padded focus/hit surface, independent listening actions ([music-item standard](../../spofify-go/docs/frontend.md#shared-music-item-ui-standard)) | Give the main row destination coherent Android focus/ripple feedback; track identity opens details, More retains deliberate Play now/Play next, and a physical-right swipe supplements Play next. Collection Play and transport remain separate deliberate targets. No fragmented metadata hover/ripple patches or nested competing click targets. Preserve every known artist/album ID through intentional details/More; missing IDs remain honest text. |
| Contextual loading, retained usable content, stable controls and reduced motion ([loading](../../spofify-go/docs/frontend.md#shared-loading-ui-standard)) | Shared native progress with accessible context; concise inline error/retry, not explanatory paragraphs or endless spinners. Keep geometry/focus through refresh and availability changes. Do not infer success, emptiness or identity from absent data. |
| Listening-first details; management disclosed separately; no fake content or redundant chrome ([collections](../../spofify-go/docs/frontend.md#personal-library-and-collection-navigation)) | One clear primary path with secondary capabilities in More/details. Backend capability inventory is not an on-screen button inventory. No fake recommendations or prose explaining obvious controls; retain necessary warnings, uncertainty and accessible alternatives. |

### Compact music geometry

- Use a common compact **two-line** media-row primitive for comparable list presentations: aligned square art (native artist avatars may be circular), title, **one inline credit/metadata line**, and trailing More. Track rows have no inline Play/Pause or reserved playback spacer; their titles use that reclaimed width. Intentional collection Play and player transport remain. Do not stack one button/line per artist, add an extra type line, or enlarge padding/font on each iteration to imply hierarchy.
- Reserve More geometry and bounded copy slots regardless of artist cardinality, missing artwork/audio or background refresh; never reserve the removed track playback slot. A large font scale may change the shared row pitch; it must change consistently, not only for multi-artist items. Long secondary credits can ellipsize, with complete names and all ID-backed actions available intentionally in details/More and accessibility.
- The mini-player is a **full-width flat dock**, not a floating rounded card with outer gutters/shadow. It shares the shell's inset budget and never obscures the last result.
- Improve information hierarchy before growing fonts, padding or chrome. Compact visuals must still retain **48dp** hit targets and readable text, not tiny buttons.

### Expanded Now Playing sheet

Use the familiar **sheet**, not a fixed-fullscreen replacement. The requirements below supersede older always-pinned transport and dedicated queue-disclosure guidance.

- Restore a native expanded sheet with a drag handle, deliberate collapse/dismiss and system Back. Keep its expanded viewport externally determined and independent of queue length/offset, playback progress, feedback and artwork completion. Do not substitute a fixed fullscreen player Dialog or reuse content-driven competing sheet anchors.
- On every deliberate opening, title **and credits**, seek, elapsed/duration and Previous / Play-Pause / Next fit the **initial view without scrolling**, including supported short/narrow, landscape and font2 windows. Preserve 48dp targets, production typography and Unicode fallback. Artwork and secondary controls/copy yield first; relevant errors remain visible without replacing the credits.
- Use one coherent parent lazy flow: core header, secondary information, Up next header and occurrence rows. Normal scrolling reveals extras/queue; after intentional scrolling the core may naturally go offscreen. No always-pinned primary requirement, mandatory dedicated queue screen/disclosure or nested queue scroller. Share the parent's list state/finite viewport with queue autoscroll and preserve exact occurrence keys.
- Handle system/gesture/IME insets exactly once. Dismiss underlying Search IME/focus on expansion; transient menus/IME dismiss before the sheet, then subsequent Back resumes underlying navigation without double-pop. Preserve underlying query/results/scroll and the flat mini-player.
- Expansion, scrolling, collapse, Back, resize and ordinary queue/progress publications are presentation only: never prepare, autoplay, stop, seek, replace or reorder playback. Preserve exact current occurrence, queue and elapsed continuity. Secondary capabilities remain intentional More actions.
- Regressions must prove genuine long-queue overflow and actual touch-driven index/offset movement, reach the terminal boundary and release, then observe stable sheet viewport, list offsets and visible terminal rows through many progress frames and delayed visible artwork completion. Do not require offscreen/disposed core bounds at the bottom. Distinguish finite overscroll/entry settling from continuous few-pixel oscillation; source theory is not proven native causality. Validate the production sheet's Back/insets, gestures, rendering, audio and background behavior on an isolated synthetic AVD; native-graphics component PNGs are development evidence only, not native acceptance.

## Established mobile implementation principles

### Hierarchy, reach and touch

- **JetBrains Mono is the app font**, bundled locally at regular/medium/semibold/bold weights. Apply the central `AppTypography` through MaterialTheme, retain the type hierarchy, and keep Android Unicode/emoji fallback. No runtime font service. The pinned upstream version, hashes and SIL OFL license are in `third_party/jetbrains-mono/`. Recheck wrapping and large-font layouts after font changes.
- Let artwork, musical identity and the primary listening action establish hierarchy. Put secondary actions in a contextual More menu or appropriate sheet, not five equally prominent buttons on every row.
- Use consistent spacing, alignment, typography and artwork proportions. Avoid visual changes unrelated to the requested behavior.
- Interactive targets should be at least **48dp** even when the icon is smaller. Provide separation that makes accidental destructive actions unlikely.
- Do not rely on color alone for liked/current/paused/error state. Use accessible names, state descriptions and stable control placement.
- Preserve readable contrast; normal text generally needs 4.5:1 and large text 3:1. Do not assume a mint/gray palette automatically passes.
- Support long and Unicode names, larger fonts and narrow/landscape windows. Wrap essential information; truncate secondary content deliberately. Never clip action labels or overlap controls to fit a fixed height.
- Respect status/navigation/gesture/IME insets under edge-to-edge Android. Avoid double-padding when both a Scaffold and child consume the same inset.
- Scroll content must be reachable above persistent controls. The keyboard should not turn sign-in or Search into a trap.

### Gestures and accessibility

- Use contextual gestures, not global horizontal interception: bottom mini-player artwork/identity swipe up expands, physical left/right performs Next/Previous; sheet swipe down; expanded-player Next/Previous gestures stay cover-only; row long press/More; optional queue enqueue/remove and dedicated reorder handles. The mini-player keeps Play/Pause and tap-to-expand, plus named accessible transport actions; only its visible Next button is removed.
- Preserve vertical list scrolling, seeking and Android edge Back. Use touch slop, direction/commit thresholds and at most one committed action per gesture.
- Music gestures need visible or accessible alternatives. Native system Back does **not** require a duplicate app Back button.
- Removing an upcoming occurrence is not deleting a library song or saved playlist entry. Offer occurrence-aware Undo only while it is still valid.
- Reordering uses a dedicated handle with accessible Move up/down actions. Do not make the entire row a drag trap.
- TalkBack must identify unlabeled icons and their state. Do not expose opaque IDs, implementation/debug data or private catalog paths as user-facing fallback labels.

### State, loading and navigation continuity

- Keep navigation/UI state separate from the service-owned player. Rotation/recomposition must not prepare a new queue or restart sound.
- Show bounded loading, useful empty states and explicit recovery. A timeout is not an empty library; a missing product is not perpetual loading.
- Preserve usable content and scroll during background refresh. A failed playlist shelf must not blank recents or the rest of Home.
- Debounce/cancel search and fence asynchronous results by query/filter/navigation generation and account/client identity. An old result must not overwrite a newer screen/account.
- Keep primary actions spatially stable during loading. Prevent duplicate writes without turning the whole app into a blocked screen unnecessarily.
- Confirm destructive saved-data actions. Revision conflicts and uncertain writes require reconciliation, not blind replay or optimistic false success.

### Music and background behavior

- Playback belongs to MediaSessionService. Screens are controllers, not owners of the audible player.
- Never autoplay on app launch/session restore, browsing, queue enqueue, player expansion or connection recovery. Explicit Play is intentional.
- Pause and collapse are normal listening controls. There is no ordinary **Close player** action; internal stop remains for logout, expiry, server change and safety.
- Full context playback must not be limited to the first visible page. Duplicate playlist occurrences retain identity; Up next reflects actual traversal/shuffle, not a mislabeled full timeline.
- Queue remove/reorder/clear must preserve the current audible occurrence and valid history semantics. Do not restart sound to refresh UI metadata.
- Use authenticated system metadata/artwork and the same exact-origin client for API, artwork and every audio/range request. Never hand a protected bare URL to an unauthenticated system loader.
- Selected remote output is not local audible playback/history. Advertise handoff only when exact prepared queue/position/history import and **all** app/system transport starts respect live ownership. A picker alone is not device parity.
- Test audio focus, notifications, lifecycle and supported media behavior on virtual devices where feasible. If a specific Bluetooth/headphone/codec or vendor behavior genuinely needs hardware, report the limitation and coordinate a targeted check through the user; do not require or access real phones for routine work.

### Privacy and connection UX

- Accept syntactically valid explicit `http://` and `https://` origins, including hostname and public-IP HTTP, without an HTTP warning, acknowledgement or opt-in on Login. HTTP is unencrypted; the backend may independently reject the transport or require an exact-origin allowlist. Preserve the chosen scheme, exact scheme/host/effective-port isolation and authenticated redirect refusal; never infer a scheme, classify hosts through DNS, downgrade or bypass TLS validation.
- Keep Login minimal: a modest Sign in heading, labeled Server URL / Username / Password, named password reveal/hide, Sign in, progress and concise necessary error/recovery. No marketing/tutorial prose, remember checkbox, saved-for/Use/Forget controls or font-license entry. The full bundled offline font notice remains accessible through the existing signed-in Account route; Login does not depend on the peer Profile feature.
- Automatically save one exact origin/authenticated username/unmodified password only after fully successful, still-current client/session authentication, separately encrypted with Android Keystore and excluded from backup/device transfer. Failed, partial, cancelled or stale authentication must not replace it. If saving fails after authentication, preserve the valid session and prior durable record and show a brief truthful failure-to-save notice.
- On first form display, autofill the latest valid encrypted successful record as one origin/username/password triple, not mixed with a later failed server preference. Preserve edits through recomposition/error/retry. Canonical-origin changes (including invalid addresses) or username edits clear mismatched passwords and reveal state; returning to an old identity must not resurrect a cleared password. Keep raw password whitespace intact and secrets out of saved UI state, StateFlow, parcels, URLs and logs.
- Sign out clears session authority and stops locally owned audio but retains the encrypted latest successful login; expiry also retains it for explicit re-authentication. Autofill never submits a password, starts playback or changes session-restoration/offline-retry behavior. Legacy remember flags, including false, must not gate future successful saves; corrupt encrypted data fails closed to usable manual login without plaintext fallback.
- Test only with synthetic credentials and content unless a real test account/server has been explicitly coordinated.

## Review and evidence gate

For each change, run the automated gate **and** exercise the current installed APK on an explicitly selected isolated Android virtual device. Capture and inspect affected screens and states; compare before/after when possible. Check narrow and large-font layouts, keyboard interaction, system Back, scrolling and relevant music gestures. Parent integration requires a combined native smoke pass after parallel agents finish.

Do not regenerate a baseline merely to accept a regression. Record device/API/viewport/font scale, APK revision, screenshots, exercised flows, problems and fixes. If native testing cannot run, completion is **blocked**, not silently replaced by Robolectric. Screenshots demonstrate rendering, not every hardware/vendor behavior. Routine development uses emulators; any exceptional real-device verification goes through the user, not direct agent access.

### Rendered-review questions — answer with evidence, not source assertions

For each affected layout, the implementer and an independent reviewer must inspect actual screenshots and record:

- At the same narrow viewport/font scale, how many complete music rows are visible before/after? If fewer, what necessary information or action justifies the cost?
- Are art/title/credit baselines, row pitch and action slots uniform for one/many artists, unavailable items and refresh states? Are metadata targets compact rather than stacked buttons, with rich identity actions still reachable?
- What is the total top/bottom chrome height with IME closed and open? Does active query editing actually remove navigation and empty toolbar space, keep the field reachable and restore chrome afterward?
- Does main Search omit visible type/count rows while keeping ordering, non-verbal type cues and accessible type context? Are partial errors recoverable without empty-group message walls?
- With long/Unicode credits and large fonts, do text, 48dp targets, More menus and last-row reachability remain usable? Is the full-width mini-player flat and unobscuring?
- Which inspected image paths show the after-state, and which taps/Back/scroll actions were exercised? If there is no after-render, report **blocked / not complete**, not “pass.”

The historical user-supplied baseline was **before-only evidence**, not an approved design or after-validation. Reported issues included visible Artists/Albums count rows, artist-dependent row height with stacked credits, unused top chrome and a rounded inset mini-player. If that baseline is retained, use it for comparison; this documentation cleanup supplies no approved after-render. See [the evidence status](testing.md#native-evidence-status).

## Android references

Consult current platform guidance when implementing behavior; these support the general principles, while the product-specific bottom search/no-filter/no-duplicate-title decisions above come from the user.

- [Navigation principles](https://developer.android.com/guide/navigation/principles)
- [Predictive Back](https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture)
- [Compose accessibility defaults and minimum targets](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)
- [Compose window insets](https://developer.android.com/develop/ui/compose/system/insets)
- [Adaptive layouts](https://developer.android.com/develop/ui/compose/layouts/adaptive)
- [State hoisting](https://developer.android.com/develop/ui/compose/state-hoisting)
- [Compose testing](https://developer.android.com/develop/ui/compose/testing)
- [Media3 background playback](https://developer.android.com/media/media3/session/background-playback)
