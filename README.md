# Spofify for Android

Spofify is an Android client for a running, self-hosted
[Spofify Server](https://github.com/Baizey/spofify-server). Listen to your own
music library or one shared with you through a listener account. The app runs
on **Android 8.0 (API 26) or newer**.

## Build and install from source

```sh
git clone https://github.com/Baizey/spofify-mobile.git
cd spofify-mobile
```

1. In Android Studio's SDK Manager, install **Android SDK Platform 37.0**,
   **Build-Tools 36.0.0**, and **Platform-Tools**. Set `sdk.dir` in
   `local.properties` to your SDK path. Set `JAVA_HOME` to your **JetBrains
   Runtime (JBR) 25** installation directory; the Gradle daemon is pinned to it.
2. Enable USB debugging, connect your device, and approve the debugging prompt.
   Build and install the debug app:

   ```sh
   ./gradlew :app:installDebug
   ```

   You can also select JBR 25 as the **Gradle JDK** in Android Studio and run
   the `app` configuration on your device.

   To build without installing, run `./gradlew :app:assembleDebug`. The APK is
   `app/build/outputs/apk/debug/app-debug.apk`; install it with `adb install`
   followed by that path. Updating an existing install requires the same signing
   key; use `adb install -r` for a manual update.
3. Open **spofify** and sign in with your server username and password. In
   **Server URL**, enter the server's [configured LAN or HTTPS address](https://github.com/Baizey/spofify-server#remote-and-local-network-access),
   such as `https://music.example.com`, not a loopback address or a dashboard
   path. Include the scheme and any non-default port. Prefer HTTPS.

Online playback requires [playback sync v3](https://github.com/Baizey/spofify-server/blob/main/docs/playback-sync-v3.md).
If you use a reverse proxy, it must preserve the configured Host and pass
WebSocket upgrades.

## Listening

- Search for songs, albums and artists, and browse album and artist pages.
- Create and edit manual playlists and see recently played tracks in **History**.
- Stream music, manage the queue, and switch playback to another connected
  Spofify device on the same account. The player shows waveforms and lyrics
  when available. **Settings → Crossfade** enables crossfade on this device.

### Offline listening

While online, open the **⋯** menu on a playlist with audio available for every
track and turn on **Save for offline**. Wait for its **Saved for offline**
indicator. Turn on **Settings → Offline mode** to search and play music on this
device. The app can also switch automatically after repeated failures to connect
for playback. Turn **Offline mode** off to reconnect.

Offline mode plays only saved or cached audio. Only fully downloaded playlists
appear in offline **Playlists**. Lyrics aren't saved. **History** shows a cached
snapshot; offline plays aren't recorded or synced. **Settings → Music storage**
lets you clear the automatic cache without removing saved playlists.

## Development

`:app` contains the Android UI and audio playback; `:core` contains the
Android-free server and playback contracts. The code targets Java 17 bytecode.
Run unit tests and lint from the repository root:

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug
```

`./gradlew :app:assembleRelease` produces
`app/build/outputs/apk/release/app-release-unsigned.apk`. Release signing is not
configured; sign the APK before installing it.

- **Client behavior:** [Authentication](docs/authentication.md),
  [search](docs/search.md), and [offline mode and reconnection](docs/manual-network-boundary.md).
- **UI design:** [Player layout](docs/player-layout.md) and the [interactive
  design prototype](docs/reference/index.html), which doesn't track every native change.
