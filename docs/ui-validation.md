# Required native UI/UX validation

## Policy: virtual devices are the normal path

Every agent must follow `AGENTS.md`, [mobile-ux.md](mobile-ux.md) and the isolated [worktree/review workflow](worktree-review.md). A successful build, lint run or Robolectric test does **not** prove the app looks or behaves correctly.

**Normal isolated synthetic AVD validation is allowed.** Historical emulator failures and their one-run diagnostic STOP are not standing prohibitions. Current environment health has not been established by this documentation cleanup, and no native pass is implied; see [evidence status](testing.md#native-evidence-status). Synthetic in-process/loopback test fixtures are also allowed. This does not authorize external network diagnosis, live-backend access or bypassing denied permissions.

Use an isolated Android Virtual Device (AVD) with synthetic data. Agents must not connect to, install on or test against real phones. If a particular hardware/vendor behavior genuinely cannot be established virtually, describe the precise uncertainty and coordinate any exceptional check **through the user**. Real-device access is not a routine prerequisite for development.

Do not use personal AVD data, existing accounts, the live backend, production credentials or acquisition/admin endpoints. Disable wireless/mDNS and USB discovery. Always identify the one test AVD explicitly; do not use an unqualified `adb install`, `connectedAndroidTest` against arbitrary attached devices, or a device-selection fallback.

## Required gates

1. Assigned leads acquire BUILD (successful atomic `spofify-build.guard` mkdir, recorded owner token **and** exclusive `spofify-gradle.lock`) under the [guard/lease protocol](worktree-review.md#shared-resource-guards-and-advisory-leases), then run the automated gate from their own worktree (not shared build outputs). Unassigned helpers coordinate with their lead:

   ```sh
   GRADLE_USER_HOME=/var/home/lsm/repos/spofify-mobile/.tooling/gradle \
     ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug \
       --offline --no-daemon --console=plain
   ```

2. Run the isolated synthetic AVD journey against the APKs built from the same SHA in step 1. Use `--skip-build` because the runner's default build does not supply `--offline`; do not bypass the shared-cache lease or offline requirements. Release BUILD/MAIN first, then acquire standalone NATIVE (`spofify-native.guard` plus exclusive `spofify-native.lock`) under that same protocol to serialize the shared AVD/ADB state and fixed test ports; never nest NATIVE in BUILD or MAIN. Flock alone is insufficient across pi.lot Bash mounts; retain NATIVE until actual native processes stop and release only the token-matching own guard, preserving interrupted/uncertain ownership for explicit recovery:

   ```sh
   tools/ui/validate-native.sh --skip-build
   ```

   Verify recorded APK hashes match those just built. This is deliberate build reuse, not permission to validate stale artifacts.

3. **Open and inspect the resulting screenshots.** Source code, view trees and a passing instrumentation result are insufficient. Answer the [rendered-review questions](mobile-ux.md#rendered-review-questions--answer-with-evidence-not-source-assertions) with image paths and measurements/observations, compare before/after when a baseline exists, fix regressions and re-run. An independent read-only reviewer must inspect the affected renders and constraints; address non-nit findings and repeat review until clean.
4. Record the evidence and remaining limitations. Assigned leads land reviewed work in main under the standing conditional authority, serialized MAIN guard plus flock and clean-tree/exact-base fences in [worktree-review.md](worktree-review.md); temporary integration is optional staging only. The parent owns the combined automated/native smoke gate after parallel edits settle, not only separate component tests. Preserve dirty/ignored evidence and verify main content/patch accounting before authorized worktree/ref cleanup; see [worktree-review.md](worktree-review.md#evidence-accounting-and-non-forced-cleanup). Landing or cleanup never converts blocked AVD validation into a pass.

Do not change or regenerate a baseline just to approve a regression. Generic stock-control density is not an acceptable replacement for coherent music-first design.

## Isolated runner

`tools/ui/validate-native.sh`:

- Can build the debug app and instrumentation APK without changing dependency pins, but agents use `--skip-build` after the separately leased offline gate above.
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

- **Sign-in:** minimal Sign in heading, labeled Server URL / Username / Password, named reveal/hide, Sign in, progress and concise necessary errors/recovery; no marketing/tutorial prose, HTTP warning/consent, remember checkbox, saved-for/Use/Forget controls or font-license entry. Exercise blank/invalid origins and syntactically valid explicit HTTP/HTTPS, including hostname/public/private/IPv6 HTTP, using synthetic fixtures. A backend may independently reject transport; show refusal truthfully without scheme changes, redirect following or TLS bypass. Verify masked default/re-concealment, IME Next/Done with duplicate-submit guards, busy/error/retry reachability and normal IME Back at 320dp/font scale 2 with long values. Preserve edits through recomposition/error/retry and clear mismatched secrets/reveal state on canonical-origin or username changes.
- **Login persistence/privacy:** after successful sign-in, logout/relaunch/expiry autofills the latest valid encrypted origin/username/password triple together, even after a later failed attempt at another origin; no password auto-submit, playback or transport side effects. Failed/partial/cancelled/stale auth never overwrites it; legacy false remember flags cannot suppress the next successful save. Check corrupt/invalidated storage fails closed to manual login and a post-auth save failure preserves the valid session/prior durable record with a concise truthful notice. Secrets stay out of saved UI state, StateFlow, parcels, URLs, logs, plaintext preferences and backup/device transfer; capture only synthetic credential values. Session restoration/offline retry must not post a password. The bundled font’s complete offline notice must remain reachable through signed-in Profile → Credits and dismiss via native Back; subsequent Back returns Credits to Profile, while Profile root allows normal Android root Back. Pre-auth access is not required.
- **Home:** bounded personal shelves, independent loading/errors, usable artwork and spacing, Liked Songs and playlist navigation. No catalog-wide dump or invented recommendations.
- **Search:** empty-query prompt without catalog reads; lower-thumb query reachable with IME; hide bottom navigation and unnecessary/blank top toolbar while editing, restore on edit end without resetting results. Keep grouped ordering but **no visible result-type title/count rows**; type cues use existing art/small overlays without added height and retain accessible context. Generous See more and partial retry; no filter strip, tiny pagers or per-empty-group message wall alongside matches. Capture loading, empty and partial/error states.
- **Details and Playlists:** intentional entity/credit navigation; meaningful names rather than redundant generic headers; occurrence-aware actions; duplicate/unavailable items; editing/conflict recovery. Avoid walls of equally weighted buttons.
- **Native Back:** IME first, then menus/sheets, then nested screens. No duplicate app Back buttons. Preserve query/results/scroll and avoid double-pop. At a root, allow normal Android behavior.
- **Now Playing:** use the expanded native sheet specified in [mobile-ux.md](mobile-ux.md#expanded-now-playing-sheet), not a fixed-fullscreen Dialog or mandatory separate queue disclosure. On deliberate opening, identity **and credits**, seek/times and 48dp Previous / Play-Pause / Next fit the initial view without scrolling; artwork/secondary content yield first. Exercise shuffle/repeat, actual upcoming traversal, queue remove/Undo/reorder and accessible alternatives.
  - Verify one parent lazy flow containing the core header, secondary information, Up next and occurrence rows, with no nested queue scroller. Ensure the queue genuinely overflows: observe actual touch-driven index/offset movement, reach the terminal boundary and release. The sheet viewport, list offsets and visible terminal rows must remain stable through many progress frames and delayed visible artwork completion; the core may naturally be offscreen after intentional scrolling. Distinguish finite overscroll/entry settling from persistent oscillation. A fitting default queue is not evidence for this regression; temporary synthetic additions must preserve/restore fixture occurrence IDs/order and catalog totals.
  - Inspect short/narrow, landscape and large-font windows. Exercise the **production sheet** Back/inset path: expansion dismisses underlying Search IME/focus, transient menus/IME dismiss before the sheet, then subsequent Back resumes underlying navigation without double-pop. Preserve query/results/scroll and the flat mini-player.
  - Expansion, scrolling, collapse, Back, resize and ordinary queue/progress publications are presentation only: no prepare/autoplay/stop/seek/replace/reorder. Preserve exact occurrence, queue and elapsed continuity. Component screenshots and Robolectric dispatch tests are development evidence, **not native AVD approval**.
- **Navigation/lifecycle:** tabs, rotation/recreation and background/foreground do not autoplay or reset an active occurrence. Logout/expiry stop buffered output and stale work cannot resurrect it.
- **Device sheet:** open from Devices immediately left of Play/Pause in the empty dock, mini-player and expanded transport, never Profile. Verify disabled empty Play, independent 48dp targets, no identity gesture capture, and picker Back returning to the same mounted player/queue or underlying route. Opening must not connect/register, select output or issue playback commands. Verify truthful view-only/unavailable state without registration; correctly gated selected-output controls when registered. Exercise authority-fenced handoff with synthetic endpoints where applicable, including rejection/needs-tap paths and stale authority. Enabled source and a visible picker are not proof of native transfer; see [device-playback.md](device-playback.md).

Check shared compact two-line rows with aligned art/title plus one inline credit/metadata line: no stacked artist TextButtons, modest artist type, stable row/action geometry across artist counts, unavailable items and refresh. Artwork stays square except for native circular artist avatars. All rich ID-backed artist/album actions must remain reachable in details/More. Inspect the full-width flat mini-player, not a rounded inset card, and coherent whole-row focus/ripple feedback with stable More targets. Track rows have no inline Play/Pause slot; intentional collection Play and music transport remain separate controls.

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

The historical user-supplied image was inspected as a **before-only** baseline. Its recorded root-checkout location is listed in [evidence status](testing.md#native-evidence-status); verify it exists before referencing it. It is not an approved after-render or a successful native gate.

If an emulator cannot boot, instrumentation fails, a required flow is untested or screenshots were not inspected, report **UI validation blocked / not complete**, with the concrete reason. Never silently substitute unit tests or claim a visual pass. Ordinary emulator-validated work does not need an invented physical-device acceptance blocker.

## Why discovery is disabled

`224.0.0.251:5353` and `[ff02::fb]:5353` are mDNS multicast discovery destinations. Multicast discovery is unnecessary for a specifically selected local AVD; keep it disabled and do not bypass denied permissions. Disable discovery before starting a fresh dedicated ADB server; changing an environment variable does not reconfigure an already-running personal server.

ADB supports the disable switches in its upstream source: [USB initialization](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/client/main.cpp) and [mDNS enable check](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/client/mdns_utils.cpp). `ADB_MDNS_AUTO_CONNECT=0` alone disables auto-connect, **not discovery itself**.
