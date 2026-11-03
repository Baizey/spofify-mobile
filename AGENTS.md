# Spofify Mobile

Native Android only: Kotlin, Jetpack Compose, Media3. No WebView, Node, Expo or management UI.

- `../spofify-go` is live and independently developed: **read-only**. Coordinate API changes before implementation.
- `:core` contains the JVM-only client, DTOs and contract tests; `:app` contains Android storage, UI and playback.
- Reuse the exact-origin authenticated OkHttp client for API, artwork and every audio/range request. Never credentials in URLs/logs; never authenticated redirects.
- Persist sessions and explicitly remembered sign-in details only with Android Keystore encryption. Never persist plaintext credentials or private catalog paths. Exclude all app data from backup/device transfer and images from disk caching.
- Browsing and Play must not call acquisition, download, source or administrative APIs.
- Audio belongs to MediaSessionService, not a screen. Never autoplay on startup/restoration.
- Run `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` with an Android SDK. Independently review changes before completion.

## Mandatory native UI/UX validation — every agent

**The user requires actual rendered-app validation. This is a completion gate, not an optional follow-up.** Unit tests, Robolectric semantics, compilation, lint and a built APK do not establish visual quality.

- Before declaring any app change complete, install and exercise the current APK on an explicitly selected Android emulator or authorized test phone. Use a fresh, isolated test AVD and synthetic fixtures; never another person's AVD data or an unapproved live account/server.
- Capture and actually inspect screenshots of affected flows, including their relevant loading, empty and error states. When changing presentation, capture a before/after comparison where a baseline is available. Do not approve UI from source code or accessibility trees alone.
- Smoke-test Sign-in, Home, query-driven Search, Playlists and Now Playing. Exercise the changed interactions with taps, scrolling, Back and relevant gestures. Verify the keyboard does not cover sign-in controls and that player collapse preserves playback/queue/position.
- Check a narrow phone viewport and large-font rendering for overflow, clipping, cramped controls and unreadable hierarchy. Preserve touch targets, contrast and visible/accessibility alternatives to gestures. Do not replace a coherent music-first design with dense stock-button/debug layouts merely to expose more features.
- Main navigation is **Home / Search / Playlists**. Home is bounded and personal; empty Search is prompt-only. Do not reintroduce a giant global-catalog Library landing page or fabricated recommendations.
- Record device/API/viewport, APK revision, flows exercised, screenshot paths, findings and fixes in the validation evidence. Use the workflow in `docs/ui-validation.md`; generated images/logs stay in ignored `.tooling/ui-testing/` unless intentionally approved as non-sensitive test assets.
- Visual regressions are blockers. Fix and re-capture before completion. If an emulator/device cannot run, explicitly report **UI validation blocked / not complete** with the reason; never silently substitute unit tests or claim the app was visually validated.
- Delegated UI implementers and independent reviewers must inspect the relevant rendered screenshots. The parent must run the combined native smoke pass after integrating parallel changes. Physical-device Bluetooth, codec/audio-focus and background acceptance remain additional release checks.
