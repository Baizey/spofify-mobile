# Spofify Mobile

A native **Android** listener for your own [spofify-go](../spofify-go) server. Kotlin, Jetpack Compose and Media3—not a website wrapper, and no server-management screens.

## First slice

- Enter your server URL and sign in with an existing Spofify account.
- Browse albums, artists and songs; search saved music with pagination.
- Return to recently played songs, listen to Liked songs and existing **manual personal playlists**.
- Play album/artist/playlist contexts, not just the currently visible page.
- Persistent mini-player, expanded Now Playing, seeking, queue selection, shuffle and repeat.
- MediaSessionService-owned audio with foreground notification, lock-screen controls, audio focus and headphone-disconnect handling.
- Like/unlike the current song; report listening history after actual playback progress.
- Android Keystore-encrypted sessions and optional remembered server/username/password for quicker testing and sign-in.

**This is an initial implementation, not a production release.** The API contract tests, Robolectric sign-in UI checks, debug APK build and Android lint can run locally. Physical-device playback/background/codec behavior still needs the checklist in [docs/testing.md](docs/testing.md). No live backend was changed or exercised during development.

## Run

Open this directory in Android Studio, install **Android SDK 36**, and run the `app` configuration on Android **8.0 / API 26 or later**. The Gradle wrapper is pinned. JDK 17+ is required; development validation used JDK 25.

Command line:

```sh
# Set ANDROID_HOME to your SDK, or let Android Studio write local.properties.
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The build produces **app/build/outputs/apk/debug/app-debug.apk**. Debug builds are for local testing, not store distribution. No release signing key is included.

On this development machine, ignored tooling was provisioned under `.tooling/`. To reuse it:

```sh
GRADLE_USER_HOME="$PWD/.tooling/gradle" ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
.tooling/android-sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Connect your server

Enter an **origin**, for example `https://music.example.com` or `http://192.168.1.20:8080`. Do not append `/listen` or `/api`. URL credentials, paths, query strings and fragments are rejected.

- Prefer HTTPS with a certificate trusted by Android. Certificate verification is never disabled.
- Trusted-LAN HTTP is limited to concrete private, loopback and Tailscale/shared-space IPs. A visible warning explains that HTTP exposes passwords and media; there is no separate acknowledgement checkbox.
- The URL's exact host/port must already be permitted by your server's origin configuration. The app does not rewrite that configuration.
- A phone's `127.0.0.1` is the phone, not your development machine. Use its reachable LAN/Tailscale address. An emulator's `10.0.2.2` still needs to be an allowed origin on the server; the app cannot bypass Host checks.
- Use an existing listener or admin account. Both receive the same listener-only app; no Spotify credentials are needed here.
- Sessions expire on the server (currently 12 hours). Sign in again after expiry. Sign out before changing servers.
- **Remember sign-in on this device** defaults on for convenient testing. After successful sign-in, one server/username/password is encrypted separately from the session using Android Keystore; it is never put in backup, saved UI state or logs. Changing the server origin or username clears the form password. It never signs in automatically with a password.
- Sign out clears the session but keeps that remembered login for next time. **Forget saved login** (on sign-in or in Account) deletes it and turns remembering off without ending an active session. Turn remembering off on shared devices.

## Structure

| Module | Responsibility |
| --- | --- |
| `:core` | JVM-only API client, DTOs, exact-origin policy, cookie/CSRF authentication and MockWebServer contract tests |
| `:app` | Compose UI/ViewModel, encrypted Android storage, authenticated image loader and Media3 service/controller |

One exact-origin OkHttp client supplies API calls, artwork and **every audio/range request**. Authenticated redirects are refused. Image disk/memory caches and session backup/device transfer are disabled. No offline audio cache is created.

## Deliberately not included yet

Offline downloads, playlist creation/editing, derived-playlist evaluation, server management/acquisition, Android Auto, cross-device player synchronization, persistent queue recovery after process death, and release/store packaging. System media controls currently show text metadata without embedded artwork; in-app artwork is authenticated. The server streams original files without transcoding, so device codec support varies. Large playback contexts honor the server's 1,000-song cap. Duplicate playlist entries retain their occurrence identity, so selecting the same song twice in a playlist does not collapse its ordering.

## Backend coordination

**spofify-go is read-only for this project while it is being developed live.** This app consumes shipped APIs; it does not modify or launch that repository, run migrations, seed accounts, or call acquisition/admin endpoints. Coordinate any backend contract or configuration changes with its owner first. See [docs/api-contract.md](docs/api-contract.md).
