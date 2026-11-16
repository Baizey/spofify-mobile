# Spofify for Android

Native Kotlin / Jetpack Compose client. The Android-free `:core` owns validated
HTTP/session contracts and server-authoritative playback; `:app` owns platform
storage, native audio, lifecycle and presentation.

Implemented surfaces include authentication, server-ranked Search, artist/album details,
playlists, Recently played, mini/full player, queue/output controls, waveform/lyrics,
and Settings appearance. Full History insights and artist rankings remain deferred.
Offline storage, downloads and local-only playback are not yet enabled; API preparation
must not be mistaken for saved or playable local audio.

## UI specification

The agreed, iterated visual and interaction specification is
[the interactive wireframe](docs/reference/index.html). Open it directly in a browser;
it is self-contained and works offline. Its final rendered screens and touch behavior
are the specification, not suggestions. The file contains cumulative renderer overrides,
so earlier function definitions alone do not describe the final UI. Its synthetic music,
accounts and playback are fixtures, not production API contracts.

The subsequently approved player changes are recorded in
[player layout](docs/player-layout.md): album navigation above artwork, fixed-height
scrollable lyrics at the 13.2sp album/artist scale with tap-to-open text and no
menu, lyric icon or scroll hint, compact
identity/seek spacing, and time labels aligned with Shuffle and Repeat.
[Settings appearance](docs/settings-appearance.md) records palette ownership and
persistence behavior. The interactive reference now includes these approved
native refinements too. [Unified search](docs/search.md) records the subsequent
search migration: the server now owns matching and cross-kind ranking, rather than
the reference fixture’s local ranking heuristic. Search layout and bottom-oriented
results remain unchanged.

The reference remains self-contained. Its rendered behavior check uses Node.js 24
and an already provisioned Playwright module plus matching Chromium headless shell:

```sh
node docs/reference/check.cjs "$PLAYWRIGHT_MODULE" "$CHROMIUM_HEADLESS_SHELL" "$PRIVATE_ARTIFACT_DIR"
```

Supply a private output directory for the browser profile, temporary state and
screenshots. The check does not download browsers, start a server or contact a
backend. It covers player geometry/lyrics/navigation, palette stability and row
action alignment; it is not a native Android or cross-browser acceptance test.

## Development

Open this directory in Android Studio. Use JDK 25, Android platform 37.0 and
build-tools 36.0.0. The version catalog pins AGP 9.3.1, Kotlin / Compose compiler
2.4.21. The wrapper retains Gradle 9.9.0 from main. AGP built-in Kotlin consumes
the newer KGP loaded by the root JVM plugin; no second Android Kotlin plugin is
applied. Sources target Java 17; runtime minimum is Android 8 (API 26), target API 36.

The retained 9.9.0 wrapper has been used for unit tests, lint, APK assembly and
emulator checks during native integration. Those checks do not establish every
upstream toolchain combination, deployment readiness or complete feature acceptance.
Keep toolchain upgrades separate from feature changes.

On an environment with these dependencies legitimately provisioned:

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon
```

Use `--offline` only when dependencies and the wrapper distribution are already
provisioned; it does not prevent wrapper bootstrap. Independent builds need separate
writable Gradle homes and temporary state. No cache repair, download workaround or
toolchain installation is provided by this repository.

Debug/release are standard Android Studio build types, both with application ID
`dev.spofify.mobile`. Release signing is not configured here. Debug signing is the
normal Android tooling default. This does not establish compatibility with an
already installed application's signature; do not uninstall, clear data, replace keys
or change signing material to work around an incompatibility.

## Contracts

See [authentication](docs/authentication.md) for transport, state ownership and
installed storage compatibility, and [unified search](docs/search.md) for ranked
snapshots, cursor continuation and typed search failures. JetBrains Mono is bundled from the previously
approved public [JetBrains Mono 2.304](https://github.com/JetBrains/JetBrainsMono/releases/tag/v2.304) source; its complete OFL is in
`app/src/main/assets/licenses/JetBrainsMono-OFL.txt` and locally accessible from
Settings → Credits → Font license, as well as Account.
Fonts retain the approved existing regular/medium/semibold/bold bytes.

Unit/contract checks do not establish rendered UI, Keystore behavior on a device,
installation compatibility or real-server operation.
