# Spofify Mobile

Native Android only: Kotlin, Jetpack Compose, Media3. No WebView, Node, Expo or management UI.

- `../spofify-go` is live and independently developed: **read-only**. Coordinate API changes before implementation.
- `:core` contains the JVM-only client, DTOs and contract tests; `:app` contains Android storage, UI and playback.
- Reuse the exact-origin authenticated OkHttp client for API, artwork and every audio/range request. Never credentials in URLs/logs; never authenticated redirects.
- Persist sessions and explicitly remembered sign-in details only with Android Keystore encryption. Never persist plaintext credentials or private catalog paths. Exclude all app data from backup/device transfer and images from disk caching.
- Browsing and Play must not call acquisition, download, source or administrative APIs.
- Audio belongs to MediaSessionService, not a screen. Never autoplay on startup/restoration.
- Run `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` with the API 37.0 compile platform. Independently review changes before completion.
- Use the bundled **JetBrains Mono** family through central `AppTypography`; preserve the type hierarchy and Android Unicode/emoji fallback. Ship its full license in the APK and keep the offline notice accessible.

## Changes and commits

- Make focused, meaningful commits after coherent work and relevant checks; committing is part of the agent’s responsibility, not a user cleanup task. Report commit hashes and validation state. A checkpoint commit does not waive the native visual gate.
- Subagents may commit explicitly assigned chunks, but serialize Git/index operations with the parent and commit only the agreed scope. Do not sweep unrelated or concurrent changes into a commit.
- Preserve existing/concurrent commits and staged work. Do not reset, restore snapshots, rewrite/amend someone else’s history or push without explicit instruction.

## Mobile product and interaction rules

Read `docs/mobile-ux.md` before changing a screen. Native Android UX is not a compressed website.

- Main destinations are **Home / Search / Playlists**. Home is bounded and personal; empty Search issues no match-all request. No global-catalog Library dump or fabricated recommendations.
- Put Search input in the **lower thumb zone** and keep it reachable with the IME open. While actively editing the query, hide bottom navigation and unnecessary top toolbar chrome (including its reserved blank space); restore them when editing ends. Keep one query, grouped ordering and generous progressive See more/retry actions, not filters or tiny paging knobs.
- Main Search has **no visible result-type headings or count rows**. Convey type through existing artwork geometry/small overlay icons without adding height, retaining accessible type context. Meaningful entity titles and necessary detail-group headers remain allowed.
- Use reusable compact **two-line** media rows: aligned artwork/title plus **one inline credit/metadata line**, modest artist typography and reserved action geometry. Artist count, unavailable audio and refresh must not change row pitch at a given font scale; no stacked artist TextButtons. Keep rich ID-backed artist/album navigation in intentional details/More, not a capability inventory on every row.
- The mini-player is a **full-width flat dock**, not a rounded floating card. Repeated rows, collections, artwork and focus/feedback surfaces are square, with a native circular **artist-avatar** exception; individual controls retain appropriate shapes. Avoid arbitrary shadows, pills, padding stacks and empty toolbar spacers. See the website-to-native lessons in `docs/mobile-ux.md`.
- Use Android system Back/edge gesture/three-button navigation; no duplicate in-app Back arrows/buttons. IME, menus and sheets dismiss before nested screens pop. Preserve query/scroll and playback. Music transport Previous remains a separate action.
- Respect 48dp targets, font scaling, contrast, gesture/safe-drawing/IME insets and responsive phone-sized layouts. Keep usable content during refresh; gestures supplement accessible music controls.

## Mandatory native UI/UX validation — every agent

**The user requires actual rendered-app validation. This is a completion gate, not an optional follow-up.** Unit tests, Robolectric semantics, compilation, lint and a built APK do not establish visual quality.

- **Use virtual devices for normal development and validation. Agents must not connect to, install on or target real phones.** Exercise the current APK on an explicitly selected isolated test AVD with synthetic fixtures. Any exceptional real-hardware check must be explicitly coordinated **through the user**; do not make physical-device access a routine prerequisite. Do not use personal AVD data or unapproved live accounts/servers. Disable ADB wireless/mDNS discovery and USB transports; target the emulator serial explicitly.
- Capture and actually inspect screenshots of affected flows, including their relevant loading, empty and error states. When changing presentation, capture a before/after comparison where a baseline is available. Do not approve UI from source code or accessibility trees alone.
- Smoke-test Sign-in, Home, query-driven Search, Playlists and Now Playing. Exercise the changed interactions with taps, scrolling, Back and relevant gestures. Verify the keyboard does not cover sign-in controls and that player collapse preserves playback/queue/position.
- Check a narrow phone viewport and large-font rendering for overflow, clipping, cramped controls and unreadable hierarchy. Preserve touch targets, contrast and visible/accessibility alternatives to gestures. Do not replace a coherent music-first design with dense stock-button/debug layouts merely to expose more features.
- Main navigation is **Home / Search / Playlists**. Home is bounded and personal; empty Search is prompt-only. Do not reintroduce a giant global-catalog Library landing page or fabricated recommendations.
- Record device/API/viewport, APK revision, flows exercised, screenshot paths, findings and fixes in the validation evidence. Use the workflow in `docs/ui-validation.md`; generated images/logs stay in ignored `.tooling/ui-testing/` unless intentionally approved as non-sensitive test assets.
- Visual regressions are blockers. Fix and re-capture before completion. If an emulator cannot run, explicitly report **UI validation blocked / not complete** with the reason; never silently substitute unit tests or claim the app was visually validated.
- Answer the rendered-review checklist in `docs/mobile-ux.md` using actual screen evidence: visible row count, row/cardinality uniformity, IME/chrome budget, large-font/long credits and actionable versus metadata targets. A before-only screenshot is not an approved after-render.
- Delegated UI implementers and independent reviewers must inspect the relevant rendered screenshots. The parent must run the combined native smoke pass after integrating parallel changes. Report genuinely hardware-specific behavior that emulators cannot establish as unverified and coordinate any needed check through the user. This is not a reason to block ordinary emulator-validated development or silently substitute a real phone.
