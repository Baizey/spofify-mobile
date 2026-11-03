# Spofify Mobile

Native Android only: Kotlin, Jetpack Compose, Media3. No WebView, Node, Expo or management UI.

- `../spofify-go` is live and independently developed: **read-only**. Coordinate API changes before implementation.
- `:core` contains the JVM-only client, DTOs and contract tests; `:app` contains Android storage, UI and playback.
- Reuse the exact-origin authenticated OkHttp client for API, artwork and every audio/range request. Never credentials in URLs/logs; never authenticated redirects.
- Persist sessions and explicitly remembered sign-in details only with Android Keystore encryption. Never persist plaintext credentials or private catalog paths. Exclude all app data from backup/device transfer and images from disk caching.
- Browsing and Play must not call acquisition, download, source or administrative APIs.
- Audio belongs to MediaSessionService, not a screen. Never autoplay on startup/restoration.
- Run `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` with an Android SDK. Independently review changes before completion.

## Mobile product and interaction rules

Read `docs/mobile-ux.md` before changing a screen. Native Android UX is not a compressed website.

- Main destinations are **Home / Search / Playlists**. Home is bounded and personal; empty Search issues no match-all request. No global-catalog Library dump or fabricated recommendations.
- Put Search input in the **lower thumb zone**, above navigation/player chrome and reachable with the IME open. Use one query, well-ordered grouped results and generous progressive See more actions, not type/scope/availability filters or tiny paging knobs.
- Omit redundant generic app/screen headings when context is already clear. Keep meaningful music/entity names and result-group labels; avoid stock-button/form/debug density.
- Use Android system Back/edge gesture/three-button navigation; no duplicate in-app Back arrows/buttons. IME, menus and sheets dismiss before nested screens pop. Preserve query/scroll and playback. Music transport Previous remains a separate action.
- Respect 48dp targets, font scaling, contrast, gesture/safe-drawing/IME insets and responsive phone-sized layouts. Keep usable content during refresh; gestures supplement accessible music controls.

## Mandatory native UI/UX validation — every agent

**The user requires actual rendered-app validation. This is a completion gate, not an optional follow-up.** Unit tests, Robolectric semantics, compilation, lint and a built APK do not establish visual quality.

- **Virtual devices only. Never connect to, install on, target or test against a real phone.** Before declaring any app change complete, install and exercise the current APK on an explicitly selected isolated test AVD with synthetic fixtures. Do not use personal AVD data or an unapproved live account/server. Disable ADB wireless/mDNS discovery; target the emulator serial explicitly.
- Capture and actually inspect screenshots of affected flows, including their relevant loading, empty and error states. When changing presentation, capture a before/after comparison where a baseline is available. Do not approve UI from source code or accessibility trees alone.
- Smoke-test Sign-in, Home, query-driven Search, Playlists and Now Playing. Exercise the changed interactions with taps, scrolling, Back and relevant gestures. Verify the keyboard does not cover sign-in controls and that player collapse preserves playback/queue/position.
- Check a narrow phone viewport and large-font rendering for overflow, clipping, cramped controls and unreadable hierarchy. Preserve touch targets, contrast and visible/accessibility alternatives to gestures. Do not replace a coherent music-first design with dense stock-button/debug layouts merely to expose more features.
- Main navigation is **Home / Search / Playlists**. Home is bounded and personal; empty Search is prompt-only. Do not reintroduce a giant global-catalog Library landing page or fabricated recommendations.
- Record device/API/viewport, APK revision, flows exercised, screenshot paths, findings and fixes in the validation evidence. Use the workflow in `docs/ui-validation.md`; generated images/logs stay in ignored `.tooling/ui-testing/` unless intentionally approved as non-sensitive test assets.
- Visual regressions are blockers. Fix and re-capture before completion. If an emulator cannot run, explicitly report **UI validation blocked / not complete** with the reason; never silently substitute unit tests or claim the app was visually validated.
- Delegated UI implementers and independent reviewers must inspect the relevant rendered screenshots. The parent must run the combined native smoke pass after integrating parallel changes. Hardware-specific Bluetooth, codec/audio-focus and background behavior that emulators cannot establish must be reported as unverified; do not substitute a real device.
