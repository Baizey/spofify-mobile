# Spofify for Android

Native Kotlin / Jetpack Compose client. The Android-free `:core` owns validated
HTTP/session contracts and server-authoritative playback; `:app` owns platform
storage, native audio, lifecycle and presentation.

Implemented surfaces include authentication, server-ranked Search, artist/album details,
playlists, Recently played, mini/full player, queue/output controls, waveform/lyrics,
and Settings. Full History insights and artist rankings remain deferred.
Settings → Offline mode switches the existing app to this account’s local music. It requires an explicit switch; network outages do not transfer playback ownership.
Keep 3 / 10 / 100 / 1000 distinct songs to look ahead 1 / 3 / 6 / 12 songs respectively.
Prefetch defaults to unmetered connections; requested streams are unrestricted. Settings →
Music storage separates offline playlist audio (including pending saves) from automatic
cache, and counts shared audio once. Clear automatic cache preserves playlist downloads;
bytes held by current playback may remain until that source closes. Save a fully playable
playlist from its More menu. Only a verified complete save appears in offline Playlists.
Online playback also prefers verified on-device audio for the selected track. Offline
History reads a previously observed online snapshot; device playback never records or
uploads history. Display lyrics are not saved. Going online retires local output and its
queue before validating the session and waiting for a fresh server snapshot.

Settings → Crossfade is off by default and applies to playback on this phone, including
manual offline mode. It overlaps natural transitions by up to five seconds. Short tracks
use less overlap, and a missing, unconfirmed, or late successor plays with the usual
transition. Skips, seeks, pauses, and changes to the upcoming track cancel the overlap.
Online crossfade can start loading the next track before the current one ends; it does not
change which device owns playback or which queue entry is reported before natural EOF.

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
[Settings appearance](docs/settings-appearance.md) describes Android’s fixed dark theme.
[Unified search](docs/search.md) records the subsequent search migration: the
server now owns matching and cross-kind ranking, rather than the reference fixture’s
local ranking heuristic. Search layout and bottom-oriented
results remain unchanged. [Artist discography](docs/artist-discography.md) records
the subsequent native grouping refinement: separate own Albums and Singles, with
Appears on collapsed at the bottom using backend release relationships.

The reference remains self-contained. Its rendered behavior check uses Node.js 24
and an already provisioned Playwright module plus matching Chromium headless shell:

```sh
node docs/reference/check.cjs "$PLAYWRIGHT_MODULE" "$CHROMIUM_HEADLESS_SHELL" "$PRIVATE_ARTIFACT_DIR"
```

Supply a private output directory for the browser profile, temporary state and
screenshots. The check does not download browsers, start a server or contact a
backend. It covers player geometry, lyrics, navigation, and row action alignment;
it is not a native Android or cross-browser acceptance test.

## Development

Open this directory in Android Studio. Use JDK 25, Android platform 37.0 and
build-tools 36.0.0. The version catalog pins AGP 9.5.1, Kotlin / Compose compiler
2.4.21. The wrapper pins Gradle 9.9.1. AGP built-in Kotlin consumes
the newer KGP loaded by the root JVM plugin; no second Android Kotlin plugin is
applied. Sources target Java 17; runtime minimum is Android 8 (API 26), target API 36.

The earlier 9.9.0 wrapper was used for unit tests, lint, APK assembly and
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
