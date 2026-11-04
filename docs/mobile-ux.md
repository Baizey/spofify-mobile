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

- Place the Search input in the **lower thumb zone**, above the navigation/mini-player area, not automatically at the top because that is common on the web.
- Keep the field reachable while the keyboard opens. Let results use the space above it; do not cover the field, last result or transport with IME/navigation insets.
- Start with one query and sensibly ordered grouped results. Do not require users to tune type, scope and availability controls first.
- No persistent filter-chip strip, Include missing checkbox or small Previous/Next control cluster on the main search screen. Internal API filters can still support intentional detail views.
- Use bounded useful matches and generous progressive **See more** actions. Group labels describe results; they are not an extra configuration panel.
- Stable exact/prefix/name matching can improve a bounded candidate set. Do not claim global relevance ranking from one alphabetically paged response, invent unsupported server sort parameters or scan the whole catalog on the phone. Coordinate backend ranking improvements if needed.

### Context without redundant titles

- Selected navigation and surrounding content often already explain where the user is. Avoid repeating `spofify`, Home, Search or Playlists as large generic headings/app bars.
- Remove decorative filler headings that push music and primary actions away from the user's hand.
- Keep meaningful **song, album, artist and playlist names**, useful result-group labels and action-dialog context. “No titles” is not a rule to remove identity or accessibility labels.
- Empty chrome is not a solution: removing an app-bar title should not leave an unnecessary blank bar. Account and other necessary actions must remain discoverable.

### Android navigation is native

- Use system Back, edge Back and three-button Back. Do not duplicate them with in-app Back arrows or text buttons.
- Back order is contextual: dismiss IME first; then an open menu/dialog/sheet; then pop the current nested destination; at a root, allow normal Android behavior.
- Now Playing collapses through Back or its sheet gesture. Collapse/navigation never stops audio, resets position or replaces the queue.
- Preserve the preceding query, results, paging and scroll when returning from details or switching destinations. Returning is not a new search or full reset.
- Do not install competing BackHandlers that double-pop, swallow root navigation or jump behind an open sheet. Prefer platform/Compose navigation behavior; support predictive Back correctly where applicable.
- Previous/restart in the **music transport** is not navigation Back and remains a visible playback control. Cancel/Save in an editor are also distinct actions.

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

- Use contextual gestures, not global horizontal interception: mini-player swipe up; sheet swipe down; cover-only Next/Previous; row long press/More; optional queue enqueue/remove and dedicated reorder handles.
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

- Permit configured trusted-network literal-IP HTTP as well as HTTPS. Show a clear HTTP risk warning; do not use an unexplained acknowledgement checkbox or disable TLS validation.
- Clearly label remembered sign-in. Save one exact origin/username/password only after successful current-client authentication, separately encrypted with Android Keystore and excluded from backup.
- Server-origin or username edits clear the form password. Do not persist passwords in saved UI state or log them. Password storage is never an automatic password-based sign-in on startup.
- Explain that Sign out clears the session but retains an opted-in remembered login; Forget saved login clears credentials and turns remembering off without destroying an active session.
- Test only with synthetic credentials and content unless a real test account/server has been explicitly coordinated.

## Review and evidence gate

For each change, run the automated gate **and** exercise the current installed APK on an explicitly selected isolated Android virtual device. Capture and inspect affected screens and states; compare before/after when possible. Check narrow and large-font layouts, keyboard interaction, system Back, scrolling and relevant music gestures. Parent integration requires a combined native smoke pass after parallel agents finish.

Do not regenerate a baseline merely to accept a regression. Record device/API/viewport/font scale, APK revision, screenshots, exercised flows, problems and fixes. If native testing cannot run, completion is **blocked**, not silently replaced by Robolectric. Screenshots demonstrate rendering, not every hardware/vendor behavior. Routine development uses emulators; any exceptional real-device verification goes through the user, not direct agent access.

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
