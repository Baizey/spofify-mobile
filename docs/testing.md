# Validation

Read [mobile-ux.md](mobile-ux.md) and [ui-validation.md](ui-validation.md) before changing a screen. **Automated checks and rendered native validation are separate gates.**

## Automated gate

Install the API 37.0 compile platform; minimum Android remains API 26 and target SDK remains 36. Run:

```sh
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug \
  :app:assembleDebugAndroidTest :app:lintDebug --no-daemon --console=plain
```

On the provisioned local toolchain, prefix this with `GRADLE_USER_HOME="$PWD/.tooling/gradle"`. CI uses JDK 21; local validation uses JDK 25. APK paths can be under `outputs/` or `intermediates/`, depending on AGP; the native runner finds the actual artifacts.

Core tests use MockWebServer, never the live backend or real accounts. They cover exact origins/IP policy, opaque IDs, authentication/CSRF, cookies, redirect refusal, one-shot writes, session-generation races, cancellation, transport bounds, catalog paging/revisions and personal/derived collections.

App JVM/Robolectric tests cover encrypted storage and remembered-login behavior, stale-session fences, search/navigation/state, playlist builders, likes, queue/occurrence selection, playback coordination/progress, bundled typography and the full offline font-license asset/dialog. They are **not** native screenshot or physical-hardware tests.

The latest full gate passed **99 core + 156 app tests**, debug and instrumentation APK builds, and lint with zero errors. The font-license follow-up is included; APK inspection also verified all four font faces and the exact complete OFL notice. Re-run after changes; use the current XML reports rather than treating this snapshot as a permanent guarantee. Remaining warnings include pinned dependency/API deprecations and intentional private-network HTTP. The newest local gate log is `.tooling/parity-final-gate.log` (ignored).

## Required native AVD gate

```sh
tools/ui/validate-native.sh
```

The runner uses a dedicated synthetic AVD, disabled ADB mDNS/wireless discovery and USB transports, an explicitly selected loopback emulator transport, and a fixture server inside the emulator. Its native test also rejects a non-emulator before Activity launch or test writes. Agents must not target real phones, personal AVDs or live accounts. Do not use an unqualified `adb install` or arbitrary-device `connectedAndroidTest`.

**Open and actually inspect screenshots**, exercise the changed flows, fix regressions, and recapture. Passing instrumentation without image review is not visual approval. Record the APK hash/revision, image/API/viewport, font scale, actions, image paths, findings and reviewer. Compare before/after when a baseline exists and answer the [mobile rendered-review checklist](mobile-ux.md#rendered-review-questions--answer-with-evidence-not-source-assertions). An independent reviewer inspects affected screenshots; fix non-nit findings and re-review until clean.

### Current native status: blocked / not complete

No successful native screenshot pass has been completed. The supplied `/tmp/Spectacle.uTAxWI/Screenshot_20261104_011326.png` has been inspected as a before-only baseline; it is not after-validation or approval of the current APK. The installed emulator 37.2.13.0 and API 37.0 x86_64 image crash with a segmentation fault before app launch under software emulation; `/dev/kvm` is unavailable. A bounded attempt with disposable RAM-backed runtime state also failed. Latest diagnostic evidence: `.tooling/ui-testing/runs/20261103T235519Z-2/`. That attempt verified `MDNS_DISABLED` and `USB_DISABLED`; denied multicast discovery was not retried. It did not run the app or use credentials.

The next required step is to run the current APK and synthetic journey in a working isolated AVD environment, inspect the evidence, and fix any findings. This is not a request to substitute a real phone. See [ui-validation.md](ui-validation.md) for the complete workflow and safety checks.

## Native acceptance checklist

Use synthetic fixture accounts/library data, not the live backend.

1. Sign-in: valid/invalid origins, HTTPS and permitted trusted-network HTTP, risk warning, remembering, saved-login use/forget, masked password and reachable IME submission. Full JetBrains Mono license opens offline before authentication and dismisses via Android Back.
2. Home/Search: bounded personal shelves, real Liked Songs counts, no fabricated recommendations or empty-query catalog fetch; bottom query reachable with IME; hide bottom navigation and unnecessary/blank toolbar during active editing and restore on edit end. Keep grouped ordering without visible result-type title/count rows, retaining non-verbal/accessibility type cues. Generous progressive expansion and partial retry, without persistent filters, tiny paging controls or empty-group message walls.
3. Navigation: native Back dismisses IME, then menu/sheet, then nested route without double-pop. Tabs and detail returns preserve query/scroll/playback. No duplicate in-app Back arrows.
4. Details/playlists: meaningful entity names, credits, long/Unicode names, missing art/audio, duplicate/unavailable entries, manual editing and derived recipe labels/evaluation/error recovery. Keep useful content during refresh and stale-revision revalidation.
5. Playback: full/capped contexts and late selections; seek, previous/restart, next, shuffle, repeat and occurrence-aware queue operations/Undo. Collapse Now Playing with Back or downward dismissal without changing audio, position or queue. Browsing/enqueue/reconnection never starts audio.
6. Lifecycle/auth: rotation/recreation, leaving/backgrounding the Activity, notification/system media controls, no autoplay after restart; logout/expiry stops buffered output and stale work cannot affect another account/server. History requires actual progress, not preparation/seeking.
7. Device sheet: truthful read-only state and no remote mutation/transfer controls while native exact-handoff support is disabled. A picker is not handoff parity.
8. Accessibility/layout: narrow viewport, increased font scale, JetBrains Mono wrapping and Android Unicode/emoji fallback, contrast, 48dp targets, TalkBack labels, bottom/gesture/IME insets, last-row reachability and accessible alternatives to music gestures. Check complete visible-row counts, aligned compact art/title plus one inline credit/metadata line, modest artist type and uniform row/action geometry across artist counts, availability and refresh. No stacked artist buttons; preserve rich artist/album actions through details/More. Mini-player is full-width and flat, not a rounded floating card.
9. Storage/privacy: encrypted origin-bound sessions and opted-in credentials; no plaintext logs, backup/device transfer, audio cache or authenticated image disk cache. Every API/art/audio/range request remains on the exact-origin authenticated client; redirects fail safely.

## Hardware-specific limits

Emulators cannot establish every Bluetooth/vendor/codec/power-management behavior. Describe the precise unverified behavior and coordinate an exceptional check **through the user**, if needed; agents do not directly connect to or install on physical phones. Hardware access is not a routine prerequisite for ordinary emulator-validated work. No universal codec support or exact native cross-device handoff is claimed.
