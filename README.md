# Spofify Mobile

A native **Android** listener for your own [spofify-go](../spofify-go) server. Kotlin, Jetpack Compose and Media3—not a website wrapper, and no server-management screens.

## Current listener scope

- Enter your server URL and sign in with an existing Spofify account.
- **Home / Search / Playlists**: bounded personal shelves, Liked Songs, bottom-positioned query entry, grouped results and progressive See more actions. Empty Search does not fetch the global catalog. The [mobile UX contract](docs/mobile-ux.md) requires editing-focused chrome, compact inline credits, no main-Search type/count heading rows and a flat full-width mini-player; these are rendered acceptance requirements, not a claim of visual approval.
- Open artist/album/song details and play complete contexts, not just the visible page.
- Create/edit manual personal playlists and build/evaluate server-supported derived playlists.
- Persistent mini-player, expanded Now Playing, seeking, occurrence-aware queue editing, shuffle and repeat.
- MediaSessionService-owned audio with notification/system controls, audio focus, headphone-disconnect handling and progress-qualified listening history.
- Read-only playback-device visibility; native output registration, remote control and exact handoff remain disabled until the native authority boundary is complete.
- Android Keystore-encrypted sessions and optional remembered server/username/password, without automatic password sign-in or autoplay.
- Bundled **JetBrains Mono** typography, including its full offline font-license notice on Sign-in and in Account. Provenance and SIL OFL license: [third_party/jetbrains-mono](third_party/jetbrains-mono/).

**This is an initial implementation, not a production release.** Automated checks and APK builds do not establish visual quality. Actual rendered AVD screenshots and native interactions are mandatory; the current environment's emulator crashes before app launch, so **UI validation is blocked / not complete**. The supplied screenshot was inspected as a before-only baseline; no after-render is approved. See [docs/ui-validation.md](docs/ui-validation.md) and [docs/testing.md](docs/testing.md). No live backend was changed or exercised.

## Run

Open this directory in Android Studio and install the **Android API 37.0 compile platform** (`platforms;android-37.0`). Minimum Android remains **8.0 / API 26** and target SDK remains **36**. The Gradle wrapper is pinned; local validation used JDK 25 and CI uses JDK 21.

```sh
# Set ANDROID_HOME to your SDK, or let Android Studio write local.properties.
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug \
  :app:assembleDebugAndroidTest :app:lintDebug

# Isolated synthetic AVD only: build, boot, install, exercise and capture evidence.
tools/ui/validate-native.sh
```

Agents must not target real phones, personal AVDs or live accounts. The runner disables ADB mDNS/wireless discovery and USB transports, verifies the selected virtual device, and never downloads a system image automatically. See [the native workflow](docs/ui-validation.md) for installed-image prerequisites and screenshot review.

Depending on AGP, the debug APK is under `app/build/outputs/apk/debug/` or `app/build/intermediates/apk/debug/`; the runner locates the actual artifact. Debug builds are not store releases; no release signing key is included.

On this machine, ignored SDK and Gradle tooling live under `.tooling/`. Prefix the Gradle command with `GRADLE_USER_HOME="$PWD/.tooling/gradle"` to reuse that cache.

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

Offline downloads, server management/acquisition, Android Auto, exact native cross-device playback handoff, persistent queue recovery after process death, and release/store packaging. Lyrics, waveform/analysis, similarity and provider-backed artist top tracks need approved backend contracts; see [backend coordination](docs/backend-feature-coordination.md). System media controls currently show text metadata without embedded artwork; in-app artwork is authenticated. The server streams original files without transcoding, so device codec support varies. Large playback contexts honor the server's 1,000-song cap. Duplicate playlist entries retain their occurrence identity, so selecting the same song twice in a playlist does not collapse its ordering.

## Backend coordination

**spofify-go is read-only for this project while it is being developed live.** This app consumes shipped APIs; it does not modify or launch that repository, run migrations, seed accounts, or call acquisition/admin endpoints. Coordinate any backend contract or configuration changes with its owner first. See [docs/api-contract.md](docs/api-contract.md).
