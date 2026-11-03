# Spofify Mobile

Native Android only: Kotlin, Jetpack Compose, Media3. No WebView, Node, Expo or management UI.

- `../spofify-go` is live and independently developed: **read-only**. Coordinate API changes before implementation.
- `:core` contains the JVM-only client, DTOs and contract tests; `:app` contains Android storage, UI and playback.
- Reuse the exact-origin authenticated OkHttp client for API, artwork and every audio/range request. Never credentials in URLs/logs; never authenticated redirects.
- Persist sessions only with Android Keystore encryption. Never persist passwords or private catalog paths. Exclude sessions from backup and image disk caching.
- Browsing and Play must not call acquisition, download, source or administrative APIs.
- Audio belongs to MediaSessionService, not a screen. Never autoplay on startup/restoration.
- Run `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` with an Android SDK. Independently review changes before completion.
