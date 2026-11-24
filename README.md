# Spofify for Android

Spofify plays music from a running, self-hosted Spofify Server. Ask whoever hosts
it for a server address your phone can reach and a Spofify account, not a Spotify
login. The app runs on **Android 8.0 (API 26) or newer**.

## Install

You can build and install the debug app from source below. If an official
`spofify-mobile-release.apk` is available on [GitHub Releases](https://github.com/Baizey/spofify-mobile/releases),
open it on your device and follow Android's install prompts. **Spofify Release**
is separate from the debug app; sign-in and downloads don't carry over.

The release app checks for newer APKs signed with the same certificate. When
**Settings → Install update** appears, pause playback on this device, then tap it
and confirm in Android. The debug app has no in-app updater.

### Build and install from source

```sh
git clone https://github.com/Baizey/spofify-mobile.git
cd spofify-mobile
```

1. In Android Studio's SDK Manager, install **Android SDK Platform 37.0**,
   **Build-Tools 36.0.0**, and **Platform-Tools**. Replace `sdk.dir` in
   `local.properties` with your SDK path and remove `.gradle/config.properties`,
   which contains a machine-specific JDK path. Set `JAVA_HOME` to your
   **JetBrains Runtime (JBR) 25** installation directory.
2. Enable USB debugging, connect your device, and approve the debugging prompt.
   Build and install the debug app:

   ```sh
   ./gradlew :app:installDebug
   ```

   In Android Studio, you can instead select JBR 25 as the **Gradle JDK** and run
   the `app` configuration on your device. To build without installing, run
   `./gradlew :app:assembleDebug`; the APK is
   `app/build/outputs/apk/debug/app-debug.apk`. To update a debug install
   manually, use `adb install -r` with an APK built with the same debug signing key.

## Sign in

Open **spofify** (debug) or **Spofify Release**. Enter the **Server URL**, such as
`https://music.example.com`, and sign in with your Spofify username and password.
Include the scheme and any non-default port, but no path. Prefer HTTPS; HTTP
sends credentials unencrypted. The app stores your password encrypted on this
device for autofill; signing out doesn't erase it.

Online playback and search require a working playback sync v3 connection. If you
use a reverse proxy, it must preserve the configured Host and pass WebSocket
upgrades. Online search also needs a configured Typesense index on the server.

## Listening

- Use **Search** to find tracks, albums, and artists in the server's library.
- Create and edit manual playlists and see recently played tracks in **History**.
- Manage the queue, control playback on another connected device signed in to
  the same account, or transfer playback with **Playback devices**.
- Open the player for waveforms and lyrics when available. Local playback
  supports Android media controls and **Settings → Crossfade**.

### Offline listening

Sign in online before going offline for the first time. While connected, open
the **⋯** menu on a playlist with playable audio for every track, turn on
**Save for offline**, and wait for the completed download icon. Turn on
**Settings → Offline mode** to search and play music on this device. The app can
also switch automatically after repeated playback connection failures. To
reconnect, turn **Offline mode** off or choose **Go online** if shown.

Offline mode plays only saved or cached audio. Only fully downloaded playlists
appear in **Playlists**, and you can't edit them offline. Waveforms and lyrics
aren't available. **History** shows a cached snapshot; offline plays aren't
recorded or synced. **Settings → Music storage** lets you clear the automatic
cache without removing saved playlists.

## Development

`:app` contains the Android UI and audio playback; `:core` contains the
Android-free server and playback contracts. The code targets Java 17 bytecode.
Run unit tests and lint from the repository root:

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug
```

`./gradlew :app:assembleRelease` produces
`app/build/outputs/apk/release/app-release-unsigned.apk` for the separate release
app. For signing and publishing to GitHub Releases, see [Mobile releases](docs/mobile-release.md).

- **Client behavior:** [Authentication](docs/authentication.md),
  [search](docs/search.md), and [offline mode and reconnection](docs/manual-network-boundary.md)
  (debug app).
- **UI design:** [Player layout](docs/player-layout.md) and the [interactive
  design prototype](docs/reference/index.html), which doesn't track every native change.
