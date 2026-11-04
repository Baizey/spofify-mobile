# Required native UI/UX validation

## Policy: virtual devices are the normal path

Every agent must follow `AGENTS.md` and [mobile-ux.md](mobile-ux.md). A successful build, lint run or Robolectric test does **not** prove the app looks or behaves correctly.

Use an isolated Android Virtual Device (AVD) with synthetic data. Agents must not connect to, install on or test against real phones. If a particular hardware/vendor behavior genuinely cannot be established virtually, describe the precise uncertainty and coordinate any exceptional check **through the user**. Real-device access is not a routine prerequisite for development.

Do not use personal AVD data, existing accounts, the live backend, production credentials or acquisition/admin endpoints. Disable wireless/mDNS and USB discovery. Always identify the one test AVD explicitly; do not use an unqualified `adb install`, `connectedAndroidTest` against arbitrary attached devices, or a device-selection fallback.

## Required gates

1. Run the automated gate:

   ```sh
   GRADLE_USER_HOME="$PWD/.tooling/gradle" \
     ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug \
       --no-daemon --console=plain
   ```

2. Build and run the native AVD journey:

   ```sh
   tools/ui/validate-native.sh
   ```

3. **Open and inspect the resulting screenshots.** Source code, view trees and a passing instrumentation result are insufficient. Answer the [rendered-review questions](mobile-ux.md#rendered-review-questions--answer-with-evidence-not-source-assertions) with image paths and measurements/observations, compare before/after when a baseline exists, fix regressions and re-run. An independent read-only reviewer must inspect the affected renders and constraints; address non-nit findings and repeat review until clean.
4. Record the evidence and remaining limitations. Parent integration requires a combined native smoke pass after parallel edits settle, not only separate component tests.

Do not change or regenerate a baseline just to approve a regression. Generic stock-control density is not an acceptable replacement for coherent music-first design.

## Isolated runner

`tools/ui/validate-native.sh`:

- Builds the current debug app and instrumentation APK without changing dependency pins.
- Uses only the dedicated `spofify_visual_test` AVD under ignored `.tooling/ui-testing/avd/`; it refuses an existing AVD without its ownership marker. Optional `UI_TEST_RAM_RUNTIME=1` instead creates and removes its own disposable `/dev/shm/spofify-ui.*` workspace; it never selects another AVD.
- Uses a dedicated ADB server on local port **5039**, with `ADB_MDNS=0`, `ADB_MDNS_AUTO_CONNECT=0`, `ADB_USB=0` and `ADB_EMU=0`. It verifies that ADB reports `MDNS_DISABLED` and `USB_DISABLED`.
- Refuses occupied test ports; creates its own emulator on console/transport ports **5560/5561** and targets only its explicit loopback transport. It checks `ro.kernel.qemu=1` and emulator hardware **before** settings changes, installation or app-data clearing.
- Disables camera passthrough, host audio and unnecessary virtual radio/rendering features. It does not enable physical-device transport or ask for multicast permission.
- Runs `dev.spofify.mobile.ui.NativeUiSmokeTest` against the real Activity, Android Keystore, networking and Media3. Its server and content are synthetic and run inside the AVD on loopback; no live account/server is involved.
- Pulls full-device `UiAutomation.takeScreenshot()` evidence and records APK hash, Git state, Android API, viewport and density. It shuts down only its own emulator/dedicated ADB server.

The runner requires installed Android command-line tools, emulator and a compatible system image. It does **not** download packages or alter personal AVDs automatically. Set `ANDROID_HOME` if not using the project-local SDK. `UI_TEST_SYSTEM_IMAGE` can choose another installed image package.

Hardware acceleration is preferred when available. Software emulation may be slow or unsupported for a particular image/renderer. `UI_TEST_BOOT_TIMEOUT` changes the bounded boot wait; `UI_TEST_GPU` selects a supported renderer. Do not endlessly retry startup failures or bypass a denied permission.

Useful diagnostics:

```sh
# Launch and capture sign-in only: NOT the full completion gate.
tools/ui/validate-native.sh --boot-smoke

# Reuse already-built APKs deliberately; evidence still records the installed APK hash.
tools/ui/validate-native.sh --skip-build
```

Android Gradle Plugin versions can place APKs under `outputs/` or `intermediates/`; the runner locates the newest matching actual artifacts. `--skip-build` is diagnostic, not evidence that stale APKs validate newer source changes.

In tool environments where processes/network namespaces end with each command, keep emulator startup, installation, instrumentation and evidence capture in **one invocation**. Backgrounding an emulator in one command and using ADB in another may not work.

## Native journey and manual inspection

The fixture journey is a starting point, not a substitute for inspecting a changed flow. Cover:

- **Sign-in:** blank/invalid origin, clear HTTP warning, explicitly labeled remembering, masked password, keyboard and visible submission controls. The bundled font’s complete offline license must open without an account and dismiss via native Back. Never save credentials in screenshots except synthetic test values.
- **Home:** bounded personal shelves, independent loading/errors, usable artwork and spacing, Liked Songs and playlist navigation. No catalog-wide dump or invented recommendations.
- **Search:** empty-query prompt without catalog reads; lower-thumb query reachable with IME; hide bottom navigation and unnecessary/blank top toolbar while editing, restore on edit end without resetting results. Keep grouped ordering but **no visible result-type title/count rows**; type cues use existing art/small overlays without added height and retain accessible context. Generous See more and partial retry; no filter strip, tiny pagers or per-empty-group message wall alongside matches. Capture loading, empty and partial/error states.
- **Details and Playlists:** intentional entity/credit navigation; meaningful names rather than redundant generic headers; occurrence-aware actions; duplicate/unavailable items; editing/conflict recovery. Avoid walls of equally weighted buttons.
- **Native Back:** IME first, then menus/sheets, then nested screens. No duplicate app Back buttons. Preserve query/results/scroll and avoid double-pop. At a root, allow normal Android behavior.
- **Now Playing:** identity/artwork/transport hierarchy, seek/shuffle/repeat, actual upcoming traversal, queue remove/Undo/reorder and accessible alternatives. System Back or downward dismissal collapses without restarting/stopping audio or changing the queue.
- **Navigation/lifecycle:** tabs, rotation/recreation and background/foreground do not autoplay or reset an active occurrence. Logout/expiry stop buffered output and stale work cannot resurrect it.
- **Device sheet:** truthful read-only/unsupported state until exact authority-fenced native handoff exists. A visible picker is not proof of playback transfer.

Check shared compact two-line rows with aligned art/title plus one inline credit/metadata line: no stacked artist TextButtons, modest artist type, stable row/action geometry across artist counts, unavailable items and refresh. Artwork stays square except for native circular artist avatars. All rich ID-backed artist/album actions must remain reachable in details/More. Inspect the full-width flat mini-player, not a rounded inset card, and coherent whole-row focus/ripple feedback with separate Play/More targets.

Inspect at least a narrow phone-sized viewport and increased font scale; include long/Unicode names. Typography uses bundled JetBrains Mono: inspect actual glyph fallback, wrapping and control widths rather than assuming the previous font’s layout still fits. Check clipping, touch targets, contrast, bottom/IME/gesture insets, last-row reachability, overlays and artwork proportions. Add landscape or another virtual size when the affected layout warrants it. Record what was actually tested rather than implying every Android version was covered.

## Evidence and completion report

Each run writes ignored evidence under:

```text
.tooling/ui-testing/runs/<timestamp>-<run-id>/
  evidence.md
  working-tree.txt
  adb-server-status.txt
  emulator.log
  instrumentation.log
  screenshots/
```

`.tooling/ui-testing/latest-run.txt` points to the newest attempt. A failed run can still contain useful partial screenshots. Generated logs/images are local test artifacts; do not commit private data or upload them automatically.

Record in `evidence.md` (or a linked review):

- APK SHA-256/revision and relevant local edits.
- AVD/image/API, viewport, density, font scale and renderer/acceleration.
- Flows/actions exercised and screenshots **actually inspected**.
- Visual/correctness findings, fixes, re-captured evidence and reviewer identity; include visible complete-row count, row/cardinality uniformity, IME/chrome budget and actionable-versus-metadata targets from the mobile UX checklist.
- Automated/native results and precise unverified claims.

The supplied `/tmp/Spectacle.uTAxWI/Screenshot_20261104_011326.png` was inspected as a **before-only** baseline. It is not an approved after-render or a successful native gate; see [current blocked status](testing.md#current-native-status-blocked--not-complete).

If an emulator cannot boot, instrumentation fails, a required flow is untested or screenshots were not inspected, report **UI validation blocked / not complete**, with the concrete reason. Never silently substitute unit tests or claim a visual pass. Ordinary emulator-validated work does not need an invented physical-device acceptance blocker.

## Why discovery is disabled

`224.0.0.251:5353` and `[ff02::fb]:5353` are mDNS multicast discovery destinations. The user's denied discovery attempts were unnecessary for a specifically selected local AVD. Do not request or retry them. Disable discovery before starting a fresh dedicated ADB server; changing an environment variable does not reconfigure an already-running personal server.

ADB supports the disable switches in its upstream source: [USB initialization](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/client/main.cpp) and [mDNS enable check](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/client/mdns_utils.cpp). `ADB_MDNS_AUTO_CONNECT=0` alone disables auto-connect, **not discovery itself**.
