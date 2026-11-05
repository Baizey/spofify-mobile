# Spofify Mobile

A native **Android** listener for your own [spofify-go](../spofify-go) server. Kotlin, Jetpack Compose and Media3—not a website wrapper, and no server-management screens.

## Current listener scope

- Enter your server URL and sign in with an existing Spofify account.
- **Home / Search / Playlists**: bounded personal shelves, Liked Songs, bottom-positioned query entry, grouped results and progressive See more actions. Empty Search does not fetch the global catalog. The [mobile UX contract](docs/mobile-ux.md) requires editing-focused chrome, compact inline credits, no main-Search type/count heading rows and a flat full-width mini-player; these are rendered acceptance requirements, not a claim of visual approval.
- Open artist/album/song details and play complete contexts, not just the visible page.
- Create/edit manual personal playlists and build/evaluate server-supported derived playlists.
- Persistent mini-player, expanded Now Playing, seeking, occurrence-aware queue editing, shuffle and repeat.
- MediaSessionService-owned audio with notification/system controls, audio focus, headphone-disconnect handling and progress-qualified listening history.
- Service-lifecycle playback-device registration, selected-output controls and exact-snapshot handoff are enabled in source; native runtime/audio/background acceptance remains unverified. See [device playback](docs/device-playback.md).
- Android Keystore-encrypted sessions and automatic remembering/autofill of the latest successful server/username/password, without automatic password sign-in or autoplay.
- Bundled **JetBrains Mono** typography, including its full offline font-license notice in the existing signed-in Account route, not on Sign-in. Provenance and SIL OFL license: [third_party/jetbrains-mono](third_party/jetbrains-mono/).

**This is an initial implementation, not a production release.** Automated checks and APK builds do not establish visual quality. Actual rendered AVD screenshots and native interactions are mandatory. Earlier isolated emulator attempts failed before app launch; those failures do not establish the current environment's condition. Normal isolated synthetic AVD validation is allowed, but no successful after-render is established by this documentation cleanup: **UI validation remains not complete**. See [docs/ui-validation.md](docs/ui-validation.md) and [docs/testing.md](docs/testing.md).

## Task contracts and review

Follow the assigned task specs, when present under `docs/specs/`, and the roles in [AGENT_BEHAVIOUR.md](AGENT_BEHAVIOUR.md). Deleted historical specs and board metadata are not required scaffolding. Assigned leads follow [exact-SHA review and leased self-integration](docs/worktree-review.md), never self-approval or parallel main writes. Blocked native validation is not a blanket source-work dependency.

## Run

Open this directory in Android Studio and install the **Android API 37.0 compile platform** (`platforms;android-37.0`). Minimum Android remains **8.0 / API 26** and target SDK remains **36**. The Gradle wrapper is pinned; local validation used JDK 25 and CI uses JDK 21.

```sh
# Set ANDROID_HOME to your SDK, or let Android Studio write local.properties.
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug \
  :app:assembleDebugAndroidTest :app:lintDebug

# Isolated synthetic AVD only: reuse the APKs just built, then inspect the captures.
tools/ui/validate-native.sh --skip-build
```

Agents must not target real phones, personal AVDs or live accounts. The runner disables ADB mDNS/wireless discovery and USB transports, verifies the selected virtual device, and never downloads a system image automatically. See [the native workflow](docs/ui-validation.md) for installed-image prerequisites and screenshot review.

Depending on AGP, the debug APK is under `app/build/outputs/apk/debug/` or `app/build/intermediates/apk/debug/`; the runner locates the actual artifact. Debug builds are not store releases; no release signing key is included.

The repository's local tooling convention uses ignored `.tooling/` directories; verify installed SDK/JDK prerequisites rather than assuming an old run's environment still exists. Agents use the absolute shared `GRADLE_USER_HOME=/var/home/lsm/repos/spofify-mobile/.tooling/gradle` with own-worktree outputs, `--offline --no-daemon` and the separate exclusive Gradle advisory lease described in [docs/worktree-review.md](docs/worktree-review.md); do not overlap builds.

## Connect your server

Enter an **origin**, for example `https://music.example.com` or `http://192.168.1.20:8080`. Do not append `/listen` or `/api`. URL credentials, paths, query strings and fragments are rejected.

- Prefer HTTPS with a certificate trusted by Android. Certificate verification is never disabled.
- Explicit HTTP origins are accepted for hostnames and public/private/loopback IPs as well as HTTPS; the selected scheme is preserved. HTTP exposes passwords, session traffic and media without encryption, but Login has no HTTP warning, acknowledgement or opt-in controls.
- Your server may independently reject HTTP transport or require its exact scheme/host/effective-port origin allowlist. Client admission is not a claim of deployed backend support; the app does not rewrite server configuration, silently downgrade or bypass TLS checks.
- A phone's `127.0.0.1` is the phone, not your development machine. Use its reachable LAN/Tailscale address. An emulator's `10.0.2.2` still needs to be an allowed origin on the server; the app cannot bypass Host checks.
- Use an existing listener or admin account. Both receive the same listener-only app; no Spotify credentials are needed here.
- Sessions expire on the server (currently 12 hours). Sign in again after expiry. Sign out before changing servers.
- Only a fully successful, still-current sign-in automatically replaces the single server/authenticated-username/password record, separately encrypted from the session using Android Keystore and excluded from backup/device transfer, saved UI state and logs. Failed, cancelled or stale attempts never replace it. A failure to save after successful authentication keeps the session and prior durable record and reports the save failure.
- The minimal Login form autofills all three fields together from that latest successful record, even after a failed attempt at another server. Edited values survive error/retry; changing canonical origin or username clears any mismatched form password. No remember checkbox or saved-login Use/Forget controls appear on Login, and autofill never submits a password automatically.
- Sign out clears the session and stops locally owned audio but retains the encrypted latest successful login; expiry likewise retains it for explicit re-authentication. Existing session restoration/offline retry is separate from password autofill and never autoplays.

## Structure

| Module | Responsibility |
| --- | --- |
| `:core` | JVM-only API client, DTOs, exact-origin policy, cookie/CSRF authentication and MockWebServer contract tests |
| `:app` | Compose UI/ViewModel, encrypted Android storage, authenticated image loader and Media3 service/controller |

One exact-origin OkHttp client supplies API calls, artwork and **every audio/range request**. Authenticated redirects are refused. Image disk/memory caches and session backup/device transfer are disabled. No offline audio cache is created.

## Deliberately not included yet

Offline downloads, server management/acquisition, Android Auto, persistent queue recovery after process death, and release/store packaging. Lyrics, waveform/analysis, similarity and provider-backed artist top tracks need approved backend contracts; see [backend coordination](docs/backend-feature-coordination.md). System media controls currently show text metadata without embedded artwork; in-app artwork is authenticated. The server streams original files without transcoding, so device codec support varies. Large playback contexts honor the server's 1,000-song cap. Duplicate playlist entries retain their occurrence identity, so selecting the same song twice in a playlist does not collapse its ordering.

## Backend coordination

**spofify-go is read-only for this project while it is being developed live.** This app consumes shipped APIs; it does not modify or launch that repository, run migrations, seed accounts, or call acquisition/admin endpoints. Coordinate any backend contract or configuration changes with its owner first. See [docs/api-contract.md](docs/api-contract.md).
